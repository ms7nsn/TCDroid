package com.tcdroid.fat;

import com.tcdroid.core.Msg;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Système de fichiers FAT12/16/32 avec noms longs (VFAT) : lecture, écriture, création, suppression,
 * renommage, formatage. Chemins de la forme "/dossier/fichier". Appeler flush() pour persister la FAT.
 */
public final class FatFs {
    public static final class Node {
        public String name; public boolean dir; public long size; public long mtime;
        int cluster, attr, parent, idx, lfn; String shortNm;
    }

    private final BlockDev dev;
    private int bps, spc, clusterSize, nFats, rootEnt, type, rootCluster, nClusters;
    private long fatStart, fatBytes, rootStart, dataStart, totalSec;
    private byte[] fat;
    private boolean fatDirty;
    private int hint = 2;
    private long fsInfoOff = -1;

    private FatFs(BlockDev d) { dev = d; }

    // ================================================================== montage
    public static FatFs mount(BlockDev dev) throws IOException {
        FatFs f = new FatFs(dev);
        byte[] b = new byte[512]; dev.read(0, b, 0, 512);
        if ((b[510] & 0xff) != 0x55 || (b[511] & 0xff) != 0xAA) throw new IOException(Msg.t("Pas de système de fichiers FAT", "No FAT file system found"));
        f.bps = u16(b, 11); f.spc = b[13] & 0xff; int res = u16(b, 14); f.nFats = b[16] & 0xff;
        f.rootEnt = u16(b, 17);
        long tot = u16(b, 19) != 0 ? u16(b, 19) : u32(b, 32);
        long fsz = u16(b, 22) != 0 ? u16(b, 22) : u32(b, 36);
        if (f.bps < 512 || Integer.bitCount(f.bps) != 1 || f.spc == 0 || Integer.bitCount(f.spc) != 1 || f.nFats == 0)
            throw new IOException(Msg.t("Système de fichiers non FAT (BPB invalide, exFAT/NTFS non pris en charge)", "Not a FAT file system (invalid BPB; exFAT/NTFS are not supported)"));
        f.clusterSize = f.bps * f.spc; f.totalSec = tot;
        long rootSecs = ((long) f.rootEnt * 32 + f.bps - 1) / f.bps;
        long dataSecs = tot - res - f.nFats * fsz - rootSecs;
        f.nClusters = (int) (dataSecs / f.spc);
        f.type = f.nClusters < 4085 ? 12 : f.nClusters < 65525 ? 16 : 32;
        f.fatStart = (long) res * f.bps; f.fatBytes = fsz * f.bps;
        f.rootStart = f.fatStart + f.nFats * f.fatBytes;
        f.dataStart = f.rootStart + rootSecs * f.bps;
        f.rootCluster = f.type == 32 ? (int) u32(b, 44) : 0;
        if (f.type == 32 && u16(b, 48) != 0) f.fsInfoOff = (long) u16(b, 48) * f.bps;
        f.fat = new byte[(int) f.fatBytes];
        dev.read(f.fatStart, f.fat, 0, f.fat.length);
        return f;
    }

