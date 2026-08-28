package com.ohdduck.parknote;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "자동 기록이 실제로 동작할 상태인가"와 "더 편해지려면 무엇이 남았나"를 한 번에 판정한다.
 *
 * <p>이 앱의 자동 알림은 조건 네댓 개가 전부 맞아야 뜬다. 하나라도 어긋나면 알림이
 * 조용히 안 뜨는데, 사용자 입장에서는 앱이 그냥 고장 난 것처럼 보인다. 그 조건들에
 * 위치 권한·위젯·타일처럼 "없어도 되지만 있으면 훨씬 편한" 항목을 더해 하나의 목록으로 둔다.
 *
 * <p>판정만 하고 화면은 그리지 않는다. 설정 탭({@link SettingsTab})은 전체 목록을,
 * 홈의 준비 카드({@link MainActivity})는 {@link #nextPending}이 고른 다음 한 가지를 그린다.
 * 온보딩 완료 화면도 같은 판정을 쓴다.
 */
class ReadyCheck {

    /** 항목의 상태. 화면은 이 셋만 구분해 그리면 된다. */
    enum State {
        /** 조건이 맞다. */
        OK,
        /** 지금 이대로면 자동 기록이 안 되거나, 손보면 확실히 편해진다. */
        ACTION_NEEDED,
        /**
         * 지금은 해당 없다. 경고가 아니다.
         *
         * <p>"항상 허용"은 앱 사용 중 위치를 먼저 받아야 물을 수 있고, 주행 중 추적은
         * 좌표 없는 기록이 이어질 때만 권한다. 때가 아닌 항목에 빨간불을 띄우면
         * 멀쩡한 앱이 고장 난 것처럼 보인다.
         */
        NOT_USED
    }

    /** 항목을 누르거나 '설정하기'를 눌렀을 때 무엇을 열지 고르는 식별자. 홈 카드의 "나중에" 키로도 쓴다. */
    enum Action {
        NOTIFICATIONS, BLUETOOTH, BATTERY, LOCATION, LOCATION_ALWAYS, WIDGET, TILE, DRIVE
    }

    static class Item {
        final int titleRes;
        final State state;
        /** 상태 밑에 붙는 짧은 설명. */
        final String detail;
        /** 홈 카드에 붙는 "왜 지금 필요한가" 한 줄. */
        final int reasonRes;
        final Action action;

        Item(int titleRes, State state, String detail, int reasonRes, Action action) {
            this.titleRes = titleRes;
            this.state = state;
            this.detail = detail;
            this.reasonRes = reasonRes;
            this.action = action;
        }
    }

    private ReadyCheck() {
    }

    /**
     * 위에서부터 중요한 순서로 돌려준다.
     *
     * <p>알림이 맨 위인 이유: 나머지가 전부 맞아도 알림 권한이 없으면 사용자가 보는
     * 결과는 "아무 일도 안 일어남"으로 똑같다. 블루투스 등록이 그다음인데, 기기가
     * 비어 있으면 감지 자체가 시작되지 않기 때문이다. 배터리는 삼성 등 절전이 센 폰에서
     * 리시버를 재워 버리므로 처음부터 권한다.
     */
    static List<Item> all(Context c) {
        List<Item> items = new ArrayList<>();
        items.add(notifications(c));
        items.add(bluetooth(c));
        items.add(battery(c));
        items.add(locationForeground(c));
        items.add(locationBackground(c));
        items.add(widget(c));
        items.add(tile(c));
        items.add(drive(c));
        return items;
    }

    /** 홈 카드에 올릴 다음 할 일. "나중에"로 접은 것은 건너뛴다. 없으면 null. */
    static Item nextPending(Context c) {
        Set<String> dismissed = Store.setupDismissed(c);
        for (Item item : all(c)) {
            if (item.state == State.ACTION_NEEDED && !dismissed.contains(item.action.name())) {
                return item;
            }
        }
        return null;
    }

    /** {끝난 항목, 해당되는 항목} 수. NOT_USED는 세지 않는다. */
    static int[] progress(Context c) {
        int done = 0;
        int total = 0;
        for (Item item : all(c)) {
            if (item.state == State.NOT_USED) continue;
            total++;
            if (item.state == State.OK) done++;
        }
        return new int[]{done, total};
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
                R.string.ready_why_notifications, Action.NOTIFICATIONS);
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
                    R.string.ready_why_bluetooth, Action.BLUETOOTH);
        }
        // 주소를 저장해 둔 차량은 권한 없이도 맞춰진다. 이름만 있으면 이름을 읽을 권한이 필요하다.
        boolean canMatch = hasBluetoothPermission(c) || !Store.vehicleBtAddress(active).isEmpty();
        return new Item(R.string.ready_bluetooth,
                canMatch ? State.OK : State.ACTION_NEEDED,
                canMatch ? btName : c.getString(R.string.ready_bt_no_permission),
                R.string.ready_why_bluetooth, Action.BLUETOOTH);
    }

    static boolean batteryExempt(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    private static Item battery(Context c) {
        boolean exempt = batteryExempt(c);
        return new Item(R.string.ready_battery,
                exempt ? State.OK : State.ACTION_NEEDED,
                c.getString(exempt ? R.string.ready_done : R.string.ready_needed),
                R.string.ready_why_battery, Action.BATTERY);
    }

    /** 앱 사용 중 위치. 기록마다 좌표가 붙고 위치 탭·길찾기가 전부 이 위에 서 있어 항상 권한다. */
    private static Item locationForeground(Context c) {
        boolean granted = Nearby.hasForegroundPermission(c);
        return new Item(R.string.ready_location,
                granted ? State.OK : State.ACTION_NEEDED,
                c.getString(granted ? R.string.ready_done : R.string.ready_needed),
                R.string.ready_why_location, Action.LOCATION);
    }

    /**
     * 항상 허용. 앱 사용 중 위치를 먼저 받고, 차량 블루투스 이벤트를 한 번이라도 본 뒤에 묻는다 —
     * "내리는 순간에도"라는 이유가 그때부터 와 닿는다. 블루투스 없는 차량이면 해당 없음.
     */
    private static Item locationBackground(Context c) {
        boolean applicable = Nearby.hasForegroundPermission(c)
                && !Store.vehicleBtName(Store.activeVehicle(c)).isEmpty()
                && Store.btState(c) != null;
        if (!applicable) {
            return new Item(R.string.ready_location_always, State.NOT_USED,
                    c.getString(R.string.ready_location_later), R.string.ready_why_location_always,
                    Action.LOCATION_ALWAYS);
        }
        // Nearby.hasPermission은 앞 항목(앱 사용 중 권한)까지 함께 본다.
        boolean granted = Nearby.hasPermission(c);
        return new Item(R.string.ready_location_always,
                granted ? State.OK : State.ACTION_NEEDED,
                c.getString(granted ? R.string.ready_done : R.string.ready_needed),
                R.string.ready_why_location_always, Action.LOCATION_ALWAYS);
    }

    static boolean widgetPlaced(Context c) {
        int[] ids = AppWidgetManager.getInstance(c)
                .getAppWidgetIds(new ComponentName(c, ParkWidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    static boolean canPinWidget(Context c) {
        return AppWidgetManager.getInstance(c).isRequestPinAppWidgetSupported();
    }

    private static Item widget(Context c) {
        if (widgetPlaced(c)) {
            return new Item(R.string.ready_widget, State.OK, c.getString(R.string.ready_done),
                    R.string.ready_why_widget, Action.WIDGET);
        }
        // 런처가 핀 요청을 못 받으면 손으로 놓는 수밖에 없다. 그건 여기서 권할 일이 아니다.
        return new Item(R.string.ready_widget,
                canPinWidget(c) ? State.ACTION_NEEDED : State.NOT_USED,
                c.getString(canPinWidget(c) ? R.string.ready_needed : R.string.ready_widget_manual),
                R.string.ready_why_widget, Action.WIDGET);
    }

    /** 퀵설정 타일. 앱이 추가를 요청하는 API는 Android 13부터 있다. 그 아래는 손으로. */
    private static Item tile(Context c) {
        if (Store.tileAdded(c)) {
            return new Item(R.string.ready_tile, State.OK, c.getString(R.string.ready_done),
                    R.string.ready_why_tile, Action.TILE);
        }
        boolean canAsk = Build.VERSION.SDK_INT >= 33;
        return new Item(R.string.ready_tile,
                canAsk ? State.ACTION_NEEDED : State.NOT_USED,
                c.getString(canAsk ? R.string.ready_needed : R.string.ready_tile_manual),
                R.string.ready_why_tile, Action.TILE);
    }

    /**
     * 주행 중 위치 추적. 좌표 없는 기록이 이어질 때(= 지하 주차장 정황)만 권한다.
     * 항상 허용이 있어야 동작하므로 그 전에는 해당 없음.
     */
    private static Item drive(Context c) {
        if (Store.driveTrackingOn(c)) {
            return new Item(R.string.ready_drive, State.OK, c.getString(R.string.state_on),
                    R.string.ready_why_drive, Action.DRIVE);
        }
        boolean suggest = Nearby.hasPermission(c)
                && Store.recentRecordsWithoutCoords(c, 5) >= 2;
        return new Item(R.string.ready_drive,
                suggest ? State.ACTION_NEEDED : State.NOT_USED,
                c.getString(suggest ? R.string.ready_needed : R.string.ready_location_off),
                R.string.ready_why_drive, Action.DRIVE);
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
                LocationFilterDialogs.requestLocation(a);
                break;
            case LOCATION_ALWAYS:
                LocationFilterDialogs.requestAlwaysLocation(a);
                break;
            case WIDGET:
                requestPinWidget(a);
                break;
            case TILE:
                requestAddTile(a);
                break;
            case DRIVE:
                LocationFilterDialogs.showDriveTracking(a);
                break;
        }
    }

    /**
     * 런처에 위젯을 놓아 달라고 요청한다. 시스템 확인 다이얼로그 한 번으로 끝난다.
     * 예전에는 "홈 화면 길게 누르기 → 위젯 → 찾기"를 README로만 안내했다.
     */
    static boolean requestPinWidget(Activity a) {
        AppWidgetManager manager = AppWidgetManager.getInstance(a);
        if (!manager.isRequestPinAppWidgetSupported()) {
            Toast.makeText(a, R.string.ready_widget_manual, Toast.LENGTH_LONG).show();
            return false;
        }
        return manager.requestPinAppWidget(new ComponentName(a, ParkWidgetProvider.class),
                null, null);
    }

    /** 퀵설정 타일 추가 요청 (Android 13+). 이미 있거나 방금 추가됐으면 완료로 적는다. */
    static void requestAddTile(Activity a) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(a, R.string.ready_tile_manual, Toast.LENGTH_LONG).show();
            return;
        }
        StatusBarManager bar = a.getSystemService(StatusBarManager.class);
        if (bar == null) return;
        bar.requestAddTileService(new ComponentName(a, ParkTileService.class),
                a.getString(R.string.app_name), Icon.createWithResource(a, R.drawable.ic_notif),
                a.getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Store.setTileAdded(a, true);
                        if (a instanceof ScreenHost && !a.isFinishing()) {
                            ((ScreenHost) a).refresh(false);
                        }
                    }
                });
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
    static void openBatterySettings(Activity a) {
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

    /** 어느 전용 화면도 없는 기기를 위한 마지막 수단, 그리고 권한을 두 번 거부한 뒤의 유일한 길: 앱 정보 화면. */
    static void openAppDetails(Activity a) {
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
