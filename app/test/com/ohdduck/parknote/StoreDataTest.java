package com.ohdduck.parknote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 백업 파일 검증과 주차 기록 규칙.
 *
 * <p>백업 검증 테스트는 저장 키를 일부러 문자열 리터럴로 쓴다. 그 키들이 곧 백업 파일의
 * 형식이라, 상수 이름을 따라가면 형식이 바뀌어도 테스트가 통과해 버린다.
 */
public class StoreDataTest {

    // ---------- 백업 검증 ----------

    @Test
    public void validate_정상_백업은_통과() throws JSONException {
        assertNull(Store.validate(backup("[{\"id\":\"p1\"}]", "[{\"id\":\"v1\"}]", "[]", "[]")));
    }

    @Test
    public void validate_주차장이_없으면_거부() throws JSONException {
        assertEquals(Integer.valueOf(R.string.backup_err_no_profiles),
                Store.validate(backup("[]", "[{\"id\":\"v1\"}]", "[]", "[]")));
        assertNotNull(Store.validate(new JSONObject()));
    }

    @Test
    public void validate_차량이_없으면_거부() throws JSONException {
        assertEquals(Integer.valueOf(R.string.backup_err_no_vehicles),
                Store.validate(backup("[{\"id\":\"p1\"}]", "[]", "[]", "[]")));
    }

    @Test
    public void validate_기록이_깨졌으면_거부() throws JSONException {
        assertEquals(Integer.valueOf(R.string.backup_err_bad_history), Store.validate(
                backup("[{\"id\":\"p1\"}]", "[{\"id\":\"v1\"}]", "{망가짐", "[]")));
        assertEquals(Integer.valueOf(R.string.backup_err_bad_habits), Store.validate(
                backup("[{\"id\":\"p1\"}]", "[{\"id\":\"v1\"}]", "[]", "{망가짐")));
    }

    @Test
    public void validate_더_새로운_스키마의_백업은_거부() throws JSONException {
        // 봉투 format이 같아도 안의 parking_schema가 더 크면 Migration이 "이미 최신"으로
        // 보고 조기 리턴한다. 모르는 구조를 검증 없이 쓰게 되므로 여기서 막아야 한다.
        JSONObject data = backup("[{\"id\":\"p1\"}]", "[{\"id\":\"v1\"}]", "[]", "[]")
                .put("parking_schema", 99);
        assertEquals(Integer.valueOf(R.string.backup_err_newer), Store.validate(data));

        // 같은 버전이거나 키가 없으면(예전 백업) 통과한다.
        assertNull(Store.validate(
                backup("[{\"id\":\"p1\"}]", "[{\"id\":\"v1\"}]", "[]", "[]")
                        .put("parking_schema", Store.SCHEMA_VERSION)));
    }

    @Test
    public void counts_는_항목_수를_센다() throws JSONException {
        int[] counts = Store.counts(backup(
                "[{\"id\":\"p1\"},{\"id\":\"p2\"}]",
                "[{\"id\":\"v1\"}]",
                "[{\"id\":\"r1\"},{\"id\":\"r2\"},{\"id\":\"r3\"}]",
                "[]"));
        assertArrayEquals(new int[]{2, 1, 3, 0}, counts);
    }

    @Test
    public void counts_는_깨진_배열을_0으로_센다() throws JSONException {
        int[] counts = Store.counts(backup("{망가짐", "[]", "[]", "[]"));
        assertArrayEquals(new int[]{0, 0, 0, 0}, counts);
    }

    // ---------- 기록 필드 ----------

    @Test
    public void recordMemo_는_공백을_다듬고_없으면_빈_문자열() throws JSONException {
        assertEquals("", Store.recordMemo(null));
        assertEquals("", Store.recordMemo(new JSONObject()));
        assertEquals("", Store.recordMemo(new JSONObject().put("m", "   ")));
        assertEquals("F기둥", Store.recordMemo(new JSONObject().put("m", "  F기둥 ")));
    }

    @Test
    public void 기록_좌표는_두_값이_모두_유효한_범위일_때만_인정한다() throws JSONException {
        assertTrue(Store.recordHasCoords(new JSONObject().put("lat", 37.5).put("lon", 127.0)));
        assertFalse(Store.recordHasCoords(new JSONObject().put("lat", 37.5)));
        assertFalse(Store.recordHasCoords(new JSONObject().put("lat", 91).put("lon", 127)));
        assertFalse(Store.recordHasCoords(null));
    }

    @Test
    public void 위치_메타데이터는_옛_기록과_새_기록을_함께_읽는다() throws JSONException {
        assertEquals(0L, Store.recordLocationTime(new JSONObject()));
        assertEquals(-1f, Store.recordLocationAccuracy(new JSONObject()), 0f);

        JSONObject recent = new JSONObject().put("loc_t", 12345L).put("loc_acc", 7.5);
        assertEquals(12345L, Store.recordLocationTime(recent));
        assertEquals(7.5f, Store.recordLocationAccuracy(recent), 0.001f);

        assertEquals(0L, Store.recordLocationTime(new JSONObject().put("loc_t", -1)));
        assertEquals(-1f,
                Store.recordLocationAccuracy(new JSONObject().put("loc_acc", -1)), 0f);
    }

