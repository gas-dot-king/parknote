package com.ohdduck.parknote;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.service.quicksettings.TileService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 주차장 프로필·차량·주차 기록의 저장/조회. 앱 화면, 위젯, 알림, 타일이 모두 이 클래스를 쓴다.
 *
 * <p>이전 버전 데이터의 이관은 {@link Migration}, 습관은 {@link Habits}, 시각 표시는
 * {@link Fmt}, 알림 채널은 {@link Notify}가 맡는다.
 */
class Store {

    static final String LEGACY_PROFILE_ID = "legacy-default-profile";
    static final String LEGACY_VEHICLE_ID = "legacy-default-vehicle";

    /**
     * 저장 구조 버전. 올릴 때는 Backup.FORMAT도 함께 볼 것 — validate가 이 값보다 새 백업을 거부한다.
     *
     * <p>v6: 기록에 구역이 없을 수 있고(초안), 출차 시각(e)과 사진(ph)이 붙는다.
     * 구조 변환은 없지만 v5 앱이 이 기록을 "?" 구역으로 읽게 두지 않으려고 올렸다.
     */
    static final int SCHEMA_VERSION = 6;

    /**
     * 끊겼다 이만큼 안에 다시 붙으면 잠깐의 신호 끊김으로 본다. 그 사이 만들어진
     * 빈 초안(구역·사진·메모 없음)은 지운다.
     */
    static final long FLAP_MS = 3 * 60 * 1000L;
    static final int MAX_MEMO_LENGTH = 200;
    static final int MAX_PROFILES = 6;
    static final int MAX_VEHICLES = 3;
    /** 격자 한 변의 상한. 칸이 너무 많으면 한 화면에서 고르는 이점이 사라진다. */
    static final int MAX_ROWS = 8;
    static final int MAX_COLS = 8;
    /** 층 없이 쓰는 목록형은 격자가 아니므로 예전 상한(30개)을 그대로 허용한다. */
    static final int MAX_FLAT_ZONES = 30;
    /** 격자 밖 기타 구역의 상한. 화면과 저장 검증이 같은 값을 본다. */
    static final int MAX_ETC_ZONES = 30;
    static final String DEFAULT_SEP = "-";

    // 층(rows) × 구역(cols) 격자. rows가 비면 cols가 그대로 버튼이 되는 1차원 목록이다.
    static final String[] DEFAULT_ROWS = {"B1", "B2", "B3"};
    static final String[] DEFAULT_COLS = {"A", "B"};
    /** 새 주차장의 기타 구역은 비어 있다. 필요하면 사용자가 직접 채운다. */
    static final String[] NO_ZONES = {};

    /** 프로필 좌표의 기본 반경(m). 지하 진입 직전 좌표를 쓰므로 넉넉하게 잡는다. */
    static final int DEFAULT_RADIUS_M = 300;

    // 저장 키. Migration·exportData·importData가 같은 이름을 본다.
    static final String PREF_SCHEMA = "parking_schema";
    static final String PREF_PROFILES = "parking_profiles_v1";
    static final String PREF_ACTIVE_PROFILE = "active_parking_profile_id";
    static final String PREF_VEHICLES = "parking_vehicles_v1";
    static final String PREF_ACTIVE_VEHICLE = "active_parking_vehicle_id";
    static final String PREF_HISTORY = "history";
    static final String PREF_HABITS = "habits";
    static final String PREF_ONBOARDED = "onboarded";
    static final String PREF_LOCATION_FILTER = "location_filter";
    /** BtReceiver가 차량별로 본 마지막 연결/해제. 홈 감지 카드의 표시용 상태다. */
    private static final String PREF_BT_STATE = "bt_state";

