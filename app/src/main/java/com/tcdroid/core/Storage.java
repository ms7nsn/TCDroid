package com.tcdroid.core;

import java.io.Closeable;
import java.io.IOException;

/** Accès aléatoire à l'hôte du volume (fichier conteneur ou périphérique). */
public interface Storage extends Closeable {
    long length() throws IOException;
    void read(long offset, byte[] buf, int off, int len) throws IOException;
    void write(long offset, byte[] buf, int off, int len) throws IOException;
    void flush() throws IOException;
}
