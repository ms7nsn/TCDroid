package com.tcdroid;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputLayout;
import com.tcdroid.core.Msg;
import com.tcdroid.core.TcVolume;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_OPEN = 1, REQ_KEYS = 2;

    private Uri container;
    private String containerName = "";
    private final List<Uri> keyfiles = new ArrayList<>();

    private TextView status, containerView, keysView;
    private TextInputLayout pw;
    private MaterialCheckBox backup;
    private MaterialButton btnUnlock, btnFiles, btnLock, btnChange;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.syncCoreLanguage(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle(R.string.app_name);
        tb.setSubtitle(R.string.subtitle);
        MenuItem mi = tb.getMenu().add(0, 1, 0, R.string.settings);
        mi.setIcon(R.drawable.ic_settings);
        mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        tb.setOnMenuItemClickListener(item -> { startActivity(new Intent(this, SettingsActivity.class)); return true; });
        page.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout root = Ui.column(this, 16);

        LinearLayout cStatus = Ui.card(this, root);
        status = Ui.headline(this, "");
        cStatus.addView(status);
        btnFiles = Ui.button(this, getString(R.string.open_in_files), true, v -> openInFiles());
        btnLock = Ui.button(this, getString(R.string.lock), false, v -> { Session.lock(); refresh(); });
        btnChange = Ui.button(this, getString(R.string.change_password), false, v -> changePassword());
        Ui.add(cStatus, btnFiles, btnChange, btnLock);

        LinearLayout cOpen = Ui.card(this, root);
        Ui.add(cOpen, Ui.button(this, getString(R.string.choose_container), false, v -> pick(REQ_OPEN, false)));
        containerView = Ui.body(this, "");
        cOpen.addView(containerView);
        pw = Ui.input(this, getString(R.string.password), true, false);
        Ui.add(cOpen, pw);
        Ui.add(cOpen, Ui.button(this, getString(R.string.keyfiles), false, v -> pick(REQ_KEYS, true)));
        keysView = Ui.small(this, "");
        cOpen.addView(keysView);
        Ui.add(cOpen, Ui.button(this, getString(R.string.remove_keyfiles), false, v -> { keyfiles.clear(); refresh(); }));
        backup = Ui.check(this, getString(R.string.use_backup));
        Ui.add(cOpen, backup);
        btnUnlock = Ui.button(this, getString(R.string.unlock), true, v -> unlock());
        Ui.add(cOpen, btnUnlock);

        Ui.add(root, Ui.button(this, getString(R.string.create_volume), false, v -> startActivity(new Intent(this, CreateActivity.class))));
        root.addView(Ui.small(this, getString(R.string.main_hint)));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        boolean un = Session.isUnlocked();
        status.setText(!un ? getString(R.string.locked)
                : getString(Session.isHidden() ? R.string.unlocked_hidden : R.string.unlocked, Session.name()));
        containerView.setText(container == null ? getString(R.string.no_container) : containerName);
        keysView.setText(keyfiles.isEmpty() ? getString(R.string.no_keyfiles) : getString(R.string.n_keyfiles, keyfiles.size()));
        btnUnlock.setEnabled(!un && container != null);
        int vis = un ? android.view.View.VISIBLE : android.view.View.GONE;
        btnFiles.setVisibility(vis);
        btnLock.setVisibility(vis);
        btnChange.setVisibility(vis);
    }

    private void pick(int req, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_OPEN && data.getData() != null) {
            container = data.getData();
            containerName = Ui.displayName(this, container);
            try {
                getContentResolver().takePersistableUriPermission(container,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException ignored) { }
        } else if (req == REQ_KEYS) {
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) keyfiles.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) keyfiles.add(data.getData());
        }
        refresh();
    }

    private void unlock() {
        final String pass = Ui.val(pw);
        final boolean useBackup = backup.isChecked();
        final Uri c = container;
        final String name = containerName;
        final List<Uri> keys = new ArrayList<>(keyfiles);
        status.setText(R.string.unlocking);
        btnUnlock.setEnabled(false);
        new Thread(() -> {
            String err = null;
            try {
                byte[] cred = Session.credentials(this, pass, keys);
                Session.unlock(this, c, name, cred, useBackup);
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String msg = err;
            runOnUiThread(() -> {
                if (msg == null) Ui.set(pw, ""); else Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private void openInFiles() {
        Uri root = DocumentsContract.buildRootUri(TcDocumentsProvider.AUTHORITY, TcDocumentsProvider.ROOT_ID);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, R.string.open_files_hint, Toast.LENGTH_LONG).show(); }
    }

    private void changePassword() {
        LinearLayout l = Ui.column(this, 16);
        final TextInputLayout a = Ui.input(this, getString(R.string.new_password), true, false);
        final TextInputLayout c = Ui.input(this, getString(R.string.confirm), true, false);
        Ui.add(l, a, c, Ui.small(this, keyfiles.isEmpty() ? getString(R.string.no_keys_applied)
                : getString(R.string.keys_applied, keyfiles.size())));
        new AlertDialog.Builder(this).setTitle(R.string.change_password).setView(l)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.change, (d, w) -> {
                    if (!Ui.val(a).equals(Ui.val(c))) {
                        Toast.makeText(this, R.string.pw_mismatch, Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String np = Ui.val(a);
                    final List<Uri> keys = new ArrayList<>(keyfiles);
                    new Thread(() -> {
                        String err = null;
                        try {
                            byte[] cred = Session.credentials(this, np, keys);
                            synchronized (Session.LOCK) {
                                TcVolume v = Session.volume();
                                if (v == null) throw new java.io.IOException(Msg.t("Volume verrouillé", "Volume is locked"));
                                v.changePassword(cred, v.prf(), new SecureRandom());
                            }
                            java.util.Arrays.fill(cred, (byte) 0);
                        } catch (Throwable e) { err = String.valueOf(e.getMessage()); }
                        final String msg = err;
                        runOnUiThread(() -> Toast.makeText(this, msg == null ? getString(R.string.password_changed) : msg, Toast.LENGTH_LONG).show());
                    }).start();
                }).show();
    }
}
