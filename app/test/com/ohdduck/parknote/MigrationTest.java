package com.ohdduck.parknote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 이전 버전 데이터의 이관. 여기가 깨지면 기존 사용자의 기록과 구역 구성이 통째로 날아간다.
 */
public class MigrationTest {

    // ---------- 기록 정규화 ----------

    @Test
    public void normalizeHistory_v2_2_기록에_id와_기본_주차장_차량을_채운다() throws JSONException {
        JSONArray old = new JSONArray().put(new JSONObject().put("z", "B2-A").put("t", 1000L));
        JSONArray out = Migration.normalizeHistory(old,
                profiles("legacy-default-profile", "우리 집"),
                vehicles("legacy-default-vehicle", "내 차"), "주차장", "차량");

        assertEquals(1, out.length());
        JSONObject entry = out.getJSONObject(0);
        assertFalse(entry.getString("id").isEmpty());
        assertEquals("legacy-default-profile", entry.getString("p"));
        assertEquals("우리 집", entry.getString("pn"));
        assertEquals("legacy-default-vehicle", entry.getString("c"));
        assertEquals("내 차", entry.getString("cn"));
        assertEquals("B2-A", entry.getString("z")); // 구역 이름은 절대 안 바뀐다
        assertEquals(1000L, entry.getLong("t"));
    }

    @Test
    public void normalizeHistory_모르는_주차장은_첫_항목으로_되돌린다() throws JSONException {
        JSONArray old = new JSONArray().put(new JSONObject()
                .put("id", "r1").put("z", "A").put("t", 1L)
                .put("p", "ghost").put("c", "ghost"));
        JSONArray out = Migration.normalizeHistory(old, profiles("home", "우리 집"),
                vehicles("car", "내 차"), "주차장", "차량");

        JSONObject entry = out.getJSONObject(0);
        assertEquals("r1", entry.getString("id")); // 있던 id는 유지
        assertEquals("home", entry.getString("p"));
        assertEquals("car", entry.getString("c"));
    }

    @Test
    public void normalizeHistory_이름_스냅샷이_있으면_덮어쓰지_않는다() throws JSONException {
        JSONArray old = new JSONArray().put(new JSONObject()
                .put("id", "r1").put("z", "A").put("t", 1L)
                .put("p", "home").put("pn", "옛 이름").put("c", "car").put("cn", "옛 차"));
        JSONArray out = Migration.normalizeHistory(old, profiles("home", "새 이름"),
                vehicles("car", "새 차"), "주차장", "차량");

        assertEquals("옛 이름", out.getJSONObject(0).getString("pn"));
        assertEquals("옛 차", out.getJSONObject(0).getString("cn"));
    }

    @Test
    public void normalizeHistory_원본_배열은_건드리지_않는다() throws JSONException {
        JSONObject original = new JSONObject().put("z", "A").put("t", 1L);
        JSONArray old = new JSONArray().put(original);
        Migration.normalizeHistory(old, profiles("home", "집"), vehicles("car", "차"),
                "주차장", "차량");
        assertFalse(original.has("id")); // 사본에만 채워야 실패 시 되돌릴 수 있다
    }

    // ---------- inferGrid: 평면 목록 → 격자 역추론 ----------

    @Test
    public void inferGrid_완전한_곱집합이면_격자로_되돌린다() {
        String[][] grid = Migration.inferGrid(
                new String[]{"B1-A", "B1-B", "B2-A", "B2-B"}, "-");
        assertNotNull(grid);
        assertArrayEquals(new String[]{"B1", "B2"}, grid[0]);
        assertArrayEquals(new String[]{"A", "B"}, grid[1]);
    }

    @Test
    public void inferGrid_한_칸이라도_비면_포기한다() {
        // B2-B가 없다 → 격자가 아니라 사용자가 손으로 고른 목록이다. 그대로 보존해야 한다.
        assertNull(Migration.inferGrid(new String[]{"B1-A", "B1-B", "B2-A", "B3-A"}, "-"));
    }

    @Test
    public void inferGrid_순서가_행우선이_아니면_포기한다() {
        assertNull(Migration.inferGrid(new String[]{"B1-A", "B2-A", "B1-B", "B2-B"}, "-"));
    }

    @Test
    public void inferGrid_구분자가_두_번_나오면_포기한다() {
        assertNull(Migration.inferGrid(
                new String[]{"B1-A-1", "B1-A-2", "B2-A-1", "B2-A-2"}, "-"));
    }

    @Test
    public void inferGrid_네_개_미만은_격자로_보지_않는다() {
        assertNull(Migration.inferGrid(new String[]{"B1-A", "B1-B"}, "-"));
    }

    @Test
    public void inferGrid_구분자가_맨_앞이나_뒤면_포기한다() {
        assertNull(Migration.inferGrid(new String[]{"-A", "-B", "B2-A", "B2-B"}, "-"));
        assertNull(Migration.inferGrid(new String[]{"B1-", "B1-B", "B2-", "B2-B"}, "-"));
    }

    // ---------- applyFlatZones: 마이그레이션 진입점 ----------

    @Test
    public void applyFlatZones_격자로_떨어지면_행과_열로_나눈다() throws JSONException {
        JSONObject profile = new JSONObject();
        Migration.applyFlatZones(profile,
                new String[]{"B1-A", "B1-B", "B2-A", "B2-B"});

        assertEquals("-", profile.getString("sep"));
        assertArrayEquals(new String[]{"B1", "B2"}, Json.strings(profile.getJSONArray("rows")));
        assertArrayEquals(new String[]{"A", "B"}, Json.strings(profile.getJSONArray("cols")));
    }

    @Test
    public void applyFlatZones_언더바_구분자도_알아본다() throws JSONException {
        JSONObject profile = new JSONObject();
        Migration.applyFlatZones(profile,
                new String[]{"B1_A", "B1_B", "B2_A", "B2_B"});

        assertEquals("_", profile.getString("sep"));
        assertArrayEquals(new String[]{"B1", "B2"}, Json.strings(profile.getJSONArray("rows")));
    }

    @Test
    public void applyFlatZones_격자가_아니면_1차원으로_보존한다() throws JSONException {
        JSONObject profile = new JSONObject();
        String[] flat = {"정문 앞", "지하 입구", "옥상", "길가"};
        Migration.applyFlatZones(profile, flat);

        assertEquals(0, profile.getJSONArray("rows").length()); // 층 없음
        assertArrayEquals(flat, Json.strings(profile.getJSONArray("cols")));
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
            Migration.applyFlatZones(profile, flat);
            String[] back = Store.flatten(
                    Json.strings(profile.getJSONArray("rows")),
                    Json.strings(profile.getJSONArray("cols")),
                    profile.getString("sep"));
            assertArrayEquals("왕복 실패: " + String.join(", ", flat), flat, back);
        }
    }

    private static JSONArray profiles(String id, String name) throws JSONException {
        return new JSONArray().put(new JSONObject().put("id", id).put("n", name));
    }

    private static JSONArray vehicles(String id, String name) throws JSONException {
        return new JSONArray().put(new JSONObject().put("id", id).put("n", name));
    }
}
