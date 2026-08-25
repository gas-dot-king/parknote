package com.ohdduck.parknote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

/**
 * 차량 블루투스가 끊기면 = 차에서 내리면 "주차 위치 기록" 알림을 띄운다.
 * 다시 연결되면(순간 끊김 오탐, 재승차) 알림을 거둔다.
 * 알림의 최근 구역 버튼으로 앱을 열지 않고 바로 기록할 수 있다.
 *
 * <p>알림이 뜨려면 조건 셋이 전부 맞아야 한다. ① 차량에 블루투스 이름이 등록돼
 * 있고, ② 그 이름이 끊긴 기기 이름과 정확히 같고, ③ BLUETOOTH_CONNECT 권한이 있어
 * 기기 이름을 읽을 수 있어야 한다. 셋 중 하나만 어긋나도 조용히 아무 일도 일어나지
 * 않으므로, 설정 탭의 준비 상태 카드가 이 조건들을 미리 보여 주고
 * {@link #showParkPrompt}는 테스트 알림에서도 그대로 재사용한다.
 */
public class BtReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        if (!connected && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) return;

        BluetoothDevice dev = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String name = null;
        try {
            if (dev != null) name = dev.getName();
        } catch (SecurityException ignored) {
            // BLUETOOTH_CONNECT 권한이 없으면 이름을 못 읽는다 → 어느 차인지 알 수
            // 없으므로 무시한다. 권한이 빠진 상태는 준비 상태 카드가 잡아 준다.
        }
        JSONObject vehicle = Store.vehicleMatchingBluetooth(ctx, name);
        if (vehicle == null) return;
        String vehicleId = vehicle.optString("id");

        // 홈의 감지 상태 카드가 "시동 켜짐/꺼짐"을 여기서 읽는다. 알림을 띄우기
        // 전에 적어 둬야 알림 경로가 어디서 빠져나가도 상태는 최신으로 남는다.
        Store.setBtState(ctx, vehicleId, connected);

        if (connected) { // 잠깐 끊겼다 다시 붙음 → 방금 띄운 알림은 오탐
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel("bt:" + vehicleId, Store.NOTIF_ID_BT);
            return;
        }
        showParkPrompt(ctx, vehicle);
    }

    /**
     * "어디에 대셨어요?" 알림을 띄운다.
     *
     * <p>브로드캐스트 수신과 설정 탭의 테스트 알림이 같은 코드를 쓴다. 테스트가
     * 다른 경로를 타면 "테스트는 되는데 실제로는 안 온다"를 구별하지 못한다.
     */
    static void showParkPrompt(Context ctx, JSONObject vehicle) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || vehicle == null) return;

        String vehicleId = vehicle.optString("id");
        String car = vehicle.optString("n", ctx.getString(R.string.vehicle_default_name));
        String brand = ctx.getString(R.string.app_name);

        // 같은 ID로 다시 등록하면 기존 채널의 사용자 설정은 유지하면서 이름만 최신화된다.
        nm.createNotificationChannel(new NotificationChannel(
                Store.CHANNEL, ctx.getString(R.string.bt_channel, brand),
                NotificationManager.IMPORTANCE_HIGH));

        // 위치를 알면 그 주차장 기준으로 묻고, 모르는 곳이면 조용한 채널로 내린다.
        // 판정에 실패했을 때는 평소대로 알린다 — 놓치는 쪽이 훨씬 손해다.
        Nearby.Where where = Nearby.locate(ctx);
        String profileId = Store.activeProfileId(ctx);
        if (where.profile != null) profileId = where.profile.optString("id", profileId);

        String channel = Store.CHANNEL;
        if (!where.shouldAlert()) {
            channel = Store.CHANNEL_QUIET;
            nm.createNotificationChannel(new NotificationChannel(
                    channel, ctx.getString(R.string.bt_channel_quiet, brand),
                    NotificationManager.IMPORTANCE_LOW));
        }
        String profileName = Store.profileName(ctx, profileId);
        Intent openIntent = new Intent(ctx, MainActivity.class)
                .setData(Uri.fromParts("parknote", "bt/" + vehicleId, null))
                .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, profileId)
                .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, vehicleId);
        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(ctx.getString(R.string.bt_notification_title, brand, car))
                .setContentText(where.shouldAlert()
                        ? ctx.getString(R.string.bt_notification_known, profileName)
                        : ctx.getString(R.string.bt_notification_elsewhere))
                .setContentIntent(open)
                .setAutoCancel(true);

        // 최근 구역 버튼: 위젯과 같은 RECORD 브로드캐스트 재사용.
        // 구역별 data URI로 PendingIntent를 구분하고, requestCode는 위젯(0~5, 100)과
        // 겹치지 않는 200번대를 쓴다.
        String[] zones = Store.recentZones(ctx, profileId, vehicleId, 3);
        for (int i = 0; i < zones.length; i++) {
            Intent it = new Intent(ctx, ParkWidgetProvider.class)
                    .setAction(ParkWidgetProvider.ACTION_RECORD)
                    .setData(Uri.fromParts("parknote",
                            "zone/" + vehicleId + "/" + profileId + "/" + i, null))
                    .putExtra(ParkWidgetProvider.EXTRA_ZONE, zones[i])
                    .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, profileId)
                    .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, vehicleId);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif), zones[i],
                    PendingIntent.getBroadcast(ctx, 200 + i, it,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build());
        }

        nm.notify("bt:" + vehicleId, Store.NOTIF_ID_BT, nb.build());
    }
}