    @Test
    public void 기록_상한이_맥락별로_나뉘어_있다() {
        // 전역 40개였을 때는 주차장 6 × 차량 3 = 18개 맥락이 40칸을 나눠 썼다.
        assertTrue("맥락당 상한이 화면에 보여 주는 5개보다는 넉넉해야 한다",
                Store.MAX_HISTORY_PER_CONTEXT >= 10);
        assertTrue("전체 상한이 한 맥락 상한보다 커야 한다",
                Store.MAX_HISTORY > Store.MAX_HISTORY_PER_CONTEXT);
    }

    // ---------- 기록 잘라내기 ----------

    @Test
    public void trimHistory_맥락당_20개_전체_240개를_넘기지_않는다() throws JSONException {
        JSONArray old = new JSONArray();
        long t = 1_000_000L;
        // 주차장 6 × 차량 3 = 18개 맥락에 각 30개 → 540개
        for (int p = 0; p < 6; p++) {
            for (int v = 0; v < 3; v++) {
                for (int i = 0; i < 30; i++) old.put(record("p" + p, "v" + v, t--));
            }
        }
        JSONObject fresh = record("p0", "v0", 2_000_000L);
        ArrayList<String> cancelled = new ArrayList<>();
        JSONArray next = Store.trimHistory(fresh, old, cancelled);

        assertTrue("전체 " + next.length(), next.length() <= Store.MAX_HISTORY);
        HashMap<String, Integer> perContext = new HashMap<>();
        for (int i = 0; i < next.length(); i++) {
            JSONObject e = next.getJSONObject(i);
            String key = e.getString("p") + "/" + e.getString("c");
            Integer n = perContext.get(key);
            perContext.put(key, n == null ? 1 : n + 1);
        }
        for (Integer n : perContext.values()) {
            assertTrue("맥락 " + n, n <= Store.MAX_HISTORY_PER_CONTEXT);
        }
        assertEquals(fresh.getString("id"), next.getJSONObject(0).getString("id"));
    }

    @Test
    public void trimHistory_한쪽_차량을_자주_대도_다른_차량_기록을_밀어내지_않는다()
            throws JSONException {
        JSONArray old = new JSONArray();
        long t = 1_000_000L;
        for (int i = 0; i < 100; i++) old.put(record("home", "carA", t--));
        old.put(record("home", "carB", 1L)); // 아주 오래된 다른 차량 기록 하나

        JSONArray next = Store.trimHistory(record("home", "carA", 2_000_000L), old,
                new ArrayList<String>());

        boolean carBKept = false;
        for (int i = 0; i < next.length(); i++) {
            if ("carB".equals(next.getJSONObject(i).getString("c"))) carBKept = true;
        }
        assertTrue("전역 상한이었을 때 사라지던 기록", carBKept);
    }

    @Test
    public void trimHistory_같은_차량의_이전_타이머는_해제하고_잘린_기록의_타이머도_해제한다()
            throws JSONException {
        JSONArray old = new JSONArray();
        long t = 1_000_000L;
        old.put(record("home", "carA", t--).put("due", 5_000_000L));      // 같은 차 → 해제
        old.put(record("home", "carB", t--).put("due", 5_000_000L));      // 다른 차 → 유지
        for (int i = 0; i < Store.MAX_HISTORY_PER_CONTEXT; i++) {
            old.put(record("work", "carB", t--));
        }
        JSONObject dropped = record("work", "carB", t--).put("due", 5_000_000L); // 21번째 → 잘림
        old.put(dropped);

        ArrayList<String> cancelled = new ArrayList<>();
        JSONArray next = Store.trimHistory(record("home", "carA", 2_000_000L), old, cancelled);

        assertTrue(cancelled.contains(old.getJSONObject(0).getString("id")));
        assertTrue(cancelled.contains(dropped.getString("id")));
        assertFalse(cancelled.contains(old.getJSONObject(1).getString("id")));
        assertEquals(2, cancelled.size());
        // 해제된 기록은 목록에 남되 due가 빠져 있어야 재부팅 때 다시 예약되지 않는다
        assertFalse(findById(next, old.getJSONObject(0).getString("id")).has("due"));
        assertEquals(5_000_000L, findById(next, old.getJSONObject(1).getString("id")).getLong("due"));
    }

    @Test
    public void trimHistory_끊김_시각이_과거인_기록은_시각_순서대로_들어간다() throws JSONException {
        // 블루투스 알림 버튼을 늦게 누르면 새 기록의 t가 기존 기록보다 과거일 수 있다.
        JSONArray old = new JSONArray().put(record("home", "carA", 3_000L));
        JSONArray next = Store.trimHistory(record("home", "carB", 1_000L), old,
                new ArrayList<String>());
        assertEquals(3_000L, next.getJSONObject(0).getLong("t"));
        assertEquals(1_000L, next.getJSONObject(1).getLong("t"));
    }

