package com.ohdduck.parknote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** 층×구역 격자 로직과 칸 크기, 주차장 좌표. 평면 목록의 격자 역추론은 MigrationTest에 있다. */
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

    // ---------- 상한 ----------

    @Test
    public void colLimit_층이_있으면_격자_한_변_없으면_목록_상한() {
        assertEquals(Store.MAX_COLS, Store.colLimit(new String[]{"B1"}));
        assertEquals(Store.MAX_FLAT_ZONES, Store.colLimit(new String[0]));
        assertEquals(Store.MAX_FLAT_ZONES, Store.colLimit(null));
    }

    @Test
    public void 기타_구역_상한은_목록형_구역_상한과_같다() {
        // README가 "기타 구역은 0~30개"라고 약속한다. 화면(ZoneSettingsActivity)과
        // 저장 검증(Store.setGrid)이 같은 상수를 보므로 여기서 값만 못 박는다.
        assertEquals(30, Store.MAX_ETC_ZONES);
        assertEquals(Store.MAX_FLAT_ZONES, Store.MAX_ETC_ZONES);
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

    // ---------- 거리 ----------

    @Test
    public void metersBetween_같은_지점은_0() {
        assertEquals(0.0, Nearby.metersBetween(37.5665, 126.9780, 37.5665, 126.9780), 0.001);
    }

    @Test
    public void metersBetween_서울시청에서_약_300m() {
        // 위도 0.0027도 ≈ 300m. 판정 반경이 이 스케일이라 여기가 맞아야 한다.
        double d = Nearby.metersBetween(37.5665, 126.9780, 37.5692, 126.9780);
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
}
