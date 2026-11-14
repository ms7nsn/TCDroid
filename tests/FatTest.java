import com.tcdroid.fat.*;
import java.io.*;
import java.util.*;

public class FatTest {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "OK   " : "FAIL ") + n); if (!ok) fails++; }
    static BlockDev dev(RandomAccessFile r, long size) {
        return new BlockDev() {
            public long size() { return size; }
            public void read(long o, byte[] b, int off, int l) throws IOException { r.seek(o); r.readFully(b, off, l); }
            public void write(long o, byte[] b, int off, int l) throws IOException { r.seek(o); r.write(b, off, l); }
        };
    }
    public static void main(String[] a) throws Exception {
        long[] sizes = {3L << 20, 40L << 20, 300L << 20, 1200L << 20};
        for (long sz : sizes) {
            File f = File.createTempFile("fat", ".img"); f.deleteOnExit();
            RandomAccessFile r = new RandomAccessFile(f, "rw"); r.setLength(sz);
            BlockDev d = dev(r, sz);
            FatFs fs = FatFs.format(d, "TEST");
            String tag = (sz >> 20) + " Mio FAT" + fs.fatType();
            Random rnd = new Random(sz);
            fs.mkdir("/Documents"); fs.mkdir("/Documents/Sous dossier avec espaces");
            byte[] big = new byte[Math.min((int) (sz / 8), 3_000_000)]; rnd.nextBytes(big);
            String[] names = {"/Documents/rapport très long avec accents é à ü.txt", "/Documents/Sous dossier avec espaces/données.bin", "/PETIT.TXT", "/minuscule.txt", "/Documents/rapport très long avec accents é à ü 2.txt"};
            for (String n : names) { fs.createFile(n); fs.write(n, 0, big, 0, big.length); }
            fs.write("/PETIT.TXT", 100, "salut".getBytes(), 0, 5);
            for (int i = 0; i < 60; i++) fs.createFile("/Documents/f" + i + ".dat");     // force l'extension du dossier
            fs.flush();
            FatFs m = FatFs.mount(d);
            boolean ok = true;
            for (int i = 0; i < 3; i++) { String n = names[i == 2 ? 1 : i]; byte[] g = new byte[big.length]; int k = m.read(n, 0, g, 0, g.length); ok &= k == big.length && Arrays.equals(g, big); }
            check(tag + " : fichiers à noms longs relus après remontage", ok);
            byte[] g = new byte[5]; m.read("/PETIT.TXT", 100, g, 0, 5);
            check(tag + " : écriture avec trou (zéros) + relecture", new String(g).equals("salut") && m.stat("/PETIT.TXT").size == big.length);
            check(tag + " : liste du dossier (62 entrées)", m.list("/Documents").size() == 62 + 0 || m.list("/Documents").size() == 63);
            check(tag + " : insensible à la casse", m.stat("/MINUSCULE.TXT") != null && m.stat("/documents/F5.DAT") != null);
            long free0 = m.freeSpace();
            m.setLength("/Documents/rapport très long avec accents é à ü.txt", 1000);
            check(tag + " : troncature libère de l'espace", m.freeSpace() > free0 && m.stat("/Documents/rapport très long avec accents é à ü.txt").size == 1000);
            m.rename("/Documents/Sous dossier avec espaces", "/Déplacé");
            byte[] g2 = new byte[big.length]; m.read("/Déplacé/données.bin", 0, g2, 0, g2.length);
            check(tag + " : renommage/déplacement de dossier", Arrays.equals(g2, big) && m.stat("/Documents/Sous dossier avec espaces") == null);
            try { m.delete("/Documents"); check(tag + " : refuse de supprimer un dossier non vide", false); } catch (IOException e) { check(tag + " : refuse de supprimer un dossier non vide", true); }
            long free1 = m.freeSpace(); m.delete("/Déplacé/données.bin"); m.delete("/Déplacé");
            check(tag + " : suppression récupère l'espace", m.freeSpace() > free1 && m.stat("/Déplacé") == null);
            m.flush(); FatFs m2 = FatFs.mount(d);
            check(tag + " : cohérence après nouveau remontage", m2.stat("/PETIT.TXT") != null && m2.stat("/Déplacé") == null && m2.freeSpace() == m.freeSpace());
            r.close();
        }
        System.out.println(fails == 0 ? "TOUS LES TESTS FAT PASSENT" : fails + " ÉCHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