    // ================================================================== FAT
    private int fget(int n) {
        switch (type) {
            case 12: { int o = n + n / 2; int v = (fat[o] & 0xff) | ((fat[o + 1] & 0xff) << 8); return (n & 1) == 0 ? v & 0xfff : v >>> 4; }
            case 16: return (fat[2 * n] & 0xff) | ((fat[2 * n + 1] & 0xff) << 8);
            default: { int o = 4 * n; return ((fat[o] & 0xff) | (fat[o + 1] & 0xff) << 8 | (fat[o + 2] & 0xff) << 16 | (fat[o + 3] & 0xff) << 24) & 0x0fffffff; }
        }
    }
    private void fset(int n, int v) {
        fatDirty = true;
        switch (type) {
            case 12: { int o = n + n / 2; int cur = (fat[o] & 0xff) | ((fat[o + 1] & 0xff) << 8);
                cur = (n & 1) == 0 ? (cur & 0xf000) | (v & 0xfff) : (cur & 0x000f) | ((v & 0xfff) << 4);
                fat[o] = (byte) cur; fat[o + 1] = (byte) (cur >>> 8); break; }
            case 16: fat[2 * n] = (byte) v; fat[2 * n + 1] = (byte) (v >>> 8); break;
            default: { int o = 4 * n; int top = fat[o + 3] & 0xf0;
                fat[o] = (byte) v; fat[o + 1] = (byte) (v >>> 8); fat[o + 2] = (byte) (v >>> 16); fat[o + 3] = (byte) (top | ((v >>> 24) & 0x0f)); }
        }
    }
    private boolean eoc(int v) { return type == 12 ? v >= 0xff8 : type == 16 ? v >= 0xfff8 : v >= 0x0ffffff8; }
    private int eocVal() { return type == 12 ? 0xfff : type == 16 ? 0xffff : 0x0fffffff; }

    private List<Integer> chain(int c) throws IOException {
        List<Integer> l = new ArrayList<>();
        while (c >= 2 && c < nClusters + 2 && !eoc(c)) {
            l.add(c); if (l.size() > nClusters) throw new IOException(Msg.t("FAT corrompue (boucle)", "Corrupted FAT (loop)"));
            c = fget(c);
        }
        return l;
    }
    private int alloc() throws IOException {
        for (int i = 0; i < nClusters; i++) {
            int c = 2 + (hint - 2 + i) % nClusters;
            if (fget(c) == 0) { fset(c, eocVal()); hint = c + 1; zeroCluster(c); return c; }
        }
        throw new IOException(Msg.t("Volume plein", "Volume is full"));
    }
    private void freeChain(int c) throws IOException { for (int x : chain(c)) fset(x, 0); }
    private long cOff(int c) { return dataStart + (long) (c - 2) * clusterSize; }
    private void zeroCluster(int c) throws IOException { dev.write(cOff(c), new byte[clusterSize], 0, clusterSize); }

    public void flush() throws IOException {
        if (fatDirty) {
            for (int i = 0; i < nFats; i++) dev.write(fatStart + i * fatBytes, fat, 0, fat.length);
            fatDirty = false;
        }
    }
    public long totalSpace() { return (long) nClusters * clusterSize; }
    public long freeSpace() { long n = 0; for (int c = 2; c < nClusters + 2; c++) if (fget(c) == 0) n++; return n * clusterSize; }
    public int fatType() { return type; }

    // ================================================================== répertoires
    private byte[] loadDir(int c) throws IOException {
        if (c == 0) { byte[] r = new byte[rootEnt * 32]; dev.read(rootStart, r, 0, r.length); return r; }
        List<Integer> ch = chain(c); byte[] d = new byte[ch.size() * clusterSize];
        for (int i = 0; i < ch.size(); i++) dev.read(cOff(ch.get(i)), d, i * clusterSize, clusterSize);
        return d;
    }
    private void storeDir(int c, byte[] d) throws IOException {
        if (c == 0) { dev.write(rootStart, d, 0, d.length); return; }
        List<Integer> ch = chain(c);
        while (ch.size() * clusterSize < d.length) { int n = alloc(); fset(ch.get(ch.size() - 1), n); ch.add(n); }
        for (int i = 0; i * clusterSize < d.length; i++) dev.write(cOff(ch.get(i)), d, i * clusterSize, clusterSize);
    }
    private int dirKey(int c) { return c == rootCluster ? (type == 32 ? rootCluster : 0) : c; }

