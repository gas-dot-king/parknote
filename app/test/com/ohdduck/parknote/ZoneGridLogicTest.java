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

/**
 * 층×구역 격자 로직과, v2.2 평면 목록을 격자로 되돌리는 마이그레이션 추론.
 *
 * <p>여기가 깨지면 기존 사용자의 구역 구성이 통째로 날아간다. 앱 전체에서 회귀가 가장
 * 무서운 코드인데 지금까지 검증하는 수단이 없었다.
 */
public class ZoneGridLogicTest {

    // ---------- flatten ----------

    @Test
    public void flatten_행이_있으면_행우선_곱집합() {
        assertArrayEquals(
                new String[]{"B1-A", "B1-B", "B2-A", "B2-B"},
                Store.flatten(new String[]{"B1", "B2"}, new String[]{"A", "B"}, "-"));
    }

    @Test
    public void flatten_행이_없으면_구역이_그대로_버튼() {
        assertArrayEquals(
                new String[]{"정문", "후문"},
                Store.flatten(new String[0], new String[]{"정문", "후문"}, "-"));
        assertArrayEquals(
                new String[]{"정문"},
                Store.flatten(null, new String[]{"정문"}, "-"));
    }

    @Test
    public void flatten_구역이_없으면_빈_격자() {
        assertEquals(0, Store.flatten(new String[]{"B1"}, new String[0], "-").length);
        assertEquals(0, Store.flatten(new String[]{"B1"}, null, "-").length);
    }

    @Test
    public void flatten_은_입력_배열을_공유하지_않는다() {
        String[] cols = {"A", "B"};
        String[] out = Store.flatten(new String[0], cols, "-");
        out[0] = "바뀜";
        assertEquals("A", cols[0]); // 원본이 오염되면 프로필 하나 수정이 전부에 번진다
    }

    // ---------- inferGrid: 평면 목록 → 격자 역추론 ----------

    @Test
    public void inferGrid_완전한_곱집합이면_격자로_되돌린다() {
        String[][] grid = Store.inferGrid(
                new String[]{"B1-A", "B1-B", "B2-A", "B2-B"}, "-");
        assertNotNull(grid);
        assertArrayEquals(new String[]{"B1", "B2"}, grid[0]);
        assertArrayEquals(new String[]{"A", "B"}, grid[1]);
    }

    @Test
    public void inferGrid_한_칸이라도_비면_포기한다() {
        // B2-B가 없다 → 격자가 아니라 사용자가 손으로 고른 목록이다. 그대로 보존해야 한다.
        assertNull(Store.inferGrid(new String[]{"B1-A", "B1-B", "B2-A", "B3-A"}, "-"));
    }

    @Test
    public void inferGrid_순서가_행우선이_아니면_포기한다() {
        assertNull(Store.inferGrid(new String[]{"B1-A", "B2-A", "B1-B", "B2-B"}, "-"));
    }

    @Test
    public void inferGrid_구분자가_두_번_나오면_포기한다() {
        assertNull(Store.inferGrid(
                new String[]{"B1-A-1", "B1-A-2", "B2-A-1", "B2-A-2"}, "-"));
    }

    @Test
    public void inferGrid_네_개_미만은_격자로_보지_않는다() {
        assertNull(Store.inferGrid(new String[]{"B1-A", "B1-B"}, "-"));
    }

    @Test
    public void inferGrid_구분자가_맨_앞이나_뒤면_포기한다() {
        assertNull(Store.inferGrid(new String[]{"-A", "-B", "B2-A", "B2-B"}, "-"));
        assertNull(Store.inferGrid(new String[]{"B1-", "B1-B", "B2-", "B2-B"}, "-"));
    }

    // ---------- applyFlatZones: 마이그레이션 진입점 ----------

    @Test
    public void applyFlatZones_격자로_떨어지면_행과_열로_나눈다() throws JSONException {
        JSONObject profile = new JSONObject();
        Store.applyFlatZones(profile,
                new String[]{"B1-A", "B1-B", "B2-A", "B2-B"});

        assertEquals("-", profile.getString("sep"));
        assertArrayEquals(new String[]{"B1", "B2"}, toArray(profile.getJSONArray("rows")));
        assertArrayEquals(new String[]{"A", "B"}, toArray(profile.getJSONArray("cols")));
    }

