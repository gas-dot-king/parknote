package com.ohdduck.parknote;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 기록 전체를 파일 하나로 내보내고 되돌린다.
 *
 * <p>앱 데이터는 기기 안에만 있고 클라우드 백업도 꺼져 있어서, 서명 키가 바뀌거나 폰을
 * 바꾸면 지웠다 깔아야 하고 그 순간 전부 사라진다. 문서 선택기(SAF)를 쓰므로 저장소
 * 권한은 필요 없다.
 *
 * <p>파일은 zip이다: {@code data.json}(예전과 같은 JSON 봉투) + {@code photos/}(주차 사진).
 * JSON 하나였던 예전 형식(format 1)도 그대로 읽는다. java.util.zip은 JDK에 있어 라이브러리가
 * 늘지 않는다.
 *
 * <p>실패 사유는 문자열이 아니라 <b>문자열 리소스 id</b>로 돌려준다. 이 클래스는 워커
 * 스레드에서 돌고, 문구를 만드는 일은 화면(BackupFlow)이 맡는다.
 */
class Backup {

    /** 남의 JSON을 잘못 복원하지 않도록 확인하는 표식 */
    private static final String MARKER = "amatda-parking";
    private static final String KEY_APP = "app";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_SAVED_AT = "savedAt";
    private static final String KEY_APP_VERSION = "appVersion";
    private static final String KEY_DATA = "data";
    /** 봉투 형식. 1 = JSON 파일 하나, 2 = zip(data.json + photos/). */
    private static final int FORMAT = 2;
    private static final String ENTRY_DATA = "data.json";
    private static final String ENTRY_PHOTOS = "photos/";
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PHOTO_BYTES = 6 * 1024 * 1024;

    static final String MIME = "application/zip";

    /** 파일 이름은 사용자가 읽는 문장이 아니라 식별자라 로케일에 독립적으로 둔다. */
    private static final String FILE_STAMP_PATTERN = "yyyyMMdd-HHmm";

    static String suggestedFileName() {
        return "amatda-parking-"
                + new SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(new Date())
                + ".zip";
    }

    // ---------- 내보내기 ----------

    /** 실패하면 사유 문자열 리소스 id, 성공하면 null. */
    static Integer writeTo(Context c, Uri target) {
        byte[] json;
        try {
            JSONObject envelope = new JSONObject();
            envelope.put(KEY_APP, MARKER);
            envelope.put(KEY_FORMAT, FORMAT);
            envelope.put(KEY_SAVED_AT, System.currentTimeMillis());
            envelope.put(KEY_APP_VERSION, versionName(c));
            envelope.put(KEY_DATA, Store.exportData(c));
            json = envelope.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return R.string.backup_err_build;
        }
        JSONArray history = Store.history(c);
        // 기존 파일을 고른 경우 남은 내용이 뒤에 붙지 않도록 "wt"(자르고 쓰기)를 먼저 쓴다.
        // 이 모드를 지원하지 않는 제공자가 있어 실패하면 평범한 "w"로 한 번 더 시도한다.
        if (write(c, target, json, history, "wt") || write(c, target, json, history, "w")) {
            return null;
        }
        return R.string.backup_err_write;
    }

    private static boolean write(Context c, Uri target, byte[] json, JSONArray history,
                                 String mode) {
        try (OutputStream raw = c.getContentResolver().openOutputStream(target, mode)) {
            if (raw == null) return false;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
                zip.putNextEntry(new ZipEntry(ENTRY_DATA));
                zip.write(json);
                zip.closeEntry();
                for (int i = 0; i < history.length(); i++) {
                    String name = Store.photoOf(history.optJSONObject(i));
                    File photo = Photos.file(c, name);
                    if (!Photos.validName(name) || !photo.isFile()) continue;
                    zip.putNextEntry(new ZipEntry(ENTRY_PHOTOS + name));
                    try (InputStream in = new FileInputStream(photo)) {
                        copy(in, zip);
                    }
                    zip.closeEntry();
                }
            }
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
    }