    private List<Node> parse(byte[] d, int parent) {
        List<Node> out = new ArrayList<>();
        StringBuilder lfnName = new StringBuilder(); int lfnCount = 0, lfnSum = -1;
        for (int i = 0; i + 32 <= d.length; i++) {
            int o = i * 32; int first = d[o] & 0xff;
            if (first == 0) break;
            if (first == 0xE5) { lfnCount = 0; lfnName.setLength(0); continue; }
            int attr = d[o + 11] & 0xff;
            if (attr == 0x0f) {
                if ((first & 0x40) != 0) { lfnName.setLength(0); lfnCount = 0; lfnSum = d[o + 13] & 0xff; }
                StringBuilder part = new StringBuilder();
                int[] offs = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
                for (int k : offs) { int ch = u16le(d, o + k); if (ch == 0 || ch == 0xffff) break; part.append((char) ch); }
                lfnName.insert(0, part); lfnCount++; continue;
            }
            if ((attr & 0x08) != 0) { lfnCount = 0; lfnName.setLength(0); continue; }  // étiquette de volume
            Node n = new Node();
            String sn = shortName(d, o);
            if (sn.equals(".") || sn.equals("..")) { lfnCount = 0; lfnName.setLength(0); continue; }
            boolean useLfn = lfnCount > 0 && lfnSum == checksum(d, o);
            n.name = useLfn ? lfnName.toString() : sn; n.lfn = useLfn ? lfnCount : 0;
            n.shortNm = sn; n.attr = attr; n.dir = (attr & 0x10) != 0; n.size = u32(d, o + 28);
            n.cluster = (u16le(d, o + 20) << 16) | u16le(d, o + 26);
            n.mtime = dosTime(u16le(d, o + 24), u16le(d, o + 22)); n.parent = parent; n.idx = i;
            out.add(n); lfnCount = 0; lfnName.setLength(0);
        }
        return out;
    }
    private static String shortName(byte[] d, int o) {
        String base = new String(d, o, 8, StandardCharsets.ISO_8859_1).replaceAll(" +$", "");
        String ext = new String(d, o + 8, 3, StandardCharsets.ISO_8859_1).replaceAll(" +$", "");
        if ((d[o] & 0xff) == 0x05) base = (char) 0xE5 + base.substring(1);
        boolean lcB = (d[o + 12] & 0x08) != 0, lcE = (d[o + 12] & 0x10) != 0;
        if (lcB) base = base.toLowerCase(); if (lcE) ext = ext.toLowerCase();
        return ext.isEmpty() ? base : base + "." + ext;
    }

    // ================================================================== chemins
    private static String[] split(String p) {
        List<String> l = new ArrayList<>(); for (String s : p.split("/")) if (!s.isEmpty()) l.add(s);
        return l.toArray(new String[0]);
    }
    private int rootKey() { return type == 32 ? rootCluster : 0; }

    private Node find(List<Node> l, String name) {
        for (Node n : l) if (n.name.equalsIgnoreCase(name)) return n;
        return null;
    }
    /** Renvoie le nœud ou null ; le dossier racine est représenté par un nœud synthétique. */
    public Node stat(String path) throws IOException {
        String[] p = split(path);
        Node cur = new Node(); cur.name = ""; cur.dir = true; cur.cluster = rootKey(); cur.parent = -1;
        for (String s : p) {
            if (!cur.dir) return null;
            Node n = find(parse(loadDir(dirKey(cur.cluster)), dirKey(cur.cluster)), s);
            if (n == null) return null; cur = n;
        }
        return cur;
    }
    public List<Node> list(String path) throws IOException {
        Node d = stat(path); if (d == null || !d.dir) throw new IOException(Msg.t("Dossier introuvable : ", "Folder not found: ") + path);
        return parse(loadDir(dirKey(d.cluster)), dirKey(d.cluster));
    }
    private Node need(String path) throws IOException { Node n = stat(path); if (n == null) throw new IOException(Msg.t("Introuvable : ", "Not found: ") + path); return n; }

