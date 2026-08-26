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

import java.time.LocalDate;

/**
 * 습관 연속 기록, 시각 표시, 백업 파일 검증.
 *
 * <p>백업 검증 테스트는 저장 키를 일부러 문자열 리터럴로 쓴다. 그 키들이 곧 백업 파일의
 * 형식이라, 상수 이름을 따라가면 형식이 바뀌어도 테스트가 통과해 버린다.
 */
public class StoreDataTest {

    // ---------- 습관 연속 기록 ----------

    @Test
    public void streak_오늘부터_이어진_날을_센다() throws JSONException {
        assertEquals(3, Store.streak(habit(0, 1, 2)));
    }

    @Test
    public void streak_오늘_안_했어도_어제까지는_살아_있다() throws JSONException {
        // 저녁에 앱을 열었을 때 "2일 연속"이 0으로 보이면 사용자는 기록이 끊긴 줄 안다.
        assertEquals(2, Store.streak(habit(1, 2)));
    }

    @Test
    public void streak_이틀_전에서_끊기면_0() throws JSONException {
        assertEquals(0, Store.streak(habit(2, 3, 4)));
    }

    @Test
    public void streak_중간에_빠지면_거기서_멈춘다() throws JSONException {
        assertEquals(2, Store.streak(habit(0, 1, 3, 4)));
    }

    @Test
    public void streak_기록이_없으면_0() throws JSONException {
        assertEquals(0, Store.streak(habit()));
        assertEquals(0, Store.streak(new JSONObject()));
    }

    @Test
    public void last7Days_는_왼쪽이_6일_전_오른쪽이_오늘() throws JSONException {
        boolean[] week = Store.last7Days(habit(0, 6));
        assertTrue("오늘", week[6]);
        assertTrue("6일 전", week[0]);
        assertFalse("어제", week[5]);
        assertEquals(7, week.length);
    }

    @Test
    public void last7Days_7일보다_오래된_기록은_무시한다() throws JSONException {
        boolean[] week = Store.last7Days(habit(7, 8, 30));
        for (int i = 0; i < week.length; i++) {
            assertFalse("index " + i, week[i]);
        }
    }

    @Test
    public void checkedToday_는_맨_앞이_오늘일_때만_참() throws JSONException {
        assertTrue(Store.checkedToday(habit(0, 1)));
        assertFalse(Store.checkedToday(habit(1, 2)));
        assertFalse(Store.checkedToday(habit()));
    }

    @Test
    public void today_는_저장_형식과_같은_문자열() {
        assertEquals(LocalDate.now().toString(), Store.today());
        assertEquals(10, Store.today().length()); // yyyy-MM-dd
    }

    // ---------- 시각 표시 ----------

    @Test
    public void formatRelative_구간별_문구() {
        long now = System.currentTimeMillis();
        assertEquals("방금 전", Store.formatRelative(now));
        assertEquals("5분 전", Store.formatRelative(now - 5 * 60_000L));
        assertEquals("3시간 전", Store.formatRelative(now - 3 * 60 * 60_000L));
        assertEquals("2일 전", Store.formatRelative(now - 2 * 24 * 60 * 60_000L));
    }

    @Test
    public void formatRelative_미래_시각도_읽을_수_있게_표시한다() {
        // 기록 수정에서 주차 시각을 앞으로 당길 수 있어 실제로 생기는 경우다.
        assertEquals("10분 후",
                Store.formatRelative(System.currentTimeMillis() + 10 * 60_000L + 500));
    }

    @Test
    public void formatMinutesOfDay_오전_오후_경계() {
        assertEquals("오전 12:00", Store.formatMinutesOfDay(0));
        assertEquals("오전 9:05", Store.formatMinutesOfDay(9 * 60 + 5));
        assertEquals("오후 12:00", Store.formatMinutesOfDay(12 * 60));
        assertEquals("오후 9:00", Store.formatMinutesOfDay(21 * 60));
        assertEquals("오후 11:59", Store.formatMinutesOfDay(23 * 60 + 59));
    }

    @Test
    public void formatFull_과_formatShort_는_같은_시각을_가리킨다() {
        long t = System.currentTimeMillis();
        String full = Store.formatFull(t);
        String shrt = Store.formatShort(t);
        assertTrue("full=" + full + " short=" + shrt, full.endsWith(shrt));
    }

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
        assertTrue(Store.validCoordinates(-90, -180));
        assertTrue(Store.validCoordinates(90, 180));

        assertFalse(Store.recordHasCoords(new JSONObject().put("lat", 37.5)));
        assertFalse(Store.recordHasCoords(new JSONObject().put("lat", 91).put("lon", 127)));
        assertFalse(Store.validCoordinates(Double.NaN, 127));
        assertFalse(Store.validCoordinates(37.5, Double.POSITIVE_INFINITY));
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

    // ---------- 도우미 ----------

    /** daysAgo에 적힌 날짜만 체크된 습관. 최신순으로 담는다. */
    private static JSONObject habit(int... daysAgo) throws JSONException {
        JSONArray days = new JSONArray();
        LocalDate today = LocalDate.now();
        for (int ago : daysAgo) days.put(today.minusDays(ago).toString());
        return new JSONObject().put("n", "영양제").put("days", days);
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
