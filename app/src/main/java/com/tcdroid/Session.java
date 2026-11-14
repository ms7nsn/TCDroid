package com.tcdroid;

import com.tcdroid.core.Msg;
import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import com.tcdroid.core.Keyfiles;
import com.tcdroid.core.TcVolume;
import com.tcdroid.fat.BlockDev;
import com.tcdroid.fat.FatFs;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Volume actuellement déverrouillé (un seul à la fois) et verrouillage automatique. */
final class Session {
    static final Object LOCK = new Object();
    static final long IDLE_MS = 15 * 60 * 1000L;

    private static TcVolume volume;
    private static FatFs fs;
    private static String name = "";
    private static long last;
    private static Context appCtx;

    private Session() {}

    static boolean isUnlocked() {
        synchronized (LOCK) { checkIdle(); return volume != null; }
    }

    static String name() { synchronized (LOCK) { return name; } }
    static boolean isHidden() { synchronized (LOCK) { return volume != null && volume.isHidden(); } }
    static TcVolume volume() { synchronized (LOCK) { return volume; } }

    /** FatFs du volume déverrouillé ; à utiliser sous synchronized (LOCK). */
    static FatFs fs() throws IOException {
        synchronized (LOCK) {
            checkIdle();
            if (fs == null) throw new IOException(Msg.t("Volume verrouillé", "Volume is locked"));
            last = System.currentTimeMillis();
            return fs;
        }
    }

    private static void checkIdle() {
        if (volume != null && System.currentTimeMillis() - last > IDLE_MS) lockLocked();
    }

    static byte[] credentials(Context c, String password, List<Uri> keyfiles) throws IOException {
        byte[] pw = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (pw.length > Keyfiles.MAX_PASSWORD) throw new IOException(Msg.t("Mot de passe limité à 64 octets", "Password is limited to 64 bytes"));
        if (keyfiles == null || keyfiles.isEmpty()) return pw;
        List<InputStream> ins = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        for (Uri u : keyfiles) {
            InputStream in = cr.openInputStream(u);
            if (in == null) throw new IOException(Msg.t("Fichier de clés illisible : ", "Unreadable keyfile: ") + u);
            ins.add(in);
        }
        byte[] eff = Keyfiles.apply(pw, ins);
        Arrays.fill(pw, (byte) 0);
        return eff;
    }

    static void unlock(Context c, Uri container, String displayName, byte[] cred, boolean backup) throws IOException {
        synchronized (LOCK) { unlockLocked(c, container, displayName, cred, backup); }
    }

    private static void unlockLocked(Context c, Uri container, String displayName, byte[] cred, boolean backup) throws IOException {
        lock();
        appCtx = c.getApplicationContext();
        ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(container, "rw");
        if (pfd == null) throw new IOException(Msg.t("Impossible d'ouvrir le conteneur", "Cannot open the container"));
        PfdStorage st = new PfdStorage(pfd, true);
        try {
            final TcVolume v = TcVolume.open(st, cred, backup);
            BlockDev dev = asDev(v, v.size());
            FatFs f = FatFs.mount(dev);
            volume = v; fs = f; name = displayName; last = System.currentTimeMillis();
        } catch (IOException | RuntimeException e) {
            try { st.close(); } catch (IOException ignored) { }
            throw e;
        } finally {
            Arrays.fill(cred, (byte) 0);
        }
        notifyRoots();
    }

    static BlockDev asDev(final TcVolume v, final long size) {
        return new BlockDev() {
            @Override public long size() { return size; }
            @Override public void read(long off, byte[] b, int o, int len) throws IOException { v.read(off, b, o, len); }
            @Override public void write(long off, byte[] b, int o, int len) throws IOException { v.write(off, b, o, len); }
        };
    }

    static void lock() {
        synchronized (LOCK) { lockLocked(); }
    }

    private static void lockLocked() {
        if (volume == null) return;
        try { if (fs != null) fs.flush(); } catch (IOException ignored) { }
        try { volume.flush(); volume.close(); } catch (IOException ignored) { }
        volume = null; fs = null; name = "";
        notifyRoots();
    }

    private static void notifyRoots() {
        if (appCtx == null) return;
        appCtx.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(TcDocumentsProvider.AUTHORITY), null);
    }
}