    // ---------- 가져오기 ----------

    /** 파일을 읽어 검증까지 마친 결과. ok가 false면 errorRes에 사유가 담긴다. */
    static class Loaded {
        final JSONObject data;
        /** 백업이 만들어진 시각. 0이면 알 수 없음. */
        final long savedAt;
        /** {주차장, 차량, 주차 기록, 체크} 개수. */
        final int[] counts;
        /** 스테이징에 꺼내 둔 사진 수. 복원을 확정해야 옮겨진다. */
        final int photos;
        final Integer errorRes;

        private Loaded(JSONObject data, long savedAt, int[] counts, int photos,
                       Integer errorRes) {
            this.data = data;
            this.savedAt = savedAt;
            this.counts = counts;
            this.photos = photos;
            this.errorRes = errorRes;
        }

        boolean ok() {
            return errorRes == null;
        }
    }

    private static Loaded fail(int messageRes) {
        return new Loaded(null, 0, null, 0, messageRes);
    }

    /**
     * 읽고 검증한다. 사진은 스테이징에 꺼내 두고, 호출한 쪽이 확정({@link Photos#commitStaged})
     * 또는 취소({@link Photos#discardStaged})한다.
     */
    static Loaded readFrom(Context c, Uri source) {
        Photos.discardStaged(c);
        String raw;
        int photos = 0;
        try (InputStream opened = c.getContentResolver().openInputStream(source)) {
            if (opened == null) return fail(R.string.backup_err_open);
            BufferedInputStream in = new BufferedInputStream(opened);
            if (isZip(in)) {
                raw = null;
                ZipInputStream zip = new ZipInputStream(in);
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (ENTRY_DATA.equals(name)) {
                        byte[] bytes = readAll(zip, MAX_JSON_BYTES);
                        if (bytes == null) return fail(R.string.backup_err_too_big);
                        raw = new String(bytes, StandardCharsets.UTF_8);
                    } else if (name.startsWith(ENTRY_PHOTOS)) {
                        String file = name.substring(ENTRY_PHOTOS.length());
                        if (!Photos.validName(file)) continue; // 모르는 경로는 열지 않는다
                        if (!Photos.stage(c, file, zip, MAX_PHOTO_BYTES)) {
                            return fail(R.string.backup_err_too_big);
                        }
                        photos++;
                    }
                    zip.closeEntry();
                }
                if (raw == null) return fail(R.string.backup_err_empty);
            } else {
                byte[] bytes = readAll(in, MAX_JSON_BYTES);
                if (bytes == null) return fail(R.string.backup_err_too_big);
                raw = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException | SecurityException e) {
            return fail(R.string.backup_err_read);
        }

        JSONObject envelope;
        try {
            envelope = new JSONObject(raw);
        } catch (JSONException e) {
            return fail(R.string.backup_err_not_ours);
        }
        if (!MARKER.equals(envelope.optString(KEY_APP))) {
            return fail(R.string.backup_err_not_ours);
        }
        if (envelope.optInt(KEY_FORMAT, 0) > FORMAT) {
            return fail(R.string.backup_err_newer);
        }
        JSONObject data = envelope.optJSONObject(KEY_DATA);
        if (data == null) return fail(R.string.backup_err_empty);

        Integer problem = Store.validate(data);
        if (problem != null) return fail(problem);

        return new Loaded(data, envelope.optLong(KEY_SAVED_AT, 0), Store.counts(data),
                photos, null);
    }

    /** zip의 처음 4바이트는 "PK\3\4"다. 스트림은 되감아 둔다. */
    private static boolean isZip(BufferedInputStream in) throws IOException {
        in.mark(4);
        byte[] head = new byte[4];
        int read = in.read(head);
        in.reset();
        return read == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    /** 상한을 넘으면 null. */
    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > maxBytes) return null;
        }
        return buffer.toByteArray();
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }
}
