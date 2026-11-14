package com.tcdroid;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Écran minimal (sans AppCompat/Material, processus séparé) montrant l'erreur à copier. */
public class CrashActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final boolean fr = Locale.getDefault().getLanguage().equals("fr");
        final String trace = getIntent().getStringExtra("trace") == null ? "?" : getIntent().getStringExtra("trace");
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        TextView title = new TextView(this);
        title.setText(fr ? "TCDroid a rencontré une erreur" : "TCDroid hit an error");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(fr ? "Touchez « Copier », puis collez le texte dans la conversation pour que l'erreur soit corrigée."
                : "Tap “Copy”, then paste the text in the conversation so the error can be fixed.");
        hint.setPadding(0, p / 2, 0, p / 2);
        root.addView(hint);

        Button copy = new Button(this);
        copy.setText(fr ? "Copier" : "Copy");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TCDroid", trace));
            Toast.makeText(this, fr ? "Copié" : "Copied", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        Button share = new Button(this);
        share.setText(fr ? "Partager" : "Share");
        share.setOnClickListener(v -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, trace);
            startActivity(Intent.createChooser(s, "TCDroid"));
        });
        root.addView(share);

        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }
}