    @Test
    public void applyFlatZones_언더바_구분자도_알아본다() throws JSONException {
        JSONObject profile = new JSONObject();
        Store.applyFlatZones(profile,
                new String[]{"B1_A", "B1_B", "B2_A", "B2_B"});

        assertEquals("_", profile.getString("sep"));
        assertArrayEquals(new String[]{"B1", "B2"}, toArray(profile.getJSONArray("rows")));
    }

    @Test
    public void applyFlatZones_격자가_아니면_1차원으로_보존한다() throws JSONException {
        JSONObject profile = new JSONObject();
        String[] flat = {"정문 앞", "지하 입구", "옥상", "길가"};
        Store.applyFlatZones(profile, flat);

        assertEquals(0, profile.getJSONArray("rows").length()); // 층 없음
        assertArrayEquals(flat, toArray(profile.getJSONArray("cols")));
    }

    @Test
    public void applyFlatZones_왕복해도_구역_이름이_그대로다() throws JSONException {
        // 마이그레이션의 핵심 불변조건: 무슨 일이 있어도 저장된 구역 이름은 안 바뀐다.
        String[][] cases = {
                {"B1-A", "B1-B", "B2-A", "B2-B"},
                {"B1_A", "B1_B", "B2_A", "B2_B"},
                {"정문 앞", "지하 입구", "옥상", "길가"},
                {"A", "B", "C"},
                {"B1-A", "B1-B", "B2-A", "B3-A"},
        };
        for (String[] flat : cases) {
            JSONObject profile = new JSONObject();
            Store.applyFlatZones(profile, flat);
            String[] back = Store.flatten(
                    toArray(profile.getJSONArray("rows")),
                    toArray(profile.getJSONArray("cols")),
                    profile.getString("sep"));
            assertArrayEquals("왕복 실패: " + String.join(", ", flat), flat, back);
        }
    }

    // ---------- 상한 ----------

    @Test
    public void colLimit_층이_있으면_격자_한_변_없으면_목록_상한() {
        assertEquals(Store.MAX_COLS, Store.colLimit(new String[]{"B1"}));
        assertEquals(Store.MAX_FLAT_ZONES, Store.colLimit(new String[0]));
        assertEquals(Store.MAX_FLAT_ZONES, Store.colLimit(null));
    }

    // ---------- 라벨 생성 ----------

    @Test
    public void basementRows_와_columnLabels() {
        assertArrayEquals(new String[]{"B1", "B2", "B3"}, Store.basementRows(3));
        assertArrayEquals(new String[]{"A", "B", "C", "D"}, Store.columnLabels(4));
        assertEquals(0, Store.basementRows(0).length);
        assertEquals(0, Store.basementRows(-5).length); // 음수도 터지지 않아야 한다
    }

    // ---------- 격자 ↔ 기타 구역 중복 ----------

    @Test
    public void firstDuplicate_겹치는_이름을_찾는다() {
        // 지하 5층 × 2구역을 고르면 격자가 B5-A를 만든다. 예전 기본 기타 구역과 충돌한다.
        String[] grid = Store.flatten(
                Store.basementRows(5), Store.columnLabels(2), "-");
        assertEquals("B5-A", Store.firstDuplicate(grid, new String[]{"B5-A", "옥상"}));
    }

    @Test
    public void firstDuplicate_대소문자를_무시한다() {
        assertNotNull(Store.firstDuplicate(new String[]{"B1-a"}, new String[]{"B1-A"}));
    }

    @Test
    public void firstDuplicate_겹치지_않으면_null() {
        assertNull(Store.firstDuplicate(
                new String[]{"B1-A", "B1-B"}, new String[]{"옥상", "길가"}));
        assertNull(Store.firstDuplicate(new String[]{"B1-A"}, null));
        assertNull(Store.firstDuplicate(null, null));
    }

    @Test
    public void firstDuplicate_한_목록_안의_중복도_잡는다() {
        assertEquals("A", Store.firstDuplicate(new String[]{"A", "B", "A"}, new String[0]));
    }

    // ---------- 칸 높이 ----------

