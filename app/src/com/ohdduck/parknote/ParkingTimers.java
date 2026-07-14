package com.ohdduck.parknote;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/** 기록별 출차 알림 예약/해제. PendingIntent의 식별자는 항상 recordId다. */
class ParkingTimers {

    static final String ACTION = "com.ohdduck.parknote.PARKING_TIMER";
    static final String EXTRA_RECORD_ID = "record_id";
    static final String EXTRA_DUE = "due";

    static void scheduleAll(Context c) {
        JSONArray history = Store.history(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id", "");
            long due = entry.optLong("due", 0);
            if (id.isEmpty() || due <= 0) continue;
            if (due <= now) ParkingTimerReceiver.deliver(c, id, due);
            else schedule(c, id, due);
        }
    }

    static void schedule(Context c, String recordId, long due) {
        if (recordId == null || recordId.isEmpty() || due <= System.currentTimeMillis()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, recordId, due);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi);
        } catch (SecurityException e) {
            // 정확한 알람 권한이 없을 때도 타이머 자체는 동작하도록 근사 알람으로 폴백.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi);
        }
    }

    static void cancel(Context c, String recordId) {
        if (recordId == null || recordId.isEmpty()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, recordId, 0));
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel("timer:" + recordId, Store.NOTIF_ID_TIMER);
    }

    private static PendingIntent pending(Context c, String recordId, long due) {
        Intent it = new Intent(c, ParkingTimerReceiver.class)
                .setAction(ACTION)
                .setData(Uri.fromParts("parknote", "timer/" + recordId, null))
                .putExtra(EXTRA_RECORD_ID, recordId)
                .putExtra(EXTRA_DUE, due);
        return PendingIntent.getBroadcast(c, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
