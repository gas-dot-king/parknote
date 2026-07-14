package com.ohdduck.parknote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/** 습관 리마인더 알람 예약. 실제 알림은 ReminderReceiver가 처리. */
class Reminders {

    static final String ACTION = "com.ohdduck.parknote.HABIT_REMINDER";
    static final String ACTION_CHECK = "com.ohdduck.parknote.HABIT_CHECK";
    static final String EXTRA_NAME = "habit";

    /** 리마인더가 설정된 모든 습관의 알람을 다시 예약 (부팅 후, 앱 시작 시, 설정 변경 시) */
    static void scheduleAll(Context c) {
        JSONArray hs = Store.habits(c);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            String name = h.optString("n");
            int min = h.optInt("r", -1);
            if (min < 0) cancel(c, name);
            else schedule(c, name, min);
        }
    }

    /** 다음 해당 시각(오늘 또는 내일)에 알람 1회 예약. 반복은 수신 시 재예약으로 처리. */
    static void schedule(Context c, String name, int minutesOfDay) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60);
        cal.set(Calendar.MINUTE, minutesOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        // 별도 정확한 알람 권한 없이 동작한다. 절전 모드에서는 수 분 늦어질 수 있다.
        am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pending(c, name));
    }

    static void cancel(Context c, String name) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, name));
    }

    private static PendingIntent pending(Context c, String name) {
        // 습관별 data URI로 PendingIntent를 구분 — 이름 hashCode 충돌 걱정 없음
        Intent it = new Intent(c, ReminderReceiver.class)
                .setAction(ACTION)
                .setData(Uri.fromParts("parknote", "remind/" + name, null))
                .putExtra(EXTRA_NAME, name);
        return PendingIntent.getBroadcast(c, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
