package com.tcdroid.core;

import java.util.Locale;

/** Messages bilingues du cœur (FR/EN) ; la langue est fixée par l'application. */
public final class Msg {
    private static volatile boolean french = Locale.getDefault().getLanguage().equals("fr");
    private Msg() {}
    public static void setFrench(boolean fr) { french = fr; }
    public static String t(String fr, String en) { return french ? fr : en; }
}
