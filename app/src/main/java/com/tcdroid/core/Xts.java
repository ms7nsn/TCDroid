package com.tcdroid.core;

import com.tcdroid.core.Algorithms.Ea;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * XTS (IEEE P1619) avec unités de données de 512 octets, numéro d'unité 64 bits little-endian,
 * et cascades de TrueCrypt. keyData = clés primaires (32 o/chiffrement) puis clés secondaires.
 */
public final class Xts {
    public static final int UNIT = 512;
    private final BlockCipher[] enc, dec, tweak;
    private final int n;

    public Xts(Ea ea, byte[] keyData, int off) {
        n = ea.ciphers.length;
        enc = new BlockCipher[n]; dec = new BlockCipher[n]; tweak = new BlockCipher[n];
        for (int i = 0; i < n; i++) {
            byte[] k1 = new byte[32], k2 = new byte[32];
            System.arraycopy(keyData, off + 32 * i, k1, 0, 32);
            System.arraycopy(keyData, off + 32 * n + 32 * i, k2, 0, 32);
            enc[i] = ea.ciphers[i].engine(); enc[i].init(true, new KeyParameter(k1));
            dec[i] = ea.ciphers[i].engine(); dec[i].init(false, new KeyParameter(k1));
            tweak[i] = ea.ciphers[i].engine(); tweak[i].init(true, new KeyParameter(k2));
            java.util.Arrays.fill(k1, (byte) 0); java.util.Arrays.fill(k2, (byte) 0);
        }
    }

    /** Traite len octets (multiple de 16) comme une seule unité de données numéro unit. */
    public void process(boolean encrypt, byte[] buf, int off, int len, long unit) {
        if (len % 16 != 0) throw new IllegalArgumentException("len");
        if (encrypt) for (int i = 0; i < n; i++) pass(enc[i], tweak[i], buf, off, len, unit);
        else for (int i = n - 1; i >= 0; i--) pass(dec[i], tweak[i], buf, off, len, unit);
    }

    /** Traite plusieurs unités de 512 octets consécutives. */
    public void processUnits(boolean encrypt, byte[] buf, int off, int len, long firstUnit) {
        for (int p = 0; p < len; p += UNIT) process(encrypt, buf, off + p, UNIT, firstUnit + p / UNIT);
    }

    private static void pass(BlockCipher c, BlockCipher tw, byte[] buf, int off, int len, long unit) {
        byte[] t = new byte[16], x = new byte[16];
        for (int j = 0; j < 8; j++) t[j] = (byte) (unit >>> (8 * j));
        tw.processBlock(t, 0, t, 0);
        for (int p = off; p < off + len; p += 16) {
            for (int j = 0; j < 16; j++) x[j] = (byte) (buf[p + j] ^ t[j]);
            c.processBlock(x, 0, x, 0);
            for (int j = 0; j < 16; j++) buf[p + j] = (byte) (x[j] ^ t[j]);
            int carry = 0;
            for (int j = 0; j < 16; j++) { int b = t[j] & 0xff; t[j] = (byte) ((b << 1) | carry); carry = b >>> 7; }
            if (carry != 0) t[0] ^= (byte) 0x87;
        }
    }
}
