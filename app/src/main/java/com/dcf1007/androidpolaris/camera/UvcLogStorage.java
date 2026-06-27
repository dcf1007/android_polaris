package com.dcf1007.androidpolaris.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

/** Writes UVC debug logs to local storage without opening Android's share sheet. */
final class UvcLogStorage {
    private static final String MIME_TEXT = "text/plain";

    private UvcLogStorage() { }

    static String saveLogFile(Context context, String logText) throws Exception {
        String fileName = "android_polaris_uvc_debug_log_"
                + String.format(Locale.US, "%tY%<tm%<td_%<tH%<tM%<tS", new Date())
                + ".txt";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToPublicDownloads(context, fileName, logText);
            return "Downloads/" + fileName;
        }
        File file = saveToAppExternalDownloads(context, fileName, logText);
        return file.getAbsolutePath();
    }

    private static void saveToPublicDownloads(Context context, String fileName, String logText) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_TEXT);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("MediaStore returned no Downloads URI");
        }
        try {
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IllegalStateException("Could not open Downloads output stream");
                }
                outputStream.write(logText.getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (Throwable throwable) {
            resolver.delete(uri, null, null);
            throw throwable;
        }
    }

    private static File saveToAppExternalDownloads(Context context, String fileName, String logText) throws Exception {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = new File(context.getFilesDir(), "downloads");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create local log directory");
        }
        File file = new File(directory, fileName);
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(logText.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
