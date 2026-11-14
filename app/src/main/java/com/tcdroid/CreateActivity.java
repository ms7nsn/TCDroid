package com.tcdroid;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;
import com.tcdroid.core.Algorithms.Ea;
import com.tcdroid.core.Algorithms.Prf;
import com.tcdroid.core.Msg;
import com.tcdroid.core.TcVolume;
import com.tcdroid.fat.BlockDev;
import com.tcdroid.fat.FatFs;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Création d'un volume (normal, avec volume caché optionnel) formaté en FAT. */
public class CreateActivity extends AppCompatActivity {
    private static final int REQ_SAVE = 10, REQ_KEYS = 11;
    private static final long MIB = 1024L * 1024L;
    private static final Prf[] PRFS = {Prf.SHA512, Prf.RIPEMD160, Prf.WHIRLPOOL};

    private TextInputLayout size, pw, pw2, hSize, hPw, hPw2;
    private Spinner algo, hash;
    private MaterialCheckBox hidden;
    private TextView keysView, progressText;
    private LinearLayout hiddenBox;
    private LinearProgressIndicator bar;
    private final List<Uri> keyfiles = new ArrayList<>();
    private boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.syncCoreLanguage(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle(R.string.create_title);
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationContentDescription(R.string.back);
        tb.setNavigationOnClickListener(v -> finish());
        page.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout root = Ui.column(this, 16);

        LinearLayout c1 = Ui.card(this, root);
        size = Ui.input(this, getString(R.string.total_size), false, true);
        Ui.set(size, "100");
        Ui.add(c1, size);
        String[] ea = new String[Ea.values().length];
        for (int i = 0; i < ea.length; i++) ea[i] = Ea.values()[i].label;
        algo = new Spinner(this);
        algo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ea));
        String[] pr = new String[PRFS.length];
        for (int i = 0; i < pr.length; i++) pr[i] = PRFS[i].label;
        hash = new Spinner(this);
        hash.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, pr));
        Ui.add(c1, Ui.small(this, getString(R.string.cipher)), algo, Ui.small(this, getString(R.string.hash)), hash);

        LinearLayout c2 = Ui.card(this, root);
        pw = Ui.input(this, getString(R.string.password), true, false);
        pw2 = Ui.input(this, getString(R.string.confirm_password), true, false);
        Ui.add(c2, pw, pw2, Ui.button(this, getString(R.string.keyfiles), false, v -> pickKeys()));
        keysView = Ui.small(this, getString(R.string.no_keyfiles));
        c2.addView(keysView);

        LinearLayout c3 = Ui.card(this, root);
        hidden = Ui.check(this, getString(R.string.add_hidden));
        c3.addView(hidden);
        hiddenBox = new LinearLayout(this);
        hiddenBox.setOrientation(LinearLayout.VERTICAL);
        hSize = Ui.input(this, getString(R.string.hidden_size), false, true);
        hPw = Ui.input(this, getString(R.string.hidden_password), true, false);
        hPw2 = Ui.input(this, getString(R.string.hidden_confirm), true, false);
        Ui.add(hiddenBox, hSize, hPw, hPw2);
        hiddenBox.setVisibility(android.view.View.GONE);
        c3.addView(hiddenBox);
        hidden.setOnCheckedChangeListener((v, on) -> hiddenBox.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE));

        Ui.add(root, Ui.button(this, getString(R.string.create_button), true, v -> start()));
        bar = new LinearProgressIndicator(this);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        progressText = Ui.small(this, "");
        Ui.add(root, bar);
        root.addView(progressText);
        root.addView(Ui.small(this, getString(R.string.create_warning)));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    private void pickKeys() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_KEYS);
    }

    private void start() {
        if (busy) return;
        long total, hid = 0;
        try { total = Long.parseLong(Ui.val(size).trim()); } catch (NumberFormatException e) { toast(R.string.err_size); return; }
        if (total < 3) { toast(R.string.err_min_size); return; }
        if (!Ui.val(pw).equals(Ui.val(pw2))) { toast(R.string.pw_mismatch); return; }
        if (Ui.val(pw).isEmpty() && keyfiles.isEmpty()) { toast(R.string.err_need_pw); return; }
        if (hidden.isChecked()) {
            try { hid = Long.parseLong(Ui.val(hSize).trim()); } catch (NumberFormatException e) { toast(R.string.err_hidden_size); return; }
            if (hid < 1 || total - hid < 3) { toast(R.string.err_hidden_room); return; }
            if (!Ui.val(hPw).equals(Ui.val(hPw2)) || Ui.val(hPw).isEmpty()) { toast(R.string.err_hidden_pw); return; }
            if (Ui.val(hPw).equals(Ui.val(pw))) { toast(R.string.err_pw_same); return; }
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "volume.tc");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_KEYS) {
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) keyfiles.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) keyfiles.add(data.getData());
            keysView.setText(getString(R.string.n_keyfiles, keyfiles.size()));
        } else if (req == REQ_SAVE && data.getData() != null) {
            create(data.getData());
        }
    }

    private void create(final Uri target) {
        final long host = Long.parseLong(Ui.val(size).trim()) * MIB;
        final boolean withHidden = hidden.isChecked();
        final long hidBytes = withHidden ? Long.parseLong(Ui.val(hSize).trim()) * MIB : 0;
        final Ea ea = Ea.values()[algo.getSelectedItemPosition()];
        final Prf prf = PRFS[hash.getSelectedItemPosition()];
        final String pass = Ui.val(pw), hpass = Ui.val(hPw);
        final List<Uri> keys = new ArrayList<>(keyfiles);
        final String lblOuter = getString(R.string.phase_encrypt), lblHidden = getString(R.string.phase_hidden);
        busy = true;
        new Thread(() -> {
            String err = null;
            try {
                SecureRandom rnd = new SecureRandom();
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(target, "rw");
                if (pfd == null) throw new java.io.IOException(Msg.t("Impossible d'écrire le fichier", "Cannot write the file"));
                PfdStorage st = new PfdStorage(pfd, true);
                st.extendTo(host);
                byte[] cred = Session.credentials(this, pass, keys);
                final int phases = withHidden ? 2 : 1;
                TcVolume v = TcVolume.create(st, host, cred, prf, ea, rnd, (done, tot) -> progress(0, phases, done, tot, lblOuter));
                long limit = v.size();
                if (withHidden) {
                    long hs = (hidBytes / 512) * 512;
                    long start = ((host - TcVolume.GROUP - hs) / 512) * 512;
                    limit = start - TcVolume.GROUP;      // le système de fichiers extérieur n'atteindra jamais la zone cachée
                }
                BlockDev dev = Session.asDev(v, (limit / 512) * 512);
                FatFs fs = FatFs.format(dev, "TCDROID");
                fs.flush();
                v.flush();
                java.util.Arrays.fill(cred, (byte) 0);
                if (withHidden) {
                    byte[] hc = Session.credentials(this, hpass, null);
                    TcVolume hv = TcVolume.createHidden(st, hc, prf, ea, hidBytes, rnd,
                            (done, tot) -> progress(1, 2, done, tot, lblHidden));
                    BlockDev hd = Session.asDev(hv, (hv.size() / 512) * 512);
                    FatFs hf = FatFs.format(hd, "TCDROID");
                    hf.flush();
                    hv.flush();
                    java.util.Arrays.fill(hc, (byte) 0);
                    hv.close();
                } else {
                    v.close();
                }
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String msg = err;
            runOnUiThread(() -> {
                busy = false;
                if (msg == null) {
                    progressText.setText(R.string.done_msg);
                    bar.setProgress(1000);
                } else {
                    progressText.setText(getString(R.string.failed, msg));
                }
            });
        }).start();
    }

    private void progress(int phase, int phases, long done, long total, String label) {
        final int p = (int) (((phase + (double) done / total) / phases) * 1000);
        runOnUiThread(() -> { bar.setProgress(p); progressText.setText(getString(R.string.progress_fmt, label, p / 10)); });
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); }
}
