package com.ohdduck.parknote;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * 설정 탭을 그린다.
 *
 * <p>예전에는 이 화면 전체가 항목 일곱 개짜리 {@code setItems} 다이얼로그였다.
 * 다이얼로그는 각 항목의 현재 상태를 제목 문자열에 욱여넣는 것 말고는 보여 줄
 * 방법이 없어서, "지금 어느 차량이 선택돼 있고 블루투스는 등록됐는가" 같은
 * 정보가 항상 한 줄 안에 눌려 있었다.
 *
 * <p>맨 위의 준비 상태 카드가 이 화면이 생긴 진짜 이유다. 자동 알림이 안 뜨는
 * 원인은 늘 조건 네댓 개 중 하나였는데, 그걸 확인할 유일한 방법이 README를
 * 읽는 것이었다.
 */
class SettingsTab {

    private static final int TEST_VERIFY_ATTEMPTS = 6;
    private static final long TEST_VERIFY_DELAY_MS = 200L;

    private final Activity host;
    private final ScreenHost screen;
    private final TextView summary;
    private final LinearLayout readyList;
    private final LinearLayout placeGroup;
    private final LinearLayout vehicleGroup;
    private final LinearLayout dataGroup;
    private final TextView version;
    private int notificationTestGeneration;

    SettingsTab(Activity host, ScreenHost screen) {
        this.host = host;
        this.screen = screen;
        this.summary = host.findViewById(R.id.readySummary);
        this.readyList = host.findViewById(R.id.readyList);
        this.placeGroup = host.findViewById(R.id.settingsPlace);
        this.vehicleGroup = host.findViewById(R.id.settingsVehicle);
        this.dataGroup = host.findViewById(R.id.settingsData);
        this.version = host.findViewById(R.id.settingsVersion);
    }

    /**
     * 전체를 다시 그린다.
     *
     * <p>권한과 배터리 설정은 시스템 화면에 다녀오면 바뀌어 있으므로, 탭으로
     * 돌아올 때마다(그리고 onResume마다) 다시 판정해야 한다. 캐시하지 않는다.
     */
    void render() {
        renderReady();
        renderEntries();
        renderVersion();
    }

    // ---------- 준비 상태 ----------

    private void renderReady() {
        List<ReadyCheck.Item> items = ReadyCheck.all(host);
        int pending = 0;
        for (ReadyCheck.Item item : items) {
            if (item.state == ReadyCheck.State.ACTION_NEEDED) pending++;
        }
        summary.setText(pending == 0
                ? host.getString(R.string.ready_summary_ok)
                : host.getString(R.string.ready_summary_pending, pending));

        readyList.removeAllViews();
        for (ReadyCheck.Item item : items) readyList.addView(readyRow(item));
        readyList.addView(testRow());
    }

