package com.ohdduck.parknote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** 알림 채널·ID와 취소 경로. 게시는 각 리시버가 하고, 채널은 여기서 한 번에 만든다. */
final class Notify {

    static final String CHANNEL_PARK = "park_reminder";
    /** 등록한 주차장이 아닐 때 쓰는 무음 채널. 알림함에는 남지만 방해하지 않는다. */
    static final String CHANNEL_PARK_QUIET = "park_reminder_quiet";
    static final String CHANNEL_HABIT = "habit_reminder";
    static final String CHANNEL_TIMER = "parking_timer";

    static final int ID_PARK = 1;
    static final int ID_HABIT = 2;
    static final int ID_TIMER = 3;

    private Notify() {
    }

    static NotificationManager manager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * 채널 네 개를 만든다. 앱을 열 때와 알림을 게시하기 직전에 부른다.
     *
     * <p>예전에는 첫 알림을 띄우는 순간에야 채널이 생겼다. 그 전에는 시스템 설정에
     * 채널이 없어 소리를 미리 고를 수 없었고, 준비 상태 카드는 없는 채널을 "켜짐"으로
     * 셌다. 같은 ID로 다시 만들면 사용자 설정은 유지되고 이름만 최신화된다.
     */
    static void ensureChannels(Context c) {
        NotificationManager nm = manager(c);
        if (nm == null) return;
        String brand = c.getString(R.string.app_name);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PARK,
                c.getString(R.string.bt_channel, brand), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PARK_QUIET,
                c.getString(R.string.bt_channel_quiet, brand), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_TIMER,
                c.getString(R.string.timer_channel, brand), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_HABIT,
                c.getString(R.string.habit_channel, brand), NotificationManager.IMPORTANCE_HIGH));
    }

    /** 차량별 주차 알림 태그. 같은 차의 알림은 하나만 떠 있다. */
    static String parkTag(String vehicleId) {
        return "bt:" + vehicleId;
    }

    static String timerTag(String recordId) {
        return "timer:" + recordId;
    }

    static void cancelPark(Context c, String vehicleId) {
        cancel(c, parkTag(vehicleId), ID_PARK);
    }

    static void cancelTimer(Context c, String recordId) {
        cancel(c, timerTag(recordId), ID_TIMER);
    }

    /** 이미 게시된 습관 리마인더 알림을 거둔다. 항목을 지우거나 통째로 갈아 끼울 때. */
    static void cancelHabit(Context c, String name) {
        if (name == null || name.isEmpty()) return;
        cancel(c, name, ID_HABIT);
    }

    private static void cancel(Context c, String tag, int id) {
        NotificationManager nm = manager(c);
        if (nm != null) nm.cancel(tag, id);
    }
}