    // ---------- 구역 순위 ----------

    @Test
    public void rankZones_자주_댄_구역이_먼저_같은_횟수면_최근_것이_먼저() throws JSONException {
        JSONArray h = new JSONArray();
        long t = 100;
        h.put(record("home", "car", t--).put("z", "B1-A")); // 최근 1회
        h.put(record("home", "car", t--).put("z", "B2-A"));
        h.put(record("home", "car", t--).put("z", "B3-B"));
        h.put(record("home", "car", t--).put("z", "B2-A"));
        h.put(record("home", "car", t--).put("z", "B3-B"));
        h.put(record("home", "car", t--).put("z", "B2-A")); // 3회
        String[] grid = {"B1-A", "B1-B", "B2-A", "B2-B", "B3-A", "B3-B"};

        assertArrayEquals(new String[]{"B2-A", "B3-B", "B1-A"}, Store.rankZones(h, grid, 3, null));
        // 모자라면 격자 순서로 채운다
        assertArrayEquals(new String[]{"B2-A", "B3-B", "B1-A", "B1-B", "B2-B"},
                Store.rankZones(h, grid, 5, null));
    }

    @Test
    public void rankZones_초안과_허용_밖_구역은_세지_않는다() throws JSONException {
        JSONArray h = new JSONArray();
        JSONObject draft = record("home", "car", 3L);
        draft.remove("z");
        h.put(draft);                                           // 초안
        h.put(record("home", "car", 2L).put("z", "롯데몰 B2")); // 직접 입력
        h.put(record("home", "car", 1L).put("z", "B1-B"));
        String[] grid = {"B1-A", "B1-B"};
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(grid));

        assertArrayEquals(new String[]{"B1-B", "B1-A"}, Store.rankZones(h, grid, 2, allowed));
        assertArrayEquals(new String[]{"롯데몰 B2", "B1-B"}, Store.rankZones(h, grid, 2, null));
    }

    // ---------- 블루투스 재연결 = 주차 끝 ----------

    @Test
    public void closeParking_빈_초안이_3분_안에_재연결되면_오탐으로_지운다() throws JSONException {
        JSONObject draft = record("home", "car", 10_000L);
        draft.remove("z");
        JSONArray all = new JSONArray().put(draft);

        JSONObject touched = Store.closeParking(all, "car", 10_000L + Store.FLAP_MS - 1);
        assertEquals(draft.getString("id"), touched.getString("id"));
        assertEquals(0, all.length());
    }

    @Test
    public void closeParking_사진이나_메모가_있는_초안은_지우지_않는다() throws JSONException {
        JSONObject draft = record("home", "car", 10_000L);
        draft.remove("z");
        draft.put("ph", "x.jpg");
        JSONArray all = new JSONArray().put(draft);

        Store.closeParking(all, "car", 11_000L);
        assertEquals(1, all.length());
        assertEquals(11_000L, draft.getLong("e"));
    }

    @Test
    public void closeParking_구역이_있으면_출차_시각을_적고_이미_닫힌_기록은_건드리지_않는다()
            throws JSONException {
        JSONObject parked = record("home", "car", 10_000L).put("due", 99_000L);
        JSONObject other = record("home", "van", 20_000L); // 다른 차량은 무관
        JSONArray all = new JSONArray().put(other).put(parked);

        JSONObject touched = Store.closeParking(all, "car", 50_000L);
        assertEquals(parked.getString("id"), touched.getString("id"));
        assertEquals(50_000L, parked.getLong("e"));
        assertEquals(99_000L, parked.getLong("due")); // 타이머 해제는 호출한 쪽이 한다
        assertFalse(other.has("e"));

        assertNull(Store.closeParking(all, "car", 60_000L)); // 두 번째 재연결은 무시
        assertEquals(50_000L, parked.getLong("e"));
    }

    @Test
    public void isDraft_는_구역이_비었을_때만() throws JSONException {
        assertTrue(Store.isDraft(new JSONObject()));
        assertTrue(Store.isDraft(new JSONObject().put("z", "  ")));
        assertFalse(Store.isDraft(new JSONObject().put("z", "B1-A")));
        assertFalse(Store.isDraft(null));
    }

    // ---------- 도우미 ----------

    private static int recordSeq;

    private static JSONObject record(String profileId, String vehicleId, long t)
            throws JSONException {
        return new JSONObject()
                .put("id", "r" + (++recordSeq))
                .put("p", profileId).put("c", vehicleId)
                .put("z", "A").put("t", t);
    }

    private static JSONObject findById(JSONArray items, String id) {
        JSONObject item = Json.byId(items, id);
        if (item == null) throw new AssertionError("없음: " + id);
        return item;
    }

    private static JSONObject backup(String profiles, String vehicles,
                                     String history, String habits) throws JSONException {
        return new JSONObject()
                .put("parking_profiles_v1", profiles)
                .put("parking_vehicles_v1", vehicles)
                .put("history", history)
                .put("habits", habits);
    }
}
