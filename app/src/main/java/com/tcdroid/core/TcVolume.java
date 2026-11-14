package com.tcdroid.core;

import com.tcdroid.core.Algorithms.Ea;
import com.tcdroid.core.Algorithms.Prf;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Volume TrueCrypt 7.1a (XTS) : ouverture, lecture/écriture, création, changement de mot de passe. */
public final class TcVolume implements Closeable {
    public static final int HEADER_SIZE = 64 * 1024, GROUP = 2 * HEADER_SIZE;
    public static final int HIDDEN_HEADER_OFFSET = HEADER_SIZE;
    static final int SALT = 64, EFFECTIVE = 512, DK_LEN = 192, VERSION_NUM = 0x071a;
    static final int MAGIC_OFF = 64, KEY_OFF = 256;

    public static final class WrongPasswordException extends IOException {
        public WrongPasswordException() { super(Msg.t("Mot de passe, fichiers de clés ou algorithme incorrects", "Wrong password, keyfiles or algorithm")); }
    }

    private final Storage st;
    private final Xts xts;
    private final long dataStart, dataLen;
    private final boolean hidden;
    private final Ea ea;
    private final Prf prf;
    private final long headerOffset, backupOffset, hiddenSize, volumeSize;
    private final byte[] masterKeyData;
    private final int sectorSize;

    private TcVolume(Storage st, Ea ea, Prf prf, byte[] mk, long dataStart, long dataLen, boolean hidden,
                     long headerOffset, long backupOffset, long hiddenSize, long volumeSize, int sectorSize) {
        this.st = st; this.ea = ea; this.prf = prf; this.masterKeyData = mk;
        this.xts = new Xts(ea, mk, 0);
        this.dataStart = dataStart; this.dataLen = dataLen; this.hidden = hidden;
        this.headerOffset = headerOffset; this.backupOffset = backupOffset;
        this.hiddenSize = hiddenSize; this.volumeSize = volumeSize; this.sectorSize = sectorSize;
    }

    public long size() { return dataLen; }
    public boolean isHidden() { return hidden; }
    public Ea cipher() { return ea; }
    public Prf prf() { return prf; }
    public int sectorSize() { return sectorSize; }

    // ------------------------------------------------------------------ ouverture

    /** Essaie l'en-tête normal puis l'en-tête caché ; useBackup lit les en-têtes de secours en fin de fichier. */
    public static TcVolume open(Storage st, byte[] password, boolean useBackup) throws IOException {
        long host = st.length();
        if (host < GROUP * 2) throw new IOException(Msg.t("Fichier trop petit pour un volume TrueCrypt", "File too small to be a TrueCrypt volume"));
        for (int kind = 0; kind < 2; kind++) {
            boolean hid = kind == 1;
            long off = useBackup ? (hid ? host - HEADER_SIZE : host - GROUP) : (hid ? HIDDEN_HEADER_OFFSET : 0);
            long bak = hid ? host - HEADER_SIZE : host - GROUP;
            long pri = hid ? HIDDEN_HEADER_OFFSET : 0;
            byte[] raw = new byte[EFFECTIVE];
            st.read(off, raw, 0, EFFECTIVE);
            TcVolume v = tryHeader(st, raw, password, hid, host, pri, bak);
            if (v != null) return v;
        }
        throw new WrongPasswordException();
    }

    static byte[] deriveKey(Prf prf, byte[] pw, byte[] salt) {
        PKCS5S2ParametersGenerator g = new PKCS5S2ParametersGenerator(prf.digest());
        g.init(pw, salt, prf.iterations);
        return ((KeyParameter) g.generateDerivedParameters(DK_LEN * 8)).getKey();
    }

    private static TcVolume tryHeader(Storage st, byte[] raw, byte[] pw, boolean hid, long host,
                                      long pri, long bak) throws IOException {
        byte[] salt = Arrays.copyOfRange(raw, 0, SALT);
        for (Prf prf : Prf.values()) {
            byte[] dk = deriveKey(prf, pw, salt);
            for (Ea ea : Ea.values()) {
                byte[] h = raw.clone();
                new Xts(ea, dk, 0).process(false, h, SALT, EFFECTIVE - SALT, 0);
                if (u32(h, MAGIC_OFF) != 0x54525545L) continue;
                int hv = u16(h, 68);
                if (hv > 5) throw new IOException(Msg.t("Version d'en-tête non prise en charge", "Unsupported header version"));
                if (hv >= 4 && u32(h, 252) != crc(h, MAGIC_OFF, 252 - MAGIC_OFF)) continue;
                int req = u16(h, 70);
                if (u32(h, 72) != crc(h, KEY_OFF, 256)) continue;
                if (req > VERSION_NUM) throw new IOException(Msg.t("Volume créé par une version plus récente de TrueCrypt", "Volume created by a newer version of TrueCrypt"));
                if (req < 0x600) throw new IOException(Msg.t("Volumes hérités (< 6.0, LRW/CBC) non pris en charge", "Legacy volumes (< 6.0, LRW/CBC) are not supported"));
                long hiddenSize = u64(h, 92), volSize = u64(h, 100), encStart = u64(h, 108);
                int sector = hv >= 5 ? (int) u32(h, 128) : 512;
                if (sector < 512 || sector > 4096 || sector % 512 != 0) throw new IOException(Msg.t("Taille de secteur invalide", "Invalid sector size"));
                boolean isHidden = hiddenSize != 0;
                if (hid && !isHidden) continue;
                long len = isHidden ? hiddenSize : volSize;
                if (encStart % 512 != 0 || encStart + len > host) throw new IOException(Msg.t("Géométrie de volume invalide", "Invalid volume geometry"));
                byte[] mk = Arrays.copyOfRange(h, KEY_OFF, KEY_OFF + 256);
                Arrays.fill(dk, (byte) 0); Arrays.fill(h, (byte) 0);
                return new TcVolume(st, ea, prf, mk, encStart, len, isHidden, pri, bak, hiddenSize, volSize, sector);
            }
            Arrays.fill(dk, (byte) 0);
        }
        return null;
    }

