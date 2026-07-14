package com.ohdduck.parknote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;

import org.json.JSONObject;

/**
 * 습관 리마인더: 예약 시각에 아직 체크 안 했으면 알림. 부팅 후 알람 재예약과
 * 알림의 "지금 체크" 버튼 처리도 담당.
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Reminders.scheduleAll(ctx); // 재부팅하면 알람이 사라지므로 다시 예약
            ParkingTimers.scheduleAll(ctx);
            return;
        }

        if (Reminders.ACTION_CHECK.equals(action)) { // 알림의 "지금 체크" 버튼
            String checkName = intent.getStringExtra(Reminders.EXTRA_NAME);
            if (checkName == null) return;
            Store.checkTodayByName(ctx, checkName);
            NotificationManager m =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (m != null) m.cancel(checkName, Store.NOTIF_ID_HABIT);
            return;
        }

        if (!Reminders.ACTION.equals(action)) return;

        String name = intent.getStringExtra(Reminders.EXTRA_NAME);
        if (name == null) return;
        JSONObject h = Store.habitByName(ctx, name);
        if (h == null) return; // 삭제된 항목의 잔여 알람
        int r = h.optInt("r", -1);
        if (r < 0) return;     // 리마인더가 해제된 항목

        Reminders.schedule(ctx, name, r); // 내일 알람 예약

        if (Store.checkedToday(h)) return; // 이미 했으면 조용히

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String brand = ctx.getString(R.string.app_name);
        nm.createNotificationChannel(new NotificationChannel(
                Store.CHANNEL_HABIT, brand + " · 체크 알림",
                NotificationManager.IMPORTANCE_HIGH));

        PendingIntent open = PendingIntent.getActivity(
                ctx, 1, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 앱을 열지 않고 알림에서 바로 체크. 습관별 data URI로 PendingIntent를 구분.
        Intent check = new Intent(ctx, ReminderReceiver.class)
                .setAction(Reminders.ACTION_CHECK)
                .setData(Uri.fromParts("parknote", "check/" + name, null))
                .putExtra(Reminders.EXTRA_NAME, name);
        PendingIntent checkPi = PendingIntent.getBroadcast(ctx, 3, check,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, Store.CHANNEL_HABIT)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(name)
                .setContentText(brand + " · 아직 체크하지 않았어요")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_notif),
                        "지금 체크", checkPi).build())
                .build();
        nm.notify(name, Store.NOTIF_ID_HABIT, n); // 이름을 tag로 → 습관끼리 ID 충돌 없음
    }
}
