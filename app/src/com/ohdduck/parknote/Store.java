package com.ohdduck.parknote;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/**
 * 기록 저장/조회 공용 로직. 앱 화면, 위젯, 알림, 타일이 모두 이 클래스를 쓴다.
 * 기존 단일 주차장·차량 데이터는 최초 접근 시 프로필/차량/기록 ID 구조로 안전하게 옮긴다.
 */
class Store {

    static final String CAR_BT_NAME = "Ray";
    static final String LEGACY_PROFILE_ID = "legacy-default-profile";
    static final String LEGACY_VEHICLE_ID = "legacy-default-vehicle";

    private static final int SCHEMA_VERSION = 4;
    private static final int MAX_PROFILES = 12;
    private static final int MAX_VEHICLES = 12;

    private static final String[] DEFAULT_MAIN_ZONES =
            {"B1-A", "B1-B", "B2-A", "B2-B", "B3-A", "B3-B"};
    private static final String[] DEFAULT_ETC_ZONES =
            {"B4-A", "B4-B", "B5-A", "B5-B"};

    // v2.2 이하에서 쓰던 키. 마이그레이션 뒤에도 지우지 않아 복구 여지를 남긴다.
    private static final String PREF_MAIN_ZONES = "main_zones";
    private static final String PREF_ETC_ZONES = "etc_zones";
    private static final String PREF_CAR_BT = "car_bt";

    private static final String PREF_SCHEMA = "parking_schema";
    private static final String PREF_PROFILES = "parking_profiles_v1";
    private static final String PREF_ACTIVE_PROFILE = "active_parking_profile_id";
    private static final String PREF_VEHICLES = "parking_vehicles_v1";
    private static final String PREF_ACTIVE_VEHICLE = "active_parking_vehicle_id";
    private static final String PREF_HISTORY = "history";

    static final String CHANNEL = "park_reminder";
    static final String CHANNEL_HABIT = "habit_reminder";
    static final String CHANNEL_TIMER = "parking_timer";
    static final int NOTIF_ID_BT = 1;
    static final int NOTIF_ID_HABIT = 2;
    static final int NOTIF_ID_TIMER = 3;
    static final int MAX_HISTORY = 40;
    private static final int MAX_HABIT_DAYS = 400;

