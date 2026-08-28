package com.ohdduck.parknote;

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
 * 카메라 앱이 사진을 쓸 자리를 content URI로 내준다.
 *
 * <p>AndroidX FileProvider가 하는 일의 이 앱에 필요한 부분만이다. 외부에 공개하지 않고
 * (exported=false) 촬영 인텐트에 붙는 임시 권한(grantUriPermissions)으로만 열린다.
 * 열 수 있는 이름은 {@link Photos#validName}이 허용하는 것뿐이다.
 */
public class PhotoProvider extends ContentProvider {

    static final String AUTHORITY = "com.ohdduck.parknote.photos";

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.parseMode(mode));
    }

    /** 일부 카메라 앱은 쓰기 전에 이름과 크기를 묻는다. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file;
        try {
            file = fileOf(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, 1);
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File fileOf(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!Photos.validName(name) || getContext() == null) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return Photos.file(getContext(), name);
    }
}