    /**
     * 테스트 알림.
     *
     * <p>자동 알림이 안 뜨는 걸 확인하려면 지금까지는 차에 타서 시동을 끄고 내려야
     * 했다. 여기서 같은 알림을 같은 코드로 띄워 보면, 문제가 알림 경로에 있는지
     * 블루투스 감지에 있는지를 주차장에 가지 않고 가를 수 있다.
     */
    private View testRow() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.getResources().getDimensionPixelSize(R.dimen.touch_min));
        row.setBackgroundResource(outValue());
        row.setClickable(true);

        TextView icon = Ui.text(host, "▷", 15, R.color.accent_text, true);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(host, 24), LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.rightMargin = Ui.dp(host, 10);
        icon.setGravity(Gravity.CENTER);
        icon.setLayoutParams(iconLp);

        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(Ui.text(host, host.getString(R.string.ready_test),
                15, R.color.accent_text, true));
        column.addView(Ui.text(host, host.getString(R.string.ready_test_hint),
                12, R.color.subtext, false));

        row.addView(icon);
        row.addView(column);
        row.setOnClickListener(v -> sendTestNotification());
        return row;
    }

    private void sendTestNotification() {
        int generation = ++notificationTestGeneration;
        if (!ReadyCheck.canPostNotifications(host)) {
            Toast.makeText(host, R.string.ready_test_no_permission, Toast.LENGTH_LONG).show();
            ReadyCheck.run(host, ReadyCheck.Action.NOTIFICATIONS);
            return;
        }
        JSONObject vehicle = Store.activeVehicle(host);
        if (vehicle == null) {
            Toast.makeText(host, R.string.ready_test_no_vehicle, Toast.LENGTH_LONG).show();
            return;
        }
        long requestedAt = System.currentTimeMillis();
        String testToken = Long.toHexString(System.nanoTime()) + ":" + generation;
        BtReceiver.showParkPrompt(host, vehicle, testToken);
        verifyParkPrompt(generation, vehicle.optString("id", ""), testToken, requestedAt, 0);
    }

    /** NotificationManagerService의 비동기 enqueue가 끝날 때까지 짧게 재확인한다. */
    private void verifyParkPrompt(int generation, String vehicleId, String testToken,
                                  long requestedAt, int attempt) {
        host.getWindow().getDecorView().postDelayed(() -> {
            if (generation != notificationTestGeneration
                    || host.isFinishing() || host.isDestroyed()) {
                return;
            }
            if (isParkPromptVisible(vehicleId, testToken, requestedAt)) {
                Toast.makeText(host, R.string.ready_test_sent, Toast.LENGTH_LONG).show();
                return;
            }
            if (attempt + 1 < TEST_VERIFY_ATTEMPTS) {
                verifyParkPrompt(generation, vehicleId, testToken, requestedAt, attempt + 1);
                return;
            }
            // 권한 판정 직후 채널이 바뀌었거나 현재 위치가 쓰는 조용한 채널만
            // 차단된 경우까지 잡는다. 실제 게시 결과 없이 성공 문구를 띄우지 않는다.
            Toast.makeText(host, R.string.ready_test_no_permission, Toast.LENGTH_LONG).show();
            ReadyCheck.run(host, ReadyCheck.Action.NOTIFICATIONS);
        }, TEST_VERIFY_DELAY_MS);
    }

    private boolean isParkPromptVisible(String vehicleId, String testToken, long requestedAt) {
        NotificationManager manager =
                (NotificationManager) host.getSystemService(Activity.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        String expectedTag = "bt:" + vehicleId;
        try {
            StatusBarNotification[] active = manager.getActiveNotifications();
            if (active == null) return false;
            for (StatusBarNotification notification : active) {
                if (notification.getId() == Store.NOTIF_ID_BT
                        && expectedTag.equals(notification.getTag())
                        // 같은 tag/id의 예전 알림을 이번 테스트 성공으로 세지 않는다.
                        && notification.getNotification().extras != null
                        && testToken.equals(notification.getNotification().extras.getString(
                                BtReceiver.EXTRA_TEST_TOKEN))
                        && notification.getPostTime() >= requestedAt) {
                    return true;
                }
            }
        } catch (SecurityException ignored) {
            return false;
        }
        return false;
    }

    private View readyRow(ReadyCheck.Item item) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(host, 52));

        // 상태 점. 색만으로 구분하지 않도록 글리프도 함께 바꾼다
        // (완료 ✓ / 확인 필요 ! / 사용 안 함 ·).
        TextView dot = new TextView(host);
        dot.setText(glyph(item.state));
        dot.setTextSize(15);
        dot.setGravity(Gravity.CENTER);
        dot.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dot.setTextColor(host.getColor(tint(item.state)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                Ui.dp(host, 24), LinearLayout.LayoutParams.WRAP_CONTENT);
        dotLp.rightMargin = Ui.dp(host, 10);
        dot.setLayoutParams(dotLp);

        TextView title = Ui.text(host, host.getString(item.titleRes), 15, R.color.text, true);
        TextView detail = Ui.text(host, item.detail, 12, R.color.subtext, false);
        detail.setTextColor(host.getColor(tint(item.state)));

        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(title);
        column.addView(detail);

        row.addView(dot);
        row.addView(column);

        if (item.action != null) {
            TextView action = Ui.text(host, host.getString(
                    item.state == ReadyCheck.State.ACTION_NEEDED
                            ? R.string.ready_action : R.string.settings),
                    13, R.color.accent_text, true);
            action.setPadding(Ui.dp(host, 10), 0, 0, 0);
            row.addView(action);

            row.setBackgroundResource(outValue());
            row.setClickable(true);
            row.setOnClickListener(v -> ReadyCheck.run(host, item.action));
            row.setContentDescription(host.getString(item.titleRes) + ", " + item.detail);
        }
        return row;
    }

    private String glyph(ReadyCheck.State state) {
        switch (state) {
            case OK: return "✓";
            case ACTION_NEEDED: return "!";
            default: return "·";
        }
    }

    private int tint(ReadyCheck.State state) {
        switch (state) {
            case OK: return R.color.ok;
            case ACTION_NEEDED: return R.color.warn;
            default: return R.color.subtext;
        }
    }

    /** 행 전체를 누를 수 있다는 걸 알려 주는 무테 리플. */
    private int outValue() {
        android.util.TypedValue tv = new android.util.TypedValue();
        host.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ---------- 설정 항목 ----------

    private void renderEntries() {
        placeGroup.removeAllViews();
        placeGroup.addView(entry(
                host.getString(R.string.settings_switch_profile,
                        Store.activeProfileName(host)),
                host.getString(R.string.profile_switch_title),
                v -> ProfileDialogs.showPicker(host, screen)));
        placeGroup.addView(entry(
                host.getString(R.string.settings_manage_profile),
                Store.activeProfileName(host),
                v -> ProfileDialogs.showCurrentOptions(host, screen)));
        placeGroup.addView(entry(
                host.getString(R.string.settings_row_zones),
                host.getString(R.string.settings_row_zones_sub),
                v -> screen.openZoneSettings()));
        placeGroup.addView(entry(
                host.getString(R.string.settings_location_filter, host.getString(
                        Store.locationFilterOn(host)
                                ? R.string.state_on : R.string.state_off)),
                host.getString(R.string.settings_location_filter_sub),
                v -> LocationFilterDialogs.showMenu(host)));

        vehicleGroup.removeAllViews();
        vehicleGroup.addView(entry(
                host.getString(R.string.settings_switch_vehicle,
                        Store.activeVehicleName(host)),
                host.getString(R.string.vehicle_switch_title),
                v -> VehicleDialogs.showPicker(host, screen)));
        vehicleGroup.addView(entry(
                host.getString(R.string.settings_manage_vehicle),
                vehicleSubtitle(),
                v -> VehicleDialogs.showCurrentOptions(host, screen)));

        dataGroup.removeAllViews();
        dataGroup.addView(entry(
                host.getString(R.string.settings_backup),
                host.getString(R.string.backup_message),
                v -> BackupFlow.showMenu(host)));
    }

    /** 현재 차량의 블루투스 등록 여부. 감지가 되는지를 여기서 바로 읽을 수 있게. */
    private String vehicleSubtitle() {
        String name = Store.activeVehicleName(host);
        String bt = Store.vehicleBtName(host, Store.activeVehicleId(host));
        return bt == null || bt.trim().isEmpty()
                ? host.getString(R.string.vehicle_manual_suffix, name)
                : host.getString(R.string.vehicle_bt_suffix, name, bt);
    }

    private View entry(String title, String subtitle, View.OnClickListener onTap) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        row.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 12));

        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(Ui.text(host, title, 15, R.color.text, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = Ui.text(host, subtitle, 12, R.color.subtext, false);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(sub);
        }

        TextView chevron = Ui.text(host, "›", 20, R.color.subtext, false);
        chevron.setPadding(Ui.dp(host, 10), 0, 0, 0);

        row.addView(column);
        row.addView(chevron);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.gutter);
        lp.setMargins(gutter, gutter, gutter, gutter);
        row.setLayoutParams(lp);
        row.setMinimumHeight(host.getResources().getDimensionPixelSize(R.dimen.touch_min));
        row.setOnClickListener(onTap);
        return row;
    }

    private void renderVersion() {
        String name = "";
        try {
            name = host.getPackageManager()
                    .getPackageInfo(host.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 자기 자신을 못 찾는 일은 없지만, 버전 한 줄 때문에 화면이 죽으면 안 된다.
        }
        version.setText(host.getString(R.string.settings_version,
                host.getString(R.string.app_name), name,
                host.getString(R.string.brand_tagline)));
    }
}