    @Test
    public void cellHeightDp_탭할_수_있는_칸은_48dp_아래로_안_내려간다() {
        // 리디자인 때 6구역 이상 격자가 42dp → 40dp로 내려갔다. 마진은 히트 영역이
        // 아니라 여백으로 벌충되지도 않는다. 운전 직후 한 손으로 누르는 앱이라
        // 여기가 앱에서 제일 아픈 회귀다.
        for (int cols = 1; cols <= Store.MAX_COLS; cols++) {
            assertTrue("격자 " + cols + "구역: " + ZoneGrid.cellHeightDp(cols, true, false),
                    ZoneGrid.cellHeightDp(cols, true, false) >= ZoneGrid.MIN_TOUCH_DP);
            assertTrue("목록 " + cols + "구역: " + ZoneGrid.cellHeightDp(cols, false, false),
                    ZoneGrid.cellHeightDp(cols, false, false) >= ZoneGrid.MIN_TOUCH_DP);
        }
    }

    @Test
    public void cellHeightDp_구역이_많아질수록_낮아지되_단조롭다() {
        // 하한을 지키느라 중간 구간이 뒤집히면(적은 구역이 더 낮아지면) 격자가 이상해진다.
        int previous = Integer.MAX_VALUE;
        for (int cols = 1; cols <= Store.MAX_COLS; cols++) {
            int height = ZoneGrid.cellHeightDp(cols, true, false);
            assertTrue("구역 " + cols + "에서 커졌다", height <= previous);
            previous = height;
        }
    }

    @Test
    public void cellHeightDp_미리보기는_하한을_적용받지_않는다() {
        // compact는 tap이 null이라 버튼이 비활성이다. 여기까지 48dp로 키우면
        // 온보딩 미리보기가 화면을 넘긴다.
        assertTrue(ZoneGrid.cellHeightDp(8, true, true) < ZoneGrid.MIN_TOUCH_DP);
    }

    // ---------- 칸 폭 ----------

    @Test
    public void cellWidthDp_6열부터_8열까지_48dp_폭을_보장한다() {
        // 실제 홈 격자는 이 값을 고정 폭으로 적용하고 하나의 가로 스크롤 안에 넣는다.
        // 열 수가 늘었다고 폭을 줄이면 높이만 48dp인 가늘고 잘못 누르기 쉬운 칸이 된다.
        for (int cols = 6; cols <= Store.MAX_COLS; cols++) {
            assertTrue("격자 " + cols + "구역: " + ZoneGrid.cellWidthDp(cols, true, false),
                    ZoneGrid.cellWidthDp(cols, true, false) >= ZoneGrid.MIN_TOUCH_DP);
        }
    }

    @Test
    public void cellWidthDp_미리보기는_폭_하한을_적용받지_않는다() {
        assertTrue(ZoneGrid.cellWidthDp(8, true, true) < ZoneGrid.MIN_TOUCH_DP);
    }

    // ---------- 거리 ----------

    @Test
    public void metersBetween_같은_지점은_0() {
        assertEquals(0.0, Store.metersBetween(37.5665, 126.9780, 37.5665, 126.9780), 0.001);
    }

    @Test
    public void metersBetween_서울시청에서_약_300m() {
        // 위도 0.0027도 ≈ 300m. 판정 반경이 이 스케일이라 여기가 맞아야 한다.
        double d = Store.metersBetween(37.5665, 126.9780, 37.5692, 126.9780);
        assertTrue("실제: " + d, d > 280 && d < 320);
    }

    @Test
    public void profileRadius_없거나_0이면_기본값() {
        assertEquals(Store.DEFAULT_RADIUS_M, Store.profileRadius(null));
        assertEquals(Store.DEFAULT_RADIUS_M, Store.profileRadius(new JSONObject()));
    }

    @Test
    public void hasCoords_는_위경도가_둘_다_있어야_참() throws JSONException {
        assertFalse(Store.hasCoords(null));
        assertFalse(Store.hasCoords(new JSONObject().put("lat", 37.5)));
        assertTrue(Store.hasCoords(new JSONObject().put("lat", 37.5).put("lon", 127.0)));
    }

    // ---------- 기타 구역 상한 ----------

    @Test
    public void 기타_구역_상한은_목록형_구역_상한과_같다() {
        // README가 "기타 구역은 0~30개"라고 약속한다. 화면(ZoneSettingsActivity)과
        // 저장 검증(Store.setGrid)이 같은 상수를 보므로 여기서 값만 못 박는다.
        assertEquals(30, Store.MAX_ETC_ZONES);
        assertEquals(Store.MAX_FLAT_ZONES, Store.MAX_ETC_ZONES);
    }

    private static String[] toArray(JSONArray a) {
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i);
        return out;
    }
}
