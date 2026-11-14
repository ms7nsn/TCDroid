package com.tcdroid.fat;

import java.io.IOException;

/** Périphérique bloc sous-jacent (le contenu déchiffré d'un volume TrueCrypt). */
public interface BlockDev {
    long size();
    void read(long off, byte[] b, int o, int len) throws IOException;
    void write(long off, byte[] b, int o, int len) throws IOException;
}
