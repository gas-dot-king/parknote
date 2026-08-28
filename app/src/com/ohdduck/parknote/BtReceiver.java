package com.ohdduck.parknote;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * 차량 블루투스가 끊기면 = 차에서 내리면 "주차 위치 기록" 알림을 띄운다.
 * 다시 연결되면(순간 끊김 오탐, 재승차) 알림을 거둔다.
 * 알림의 최근 구역 버튼으로 앱을 열지 않고 바로 기록할 수 있다.
 *
 * <p>알림이 뜨려면 ① 차량에 블루투스 기기가 등록돼 있고 ② 그 기기가 끊긴 기기와
 * 같아야 한다(주소로, 주소가 없으면 이름으로 — 이름은 BLUETOOTH_CONNECT가 있어야
 * 읽힌다). 하나만 어긋나도 조용히 아무 일도 일어나지 않으므로, 설정 탭의 준비 상태
 * 카드가 이 조건들을 미리 보여 주고 {@link #showParkPrompt}는 테스트 알림에서도 그대로
 * 재사용한다.
 */
public class BtReceiver extends BroadcastReceiver {

    static final String EXTRA_TEST_TOKEN = "com.ohdduck.parknote.TEST_TOKEN";

    /** 무시된 알림은 이 뒤에 스스로 사라진다. 밤새 주차했다가 아침에 보는 경우까지는 남긴다. */
    private static final long PROMPT_TIMEOUT_MS = 24 * 60 * 60 * 1000L;

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        if (!connected && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) return;

        BluetoothDevice dev = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (dev == null) return;
        String name = null;
        try {
            name = dev.getName();
        } catch (SecurityException ignored) {
            // BLUETOOTH_CONNECT 권한이 없으면 이름을 못 읽는다. 주소는 권한 없이 읽히므로
            // 주소를 저장해 둔 차량은 그래도 맞춰진다.
        }
        JSONObject vehicle = Store.vehicleMatchingBluetooth(ctx, name, dev.getAddress());
        if (vehicle == null) return;
        String vehicleId = vehicle.optString("id");

        // 홈의 감지 상태 카드가 "시동 켜짐/꺼짐"을 여기서 읽는다. 알림을 띄우기
        // 전에 적어 둬야 알림 경로가 어디서 빠져나가도 상태는 최신으로 남는다.
        Store.setBtState(ctx, vehicleId, connected);

        if (connected) { // 잠깐 끊겼다 다시 붙음 → 방금 띄운 알림은 오탐
            Notify.cancelPark(ctx, vehicleId);
            return;
        }
        // 끊긴 순간의 좌표와 시각을 고정한다. 사용자는 걸어간 뒤에 버튼을 누를 수 있고,
        // 그때 다시 읽으면 엉뚱한 자리가 저장된다. 주차 시각도 버튼을 누른 순간이 아니라
        // 차에서 내린 순간이다.
        Location parkingFix = Nearby.hasPermission(ctx) ? Nearby.lastFix(ctx) : null;
        showParkPrompt(ctx, vehicle, parkingFix, System.currentTimeMillis(), null);
    }

    /**
     * "어디에 대셨어요?" 알림을 띄운다 — 설정 탭의 테스트 알림 경로.
     *
     * <p>브로드캐스트 수신과 테스트 알림이 같은 코드를 쓴다. 테스트가 다른 경로를 타면
     * "테스트는 되는데 실제로는 안 온다"를 구별하지 못한다. 토큰은 SettingsTab이
     * 같은 tag/id의 예전 알림을 이번 테스트 성공으로 착각하지 않게 하는 1회용 표식이다.
     */
    static void showParkPrompt(Context ctx, JSONObject vehicle, String testToken) {
        showParkPrompt(ctx, vehicle, Nearby.lastFix(ctx), System.currentTimeMillis(), testToken);
    }

    private static void showParkPrompt(Context ctx, JSONObject vehicle, Location parkingFix,
                                       long eventTime, String testToken) {
        NotificationManager nm = Notify.manager(ctx);
        if (nm == null || vehicle == null) return;
        Notify.ensureChannels(ctx);

        String vehicleId = vehicle.optString("id");
        String car = vehicle.optString("n", ctx.getString(R.string.vehicle_default_name));
        String brand = ctx.getString(R.string.app_name);

        // 위치를 알면 그 주차장 기준으로 묻고, 모르는 곳이면 조용한 채널로 내린다.
        // 판정에 실패했을 때는 평소대로 알린다 — 놓치는 쪽이 훨씬 손해다.
        Nearby.Where where = Nearby.locate(ctx);
        String profileId = Store.activeProfileId(ctx);
        if (where.profile != null) profileId = where.profile.optString("id", profileId);
        String profileName = Store.profileName(ctx, profileId);

        Intent openIntent = new Intent(ctx, MainActivity.class)
                .setData(Uri.fromParts("parknote", "bt/" + vehicleId, null))
                .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, profileId)
                .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, vehicleId);
        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(ctx,
                where.shouldAlert() ? Notify.CHANNEL_PARK : Notify.CHANNEL_PARK_QUIET)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(ctx.getString(R.string.bt_notification_title, brand, car))
                .setContentText(where.shouldAlert()
                        ? ctx.getString(R.string.bt_notification_known, profileName)
                        : ctx.getString(R.string.bt_notification_elsewhere))
                .setContentIntent(open)
                // 알림의 시각 = 차에서 내린 시각. "언제 내렸는지"가 알림에서 바로 보인다.
                .setWhen(eventTime)
                .setShowWhen(true)
                .setTimeoutAfter(PROMPT_TIMEOUT_MS)
                .setAutoCancel(true);
        if (testToken != null && !testToken.isEmpty()) {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_TEST_TOKEN, testToken);
            nb.addExtras(extras);
        }

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
            ParkWidgetProvider.putLocationSnapshot(it, parkingFix, eventTime);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif), zones[i],
                    PendingIntent.getBroadcast(ctx, 200 + i, it,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build());
        }

        nm.notify(Notify.parkTag(vehicleId), Notify.ID_PARK, nb.build());
    }
}
