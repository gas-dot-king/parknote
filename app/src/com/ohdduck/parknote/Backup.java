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
 * <p>앱 데이터는 기기 안에만 있고 자동 백업도 꺼져 있어서, 서명 키가 바뀌거나 폰을 바꾸면
 * 지웠다 깔아야 하고 그 순간 전부 사라진다. 문서 선택기(SAF)를 쓰므로 저장소 권한은 필요 없다.
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

    static final String MIME = "application/json";

    private static final SimpleDateFormat FILE_STAMP =
            new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);

    static String suggestedFileName() {
        return "amatda-parking-" + FILE_STAMP.format(new Date()) + ".json";
    }

    // ---------- 내보내기 ----------

    /** 실패하면 사용자에게 보여 줄 메시지, 성공하면 null. */
    static String writeTo(Context c, Uri target) {
        JSONObject envelope = new JSONObject();
        try {
            envelope.put(KEY_APP, MARKER);
            envelope.put(KEY_FORMAT, FORMAT);
            envelope.put(KEY_SAVED_AT, System.currentTimeMillis());
            envelope.put(KEY_APP_VERSION, versionName(c));
            envelope.put(KEY_DATA, Store.exportData(c));
        } catch (JSONException e) {
            return "백업 파일을 만들지 못했어요";
        }
        byte[] bytes;
        try {
            bytes = envelope.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return "백업 파일을 만들지 못했어요";
        }
        // 기존 파일을 고른 경우 남은 내용이 뒤에 붙지 않도록 "wt"(자르고 쓰기)를 먼저 쓴다.
        // 이 모드를 지원하지 않는 제공자가 있어 실패하면 평범한 "w"로 한 번 더 시도한다.
        if (write(c, target, bytes, "wt") || write(c, target, bytes, "w")) return null;
        return "파일에 쓰지 못했어요";
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

    /** 파일을 읽어 검증까지 마친 결과. ok가 false면 error에 사유가 담긴다. */
    static class Loaded {
        final JSONObject data;
        final String summary;
        final String error;

        private Loaded(JSONObject data, String summary, String error) {
            this.data = data;
            this.summary = summary;
            this.error = error;
        }

        boolean ok() {
            return error == null;
        }
    }

    private static Loaded fail(String message) {
        return new Loaded(null, null, message);
    }

    static Loaded readFrom(Context c, Uri source) {
        String raw;
        try (InputStream in = c.getContentResolver().openInputStream(source)) {
            if (in == null) return fail("파일을 열지 못했어요");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 8 * 1024 * 1024) return fail("백업 파일이 너무 커요");
            }
            raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return fail("파일을 읽지 못했어요");
        }

        JSONObject envelope;
        try {
            envelope = new JSONObject(raw);
        } catch (JSONException e) {
            return fail("이 앱의 백업 파일이 아니에요");
        }
        if (!MARKER.equals(envelope.optString(KEY_APP))) {
            return fail("이 앱의 백업 파일이 아니에요");
        }
        if (envelope.optInt(KEY_FORMAT, 0) > FORMAT) {
            return fail("더 새로운 버전에서 만든 백업이라 이 버전에서는 읽을 수 없어요");
        }
        JSONObject data = envelope.optJSONObject(KEY_DATA);
        if (data == null) return fail("백업 내용이 비어 있어요");

        String problem = Store.validate(data);
        if (problem != null) return fail(problem);

        long savedAt = envelope.optLong(KEY_SAVED_AT, 0);
        String when = savedAt > 0 ? Store.formatFull(savedAt) : "시각 미상";
        return new Loaded(data, when + "에 저장\n" + Store.describe(data), null);
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