    // ------------------------------------------------------------------ lecture / écriture

    public synchronized void read(long volOff, byte[] b, int o, int len) throws IOException {
        check(volOff, len);
        while (len > 0) {
            int n = (int) Math.min(len, 1 << 20);
            long abs = dataStart + volOff, a0 = abs & ~511L, a1 = (abs + n + 511) & ~511L;
            byte[] tmp = new byte[(int) (a1 - a0)];
            st.read(a0, tmp, 0, tmp.length);
            xts.processUnits(false, tmp, 0, tmp.length, a0 / 512);
            System.arraycopy(tmp, (int) (abs - a0), b, o, n);
            volOff += n; o += n; len -= n;
        }
    }

    public synchronized void write(long volOff, byte[] b, int o, int len) throws IOException {
        check(volOff, len);
        while (len > 0) {
            int n = (int) Math.min(len, 1 << 20);
            long abs = dataStart + volOff, a0 = abs & ~511L, a1 = (abs + n + 511) & ~511L;
            byte[] tmp = new byte[(int) (a1 - a0)];
            if (a0 != abs || a1 != abs + n) {            // lecture-modification-écriture des bords
                st.read(a0, tmp, 0, tmp.length);
                xts.processUnits(false, tmp, 0, tmp.length, a0 / 512);
            }
            System.arraycopy(b, o, tmp, (int) (abs - a0), n);
            xts.processUnits(true, tmp, 0, tmp.length, a0 / 512);
            st.write(a0, tmp, 0, tmp.length);
            volOff += n; o += n; len -= n;
        }
    }

