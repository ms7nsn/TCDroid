package com.tcdroid;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Composants Material 3 construits en code (aucune mise en page XML). */
final class Ui {
    private Ui() {}

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static LinearLayout column(Context c, int padDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** Carte M3 « remplie » : renvoie la colonne intérieure. */
    static LinearLayout card(Context c, LinearLayout parent) {
        MaterialCardView card = new MaterialCardView(c, null, com.google.android.material.R.attr.materialCardViewFilledStyle);
        LinearLayout inner = column(c, 16);
        card.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        parent.addView(card, lp);
        return inner;
    }

    static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        t.setPadding(0, 0, 0, dp(c, 8));
        return t;
    }

    static TextView headline(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        t.setPadding(0, 0, 0, dp(c, 4));
        return t;
    }

    static TextView body(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return t;
    }

    static TextView small(Context c, String s) {
        TextView t = body(c, s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        return t;
    }

    static MaterialButton button(Context c, String s, boolean primary, View.OnClickListener l) {
        MaterialButton b = primary ? new MaterialButton(c)
                : new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static TextInputLayout input(Context c, String hint, boolean password, boolean number) {
        TextInputLayout til = new TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint(hint);
        TextInputEditText e = new TextInputEditText(til.getContext());
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        til.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (password) til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        return til;
    }

    static String val(TextInputLayout t) { return t.getEditText().getText().toString(); }
    static void set(TextInputLayout t, String s) { t.getEditText().setText(s); }

    static MaterialCheckBox check(Context c, String s) {
        MaterialCheckBox b = new MaterialCheckBox(c);
        b.setText(s);
        return b;
    }

    static void add(ViewGroup g, View... vs) {
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(g.getContext(), 4);
            g.addView(v, lp);
        }
    }

    static String displayName(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException ignored) { }
        String p = u.getLastPathSegment();
        return p == null ? "volume" : p;
    }
}