    // ================================================================== lecture / écriture de fichiers
    public int read(String path, long off, byte[] b, int o, int len) throws IOException {
        Node n = need(path); if (n.dir) throw new IOException(Msg.t("C'est un dossier", "This is a folder"));
        if (off >= n.size) return 0;
        len = (int) Math.min(len, n.size - off);
        List<Integer> ch = chain(n.cluster); int done = 0;
        while (done < len) {
            long pos = off + done; int ci = (int) (pos / clusterSize), co = (int) (pos % clusterSize);
            if (ci >= ch.size()) break;
            int k = Math.min(len - done, clusterSize - co);
            // fusionne les clusters contigus
            int run = 1; while (ci + run < ch.size() && ch.get(ci + run) == ch.get(ci + run - 1) + 1 && (long) run * clusterSize - co < len - done) run++;
            k = (int) Math.min(len - done, (long) run * clusterSize - co);
            dev.read(cOff(ch.get(ci)) + co, b, o + done, k); done += k;
        }
        return done;
    }

    public void write(String path, long off, byte[] b, int o, int len) throws IOException {
        Node n = need(path); if (n.dir) throw new IOException(Msg.t("C'est un dossier", "This is a folder"));
        long end = off + len;
        if (end > 0xffffffffL) throw new IOException(Msg.t("Fichier trop gros pour FAT (4 Gio max)", "File too large for FAT (4 GiB max)"));
        List<Integer> ch = chain(n.cluster);
        int need = (int) ((end + clusterSize - 1) / clusterSize);
        while (ch.size() < need) {
            int c = alloc();
            if (ch.isEmpty()) n.cluster = c; else fset(ch.get(ch.size() - 1), c);
            ch.add(c);
        }
        int done = 0;
        while (done < len) {
            long pos = off + done; int ci = (int) (pos / clusterSize), co = (int) (pos % clusterSize);
            int run = 1; while (ci + run < ch.size() && ch.get(ci + run) == ch.get(ci + run - 1) + 1 && (long) run * clusterSize - co < len - done) run++;
            int k = (int) Math.min(len - done, (long) run * clusterSize - co);
            dev.write(cOff(ch.get(ci)) + co, b, o + done, k); done += k;
        }
        if (end > n.size) n.size = end;
        n.mtime = System.currentTimeMillis(); updateEntry(n);
    }

    public void setLength(String path, long len) throws IOException {
        Node n = need(path); if (n.dir) throw new IOException(Msg.t("C'est un dossier", "This is a folder"));
        if (len > 0xffffffffL) throw new IOException(Msg.t("Fichier trop gros pour FAT", "File too large for FAT"));
        List<Integer> ch = chain(n.cluster); int need = (int) ((len + clusterSize - 1) / clusterSize);
        if (len < n.size && len % clusterSize != 0 && need > 0) {          // efface la fin du dernier cluster conservé
            int co = (int) (len % clusterSize); dev.write(cOff(ch.get(need - 1)) + co, new byte[clusterSize - co], 0, clusterSize - co);
        }
        while (ch.size() > need) { int c = ch.remove(ch.size() - 1); fset(c, 0); }
        if (need > 0 && ch.size() >= need) fset(ch.get(need - 1), eocVal());
        while (ch.size() < need) { int c = alloc(); if (ch.isEmpty()) n.cluster = c; else fset(ch.get(ch.size() - 1), c); ch.add(c); }
        if (need == 0) n.cluster = 0;
        n.size = len; n.mtime = System.currentTimeMillis(); updateEntry(n);
    }

    private void updateEntry(Node n) throws IOException {
        byte[] d = loadDir(n.parent); int o = n.idx * 32;
        put16(d, o + 20, n.cluster >>> 16); put16(d, o + 26, n.cluster & 0xffff);
        put32(d, o + 28, n.size); int[] t = toDos(n.mtime); put16(d, o + 22, t[0]); put16(d, o + 24, t[1]);
        d[o + 11] = (byte) n.attr; storeDir(n.parent, d);
    }

    // ================================================================== création / suppression / renommage
    public void createFile(String path) throws IOException { create(path, false); }
    public void mkdir(String path) throws IOException { create(path, true); }

