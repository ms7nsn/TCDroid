package com.tcdroid;

import com.tcdroid.core.Msg;
import android.os.ParcelFileDescriptor;

import com.tcdroid.core.Storage;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Accès au conteneur choisi via le Storage Access Framework (lecture positionnelle + écriture). */
final class PfdStorage implements Storage {
    private final ParcelFileDescriptor pfd;
    private final FileChannel in, out;

    PfdStorage(ParcelFileDescriptor pfd, boolean writable) {
        this.pfd = pfd;
        this.in = new FileInputStream(pfd.getFileDescriptor()).getChannel();
        this.out = writable ? new FileOutputStream(pfd.getFileDescriptor()).getChannel() : null;
    }

    @Override public long length() throws IOException { return in.size(); }

    @Override public void read(long off, byte[] b, int o, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, o, len);
        long pos = off;
        while (bb.hasRemaining()) {
            int n = in.read(bb, pos);
            if (n < 0) throw new IOException(Msg.t("Fin de fichier inattendue", "Unexpected end of file"));
            pos += n;
        }
    }

    @Override public void write(long off, byte[] b, int o, int len) throws IOException {
        if (out == null) throw new IOException(Msg.t("Conteneur ouvert en lecture seule", "Container opened read-only"));
        ByteBuffer bb = ByteBuffer.wrap(b, o, len);
        long pos = off;
        while (bb.hasRemaining()) pos += out.write(bb, pos);
    }

    @Override public void flush() throws IOException { if (out != null) out.force(false); }

    /** Étend le fichier à size octets (fichier creux). */
    void extendTo(long size) throws IOException {
        if (out == null) throw new IOException("lecture seule");
        out.write(ByteBuffer.wrap(new byte[1]), size - 1);
    }

    @Override public void close() throws IOException { pfd.close(); }
}
