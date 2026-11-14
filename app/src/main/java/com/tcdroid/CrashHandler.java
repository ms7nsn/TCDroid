package com.tcdroid;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Capture tout plantage et l'affiche dans CrashActivity (autre processus) pour pouvoir le copier. */
final class CrashHandler {
    private CrashHandler() {}

    static void install(final Application app) {
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":crash")) return;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String trace = "TCDroid 0.2.1 | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | "
                        + Build.MANUFACTURER + " " + Build.MODEL + "\nThread: " + t.getName() + "\n\n" + sw;
                if (trace.length() > 30000) trace = trace.substring(0, 30000);
                Intent i = new Intent((Context) app, CrashActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("trace", trace);
                app.startActivity(i);
                Thread.sleep(500);
            } catch (Throwable ignored) { }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}
