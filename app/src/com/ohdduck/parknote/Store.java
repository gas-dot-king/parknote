package com.ohdduck.parknote;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;

/**
 * 기록 저장/조회 공용 로직. 앱 화면, 위젯, 알림, 타일이 모두 이 클래스를 쓴다.
 * 기존 단일 주차장·차량 데이터는 최초 접근 시 프로필/차량/기록 ID 구조로 안전하게 옮긴다.
 */
class Store {

    static final String LEGACY_PROFILE_ID = "legacy-default-profile";
    static final String LEGACY_VEHICLE_ID = "legacy-default-vehicle";

    private static final int SCHEMA_VERSION = 5;
    static final int MAX_PROFILES = 6;
    static final int MAX_VEHICLES = 3;
    /** 격자 한 변의 상한. 칸이 너무 많으면 한 화면에서 고르는 이점이 사라진다. */
    static final int MAX_ROWS = 8;
    static final int MAX_COLS = 8;
    /** 층 없이 쓰는 목록형은 격자가 아니므로 예전 상한(30개)을 그대로 허용한다. */
    static final int MAX_FLAT_ZONES = 30;
    static final String DEFAULT_SEP = "-";

    // 층(rows) × 구역(cols) 격자. rows가 비면 cols가 그대로 버튼이 되는 1차원 목록이다.
    private static final String[] DEFAULT_ROWS = {"B1", "B2", "B3"};
    private static final String[] DEFAULT_COLS = {"A", "B"};
    /**
     * v2.2에서 쓰던 기타 구역 기본값. <b>이제는 마이그레이션 폴백으로만 쓴다.</b>
     *
     * <p>새로 설치한 사람에게 이걸 심으면 지하 2층짜리 주차장을 설정한 사람도 있지도 않은
     * B4·B5 버튼을 보게 된다. 더 나쁜 건 지하 5층 × 구역 2개를 고른 경우다. 격자가 만드는
     * 이름과 그대로 겹쳐서, 이후 구역 편집이 "중복된 구역이 있어요"로 영영 저장되지 않았다.
     */
    private static final String[] LEGACY_ETC_ZONES =
            {"B4-A", "B4-B", "B5-A", "B5-B"};
    /** 새 주차장의 기타 구역은 비어 있다. 필요하면 사용자가 직접 채운다. */
    private static final String[] NO_ZONES = {};
    /** 평면 목록을 격자로 역추론할 때 앞에서부터 시도하는 구분자 */
    private static final String[] SEP_CANDIDATES = {"-", "_", " "};

    // v2.2 이하에서 쓰던 키. 마이그레이션 뒤에도 지우지 않아 복구 여지를 남긴다.
    private static final String PREF_MAIN_ZONES = "main_zones";
    private static final String PREF_ETC_ZONES = "etc_zones";
    private static final String PREF_CAR_BT = "car_bt";
    private static final String PREF_ONBOARDED = "onboarded";
    private static final String PREF_LOCATION_FILTER = "location_filter";

    /** 프로필 좌표의 기본 반경(m). 지하 진입 직전 좌표를 쓰므로 넉넉하게 잡는다. */
    static final int DEFAULT_RADIUS_M = 300;

    private static final String PREF_SCHEMA = "parking_schema";
    private static final String PREF_PROFILES = "parking_profiles_v1";
    private static final String PREF_ACTIVE_PROFILE = "active_parking_profile_id";
    private static final String PREF_VEHICLES = "parking_vehicles_v1";
    private static final String PREF_ACTIVE_VEHICLE = "active_parking_vehicle_id";
    private static final String PREF_HISTORY = "history";
    private static final String PREF_HABITS = "habits";

    static final String CHANNEL = "park_reminder";
    /** 등록한 주차장이 아닐 때 쓰는 무음 채널. 알림함에는 남지만 방해하지 않는다. */
    static final String CHANNEL_QUIET = "park_reminder_quiet";
    static final String CHANNEL_HABIT = "habit_reminder";
    static final String CHANNEL_TIMER = "parking_timer";
    static final int NOTIF_ID_BT = 1;
    static final int NOTIF_ID_HABIT = 2;
    static final int NOTIF_ID_TIMER = 3;

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
    private static final int MAX_HABIT_DAYS = 400;

