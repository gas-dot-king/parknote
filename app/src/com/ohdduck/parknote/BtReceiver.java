package com.ohdduck.parknote;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
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
 * 차량 블루투스가 끊기면 = 차에서 내리면, 그 순간의 시각·좌표를 초안 기록으로 남기고
 * "어디에 대셨어요?" 알림을 띄운다. 다시 연결되면(차에 탔다) 알림을 거두고 주차를 닫는다.
 *
 * <p>알림이 뜨려면 ① 차량에 블루투스 기기가 등록돼 있고 ② 그 기기가 끊긴 기기와
 * 같아야 한다(주소로, 주소가 없으면 이름으로 — 이름은 BLUETOOTH_CONNECT가 있어야
 * 읽힌다). 하나만 어긋나도 조용히 아무 일도 일어나지 않으므로, 설정 탭의 준비 상태
 * 카드가 이 조건들을 미리 보여 주고 {@link #showParkPrompt}는 테스트 알림에서도 그대로
 * 재사용한다.
 */
public class BtReceiver extends BroadcastReceiver {

    static final String EXTRA_TEST_TOKEN = "com.ohdduck.parknote.TEST_TOKEN";
    /** 알림의 직접 입력 칸. 결과는 RemoteInput.getResultsFromIntent로 읽는다. */
    static final String KEY_REPLY_ZONE = "zone";

    /** 무시된 알림은 이 뒤에 스스로 사라진다. 밤새 주차했다가 아침에 보는 경우까지는 남긴다. */
    private static final long PROMPT_TIMEOUT_MS = 24 * 60 * 60 * 1000L;
    /** 끊긴 직후 더 나은 좌표를 기다리는 시간. 리시버는 10초 안에 끝나야 한다. */
    private static final long DISCONNECT_FIX_TIMEOUT_MS = 8000L;

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
        long now = System.currentTimeMillis();

        // 홈의 감지 상태 카드가 "시동 켜짐/꺼짐"을 여기서 읽는다. 알림을 띄우기
        // 전에 적어 둬야 알림 경로가 어디서 빠져나가도 상태는 최신으로 남는다.
        Store.setBtState(ctx, vehicleId, connected);

        if (connected) {
            // 차에 탔다. 방금 띄운 알림은 오탐이거나 이미 지난 일이고, 주차는 끝났다.
            Notify.cancelPark(ctx, vehicleId);
            Store.endParking(ctx, vehicleId, now);
            return;
        }

        // 끊긴 순간의 좌표와 시각을 초안으로 고정한다. 사용자가 알림을 무시해도, 나중에
        // 어느 경로로 구역을 적어도 "언제 어디쯤에서 내렸는지"는 남는다.
        boolean canLocate = Nearby.hasPermission(ctx);
        Location fix = canLocate ? Nearby.lastFix(ctx) : null;
        Nearby.Where where = Nearby.locate(ctx);
        String profileId = where.profile == null
                ? Store.activeProfileId(ctx) : where.profile.optString("id");
        String draftId = Store.startParking(ctx, profileId, vehicleId, fix, now);
        showParkPrompt(ctx, vehicle, profileId, where, draftId, fix, now, null);

        // 캐시가 없거나 낡았거나 흐리면 8초만 새 좌표를 기다린다. 내비를 안 켜고 온 날은
        // 캐시가 비어 있기 일쑤다. 지상 주차장이면 대개 이 안에 잡히고, 지하면 캐시로 남는다.
        // 주차당 한 번이라 배터리 영향은 없다시피 하다.
        if (draftId != null && canLocate && Nearby.wantsBetterFix(fix)) {
            PendingResult result = goAsync();
            Nearby.requestFix(ctx, DISCONNECT_FIX_TIMEOUT_MS, better -> {
                if (better != null) Store.improveLocation(ctx, draftId, better);
                result.finish();
            });
        }
    }

    /**
     * "어디에 대셨어요?" 알림을 띄운다 — 설정 탭의 테스트 알림 경로.
     *
     * <p>브로드캐스트 수신과 테스트 알림이 같은 코드를 쓴다. 테스트가 다른 경로를 타면
     * "테스트는 되는데 실제로는 안 온다"를 구별하지 못한다. 초안은 만들지 않으므로 버튼은
     * 새 기록을 만든다. 토큰은 SettingsTab이 같은 tag/id의 예전 알림을 이번 테스트
     * 성공으로 착각하지 않게 하는 1회용 표식이다.
     */
    static void showParkPrompt(Context ctx, JSONObject vehicle, String testToken) {
        Nearby.Where where = Nearby.locate(ctx);
        String profileId = where.profile == null
                ? Store.activeProfileId(ctx) : where.profile.optString("id");
        showParkPrompt(ctx, vehicle, profileId, where, null, Nearby.lastFix(ctx),
                System.currentTimeMillis(), testToken);
    }

    private static void showParkPrompt(Context ctx, JSONObject vehicle, String profileId,
                                       Nearby.Where where, String draftId, Location parkingFix,
                                       long eventTime, String testToken) {
        NotificationManager nm = Notify.manager(ctx);
        if (nm == null || vehicle == null) return;
        Notify.ensureChannels(ctx);

        String vehicleId = vehicle.optString("id");
        String car = vehicle.optString("n", ctx.getString(R.string.vehicle_default_name));
        String brand = ctx.getString(R.string.app_name);
        String profileName = Store.profileName(ctx, profileId);

        // 본문 탭: 초안이 있으면 잠금 화면 위 빠른 입력, 없으면(테스트) 앱.
        Intent openIntent = draftId == null
                ? new Intent(ctx, MainActivity.class)
                        .setData(Uri.fromParts("parknote", "bt/" + vehicleId, null))
                        .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, profileId)
                        .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, vehicleId)
                : new Intent(ctx, QuickRecordActivity.class)
                        .setData(Uri.fromParts("parknote", "quick/" + draftId, null))
                        .putExtra(QuickRecordActivity.EXTRA_RECORD_ID, draftId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 위치를 알면 그 주차장 기준으로 묻고, 모르는 곳이면 조용한 채널로 내린다.
        // 판정에 실패했을 때는 평소대로 알린다 — 놓치는 쪽이 훨씬 손해다.
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

        // 자주 대는 구역 버튼 두 개 + 직접 입력. 위젯과 같은 RECORD 브로드캐스트를 재사용한다.
        // 구역별 data URI로 PendingIntent를 구분하고, requestCode는 위젯(0~5, 100)과
        // 겹치지 않는 200번대를 쓴다. 초안이 있으면 Store가 새 기록 대신 초안을 채운다.
        String[] zones = Store.topZones(ctx, profileId, vehicleId, 2);
        for (int i = 0; i < zones.length; i++) {
            Intent it = recordIntent(ctx, "zone/" + vehicleId + "/" + profileId + "/" + i,
                    profileId, vehicleId, parkingFix, eventTime)
                    .putExtra(ParkWidgetProvider.EXTRA_ZONE, zones[i]);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif), zones[i],
                    PendingIntent.getBroadcast(ctx, 200 + i, it,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build());
        }
        // 직접 입력: 알림에서 바로 타이핑한다. 키보드의 마이크로 말해도 된다. 버튼 두 개에
        // 없는 구역이라고 앱을 열 필요가 없다. 시스템이 입력값을 실어야 하므로 이 하나만 mutable.
        Intent reply = recordIntent(ctx, "reply/" + vehicleId + "/" + profileId,
                profileId, vehicleId, parkingFix, eventTime);
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        nb.addAction(new Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_notif),
                ctx.getString(R.string.bt_reply_label),
                PendingIntent.getBroadcast(ctx, 210, reply,
                        PendingIntent.FLAG_UPDATE_CURRENT | mutable))
                .addRemoteInput(new RemoteInput.Builder(KEY_REPLY_ZONE)
                        .setLabel(ctx.getString(R.string.bt_reply_hint))
                        .build())
                .build());

        nm.notify(Notify.parkTag(vehicleId), Notify.ID_PARK, nb.build());
    }

    private static Intent recordIntent(Context ctx, String path, String profileId,
                                       String vehicleId, Location parkingFix, long eventTime) {
        Intent it = new Intent(ctx, ParkWidgetProvider.class)
                .setAction(ParkWidgetProvider.ACTION_RECORD)
                .setData(Uri.fromParts("parknote", path, null))
                .putExtra(ParkWidgetProvider.EXTRA_PROFILE_ID, profileId)
                .putExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID, vehicleId);
        ParkWidgetProvider.putLocationSnapshot(it, parkingFix, eventTime);
        return it;
    }
}
