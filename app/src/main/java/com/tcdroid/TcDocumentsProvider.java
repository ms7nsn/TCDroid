package com.tcdroid;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;
import android.provider.DocumentsContract;

import com.tcdroid.core.Msg;
import com.tcdroid.fat.FatFs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Expose le volume déverrouillé comme un « stockage » dans l'application Fichiers d'Android. */
public class TcDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "com.tcdroid.documents";
    static final String ROOT_ID = "tcvolume";

    private static final String[] ROOT_COLS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOC_COLS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};

    private static HandlerThread ioThread;
    private static Handler ioHandler;

    private static synchronized Handler handler() {
        if (ioHandler == null) {
            ioThread = new HandlerThread("tcdroid-io");
            ioThread.start();
            ioHandler = new Handler(ioThread.getLooper());
        }
        return ioHandler;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : ROOT_COLS);
        if (Session.isUnlocked()) {
            long free = 0;
            try { synchronized (Session.LOCK) { free = Session.fs().freeSpace(); } } catch (IOException ignored) { }
            MatrixCursor.RowBuilder r = c.newRow();
            r.add(Root.COLUMN_ROOT_ID, ROOT_ID);
            r.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
            r.add(Root.COLUMN_TITLE, "TCDroid : " + Session.name());
            r.add(Root.COLUMN_SUMMARY, Session.isHidden() ? Msg.t("Volume caché déverrouillé", "Hidden volume unlocked") : Msg.t("Volume déverrouillé", "Volume unlocked"));
            r.add(Root.COLUMN_DOCUMENT_ID, "/");
            r.add(Root.COLUMN_ICON, android.R.drawable.ic_lock_lock);
            r.add(Root.COLUMN_AVAILABLE_BYTES, free);
        }
        return c;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_COLS);
        try { synchronized (Session.LOCK) { addRow(c, id, Session.fs().stat(id)); } }
        catch (IOException e) { throw nf(e); }
        return c;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_COLS);
        try {
            synchronized (Session.LOCK) {
                for (FatFs.Node n : Session.fs().list(parent)) addRow(c, child(parent, n.name), n);
            }
        } catch (IOException e) { throw nf(e); }
        return c;
    }

    private void addRow(MatrixCursor c, String id, FatFs.Node n) throws FileNotFoundException {
        if (n == null) throw new FileNotFoundException(id);
        int flags;
        if (n.dir) flags = Document.FLAG_DIR_SUPPORTS_CREATE | (id.equals("/") ? 0 : Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME);
        else flags = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(Document.COLUMN_DOCUMENT_ID, id);
        r.add(Document.COLUMN_DISPLAY_NAME, id.equals("/") ? Session.name() : n.name);
        r.add(Document.COLUMN_SIZE, n.dir ? null : n.size);
        r.add(Document.COLUMN_MIME_TYPE, n.dir ? Document.MIME_TYPE_DIR : mime(n.name));
        r.add(Document.COLUMN_LAST_MODIFIED, n.mtime == 0 ? null : n.mtime);
        r.add(Document.COLUMN_FLAGS, flags);
    }

    static String child(String parent, String name) { return parent.equals("/") ? "/" + name : parent + "/" + name; }
    private static String parentOf(String id) { int i = id.lastIndexOf('/'); return i <= 0 ? "/" : id.substring(0, i); }
    private static String mime(String name) {
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(i + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
    private static FileNotFoundException nf(IOException e) {
        FileNotFoundException f = new FileNotFoundException(String.valueOf(e.getMessage())); f.initCause(e); return f;
    }

    @Override public boolean isChildDocument(String parent, String doc) {
        return parent.equals("/") ? doc.startsWith("/") && !doc.equals("/") : doc.startsWith(parent + "/");
    }

    @Override public String getDocumentType(String id) throws FileNotFoundException {
        try { synchronized (Session.LOCK) { FatFs.Node n = Session.fs().stat(id); if (n == null) throw new FileNotFoundException(id); return n.dir ? Document.MIME_TYPE_DIR : mime(n.name); } }
        catch (IOException e) { throw nf(e); }
    }

    @Override public String createDocument(String parent, String mimeType, String displayName) throws FileNotFoundException {
        String id = child(parent, displayName);
        try {
            synchronized (Session.LOCK) {
                FatFs fs = Session.fs();
                if (Document.MIME_TYPE_DIR.equals(mimeType)) fs.mkdir(id); else fs.createFile(id);
                fs.flush();
            }
        } catch (IOException e) { throw nf(e); }
        changed(parent);
        return id;
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        try { synchronized (Session.LOCK) { FatFs fs = Session.fs(); deleteRec(fs, id); fs.flush(); } }
        catch (IOException e) { throw nf(e); }
        changed(parentOf(id));
    }
    private static void deleteRec(FatFs fs, String id) throws IOException {
        FatFs.Node n = fs.stat(id);
        if (n == null) throw new FileNotFoundException(id);
        if (n.dir) for (FatFs.Node c : fs.list(id)) deleteRec(fs, child(id, c.name));
        fs.delete(id);
    }

    @Override public String renameDocument(String id, String newName) throws FileNotFoundException {
        String target = child(parentOf(id), newName);
        try { synchronized (Session.LOCK) { FatFs fs = Session.fs(); fs.rename(id, target); fs.flush(); } }
        catch (IOException e) { throw nf(e); }
        changed(parentOf(id));
        return target;
    }

    @Override public String moveDocument(String id, String srcParent, String dstParent) throws FileNotFoundException {
        String name = id.substring(id.lastIndexOf('/') + 1), target = child(dstParent, name);
        try { synchronized (Session.LOCK) { FatFs fs = Session.fs(); fs.rename(id, target); fs.flush(); } }
        catch (IOException e) { throw nf(e); }
        changed(srcParent); changed(dstParent);
        return target;
    }

    private void changed(String parent) {
        if (getContext() != null)
            getContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, parent), null);
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        final boolean write = mode.contains("w");
        try {
            synchronized (Session.LOCK) {
                FatFs fs = Session.fs();
                FatFs.Node n = fs.stat(id);
                if (n == null || n.dir) throw new FileNotFoundException(id);
                if (mode.contains("t")) fs.setLength(id, 0);
            }
            StorageManager sm = getContext().getSystemService(StorageManager.class);
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.parseMode(mode), new Proxy(id, write), handler());
        } catch (IOException e) { throw nf(e); }
    }

    /** Fait le pont entre le descripteur de fichier donné au client et le volume chiffré. */
    private static final class Proxy extends ProxyFileDescriptorCallback {
        private final String path; private final boolean writable;
        Proxy(String p, boolean w) { path = p; writable = w; }

        @Override public long onGetSize() throws ErrnoException {
            try { synchronized (Session.LOCK) { FatFs.Node n = Session.fs().stat(path); return n == null ? 0 : n.size; } }
            catch (IOException e) { throw new ErrnoException("size", OsConstants.EIO); }
        }
        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            try { synchronized (Session.LOCK) { return Session.fs().read(path, offset, data, 0, size); } }
            catch (IOException e) { throw new ErrnoException("read", OsConstants.EIO); }
        }
        @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            if (!writable) throw new ErrnoException("write", OsConstants.EBADF);
            try { synchronized (Session.LOCK) { Session.fs().write(path, offset, data, 0, size); } return size; }
            catch (IOException e) { throw new ErrnoException("write", OsConstants.ENOSPC); }
        }
        @Override public void onFsync() throws ErrnoException {
            try { synchronized (Session.LOCK) { Session.fs().flush(); Session.volume().flush(); } }
            catch (IOException e) { throw new ErrnoException("fsync", OsConstants.EIO); }
        }
        @Override public void onRelease() {
            try { synchronized (Session.LOCK) { if (Session.isUnlocked()) { Session.fs().flush(); Session.volume().flush(); } } }
            catch (IOException ignored) { }
        }
    }
}
