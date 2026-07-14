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
            // BLUETOOTH_CONNECT 권한이 없으면 이름을 못 읽는다 → 그냥 무시
        }
        JSONObject vehicle = Store.vehicleMatchingBluetooth(ctx, name);
        if (vehicle == null) return;
        String vehicleId = vehicle.optString("id");
        String car = vehicle.optString("n", "차량");

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (connected) { // 잠깐 끊겼다 다시 붙음 → 방금 띄운 알림은 오탐
            nm.cancel("bt:" + vehicleId, Store.NOTIF_ID_BT);
            return;
        }

        String brand = ctx.getString(R.string.app_name);
        // 같은 ID로 다시 등록하면 기존 채널의 사용자 설정은 유지하면서 이름만 최신화된다.
        nm.createNotificationChannel(new NotificationChannel(
                Store.CHANNEL, brand + " · 위치 기록", NotificationManager.IMPORTANCE_HIGH));

        String profileId = Store.activeProfileId(ctx);
        String profileName = Store.activeProfileName(ctx);
        Intent openIntent = new Intent(ctx, MainActivity.class)
                .setData(Uri.fromParts("parknote", "bt/" + vehicleId, null))
                .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, profileId)
                .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, vehicleId);
        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(ctx, Store.CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(brand + " · " + car)
                .setContentText(profileName + "에 댄 곳을 남겨둘까요?")
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
