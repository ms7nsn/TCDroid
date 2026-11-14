package com.tcdroid.core;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.digests.WhirlpoolDigest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.SerpentEngine;
import org.bouncycastle.crypto.engines.TwofishEngine;

/** Algorithmes de TrueCrypt 7.1a (mode XTS). */
public final class Algorithms {
    private Algorithms() {}

    /** Fonctions de dérivation PBKDF2 (itérations de Pkcs5.c, hors démarrage système). */
    public enum Prf {
        RIPEMD160("RIPEMD-160", 2000), SHA512("SHA-512", 1000),
        WHIRLPOOL("Whirlpool", 1000), SHA1("SHA-1 (ancien)", 2000);
        public final String label; public final int iterations;
        Prf(String l, int it) { label = l; iterations = it; }
        public Digest digest() {
            switch (this) {
                case RIPEMD160: return new RIPEMD160Digest();
                case SHA512: return new SHA512Digest();
                case WHIRLPOOL: return new WhirlpoolDigest();
                default: return new SHA1Digest();
            }
        }
    }

    public enum Cipher {
        AES, SERPENT, TWOFISH;
        BlockCipher engine() {
            switch (this) {
                case AES: return new AESEngine();
                case SERPENT: return new SerpentEngine();
                default: return new TwofishEngine();
            }
        }
    }

    /**
     * Algorithmes de chiffrement. Les chiffrements d'une cascade sont listés dans l'ordre de
     * Crypto.c : chiffrement = premier vers dernier, déchiffrement = dernier vers premier.
     */
    public enum Ea {
        AES("AES", Cipher.AES),
        SERPENT("Serpent", Cipher.SERPENT),
        TWOFISH("Twofish", Cipher.TWOFISH),
        AES_TWOFISH("AES-Twofish", Cipher.TWOFISH, Cipher.AES),
        AES_TWOFISH_SERPENT("AES-Twofish-Serpent", Cipher.SERPENT, Cipher.TWOFISH, Cipher.AES),
        SERPENT_AES("Serpent-AES", Cipher.AES, Cipher.SERPENT),
        SERPENT_TWOFISH_AES("Serpent-Twofish-AES", Cipher.AES, Cipher.TWOFISH, Cipher.SERPENT),
        TWOFISH_SERPENT("Twofish-Serpent", Cipher.SERPENT, Cipher.TWOFISH);

        public final String label; public final Cipher[] ciphers;
        Ea(String l, Cipher... c) { label = l; ciphers = c; }
        /** Taille de clé totale d'un jeu (primaire ou secondaire) : 32 octets par chiffrement. */
        public int keySize() { return 32 * ciphers.length; }
    }
}