    // java.time은 불변이라 스레드 세이프하다. SimpleDateFormat은 아니었고, static으로
    // 공유하던 탓에 백업 I/O를 워커 스레드로 옮기는 순간 조용히 깨질 코드였다.
    private static final DateTimeFormatter FMT_FULL =
            DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREAN);
    private static final DateTimeFormatter FMT_SHORT =
            DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("parknote", Context.MODE_PRIVATE);
    }

    // ---------- 데이터 마이그레이션 ----------

    /**
     * v2.2의 main_zones / etc_zones / car_bt / {z,t} 기록을 한 번에 새 구조로 옮긴다.
     * 수신기나 위젯이 앱보다 먼저 실행될 수 있으므로 모든 공개 접근자에서 호출한다.
     */
    private static void ensureSchema(Context c) {
        if (prefs(c).getInt(PREF_SCHEMA, 0) >= SCHEMA_VERSION) return;
        synchronized (Store.class) {
            SharedPreferences p = prefs(c);
            if (p.getInt(PREF_SCHEMA, 0) >= SCHEMA_VERSION) return;

            // 이미 쓰고 있던 기기는 온보딩을 다시 보여 주지 않는다. 저장된 흔적이
            // 하나라도 있으면 업데이트 설치로 본다.
            boolean freshInstall = p.getInt(PREF_SCHEMA, 0) == 0
                    && p.getString(PREF_PROFILES, null) == null
                    && p.getString(PREF_MAIN_ZONES, null) == null
                    && p.getString(PREF_HISTORY, null) == null;

            try {
                JSONArray profileList = safeArray(p.getString(PREF_PROFILES, null));
                if (profileList == null || profileList.length() == 0) {
                    profileList = new JSONArray();
                    // 새로 설치했으면 기타 구역을 비워 둔다. 업데이트 설치라면 예전 값을
                    // 그대로 읽고, 그것도 없으면 v2.2 기본값으로 되살린다.
                    JSONObject fresh = newProfile(LEGACY_PROFILE_ID,
                            c.getString(R.string.onboarding_default_profile),
                            DEFAULT_ROWS, DEFAULT_COLS, DEFAULT_SEP,
                            freshInstall
                                    ? NO_ZONES
                                    : readLegacyZones(p, PREF_ETC_ZONES, LEGACY_ETC_ZONES, true));
                    // v2.2의 평면 구역 목록이 남아 있으면 그걸 격자로 되돌린다.
                    String[] legacyMain = readLegacyZones(p, PREF_MAIN_ZONES, null, false);
                    if (legacyMain != null && legacyMain.length > 0) applyFlatZones(fresh, legacyMain);
                    profileList.put(fresh);
                }
                // v4 이하 프로필의 평면 main 목록을 격자로 역추론한다.
                for (int i = 0; i < profileList.length(); i++) {
                    JSONObject profile = profileList.optJSONObject(i);
                    if (profile != null && !profile.has("cols")) {
                        String[] flat = jsonToArray(profile.optJSONArray("main"));
                        applyFlatZones(profile,
                                flat.length > 0 ? flat : flatten(DEFAULT_ROWS, DEFAULT_COLS, DEFAULT_SEP));
                    }
                }
                JSONObject firstProfile = profileList.optJSONObject(0);
                String firstProfileId = firstProfile == null
                        ? LEGACY_PROFILE_ID : firstProfile.optString("id", LEGACY_PROFILE_ID);
                String activeProfile = p.getString(PREF_ACTIVE_PROFILE, "");
                if (findById(profileList, activeProfile) == null) activeProfile = firstProfileId;

                JSONArray vehicleList = safeArray(p.getString(PREF_VEHICLES, null));
                if (vehicleList == null || vehicleList.length() == 0) {
                    // 새로 설치한 경우 블루투스 이름을 비워 둔다. 온보딩에서 실제 페어링된
                    // 기기를 고르게 하므로, 남의 차 이름을 기본값으로 심어 두지 않는다.
                    String legacyBt = clean(p.getString(PREF_CAR_BT, ""));
                    vehicleList = new JSONArray();
                    vehicleList.put(newVehicle(LEGACY_VEHICLE_ID,
                            legacyBt.isEmpty()
                                    ? c.getString(R.string.onboarding_default_vehicle)
                                    : legacyBt,
                            legacyBt));
                }
                JSONObject firstVehicle = vehicleList.optJSONObject(0);
                String firstVehicleId = firstVehicle == null
                        ? LEGACY_VEHICLE_ID : firstVehicle.optString("id", LEGACY_VEHICLE_ID);
                String activeVehicle = p.getString(PREF_ACTIVE_VEHICLE, "");
                if (findById(vehicleList, activeVehicle) == null) activeVehicle = firstVehicleId;

                JSONArray oldHistory = safeArray(p.getString(PREF_HISTORY, "[]"));
                if (oldHistory == null) oldHistory = new JSONArray();
                JSONArray normalized = new JSONArray();
                for (int i = 0; i < oldHistory.length(); i++) {
                    JSONObject old = oldHistory.optJSONObject(i);
                    if (old == null) continue;
                    JSONObject entry = copyObject(old);
                    if (clean(entry.optString("id", "")).isEmpty()) {
                        entry.put("id", UUID.randomUUID().toString());
                    }
                    String profileId = clean(entry.optString("p", ""));
                    if (findById(profileList, profileId) == null) profileId = LEGACY_PROFILE_ID;
                    JSONObject profile = findById(profileList, profileId);
                    if (profile == null) profile = firstProfile;
                    entry.put("p", profile == null ? firstProfileId : profile.optString("id"));
                    if (clean(entry.optString("pn", "")).isEmpty() && profile != null) {
                        entry.put("pn", profile.optString("n",
                                c.getString(R.string.onboarding_default_profile)));
                    }
                    String vehicleId = clean(entry.optString("c", ""));
                    if (findById(vehicleList, vehicleId) == null) vehicleId = LEGACY_VEHICLE_ID;
                    JSONObject vehicle = findById(vehicleList, vehicleId);
                    if (vehicle == null) vehicle = firstVehicle;
                    entry.put("c", vehicle == null ? firstVehicleId : vehicle.optString("id"));
                    if (clean(entry.optString("cn", "")).isEmpty() && vehicle != null) {
                        entry.put("cn", vehicle.optString("n",
                                c.getString(R.string.onboarding_default_vehicle)));
                    }
                    normalized.put(entry);
                }

                p.edit()
                        .putString(PREF_PROFILES, profileList.toString())
                        .putString(PREF_ACTIVE_PROFILE, activeProfile)
                        .putString(PREF_VEHICLES, vehicleList.toString())
                        .putString(PREF_ACTIVE_VEHICLE, activeVehicle)
                        .putString(PREF_HISTORY, normalized.toString())
                        .putBoolean(PREF_ONBOARDED, !freshInstall)
                        .putInt(PREF_SCHEMA, SCHEMA_VERSION)
                        .apply();
                NotificationManager nm =
                        (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIF_ID_BT); // v2.2의 태그 없는 주차 알림 정리
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static JSONArray safeArray(String raw) {
        if (raw == null) return null;
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static JSONObject copyObject(JSONObject source) throws JSONException {
        return new JSONObject(source.toString());
    }

    /** defaults가 null이면 저장된 값이 없을 때 null을 돌려준다. */
    private static String[] readLegacyZones(SharedPreferences p, String key,
                                            String[] defaults, boolean emptyAllowed) {
        JSONArray a = safeArray(p.getString(key, null));
        if (a == null) return defaults == null ? null : defaults.clone();
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String zone = clean(a.optString(i, ""));
            if (!zone.isEmpty()) out.add(zone);
        }
        if (!out.isEmpty() || emptyAllowed) return out.toArray(new String[0]);
        return defaults == null ? null : defaults.clone();
    }

    private static JSONObject newProfile(String id, String name, String[] rows, String[] cols,
                                         String sep, String[] etc) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("n", name);
        out.put("rows", zonesJson(rows));
        out.put("cols", zonesJson(cols));
        out.put("sep", sep);
        out.put("etc", zonesJson(etc));
        return out;
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

    /**
     * 평면 구역 목록이 "행 + 구분자 + 열"의 완전한 곱집합이고 행 우선 순서면 격자로 되돌린다.
     * 하나라도 어긋나면 null을 반환해 사용자가 쓰던 목록을 그대로 1차원으로 보존하게 한다.
     */
    static String[][] inferGrid(String[] zones, String sep) {
        if (zones.length < 4) return null; // 2×2 미만은 굳이 격자로 볼 이득이 없다
        ArrayList<String> rows = new ArrayList<>();
        ArrayList<String> cols = new ArrayList<>();
        for (String zone : zones) {
            int at = zone.indexOf(sep);
            if (at <= 0 || at + sep.length() >= zone.length()) return null;
            String row = zone.substring(0, at);
            String col = zone.substring(at + sep.length());
            if (col.contains(sep)) return null; // 구분자가 두 번 이상
            if (!rows.contains(row)) rows.add(row);
            if (!cols.contains(col)) cols.add(col);
        }
        if (rows.size() * cols.size() != zones.length) return null;
        int i = 0;
        for (String row : rows) {
            for (String col : cols) {
                if (!zones[i++].equals(row + sep + col)) return null;
            }
        }
        return new String[][]{rows.toArray(new String[0]), cols.toArray(new String[0])};
    }

    /** 평면 목록을 프로필의 격자 필드로 옮긴다. 격자로 안 떨어지면 행 없는 1차원으로 둔다. */
    static void applyFlatZones(JSONObject profile, String[] zones) throws JSONException {
        for (String sep : SEP_CANDIDATES) {
            String[][] grid = inferGrid(zones, sep);
            if (grid == null) continue;
            profile.put("rows", zonesJson(grid[0]));
            profile.put("cols", zonesJson(grid[1]));
            profile.put("sep", sep);
            return;
        }
        profile.put("rows", new JSONArray());
        profile.put("cols", zonesJson(zones));
        profile.put("sep", DEFAULT_SEP);
    }

    private static String[] jsonToArray(JSONArray a) {
        if (a == null) return new String[0];
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String value = clean(a.optString(i, ""));
            if (!value.isEmpty()) out.add(value);
        }
        return out.toArray(new String[0]);
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

    private static JSONObject newVehicle(String id, String name, String bt)
            throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("n", name);
        out.put("b", bt);
        return out;
    }

    private static JSONArray zonesJson(String[] zones) {
        JSONArray out = new JSONArray();
        if (zones != null) {
            for (String zone : zones) out.put(zone);
        }
        return out;
    }

    /** 기타 구역처럼 격자 밖에 있는 평면 목록을 읽는다. */
    private static String[] zonesOf(JSONObject profile, String key,
                                    String[] defaults, boolean emptyAllowed) {
        if (profile == null) return defaults.clone();
        JSONArray a = profile.optJSONArray(key);
        if (a == null) return defaults.clone();
        String[] out = jsonToArray(a);
        if (out.length > 0 || emptyAllowed) return out;
        return defaults.clone();
    }

    /** 프로필의 격자를 편 구역 이름 배열. 위젯·알림·화면이 모두 이 결과를 쓴다. */
    private static String[] gridZones(JSONObject profile) {
        if (profile == null) return flatten(DEFAULT_ROWS, DEFAULT_COLS, DEFAULT_SEP);
        String[] cols = jsonToArray(profile.optJSONArray("cols"));
        if (cols.length == 0) return flatten(DEFAULT_ROWS, DEFAULT_COLS, DEFAULT_SEP);
        return flatten(jsonToArray(profile.optJSONArray("rows")), cols,
                profile.optString("sep", DEFAULT_SEP));
    }

    private static JSONObject findById(JSONArray items, String id) {
        if (items == null || id == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // ---------- 주차장 프로필 ----------

    static JSONArray profiles(Context c) {
        ensureSchema(c);
        JSONArray out = safeArray(prefs(c).getString(PREF_PROFILES, "[]"));
        return out == null ? new JSONArray() : out;
    }

    static int profileCount(Context c) {
        return profiles(c).length();
    }

    static String activeProfileId(Context c) {
        ensureSchema(c);
        return prefs(c).getString(PREF_ACTIVE_PROFILE, LEGACY_PROFILE_ID);
    }

    static JSONObject activeProfile(Context c) {
        JSONArray ps = profiles(c);
        JSONObject profile = findById(ps, activeProfileId(c));
        return profile == null ? ps.optJSONObject(0) : profile;
    }

    static JSONObject profileById(Context c, String id) {
        return findById(profiles(c), id);
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
        ensureSchema(c);
        String n = clean(name);
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
                    current == null ? DEFAULT_ROWS : jsonToArray(current.optJSONArray("rows")),
                    current == null ? DEFAULT_COLS : jsonToArray(current.optJSONArray("cols")),
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
        ensureSchema(c);
        String n = clean(name);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_profile_name_required));
        JSONArray ps = profiles(c);
        JSONObject profile = findById(ps, id);
        if (profile == null) throw new IllegalArgumentException(c.getString(R.string.err_profile_not_found));
        if (nameTaken(ps, n, id)) throw new IllegalArgumentException(c.getString(R.string.err_profile_name_taken));
        try {
            profile.put("n", n);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit().putString(PREF_PROFILES, ps.toString()).apply();
        notifyParkingContextChanged(c);
    }

    static boolean deleteProfile(Context c, String id) {
        ensureSchema(c);
        JSONArray ps = profiles(c);
        if (ps.length() <= 1 || findById(ps, id) == null) return false;
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

    static String[] mainZones(Context c) {
        return gridZones(activeProfile(c));
    }

    static String[] etcZones(Context c) {
        return zonesOf(activeProfile(c), "etc", NO_ZONES, true);
    }

    static String[] mainZonesForProfile(Context c, String profileId) {
        return gridZones(profileById(c, profileId));
    }

    /** 현재 주차장의 층 목록. 비어 있으면 1차원(층 없이 구역 버튼만) 구성이다. */
    static String[] activeRows(Context c) {
        JSONObject profile = activeProfile(c);
        return profile == null ? DEFAULT_ROWS.clone() : jsonToArray(profile.optJSONArray("rows"));
    }

    static String[] activeCols(Context c) {
        JSONObject profile = activeProfile(c);
        if (profile == null) return DEFAULT_COLS.clone();
        String[] cols = jsonToArray(profile.optJSONArray("cols"));
        return cols.length == 0 ? DEFAULT_COLS.clone() : cols;
    }

    static String activeSep(Context c) {
        JSONObject profile = activeProfile(c);
        return profile == null ? DEFAULT_SEP : profile.optString("sep", DEFAULT_SEP);
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
        // 격자가 만들어 내는 이름과 기타 구역이 겹치면 같은 이름의 버튼이 두 개가 되고,
        // 기록이 어느 쪽인지 모호해진다. 화면 검증에만 두면 온보딩처럼 다른 경로로
        // 들어온 값이 그대로 저장돼 버리므로 여기서 막는다.
        String duplicate = firstDuplicate(flatten(rows, cols, sep), etc);
        if (duplicate != null) {
            throw new IllegalArgumentException(
                    c.getString(R.string.err_zone_grid_overlap, duplicate));
        }
        JSONArray ps = profiles(c);
        JSONObject profile = findById(ps, activeProfileId(c));
        if (profile == null) return;
        try {
            profile.put("rows", zonesJson(rows == null ? new String[0] : rows));
            profile.put("cols", zonesJson(cols));
            profile.put("sep", clean(sep).isEmpty() ? DEFAULT_SEP : sep);
            profile.put("etc", zonesJson(etc == null ? new String[0] : etc));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit().putString(PREF_PROFILES, ps.toString()).apply();
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
                String key = clean(zone).toLowerCase(Locale.ROOT);
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
        ensureSchema(c);
        return prefs(c).getBoolean(PREF_LOCATION_FILTER, false);
    }

    static void setLocationFilter(Context c, boolean on) {
        ensureSchema(c);
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
        JSONObject profile = findById(ps, profileId);
        if (profile == null) return;
        try {
            profile.put("lat", lat);
            profile.put("lon", lon);
            profile.put("rad", radiusM > 0 ? radiusM : DEFAULT_RADIUS_M);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit().putString(PREF_PROFILES, ps.toString()).apply();
    }

    static void clearProfileCoords(Context c, String profileId) {
        JSONArray ps = profiles(c);
        JSONObject profile = findById(ps, profileId);
        if (profile == null) return;
        profile.remove("lat");
        profile.remove("lon");
        profile.remove("rad");
        prefs(c).edit().putString(PREF_PROFILES, ps.toString()).apply();
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
            double distance = metersBetween(lat, lon, profile.optDouble("lat"), profile.optDouble("lon"));
            if (distance <= profileRadius(profile) && distance < bestDistance) {
                bestDistance = distance;
                best = profile;
            }
        }
        return best;
    }

    /** 하버사인 거리(m). 수 km 범위에서 반경 판정에 충분하다. */
    static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ---------- 백업 ----------

    /**
     * 저장된 값을 키 그대로 담아 낸다. 가공하지 않으므로 예전 버전에서 만든 백업을
     * 되돌려도 복원 뒤 평소의 마이그레이션 경로를 그대로 다시 탄다.
     */
    static JSONObject exportData(Context c) {
        ensureSchema(c);
        SharedPreferences p = prefs(c);
        JSONObject out = new JSONObject();
        try {
            out.put(PREF_SCHEMA, p.getInt(PREF_SCHEMA, SCHEMA_VERSION));
            out.put(PREF_PROFILES, p.getString(PREF_PROFILES, "[]"));
            out.put(PREF_ACTIVE_PROFILE, p.getString(PREF_ACTIVE_PROFILE, ""));
            out.put(PREF_VEHICLES, p.getString(PREF_VEHICLES, "[]"));
            out.put(PREF_ACTIVE_VEHICLE, p.getString(PREF_ACTIVE_VEHICLE, ""));
            out.put(PREF_HISTORY, p.getString(PREF_HISTORY, "[]"));
            out.put(PREF_HABITS, p.getString(PREF_HABITS, "[]"));
            out.put(PREF_ONBOARDED, p.getBoolean(PREF_ONBOARDED, true));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * 백업으로 통째로 교체한다. 지금 걸려 있는 알람은 먼저 거둔다 —
     * 교체 뒤에는 어떤 기록에 붙어 있던 알람인지 알 수 없기 때문.
     */
    static void importData(Context c, JSONObject data) {
        ensureSchema(c);
        cancelAllScheduled(c);

        prefs(c).edit()
                .putInt(PREF_SCHEMA, data.optInt(PREF_SCHEMA, SCHEMA_VERSION))
                .putString(PREF_PROFILES, data.optString(PREF_PROFILES, "[]"))
                .putString(PREF_ACTIVE_PROFILE, data.optString(PREF_ACTIVE_PROFILE, ""))
                .putString(PREF_VEHICLES, data.optString(PREF_VEHICLES, "[]"))
                .putString(PREF_ACTIVE_VEHICLE, data.optString(PREF_ACTIVE_VEHICLE, ""))
                .putString(PREF_HISTORY, data.optString(PREF_HISTORY, "[]"))
                .putString(PREF_HABITS, data.optString(PREF_HABITS, "[]"))
                .putBoolean(PREF_ONBOARDED, data.optBoolean(PREF_ONBOARDED, true))
                .apply();

        ensureSchema(c); // 예전 스키마의 백업이면 여기서 최신 구조로 올라온다
        repairActiveIds(c); // 같은 스키마 백업이면 ensureSchema가 조기 리턴하므로 따로 본다
        ParkingTimers.scheduleAll(c);
        Reminders.scheduleAll(c);
        notifyParkingHistoryChanged(c);
    }

    /**
     * 활성 주차장·차량 id가 실제 목록을 가리키는지 확인하고, 아니면 첫 항목으로 되돌린다.
     *
     * <p>복원은 활성 id까지 백업 값으로 덮어쓰는데, 스키마 버전이 같으면 ensureSchema가
     * 조기 리턴해서 아무도 검증하지 않는다. 어긋난 채로 두면 화면은 activeProfile()의
     * 폴백 덕에 멀쩡해 보이지만, 저장은 조용히 실패한다.
     */
    private static void repairActiveIds(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor edit = null;

        JSONArray ps = profiles(c);
        if (findById(ps, p.getString(PREF_ACTIVE_PROFILE, "")) == null) {
            JSONObject first = ps.optJSONObject(0);
            if (first != null) {
                edit = p.edit();
                edit.putString(PREF_ACTIVE_PROFILE, first.optString("id", LEGACY_PROFILE_ID));
            }
        }
        JSONArray vs = vehicles(c);
        if (findById(vs, p.getString(PREF_ACTIVE_VEHICLE, "")) == null) {
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
        JSONArray hs = habits(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject habit = hs.optJSONObject(i);
            if (habit != null) Reminders.cancel(c, habit.optString("n"));
        }
        JSONArray vs = vehicles(c);
        for (int i = 0; i < vs.length(); i++) {
            JSONObject vehicle = vs.optJSONObject(i);
            if (vehicle != null) cancelBtPrompt(c, vehicle.optString("id"));
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
                length(safeArray(data.optString(PREF_PROFILES, "[]"))),
                length(safeArray(data.optString(PREF_VEHICLES, "[]"))),
                length(safeArray(data.optString(PREF_HISTORY, "[]"))),
                length(safeArray(data.optString(PREF_HABITS, "[]")))};
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
        JSONArray profiles = safeArray(data.optString(PREF_PROFILES, ""));
        if (profiles == null || profiles.length() == 0) {
            return R.string.backup_err_no_profiles;
        }
        JSONArray vehicles = safeArray(data.optString(PREF_VEHICLES, ""));
        if (vehicles == null || vehicles.length() == 0) {
            return R.string.backup_err_no_vehicles;
        }
        if (safeArray(data.optString(PREF_HISTORY, "[]")) == null) {
            return R.string.backup_err_bad_history;
        }
        if (safeArray(data.optString(PREF_HABITS, "[]")) == null) {
            return R.string.backup_err_bad_habits;
        }
        return null;
    }

    // ---------- 첫 실행 온보딩 ----------

    static boolean isOnboarded(Context c) {
        ensureSchema(c);
        return prefs(c).getBoolean(PREF_ONBOARDED, false);
    }

    /** 온보딩에서 만든 첫 주차장·차량으로 기본값을 덮어쓴다. */
    static void completeOnboarding(Context c, String profileName, String[] rows, String[] cols,
                                   String sep, String vehicleName, String btName) {
        ensureSchema(c);
        String name = clean(profileName);
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
            String vn = clean(vehicleName);
            updateVehicle(c, vehicle.optString("id"),
                    vn.isEmpty()
                            ? vehicle.optString("n",
                                    c.getString(R.string.onboarding_default_vehicle))
                            : vn,
                    clean(btName));
        }
        prefs(c).edit().putBoolean(PREF_ONBOARDED, true).apply();
    }

    /** 온보딩을 건너뛰어도 다시 묻지 않는다. */
    static void skipOnboarding(Context c) {
        ensureSchema(c);
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

    static JSONArray vehicles(Context c) {
        ensureSchema(c);
        JSONArray out = safeArray(prefs(c).getString(PREF_VEHICLES, "[]"));
        return out == null ? new JSONArray() : out;
    }

    static int vehicleCount(Context c) {
        return vehicles(c).length();
    }

    static String activeVehicleId(Context c) {
        ensureSchema(c);
        return prefs(c).getString(PREF_ACTIVE_VEHICLE, LEGACY_VEHICLE_ID);
    }

    static JSONObject activeVehicle(Context c) {
        JSONArray vs = vehicles(c);
        JSONObject vehicle = findById(vs, activeVehicleId(c));
        return vehicle == null ? vs.optJSONObject(0) : vehicle;
    }

    static JSONObject vehicleById(Context c, String id) {
        return findById(vehicles(c), id);
    }

    static String vehicleName(Context c, String id) {
        JSONObject vehicle = vehicleById(c, id);
        return vehicle == null
                ? c.getString(R.string.vehicle_deleted)
                : vehicle.optString("n", c.getString(R.string.vehicle_default_name));
    }

    static String vehicleBtName(Context c, String id) {
        JSONObject vehicle = vehicleById(c, id);
        return vehicle == null ? "" : vehicle.optString("b", "");
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

    static String addVehicle(Context c, String name, String btName) {
        ensureSchema(c);
        String n = clean(name);
        String b = clean(btName);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_required));
        JSONArray vs = vehicles(c);
        if (vs.length() >= MAX_VEHICLES) {
            throw new IllegalArgumentException(
                    c.getString(R.string.err_vehicle_limit, MAX_VEHICLES));
        }
        if (nameTaken(vs, n, null)) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_taken));
        if (!b.isEmpty() && bluetoothNameTaken(vs, b, null)) {
            throw new IllegalArgumentException(c.getString(R.string.err_bt_name_taken));
        }
        String id = UUID.randomUUID().toString();
        try {
            vs.put(newVehicle(id, n, b));
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

    static void updateVehicle(Context c, String id, String name, String btName) {
        ensureSchema(c);
        String n = clean(name);
        String b = clean(btName);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_required));
        JSONArray vs = vehicles(c);
        JSONObject vehicle = findById(vs, id);
        if (vehicle == null) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_not_found));
        if (nameTaken(vs, n, id)) throw new IllegalArgumentException(c.getString(R.string.err_vehicle_name_taken));
        if (!b.isEmpty() && bluetoothNameTaken(vs, b, id)) {
            throw new IllegalArgumentException(c.getString(R.string.err_bt_name_taken));
        }
        try {
            vehicle.put("n", n);
            vehicle.put("b", b);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit().putString(PREF_VEHICLES, vs.toString()).apply();
        cancelBtPrompt(c, id);
        notifyParkingContextChanged(c);
    }

    static boolean deleteVehicle(Context c, String id) {
        ensureSchema(c);
        JSONArray vs = vehicles(c);
        if (vs.length() <= 1 || findById(vs, id) == null) return false;
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
            if (entry != null && id.equals(entry.optString("c"))) {
                if (entry.optLong("due", 0) > 0) {
                    ParkingTimers.cancel(c, entry.optString("id"));
                    entry.remove("due");
                }
                timersCleared = true;
            }
        }
        if (timersCleared) saveHistory(c, history);
        cancelBtPrompt(c, id);
        notifyParkingHistoryChanged(c);
        return true;
    }

    static JSONObject vehicleMatchingBluetooth(Context c, String bluetoothName) {
        if (bluetoothName == null) return null;
        String target = bluetoothName.trim();
        if (target.isEmpty()) return null;
        JSONArray vs = vehicles(c);
        for (int i = 0; i < vs.length(); i++) {
            JSONObject vehicle = vs.optJSONObject(i);
            String bt = vehicle == null ? "" : clean(vehicle.optString("b", ""));
            if (!bt.isEmpty() && bt.equalsIgnoreCase(target)) return vehicle;
        }
        return null;
    }

    private static boolean bluetoothNameTaken(JSONArray vehicles, String bt, String exceptId) {
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject vehicle = vehicles.optJSONObject(i);
            if (vehicle == null || (exceptId != null && exceptId.equals(vehicle.optString("id")))) {
                continue;
            }
            if (bt.equalsIgnoreCase(clean(vehicle.optString("b", "")))) return true;
        }
        return false;
    }

    // ---------- 주차 기록 ----------

    /** 전체 기록. 주차장/차량별 화면에는 activeHistory를 사용한다. */
    static JSONArray history(Context c) {
        ensureSchema(c);
        JSONArray out = safeArray(prefs(c).getString(PREF_HISTORY, "[]"));
        return out == null ? new JSONArray() : out;
    }

    /**
     * 주차장×차량 하나를 가리키는 키.
     *
     * <p>구분자는 이스케이프로 적는다. 소스에 NUL 바이트를 그대로 넣으면 git이 이 파일을
     * 바이너리로 판정해서 줄바꿈 정규화와 diff가 통째로 망가진다.
     */
    private static String contextKey(String profileId, String vehicleId) {
        return clean(profileId) + "\u0000" + clean(vehicleId);
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
        if (id == null) return null;
        JSONArray all = history(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return entry;
        }
        return null;
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
        ensureSchema(c);
        String z = clean(zone);
        if (z.isEmpty()) return null;
        boolean explicitProfile = !clean(profileId).isEmpty();
        boolean explicitVehicle = !clean(vehicleId).isEmpty();
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

        String id = UUID.randomUUID().toString();
        JSONArray old = history(c);
        JSONArray next = new JSONArray();
        try {
            JSONObject entry = new JSONObject();
            entry.put("id", id);
            entry.put("c", vehicleId);
            entry.put("cn", vehicle == null
                    ? c.getString(R.string.vehicle_default_name)
                    : vehicle.optString("n", c.getString(R.string.vehicle_default_name)));
            entry.put("p", profileId);
            entry.put("pn", profile == null
                    ? c.getString(R.string.profile_default_name)
                    : profile.optString("n", c.getString(R.string.profile_default_name)));
            entry.put("z", z);
            entry.put("t", System.currentTimeMillis());
            String m = clean(memo);
            if (!m.isEmpty()) entry.put("m", m);
            next.put(entry);

            // 맥락(주차장×차량)별로 세면서 담는다. 전역 상한 하나로 자르면 차를 두 대
            // 쓰는 사람이 한쪽을 자주 댔다는 이유로 다른 쪽 기록을 잃는다.
            HashMap<String, Integer> perContext = new HashMap<>();
            perContext.put(contextKey(profileId, vehicleId), 1); // 방금 넣은 기록

            for (int i = 0; i < old.length(); i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous == null) continue;

                // 같은 차량의 이전 주차 타이머는 차량이 다시 움직였다는 뜻이므로 해제한다.
                if (vehicleId.equals(previous.optString("c"))
                        && previous.optLong("due", 0) > 0) {
                    ParkingTimers.cancel(c, previous.optString("id"));
                    previous.remove("due");
                }

                String key = contextKey(previous.optString("p"), previous.optString("c"));
                Integer used = perContext.get(key);
                int count = used == null ? 0 : used;
                if (count < MAX_HISTORY_PER_CONTEXT && next.length() < MAX_HISTORY) {
                    perContext.put(key, count + 1);
                    next.put(previous);
                } else if (previous.optLong("due", 0) > 0) {
                    // 잘려 나가는 기록의 타이머는 반드시 취소한다. 남겨 두면 이제
                    // 존재하지 않는 기록을 가리키는 알람이 남는다.
                    ParkingTimers.cancel(c, previous.optString("id"));
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        // 위젯/알림 액션의 스냅샷을 현재 맥락으로도 전환해 저장 결과가 즉시 보이게 한다.
        prefs(c).edit()
                .putString(PREF_HISTORY, next.toString())
                .putString(PREF_ACTIVE_PROFILE, profileId)
                .putString(PREF_ACTIVE_VEHICLE, vehicleId)
                .apply();
        cancelBtPrompt(c, vehicleId);
        notifyParkingHistoryChanged(c);
        return id;
    }

    static boolean updateRecord(Context c, String recordId, String profileId, String vehicleId,
                                String zone, long parkedAt, String memo, long timerDue) {
        ensureSchema(c);
        String z = clean(zone);
        if (z.isEmpty()) return false;
        JSONArray all = history(c);
        JSONObject entry = findById(all, recordId);
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
            entry.put("z", z);
            entry.put("t", parkedAt);
            String m = clean(memo);
            if (m.isEmpty()) entry.remove("m");
            else entry.put("m", m);
            if (safeDue == 0) entry.remove("due");
            else entry.put("due", safeDue);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        JSONArray sorted = sortHistory(all);
        saveHistory(c, sorted);
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
        JSONObject entry = findById(all, recordId);
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

    static String latestZone(Context c) {
        JSONObject e = latestRecord(c);
        return e == null ? null : e.optString("z", "?");
    }

    static long latestTime(Context c) {
        JSONObject e = latestRecord(c);
        return e == null ? 0 : e.optLong("t", 0);
    }

    static String recordProfileName(Context c, JSONObject entry) {
        if (entry == null) return "";
        String snapshot = clean(entry.optString("pn", ""));
        return snapshot.isEmpty() ? profileName(c, entry.optString("p")) : snapshot;
    }

    static String recordVehicleName(Context c, JSONObject entry) {
        if (entry == null) return "";
        String snapshot = clean(entry.optString("cn", ""));
        return snapshot.isEmpty() ? vehicleName(c, entry.optString("c")) : snapshot;
    }

    static String recordMemo(JSONObject entry) {
        return entry == null ? "" : clean(entry.optString("m", ""));
    }

    /** 해당 차량·주차장 프로필 안에서 최근에 쓴 서로 다른 구역. 블루투스 알림 버튼용. */
    static String[] recentZones(Context c, String profileId, String vehicleId, int max) {
        ArrayList<String> out = new ArrayList<>();
        JSONArray h = historyForContext(c, profileId, vehicleId);
        for (int i = 0; i < h.length() && out.size() < max; i++) {
            JSONObject e = h.optJSONObject(i);
            String zone = e == null ? "" : clean(e.optString("z", ""));
            if (!zone.isEmpty() && !out.contains(zone)) out.add(zone);
        }
        String[] main = mainZonesForProfile(c, profileId);
        for (String zone : main) {
            if (out.size() >= max) break;
            if (!out.contains(zone)) out.add(zone);
        }
        return out.toArray(new String[0]);
    }

    /** 활성 차량·주차장 호환용. */
    static String[] recentZones(Context c, int max) {
        return recentZones(c, activeProfileId(c), activeVehicleId(c), max);
    }

    /**
     * 홈 위젯에 올릴 구역. 격자가 버튼 수보다 크면 최근에 쓴 구역을 골라 담되,
     * <b>배치는 격자 순서를 그대로 지킨다</b>. 최근순으로 늘어놓으면 주차할 때마다
     * 버튼이 자리를 바꿔서 손에 익지 않는다.
     */
    static String[] widgetZones(Context c, String profileId, String vehicleId, int max) {
        String[] grid = mainZonesForProfile(c, profileId);
        if (grid.length <= max) return grid;

        HashSet<String> inGrid = new HashSet<>(Arrays.asList(grid));
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        JSONArray h = historyForContext(c, profileId, vehicleId);
        for (int i = 0; i < h.length() && keep.size() < max; i++) {
            JSONObject entry = h.optJSONObject(i);
            String zone = entry == null ? "" : clean(entry.optString("z", ""));
            // 직접 입력한 일회성 위치(예: 롯데몰)는 위젯 자리를 차지하지 않게 거른다.
            if (!zone.isEmpty() && inGrid.contains(zone)) keep.add(zone);
        }
        for (String zone : grid) {
            if (keep.size() >= max) break;
            keep.add(zone);
        }
        ArrayList<String> out = new ArrayList<>();
        for (String zone : grid) {
            if (keep.contains(zone)) out.add(zone);
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

    static void cancelBtPrompt(Context c, String vehicleId) {
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel("bt:" + vehicleId, NOTIF_ID_BT);
    }

    // ---------- 습관 체크 ----------
    // habits: [{n: 이름, r: 리마인더(0시 기준 분, -1=없음), days: ["yyyy-MM-dd" 최신순], lt: 오늘 체크 시각}]

    static JSONArray habits(Context c) {
        ensureSchema(c); // 클래스 규칙: 모든 공개 접근자가 호출한다. 여기만 예외였다.
        try {
            return new JSONArray(prefs(c).getString(PREF_HABITS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void saveHabits(Context c, JSONArray hs) {
        prefs(c).edit().putString(PREF_HABITS, hs.toString()).apply();
    }

    static void addHabit(Context c, String name) {
        try {
            JSONArray hs = habits(c);
            JSONObject h = new JSONObject();
            h.put("n", name);
            h.put("r", -1);
            h.put("days", new JSONArray());
            h.put("lt", 0L);
            hs.put(h);
            saveHabits(c, hs);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static void deleteHabit(Context c, int index) {
        JSONArray hs = habits(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < hs.length(); i++) {
            if (i != index) next.put(hs.optJSONObject(i));
        }
        saveHabits(c, next);
    }

    static void setReminder(Context c, int index, int minutesOfDay) {
        try {
            JSONArray hs = habits(c);
            hs.getJSONObject(index).put("r", minutesOfDay);
            saveHabits(c, hs);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static JSONObject habitByName(Context c, String name) {
        JSONArray hs = habits(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && name.equals(h.optString("n"))) return h;
        }
        return null;
    }

    static boolean checkedToday(JSONObject h) {
        JSONArray days = h.optJSONArray("days");
        return days != null && days.length() > 0 && today().equals(days.optString(0));
    }

    static void toggleToday(Context c, int index) {
        try {
            JSONArray hs = habits(c);
            JSONObject h = hs.getJSONObject(index);
            JSONArray days = h.optJSONArray("days");
            if (days == null) days = new JSONArray();
            JSONArray next = new JSONArray();
            String today = today();
            if (days.length() > 0 && today.equals(days.optString(0))) {
                for (int i = 1; i < days.length(); i++) next.put(days.optString(i));
                h.put("lt", 0L);
            } else {
                next.put(today);
                for (int i = 0; i < days.length() && i < MAX_HABIT_DAYS - 1; i++) {
                    next.put(days.optString(i));
                }
                h.put("lt", System.currentTimeMillis());
            }
            h.put("days", next);
            saveHabits(c, hs);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static void checkTodayByName(Context c, String name) {
        JSONArray hs = habits(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && name.equals(h.optString("n"))) {
                if (!checkedToday(h)) toggleToday(c, i);
                return;
            }
        }
    }

    /** 체크한 날짜 집합. 저장 형식은 LocalDate.toString()과 같은 yyyy-MM-dd다. */
    private static HashSet<String> checkedDays(JSONObject h) {
        HashSet<String> set = new HashSet<>();
        JSONArray days = h == null ? null : h.optJSONArray("days");
        if (days != null) {
            for (int i = 0; i < days.length(); i++) set.add(days.optString(i));
        }
        return set;
    }

    static boolean[] last7Days(JSONObject h) {
        HashSet<String> set = checkedDays(h);
        boolean[] out = new boolean[7];
        LocalDate day = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            out[i] = set.contains(day.toString());
            day = day.minusDays(1);
        }
        return out;
    }

    static int streak(JSONObject h) {
        HashSet<String> set = checkedDays(h);
        if (set.isEmpty()) return 0;
        LocalDate day = LocalDate.now();
        // 오늘 아직 안 했어도 어제까지 이어진 연속은 살아 있다.
        if (!set.contains(day.toString())) day = day.minusDays(1);
        int n = 0;
        while (set.contains(day.toString())) {
            n++;
            day = day.minusDays(1);
        }
        return n;
    }

    static String today() {
        return LocalDate.now().toString();
    }

    // ---------- 포맷 ----------
    // 여기 있는 한국어 조각("분 전", "오전")은 일부러 strings.xml로 빼지 않았다.
    // 위의 날짜 패턴("M월 d일 (E) a h:mm")과 한 덩어리로 움직이는 로케일 포맷이고,
    // Context를 받지 않아야 유닛 테스트에서 그대로 검증할 수 있다.
    // 다국어를 하게 되면 이 블록 전체를 한 번에 옮겨야 한다.

    private static java.time.ZonedDateTime at(long t) {
        return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault());
    }

    static String formatFull(long t) {
        return FMT_FULL.format(at(t));
    }

    static String formatShort(long t) {
        return FMT_SHORT.format(at(t));
    }

    static String formatRelative(long t) {
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 0) return (-min) + "분 후";
        if (min < 1) return "방금 전";
        if (min < 60) return min + "분 전";
        long hour = min / 60;
        if (hour < 24) return hour + "시간 전";
        return (hour / 24) + "일 전";
    }

    static String formatMinutesOfDay(int m) {
        int h = m / 60;
        String ap = h < 12 ? "오전" : "오후";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return ap + " " + h12 + ":" + String.format(Locale.US, "%02d", m % 60);
    }
}
