package com.ohdduck.parknote;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/** 출차 타이머가 끝났을 때 현재 기록을 다시 확인한 뒤 알림을 표시한다. */
public class ParkingTimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!ParkingTimers.ACTION.equals(intent.getAction())) return;
        String recordId = intent.getStringExtra(ParkingTimers.EXTRA_RECORD_ID);
        long expectedDue = intent.getLongExtra(ParkingTimers.EXTRA_DUE, 0);
        if (recordId == null || expectedDue <= 0) return;

        deliver(ctx, recordId, expectedDue);
    }

    /** 부팅/재개 시 이미 지난 타이머도 같은 검증·알림 경로로 처리한다. */
    static void deliver(Context ctx, String recordId, long expectedDue) {
        JSONObject entry = Store.recordById(ctx, recordId);
        if (entry == null) return; // 삭제된 기록의 남은 알람
        long currentDue = entry.optLong("due", 0);
        if (currentDue != expectedDue || currentDue <= 0) return; // 수정/해제된 옛 알람
        if (currentDue > System.currentTimeMillis()) {
            ParkingTimers.schedule(ctx, recordId, currentDue); // 너무 이른 수신은 다시 예약
            return;
        }

        // 같은 타이머가 재부팅 뒤 다시 예약되지 않도록 기록에서 먼저 해제한다.
        Store.clearParkingTimer(ctx, recordId);

        NotificationManager nm = Notify.manager(ctx);
        if (nm == null) return;
        Notify.ensureChannels(ctx);
        String brand = ctx.getString(R.string.app_name);

        Intent openIntent = new Intent(ctx, MainActivity.class)
                .setData(Uri.fromParts("parknote", "record/" + recordId, null))
                .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, entry.optString("p", ""))
                .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, entry.optString("c", ""))
                .putExtra(MainActivity.EXTRA_EDIT_RECORD_ID, recordId);
        PendingIntent open = PendingIntent.getActivity(ctx, 30, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String zone = entry.optString("z", ctx.getString(R.string.record_label_zone));
        String detail = Store.recordProfileName(ctx, entry) + " · " + zone;
        String memo = Store.recordMemo(entry);
        if (!memo.isEmpty()) detail += " · " + memo;
        Notification notification = new Notification.Builder(ctx, Notify.CHANNEL_TIMER)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(ctx.getString(R.string.timer_notification_title, brand))
                .setContentText(detail)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(Notify.timerTag(recordId), Notify.ID_TIMER, notification);
    }
}
