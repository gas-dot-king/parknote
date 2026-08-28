package com.ohdduck.parknote;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 주행 중 위치 추적 (설정에서 켜는 선택 기능, 기본 꺼짐).
 *
 * <p>왜 필요한가: 지하 주차장에서는 위성이 안 잡힌다. 차에서 내린 순간 쓸 수 있는 좌표는
 * "지하로 들어가기 직전에 잡힌 것"뿐인데, 내비를 안 켜고 온 날은 그런 좌표가 아예 없다.
 * 차 블루투스가 연결된 동안(= 주행 중)만 좌표를 받아 두면, 신호가 끊긴 마지막 자리가 곧
 * 주차장 입구다. {@link BtReceiver}가 시동(연결)에 켜고 끊김에 끈다.
 *
 * <p>비용: 주행 중 10초/25m마다 측위, 진행 중 알림 한 줄. 시동을 끄면 바로 멈춘다.
 * 끊김을 못 받았을 때를 대비해 3시간이 지나면 스스로 멈춘다.
 *
 * <p>Android 12+의 "백그라운드에서 포그라운드 서비스 시작 금지"는 블루투스 브로드캐스트가
 * 예외라 리시버에서 시작할 수 있다. 백그라운드에서 시작한 서비스가 위치를 읽으려면
 * "항상 허용"이 필요하므로 그 권한이 있을 때만 켠다. 그래도 시스템이 거절하면 조용히 포기한다 —
 * 이 기능이 없어도 앱은 예전처럼 캐시 좌표로 동작한다.
 */
public class DriveTracker extends Service {

    private static final String EXTRA_VEHICLE = "vehicle";
    private static final long INTERVAL_MS = 10_000L;
    private static final float MIN_DISTANCE_M = 25f;
    private static final long MAX_DRIVE_MS = 3 * 60 * 60 * 1000L;
    /** 이보다 흐린 좌표는 입구 후보로 안 친다. 지하 진입 직후 기지국으로 잡힌 수백 m 좌표를 걸러낸다. */
    private static final float MAX_ACCURACY_M = 100f;

    /** 추적 중 마지막으로 본 쓸 만한 좌표. 리시버가 끊김 순간에 가져간다. */
    private static volatile Location lastGood;

    private Nearby.FixRequest updates;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelf;

    static void start(Context c, String vehicleName) {
        Intent intent = new Intent(c, DriveTracker.class).putExtra(EXTRA_VEHICLE, vehicleName);
        try {
            c.startForegroundService(intent);
        } catch (RuntimeException e) {
            // API 34: 시스템이 백그라운드 시작을 거절한 경우(ForegroundServiceStartNotAllowedException).
            // 기능 없이도 앱은 동작한다.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, DriveTracker.class));
    }

    /** 마지막 쓸 만한 좌표를 넘기고 비운다. 다음 주차에 이번 좌표가 섞이지 않게. */
    static Location takeLastFix() {
        Location fix = lastGood;
        lastGood = null;
        return fix;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String vehicle = intent == null ? null : intent.getStringExtra(EXTRA_VEHICLE);
        if (vehicle == null) vehicle = getString(R.string.vehicle_default_name);
        Notify.ensureChannels(this);
        Notification notification = new Notification.Builder(this, Notify.CHANNEL_DRIVE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.drive_notification_title, vehicle))
                .setContentText(getString(R.string.drive_notification_text))
                .setContentIntent(PendingIntent.getActivity(this, 5,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notify.ID_DRIVE, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(Notify.ID_DRIVE, notification);
            }
        } catch (RuntimeException e) {
            // 권한이 빠졌거나(SecurityException) 시작이 허용되지 않은 상태. 조용히 접는다.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Nearby.hasPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (updates == null) {
            updates = Nearby.requestUpdates(this, INTERVAL_MS, MIN_DISTANCE_M, fix -> {
                float accuracy = Nearby.accuracyOf(fix);
                if (fix != null && accuracy >= 0 && accuracy <= MAX_ACCURACY_M) lastGood = fix;
            });
            handler.postDelayed(timeout, MAX_DRIVE_MS);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (updates != null) updates.cancel();
        handler.removeCallbacks(timeout);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
