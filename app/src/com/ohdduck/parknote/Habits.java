package com.ohdduck.parknote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.HashSet;

/**
 * "나가기 전 체크" 항목. 주차 기록과는 저장소만 같이 쓴다.
 *
 * <p>habits: [{n: 이름, r: 리마인더(0시 기준 분, -1=없음), days: ["yyyy-MM-dd" 최신순], lt: 오늘 체크 시각}]
 */
final class Habits {

    private static final int MAX_DAYS = 400;

    private Habits() {
    }

    static JSONArray all(Context c) {
        Migration.ensure(c);
        JSONArray out = Json.array(Store.prefs(c).getString(Store.PREF_HABITS, "[]"));
        return out == null ? new JSONArray() : out;
    }

    private static void save(Context c, JSONArray hs) {
        Store.prefs(c).edit().putString(Store.PREF_HABITS, hs.toString()).apply();
    }

    static void add(Context c, String name) {
        try {
            JSONArray hs = all(c);
            hs.put(new JSONObject()
                    .put("n", name)
                    .put("r", -1)
                    .put("days", new JSONArray())
                    .put("lt", 0L));
            save(c, hs);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static void delete(Context c, int index) {
        JSONArray hs = all(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            if (i == index) {
                // 알람(Reminders.cancel)은 호출한 쪽이 거둔다. 이미 떠 있는 알림은 여기서.
                Notify.cancelHabit(c, h.optString("n"));
                continue;
            }
            next.put(h);
        }
        save(c, next);
    }

    static void setReminder(Context c, int index, int minutesOfDay) {
        try {
            JSONArray hs = all(c);
            hs.getJSONObject(index).put("r", minutesOfDay);
            save(c, hs);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static JSONObject byName(Context c, String name) {
        JSONArray hs = all(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && name.equals(h.optString("n"))) return h;
        }
        return null;
    }

    static boolean checkedToday(JSONObject h) {
        JSONArray days = h.optJSONArray("days");
        return days != null && days.length() > 0 && Fmt.today().equals(days.optString(0));
    }

    static void toggleToday(Context c, int index) {
        try {
            JSONArray hs = all(c);
            JSONObject h = hs.getJSONObject(index);
            JSONArray days = h.optJSONArray("days");
            if (days == null) days = new JSONArray();
            JSONArray next = new JSONArray();
            String today = Fmt.today();
            if (days.length() > 0 && today.equals(days.optString(0))) {
                for (int i = 1; i < days.length(); i++) next.put(days.optString(i));
                h.put("lt", 0L);
            } else {
                next.put(today);
                for (int i = 0; i < days.length() && i < MAX_DAYS - 1; i++) {
                    next.put(days.optString(i));
                }
                h.put("lt", System.currentTimeMillis());
            }
            h.put("days", next);
            save(c, hs);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /** 알림의 "지금 체크" 버튼. 이미 했으면 그대로 둔다. */
    static void checkTodayByName(Context c, String name) {
        JSONArray hs = all(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && name.equals(h.optString("n"))) {
                if (!checkedToday(h)) toggleToday(c, i);
                return;
            }
        }
    }

    private static HashSet<String> checkedDays(JSONObject h) {
        HashSet<String> set = new HashSet<>();
        JSONArray days = h == null ? null : h.optJSONArray("days");
        if (days != null) {
            for (int i = 0; i < days.length(); i++) set.add(days.optString(i));
        }
        return set;
    }

    /** 최근 7일 체크 여부 (왼쪽=6일 전, 오른쪽=오늘) */
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
}
