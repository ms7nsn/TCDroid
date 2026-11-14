package com.tcdroid.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Fichiers de clés : même algorithme que Keyfiles.c (pool de 64 octets, CRC-32 cumulés). */
public final class Keyfiles {
    public static final int POOL = 64, MAX_READ = 1024 * 1024, MAX_PASSWORD = 64;
    private static final int[] TAB = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            TAB[i] = c;
        }
    }
    private Keyfiles() {}

    /** Renvoie le mot de passe effectif (mot de passe + pool de fichiers de clés), 64 octets minimum. */
    public static byte[] apply(byte[] password, List<InputStream> files) throws IOException {
        byte[] pool = new byte[POOL];
        for (InputStream in : files) {
            int crc = 0xffffffff, writePos = 0, total = 0, b;
            byte[] chunk = new byte[8192];
            int n;
            outer:
            while ((n = in.read(chunk)) > 0) {
                for (int i = 0; i < n; i++) {
                    crc = TAB[(crc ^ chunk[i]) & 0xff] ^ (crc >>> 8);
                    pool[writePos++] += (byte) (crc >>> 24);
                    pool[writePos++] += (byte) (crc >>> 16);
                    pool[writePos++] += (byte) (crc >>> 8);
                    pool[writePos++] += (byte) crc;
                    if (writePos >= POOL) writePos = 0;
                    if (++total >= MAX_READ) break outer;
                }
            }
            in.close();
        }
        int len = Math.max(Math.min(password.length, MAX_PASSWORD), POOL);
        byte[] out = new byte[len];
        for (int i = 0; i < POOL; i++) {
            out[i] = (byte) ((i < password.length ? password[i] : 0) + pool[i]);
        }
        for (int i = POOL; i < len; i++) out[i] = password[i];
        java.util.Arrays.fill(pool, (byte) 0);
        return out;
    }
}
