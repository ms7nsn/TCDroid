import com.tcdroid.core.*;
import com.tcdroid.core.Algorithms.*;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public class CoreTest {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "OK   " : "FAIL ") + n); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        byte[] kf = Files.readAllBytes(Paths.get("kf.bin"));
        // 1) volume généré par Python : normal + clés + caché
        File f = new File("py.tc");
        byte[] pwOuter = Keyfiles.apply("outerpass".getBytes(), List.of(new ByteArrayInputStream(kf)));
        TcVolume v = TcVolume.open(new FileStorage(f, true), pwOuter, false);
        check("ouvre le volume normal (mot de passe + fichier de clés), hidden=false", !v.isHidden());
        byte[] all = new byte[(int) v.size()]; v.read(0, all, 0, all.length);
        boolean same = true; for (int i = 0; i < all.length - (1 << 18); i++) if (all[i] != (byte) ((i * 7 + 3) & 0xff)) { same = false; break; }
        check("lit 1 Mio déchiffré identique au contenu Python (AES-XTS, SHA-512)", same);
        byte[] w = new byte[777]; for (int i = 0; i < w.length; i++) w[i] = (byte) ((i * 31 + 1) & 0xff);
        v.write(5000, w, 0, w.length);
        byte[] back = new byte[777]; v.read(5000, back, 0, 777);
        check("écriture non alignée puis relecture", Arrays.equals(w, back));
        v.close();
        try { TcVolume.open(new FileStorage(f, false), "outerpass".getBytes(), false); check("refuse sans fichier de clés", false); }
        catch (TcVolume.WrongPasswordException e) { check("refuse sans fichier de clés", true); }
        TcVolume hv = TcVolume.open(new FileStorage(f, true), "hiddenpass".getBytes(), false);
        check("ouvre le volume caché, hidden=true", hv.isHidden());
        byte[] hb = new byte[(int) hv.size()]; hv.read(0, hb, 0, hb.length);
        boolean hs = true; for (int i = 0; i < hb.length; i++) if (hb[i] != (byte) ((i * 13 + 5) & 0xff)) { hs = false; break; }
        check("volume caché : contenu identique au contenu Python", hs);
        byte[] w2 = new byte[1500]; for (int i = 0; i < w2.length; i++) w2[i] = (byte) ((i * 17 + 9) & 0xff);
        hv.write(3001, w2, 0, w2.length); hv.close();
        TcVolume bk = TcVolume.open(new FileStorage(f, false), pwOuter, true);
        check("ouvre via l'en-tête de secours", !bk.isHidden() && bk.size() == all.length); bk.close();

        // 2) toutes les combinaisons PRF x algorithme : création -> ouverture -> E/S -> changement de mot de passe
        SecureRandom rnd = new SecureRandom();
        int combos = 0, bad = 0;
        for (Prf p : Prf.values()) for (Ea e : Ea.values()) {
            File t = File.createTempFile("tcx", ".tc"); t.deleteOnExit();
            byte[] pw = ("pw-" + p + e).getBytes();
            try (RandomAccessFile r = new RandomAccessFile(t, "rw")) { r.setLength(300000); }
            TcVolume c = TcVolume.create(new FileStorage(t, true), 300000, pw, p, e, rnd, null);
            byte[] d = new byte[3000]; rnd.nextBytes(d); c.write(1234, d, 0, d.length); c.close();
            TcVolume o = TcVolume.open(new FileStorage(t, true), pw, false);
            byte[] g = new byte[3000]; o.read(1234, g, 0, g.length);
            boolean ok = Arrays.equals(d, g) && o.cipher() == e && o.prf() == p;
            o.changePassword("nouveau".getBytes(), Prf.SHA512, rnd); o.close();
            TcVolume o2 = TcVolume.open(new FileStorage(t, false), "nouveau".getBytes(), false);
            o2.read(1234, g, 0, g.length); ok &= Arrays.equals(d, g); o2.close();
            combos++; if (!ok) { bad++; System.out.println("   échec " + p + " / " + e); }
        }
        check(combos + " combinaisons PRF x algorithme (création, E/S, changement de mot de passe)", bad == 0);

        // 3) volume caché créé par Java
        File t = File.createTempFile("tch1", ".tc"); t.deleteOnExit();
        try (RandomAccessFile r = new RandomAccessFile(t, "rw")) { r.setLength(2_000_000); }
        TcVolume out = TcVolume.create(new FileStorage(t, true), 2_000_000, "outer".getBytes(), Prf.SHA512, Ea.AES, rnd, null);
        out.close();
        TcVolume hid = TcVolume.createHidden(new FileStorage(t, true), "inner".getBytes(), Prf.WHIRLPOOL, Ea.SERPENT_TWOFISH_AES, 500000, rnd, null);
        byte[] d = new byte[900]; rnd.nextBytes(d); hid.write(100, d, 0, 900); hid.close();
        TcVolume h2 = TcVolume.open(new FileStorage(t, true), "inner".getBytes(), false);
        byte[] g = new byte[900]; h2.read(100, g, 0, 900); h2.close();
        TcVolume o3 = TcVolume.open(new FileStorage(t, true), "outer".getBytes(), false);
        check("volume caché créé en Java : relecture + volume extérieur toujours ouvrable",
              Arrays.equals(d, g) && h2.isHidden() && !o3.isHidden()); o3.close();
        System.out.println(fails == 0 ? "TOUS LES TESTS PASSENT" : fails + " ÉCHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
