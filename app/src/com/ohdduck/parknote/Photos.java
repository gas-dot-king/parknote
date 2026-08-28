package com.ohdduck.parknote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.provider.MediaStore;
import android.widget.ImageView;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;

/**
 * 주차 사진. 기둥 번호 표지판 한 장이 구역 이름보다 확실할 때가 있다.
 *
 * <p>파일은 앱 전용 저장소(filesDir/photos)에 기록 id 이름으로 둔다. 갤러리에 올리지 않는다 —
 * 남의 차 번호판이 찍힌 사진을 사진 앱에 흩뿌릴 이유가 없다. 카메라 앱에는
 * {@link PhotoProvider}의 content URI로 쓸 자리를 내준다(AndroidX FileProvider 없이).
 *
 * <p>긴 변 1280px·JPEG 82로 줄여 기록당 100~200KB. 기록 상한 240개면 많아야 수십 MB다.
 * 기록이 지워지거나 잘려 나가면 사진도 함께 지운다({@link #delete}, {@link #prune}).
 *
 * <p>회전값은 프레임워크 ExifInterface로 읽는다. lint가 권하는 AndroidX 판은 이 앱의
 * "라이브러리 0개" 전제와 어긋나고, 회전값 하나 읽는 데는 프레임워크 판이면 충분하다.
 */
@SuppressLint("ExifInterface")
final class Photos {

    private static final String DIR = "photos";
    /** 복원 확정 전까지 zip에서 꺼낸 사진을 두는 곳. 취소하면 통째로 버린다. */
    private static final String STAGING_DIR = "photos_import";
    private static final String CAPTURE_SUFFIX = ".capture.jpg";
    private static final int MAX_EDGE = 1280;
    private static final int JPEG_QUALITY = 82;

    private Photos() {
    }

    static File dir(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        dir.mkdirs();
        return dir;
    }

    static File file(Context c, String name) {
        return new File(dir(c), name);
    }

    static boolean exists(Context c, String name) {
        return validName(name) && file(c, name).isFile();
    }

    /**
     * 파일 이름 검사. 기록 id(UUID) + ".jpg"만 허용한다.
     * provider와 백업 zip이 남의 경로("../")를 열지 않게 하는 유일한 방어선이다.
     */
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9-]{1,64}(\\.capture)?\\.jpg");
    }

    // ---------- 촬영 ----------

    /** 카메라 앱을 연다. 결과가 오면 {@link #onCaptured}로 넘긴다. 카메라가 없으면 false. */
    static boolean startCapture(Activity a, String recordId, int requestCode) {
        String name = recordId + CAPTURE_SUFFIX;
        file(a, name).delete();
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.uriFor(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            a.startActivityForResult(intent, requestCode);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /** 촬영 결과. 줄이고 EXIF 회전을 적용해 기록에 붙인다. 실패하면 false. */
    static boolean onCaptured(Context c, String recordId) {
        File raw = file(c, recordId + CAPTURE_SUFFIX);
        try {
            if (!raw.isFile() || raw.length() == 0) return false;
            Bitmap bitmap = decodeForStorage(raw);
            if (bitmap == null) return false;
            String name = recordId + ".jpg";
            try (FileOutputStream out = new FileOutputStream(file(c, name))) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            }
            bitmap.recycle();
            Store.setPhoto(c, recordId, name);
            return true;
        } catch (IOException | OutOfMemoryError e) {
            return false;
        } finally {
            raw.delete();
        }
    }

    private static Bitmap decodeForStorage(File source) {
        Bitmap bitmap = decode(source, MAX_EDGE);
        if (bitmap == null) return null;
        // 카메라 앱은 대개 센서 방향 그대로 저장하고 회전값만 EXIF에 적는다.
        int rotation = rotationOf(source);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(width, height));
        if (rotation == 0 && scale >= 1f) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
        if (out != bitmap) bitmap.recycle();
        return out;
    }

    private static int rotationOf(File source) {
        try {
            int orientation = new ExifInterface(source.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------- 읽기 ----------

    /** 화면에 띄울 만큼만 읽는다(inSampleSize). 없거나 깨졌으면 null. */
    static Bitmap load(Context c, String name, int maxEdgePx) {
        return exists(c, name) ? decode(file(c, name), maxEdgePx) : null;
    }

    private static Bitmap decode(File source, int maxEdgePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        while (edge / (options.inSampleSize * 2) >= maxEdgePx) options.inSampleSize *= 2;
        try {
            return BitmapFactory.decodeFile(source.getPath(), options);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** 사진을 크게 보여 준다. */
    static void show(Activity a, String name) {
        Bitmap bitmap = load(a, name,
                Math.max(a.getResources().getDisplayMetrics().widthPixels,
                        a.getResources().getDisplayMetrics().heightPixels));
        if (bitmap == null) return;
        ImageView view = new ImageView(a);
        view.setImageBitmap(bitmap);
        view.setAdjustViewBounds(true);
        int pad = Ui.dp(a, 8);
        view.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(a)
                .setView(view)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    // ---------- 정리 ----------

    static void delete(Context c, String name) {
        if (validName(name)) file(c, name).delete();
    }

    /** history가 가리키지 않는 파일을 지운다. 기록이 상한에 잘려 나갔을 때. */
    static void prune(Context c, JSONArray history) {
        HashSet<String> keep = new HashSet<>();
        for (int i = 0; i < history.length(); i++) {
            keep.add(Store.photoOf(history.optJSONObject(i)));
        }
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            // 촬영 중인 임시 파일은 아직 기록에 안 붙어 있다. 건드리지 않는다.
            if (!keep.contains(name) && !name.endsWith(CAPTURE_SUFFIX)) file.delete();
        }
    }

    // ---------- 백업 복원 ----------

    /** zip에서 꺼낸 사진을 스테이징에 쓴다. maxBytes를 넘으면 지우고 false. */
    static boolean stage(Context c, String name, InputStream in, int maxBytes) throws IOException {
        File staging = new File(c.getFilesDir(), STAGING_DIR);
        staging.mkdirs();
        File target = new File(staging, name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > maxBytes) {
                    out.close();
                    target.delete();
                    return false;
                }
                out.write(chunk, 0, read);
            }
        }
        return true;
    }

    /** 복원 확정: 지금 사진을 전부 지우고 스테이징의 사진을 옮긴다. */
    static void commitStaged(Context c) {
        File dir = dir(c);
        clear(dir);
        File staging = new File(c.getFilesDir(), STAGING_DIR);
        File[] files = staging.listFiles();
        if (files != null) {
            for (File file : files) file.renameTo(new File(dir, file.getName()));
        }
        staging.delete();
    }

    /** 복원 취소(또는 다음 복원 준비): 스테이징을 통째로 버린다. */
    static void discardStaged(Context c) {
        File staging = new File(c.getFilesDir(), STAGING_DIR);
        clear(staging);
        staging.delete();
    }

    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
    }
}