    private void create(String path, boolean dir) throws IOException {
        String[] p = split(path); if (p.length == 0) throw new IOException(Msg.t("Chemin invalide", "Invalid path"));
        String name = p[p.length - 1]; checkName(name);
        Node par = need("/" + String.join("/", Arrays.copyOf(p, p.length - 1)));
        if (!par.dir) throw new IOException(Msg.t("Le parent n'est pas un dossier", "The parent is not a folder"));
        int pk = dirKey(par.cluster);
        byte[] d = loadDir(pk);
        if (find(parse(d, pk), name) != null) throw new IOException(Msg.t("Existe déjà : ", "Already exists: ") + name);
        int cl = 0;
        if (dir) {
            cl = alloc(); byte[] c = new byte[clusterSize];
            entry(c, 0, ".          ".getBytes(StandardCharsets.ISO_8859_1), 0x10, cl, 0);
            entry(c, 32, "..         ".getBytes(StandardCharsets.ISO_8859_1), 0x10, pk == rootKey() ? 0 : pk, 0);
            dev.write(cOff(cl), c, 0, clusterSize);
        }
        insertEntry(pk, d, name, dir ? 0x10 : 0x20, cl, 0, System.currentTimeMillis());
    }

    private void insertEntry(int pk, byte[] d, String name, int attr, int cluster, long size, long mtime) throws IOException {
        List<Node> existing = parse(d, pk);
        byte[] sn = makeShort(name, existing);
        boolean needLfn = !isPlainShort(name, sn);
        int[] cps = name.codePoints().toArray(); String uname = name;
        int lfn = needLfn ? (name.length() + 12) / 13 : 0, slots = lfn + 1;
        int at = findFree(d, slots);
        if (at < 0) {
            if (pk == 0) throw new IOException(Msg.t("Dossier racine plein", "Root folder is full"));
            int oldLen = d.length; d = Arrays.copyOf(d, oldLen + clusterSize); at = findFree(d, slots);
            if (at < 0) throw new IOException(Msg.t("Dossier plein", "Folder is full"));
        }
        int sum = checksumRaw(sn);
        for (int k = 0; k < lfn; k++) {
            int seq = lfn - k, o = (at + k) * 32; Arrays.fill(d, o, o + 32, (byte) 0);
            d[o] = (byte) (seq | (k == 0 ? 0x40 : 0)); d[o + 11] = 0x0f; d[o + 13] = (byte) sum;
            int[] offs = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
            for (int j = 0; j < 13; j++) {
                int ci = (seq - 1) * 13 + j; int ch = ci < name.length() ? name.charAt(ci) : ci == name.length() ? 0 : 0xffff;
                put16(d, o + offs[j], ch);
            }
        }
        int o = (at + lfn) * 32; entry(d, o, sn, attr, cluster, size);
        int[] t = toDos(mtime); put16(d, o + 22, t[0]); put16(d, o + 24, t[1]); put16(d, o + 16, t[1]); put16(d, o + 18, t[1]);
        storeDir(pk, d);
    }
    private int findFree(byte[] d, int slots) {
        int run = 0;
        for (int i = 0; i * 32 < d.length; i++) {
            int f = d[i * 32] & 0xff;
            if (f == 0 || f == 0xE5) { if (++run == slots) return i - slots + 1; } else run = 0;
        }
        return -1;
    }
    private static void entry(byte[] d, int o, byte[] sn, int attr, int cluster, long size) {
        System.arraycopy(sn, 0, d, o, 11); d[o + 11] = (byte) attr;
        for (int i = 12; i < 32; i++) d[o + i] = 0;
        put16(d, o + 20, cluster >>> 16); put16(d, o + 26, cluster & 0xffff); put32(d, o + 28, size);
    }
    private static void checkName(String n) throws IOException {
        if (n.length() > 255 || n.equals(".") || n.equals("..") || n.matches(".*[\\\\/:*?\"<>|\\x00-\\x1f].*") || n.endsWith(" ") || n.endsWith("."))
            throw new IOException(Msg.t("Nom de fichier invalide pour FAT : ", "Invalid file name for FAT: ") + n);
    }
    private static boolean isPlainShort(String name, byte[] sn) {
        String base = new String(sn, 0, 8, StandardCharsets.ISO_8859_1).trim(), ext = new String(sn, 8, 3, StandardCharsets.ISO_8859_1).trim();
        String re = ext.isEmpty() ? base : base + "." + ext;
        return re.equals(name);
    }
    private byte[] makeShort(String name, List<Node> existing) {
        String up = name.toUpperCase(Locale.ROOT); int dot = up.lastIndexOf('.');
        String b = dot > 0 ? up.substring(0, dot) : up, e = dot > 0 ? up.substring(dot + 1) : "";
        b = clean(b); e = clean(e); if (e.length() > 3) e = e.substring(0, 3);
        boolean lossy = !b.equals(dot > 0 ? up.substring(0, dot) : up) || b.length() > 8 || !e.equals(dot > 0 ? up.substring(dot + 1) : "") || (dot < 0 && name.contains(" "));
        if (b.isEmpty()) b = "_";
        String base = b.length() > 8 ? b.substring(0, 8) : b;
        Set<String> used = new HashSet<>();
        for (Node n : existing) used.add(n.name.toUpperCase(Locale.ROOT));
        // les noms courts existants sont reconstitués depuis les entrées ; on teste aussi la forme courte
        String cand = e.isEmpty() ? base : base + "." + e;
        if (!lossy && !used.contains(cand) && !shortTaken(existing, base, e)) return pad(base, e);
        for (int i = 1; i < 1000000; i++) {
            String tail = "~" + i; String bb = base.substring(0, Math.min(base.length(), 8 - tail.length())) + tail;
            if (!shortTaken(existing, bb, e)) return pad(bb, e);
        }
        throw new IllegalStateException("noms courts épuisés");
    }
    private boolean shortTaken(List<Node> ex, String b, String e) {
        String s = e.isEmpty() ? b : b + "." + e;
        for (Node n : ex) { if (n.name.equalsIgnoreCase(s)) return true; if (n.shortNm != null && n.shortNm.equalsIgnoreCase(s)) return true; }
        return false;
    }
    private static String clean(String s) { return s.replaceAll("[^A-Z0-9!#$%&'()\\-@^_`{}~]", ""); }
    private static byte[] pad(String b, String e) {
        byte[] r = new byte[11]; Arrays.fill(r, (byte) ' ');
        byte[] bb = b.getBytes(StandardCharsets.ISO_8859_1), eb = e.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bb, 0, r, 0, Math.min(8, bb.length)); System.arraycopy(eb, 0, r, 8, Math.min(3, eb.length));
        if ((r[0] & 0xff) == 0xE5) r[0] = 0x05; return r;
    }
    private static int checksumRaw(byte[] sn) { int s = 0; for (int i = 0; i < 11; i++) s = (((s & 1) << 7) | ((s & 0xfe) >> 1)) + (sn[i] & 0xff) & 0xff; return s; }
    private static int checksum(byte[] d, int o) { int s = 0; for (int i = 0; i < 11; i++) s = (((s & 1) << 7) | ((s & 0xfe) >> 1)) + (d[o + i] & 0xff) & 0xff; return s; }

    public void delete(String path) throws IOException {
        Node n = need(path); if (n.parent < 0) throw new IOException(Msg.t("Impossible de supprimer la racine", "Cannot delete the root folder"));
        if (n.dir) for (Node c : list(path)) { throw new IOException(Msg.t("Dossier non vide", "Folder is not empty")); }
        removeEntries(n); if (n.cluster != 0) freeChain(n.cluster);
    }
    private void removeEntries(Node n) throws IOException {
        byte[] d = loadDir(n.parent);
        for (int i = n.idx - n.lfn; i <= n.idx; i++) d[i * 32] = (byte) 0xE5;
        storeDir(n.parent, d);
    }
    /** Déplace/renomme sans copier les données. */
    public void rename(String from, String to) throws IOException {
        Node n = need(from); String[] p = split(to); if (p.length == 0) throw new IOException(Msg.t("Chemin invalide", "Invalid path"));
        String name = p[p.length - 1]; checkName(name);
        Node par = need("/" + String.join("/", Arrays.copyOf(p, p.length - 1)));
        if (!par.dir) throw new IOException(Msg.t("Destination invalide", "Invalid destination"));
        if (find(parse(loadDir(dirKey(par.cluster)), dirKey(par.cluster)), name) != null) throw new IOException(Msg.t("Existe déjà", "Already exists"));
        if (n.dir && (to + "/").startsWith(from + "/")) throw new IOException(Msg.t("Déplacement dans soi-même", "Cannot move a folder into itself"));
        removeEntries(n);
        int pk = dirKey(par.cluster);
        insertEntry(pk, loadDir(pk), name, n.attr, n.cluster, n.size, n.mtime);
        if (n.dir && n.cluster != 0) {   // met à jour ".."
            byte[] c = new byte[clusterSize]; dev.read(cOff(n.cluster), c, 0, clusterSize);
            int pc = pk == rootKey() ? 0 : pk; put16(c, 32 + 20, pc >>> 16); put16(c, 32 + 26, pc & 0xffff);
            dev.write(cOff(n.cluster), c, 0, clusterSize);
        }
    }

    // ================================================================== formatage
    public static FatFs format(BlockDev dev, String label) throws IOException {
        long tot = dev.size() / 512; if (tot < 64) throw new IOException(Msg.t("Volume trop petit pour FAT", "Volume too small for FAT"));
        int type = 0, spc = 0, fsz = 0, res, rootEnt, nFats = 2;
        long bytes = tot * 512;
        outer:
        if (bytes <= 512L * 1024 * 1024) {
            boolean small = bytes <= 16L * 1024 * 1024;
            for (int t : new int[]{12, 16}) for (int s = 1; s <= 64; s *= 2) {
                if (t == 12 && !small) continue;
                res = 1; rootEnt = 512; long rootSecs = 32;
                int fs = 1; int cnt = 0;
                for (int it = 0; it < 20; it++) { cnt = (int) ((tot - res - nFats * fs - rootSecs) / s);
                    int need = (int) ((((long) (cnt + 2) * (t == 12 ? 3 : 4) / 2) + 511) / 512); if (t == 16) need = (int) ((((long) (cnt + 2) * 2) + 511) / 512); if (need == fs) break; fs = need; }
                if ((t == 12 && cnt >= 1 && cnt < 4085) || (t == 16 && cnt >= 4085 && cnt < 65525)) { type = t; spc = s; fsz = fs; break outer; }
            }
        }
        if (type == 0) {
            type = 32; spc = bytes <= 8L << 30 ? 8 : bytes <= 16L << 30 ? 16 : bytes <= 32L << 30 ? 32 : 64;
            if (bytes <= 260L << 20) spc = 1; else if (bytes <= 8L << 30) spc = 8;
            int fs = 1; for (int it = 0; it < 30; it++) { int cnt = (int) ((tot - 32 - 2L * fs) / spc); int need = (int) (((long) (cnt + 2) * 4 + 511) / 512); if (need == fs) break; fs = need; }
            fsz = fs; if ((tot - 32 - 2L * fs) / spc < 65525) throw new IOException(Msg.t("Volume trop petit pour FAT32", "Volume too small for FAT32"));
        }
        res = type == 32 ? 32 : 1; rootEnt = type == 32 ? 0 : 512;
        byte[] b = new byte[512];
        b[0] = (byte) 0xEB; b[1] = type == 32 ? (byte) 0x58 : (byte) 0x3C; b[2] = (byte) 0x90;
        System.arraycopy("MSDOS5.0".getBytes(), 0, b, 3, 8);
        put16(b, 11, 512); b[13] = (byte) spc; put16(b, 14, res); b[16] = 2; put16(b, 17, rootEnt);
        if (tot < 65536 && type != 32) put16(b, 19, (int) tot); else put32(b, 32, tot);
        b[21] = (byte) 0xF8; put16(b, 24, 63); put16(b, 26, 255);
        int vol = new Random().nextInt(); String lab = String.format("%-11s", label == null || label.isEmpty() ? "NO NAME" : label.toUpperCase()).substring(0, 11);
        int bo;
        if (type == 32) { put32(b, 36, fsz); put32(b, 44, 2); put16(b, 48, 1); put16(b, 50, 6); bo = 64; }
        else { put16(b, 22, fsz); bo = 36; }
        b[bo] = (byte) 0x80; b[bo + 2] = 0x29; put32(b, bo + 3, vol & 0xffffffffL);
        System.arraycopy(lab.getBytes(), 0, b, bo + 7, 11);
        System.arraycopy((type == 32 ? "FAT32   " : type == 16 ? "FAT16   " : "FAT12   ").getBytes(), 0, b, bo + 18, 8);
        b[510] = 0x55; b[511] = (byte) 0xAA;
        dev.write(0, b, 0, 512);
        long fatStart = res * 512L, fb = fsz * 512L;
        byte[] z = new byte[(int) Math.min(fb, 1 << 20)];
        for (int i = 0; i < 2; i++) for (long p = 0; p < fb; p += z.length) dev.write(fatStart + i * fb + p, z, 0, (int) Math.min(z.length, fb - p));
        byte[] f0 = new byte[12];
        if (type == 32) { put32(f0, 0, 0x0ffffff8L); put32(f0, 4, 0x0fffffffL); put32(f0, 8, 0x0fffffffL); }
        else if (type == 16) { put16(f0, 0, 0xfff8); put16(f0, 2, 0xffff); }
        else { f0[0] = (byte) 0xf8; f0[1] = (byte) 0xff; f0[2] = (byte) 0xff; }
        for (int i = 0; i < 2; i++) dev.write(fatStart + i * fb, f0, 0, type == 32 ? 12 : 4);
        if (type == 32) {
            byte[] fi = new byte[512]; put32(fi, 0, 0x41615252L); put32(fi, 484, 0x61417272L); put32(fi, 488, 0xffffffffL); put32(fi, 492, 0xffffffffL); put32(fi, 508, 0xaa550000L);
            dev.write(512, fi, 0, 512); dev.write(7 * 512L, fi, 0, 512); dev.write(6 * 512L, b, 0, 512);
            dev.write(res * 512L + 2L * fb, new byte[spc * 512], 0, spc * 512);       // cluster racine
        } else dev.write(fatStart + 2 * fb, new byte[rootEnt * 32], 0, rootEnt * 32);
        return mount(dev);
    }

    // ================================================================== utilitaires
    static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    static int u16le(byte[] b, int o) { return u16(b, o); }
    static long u32(byte[] b, int o) { return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24; }
    static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); }
    static void put32(byte[] b, int o, long v) { put16(b, o, (int) v); put16(b, o + 2, (int) (v >>> 16)); }
    private static int[] toDos(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        int y = Math.max(1980, c.get(Calendar.YEAR));
        return new int[]{(c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5) | (c.get(Calendar.SECOND) / 2),
                ((y - 1980) << 9) | ((c.get(Calendar.MONTH) + 1) << 5) | c.get(Calendar.DAY_OF_MONTH)};
    }
    private static long dosTime(int date, int time) {
        if (date == 0) return 0; Calendar c = Calendar.getInstance(); c.clear();
        c.set(1980 + (date >> 9), Math.max(0, ((date >> 5) & 15) - 1), Math.max(1, date & 31), time >> 11, (time >> 5) & 63, (time & 31) * 2);
        return c.getTimeInMillis();
    }
}
