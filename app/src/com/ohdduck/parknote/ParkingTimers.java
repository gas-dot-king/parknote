package com.ohdduck.parknote;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/** 기록별 출차 알림 예약/해제. 절전 상태에서는 알림이 다소 늦어질 수 있다. */
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
        PendingIntent pi = pending(c, recordId, due, PendingIntent.FLAG_UPDATE_CURRENT);
        // Google Play의 제한된 정확한 알람 권한을 요청하지 않는다. 출차 알림은 배터리 정책에
        // 따라 조금 늦을 수 있지만, 앱이 절전 상태여도 가능한 한 빨리 전달된다.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi);
    }

    static void cancel(Context c, String recordId) {
        if (recordId == null || recordId.isEmpty()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        // NO_CREATE: 없던 PendingIntent를 취소하려고 만들었다 지우지 않는다.
        // PendingIntent의 동일성은 data URI로 정해지므로 due 값은 아무거나 넣어도 같은 것을 찾는다.
        PendingIntent pi = pending(c, recordId, 0, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            if (am != null) am.cancel(pi);
            pi.cancel();
        }
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel("timer:" + recordId, Store.NOTIF_ID_TIMER);
    }

    private static PendingIntent pending(Context c, String recordId, long due, int flags) {
        Intent it = new Intent(c, ParkingTimerReceiver.class)
                .setAction(ACTION)
                .setData(Uri.fromParts("parknote", "timer/" + recordId, null))
                .putExtra(EXTRA_RECORD_ID, recordId)
                .putExtra(EXTRA_DUE, due);
        return PendingIntent.getBroadcast(c, 0, it, flags | PendingIntent.FLAG_IMMUTABLE);
    }
}
