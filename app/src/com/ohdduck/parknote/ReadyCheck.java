package com.ohdduck.parknote;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "자동 기록이 실제로 동작할 상태인가"를 한 번에 판정한다.
 *
 * <p>이 앱의 자동 알림은 조건 네댓 개가 전부 맞아야 뜬다. 하나라도 어긋나면 알림이
 * 조용히 안 뜨는데, 사용자 입장에서는 앱이 그냥 고장 난 것처럼 보인다. 특히 배터리
 * 최적화는 지금까지 README의 "폰에 설치 후 할 일 4번"으로만 안내하고 있었다 —
 * 앱을 설치한 사람이 README를 읽을 이유가 없다.
 *
 * <p>판정만 하고 화면은 그리지 않는다. 그리는 쪽은 {@link SettingsTab}이다.
 */
class ReadyCheck {

    /** 항목의 상태. 화면은 이 셋만 구분해 그리면 된다. */
    enum State {
        /** 조건이 맞다. */
        OK,
        /** 지금 이대로면 자동 기록이 안 된다. 사용자가 손봐야 한다. */
        ACTION_NEEDED,
        /**
         * 이 기능을 안 쓰기로 해서 조건이 필요 없다.
         *
         * <p>경고가 아니다. 위치로 알림 조절은 기본이 꺼짐이라, 꺼 둔 사람에게
         * 위치 권한을 "확인 필요"로 띄우면 멀쩡한 앱이 빨간불 투성이로 보인다.
         */
        NOT_USED
    }

    /** 항목을 누르거나 '설정하기'를 눌렀을 때 무엇을 열지 고르는 식별자. */
    enum Action {
        NOTIFICATIONS, BLUETOOTH, BATTERY, LOCATION
    }

    static class Item {
        final int titleRes;
        final State state;
        /** 상태 밑에 붙는 짧은 설명. */
        final String detail;
        /** null이면 손볼 게 없어 버튼을 달지 않는다. */
        final Action action;

        Item(int titleRes, State state, String detail, Action action) {
            this.titleRes = titleRes;
            this.state = state;
            this.detail = detail;
            this.action = action;
        }
    }

    private ReadyCheck() {
    }

    /**
     * 위에서부터 중요한 순서로 돌려준다.
     *
     * <p>알림이 맨 위인 이유: 나머지가 전부 맞아도 알림 권한이 없으면 사용자가 보는
     * 결과는 "아무 일도 안 일어남"으로 똑같다. 블루투스 등록이 그다음인데, 이름이
     * 비어 있으면 감지 자체가 시작되지 않기 때문이다.
     */
    static List<Item> all(Context c) {
        List<Item> items = new ArrayList<>();
        items.add(notifications(c));
        items.add(bluetooth(c));
        items.add(battery(c));
        items.add(locationForeground(c));
        items.add(locationBackground(c));
        return items;
    }

    // ---------- 개별 판정 ----------

    /** 알림을 띄울 수 있는 상태인가. 테스트 알림이 먼저 묻는다. */
    static boolean canPostNotifications(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return false;

        // 앱 전체 알림은 켜고 위치 기록 채널만 끈 경우에도 실제 알림은 나오지 않는다.
        Notify.ensureChannels(c);
        if (!channelEnabled(manager, Notify.CHANNEL_PARK)) return false;
        // 위치 조절을 켜면 등록 주차장 밖의 알림은 별도 무음 채널을 쓴다. 그 채널을
        // 사용자가 껐다면 알림함에도 남지 않으므로 준비 완료로 표시하면 안 된다.
        return !Store.locationFilterOn(c) || channelEnabled(manager, Notify.CHANNEL_PARK_QUIET);
    }