    private void check(long off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len > dataLen) throw new IOException(Msg.t("Accès hors du volume", "Access outside the volume"));
    }

    public void flush() throws IOException { st.flush(); }

    @Override public void close() throws IOException {
        Arrays.fill(masterKeyData, (byte) 0);
        st.close();
    }

    // ------------------------------------------------------------------ en-têtes

    /** Construit un en-tête de 512 octets chiffré avec (mot de passe, sel aléatoire). */
    static byte[] buildHeader(byte[] pw, Prf prf, Ea ea, byte[] masterKeyData, long volumeSize,
                              long hiddenSize, long encStart, SecureRandom rnd) {
        byte[] h = new byte[EFFECTIVE];
        byte[] salt = new byte[SALT]; rnd.nextBytes(salt);
        System.arraycopy(salt, 0, h, 0, SALT);
        p32(h, MAGIC_OFF, 0x54525545L);
        p16(h, 68, 5); p16(h, 70, 0x0700);
        p64(h, 92, hiddenSize); p64(h, 100, volumeSize);
        p64(h, 108, encStart); p64(h, 116, hiddenSize != 0 ? hiddenSize : volumeSize);
        p32(h, 124, 0); p32(h, 128, 512);
        System.arraycopy(masterKeyData, 0, h, KEY_OFF, 256);
        p32(h, 72, crc(h, KEY_OFF, 256));
        p32(h, 252, crc(h, MAGIC_OFF, 252 - MAGIC_OFF));
        byte[] dk = deriveKey(prf, pw, salt);
        new Xts(ea, dk, 0).process(true, h, SALT, EFFECTIVE - SALT, 0);
        Arrays.fill(dk, (byte) 0);
        return h;
    }

    /** Crée un volume normal : en-têtes (principal + secours), zones cachées aléatoires, données chiffrées (zéros). */
    public static TcVolume create(Storage st, long hostSize, byte[] pw, Prf prf, Ea ea,
                                  SecureRandom rnd, ProgressListener pl) throws IOException {
        if (hostSize < GROUP * 2 + 4096) throw new IOException(Msg.t("Taille trop petite", "Size too small"));
        long volSize = ((hostSize - 2L * GROUP) / 512) * 512;
        byte[] mk = new byte[256]; rnd.nextBytes(mk);
        byte[] rndHdr = new byte[HEADER_SIZE];
        rnd.nextBytes(rndHdr);
        st.write(0, rndHdr, 0, HEADER_SIZE);
        st.write(HIDDEN_HEADER_OFFSET, rndHdr, 0, HEADER_SIZE);
        rnd.nextBytes(rndHdr);
        st.write(hostSize - HEADER_SIZE, rndHdr, 0, HEADER_SIZE);
        st.write(hostSize - GROUP, rndHdr, 0, HEADER_SIZE);
        byte[] h1 = buildHeader(pw, prf, ea, mk, volSize, 0, GROUP, rnd);
        byte[] h2 = buildHeader(pw, prf, ea, mk, volSize, 0, GROUP, rnd);
        st.write(0, h1, 0, EFFECTIVE);
        st.write(hostSize - GROUP, h2, 0, EFFECTIVE);
        TcVolume v = new TcVolume(st, ea, prf, mk, GROUP, volSize, false, 0, hostSize - GROUP, 0, volSize, 512);
        byte[] zero = new byte[1 << 20];
        for (long p = 0; p < volSize; p += zero.length) {
            int n = (int) Math.min(zero.length, volSize - p);
            Arrays.fill(zero, 0, n, (byte) 0);
            v.write(p, zero, 0, n);
            if (pl != null) pl.progress(p + n, volSize);
        }
        st.flush();
        return v;
    }

    /**
     * Crée un volume caché dans la zone libre à la fin d'un volume extérieur déjà ouvert/formaté.
     * hiddenSize est arrondi à 512 ; la zone cachée occupe la fin du conteneur (avant les en-têtes de secours).
     */
    public static TcVolume createHidden(Storage st, byte[] hiddenPw, Prf prf, Ea ea, long hiddenSize,
                                        SecureRandom rnd, ProgressListener pl) throws IOException {
        long host = st.length();
        hiddenSize = (hiddenSize / 512) * 512;
        long start = ((host - GROUP - hiddenSize) / 512) * 512;   // aligné sur une unité XTS
        if (hiddenSize < 4096 || start < GROUP + 512 * 1024) throw new IOException(Msg.t("Taille du volume caché invalide", "Invalid hidden volume size"));
        byte[] mk = new byte[256]; rnd.nextBytes(mk);
        byte[] h1 = buildHeader(hiddenPw, prf, ea, mk, hiddenSize, hiddenSize, start, rnd);
        byte[] h2 = buildHeader(hiddenPw, prf, ea, mk, hiddenSize, hiddenSize, start, rnd);
        st.write(HIDDEN_HEADER_OFFSET, h1, 0, EFFECTIVE);
        st.write(host - HEADER_SIZE, h2, 0, EFFECTIVE);
        TcVolume v = new TcVolume(st, ea, prf, mk, start, hiddenSize, true, HIDDEN_HEADER_OFFSET,
                host - HEADER_SIZE, hiddenSize, hiddenSize, 512);
        byte[] zero = new byte[1 << 20];
        for (long p = 0; p < hiddenSize; p += zero.length) {
            int n = (int) Math.min(zero.length, hiddenSize - p);
            Arrays.fill(zero, 0, n, (byte) 0);
            v.write(p, zero, 0, n);
            if (pl != null) pl.progress(p + n, hiddenSize);
        }
        st.flush();
        return v;
    }

    /** Change le mot de passe/PRF : réécrit l'en-tête principal et celui de secours (nouveaux sels). */
    public synchronized void changePassword(byte[] newPw, Prf newPrf, SecureRandom rnd) throws IOException {
        long enc = dataStart;
        byte[] h1 = buildHeader(newPw, newPrf, ea, masterKeyData, volumeSize, hiddenSize, enc, rnd);
        byte[] h2 = buildHeader(newPw, newPrf, ea, masterKeyData, volumeSize, hiddenSize, enc, rnd);
        st.write(headerOffset, h1, 0, EFFECTIVE);
        st.write(backupOffset, h2, 0, EFFECTIVE);
        st.flush();
    }

    public interface ProgressListener { void progress(long done, long total); }

    // ------------------------------------------------------------------ utilitaires

    static long crc(byte[] b, int off, int len) { CRC32 c = new CRC32(); c.update(b, off, len); return c.getValue(); }
    static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    static long u32(byte[] b, int o) { return ((long) u16(b, o) << 16 | u16(b, o + 2)) & 0xffffffffL; }
    static long u64(byte[] b, int o) { return (u32(b, o) << 32) | u32(b, o + 4); }
    static void p16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    static void p32(byte[] b, int o, long v) { p16(b, o, (int) (v >>> 16)); p16(b, o + 2, (int) v); }
    static void p64(byte[] b, int o, long v) { p32(b, o, v >>> 32); p32(b, o + 4, v); }
}
