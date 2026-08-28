package com.ohdduck.parknote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 첫 실행 안내. 필수 세 화면 + 완료 화면.
 *
 * <p><b>단계 순서가 곧 이 앱이 동작하는 순서다.</b> 권한 → 차 블루투스 → 주차장(이름과 격자).
 * 권한이 맨 앞인 이유: 다음 단계에서 페어링된 기기 목록을 열어야 하는데, 그때
 * BLUETOOTH_CONNECT가 없으면 목록이 비어 보인다. 목록이 비면 그냥 넘어가게 되고,
 * 기기가 비면 {@link Store#vehicleMatchingBluetooth}가 null을 돌려줘 {@link BtReceiver}가
 * 알림을 띄우기 전에 그대로 빠져나간다. 사용자 입장에서는 "자동 알림이 영영 안 오는 앱"이 된다.
 *
 * <p><b>완료 화면이 확인 자리다.</b> 예전에는 "시작하기"를 누르면 홈이 뜨고 끝이라, 실제로
 * 시동을 끄기 전까지 동작하는지 알 길이 없었다. 이제 마지막 화면에서 진짜 알림을 미리 띄워
 * 보고(연습이라 저장은 안 됨), 위치 권한·절전 예외·위젯처럼 "있으면 훨씬 편한" 것을 한 번씩
 * 권한다. 안 해도 앱은 되고, 홈의 준비 카드가 나중에 다시 권한다.
 *
 * <p>주차장 화면에서 "다음"을 누르는 순간 저장한다. 완료 화면의 연습 알림·위젯이 방금 적은
 * 차량 이름과 격자로 떠야 하기 때문이다.
 *
 * <p>껍데기(activity_onboarding.xml)는 onCreate에서 한 번만 인플레이트하고, 단계가
 * 바뀌면 본문 컨테이너의 자식만 갈아 끼운다. 예전에는 단계마다 뷰 트리를 통째로
 * 새로 만들어 setContentView를 다시 불렀는데, PhoneWindow는 콘텐츠 전환 기능이
 * 꺼져 있을 때만 이전 뷰를 지우기 때문에 그 기능이 켜진 테마에서는 화면이 겹쳐
 * 비어 보였다.
 */
public class OnboardingActivity extends Activity {

    private static final int REQ_BLUETOOTH = 10;
    private static final int REQ_PERMISSIONS = 12;
    private static final int REQ_LOCATION = 13;

    private static final int STEP_PERMISSIONS = 0;
    private static final int STEP_VEHICLE = 1;
    private static final int STEP_PROFILE = 2;
    private static final int STEP_FINISH = 3;
    private static final int LAST_STEP = STEP_FINISH;

    private interface IntChange {
        void onChange(int value);
    }

    private int step;
    private String profileName;
    private boolean useFloors = true;
    private int floorCount = 3;
    private int zoneCount = 2;
    private String vehicleName;
    private String btName = "";
    /** 목록에서 고른 기기의 주소. 직접 입력했으면 비어 있다. */
    private String btAddress = "";
    /** 블루투스를 비운 채 넘어가겠다고 이미 확인했으면 다시 묻지 않는다. */
    private boolean btSkipConfirmed;
    /**
     * 권한을 두 번 거부해 시스템이 더는 묻지 않는 상태. 이때 "허용하기" 버튼은 눌러도
     * 아무 일이 없어서, 앱 정보 화면으로 가는 버튼으로 바꾼다.
     */
    private boolean permissionBlocked;

    private TextView stepLabel;
    private LinearLayout body;
    private TextView backButton;
    private Button nextButton;
    private TextView btValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profileName = getString(R.string.onboarding_default_profile);
        vehicleName = getString(R.string.onboarding_default_vehicle);
        // 화면을 돌렸다고 설정을 처음부터 다시 하게 만들지 않는다.
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step", step);
            profileName = savedInstanceState.getString("profileName", profileName);
            useFloors = savedInstanceState.getBoolean("useFloors", useFloors);
            floorCount = savedInstanceState.getInt("floorCount", floorCount);
            zoneCount = savedInstanceState.getInt("zoneCount", zoneCount);
            vehicleName = savedInstanceState.getString("vehicleName", vehicleName);
            btName = savedInstanceState.getString("btName", btName);
            btAddress = savedInstanceState.getString("btAddress", btAddress);
            btSkipConfirmed = savedInstanceState.getBoolean("btSkipConfirmed", false);
            permissionBlocked = savedInstanceState.getBoolean("permissionBlocked", false);
        }

        setContentView(R.layout.activity_onboarding);
        stepLabel = findViewById(R.id.onboardingStep);
        body = findViewById(R.id.onboardingBody);
        backButton = findViewById(R.id.onboardingBack);
        nextButton = findViewById(R.id.onboardingNext);

        backButton.setOnClickListener(v -> onBack());
        nextButton.setOnClickListener(v -> onNext());
        applyWindowInsets();
        render();
    }

    private void applyWindowInsets() {
        if (Build.VERSION.SDK_INT < 35) return;
        View root = findViewById(R.id.onboardingRoot);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            // 차량·주차장 이름은 다이얼로그가 아니라 이 화면의 EditText다. 엣지-투-엣지에서는
            // 키보드 인셋을 직접 받아야 입력 중인 칸과 다음 버튼이 키보드 아래로 숨지 않는다.
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(dp(24) + bars.left, dp(28) + bars.top,
                    dp(24) + bars.right, dp(20) + Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
    }

    /**
     * 시스템 뒤로 가기를 화면 안의 "이전 / 건너뛰기"와 똑같이 다룬다.
     *
     * <p>기본 동작대로 두면 2단계에서 뒤로 간 사용자가 액티비티를 끝내 앱을 닫게 되고,
     * 온보딩 완료 플래그가 저장되지 않아 다음 실행에 STEP 1부터 다시 시작한다.
     *
     * <p>매니페스트에서 enableOnBackInvokedCallback을 켜지 않았으므로 targetSdk 35에서도
     * 이 콜백이 그대로 호출된다. AndroidX 없이 프레임워크 Activity만 쓰는 구성이라
     * OnBackPressedDispatcher는 쓸 수 없다.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        onBack();
    }

    private void onBack() {
        if (step > 0) {
            step--;
            render();
            return;
        }
        confirmSkip();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        out.putString("profileName", profileName);
        out.putBoolean("useFloors", useFloors);
        out.putInt("floorCount", floorCount);
        out.putInt("zoneCount", zoneCount);
        out.putString("vehicleName", vehicleName);
        out.putString("btName", btName);
        out.putString("btAddress", btAddress);
        out.putBoolean("btSkipConfirmed", btSkipConfirmed);
        out.putBoolean("permissionBlocked", permissionBlocked);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 권한·절전·위젯은 시스템 화면에 다녀오면 바뀌어 있다. 체크 표시를 다시 읽는다.
        if (step == STEP_PERMISSIONS || step == STEP_FINISH) render();
    }

    // ---------- 화면 ----------

    /** 껍데기는 그대로 두고 본문만 새로 채운다. */
    private void render() {
        stepLabel.setText(getString(R.string.onboarding_step, step + 1, LAST_STEP + 1));
        backButton.setText(step == 0 ? R.string.onboarding_skip : R.string.onboarding_back);
        nextButton.setText(step == LAST_STEP
                ? R.string.onboarding_start : R.string.onboarding_next);

        body.removeAllViews();
        switch (step) {
            case STEP_PERMISSIONS: buildPermissionStep(); break;
            case STEP_VEHICLE: buildVehicleStep(); break;
            case STEP_PROFILE: buildProfileStep(); break;
            default: buildFinishStep(); break;
        }
    }

    /**
     * 1단계: 권한 두 개.
     *
     * <p>맨 앞에 둔다. 다음 단계에서 페어링된 기기 목록을 열어야 하는데, 그때
     * BLUETOOTH_CONNECT가 없으면 목록이 비어 보인다. 알림 권한도 마찬가지로,
     * 없으면 이 앱이 하는 일 전체가 조용히 아무 일도 안 하는 것으로 끝난다.
     */
    private void buildPermissionStep() {
        body.addView(text(getString(R.string.onboarding_perm_title), 24, R.color.text, true));
        body.addView(gap(text(getString(R.string.onboarding_perm_body),
                14, R.color.subtext, false), 10));

        boolean notify = hasNotificationPermission();
        boolean bluetooth = hasBluetoothPermission();
        body.addView(gap(checkRow(getString(R.string.onboarding_perm_notify),
                getString(R.string.onboarding_perm_notify_detail), notify, null, null), 20));
        body.addView(gap(checkRow(getString(R.string.onboarding_perm_bt),
                getString(R.string.onboarding_perm_bt_detail), bluetooth, null, null), 12));

        if (!notify || !bluetooth) {
            if (permissionBlocked) {
                body.addView(gap(text(getString(R.string.onboarding_perm_blocked),
                        13, R.color.warn, false), 16));
            }
            body.addView(gap(primaryButton(getString(permissionBlocked
                            ? R.string.onboarding_perm_settings : R.string.onboarding_perm_allow),
                    () -> {
                        if (permissionBlocked) ReadyCheck.openAppDetails(this);
                        else requestOnboardingPermissions();
                    }), permissionBlocked ? 10 : 20));
        }

        body.addView(gap(text(getString(R.string.onboarding_perm_note),
                13, R.color.accent_text, false), 20));
    }

    /**
     * 2단계: 차량과 블루투스.
     *
     * <p>이 앱의 자동 알림은 통째로 이 한 칸에 달려 있다. 등록된 기기와 끊긴 기기가
     * 같아야 알림이 뜬다.
     */
    private void buildVehicleStep() {
        body.addView(text(getString(R.string.onboarding_vehicle_title),
                24, R.color.text, true));
        body.addView(gap(text(getString(R.string.onboarding_vehicle_body),
                14, R.color.subtext, false), 10));

        EditText input = field(vehicleName, getString(R.string.onboarding_vehicle_hint));
        input.addTextChangedListener(new SimpleWatcher(value -> vehicleName = value));
        body.addView(gap(input, 20));

        body.addView(gap(text(getString(R.string.onboarding_bt_label),
                13, R.color.subtext, true), 16));
        btValue = text(VehicleDialogs.btLabel(this, btName), 15,
                btName.isEmpty() ? R.color.warn : R.color.text, true);
        btValue.setBackgroundResource(R.drawable.bg_button);
        btValue.setPadding(dp(14), dp(14), dp(14), dp(14));
        btValue.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.touch_min));
        btValue.setGravity(Gravity.CENTER_VERTICAL);
        btValue.setOnClickListener(v -> pickBluetoothDevice());
        body.addView(gap(btValue, 4));

        body.addView(gap(text(getString(R.string.onboarding_bt_body),
                13, R.color.subtext, false), 8));
    }

    /** 3단계: 주차장 이름과 격자를 한 화면에. 두 화면을 오갈 이유가 없었다. */
    private void buildProfileStep() {
        body.addView(text(getString(R.string.onboarding_profile_title),
                24, R.color.text, true));
        body.addView(gap(text(getString(R.string.onboarding_profile_body),
                14, R.color.subtext, false), 10));

        EditText input = field(profileName, getString(R.string.onboarding_profile_hint));
        input.addTextChangedListener(new SimpleWatcher(value -> profileName = value));
        body.addView(gap(input, 16));

        body.addView(gap(text(getString(R.string.onboarding_grid_title),
                16, R.color.text, true), 26));
        body.addView(gap(text(getString(R.string.onboarding_grid_body),
                13, R.color.subtext, false), 4));

        LinearLayout choice = new LinearLayout(this);
        choice.setOrientation(LinearLayout.HORIZONTAL);
        choice.addView(choiceButton(getString(R.string.onboarding_grid_floors_zones),
                useFloors, () -> {
            useFloors = true;
            render();
        }));
        choice.addView(choiceButton(getString(R.string.onboarding_grid_floors_only),
                !useFloors, () -> {
            useFloors = false;
            if (floorCount < 2) floorCount = 2;
            render();
        }));
        body.addView(gap(choice, 12));

        // 층만 쓰는 경우 층이 곧 버튼이므로 최소 2개는 있어야 고를 의미가 있다.
        body.addView(gap(stepper(getString(R.string.onboarding_grid_floor_count),
                floorCount, useFloors ? 1 : 2,
                Store.MAX_ROWS,
                value -> {
                    floorCount = value;
                    render();
                }), 12));
        if (useFloors) {
            body.addView(gap(stepper(getString(R.string.onboarding_grid_zone_count),
                    zoneCount, 2, Store.MAX_COLS,
                    value -> {
                        zoneCount = value;
                        render();
                    }), 8));
        }

        String[] rows = gridRows();
        String[] cols = gridCols();
        body.addView(gap(text(getString(R.string.zone_preview),
                12, R.color.subtext, true), 16));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        ZoneGrid.build(this, preview, rows, cols, Store.DEFAULT_SEP, true, null);
        body.addView(gap(preview, 6));

        String[] zones = Store.flatten(rows, cols, Store.DEFAULT_SEP);
        String summary = zones.length == 0
                ? getString(R.string.onboarding_grid_sample_empty, 0)
                : getString(R.string.onboarding_grid_sample, zones.length, zones[0]);
        body.addView(gap(text(summary, 13, R.color.subtext, false), 8));
    }

    /**
     * 완료 화면. 설정은 이미 저장됐고, 여기서는 "되는지 보고" "더 편하게" 만든다.
     *
     * <p>항목은 전부 선택이고, 홈의 준비 카드가 같은 항목을 나중에 다시 권한다.
     * "항상 허용"은 여기서 묻지 않는다 — 안드로이드 11+에선 설정 화면까지 가야 해서
     * 첫 실행의 이탈 지점이 되고, 첫 알림을 받아 본 뒤에야 이유가 와 닿는다.
     */
    private void buildFinishStep() {
        body.addView(text(getString(R.string.onboarding_finish_title), 24, R.color.text, true));
        body.addView(gap(text(getString(R.string.onboarding_finish_body),
                14, R.color.subtext, false), 10));

        body.addView(gap(checkRow(getString(R.string.onboarding_finish_notify),
                getString(R.string.onboarding_finish_notify_detail), false,
                getString(R.string.onboarding_finish_notify_action),
                this::sendPracticeNotification), 20));
        body.addView(gap(checkRow(getString(R.string.onboarding_finish_location),
                getString(R.string.onboarding_finish_location_detail),
                Nearby.hasForegroundPermission(this),
                getString(R.string.onboarding_finish_location_action),
                () -> requestPermissions(Nearby.FOREGROUND_PERMISSIONS, REQ_LOCATION)), 12));
        body.addView(gap(checkRow(getString(R.string.onboarding_finish_battery),
                getString(R.string.onboarding_finish_battery_detail),
                ReadyCheck.batteryExempt(this),
                getString(R.string.onboarding_finish_battery_action),
                () -> ReadyCheck.openBatterySettings(this)), 12));
        if (ReadyCheck.canPinWidget(this)) {
            body.addView(gap(checkRow(getString(R.string.onboarding_finish_widget),
                    getString(R.string.onboarding_finish_widget_detail),
                    ReadyCheck.widgetPlaced(this),
                    getString(R.string.onboarding_finish_widget_action),
                    () -> ReadyCheck.requestPinWidget(this)), 12));
        }

        body.addView(gap(text(getString(R.string.onboarding_finish_note),
                13, R.color.accent_text, false), 20));
    }

    /**
     * 연습 알림. 실제 경로와 같은 알림을 띄우되 버튼을 눌러도 저장하지 않는다.
     * 알림이 막혀 있으면 그 자리에서 알림 설정으로 보낸다 — 여기서 못 보면 실제로도 못 본다.
     */
    private void sendPracticeNotification() {
        if (!ReadyCheck.canPostNotifications(this)) {
            Toast.makeText(this, R.string.ready_test_no_permission, Toast.LENGTH_LONG).show();
            ReadyCheck.run(this, ReadyCheck.Action.NOTIFICATIONS);
            return;
        }
        JSONObject vehicle = Store.activeVehicle(this);
        if (vehicle == null) return;
        BtReceiver.showParkPrompt(this, vehicle, Long.toHexString(System.nanoTime()));
        Toast.makeText(this, R.string.onboarding_finish_notify_sent, Toast.LENGTH_LONG).show();
    }

    /**
     * 체크 한 줄. 끝났으면 초록 체크, 아니면 오른쪽에 버튼. 권한 단계는 버튼 없이 체크만 쓴다.
     */
    private View checkRow(String title, String detail, boolean done, String actionLabel,
                          Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(text(title, 15, R.color.text, true));
        column.addView(gap(text(detail, 13, R.color.subtext, false), 2));
        row.addView(column);

        if (done || action == null) {
            TextView mark = text(done ? "✓" : "·", 16, done ? R.color.ok : R.color.subtext, true);
            mark.setPadding(dp(10), 0, 0, 0);
            row.addView(mark);
            return row;
        }
        Button button = new Button(this);
        button.setText(actionLabel);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(getColor(R.color.on_accent));
        button.setBackgroundResource(R.drawable.bg_button_active);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.leftMargin = dp(10);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> action.run());
        row.addView(button);
        return row;
    }

    // ---------- 위젯 조각 ----------

    private TextView text(String value, int sizeSp, int colorRes, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(colorRes));
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private View gap(View view, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        view.setLayoutParams(lp);
        return view;
    }

    /** 앱의 다른 폼과 같은 입력 필드를 쓴다 (Ui.input). */
    private EditText field(String value, String hint) {
        EditText input = Ui.input(this, hint);
        input.setText(value);
        input.setSelection(input.getText().length());
        return input;
    }

    private Button primaryButton(String label, Runnable onTap) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(getColor(R.color.on_accent));
        button.setBackgroundResource(R.drawable.bg_button_active);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.touch_min)));
        button.setOnClickListener(v -> onTap.run());
        return button;
    }

    private View choiceButton(String label, boolean active, Runnable onTap) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setLineSpacing(0, 1.1f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(getColor(active ? R.color.on_accent : R.color.subtext));
        b.setBackgroundResource(active ? R.drawable.bg_button_active : R.drawable.bg_button);
        b.setStateListAnimator(null);
        int gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
        lp.setMargins(gutter, 0, gutter, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    private View stepper(String label, int value, int min, int max, IntChange onChange) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setBackgroundResource(R.drawable.bg_button);
        wrap.setPadding(dp(16), dp(8), dp(8), dp(8));

        TextView title = text(label, 14, R.color.text, false);
        wrap.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        wrap.addView(stepButton("−", value > min, () -> onChange.onChange(value - 1)));
        TextView count = text(String.valueOf(value), 18, R.color.accent_text, true);
        count.setGravity(Gravity.CENTER);
        count.setLayoutParams(new LinearLayout.LayoutParams(dp(38),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        wrap.addView(count);
        wrap.addView(stepButton("+", value < max, () -> onChange.onChange(value + 1)));
        return wrap;
    }

    /**
     * −/+ 한 칸.
     *
     * <p>비활성 글자색은 text_disabled를 쓴다. 예전에는 outline(테두리 토큰)을
     * 글자색으로 빌려 썼는데, 그 토큰이 테두리용으로 어두워지자 button 배경 위에서
     * 1.15:1이 돼 '−'가 아예 사라졌다. 층 수가 최소일 때 스테퍼가 반쯤 비어
     * 고장난 것처럼 보였다.
     */
    private View stepButton(String label, boolean enabled, Runnable onTap) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(19);
        b.setTextColor(getColor(enabled ? R.color.text : R.color.text_disabled));
        b.setBackgroundResource(R.drawable.bg_button);
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setEnabled(enabled);
        int touch = getResources().getDimensionPixelSize(R.dimen.touch_min);
        b.setLayoutParams(new LinearLayout.LayoutParams(touch, touch));
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    // ---------- 흐름 ----------

    private String[] gridRows() {
        return useFloors ? Store.basementRows(floorCount) : new String[0];
    }

    private String[] gridCols() {
        return useFloors ? Store.columnLabels(zoneCount) : Store.basementRows(floorCount);
    }

    private void onNext() {
        if (step == STEP_VEHICLE) {
            if (vehicleName.trim().isEmpty()) {
                Toast.makeText(this, R.string.onboarding_vehicle_required,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // 블루투스를 비운 채 넘어가려 하면 결과를 먼저 알려 준다. 이걸 그냥
            // 통과시키면 자동 알림이 영영 안 오는데, 사용자는 앱이 고장 났다고 여긴다.
            if (btName.trim().isEmpty() && !btSkipConfirmed) {
                confirmNoBluetooth();
                return;
            }
        }
        if (step == STEP_PROFILE) {
            if (profileName.trim().isEmpty()) {
                Toast.makeText(this, R.string.onboarding_profile_required,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // 여기서 저장한다. 완료 화면의 연습 알림과 위젯이 방금 적은 이름·격자로 떠야 한다.
            // 뒤로 갔다 고치고 다시 오면 같은 값을 덮어쓴다.
            save();
        }
        if (step < LAST_STEP) {
            step++;
            render();
            return;
        }
        openMain();
    }

    /** 블루투스 없이 넘어가려 할 때. 고르러 가는 쪽을 기본 동작으로 둔다. */
    private void confirmNoBluetooth() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.onboarding_bt_missing_title)
                .setMessage(R.string.onboarding_bt_missing_message)
                .setPositiveButton(R.string.onboarding_bt_missing_pick,
                        (d, w) -> pickBluetoothDevice())
                .setNegativeButton(R.string.onboarding_bt_missing_skip, (d, w) -> {
                    btSkipConfirmed = true;
                    step++;
                    render();
                })
                .show();
    }

    private void confirmSkip() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.onboarding_skip_confirm)
                .setPositiveButton(R.string.onboarding_skip_action, (d, w) -> {
                    Store.skipOnboarding(this);
                    openMain();
                })
                .setNegativeButton(R.string.onboarding_skip_continue, null)
                .show();
    }

    // ---------- 권한 ----------

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private String[] missingPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (!hasBluetoothPermission()) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!hasNotificationPermission()) need.add(Manifest.permission.POST_NOTIFICATIONS);
        return need.toArray(new String[0]);
    }

    private void requestOnboardingPermissions() {
        String[] need = missingPermissions();
        if (need.length == 0) {
            render();
            return;
        }
        requestPermissions(need, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMISSIONS) {
            // 거부됐는데 시스템이 더는 이유를 물을 필요가 없다고 하면 "다시 묻지 않음"이다.
            // 이 뒤로는 requestPermissions가 조용히 거부만 돌려주므로 설정으로 보내야 한다.
            for (int i = 0; i < permissions.length && i < results.length; i++) {
                if (results[i] != PackageManager.PERMISSION_GRANTED
                        && !shouldShowRequestPermissionRationale(permissions[i])) {
                    permissionBlocked = true;
                }
            }
            render(); // 체크 표시를 방금 결과로 갱신
            return;
        }
        if (requestCode == REQ_LOCATION) {
            render();
            return;
        }
        if (requestCode != REQ_BLUETOOTH) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            showBondedDevices();
        } else {
            Toast.makeText(this, R.string.bt_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void save() {
        Store.completeOnboarding(this, profileName, gridRows(), gridCols(),
                Store.DEFAULT_SEP, vehicleName, btName, btAddress);
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // ---------- 블루투스 ----------

    private void pickBluetoothDevice() {
        if (!hasBluetoothPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH);
            return;
        }
        showBondedDevices();
    }

    private void showBondedDevices() {
        BtPicker.show(this, btName, this::setBt);
    }

    private void setBt(String name, String address) {
        btName = name == null ? "" : name.trim();
        btAddress = address == null ? "" : address.trim();
        if (!btName.isEmpty()) btSkipConfirmed = false;
        // 색까지 함께 바뀌어야 해서 (비었으면 경고색) 이 단계를 다시 그린다.
        if (step == STEP_VEHICLE) render();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** EditText 값만 받아 두는 최소 TextWatcher */
    private static class SimpleWatcher implements TextWatcher {
        private final TextChange sink;

        SimpleWatcher(TextChange sink) {
            this.sink = sink;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

        @Override public void afterTextChanged(Editable s) {
            sink.onChange(s.toString());
        }
    }

    private interface TextChange {
        void onChange(String value);
    }
}