    private static boolean channelEnabled(NotificationManager manager, String channelId) {
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static Item notifications(Context c) {
        // API 32 이하에도 앱 전체/채널 차단은 있으므로 런타임 권한만 보면 안 된다.
        boolean granted = canPostNotifications(c);
        return new Item(R.string.ready_notifications,
                granted ? State.OK : State.ACTION_NEEDED,
                c.getString(granted ? R.string.ready_done : R.string.ready_needed),
                granted ? null : Action.NOTIFICATIONS);
    }

    private static Item bluetooth(Context c) {
        // 기기가 등록돼 있지 않으면 자동 알림은 절대 오지 않는다. Store.vehicleMatchingBluetooth가
        // null을 돌려주고 BtReceiver가 거기서 빠져나가기 때문이다.
        // "수동 기록 전용"이라는 정당한 선택이긴 하지만, 자동 알림을 기대하는 사람에게는
        // 이게 유일한 실패 원인이므로 조용한 NOT_USED가 아니라 경고로 올린다.
        JSONObject active = Store.activeVehicle(c);
        String btName = Store.vehicleBtName(active);
        if (btName.isEmpty()) {
            return new Item(R.string.ready_bluetooth, State.ACTION_NEEDED,
                    c.getString(R.string.ready_bt_manual, Store.activeVehicleName(c)),
                    Action.BLUETOOTH);
        }
        // 주소를 저장해 둔 차량은 권한 없이도 맞춰진다. 이름만 있으면 이름을 읽을 권한이 필요하다.
        boolean canMatch = hasBluetoothPermission(c) || !Store.vehicleBtAddress(active).isEmpty();
        return new Item(R.string.ready_bluetooth,
                canMatch ? State.OK : State.ACTION_NEEDED,
                canMatch ? btName : c.getString(R.string.ready_bt_no_permission),
                Action.BLUETOOTH);
    }

    private static Item battery(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        return new Item(R.string.ready_battery,
                exempt ? State.OK : State.ACTION_NEEDED,
                c.getString(exempt ? R.string.ready_done : R.string.ready_needed),
                exempt ? null : Action.BATTERY);
    }

    private static Item locationForeground(Context c) {
        if (!Store.locationFilterOn(c)) {
            return new Item(R.string.ready_location, State.NOT_USED,
                    c.getString(R.string.ready_location_off), Action.LOCATION);
        }
        boolean granted = Nearby.hasForegroundPermission(c);
        return new Item(R.string.ready_location,
                granted ? State.OK : State.ACTION_NEEDED,
                c.getString(granted ? R.string.ready_done : R.string.ready_needed),
                granted ? null : Action.LOCATION);
    }

    private static Item locationBackground(Context c) {
        if (!Store.locationFilterOn(c)) {
            return new Item(R.string.ready_location_always, State.NOT_USED,
                    c.getString(R.string.ready_location_off), Action.LOCATION);
        }
        // 블루투스 끊김은 브로드캐스트 수신이라 앱이 백그라운드로 취급된다.
        // "앱 사용 중에만 허용"으로는 그 순간 좌표를 읽지 못한다.
        // Nearby.hasPermission은 앞 항목(앱 사용 중 권한)까지 함께 본다. API 28 이하에서
        // 앞 항목이 거부인데 이 항목만 완료로 뜨는 모순을 막는다.
        boolean granted = Nearby.hasPermission(c);
        return new Item(R.string.ready_location_always,
                granted ? State.OK : State.ACTION_NEEDED,
                c.getString(granted ? R.string.ready_done : R.string.ready_needed),
                granted ? null : Action.LOCATION);
    }

    // ---------- 손보기 ----------

    /** 항목의 '설정하기'를 눌렀을 때. 해당 시스템 화면이나 앱 다이얼로그를 연다. */
    static void run(Activity a, Action action) {
        switch (action) {
            case NOTIFICATIONS:
                openAppNotificationSettings(a);
                break;
            case BLUETOOTH:
                // 권한이 빠진 상태에서 차량 편집기만 열면 직접 입력밖에 할 수 없어
                // 자동 감지를 복구하지 못한다. 이때는 앱 권한 화면을 먼저 연다.
                if (!hasBluetoothPermission(a)) {
                    openBluetoothPermissionSettings(a);
                } else if (a instanceof ScreenHost) {
                    VehicleDialogs.showCurrentOptions(a, (ScreenHost) a);
                }
                break;
            case BATTERY:
                openBatterySettings(a);
                break;
            case LOCATION:
                LocationFilterDialogs.showMenu(a);
                break;
        }
    }

    private static void openAppNotificationSettings(Activity a) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
        if (!start(a, intent)) openAppDetails(a);
    }

    /**
     * 배터리 최적화 목록을 연다.
     *
     * <p>바로 예외를 요청하는 {@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}는
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 권한이 필요하고 Play 정책 심사 대상이다.
     * 목록을 열어 사용자가 직접 고르게 하면 권한도 심사도 필요 없다.
     */
    private static void openBatterySettings(Activity a) {
        if (start(a, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
        openAppDetails(a);
    }

    static boolean hasBluetoothPermission(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** 준비 카드와 블루투스 선택기가 공유하는 권한 복구 진입점. */
    static void openBluetoothPermissionSettings(Activity a) {
        openAppDetails(a);
    }

    /** 어느 전용 화면도 없는 기기를 위한 마지막 수단: 앱 정보 화면. */
    private static void openAppDetails(Activity a) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", a.getPackageName(), null));
        if (!start(a, intent)) {
            Toast.makeText(a, R.string.location_settings_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean start(Activity a, Intent intent) {
        try {
            a.startActivity(intent);
            return true;
        } catch (Exception e) {
            // 제조사에 따라 해당 화면이 없는 기기가 있다. 앱이 죽는 것보다 낫다.
            return false;
        }
    }
}
