package com.tcdroid;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.radiobutton.MaterialRadioButton;

/** Écran des paramètres : thème et langue. */
public class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.syncCoreLanguage(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle(R.string.settings);
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.setNavigationContentDescription(R.string.back);
        tb.setNavigationOnClickListener(v -> finish());
        page.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout root = Ui.column(this, 16);

        // ---- thème
        LinearLayout cardTheme = Ui.card(this, root);
        cardTheme.addView(Ui.title(this, getString(R.string.theme)));
        final RadioGroup rgTheme = new RadioGroup(this);
        int[] themeLabels = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
        int cur = Prefs.theme(this);
        for (int i = 0; i < 3; i++) {
            MaterialRadioButton r = new MaterialRadioButton(this);
            r.setId(100 + i);
            r.setText(themeLabels[i]);
            rgTheme.addView(r);
            r.setChecked(i == cur);
        }
        rgTheme.setOnCheckedChangeListener((g, id) -> {
            int t = id - 100;
            if (t != Prefs.theme(this)) Prefs.setTheme(this, t);
        });
        cardTheme.addView(rgTheme);

        // ---- langue
        LinearLayout cardLang = Ui.card(this, root);
        cardLang.addView(Ui.title(this, getString(R.string.language)));
        final RadioGroup rgLang = new RadioGroup(this);
        final String[] tags = {"", "en", "fr"};
        int[] langLabels = {R.string.lang_system, R.string.lang_en, R.string.lang_fr};
        String curLang = Prefs.language();
        for (int i = 0; i < 3; i++) {
            MaterialRadioButton r = new MaterialRadioButton(this);
            r.setId(200 + i);
            r.setText(langLabels[i]);
            rgLang.addView(r);
            r.setChecked(tags[i].equals(curLang));
        }
        rgLang.setOnCheckedChangeListener((g, id) -> {
            String tag = tags[id - 200];
            if (!tag.equals(Prefs.language())) Prefs.setLanguage(tag);   // recrée l'activité dans la nouvelle langue
        });
        cardLang.addView(rgLang);

        // ---- à propos
        LinearLayout cardAbout = Ui.card(this, root);
        cardAbout.addView(Ui.title(this, getString(R.string.about)));
        cardAbout.addView(Ui.body(this, getString(R.string.about_text)));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }
}
