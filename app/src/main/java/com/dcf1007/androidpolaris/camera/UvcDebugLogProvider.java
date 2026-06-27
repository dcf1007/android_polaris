package com.dcf1007.androidpolaris.camera;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only provider for exported UVC debug logs.
 *
 * <p>The UVC controller writes android_polaris_uvc_debug_log.txt under
 * cache/uvc_logs. This provider exposes only that cache directory through a
 * content:// URI so Android's share sheet can send a real text file without
 * exposing a raw file:// path.</p>
 */
public final class UvcDebugLogProvider extends ContentProvider {
    private static final String LOG_DIRECTORY = "uvc_logs";
    private static final String MIME_TEXT = "text/plain";

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        return MIME_TEXT;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("UVC debug logs are read-only");
        }
        File file = resolveRequestedFile(uri);
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException("UVC debug log does not exist: " + file.getName());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        File file = resolveRequestedFile(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file.getName(), file.exists() ? file.length() : 0L});
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private File resolveRequestedFile(Uri uri) {
        if (getContext() == null) {
            return new File("missing-context");
        }
        String lastSegment = uri.getLastPathSegment();
        String cleanName = lastSegment == null ? "android_polaris_uvc_debug_log.txt" : lastSegment.replace("/", "");
        return new File(new File(getContext().getCacheDir(), LOG_DIRECTORY), cleanName);
    }
}
