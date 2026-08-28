package com.ohdduck.parknote;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 이전 버전 데이터를 현재 구조로 옮긴다.
 *
 * <p>v2.2의 main_zones / etc_zones / car_bt / {z,t} 기록 → 프로필·차량·기록 ID 구조,
 * v2.5 이하의 평면 구역 목록 → 층×구역 격자(스키마 v5). 수신기나 위젯이 앱보다 먼저
 * 실행될 수 있으므로 Store의 모든 공개 접근자가 {@link #ensure}를 거친다.
 */
final class Migration {

    // v2.2 이하에서 쓰던 키. 마이그레이션 뒤에도 지우지 않아 복구 여지를 남긴다.
    private static final String PREF_MAIN_ZONES = "main_zones";
    private static final String PREF_ETC_ZONES = "etc_zones";
    private static final String PREF_CAR_BT = "car_bt";

    /**
     * v2.2에서 쓰던 기타 구역 기본값. <b>이제는 마이그레이션 폴백으로만 쓴다.</b>
     *
     * <p>새로 설치한 사람에게 이걸 심으면 지하 2층짜리 주차장을 설정한 사람도 있지도 않은
     * B4·B5 버튼을 보게 된다. 더 나쁜 건 지하 5층 × 구역 2개를 고른 경우다. 격자가 만드는
     * 이름과 그대로 겹쳐서, 이후 구역 편집이 "중복된 구역이 있어요"로 영영 저장되지 않았다.
     */
    private static final String[] LEGACY_ETC_ZONES = {"B4-A", "B4-B", "B5-A", "B5-B"};
    /** 평면 목록을 격자로 역추론할 때 앞에서부터 시도하는 구분자 */
    private static final String[] SEP_CANDIDATES = {"-", "_", " "};

    private Migration() {
    }

    static void ensure(Context c) {
        SharedPreferences p = Store.prefs(c);
        if (p.getInt(Store.PREF_SCHEMA, 0) >= Store.SCHEMA_VERSION) return;
        synchronized (Migration.class) {
            if (p.getInt(Store.PREF_SCHEMA, 0) >= Store.SCHEMA_VERSION) return;

            // 이미 쓰고 있던 기기는 온보딩을 다시 보여 주지 않는다. 저장된 흔적이
            // 하나라도 있으면 업데이트 설치로 본다.
            boolean freshInstall = p.getInt(Store.PREF_SCHEMA, 0) == 0
                    && p.getString(Store.PREF_PROFILES, null) == null
                    && p.getString(PREF_MAIN_ZONES, null) == null
                    && p.getString(Store.PREF_HISTORY, null) == null;

            try {
                JSONArray profileList = Json.array(p.getString(Store.PREF_PROFILES, null));
                if (profileList == null || profileList.length() == 0) {
                    profileList = new JSONArray();
                    // 새로 설치했으면 기타 구역을 비워 둔다. 업데이트 설치라면 예전 값을
                    // 그대로 읽고, 그것도 없으면 v2.2 기본값으로 되살린다.
                    JSONObject fresh = Store.newProfile(Store.LEGACY_PROFILE_ID,
                            c.getString(R.string.onboarding_default_profile),
                            Store.DEFAULT_ROWS, Store.DEFAULT_COLS, Store.DEFAULT_SEP,
                            freshInstall
                                    ? Store.NO_ZONES
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
                        String[] flat = Json.strings(profile.optJSONArray("main"));
                        applyFlatZones(profile, flat.length > 0 ? flat
                                : Store.flatten(Store.DEFAULT_ROWS, Store.DEFAULT_COLS, Store.DEFAULT_SEP));
                    }
                }
                String activeProfile = p.getString(Store.PREF_ACTIVE_PROFILE, "");
                if (Json.byId(profileList, activeProfile) == null) {
                    activeProfile = firstId(profileList, Store.LEGACY_PROFILE_ID);
                }

                JSONArray vehicleList = Json.array(p.getString(Store.PREF_VEHICLES, null));
                if (vehicleList == null || vehicleList.length() == 0) {
                    // 새로 설치한 경우 블루투스 이름을 비워 둔다. 온보딩에서 실제 페어링된
                    // 기기를 고르게 하므로, 남의 차 이름을 기본값으로 심어 두지 않는다.
                    String legacyBt = Json.clean(p.getString(PREF_CAR_BT, ""));
                    vehicleList = new JSONArray();
                    vehicleList.put(Store.newVehicle(Store.LEGACY_VEHICLE_ID,
                            legacyBt.isEmpty()
                                    ? c.getString(R.string.onboarding_default_vehicle)
                                    : legacyBt,
                            legacyBt, ""));
                }
                String activeVehicle = p.getString(Store.PREF_ACTIVE_VEHICLE, "");
                if (Json.byId(vehicleList, activeVehicle) == null) {
                    activeVehicle = firstId(vehicleList, Store.LEGACY_VEHICLE_ID);
                }

                JSONArray oldHistory = Json.array(p.getString(Store.PREF_HISTORY, "[]"));
                if (oldHistory == null) oldHistory = new JSONArray();
                JSONArray normalized = normalizeHistory(oldHistory, profileList, vehicleList,
                        c.getString(R.string.onboarding_default_profile),
                        c.getString(R.string.onboarding_default_vehicle));

                p.edit()
                        .putString(Store.PREF_PROFILES, profileList.toString())
                        .putString(Store.PREF_ACTIVE_PROFILE, activeProfile)
                        .putString(Store.PREF_VEHICLES, vehicleList.toString())
                        .putString(Store.PREF_ACTIVE_VEHICLE, activeVehicle)
                        .putString(Store.PREF_HISTORY, normalized.toString())
                        .putBoolean(Store.PREF_ONBOARDED, !freshInstall)
                        .putInt(Store.PREF_SCHEMA, Store.SCHEMA_VERSION)
                        .apply();
                NotificationManager nm = Notify.manager(c);
                if (nm != null) nm.cancel(Notify.ID_PARK); // v2.2의 태그 없는 주차 알림 정리
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String firstId(JSONArray items, String fallback) {
        JSONObject first = items.optJSONObject(0);
        return first == null ? fallback : first.optString("id", fallback);
    }

    /**
     * v2.2의 {z, t} 기록과 id·주차장·차량이 빠진 기록을 새 구조로 맞춘다.
     *
     * <p>Context 없이 돌아가는 순수 함수다. 마이그레이션은 깨지면 기존 사용자의 기록이
     * 통째로 사라지는 자리라 유닛 테스트가 붙어야 한다.
     *
     * <ul>
     *   <li>id가 없으면 새로 만든다.</li>
     *   <li>주차장·차량 id가 목록에 없으면 legacy id → 첫 항목 순으로 되돌린다.</li>
     *   <li>이름 스냅샷(pn/cn)이 비어 있으면 그 시점의 이름을 채운다.</li>
     * </ul>
     */
    static JSONArray normalizeHistory(JSONArray oldHistory, JSONArray profileList,
                                      JSONArray vehicleList, String defaultProfileName,
                                      String defaultVehicleName) throws JSONException {
        JSONObject firstProfile = profileList.optJSONObject(0);
        String firstProfileId = firstId(profileList, Store.LEGACY_PROFILE_ID);
        JSONObject firstVehicle = vehicleList.optJSONObject(0);
        String firstVehicleId = firstId(vehicleList, Store.LEGACY_VEHICLE_ID);

        JSONArray normalized = new JSONArray();
        for (int i = 0; i < oldHistory.length(); i++) {
            JSONObject old = oldHistory.optJSONObject(i);
            if (old == null) continue;
            JSONObject entry = Json.copy(old);
            if (Json.clean(entry.optString("id", "")).isEmpty()) {
                entry.put("id", UUID.randomUUID().toString());
            }
            String profileId = Json.clean(entry.optString("p", ""));
            if (Json.byId(profileList, profileId) == null) profileId = Store.LEGACY_PROFILE_ID;
            JSONObject profile = Json.byId(profileList, profileId);
            if (profile == null) profile = firstProfile;
            entry.put("p", profile == null ? firstProfileId : profile.optString("id"));
            if (Json.clean(entry.optString("pn", "")).isEmpty() && profile != null) {
                entry.put("pn", profile.optString("n", defaultProfileName));
            }
            String vehicleId = Json.clean(entry.optString("c", ""));
            if (Json.byId(vehicleList, vehicleId) == null) vehicleId = Store.LEGACY_VEHICLE_ID;
            JSONObject vehicle = Json.byId(vehicleList, vehicleId);
            if (vehicle == null) vehicle = firstVehicle;
            entry.put("c", vehicle == null ? firstVehicleId : vehicle.optString("id"));
            if (Json.clean(entry.optString("cn", "")).isEmpty() && vehicle != null) {
                entry.put("cn", vehicle.optString("n", defaultVehicleName));
            }
            normalized.put(entry);
        }
        return normalized;
    }

    /** defaults가 null이면 저장된 값이 없을 때 null을 돌려준다. */
    private static String[] readLegacyZones(SharedPreferences p, String key,
                                            String[] defaults, boolean emptyAllowed) {
        JSONArray a = Json.array(p.getString(key, null));
        if (a == null) return defaults == null ? null : defaults.clone();
        String[] out = Json.strings(a);
        if (out.length > 0 || emptyAllowed) return out;
        return defaults == null ? null : defaults.clone();
    }

    // ---------- 평면 목록 → 격자 역추론 ----------

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
            profile.put("rows", Json.of(grid[0]));
            profile.put("cols", Json.of(grid[1]));
            profile.put("sep", sep);
            return;
        }
        profile.put("rows", new JSONArray());
        profile.put("cols", Json.of(zones));
        profile.put("sep", Store.DEFAULT_SEP);
    }
}
