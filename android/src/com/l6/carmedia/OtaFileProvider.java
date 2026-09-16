package com.l6.carmedia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * OTA 升级用的 ContentProvider —— 把 cacheDir/update/ 下的 APK 暴露成 content:// 给系统安装器读。
 *
 * 为避免引入 androidx，自实现一个最小 FileProvider。
 *  authority = "com.l6.carmedia.otafp"
 *  URI = content://com.l6.carmedia.otafp/<fileName> （文件名写在 cacheDir/update/ 下）
 */
public class OtaFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.l6.carmedia.otafp";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        String seg = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        // logs/<name> → Download/L6/logs/<name>（运行日志文件，供「下载日志」导出）
        if (seg.startsWith("logs/")) {
            File dir = new File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "L6/logs");
            return new File(dir, seg.substring("logs/".length()));
        }
        // extlogs/<name> → 应用私有 files/logs/<name>（高版本系统写不进公共目录时的回落）
        if (seg.startsWith("extlogs/")) {
            File dir = new File(getContext().getExternalFilesDir(null), "logs");
            return new File(dir, seg.substring("extlogs/".length()));
        }
        File dir = new File(getContext().getFilesDir(), "update");
        return new File(dir, seg);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return new AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public String getType(Uri uri) {
        String seg = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        if (seg.endsWith(".log")) return "text/plain";
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] vals = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) vals[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) vals[i] = f.length();
            else vals[i] = null;
        }
        MatrixCursor c = new MatrixCursor(cols);
        c.addRow(vals);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}