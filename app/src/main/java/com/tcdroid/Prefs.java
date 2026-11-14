package com.tcdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.tcdroid.core.Msg;

/** Réglages : thème (clair/sombre/système) et langue (système/anglais/français). */
final class Prefs {
    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    private static final String FILE = "settings", THEME = "theme";

    private Prefs() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    static int theme(Context c) { return sp(c).getInt(THEME, THEME_SYSTEM); }

    static void setTheme(Context c, int t) {
        sp(c).edit().putInt(THEME, t).apply();
        applyTheme(t);            // recrée les activités ouvertes
    }

    static void applyTheme(int t) {
        AppCompatDelegate.setDefaultNightMode(t == THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : t == THEME_DARK ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** "" = langue du système, sinon "en" ou "fr". */
    static String language() {
        LocaleListCompat l = AppCompatDelegate.getApplicationLocales();
        return l.isEmpty() || l.get(0) == null ? "" : l.get(0).getLanguage();
    }

    static void setLanguage(String tag) {
        AppCompatDelegate.setApplicationLocales(tag.isEmpty() ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }

    /** Aligne les messages du cœur sur la langue effective de l'activité. */
    static void syncCoreLanguage(Context activity) {
        String lang = activity.getResources().getConfiguration().getLocales().get(0).getLanguage();
        Msg.setFrench("fr".equals(lang));
    }
}