    private static final SimpleDateFormat FMT_FULL =
            new SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREAN);
    private static final SimpleDateFormat FMT_SHORT =
            new SimpleDateFormat("a h:mm", Locale.KOREAN);
    private static final SimpleDateFormat FMT_DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

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

            try {
                JSONArray profileList = safeArray(p.getString(PREF_PROFILES, null));
                if (profileList == null || profileList.length() == 0) {
                    profileList = new JSONArray();
                    profileList.put(newProfile(LEGACY_PROFILE_ID, "우리 집",
                            readLegacyZones(p, PREF_MAIN_ZONES, DEFAULT_MAIN_ZONES, false),
                            readLegacyZones(p, PREF_ETC_ZONES, DEFAULT_ETC_ZONES, true)));
                }
                JSONObject firstProfile = profileList.optJSONObject(0);
                String firstProfileId = firstProfile == null
                        ? LEGACY_PROFILE_ID : firstProfile.optString("id", LEGACY_PROFILE_ID);
                String activeProfile = p.getString(PREF_ACTIVE_PROFILE, "");
                if (findById(profileList, activeProfile) == null) activeProfile = firstProfileId;

                JSONArray vehicleList = safeArray(p.getString(PREF_VEHICLES, null));
                if (vehicleList == null || vehicleList.length() == 0) {
                    String legacyBt = clean(p.getString(PREF_CAR_BT, ""));
                    if (legacyBt.isEmpty()) legacyBt = CAR_BT_NAME;
                    vehicleList = new JSONArray();
                    vehicleList.put(newVehicle(LEGACY_VEHICLE_ID, legacyBt, legacyBt));
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
                        entry.put("pn", profile.optString("n", "우리 집"));
                    }
                    String vehicleId = clean(entry.optString("c", ""));
                    if (findById(vehicleList, vehicleId) == null) vehicleId = LEGACY_VEHICLE_ID;
                    JSONObject vehicle = findById(vehicleList, vehicleId);
                    if (vehicle == null) vehicle = firstVehicle;
                    entry.put("c", vehicle == null ? firstVehicleId : vehicle.optString("id"));
                    if (clean(entry.optString("cn", "")).isEmpty() && vehicle != null) {
                        entry.put("cn", vehicle.optString("n", "내 차"));
                    }
                    normalized.put(entry);
                }

                p.edit()
                        .putString(PREF_PROFILES, profileList.toString())
                        .putString(PREF_ACTIVE_PROFILE, activeProfile)
                        .putString(PREF_VEHICLES, vehicleList.toString())
                        .putString(PREF_ACTIVE_VEHICLE, activeVehicle)
                        .putString(PREF_HISTORY, normalized.toString())
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

    private static String[] readLegacyZones(SharedPreferences p, String key,
                                            String[] defaults, boolean emptyAllowed) {
        JSONArray a = safeArray(p.getString(key, null));
        if (a == null) return defaults.clone();
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String zone = clean(a.optString(i, ""));
            if (!zone.isEmpty()) out.add(zone);
        }
        if (!out.isEmpty() || emptyAllowed) return out.toArray(new String[0]);
        return defaults.clone();
    }

    private static JSONObject newProfile(String id, String name, String[] main, String[] etc)
            throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("n", name);
        out.put("main", zonesJson(main));
        out.put("etc", zonesJson(etc));
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

    private static String[] zonesOf(JSONObject profile, String key,
                                    String[] defaults, boolean emptyAllowed) {
        if (profile == null) return defaults.clone();
        JSONArray a = profile.optJSONArray(key);
        if (a == null) return defaults.clone();
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String zone = clean(a.optString(i, ""));
            if (!zone.isEmpty()) out.add(zone);
        }
        if (!out.isEmpty() || emptyAllowed) return out.toArray(new String[0]);
        return defaults.clone();
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
        return profile == null ? "삭제된 주차장" : profile.optString("n", "주차장");
    }

    static String activeProfileName(Context c) {
        JSONObject profile = activeProfile(c);
        return profile == null ? "주차장" : profile.optString("n", "주차장");
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
        if (n.isEmpty()) throw new IllegalArgumentException("주차장 이름을 입력해 주세요");
        JSONArray ps = profiles(c);
        if (ps.length() >= MAX_PROFILES) {
            throw new IllegalArgumentException("주차장은 " + MAX_PROFILES + "개까지 만들 수 있어요");
        }
        if (nameTaken(ps, n, null)) throw new IllegalArgumentException("이미 같은 이름의 주차장이 있어요");
        JSONObject current = activeProfile(c);
        String id = UUID.randomUUID().toString();
        try {
            ps.put(newProfile(id, n,
                    zonesOf(current, "main", DEFAULT_MAIN_ZONES, false),
                    zonesOf(current, "etc", DEFAULT_ETC_ZONES, true)));
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
        if (n.isEmpty()) throw new IllegalArgumentException("주차장 이름을 입력해 주세요");
        JSONArray ps = profiles(c);
        JSONObject profile = findById(ps, id);
        if (profile == null) throw new IllegalArgumentException("주차장을 찾을 수 없어요");
        if (nameTaken(ps, n, id)) throw new IllegalArgumentException("이미 같은 이름의 주차장이 있어요");
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
        return zonesOf(activeProfile(c), "main", DEFAULT_MAIN_ZONES, false);
    }

    static String[] etcZones(Context c) {
        return zonesOf(activeProfile(c), "etc", DEFAULT_ETC_ZONES, true);
    }

    static String[] mainZonesForProfile(Context c, String profileId) {
        return zonesOf(profileById(c, profileId), "main", DEFAULT_MAIN_ZONES, false);
    }

    static void setZones(Context c, String[] main, String[] etc) {
        if (main == null || main.length == 0) {
            throw new IllegalArgumentException("main zones must not be empty");
        }
        JSONArray ps = profiles(c);
        JSONObject profile = findById(ps, activeProfileId(c));
        if (profile == null) return;
        try {
            profile.put("main", zonesJson(main));
            profile.put("etc", zonesJson(etc == null ? new String[0] : etc));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        prefs(c).edit().putString(PREF_PROFILES, ps.toString()).apply();
        notifyParkingContextChanged(c);
    }

    static void resetZones(Context c) {
        setZones(c, DEFAULT_MAIN_ZONES, DEFAULT_ETC_ZONES);
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
        return vehicle == null ? "삭제된 차량" : vehicle.optString("n", "차량");
    }

    static String vehicleBtName(Context c, String id) {
        JSONObject vehicle = vehicleById(c, id);
        return vehicle == null ? "" : vehicle.optString("b", "");
    }

    static String activeVehicleName(Context c) {
        JSONObject vehicle = activeVehicle(c);
        return vehicle == null ? "차량" : vehicle.optString("n", "차량");
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
        if (n.isEmpty()) throw new IllegalArgumentException("차량 이름을 입력해 주세요");
        JSONArray vs = vehicles(c);
        if (vs.length() >= MAX_VEHICLES) {
            throw new IllegalArgumentException("차량은 " + MAX_VEHICLES + "대까지 등록할 수 있어요");
        }
        if (nameTaken(vs, n, null)) throw new IllegalArgumentException("이미 같은 이름의 차량이 있어요");
        if (!b.isEmpty() && bluetoothNameTaken(vs, b, null)) {
            throw new IllegalArgumentException("이미 같은 블루투스 이름이 등록되어 있어요");
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
        if (n.isEmpty()) throw new IllegalArgumentException("차량 이름을 입력해 주세요");
        JSONArray vs = vehicles(c);
        JSONObject vehicle = findById(vs, id);
        if (vehicle == null) throw new IllegalArgumentException("차량을 찾을 수 없어요");
        if (nameTaken(vs, n, id)) throw new IllegalArgumentException("이미 같은 이름의 차량이 있어요");
        if (!b.isEmpty() && bluetoothNameTaken(vs, b, id)) {
            throw new IllegalArgumentException("이미 같은 블루투스 이름이 등록되어 있어요");
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

    /** 이전 단일 차량 API 호환용. 현재 선택 차량의 블루투스 이름을 반환한다. */
    static String carBtName(Context c) {
        String bt = vehicleBtName(c, activeVehicleId(c));
        return bt.isEmpty() ? CAR_BT_NAME : bt;
    }

    static void setCarBtName(Context c, String name) {
        String bt = clean(name);
        JSONObject vehicle = activeVehicle(c);
        if (vehicle != null) {
            updateVehicle(c, vehicle.optString("id"), vehicle.optString("n", "내 차"), bt);
        }
        prefs(c).edit().putString(PREF_CAR_BT, bt).apply();
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

    static String record(Context c, String zone) {
        return recordInContext(c, activeProfileId(c), activeVehicleId(c), zone, "");
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
            profileId = activeProfileId(c);
            profile = activeProfile(c);
        }
        JSONObject vehicle = vehicleById(c, vehicleId);
        if (vehicle == null) {
            if (explicitVehicle) return null;
            vehicleId = activeVehicleId(c);
            vehicle = activeVehicle(c);
        }

        String id = UUID.randomUUID().toString();
        JSONArray old = history(c);
        JSONArray next = new JSONArray();
        try {
            JSONObject entry = new JSONObject();
            entry.put("id", id);
            entry.put("c", vehicleId);
            entry.put("cn", vehicle == null ? "차량" : vehicle.optString("n", "차량"));
            entry.put("p", profileId);
            entry.put("pn", profile == null ? "주차장" : profile.optString("n", "주차장"));
            entry.put("z", z);
            entry.put("t", System.currentTimeMillis());
            String m = clean(memo);
            if (!m.isEmpty()) entry.put("m", m);
            next.put(entry);

            // 같은 차량의 이전 주차 타이머는 차량이 다시 움직였다는 뜻이므로 해제한다.
            for (int i = 0; i < old.length() && next.length() < MAX_HISTORY; i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous == null) continue;
                if (vehicleId.equals(previous.optString("c"))
                        && previous.optLong("due", 0) > 0) {
                    ParkingTimers.cancel(c, previous.optString("id"));
                    previous.remove("due");
                }
                next.put(previous);
            }
            // 잘려 나가는 오래된 기록의 타이머도 반드시 취소한다.
            for (int i = MAX_HISTORY - 1; i < old.length(); i++) {
                JSONObject dropped = old.optJSONObject(i);
                if (dropped != null && dropped.optLong("due", 0) > 0) {
                    ParkingTimers.cancel(c, dropped.optString("id"));
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
                entry.put("pn", profile.optString("n", "주차장"));
            }
            if (vehicle != null) {
                entry.put("c", vehicleId);
                entry.put("cn", vehicle.optString("n", "차량"));
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
        try {
            return new JSONArray(prefs(c).getString("habits", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void saveHabits(Context c, JSONArray hs) {
        prefs(c).edit().putString("habits", hs.toString()).apply();
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

    static boolean[] last7Days(JSONObject h) {
        JSONArray days = h.optJSONArray("days");
        HashSet<String> set = new HashSet<>();
        if (days != null) {
            for (int i = 0; i < days.length(); i++) set.add(days.optString(i));
        }
        boolean[] out = new boolean[7];
        Calendar cal = Calendar.getInstance();
        for (int i = 6; i >= 0; i--) {
            out[i] = set.contains(FMT_DAY.format(cal.getTime()));
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return out;
    }

    static int streak(JSONObject h) {
        JSONArray days = h.optJSONArray("days");
        if (days == null || days.length() == 0) return 0;
        HashSet<String> set = new HashSet<>();
        for (int i = 0; i < days.length(); i++) set.add(days.optString(i));
        Calendar cal = Calendar.getInstance();
        String day = FMT_DAY.format(cal.getTime());
        if (!set.contains(day)) {
            cal.add(Calendar.DAY_OF_YEAR, -1);
            day = FMT_DAY.format(cal.getTime());
        }
        int n = 0;
        while (set.contains(day)) {
            n++;
            cal.add(Calendar.DAY_OF_YEAR, -1);
            day = FMT_DAY.format(cal.getTime());
        }
        return n;
    }

    static String today() {
        return FMT_DAY.format(new Date());
    }

    // ---------- 포맷 ----------

    static String formatFull(long t) {
        return FMT_FULL.format(new Date(t));
    }

    static String formatShort(long t) {
        return FMT_SHORT.format(new Date(t));
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
