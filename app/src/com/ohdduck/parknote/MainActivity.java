package com.ohdduck.parknote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 탭 네 개(홈·기록·위치·설정)를 담는 화면.
 *
 * <p>홈 탭은 여기서 직접 그리고, 나머지 셋은 {@link HistoryTab}, {@link LocationTab},
 * {@link SettingsTab}이 맡는다. 홈만 남긴 이유는 홈이 구역 격자·기록 저장과
 * 얽혀 있어서 이 클래스가 이미 갖고 있는 상태(편집기 복원, 권한, 인텐트 처리)를
 * 그대로 쓰는 게 자연스럽기 때문이다.
 *
 * <p>편집·설정 다이얼로그는 전부 별도 클래스로 나가 있다({@link RecordEditor},
 * {@link ProfileDialogs}, {@link VehicleDialogs}, {@link HabitDialogs},
 * {@link BackupFlow}, {@link LocationFilterDialogs}, {@link ZoneSettingsActivity}).
 */
public class MainActivity extends Activity implements ScreenHost, Tabs.Listener {

    static final String EXTRA_EDIT_RECORD_ID = "edit_record_id";

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_ZONE_SETTINGS = 25;
    private static final int HISTORY_SHOWN = 5;

    private static final String STATE_PENDING_EDIT = "pending_edit_record_id";
    private static final String STATE_OPEN_RECORD = "open_record_id";
    private static final String STATE_TAB = "selected_tab";

    private Tabs tabs;
    private HistoryTab historyTab;
    private LocationTab locationTab;
    private SettingsTab settingsTab;

    // 홈 탭
    private View heroCard;
    private TextView heroLabel;
    private ImageView heroArt;
    private TextView statusZone;
    private TextView statusTime;
    private TextView statusMeta;
    private TextView btnProfile;
    private View heroActions;
    private View btnEdit;
    private View btnDelete;
    private View btnTimer;
    private View detectCard;
    private TextView detectEngine;
    private TextView detectEngineHint;
    private TextView detectPlace;
    private TextView detectPlaceHint;
    private LinearLayout grid;
    private LinearLayout etcGrid;
    private LinearLayout habitList;
    private LinearLayout historyList;

    private int colorText;
    private int colorSubtext;
    private int colorAccent;
    private int colorOnAccent;
    private Typeface medium;

    /** 알림·위젯에서 넘어온 "이 기록을 열어라" 요청. 화면이 준비된 뒤 처리한다. */
    private String pendingEditRecordId;
    /** 기록 편집기가 떠 있는 동안의 대상 id. 회전 뒤 같은 편집기를 다시 연다. */
    private String openRecordId;