    /**
     * 주차장×차량 맥락 하나가 보관하는 기록 수.
     *
     * <p>예전에는 전체 40개가 상한이었다. 주차장 6개 × 차량 3대까지 허용해 놓고 그 18개
     * 맥락이 40칸을 나눠 쓰는 구조라, 다차량 사용자는 "최근에 댄 곳 5개"를 채우지도
     * 못했고 위젯의 최근 구역 학습(widgetZones)도 무력화됐다.
     */
    static final int MAX_HISTORY_PER_CONTEXT = 20;
    /** 전체 상한. 맥락이 늘어나도 SharedPreferences 한 덩어리가 무한정 커지지 않게 막는다. */
    static final int MAX_HISTORY = 240;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("parknote", Context.MODE_PRIVATE);
    }

    // ---------- 파싱 캐시 ----------

    /**
     * 홈을 한 번 그리는 동안 history()가 열 번 넘게 불린다(히어로·최근 목록·위젯·타일).
     * 매번 수십 KB JSON을 다시 파싱할 이유가 없다. SharedPreferences는 값이 그대로면 같은
     * String 인스턴스를 돌려주므로 equals가 사실상 O(1)이고, 값이 바뀌면(저장·복원·
     * 마이그레이션) 다음 읽기에서 자연히 다시 파싱된다.
     *
     * <p>규칙: 돌려받은 배열을 고치는 쪽은 같은 호출 안에서 저장까지 끝낸다. 고쳐 놓고
     * 저장하지 않으면 다음 호출자가 저장되지 않은 값을 본다.
     */
    private static final class Parsed {
        String raw;
        JSONArray value;
    }

    private static final Parsed PROFILES = new Parsed();
    private static final Parsed VEHICLES = new Parsed();
    private static final Parsed HISTORY = new Parsed();

    private static JSONArray parsed(Parsed slot, String raw) {
        if (raw.equals(slot.raw)) return slot.value;
        JSONArray value = Json.array(raw);
        slot.value = value == null ? new JSONArray() : value;
        slot.raw = raw;
        return slot.value;
    }

    // ---------- 격자 (층 × 구역) ----------

    /** 격자를 실제 저장되는 구역 이름 배열로 편다. 행이 없으면 열이 곧 구역이다. */
    static String[] flatten(String[] rows, String[] cols, String sep) {
        if (cols == null || cols.length == 0) return new String[0];
        if (rows == null || rows.length == 0) return cols.clone();
        String[] out = new String[rows.length * cols.length];
        int i = 0;
        for (String row : rows) {
            for (String col : cols) out[i++] = row + sep + col;
        }
        return out;
    }

    /** 지하 n개층 라벨 (B1 ~ Bn) */
    static String[] basementRows(int count) {
        String[] out = new String[Math.max(0, count)];
        for (int i = 0; i < out.length; i++) out[i] = "B" + (i + 1);
        return out;
    }

    /** 구역 n개 라벨 (A, B, C …) */
    static String[] columnLabels(int count) {
        String[] out = new String[Math.max(0, count)];
        for (int i = 0; i < out.length; i++) out[i] = String.valueOf((char) ('A' + i));
        return out;
    }

    static JSONObject newProfile(String id, String name, String[] rows, String[] cols,
                                 String sep, String[] etc) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("n", name)
                .put("rows", Json.of(rows))
                .put("cols", Json.of(cols))
                .put("sep", sep)
                .put("etc", Json.of(etc));
    }

    static JSONObject newVehicle(String id, String name, String bt, String address)
            throws JSONException {
        JSONObject out = new JSONObject().put("id", id).put("n", name).put("b", bt);
        if (!address.isEmpty()) out.put("a", address);
        return out;
    }

    /** 기타 구역처럼 격자 밖에 있는 평면 목록을 읽는다. */
    private static String[] zonesOf(JSONObject profile, String key,
                                    String[] defaults, boolean emptyAllowed) {
        if (profile == null) return defaults.clone();
        JSONArray a = profile.optJSONArray(key);
        if (a == null) return defaults.clone();
        String[] out = Json.strings(a);
        if (out.length > 0 || emptyAllowed) return out;
        return defaults.clone();
    }

    /** 프로필의 층 목록. 비어 있으면 1차원(층 없이 구역 버튼만) 구성이다. */
    static String[] rows(JSONObject profile) {
        return profile == null ? DEFAULT_ROWS.clone() : Json.strings(profile.optJSONArray("rows"));
    }

    static String[] cols(JSONObject profile) {
        if (profile == null) return DEFAULT_COLS.clone();
        String[] cols = Json.strings(profile.optJSONArray("cols"));
        return cols.length == 0 ? DEFAULT_COLS.clone() : cols;
    }

    static String sep(JSONObject profile) {
        return profile == null ? DEFAULT_SEP : profile.optString("sep", DEFAULT_SEP);
    }

    static String[] etc(JSONObject profile) {
        return zonesOf(profile, "etc", NO_ZONES, true);
    }

    /** 프로필의 격자를 편 구역 이름 배열. 위젯·알림·화면이 모두 이 결과를 쓴다. */
    private static String[] gridZones(JSONObject profile) {
        return flatten(rows(profile), cols(profile), sep(profile));
    }

    // ---------- 주차장 프로필 ----------

    static JSONArray profiles(Context c) {
        Migration.ensure(c);
        return parsed(PROFILES, prefs(c).getString(PREF_PROFILES, "[]"));
    }

    static int profileCount(Context c) {
        return profiles(c).length();
    }

    static String activeProfileId(Context c) {
        Migration.ensure(c);
        return prefs(c).getString(PREF_ACTIVE_PROFILE, LEGACY_PROFILE_ID);
    }

    static JSONObject activeProfile(Context c) {
        JSONArray ps = profiles(c);
        JSONObject profile = Json.byId(ps, activeProfileId(c));
        return profile == null ? ps.optJSONObject(0) : profile;
    }

    static JSONObject profileById(Context c, String id) {
        return Json.byId(profiles(c), id);
    }

    static String profileName(Context c, String id) {
        JSONObject profile = profileById(c, id);
        return profile == null
                ? c.getString(R.string.profile_deleted)
                : profile.optString("n", c.getString(R.string.profile_default_name));
    }

    static String activeProfileName(Context c) {
        JSONObject profile = activeProfile(c);
        String fallback = c.getString(R.string.profile_default_name);
        return profile == null ? fallback : profile.optString("n", fallback);
    }

    static void setActiveProfile(Context c, String id) {
        if (profileById(c, id) == null) return;
        prefs(c).edit().putString(PREF_ACTIVE_PROFILE, id).apply();
        notifyParkingContextChanged(c);
    }

    /** 새 프로필은 현재 프로필의 구역 구성을 복사해 바로 다듬을 수 있게 한다. */
    static String addProfile(Context c, String name) {
        String n = Json.clean(name);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_profile_name_required));
        JSONArray ps = profiles(c);
        if (ps.length() >= MAX_PROFILES) {
            throw new IllegalArgumentException(
                    c.getString(R.string.err_profile_limit, MAX_PROFILES));
        }
        if (nameTaken(ps, n, null)) throw new IllegalArgumentException(c.getString(R.string.err_profile_name_taken));
        JSONObject current = activeProfile(c);
        String id = UUID.randomUUID().toString();
        try {
            ps.put(newProfile(id, n,
                    current == null ? DEFAULT_ROWS : Json.strings(current.optJSONArray("rows")),
                    current == null ? DEFAULT_COLS : Json.strings(current.optJSONArray("cols")),
                    current == null ? DEFAULT_SEP : current.optString("sep", DEFAULT_SEP),
                    zonesOf(current, "etc", NO_ZONES, true)));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit()
                .putString(PREF_PROFILES, ps.toString())
                .putString(PREF_ACTIVE_PROFILE, id)
                .apply();
        notifyParkingContextChanged(c);
        return id;
    }

    static void renameProfile(Context c, String id, String name) {
        String n = Json.clean(name);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_profile_name_required));
        JSONArray ps = profiles(c);
        JSONObject profile = Json.byId(ps, id);
        if (profile == null) throw new IllegalArgumentException(c.getString(R.string.err_profile_not_found));
        if (nameTaken(ps, n, id)) throw new IllegalArgumentException(c.getString(R.string.err_profile_name_taken));
        try {
            profile.put("n", n);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveProfiles(c, ps);
        notifyParkingContextChanged(c);
    }

    static boolean deleteProfile(Context c, String id) {
        JSONArray ps = profiles(c);
        if (ps.length() <= 1 || Json.byId(ps, id) == null) return false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject profile = ps.optJSONObject(i);
            if (profile != null && !id.equals(profile.optString("id"))) next.put(profile);
        }
        JSONObject fallback = next.optJSONObject(0);
        String fallbackId = fallback == null ? LEGACY_PROFILE_ID : fallback.optString("id");

        SharedPreferences.Editor edit = prefs(c).edit().putString(PREF_PROFILES, next.toString());
        JSONArray history = history(c);
        boolean timersCleared = false;
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("p"))
                    && entry.optLong("due", 0) > 0) {
                ParkingTimers.cancel(c, entry.optString("id"));
                entry.remove("due");
                timersCleared = true;
            }
        }
        if (timersCleared) edit.putString(PREF_HISTORY, history.toString());
        if (id.equals(activeProfileId(c))) edit.putString(PREF_ACTIVE_PROFILE, fallbackId);
        edit.apply();
        notifyParkingContextChanged(c);
        return true;
    }

    private static void saveProfiles(Context c, JSONArray ps) {
        prefs(c).edit().putString(PREF_PROFILES, ps.toString()).apply();
    }

    static String[] etcZones(Context c) {
        return etc(activeProfile(c));
    }

    static String[] mainZonesForProfile(Context c, String profileId) {
        return gridZones(profileById(c, profileId));
    }

    static String[] activeRows(Context c) {
        return rows(activeProfile(c));
    }

    static String[] activeCols(Context c) {
        return cols(activeProfile(c));
    }

    static String activeSep(Context c) {
        return sep(activeProfile(c));
    }

    /**
     * 구역 개수 상한. 층이 있으면 격자의 한 변이라 8개, 층이 없으면 그냥 버튼 목록이라
     * v2.5까지 쓰던 30개를 그대로 허용한다. 그러지 않으면 예전에 구역을 10개 넘게
     * 만들어 둔 사용자가 구역 편집을 저장조차 할 수 없게 된다.
     */
    static int colLimit(String[] rows) {
        return rows != null && rows.length > 0 ? MAX_COLS : MAX_FLAT_ZONES;
    }

    static void setGrid(Context c, String[] rows, String[] cols, String sep, String[] etc) {
        if (cols == null || cols.length == 0) {
            throw new IllegalArgumentException(c.getString(R.string.err_zone_required));
        }
        if (rows != null && rows.length > MAX_ROWS) {
            throw new IllegalArgumentException(c.getString(R.string.zone_limit,
                    c.getString(R.string.zone_rows_name), MAX_ROWS));
        }
        int colLimit = colLimit(rows);
        if (cols.length > colLimit) {
            throw new IllegalArgumentException(c.getString(R.string.zone_limit,
                    c.getString(R.string.zone_cols_name), colLimit));
        }
        if (etc != null && etc.length > MAX_ETC_ZONES) {
            throw new IllegalArgumentException(c.getString(R.string.zone_limit,
                    c.getString(R.string.zone_etc_blocks_name), MAX_ETC_ZONES));
        }
        // 격자가 만들어 내는 이름과 기타 구역이 겹치면 같은 이름의 버튼이 두 개가 되고,
        // 기록이 어느 쪽인지 모호해진다. 화면 검증에만 두면 온보딩처럼 다른 경로로
        // 들어온 값이 그대로 저장돼 버리므로 여기서 막는다.
        String duplicate = firstDuplicate(flatten(rows, cols, sep), etc);
        if (duplicate != null) {
            throw new IllegalArgumentException(
                    c.getString(R.string.err_zone_grid_overlap, duplicate));
        }
        JSONArray ps = profiles(c);
        JSONObject profile = Json.byId(ps, activeProfileId(c));
        if (profile == null) return;
        try {
            profile.put("rows", Json.of(rows == null ? new String[0] : rows));
            profile.put("cols", Json.of(cols));
            profile.put("sep", Json.clean(sep).isEmpty() ? DEFAULT_SEP : sep);
            profile.put("etc", Json.of(etc == null ? new String[0] : etc));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveProfiles(c, ps);
        notifyParkingContextChanged(c);
    }

    /**
     * 두 목록을 합쳤을 때 처음 겹치는 이름. 없으면 null.
     * 비교는 대소문자를 무시한다 — 버튼 두 개가 "b1-a"와 "B1-A"로 갈리면 안 된다.
     */
    static String firstDuplicate(String[] first, String[] second) {
        HashSet<String> seen = new HashSet<>();
        for (String[] group : new String[][]{first, second}) {
            if (group == null) continue;
            for (String zone : group) {
                String key = Json.clean(zone).toLowerCase(Locale.ROOT);
                if (key.isEmpty()) continue;
                if (!seen.add(key)) return zone;
            }
        }
        return null;
    }

    static void resetZones(Context c) {
        setGrid(c, DEFAULT_ROWS, DEFAULT_COLS, DEFAULT_SEP, NO_ZONES);
    }

    // ---------- 위치로 알림 조절 ----------
    // 프로필에 좌표를 심어 두면 차에서 내린 곳이 어느 주차장인지 알 수 있다.
    // 기능 전체가 이 스위치 하나에 걸려 있어, 꺼 두면 위치를 아예 읽지 않는다.

    static boolean locationFilterOn(Context c) {
        Migration.ensure(c);
        return prefs(c).getBoolean(PREF_LOCATION_FILTER, false);
    }

    static void setLocationFilter(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_LOCATION_FILTER, on).apply();
    }

    static boolean hasCoords(JSONObject profile) {
        return profile != null && profile.has("lat") && profile.has("lon");
    }

    /** 좌표가 하나라도 등록돼 있는지. 하나도 없으면 위치를 읽어 봐야 소용이 없다. */
    static boolean anyProfileHasCoords(Context c) {
        JSONArray ps = profiles(c);
        for (int i = 0; i < ps.length(); i++) {
            if (hasCoords(ps.optJSONObject(i))) return true;
        }
        return false;
    }

    static void setProfileCoords(Context c, String profileId, double lat, double lon, int radiusM) {
        JSONArray ps = profiles(c);
        JSONObject profile = Json.byId(ps, profileId);
        if (profile == null) return;
        try {
            profile.put("lat", lat);
            profile.put("lon", lon);
            profile.put("rad", radiusM > 0 ? radiusM : DEFAULT_RADIUS_M);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveProfiles(c, ps);
    }

    static void clearProfileCoords(Context c, String profileId) {
        JSONArray ps = profiles(c);
        JSONObject profile = Json.byId(ps, profileId);
        if (profile == null) return;
        profile.remove("lat");
        profile.remove("lon");
        profile.remove("rad");
        saveProfiles(c, ps);
    }

    static int profileRadius(JSONObject profile) {
        int radius = profile == null ? 0 : profile.optInt("rad", DEFAULT_RADIUS_M);
        return radius > 0 ? radius : DEFAULT_RADIUS_M;
    }

    /** 주어진 좌표의 반경 안에 드는 프로필 중 가장 가까운 것. 없으면 null. */
    static JSONObject profileNear(Context c, double lat, double lon) {
        JSONArray ps = profiles(c);
        JSONObject best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject profile = ps.optJSONObject(i);
            if (!hasCoords(profile)) continue;
            double distance = Nearby.metersBetween(lat, lon,
                    profile.optDouble("lat"), profile.optDouble("lon"));
            if (distance <= profileRadius(profile) && distance < bestDistance) {
                bestDistance = distance;
                best = profile;
            }
        }
        return best;
    }

    /**
     * 좌표를 등록한 주차장 중 반경과 무관하게 가장 가까운 것. 없으면 null.
     *
     * <p>홈의 감지 상태 카드가 "우리집에서 15.7km"를 만들 때 쓴다.
     * {@link #profileNear}는 반경 안인지만 답하므로 따로 본다.
     */
    static JSONObject nearestProfile(Context c, double lat, double lon) {
        JSONArray ps = profiles(c);
        JSONObject best = null;
        double bestDistance = 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject profile = ps.optJSONObject(i);
            if (!hasCoords(profile)) continue;
            double distance = Nearby.metersBetween(lat, lon,
                    profile.optDouble("lat"), profile.optDouble("lon"));
            if (best == null || distance < bestDistance) {
                bestDistance = distance;
                best = profile;
            }
        }
        return best;
    }

    // ---------- 백업 ----------

    /**
     * 저장된 값을 키 그대로 담아 낸다. 가공하지 않으므로 예전 버전에서 만든 백업을
     * 되돌려도 복원 뒤 평소의 마이그레이션 경로를 그대로 다시 탄다.
     */
    static JSONObject exportData(Context c) {
        Migration.ensure(c);
        SharedPreferences p = prefs(c);
        try {
            return new JSONObject()
                    .put(PREF_SCHEMA, p.getInt(PREF_SCHEMA, SCHEMA_VERSION))
                    .put(PREF_PROFILES, p.getString(PREF_PROFILES, "[]"))
                    .put(PREF_ACTIVE_PROFILE, p.getString(PREF_ACTIVE_PROFILE, ""))
                    .put(PREF_VEHICLES, p.getString(PREF_VEHICLES, "[]"))
                    .put(PREF_ACTIVE_VEHICLE, p.getString(PREF_ACTIVE_VEHICLE, ""))
                    .put(PREF_HISTORY, p.getString(PREF_HISTORY, "[]"))
                    .put(PREF_HABITS, p.getString(PREF_HABITS, "[]"))
                    .put(PREF_ONBOARDED, p.getBoolean(PREF_ONBOARDED, true))
                    .put(PREF_LOCATION_FILTER, p.getBoolean(PREF_LOCATION_FILTER, false));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 백업으로 통째로 교체한다. 지금 걸려 있는 알람은 먼저 거둔다 —
     * 교체 뒤에는 어떤 기록에 붙어 있던 알람인지 알 수 없기 때문.
     */
    static void importData(Context c, JSONObject data) {
        cancelAllScheduled(c);

        // 예전 백업에는 이 키가 없다. 그때는 지금 값을 유지한다 — 복원했다고 켜 둔 기능이
        // 조용히 꺼지면 다음 주차 때 알림 세기만 달라져서 원인을 알 수 없다.
        boolean locationFilter = data.has(PREF_LOCATION_FILTER)
                ? data.optBoolean(PREF_LOCATION_FILTER, false)
                : locationFilterOn(c);
        prefs(c).edit()
                .putInt(PREF_SCHEMA, data.optInt(PREF_SCHEMA, SCHEMA_VERSION))
                .putBoolean(PREF_LOCATION_FILTER, locationFilter)
                .putString(PREF_PROFILES, data.optString(PREF_PROFILES, "[]"))
                .putString(PREF_ACTIVE_PROFILE, data.optString(PREF_ACTIVE_PROFILE, ""))
                .putString(PREF_VEHICLES, data.optString(PREF_VEHICLES, "[]"))
                .putString(PREF_ACTIVE_VEHICLE, data.optString(PREF_ACTIVE_VEHICLE, ""))
                .putString(PREF_HISTORY, data.optString(PREF_HISTORY, "[]"))
                .putString(PREF_HABITS, data.optString(PREF_HABITS, "[]"))
                .putBoolean(PREF_ONBOARDED, data.optBoolean(PREF_ONBOARDED, true))
                // 백업에는 연결 상태가 없다. 옛 값을 두면 복원한 차량이 다음 이벤트까지
                // 연결된(또는 끊긴) 것처럼 보인다.
                .remove(PREF_BT_STATE)
                .apply();

        Migration.ensure(c); // 예전 스키마의 백업이면 여기서 최신 구조로 올라온다
        repairActiveIds(c); // 같은 스키마 백업이면 ensure가 조기 리턴하므로 따로 본다
        ParkingTimers.scheduleAll(c);
        Reminders.scheduleAll(c);
        notifyParkingHistoryChanged(c);
    }

    /**
     * 활성 주차장·차량 id가 실제 목록을 가리키는지 확인하고, 아니면 첫 항목으로 되돌린다.
     *
     * <p>복원은 활성 id까지 백업 값으로 덮어쓰는데, 스키마 버전이 같으면 Migration이
     * 조기 리턴해서 아무도 검증하지 않는다. 어긋난 채로 두면 화면은 activeProfile()의
     * 폴백 덕에 멀쩡해 보이지만, 저장은 조용히 실패한다.
     */
    private static void repairActiveIds(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor edit = null;

        JSONArray ps = profiles(c);
        if (Json.byId(ps, p.getString(PREF_ACTIVE_PROFILE, "")) == null) {
            JSONObject first = ps.optJSONObject(0);
            if (first != null) {
                edit = p.edit();
                edit.putString(PREF_ACTIVE_PROFILE, first.optString("id", LEGACY_PROFILE_ID));
            }
        }
        JSONArray vs = vehicles(c);
        if (Json.byId(vs, p.getString(PREF_ACTIVE_VEHICLE, "")) == null) {
            JSONObject first = vs.optJSONObject(0);
            if (first != null) {
                if (edit == null) edit = p.edit();
                edit.putString(PREF_ACTIVE_VEHICLE, first.optString("id", LEGACY_VEHICLE_ID));
            }
        }
        if (edit != null) edit.apply();
    }

    private static void cancelAllScheduled(Context c) {
        JSONArray h = history(c);
        for (int i = 0; i < h.length(); i++) {
            JSONObject entry = h.optJSONObject(i);
            if (entry != null && entry.optLong("due", 0) > 0) {
                ParkingTimers.cancel(c, entry.optString("id"));
            }
        }
        JSONArray hs = Habits.all(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject habit = hs.optJSONObject(i);
            if (habit == null) continue;
            Reminders.cancel(c, habit.optString("n"));
            Notify.cancelHabit(c, habit.optString("n"));
        }
        JSONArray vs = vehicles(c);
        for (int i = 0; i < vs.length(); i++) {
            JSONObject vehicle = vs.optJSONObject(i);
            if (vehicle != null) Notify.cancelPark(c, vehicle.optString("id"));
        }
    }

    /**
     * 백업에 든 항목 수 {주차장, 차량, 주차 기록, 체크}.
     *
     * <p>문구로 조립하지 않고 숫자만 돌려준다. Store는 Context 없이도 쓸 수 있어야
     * 유닛 테스트가 붙고, 사용자에게 보이는 문장은 리소스에서 와야 하기 때문이다.
     */
    static int[] counts(JSONObject data) {
        return new int[]{
                length(Json.array(data.optString(PREF_PROFILES, "[]"))),
                length(Json.array(data.optString(PREF_VEHICLES, "[]"))),
                length(Json.array(data.optString(PREF_HISTORY, "[]"))),
                length(Json.array(data.optString(PREF_HABITS, "[]")))};
    }

    private static int length(JSONArray a) {
        return a == null ? 0 : a.length();
    }

    /**
     * 복원해도 앱이 멀쩡한 데이터인지 확인한다.
     *
     * @return 문제가 없으면 null, 있으면 사유 문자열 리소스 id.
     */
    static Integer validate(JSONObject data) {
        // 봉투 format이 같아도 안의 스키마가 더 새것일 수 있다. 그대로 들이면 Migration이
        // "이미 최신"으로 보고 조기 리턴해서, 모르는 구조를 검증 없이 쓰게 된다.
        if (data.optInt(PREF_SCHEMA, SCHEMA_VERSION) > SCHEMA_VERSION) {
            return R.string.backup_err_newer;
        }
        JSONArray profiles = Json.array(data.optString(PREF_PROFILES, ""));
        if (profiles == null || profiles.length() == 0) {
            return R.string.backup_err_no_profiles;
        }
        JSONArray vehicles = Json.array(data.optString(PREF_VEHICLES, ""));
        if (vehicles == null || vehicles.length() == 0) {
            return R.string.backup_err_no_vehicles;
        }
        if (Json.array(data.optString(PREF_HISTORY, "[]")) == null) {
            return R.string.backup_err_bad_history;
        }
        if (Json.array(data.optString(PREF_HABITS, "[]")) == null) {
            return R.string.backup_err_bad_habits;
        }
        return null;
    }

    // ---------- 첫 실행 온보딩 ----------

    static boolean isOnboarded(Context c) {
        Migration.ensure(c);
        return prefs(c).getBoolean(PREF_ONBOARDED, false);
    }

    /** 온보딩에서 만든 첫 주차장·차량으로 기본값을 덮어쓴다. */
    static void completeOnboarding(Context c, String profileName, String[] rows, String[] cols,
                                   String sep, String vehicleName, String btName,
                                   String btAddress) {
        String name = Json.clean(profileName);
        if (!name.isEmpty()) {
            try {
                renameProfile(c, activeProfileId(c), name);
            } catch (IllegalArgumentException ignored) {
                // 같은 이름이 이미 있으면 기존 이름을 그대로 둔다.
            }
        }
        // 기타 구역이 새 격자와 겹치면(예전 기본값이 남아 있는 기기) 격자를 우선하고 비운다.
        // 온보딩 마지막 단계에서 예외를 던져 첫 실행을 막을 이유가 없다.
        try {
            setGrid(c, rows, cols, sep, etcZones(c));
        } catch (IllegalArgumentException ignored) {
            setGrid(c, rows, cols, sep, NO_ZONES);
        }
        JSONObject vehicle = activeVehicle(c);
        if (vehicle != null) {
            String vn = Json.clean(vehicleName);
            updateVehicle(c, vehicle.optString("id"),
                    vn.isEmpty()
                            ? vehicle.optString("n",
                                    c.getString(R.string.onboarding_default_vehicle))
                            : vn,
                    btName, btAddress);
        }
        skipOnboarding(c);
    }

    /** 온보딩을 건너뛰어도 다시 묻지 않는다. */
    static void skipOnboarding(Context c) {
        Migration.ensure(c);
        prefs(c).edit().putBoolean(PREF_ONBOARDED, true).apply();
    }

    private static boolean nameTaken(JSONArray items, String name, String exceptId) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || (exceptId != null && exceptId.equals(item.optString("id")))) continue;
            if (name.equalsIgnoreCase(item.optString("n"))) return true;
        }
        return false;
    }

    // ---------- 차량 ----------
    // {id, n: 이름, b: 블루투스 이름, a?: 블루투스 주소}

    static JSONArray vehicles(Context c) {
        Migration.ensure(c);
        return parsed(VEHICLES, prefs(c).getString(PREF_VEHICLES, "[]"));
    }

    static int vehicleCount(Context c) {
        return vehicles(c).length();
    }

    static String activeVehicleId(Context c) {
        Migration.ensure(c);
        return prefs(c).getString(PREF_ACTIVE_VEHICLE, LEGACY_VEHICLE_ID);
    }

    static JSONObject activeVehicle(Context c) {
        JSONArray vs = vehicles(c);
        JSONObject vehicle = Json.byId(vs, activeVehicleId(c));
        return vehicle == null ? vs.optJSONObject(0) : vehicle;
    }

    static JSONObject vehicleById(Context c, String id) {
        return Json.byId(vehicles(c), id);
    }

    static String vehicleName(Context c, String id) {
        JSONObject vehicle = vehicleById(c, id);
        return vehicle == null
                ? c.getString(R.string.vehicle_deleted)
                : vehicle.optString("n", c.getString(R.string.vehicle_default_name));
    }

    static String vehicleBtName(JSONObject vehicle) {
        return vehicle == null ? "" : Json.clean(vehicle.optString("b", ""));
    }

    static String vehicleBtAddress(JSONObject vehicle) {
        return vehicle == null ? "" : Json.clean(vehicle.optString("a", ""));
    }

    static String activeVehicleName(Context c) {
        JSONObject vehicle = activeVehicle(c);
        String fallback = c.getString(R.string.vehicle_default_name);
        return vehicle == null ? fallback : vehicle.optString("n", fallback);
    }

    static void setActiveVehicle(Context c, String id) {
        if (vehicleById(c, id) == null) return;
        prefs(c).edit().putString(PREF_ACTIVE_VEHICLE, id).apply();
        notifyParkingContextChanged(c);
    }

    static String addVehicle(Context c, String name, String btName, String btAddress) {
        String n = Json.clean(name);
        String b = Json.clean(btName);
        String a = Json.clean(btAddress);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_required));
        JSONArray vs = vehicles(c);
        if (vs.length() >= MAX_VEHICLES) {
            throw new IllegalArgumentException(
                    c.getString(R.string.err_vehicle_limit, MAX_VEHICLES));
        }
        if (nameTaken(vs, n, null)) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_taken));
        if (bluetoothTaken(vs, b, a, null)) {
            throw new IllegalArgumentException(c.getString(R.string.err_bt_name_taken));
        }
        String id = UUID.randomUUID().toString();
        try {
            vs.put(newVehicle(id, n, b, a));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit()
                .putString(PREF_VEHICLES, vs.toString())
                .putString(PREF_ACTIVE_VEHICLE, id)
                .apply();
        notifyParkingContextChanged(c);
        return id;
    }

    static void updateVehicle(Context c, String id, String name, String btName,
                              String btAddress) {
        String n = Json.clean(name);
        String b = Json.clean(btName);
        String a = Json.clean(btAddress);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_required));
        JSONArray vs = vehicles(c);
        JSONObject vehicle = Json.byId(vs, id);
        if (vehicle == null) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_not_found));
        boolean deviceChanged = !vehicleBtName(vehicle).equalsIgnoreCase(b)
                || !vehicleBtAddress(vehicle).equalsIgnoreCase(a);
        if (nameTaken(vs, n, id)) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_taken));
        if (bluetoothTaken(vs, b, a, id)) {
            throw new IllegalArgumentException(c.getString(R.string.err_bt_name_taken));
        }
        try {
            vehicle.put("n", n);
            vehicle.put("b", b);
            if (a.isEmpty()) vehicle.remove("a");
            else vehicle.put("a", a);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit().putString(PREF_VEHICLES, vs.toString()).apply();
        if (deviceChanged) clearBtState(c, id);
        Notify.cancelPark(c, id);
        notifyParkingContextChanged(c);
    }

    static boolean deleteVehicle(Context c, String id) {
        JSONArray vs = vehicles(c);
        if (vs.length() <= 1 || Json.byId(vs, id) == null) return false;
        JSONArray next = new JSONArray();
        for (int i = 0; i < vs.length(); i++) {
            JSONObject vehicle = vs.optJSONObject(i);
            if (vehicle != null && !id.equals(vehicle.optString("id"))) next.put(vehicle);
        }
        JSONObject fallback = next.optJSONObject(0);
        String fallbackId = fallback == null ? LEGACY_VEHICLE_ID : fallback.optString("id");
        SharedPreferences.Editor edit = prefs(c).edit().putString(PREF_VEHICLES, next.toString());
        if (id.equals(activeVehicleId(c))) edit.putString(PREF_ACTIVE_VEHICLE, fallbackId);
        edit.apply();
        JSONArray history = history(c);
        boolean timersCleared = false;
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("c"))
                    && entry.optLong("due", 0) > 0) {
                ParkingTimers.cancel(c, entry.optString("id"));
                entry.remove("due");
                timersCleared = true;
            }
        }
        if (timersCleared) saveHistory(c, history);
        clearBtState(c, id);
        Notify.cancelPark(c, id);
        notifyParkingHistoryChanged(c);
        return true;
    }

    /**
     * 끊긴(또는 붙은) 기기에 해당하는 차량. 없으면 null.
     *
     * <p>주소가 먼저다. 같은 헤드유닛 이름("CAR-AUDIO")을 쓰는 차가 둘일 수 있고 이름은
     * 바뀌기도 하는데, 주소는 그 기기 하나를 가리킨다. 주소는 BLUETOOTH_CONNECT 없이도
     * 읽히므로 권한이 빠져도 감지된다. 주소를 아직 저장하지 않은 차량(예전 버전에서
     * 등록)만 이름으로 맞춘다.
     */
    static JSONObject vehicleMatchingBluetooth(Context c, String name, String address) {
        String target = Json.clean(name);
        String addr = Json.clean(address);
        JSONArray vs = vehicles(c);
        JSONObject byName = null;
        for (int i = 0; i < vs.length(); i++) {
            JSONObject vehicle = vs.optJSONObject(i);
            if (vehicle == null) continue;
            String a = vehicleBtAddress(vehicle);
            if (!a.isEmpty()) {
                if (a.equalsIgnoreCase(addr)) return vehicle;
                continue;
            }
            if (byName == null && !target.isEmpty()
                    && vehicleBtName(vehicle).equalsIgnoreCase(target)) {
                byName = vehicle;
            }
        }
        return byName;
    }

    /**
     * 같은 기기를 두 차량에 등록하지 못하게 한다. 둘 다 주소가 있으면 주소로, 아니면
     * 이름으로 비교한다 — 이름이 같아도 주소가 다르면 다른 차다.
     */
    private static boolean bluetoothTaken(JSONArray vehicles, String bt, String address,
                                          String exceptId) {
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject vehicle = vehicles.optJSONObject(i);
            if (vehicle == null || (exceptId != null && exceptId.equals(vehicle.optString("id")))) {
                continue;
            }
            String a = vehicleBtAddress(vehicle);
            if (!address.isEmpty() && !a.isEmpty()) {
                if (address.equalsIgnoreCase(a)) return true;
                continue;
            }
            if (!bt.isEmpty() && bt.equalsIgnoreCase(vehicleBtName(vehicle))) return true;
        }
        return false;
    }

    // ---------- 주차 기록 ----------
    // {id, c: 차량ID, cn: 차량명, p: 주차장ID, pn: 주차장명, z?: 구역, t: 내린 시각,
    //  e?: 출차(블루투스 재연결) 시각, m?: 메모, ph?: 사진 파일 이름, due?: 출차 알림 시각,
    //  lat?/lon?/loc_t?/loc_acc?: 내린 시점 좌표}
    //
    // z가 없는 기록은 "초안"이다 — 차에서 내렸다는 사실(시각·좌표)만 있고 어디에 댔는지는
    // 아직 안 적은 상태. 끊긴 순간 폰이 확실히 아는 두 가지는 알림을 무시해도 남아야 한다.

    /** 전체 기록. 주차장/차량별 화면에는 activeHistory를 사용한다. */
    static JSONArray history(Context c) {
        Migration.ensure(c);
        return parsed(HISTORY, prefs(c).getString(PREF_HISTORY, "[]"));
    }

    static boolean isDraft(JSONObject entry) {
        return entry != null && Json.clean(entry.optString("z", "")).isEmpty();
    }

    static boolean hasEnded(JSONObject entry) {
        return entry != null && entry.optLong("e", 0) > 0;
    }

    /** 아직 차가 그 자리에 있고 구역도 안 적은 초안. 새 기록 대신 이걸 채운다. */
    private static boolean isOpenDraft(JSONObject entry) {
        return isDraft(entry) && !hasEnded(entry);
    }

    /** 시각·좌표 말고는 아무것도 없는 초안. 잠깐의 신호 끊김이었으면 남길 가치가 없다. */
    private static boolean isBareDraft(JSONObject entry) {
        return isDraft(entry) && photoOf(entry).isEmpty() && recordMemo(entry).isEmpty();
    }

    /** 표시용 구역 이름. 초안이면 "구역 미입력". */
    static String zoneOf(Context c, JSONObject entry) {
        String zone = entry == null ? "" : Json.clean(entry.optString("z", ""));
        return zone.isEmpty() ? c.getString(R.string.zone_unset) : zone;
    }

    static String photoOf(JSONObject entry) {
        return entry == null ? "" : Json.clean(entry.optString("ph", ""));
    }

    /** 차량의 가장 최근 기록 (주차장 무관). 없으면 null. */
    static JSONObject latestForVehicle(Context c, String vehicleId) {
        JSONArray all = history(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e != null && vehicleId.equals(e.optString("c"))) return e;
        }
        return null;
    }

    /**
     * 주차장×차량 하나를 가리키는 키.
     *
     * <p>구분자는 이스케이프로 적는다. 소스에 NUL 바이트를 그대로 넣으면 git이 이 파일을
     * 바이너리로 판정해서 줄바꿈 정규화와 diff가 통째로 망가진다.
     */
    private static String contextKey(String profileId, String vehicleId) {
        return Json.clean(profileId) + "\u0000" + Json.clean(vehicleId);
    }

    static JSONArray historyForContext(Context c, String profileId, String vehicleId) {
        JSONArray all = history(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e != null && profileId.equals(e.optString("p"))
                    && vehicleId.equals(e.optString("c"))) {
                out.put(e);
            }
        }
        return out;
    }

    static JSONArray activeHistory(Context c) {
        return historyForContext(c, activeProfileId(c), activeVehicleId(c));
    }

    static JSONObject latestRecord(Context c) {
        return activeHistory(c).optJSONObject(0);
    }

    static JSONObject recordById(Context c, String id) {
        return Json.byId(history(c), id);
    }

    /**
     * 앱 화면에서 지금 맥락으로 저장한다.
     *
     * <p>활성 맥락을 <b>명시 인자로 넘기지 않는다.</b> 넘기면 recordInContext가 이를
     * "위젯이 지정한 값"으로 보고, 그 id가 어쩌다 목록에 없을 때(백업 복원 직후 등)
     * 아무것도 저장하지 않고 null을 반환해 버린다. null을 넘겨 폴백 경로를 타게 하면
     * 활성 맥락이 어긋나 있어도 스스로 바로잡고 저장한다.
     */
    static String record(Context c, String zone) {
        return recordInContext(c, null, null, zone, "");
    }

    /** 위젯·블루투스 알림처럼 표시 당시의 차량/프로필을 명시해 저장할 때 사용한다. */
    static String recordInContext(Context c, String profileId, String vehicleId,
                                  String zone, String memo) {
        return recordInContextInternal(c, profileId, vehicleId, zone, memo, null, true, 0);
    }

    /**
     * 사건(블루투스 끊김) 시점의 좌표와 시각으로 저장한다.
     *
     * <p>snapshot이 null이면 "그때 좌표가 없었다"는 뜻이다. 나중 좌표로 대신하면 안 된다 —
     * 끊긴 뒤 걸어간 자리가 차 위치로 저장된다.
     *
     * @param eventTime 차에서 내린(블루투스가 끊긴) 시각. 주차 시각은 버튼을 누른 순간이 아니라
     *                  이 시각이다. 마트에 들어갔다가 40분 뒤 버튼을 누르면 좌표 스냅샷과
     *                  주차 시각이 30분 넘게 벌어져 위치 탭이 정확한 좌표를 낡은 것으로 본다.
     *                  0이면 지금 시각을 쓴다.
     */
    static String recordInContextUsingSnapshot(Context c, String profileId, String vehicleId,
                                               String zone, String memo, Location snapshot,
                                               long eventTime) {
        return recordInContextInternal(c, profileId, vehicleId, zone, memo, snapshot, false,
                eventTime);
    }

    private static String recordInContextInternal(Context c, String profileId, String vehicleId,
                                                  String zone, String memo, Location snapshot,
                                                  boolean useCurrentFix, long eventTime) {
        String z = Json.clean(zone);
        if (z.isEmpty()) return null;
        boolean explicitProfile = !Json.clean(profileId).isEmpty();
        boolean explicitVehicle = !Json.clean(vehicleId).isEmpty();
        JSONObject profile = profileById(c, profileId);
        if (profile == null) {
            if (explicitProfile) return null; // 삭제된 위젯/알림 액션은 다른 곳에 저장하지 않는다.
            // 폴백에서는 id도 실제 프로필에서 다시 읽는다. activeProfileId()를 그대로 쓰면
            // 어긋난 id가 그대로 기록에 박혀서 다음 저장까지 같은 문제가 이어진다.
            profile = activeProfile(c);
            profileId = profile == null ? activeProfileId(c)
                    : profile.optString("id", activeProfileId(c));
        }
        JSONObject vehicle = vehicleById(c, vehicleId);
        if (vehicle == null) {
            if (explicitVehicle) return null;
            vehicle = activeVehicle(c);
            vehicleId = vehicle == null ? activeVehicleId(c)
                    : vehicle.optString("id", activeVehicleId(c));
        }

        // 차에서 내린 뒤 아직 구역을 안 적은 초안이 있으면 새 기록을 만들지 않고 그 초안을
        // 채운다. 알림 버튼·위젯·앱 격자 어느 경로로 눌러도 내린 시각과 좌표가 그대로 남는다.
        // 예전에는 알림 본문을 탭해 앱을 열고 격자를 누르면 좌표가 지금 자리로, 시각이
        // 지금으로 바뀌었다 — 끊긴 순간의 사실은 알림 버튼 안에만 살아 있었다.
        JSONObject draft = latestForVehicle(c, vehicleId);
        if (isOpenDraft(draft)) {
            try {
                draft.put("p", profileId);
                draft.put("pn", profile == null
                        ? c.getString(R.string.profile_default_name)
                        : profile.optString("n", c.getString(R.string.profile_default_name)));
                draft.put("z", z);
                String m = Json.clean(memo);
                if (!m.isEmpty()) draft.put("m", m);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            commitRecord(c, history(c), profileId, vehicleId);
            return draft.optString("id");
        }
        return create(c, profile, profileId, vehicle, vehicleId, z, memo,
                useCurrentFix ? Nearby.lastFix(c) : snapshot, eventTime);
    }

    /**
     * 블루투스가 끊겼다 = 차에서 내렸다. 시각과 좌표만 담은 초안을 만든다.
     *
     * <p>재연결을 놓쳐 빈 초안이 남아 있으면 그 자리를 새 초안이 대신한다. 시각·좌표
     * 말고 아무것도 없는 기록을 둘 씩 쌓을 이유가 없다.
     *
     * @return 초안 id. 차량을 모르면 null.
     */
    static String startParking(Context c, String profileId, String vehicleId, Location fix,
                               long eventTime) {
        JSONObject vehicle = vehicleById(c, vehicleId);
        if (vehicle == null) return null;
        JSONObject profile = profileById(c, profileId);
        if (profile == null) {
            profile = activeProfile(c);
            profileId = profile == null ? activeProfileId(c) : profile.optString("id");
        }
        JSONObject previous = latestForVehicle(c, vehicleId);
        if (isOpenDraft(previous) && isBareDraft(previous)) {
            deleteRecord(c, previous.optString("id"));
        }
        return create(c, profile, profileId, vehicle, vehicleId, "", "", fix, eventTime);
    }

    /** 새 기록을 목록 앞에 넣고 상한에 맞춰 자른다. zone이 비면 초안이다. */
    private static String create(Context c, JSONObject profile, String profileId,
                                 JSONObject vehicle, String vehicleId, String zone, String memo,
                                 Location fix, long eventTime) {
        String id = UUID.randomUUID().toString();
        JSONArray old = history(c);
        JSONArray next;
        ArrayList<String> cancelTimers = new ArrayList<>();
        try {
            JSONObject entry = new JSONObject()
                    .put("id", id)
                    .put("c", vehicleId)
                    .put("cn", vehicle == null
                            ? c.getString(R.string.vehicle_default_name)
                            : vehicle.optString("n", c.getString(R.string.vehicle_default_name)))
                    .put("p", profileId)
                    .put("pn", profile == null
                            ? c.getString(R.string.profile_default_name)
                            : profile.optString("n", c.getString(R.string.profile_default_name)))
                    .put("t", eventTime > 0 ? eventTime : System.currentTimeMillis());
            if (!zone.isEmpty()) entry.put("z", zone);
            String m = Json.clean(memo);
            if (!m.isEmpty()) entry.put("m", m);

            // 차를 어디에 뒀는지 좌표로도 남긴다. '위치' 탭이 여기서 거리와 방향을 만든다.
            // 권한이 없거나 좌표가 낡았으면 그냥 안 붙고, 그 기록은 '위치' 탭에서 안내 문구로 바뀐다.
            putRecordLocation(entry, fix);

            next = trimHistory(entry, old, cancelTimers);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        for (String recordId : cancelTimers) ParkingTimers.cancel(c, recordId);
        // 잘려 나간 기록의 사진은 더 이상 아무도 가리키지 않는다.
        if (next.length() <= old.length()) Photos.prune(c, next);
        commitRecord(c, next, profileId, vehicleId);
        return id;
    }

    /** 기록을 저장하고, 위젯/알림 액션의 맥락을 현재 맥락으로도 전환해 저장 결과가 즉시 보이게 한다. */
    private static void commitRecord(Context c, JSONArray history, String profileId,
                                     String vehicleId) {
        prefs(c).edit()
                .putString(PREF_HISTORY, history.toString())
                .putString(PREF_ACTIVE_PROFILE, profileId)
                .putString(PREF_ACTIVE_VEHICLE, vehicleId)
                .apply();
        Notify.cancelPark(c, vehicleId);
        notifyParkingHistoryChanged(c);
    }

    /**
     * 블루투스가 다시 붙었다 = 차에 탔다. 그 차량의 진행 중이던 주차를 닫는다.
     *
     * <p>끊긴 지 {@link #FLAP_MS} 안에 붙었고 그 사이 아무것도 안 적었으면 잠깐의 신호
     * 끊김으로 보고 초안을 지운다. 아니면 출차 시각을 적고, 출차 알림은 이제 의미가
     * 없으니 함께 해제한다.
     */
    static void endParking(Context c, String vehicleId, long now) {
        JSONArray all = history(c);
        JSONObject touched;
        try {
            touched = closeParking(all, vehicleId, now);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        if (touched == null) return;
        String recordId = touched.optString("id");
        if (touched.optLong("due", 0) > 0) {
            ParkingTimers.cancel(c, recordId);
            touched.remove("due");
        }
        saveHistory(c, all);
        notifyParkingHistoryChanged(c);
    }

    /**
     * {@link #endParking}의 순수 부분. 손댄 기록을 돌려주고, 오탐으로 지운 기록은
     * {@code all}에서 빠져 있다. 손댄 게 없으면 null.
     */
    static JSONObject closeParking(JSONArray all, String vehicleId, long now)
            throws JSONException {
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e == null || !vehicleId.equals(e.optString("c"))) continue;
            if (hasEnded(e)) return null;
            if (isBareDraft(e) && now - e.optLong("t", 0) < FLAP_MS) {
                all.remove(i);
                return e;
            }
            e.put("e", now);
            return e;
        }
        return null;
    }

    /** 초안에 구역을 채운다. 잠금 화면 위 빠른 입력이 쓴다. */
    static boolean setZone(Context c, String recordId, String profileId, String zone) {
        String z = Json.clean(zone);
        if (z.isEmpty()) return false;
        JSONArray all = history(c);
        JSONObject entry = Json.byId(all, recordId);
        if (entry == null) return false;
        JSONObject profile = profileById(c, profileId);
        try {
            if (profile != null) {
                entry.put("p", profileId);
                entry.put("pn", profile.optString("n", c.getString(R.string.profile_default_name)));
            }
            entry.put("z", z);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveHistory(c, all);
        Notify.cancelPark(c, entry.optString("c"));
        notifyParkingHistoryChanged(c);
        return true;
    }

    /** 사진 파일 이름을 기록에 붙이거나(빈 값이면) 뗀다. 파일 자체는 Photos가 다룬다. */
    static void setPhoto(Context c, String recordId, String fileName) {
        JSONArray all = history(c);
        JSONObject entry = Json.byId(all, recordId);
        if (entry == null) return;
        String name = Json.clean(fileName);
        try {
            if (name.isEmpty()) entry.remove("ph");
            else entry.put("ph", name);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveHistory(c, all);
        notifyParkingHistoryChanged(c);
    }

    /** 음성으로 받은 문장을 메모 뒤에 붙인다. 상한을 넘는 부분은 버린다. */
    static void appendMemo(Context c, String recordId, String text) {
        String addition = Json.clean(text);
        if (addition.isEmpty()) return;
        JSONArray all = history(c);
        JSONObject entry = Json.byId(all, recordId);
        if (entry == null) return;
        String memo = recordMemo(entry);
        String next = memo.isEmpty() ? addition : memo + " · " + addition;
        if (next.length() > MAX_MEMO_LENGTH) next = next.substring(0, MAX_MEMO_LENGTH);
        try {
            entry.put("m", next);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveHistory(c, all);
        notifyParkingHistoryChanged(c);
    }

    /**
     * 끊긴 직후 단발 측위로 더 나은 좌표를 잡았을 때. 좌표가 없었거나, 새 좌표가 더 정확하거나,
     * 저장된 좌표가 새 좌표보다 1분 넘게 낡았으면 바꾼다.
     */
    static void improveLocation(Context c, String recordId, Location fix) {
        if (!Nearby.isValid(fix)) return;
        JSONArray all = history(c);
        JSONObject entry = Json.byId(all, recordId);
        if (entry == null) return;
        float stored = recordLocationAccuracy(entry);
        float fresh = Nearby.accuracyOf(fix);
        boolean better = !recordHasCoords(entry)
                || (fresh >= 0 && (stored < 0 || fresh <= stored))
                || fix.getTime() - recordLocationTime(entry) > 60_000L;
        if (!better) return;
        try {
            putRecordLocation(entry, fix);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveHistory(c, all);
    }

    /**
     * 새 기록을 앞에 두고 옛 기록을 상한에 맞춰 잘라낸 목록을 만든다. 결과는 시각 내림차순이다.
     *
     * <p>Context 없이 돌아가는 순수 함수다. 타이머 해제는 여기서 직접 하지 않고
     * {@code cancelTimers}에 기록 id를 쌓아 호출한 쪽이 처리하게 한다. 그래야 "맥락당 20개,
     * 전체 240개, 같은 차량의 이전 타이머는 해제"라는 규칙을 유닛 테스트로 못 박을 수 있다.
     *
     * <ul>
     *   <li>맥락(주차장×차량)별로 세면서 담는다. 전역 상한 하나로 자르면 차를 두 대 쓰는
     *       사람이 한쪽을 자주 댔다는 이유로 다른 쪽 기록을 잃는다.</li>
     *   <li>같은 차량의 이전 주차 타이머는 차량이 다시 움직였다는 뜻이므로 해제한다.</li>
     *   <li>잘려 나가는 기록의 타이머도 반드시 해제한다. 남겨 두면 이제 존재하지 않는
     *       기록을 가리키는 알람이 남는다.</li>
     * </ul>
     */
    static JSONArray trimHistory(JSONObject entry, JSONArray old, List<String> cancelTimers)
            throws JSONException {
        String profileId = entry.optString("p");
        String vehicleId = entry.optString("c");
        JSONArray next = new JSONArray();
        next.put(entry);

        HashMap<String, Integer> perContext = new HashMap<>();
        perContext.put(contextKey(profileId, vehicleId), 1); // 방금 넣은 기록

        for (int i = 0; i < old.length(); i++) {
            JSONObject previous = old.optJSONObject(i);
            if (previous == null) continue;

            if (vehicleId.equals(previous.optString("c"))
                    && previous.optLong("due", 0) > 0) {
                cancelTimers.add(previous.optString("id"));
                previous.remove("due");
            }

            String key = contextKey(previous.optString("p"), previous.optString("c"));
            Integer used = perContext.get(key);
            int count = used == null ? 0 : used;
            if (count < MAX_HISTORY_PER_CONTEXT && next.length() < MAX_HISTORY) {
                perContext.put(key, count + 1);
                next.put(previous);
            } else if (previous.optLong("due", 0) > 0) {
                cancelTimers.add(previous.optString("id"));
            }
        }
        // 새 기록의 시각이 끊김 시점(과거)일 수 있으므로 자리는 시각으로 정한다.
        // updateRecord도 같은 규칙으로 정렬하니, 목록은 언제나 시각 내림차순이다.
        return sortHistory(next);
    }

    /** 구역을 비우면 초안으로 돌아간다 — "어디였는지 모르겠다"도 정직한 상태다. */
    static boolean updateRecord(Context c, String recordId, String profileId, String vehicleId,
                                String zone, long parkedAt, String memo, long timerDue) {
        String z = Json.clean(zone);
        JSONArray all = history(c);
        JSONObject entry = Json.byId(all, recordId);
        if (entry == null) return false;
        JSONObject profile = profileById(c, profileId);
        JSONObject vehicle = vehicleById(c, vehicleId);
        boolean keepDeletedProfile = profile == null && profileId.equals(entry.optString("p"));
        boolean keepDeletedVehicle = vehicle == null && vehicleId.equals(entry.optString("c"));
        if (profile == null && !keepDeletedProfile) return false;
        if (vehicle == null && !keepDeletedVehicle) return false;
        long oldDue = entry.optLong("due", 0);
        long safeDue = timerDue > System.currentTimeMillis() ? timerDue : 0;
        try {
            if (profile != null) {
                entry.put("p", profileId);
                entry.put("pn", profile.optString("n",
                        c.getString(R.string.profile_default_name)));
            }
            if (vehicle != null) {
                entry.put("c", vehicleId);
                entry.put("cn", vehicle.optString("n",
                        c.getString(R.string.vehicle_default_name)));
            }
            if (z.isEmpty()) entry.remove("z");
            else entry.put("z", z);
            entry.put("t", parkedAt);
            String m = Json.clean(memo);
            if (m.isEmpty()) entry.remove("m");
            else entry.put("m", m);
            if (safeDue == 0) entry.remove("due");
            else entry.put("due", safeDue);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        saveHistory(c, sortHistory(all));
        if (oldDue != safeDue) ParkingTimers.cancel(c, recordId);
        if (safeDue > 0) ParkingTimers.schedule(c, recordId, safeDue);
        notifyParkingHistoryChanged(c);
        return true;
    }

    static void setParkingTimer(Context c, String recordId, long due) {
        JSONObject entry = recordById(c, recordId);
        if (entry == null) return;
        updateRecord(c, recordId, entry.optString("p"), entry.optString("c"),
                entry.optString("z"), entry.optLong("t"), entry.optString("m", ""), due);
    }

    static void clearParkingTimer(Context c, String recordId) {
        JSONArray all = history(c);
        JSONObject entry = Json.byId(all, recordId);
        if (entry == null) return;
        entry.remove("due");
        saveHistory(c, all);
        ParkingTimers.cancel(c, recordId);
        notifyParkingHistoryChanged(c);
    }

    static boolean deleteRecord(Context c, String recordId) {
        JSONArray all = history(c);
        JSONArray next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry != null && recordId.equals(entry.optString("id"))) {
                ParkingTimers.cancel(c, recordId);
                Photos.delete(c, photoOf(entry));
                found = true;
            } else if (entry != null) {
                next.put(entry);
            }
        }
        if (found) {
            saveHistory(c, next);
            notifyParkingHistoryChanged(c);
        }
        return found;
    }

    static void deleteLast(Context c) {
        JSONObject latest = latestRecord(c);
        if (latest != null) deleteRecord(c, latest.optString("id"));
    }

    /** 현재 맥락의 마지막 구역(초안이면 "구역 미입력"). 기록이 없으면 null. */
    static String latestZone(Context c) {
        JSONObject e = latestRecord(c);
        return e == null ? null : zoneOf(c, e);
    }

    static long latestTime(Context c) {
        JSONObject e = latestRecord(c);
        return e == null ? 0 : e.optLong("t", 0);
    }

    static String recordProfileName(Context c, JSONObject entry) {
        if (entry == null) return "";
        String snapshot = Json.clean(entry.optString("pn", ""));
        return snapshot.isEmpty() ? profileName(c, entry.optString("p")) : snapshot;
    }

    static String recordVehicleName(Context c, JSONObject entry) {
        if (entry == null) return "";
        String snapshot = Json.clean(entry.optString("cn", ""));
        return snapshot.isEmpty() ? vehicleName(c, entry.optString("c")) : snapshot;
    }

    static String recordMemo(JSONObject entry) {
        return entry == null ? "" : Json.clean(entry.optString("m", ""));
    }

    // ---------- 주차 좌표 ----------

    /**
     * 기록에 좌표가 붙어 있는가. 옛 기록에는 없다.
     *
     * <p>lat/lon은 v3.2부터 저장한다. 위치 권한이 없거나 마지막 좌표가 낡았으면
     * 새 기록에도 붙지 않으므로, 있는지 없는지를 항상 먼저 물어야 한다.
     */
    static boolean recordHasCoords(JSONObject entry) {
        return entry != null
                && Nearby.validCoordinates(entry.optDouble("lat", Double.NaN),
                        entry.optDouble("lon", Double.NaN));
    }

    static double recordLat(JSONObject entry) {
        return entry == null ? 0 : entry.optDouble("lat", 0);
    }

    static double recordLon(JSONObject entry) {
        return entry == null ? 0 : entry.optDouble("lon", 0);
    }

    /** 좌표가 잡힌 시각(ms). 옛 기록이거나 모르면 0. */
    static long recordLocationTime(JSONObject entry) {
        long time = entry == null ? 0 : entry.optLong("loc_t", 0);
        return time > 0 ? time : 0;
    }

    /** 좌표 오차 반경(m). 공급자가 알려 주지 않았으면 -1. */
    static float recordLocationAccuracy(JSONObject entry) {
        double accuracy = entry == null ? -1 : entry.optDouble("loc_acc", -1);
        if (Double.isNaN(accuracy) || Double.isInfinite(accuracy) || accuracy < 0) return -1f;
        return (float) accuracy;
    }

    private static void putRecordLocation(JSONObject entry, Location fix) throws JSONException {
        if (!Nearby.isValid(fix)) return;
        entry.put("lat", fix.getLatitude());
        entry.put("lon", fix.getLongitude());
        if (fix.getTime() > 0) entry.put("loc_t", fix.getTime());
        float accuracy = Nearby.accuracyOf(fix);
        if (accuracy >= 0) entry.put("loc_acc", accuracy);
    }

    // ---------- 차량 블루투스 연결 상태 ----------

    /**
     * BtReceiver가 본 마지막 연결/해제를 남긴다. 홈의 감지 상태 카드가 읽는다.
     *
     * <p>BluetoothAdapter에 "지금 이 기기가 붙어 있나"를 직접 묻는 API는 프로필별
     * 상태(A2DP/HEADSET)만 알려 주고 어느 기기인지는 알려 주지 않는다. 우리가 이미
     * 정확히 아는 시점(브로드캐스트)에 적어 두는 편이 정확하고 권한도 덜 든다.
     */
    static void setBtState(Context c, String vehicleId, boolean connected) {
        String id = Json.clean(vehicleId);
        if (id.isEmpty()) return;
        try {
            JSONObject states = readBtStates(c);
            states.put(id, new JSONObject()
                    .put("v", id)
                    .put("on", connected)
                    .put("t", System.currentTimeMillis()));
            writeBtStates(c, states);
        } catch (JSONException ignored) {
            // 상태 표시용 부가 정보다. 실패해도 기록·알림에는 영향이 없다.
        }
    }

    /** 현재 차량의 마지막 블루투스 이벤트. 한 번도 없었으면 null. */
    static JSONObject btState(Context c) {
        String id = Json.clean(activeVehicleId(c));
        return id.isEmpty() ? null : readBtStates(c).optJSONObject(id);
    }

    private static JSONObject readBtStates(Context c) {
        String raw = prefs(c).getString(PREF_BT_STATE, "");
        if (raw.isEmpty()) return new JSONObject();
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject byVehicle = root.optJSONObject("byVehicle");
            if (byVehicle != null) return byVehicle;

            // 차량별로 나누기 전의 단일 상태 값과의 호환.
            String legacyVehicleId = Json.clean(root.optString("v", ""));
            JSONObject migrated = new JSONObject();
            if (!legacyVehicleId.isEmpty()) migrated.put(legacyVehicleId, root);
            return migrated;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void writeBtStates(Context c, JSONObject states) throws JSONException {
        if (states == null || states.length() == 0) {
            prefs(c).edit().remove(PREF_BT_STATE).apply();
            return;
        }
        JSONObject root = new JSONObject().put("byVehicle", states);
        prefs(c).edit().putString(PREF_BT_STATE, root.toString()).apply();
    }

    static void clearBtState(Context c, String vehicleId) {
        String id = Json.clean(vehicleId);
        if (id.isEmpty()) return;
        JSONObject states = readBtStates(c);
        states.remove(id);
        try {
            writeBtStates(c, states);
        } catch (JSONException ignored) {
            prefs(c).edit().remove(PREF_BT_STATE).apply();
        }
    }

    static void clearBtStates(Context c) {
        prefs(c).edit().remove(PREF_BT_STATE).apply();
    }

    /**
     * 현재 차량이 연결돼 있는가 (= 아직 차 안이거나 이동 중이다).
     *
     * <p>다른 차량의 이벤트는 무시한다. 두 대를 쓰면 방금 내린 차의 상태를 묻는
     * 사람에게 다른 차의 연결 상태를 보여 주게 된다.
     */
    static boolean btConnected(Context c) {
        JSONObject state = btState(c);
        return state != null && state.optBoolean("on", false);
    }

    /** 해당 차량·주차장에서 자주 댄 순서의 구역. 블루투스 알림 버튼용. */
    static String[] topZones(Context c, String profileId, String vehicleId, int max) {
        return rankZones(historyForContext(c, profileId, vehicleId),
                mainZonesForProfile(c, profileId), max, null);
    }

    /**
     * 홈 위젯에 올릴 구역. 격자가 버튼 수보다 크면 자주 댄 구역을 골라 담되,
     * <b>배치는 격자 순서를 그대로 지킨다</b>. 최근순으로 늘어놓으면 주차할 때마다
     * 버튼이 자리를 바꿔서 손에 익지 않는다.
     */
    static String[] widgetZones(Context c, String profileId, String vehicleId, int max) {
        String[] grid = mainZonesForProfile(c, profileId);
        if (grid.length <= max) return grid;

        // 직접 입력한 일회성 위치(예: 롯데몰)는 위젯 자리를 차지하지 않게 격자 안으로 거른다.
        HashSet<String> inGrid = new HashSet<>(Arrays.asList(grid));
        HashSet<String> keep = new HashSet<>(Arrays.asList(
                rankZones(historyForContext(c, profileId, vehicleId), grid, max, inGrid)));
        ArrayList<String> out = new ArrayList<>();
        for (String zone : grid) {
            if (keep.contains(zone)) out.add(zone);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 자주 댄 순서로 구역을 고른다. 같은 횟수면 최근 것이 먼저다.
     *
     * <p>알림 버튼은 두세 개뿐이라 최근순보다 빈도순이 맞는다 — 어제 한 번 댄 손님 주차장
     * 자리보다 지난 스무 번 중 열네 번 댄 B2-A가 앞에 와야 한다. 모자라면 {@code fill}
     * (격자 순서)로 채운다.
     *
     * @param allowed null이 아니면 이 안의 구역만 센다.
     */
    static String[] rankZones(JSONArray history, String[] fill, int max, Set<String> allowed) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>(); // 삽입 순서 = 최근순
        for (int i = 0; i < history.length(); i++) {
            JSONObject e = history.optJSONObject(i);
            String zone = e == null ? "" : Json.clean(e.optString("z", ""));
            if (zone.isEmpty() || (allowed != null && !allowed.contains(zone))) continue;
            Integer n = counts.get(zone);
            counts.put(zone, n == null ? 1 : n + 1);
        }
        ArrayList<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        Collections.sort(ranked, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue(); // 안정 정렬이라 같은 횟수는 최근순 유지
            }
        });
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ranked) {
            if (out.size() >= max) break;
            out.add(entry.getKey());
        }
        for (String zone : fill) {
            if (out.size() >= max) break;
            if (!out.contains(zone)) out.add(zone);
        }
        return out.toArray(new String[0]);
    }

    private static JSONArray sortHistory(JSONArray source) {
        ArrayList<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject entry = source.optJSONObject(i);
            if (entry != null) entries.add(entry);
        }
        Collections.sort(entries, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("t", 0), a.optLong("t", 0));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject entry : entries) out.put(entry);
        return out;
    }

    private static void saveHistory(Context c, JSONArray history) {
        prefs(c).edit().putString(PREF_HISTORY, history.toString()).apply();
    }

    static void notifyParkingHistoryChanged(Context c) {
        ParkWidgetProvider.updateAll(c);
        TileService.requestListeningState(c, new ComponentName(c, ParkTileService.class));
    }

    /** 프로필/차량 전환처럼 기록 자체가 안 바뀌는 경우에도 위젯·타일 맥락을 즉시 갱신한다. */
    static void notifyParkingContextChanged(Context c) {
        notifyParkingHistoryChanged(c);
    }
}
