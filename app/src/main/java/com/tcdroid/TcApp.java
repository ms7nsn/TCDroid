package com.tcdroid;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class TcApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        // Material You : couleurs tirées du fond d'écran (Android 12+), palette Material 3 par défaut sinon
        DynamicColors.applyToActivitiesIfAvailable(this);
        Prefs.applyTheme(Prefs.theme(this));
    }
}