    /** 화면이 떠 있는 동안 매분 갱신 ("n분 전" 표시, 자정 넘김 시 습관 체크 상태) */
    private final BroadcastReceiver clockTick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { render(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 첫 실행이면 권한·차량·주차장·격자를 순서대로 안내한다.
        if (!Store.isOnboarded(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        applyWindowInsets();
        cacheColors();
        bindViews();

        historyTab = new HistoryTab(this, this::openRecordEditor);
        locationTab = new LocationTab(this, () -> tabs.select(Tabs.HOME));
        settingsTab = new SettingsTab(this, this);
        tabs = new Tabs(this, this);

        int startTab = Tabs.HOME;
        if (savedInstanceState != null) {
            pendingEditRecordId = savedInstanceState.getString(STATE_PENDING_EDIT);
            // 회전 전에 열려 있던 편집기를 다시 연다. 예전에는 그냥 사라졌다.
            String reopen = savedInstanceState.getString(STATE_OPEN_RECORD);
            if (reopen != null) pendingEditRecordId = reopen;
            startTab = savedInstanceState.getInt(STATE_TAB, Tabs.HOME);
        }
        handleNavigationIntent(getIntent());
        rebuildZoneGrids();

        requestNeededPermissions();
        Reminders.scheduleAll(this);
        ParkingTimers.scheduleAll(this);
        tabs.select(startTab);
        render();
        openPendingRecordEditor();
    }

    private void applyWindowInsets() {
        // Android 15+ 강제 엣지-투-엣지: 시스템 바 높이만큼 여백 확보.
        // 가로 방향도 함께 본다. 가로 모드나 디스플레이 컷아웃이 있는 기기에서
        // 좌우 인셋을 빼먹으면 버튼이 컷아웃 아래로 들어간다.
        // 하단 인셋은 이제 탭 바가 받는다 — 탭 바가 화면 맨 아래에 있으므로
        // 여기서 먹지 않으면 제스처 바 아래로 탭이 깔린다.
        if (Build.VERSION.SDK_INT < 35) return;
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    private void cacheColors() {
        colorText = getColor(R.color.text);
        colorSubtext = getColor(R.color.subtext);
        colorAccent = getColor(R.color.accent_text);
        colorOnAccent = getColor(R.color.on_accent);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private void bindViews() {
        heroCard = findViewById(R.id.heroCard);
        heroLabel = findViewById(R.id.heroLabel);
        heroArt = findViewById(R.id.heroArt);
        statusZone = findViewById(R.id.statusZone);
        statusTime = findViewById(R.id.statusTime);
        statusMeta = findViewById(R.id.statusMeta);
        btnProfile = findViewById(R.id.btnProfile);
        heroActions = findViewById(R.id.heroActions);
        btnEdit = findViewById(R.id.btnEdit);
        btnDelete = findViewById(R.id.btnDelete);
        btnTimer = findViewById(R.id.btnTimer);
        detectCard = findViewById(R.id.detectCard);
        detectEngine = findViewById(R.id.detectEngine);
        detectEngineHint = findViewById(R.id.detectEngineHint);
        detectPlace = findViewById(R.id.detectPlace);
        detectPlaceHint = findViewById(R.id.detectPlaceHint);
        grid = findViewById(R.id.grid);
        etcGrid = findViewById(R.id.etcGrid);
        habitList = findViewById(R.id.habitList);
        historyList = findViewById(R.id.historyList);

        btnDelete.setOnClickListener(v -> confirmDeleteLast());
        btnEdit.setOnClickListener(v -> editLatestRecord());
        btnTimer.setOnClickListener(v -> {
            JSONObject latest = Store.latestRecord(this);
            if (latest != null) RecordEditor.showTimerOnly(this, this, latest.optString("id"));
        });
        btnProfile.setOnClickListener(v -> ProfileDialogs.showPicker(this, this));
        findViewById(R.id.btnSettings).setOnClickListener(v -> tabs.select(Tabs.SETTINGS));
        heroCard.setOnLongClickListener(v -> {
            editLatestRecord();
            return true;
        });
        detectCard.setContentDescription(getString(R.string.cd_detect_card));
        detectCard.setOnClickListener(v -> tabs.select(Tabs.SETTINGS));
        findViewById(R.id.btnCustom).setOnClickListener(v -> showCustomInput());
        findViewById(R.id.btnEditZones).setOnClickListener(v -> openZoneSettings());
        findViewById(R.id.btnAddHabit).setOnClickListener(v -> HabitDialogs.showAdd(this, this));
        findViewById(R.id.btnAllHistory).setOnClickListener(v -> tabs.select(Tabs.HISTORY));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
        rebuildZoneGrids();
        // 알림·위젯에서 들어오면 방금 저장한 기록을 보러 온 것이다. 홈으로 되돌린다.
        if (tabs != null) tabs.select(Tabs.HOME);
        render();
        openPendingRecordEditor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(clockTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ParkingTimers.scheduleAll(this);
        render(); // "n시간 전" 표시, 위젯에서 저장한 기록, 날짜 변경 갱신
        // 권한·배터리 설정과 위젯에서 바뀐 기록은 시스템 화면·다른 앱에 다녀오면
        // 달라져 있을 수 있다. 홈만 다시 그리면 기록/설정 탭에는 이전 값이 남는다.
        // 현재 보이는 탭의 갱신 경로를 그대로 다시 태운다.
        if (tabs != null) onTabChanged(tabs.current());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(clockTick);
        locationTab.onHide(); // 나침반 센서는 화면이 안 보이면 반드시 끈다
    }

    @Override
    protected void onDestroy() {
        if (locationTab != null) locationTab.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_PENDING_EDIT, pendingEditRecordId);
        out.putString(STATE_OPEN_RECORD, openRecordId);
        out.putInt(STATE_TAB, tabs == null ? Tabs.HOME : tabs.current());
    }

    @Override
    public void onBackPressed() {
        // 하단 탭은 독립 화면처럼 보인다. 홈이 아닌 탭에서 뒤로 가기는 앱을
        // 갑자기 닫는 대신 홈으로 돌아가고, 홈에서 한 번 더 눌렀을 때만 종료한다.
        if (tabs != null && tabs.current() != Tabs.HOME) {
            tabs.select(Tabs.HOME);
            return;
        }
        super.onBackPressed();
    }

    // ---------- Tabs.Listener ----------

    @Override
    public void onTabChanged(int tab) {
        // 보이지 않는 탭은 그리지 않는다. 네 화면이 항상 인플레이트돼 있어서
        // 전부 그리면 탭 하나 바꿀 때마다 기록 전체를 다시 만들게 된다.
        if (tab == Tabs.HISTORY) historyTab.render();
        else if (tab == Tabs.SETTINGS) settingsTab.render();

        if (tab == Tabs.LOCATION) locationTab.onShow();
        else locationTab.onHide();
    }

    // ---------- ScreenHost ----------

    @Override
    public void refresh(boolean zonesChanged) {
        if (zonesChanged) rebuildZoneGrids();
        render();
        // 지금 보고 있는 탭도 같이 갱신한다. 예를 들어 설정 탭에서 차량을 바꾸면
        // 그 화면의 부제("블루투스 등록됨")가 바로 따라와야 한다.
        onTabChanged(tabs.current());
    }

    @Override
    public void openZoneSettings() {
        startActivityForResult(new Intent(this, ZoneSettingsActivity.class), REQ_ZONE_SETTINGS);
    }

    // ---------- 권한 · 진입 ----------

    /** 블루투스 감지(BLUETOOTH_CONNECT)와 알림(POST_NOTIFICATIONS) 권한 요청 */
    private void requestNeededPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
    }

    /** 위젯/블루투스 알림에서 앱을 열면 그 버튼이 보이던 차량·주차장으로 맥락을 맞춘다. */
    private void handleNavigationIntent(Intent intent) {
        if (intent == null) return;
        String profileId = intent.getStringExtra(ParkWidgetProvider.EXTRA_PROFILE_ID);
        String vehicleId = intent.getStringExtra(ParkWidgetProvider.EXTRA_VEHICLE_ID);
        if (profileId != null && Store.profileById(this, profileId) != null) {
            Store.setActiveProfile(this, profileId);
        }
        if (vehicleId != null && Store.vehicleById(this, vehicleId) != null) {
            Store.setActiveVehicle(this, vehicleId);
        }
        String editRecordId = intent.getStringExtra(EXTRA_EDIT_RECORD_ID);
        if (editRecordId != null && !editRecordId.isEmpty()) pendingEditRecordId = editRecordId;
    }

    private void openPendingRecordEditor() {
        if (pendingEditRecordId == null) return;
        String recordId = pendingEditRecordId;
        pendingEditRecordId = null;
        openRecordEditor(recordId);
    }

    /** 편집기를 열면서 대상 id를 기억한다. 회전으로 액티비티가 다시 만들어져도 이어진다. */
    private void openRecordEditor(String recordId) {
        AlertDialog dialog = RecordEditor.show(this, this, recordId);
        if (dialog == null) return;
        openRecordId = recordId;
        dialog.setOnDismissListener(d -> openRecordId = null);
    }

    private void editLatestRecord() {
        JSONObject latest = Store.latestRecord(this);
        if (latest == null) {
            Toast.makeText(this, R.string.record_no_edit_target, Toast.LENGTH_SHORT).show();
            return;
        }
        openRecordEditor(latest.optString("id"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ZONE_SETTINGS) {
            if (resultCode == RESULT_OK) refresh(true);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        BackupFlow.handleResult(this, this, requestCode, data.getData());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        LocationFilterDialogs.handlePermissionResult(this, requestCode, permissions, results);
        // 준비 상태 카드가 방금의 허용/거부를 바로 반영해야 한다.
        if (tabs != null && tabs.current() == Tabs.SETTINGS) settingsTab.render();
    }

    // ---------- 주차 ----------

    private void rebuildZoneGrids() {
        // 층 × 구역 격자. 층이 없는 주차장은 구역 버튼만 흐르는 목록이 된다.
        ZoneGrid.build(this, grid, Store.activeRows(this), Store.activeCols(this),
                Store.activeSep(this), false, this::record);

        String[] etc = Store.etcZones(this);
        etcGrid.setVisibility(etc.length == 0 ? View.GONE : View.VISIBLE);
        ZoneGrid.buildSecondary(this, etcGrid, etc, this::record);
    }

    private void record(String zone, View tapped) {
        String recordId = Store.record(this, zone); // 저장 + 위젯 갱신 + 알림 제거
        if (recordId == null) {
            // 주차 위치를 잃는 게 이 앱의 유일한 실패 모드다. 저장이 안 됐는데
            // 성공 문구를 띄우면 사용자는 그 사실조차 모른 채 차를 못 찾는다.
            Toast.makeText(this, R.string.record_save_failed, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        if (tapped != null) {
            tapped.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        Toast.makeText(this, getString(R.string.record_saved, zone),
                Toast.LENGTH_SHORT).show();
        render();
    }

    /** 타 주차장: 주관식 입력 */
    private void showCustomInput() {
        Ui.inputDialog(this, getString(R.string.custom_input_title),
                getString(R.string.custom_input_hint), null, zone -> {
            if (!zone.isEmpty()) record(zone, null);
        });
    }

    private void confirmDeleteLast() {
        String zone = Store.latestZone(this);
        if (zone == null) return;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_delete_last, zone))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    Store.deleteLast(this);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------- 홈 렌더링 ----------

    private void render() {
        JSONArray h = Store.activeHistory(this);
        String currentZone = null;
        btnProfile.setText(getString(R.string.profile_chip, Store.activeProfileName(this)));

        if (h.length() == 0) {
            renderHeroEmpty();
        } else {
            JSONObject latest = h.optJSONObject(0);
            currentZone = latest.optString("z", "?");
            renderHeroSaved(latest, currentZone);
        }

        ZoneGrid.highlight(this, grid, currentZone, colorText);
        ZoneGrid.highlight(this, etcGrid, currentZone, colorSubtext);
        renderDetectCard();
        renderHabits();
        renderHistory(h);
    }

    /**
     * 기록이 있을 때의 히어로. 금색 면 + 자동차 그림.
     *
     * <p>이 카드만 accent를 면으로 쓴다. "차를 어디에 뒀는가"가 화면에서 유일하게
     * 즉시 읽혀야 하는 정보라서, 다른 카드와 같은 어두운 면에 두면 우선순위가 안 보인다.
     * 면이 밝으므로 글자는 전부 on_accent(어두운 색)로 뒤집는다.
     */
    private void renderHeroSaved(JSONObject latest, String zone) {
        long t = latest.optLong("t", 0);
        heroCard.setBackgroundResource(R.drawable.bg_hero);
        heroArt.setVisibility(View.VISIBLE);

        heroLabel.setText(R.string.hero_saved_label);
        heroLabel.setTextColor(colorOnAccent);
        statusZone.setText(zone);
        statusZone.setTextColor(colorOnAccent);
        statusTime.setText(getString(R.string.status_time,
                Store.formatFull(t), Store.formatRelative(t)));
        statusTime.setTextColor(colorOnAccent);
        statusMeta.setText(statusMeta(latest));
        statusMeta.setTextColor(colorOnAccent);
        statusMeta.setVisibility(View.VISIBLE);

        heroActions.setVisibility(View.VISIBLE);
        btnEdit.setVisibility(View.VISIBLE);
        btnDelete.setVisibility(View.VISIBLE);
        btnTimer.setVisibility(View.VISIBLE);
    }

    /** 기록이 없을 때. 금색은 "저장됨"의 신호라, 저장된 게 없을 때 칠하면 거짓말이 된다. */
    private void renderHeroEmpty() {
        heroCard.setBackgroundResource(R.drawable.bg_hero_empty);
        heroArt.setVisibility(View.GONE);

        heroLabel.setText(R.string.hero_empty_label);
        heroLabel.setTextColor(colorSubtext);
        statusZone.setText(getString(R.string.empty_location));
        statusZone.setTextColor(colorSubtext);
        statusTime.setText(getString(R.string.status_empty_hint,
                Store.activeVehicleName(this)));
        statusTime.setTextColor(colorSubtext);
        statusMeta.setVisibility(View.GONE);

        heroActions.setVisibility(View.GONE);
    }

    private CharSequence statusMeta(JSONObject latest) {
        StringBuilder meta = new StringBuilder();
        meta.append(Store.recordProfileName(this, latest));
        meta.append(" · ").append(Store.recordVehicleName(this, latest));
        String memo = Store.recordMemo(latest);
        if (!memo.isEmpty()) meta.append("\n").append(memo);
        long due = latest.optLong("due", 0);
        if (due > System.currentTimeMillis()) {
            meta.append("\n").append(getString(R.string.parking_timer))
                    .append(" · ").append(Store.formatFull(due));
        }
        return meta;
    }

    /**
     * 감지 상태 카드 — "왜 알림이 안 왔지"에 앱 안에서 답한다.
     *
     * <p>왼쪽은 시동(차량 블루투스 연결), 오른쪽은 위치 판정. 둘 다 자동 알림이
     * 뜨거나 안 뜨는 이유 그 자체라, 지금까지는 알림이 떴을 때만 간접적으로
     * 드러났고 안 떴을 때는 확인할 방법이 아예 없었다.
     */
    private void renderDetectCard() {
        renderEngineState();
        renderPlaceState();
    }

    private void renderEngineState() {
        String btName = Store.vehicleBtName(this, Store.activeVehicleId(this));
        if (btName == null || btName.trim().isEmpty()) {
            // 블루투스를 비워 둔 차량은 "수동 기록 전용"이라는 정당한 설정이다.
            detectEngine.setText(R.string.detect_engine_unknown);
            detectEngine.setTextColor(colorSubtext);
            detectEngineHint.setText(R.string.detect_engine_manual);
            return;
        }
        JSONObject state = Store.btState(this);
        // 구버전의 단일 상태 값이나 전환 직전 다른 차량의 이벤트를 현재 차량의
        // "시동 꺼짐"으로 보여 주지 않는다. Store가 차량별 상태를 반환하더라도
        // 이 검사는 오래된 데이터 마이그레이션에 대한 마지막 방어선이다.
        if (state == null
                || !Store.activeVehicleId(this).equals(state.optString("v", ""))) {
            detectEngine.setText(R.string.detect_engine_unknown);
            detectEngine.setTextColor(colorSubtext);
            detectEngineHint.setText(R.string.detect_engine_never);
            return;
        }
        boolean connected = Store.btConnected(this);
        detectEngine.setText(connected
                ? R.string.detect_engine_on : R.string.detect_engine_off);
        detectEngine.setTextColor(connected ? colorAccent : colorText);
        detectEngineHint.setText(connected
                ? getString(R.string.detect_engine_on_hint)
                : getString(R.string.detect_engine_off_hint,
                        Store.formatRelative(state.optLong("t", 0))));
    }

    private void renderPlaceState() {
        if (!Store.locationFilterOn(this)) {
            detectPlace.setText(R.string.detect_place_off);
            detectPlace.setTextColor(colorSubtext);
            detectPlaceHint.setText(R.string.detect_place_off_hint);
            return;
        }
        if (!Store.anyProfileHasCoords(this)) {
            detectPlace.setText(R.string.detect_place_none);
            detectPlace.setTextColor(colorSubtext);
            detectPlaceHint.setText(R.string.detect_place_normal);
            return;
        }
        Location fix = Nearby.lastFix(this);
        if (fix == null) {
            // 좌표를 모르면 판정을 포기하고 평소대로 알린다 (Nearby의 원칙).
            detectPlace.setText(R.string.detect_place_unknown);
            detectPlace.setTextColor(colorSubtext);
            detectPlaceHint.setText(R.string.detect_place_normal);
            return;
        }
        double lat = fix.getLatitude();
        double lon = fix.getLongitude();
        JSONObject near = Store.profileNear(this, lat, lon);
        if (near != null) {
            detectPlace.setText(getString(R.string.detect_place_known,
                    near.optString("n", getString(R.string.profile_default_name))));
            detectPlace.setTextColor(colorAccent);
            detectPlaceHint.setText(R.string.detect_place_normal);
            return;
        }
        detectPlace.setText(R.string.detect_place_away);
        detectPlace.setTextColor(colorText);
        String name = Store.nearestProfileName(this, lat, lon);
        double meters = Store.nearestProfileMeters(this, lat, lon);
        detectPlaceHint.setText(name == null || meters < 0
                ? getString(R.string.detect_place_normal)
                : getString(R.string.detect_place_distance, name, formatDistance(meters)));
    }

    private String formatDistance(double meters) {
        return meters < 1000
                ? getString(R.string.location_distance_m, (int) Math.round(meters))
                : getString(R.string.location_distance_km, meters / 1000.0);
    }

    private void renderHabits() {
        JSONArray hs = Store.habits(this);
        habitList.removeAllViews();

        if (hs.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.habits_empty);
            empty.setTextSize(13);
            empty.setTextColor(colorSubtext);
            empty.setPadding(dp(8), dp(8), dp(8), dp(8));
            habitList.addView(empty);
            return;
        }

        for (int i = 0; i < hs.length(); i++) {
            final int index = i;
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            boolean done = Store.checkedToday(h);
            int streak = Store.streak(h);

            LinearLayout row = listRow(dp(56));
            row.setPadding(dp(14), 0, dp(14), 0);

            TextView mark = new TextView(this);
            mark.setText(done ? "✓" : "○");
            mark.setTextSize(15);
            mark.setTypeface(medium);
            mark.setTextColor(done ? colorAccent : colorSubtext);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.rightMargin = dp(10);
            mark.setLayoutParams(mlp);

            TextView name = new TextView(this);
            name.setText(h.optString("n"));
            name.setTextSize(15);
            name.setTypeface(medium);
            name.setTextColor(colorText);

            TextView dots = new TextView(this);
            dots.setText(weekDots(h));
            dots.setTextSize(9);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(name);
            col.addView(dots);

            TextView info = new TextView(this);
            String summary;
            if (done) {
                summary = Store.formatShort(h.optLong("lt", 0));
                if (streak >= 2) {
                    summary += " · " + getString(R.string.streak_days, streak);
                }
            } else {
                summary = streak >= 1 ? getString(R.string.streak_days, streak) : "";
            }
            info.setText(summary);
            info.setTextSize(12);
            info.setTextColor(colorSubtext);

            row.addView(mark);
            row.addView(col);
            row.addView(info);
            row.setContentDescription(getString(
                    done ? R.string.cd_habit_checked : R.string.cd_habit_unchecked,
                    h.optString("n")));
            row.setOnClickListener(v -> {
                Store.toggleToday(this, index);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                renderHabits();
            });
            row.setOnLongClickListener(v -> {
                HabitDialogs.showOptions(this, this, index);
                return true;
            });
            habitList.addView(row);
        }
    }

    /** 최근 7일 체크 도트 (왼쪽=6일 전, 오른쪽=오늘) */
    private CharSequence weekDots(JSONObject h) {
        boolean[] week = Store.last7Days(h);
        int off = (colorSubtext & 0x00FFFFFF) | 0x55000000; // 흐린 미체크 도트
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int d = 0; d < week.length; d++) {
            if (d > 0) sb.append(' ');
            int start = sb.length();
            sb.append(week[d] ? '●' : '○');
            sb.setSpan(new ForegroundColorSpan(week[d] ? colorAccent : off),
                    start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** 홈에는 현재 주차장×차량의 최근 5개만. 전체는 기록 탭이 맡는다. */
    private void renderHistory(JSONArray h) {
        historyList.removeAllViews();
        if (h.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.history_empty);
            empty.setTextSize(13);
            empty.setTextColor(colorSubtext);
            empty.setPadding(dp(8), dp(8), dp(8), dp(8));
            historyList.addView(empty);
            return;
        }
        for (int i = 0; i < h.length() && i < HISTORY_SHOWN; i++) {
            JSONObject e = h.optJSONObject(i);
            if (e == null) continue;
            String recordId = e.optString("id", "");

            LinearLayout row = listRow(LinearLayout.LayoutParams.WRAP_CONTENT);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));

            TextView zone = new TextView(this);
            zone.setText(e.optString("z", "?"));
            zone.setTextSize(15);
            zone.setTypeface(medium);
            zone.setTextColor(i == 0 ? colorAccent : colorText);

            TextView meta = new TextView(this);
            String metaText = Store.recordProfileName(this, e) + " · "
                    + Store.recordVehicleName(this, e);
            String memo = Store.recordMemo(e);
            if (!memo.isEmpty()) metaText += " · " + memo;
            meta.setText(metaText);
            meta.setTextSize(12);
            meta.setTextColor(colorSubtext);
            meta.setMaxLines(1);
            meta.setEllipsize(TextUtils.TruncateAt.END);

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            details.addView(zone);
            details.addView(meta);

            TextView time = new TextView(this);
            time.setText(Store.formatFull(e.optLong("t", 0)));
            time.setTextSize(13);
            time.setTextColor(colorSubtext);
            time.setGravity(Gravity.END);

            row.addView(details);
            row.addView(time);
            row.setOnClickListener(v -> openRecordEditor(recordId));
            row.setContentDescription(getString(R.string.cd_edit_history,
                    e.optString("z", getString(R.string.record_label_zone))));
            historyList.addView(row);
        }
    }

    /** 습관·기록 목록이 공유하는 행 껍데기. */
    private LinearLayout listRow(int height) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        int gutter = gutter();
        lp.setMargins(gutter, gutter, gutter, gutter);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 섹션 머리글(tab_home.xml)과 구역 격자(ZoneGrid)가 함께 쓰는 바깥 여백.
     * 여기만 다른 값을 쓰면 머리글과 그 아래 목록의 왼쪽 끝이 어긋난다.
     */
    private int gutter() {
        return getResources().getDimensionPixelSize(R.dimen.gutter);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
