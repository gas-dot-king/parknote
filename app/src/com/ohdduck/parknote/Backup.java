package com.ohdduck.parknote;

import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 기록 전체를 JSON 파일 하나로 내보내고 되돌린다.
 *
 * <p>앱 데이터는 기기 안에만 있고 클라우드 백업도 꺼져 있어서, 서명 키가 바뀌거나 폰을
 * 바꾸면 지웠다 깔아야 하고 그 순간 전부 사라진다. 문서 선택기(SAF)를 쓰므로 저장소
 * 권한은 필요 없다.
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
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    static final String MIME = "application/json";

    /** 파일 이름은 사용자가 읽는 문장이 아니라 식별자라 로케일에 독립적으로 둔다. */
    private static final String FILE_STAMP_PATTERN = "yyyyMMdd-HHmm";

    static String suggestedFileName() {
        return "amatda-parking-"
                + new SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(new Date())
                + ".json";
    }

    // ---------- 내보내기 ----------

    /** 실패하면 사유 문자열 리소스 id, 성공하면 null. */
    static Integer writeTo(Context c, Uri target) {
        byte[] bytes;
        try {
            JSONObject envelope = new JSONObject();
            envelope.put(KEY_APP, MARKER);
            envelope.put(KEY_FORMAT, FORMAT);
            envelope.put(KEY_SAVED_AT, System.currentTimeMillis());
            envelope.put(KEY_APP_VERSION, versionName(c));
            envelope.put(KEY_DATA, Store.exportData(c));
            bytes = envelope.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return R.string.backup_err_build;
        }
        // 기존 파일을 고른 경우 남은 내용이 뒤에 붙지 않도록 "wt"(자르고 쓰기)를 먼저 쓴다.
        // 이 모드를 지원하지 않는 제공자가 있어 실패하면 평범한 "w"로 한 번 더 시도한다.
        if (write(c, target, bytes, "wt") || write(c, target, bytes, "w")) return null;
        return R.string.backup_err_write;
    }

    private static boolean write(Context c, Uri target, byte[] bytes, String mode) {
        try (OutputStream out = c.getContentResolver().openOutputStream(target, mode)) {
            if (out == null) return false;
            out.write(bytes);
            out.flush();
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    // ---------- 가져오기 ----------

    /** 파일을 읽어 검증까지 마친 결과. ok가 false면 errorRes에 사유가 담긴다. */
    static class Loaded {
        final JSONObject data;
        /** 백업이 만들어진 시각. 0이면 알 수 없음. */
        final long savedAt;
        /** {주차장, 차량, 주차 기록, 체크} 개수. */
        final int[] counts;
        final Integer errorRes;

        private Loaded(JSONObject data, long savedAt, int[] counts, Integer errorRes) {
            this.data = data;
            this.savedAt = savedAt;
            this.counts = counts;
            this.errorRes = errorRes;
        }

        boolean ok() {
            return errorRes == null;
        }
    }

    private static Loaded fail(int messageRes) {
        return new Loaded(null, 0, null, messageRes);
    }

    static Loaded readFrom(Context c, Uri source) {
        String raw;
        try (InputStream in = c.getContentResolver().openInputStream(source)) {
            if (in == null) return fail(R.string.backup_err_open);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BYTES) return fail(R.string.backup_err_too_big);
            }
            raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
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

        return new Loaded(data, envelope.optLong(KEY_SAVED_AT, 0), Store.counts(data), null);
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
