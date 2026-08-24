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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 메인 화면. 구역 격자, 지금 주차한 곳, 나가기 전 체크, 최근 기록을 그린다.
 *
 * <p>설정과 편집 다이얼로그는 전부 별도 클래스로 나가 있다({@link RecordEditor},
 * {@link ProfileDialogs}, {@link VehicleDialogs}, {@link HabitDialogs},
 * {@link BackupFlow}, {@link LocationFilterDialogs}, {@link ZoneSettingsActivity}).
 * 여기 남은 건 화면을 그리는 일과, 그 다이얼로그들을 여는 일뿐이다.
 */
public class MainActivity extends Activity implements ScreenHost {

    static final String EXTRA_EDIT_RECORD_ID = "edit_record_id";

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_ZONE_SETTINGS = 25;
    private static final int HISTORY_SHOWN = 5;

    private static final String STATE_PENDING_EDIT = "pending_edit_record_id";
    private static final String STATE_OPEN_RECORD = "open_record_id";

    private TextView statusZone;
    private TextView statusTime;
    private TextView statusMeta;
    private TextView btnProfile;
    private View btnEdit;
    private View btnDelete;
    private View btnTimer;
    private LinearLayout grid;
    private LinearLayout etcGrid;
    private LinearLayout habitList;
    private LinearLayout historyList;

    private int colorText;
    private int colorSubtext;
    private int colorAccent;
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

        // 첫 실행이면 주차장 격자와 차량 블루투스부터 정하게 한다.
        if (!Store.isOnboarded(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        applyWindowInsets();
        cacheColors();
        bindViews();

        if (savedInstanceState != null) {
            pendingEditRecordId = savedInstanceState.getString(STATE_PENDING_EDIT);
            // 회전 전에 열려 있던 편집기를 다시 연다. 예전에는 그냥 사라졌다.
            String reopen = savedInstanceState.getString(STATE_OPEN_RECORD);
            if (reopen != null) pendingEditRecordId = reopen;
        }
        handleNavigationIntent(getIntent());
        rebuildZoneGrids();

        requestNeededPermissions();
        Reminders.scheduleAll(this);
        ParkingTimers.scheduleAll(this);
        render();
        openPendingRecordEditor();
    }

    private void applyWindowInsets() {
        // Android 15+ 강제 엣지-투-엣지: 시스템 바 높이만큼 여백 확보.
        // 가로 방향도 함께 본다. 가로 모드나 디스플레이 컷아웃이 있는 기기에서
        // 좌우 인셋을 빼먹으면 버튼이 컷아웃 아래로 들어간다.
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
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private void bindViews() {
        statusZone = findViewById(R.id.statusZone);
        statusTime = findViewById(R.id.statusTime);
        statusMeta = findViewById(R.id.statusMeta);
        btnProfile = findViewById(R.id.btnProfile);
        btnEdit = findViewById(R.id.btnEdit);
        btnDelete = findViewById(R.id.btnDelete);
        btnTimer = findViewById(R.id.btnTimer);
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
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsMenu());
        findViewById(R.id.statusCard).setOnLongClickListener(v -> {
            editLatestRecord();
            return true;
        });
        findViewById(R.id.btnCustom).setOnClickListener(v -> showCustomInput());
        findViewById(R.id.btnEditZones).setOnClickListener(v -> openZoneSettings());
        findViewById(R.id.btnAddHabit).setOnClickListener(v -> HabitDialogs.showAdd(this, this));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
        rebuildZoneGrids();
        render();
        openPendingRecordEditor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(clockTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ParkingTimers.scheduleAll(this);
        render(); // "n시간 전" 표시, 위젯에서 저장한 기록, 날짜 변경 갱신
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(clockTick);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_PENDING_EDIT, pendingEditRecordId);
        out.putString(STATE_OPEN_RECORD, openRecordId);
    }

    // ---------- ScreenHost ----------

    @Override
    public void refresh(boolean zonesChanged) {
        if (zonesChanged) rebuildZoneGrids();
        render();
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

    // ---------- 설정 ----------

    private void showSettingsMenu() {
        String state = getString(Store.locationFilterOn(this)
                ? R.string.state_on : R.string.state_off);
        String[] items = {
                getString(R.string.settings_switch_profile, Store.activeProfileName(this)),
                getString(R.string.settings_manage_profile),
                getString(R.string.settings_switch_vehicle, Store.activeVehicleName(this)),
                getString(R.string.settings_manage_vehicle),
                getString(R.string.settings_all_history),
                getString(R.string.settings_location_filter, state),
                getString(R.string.settings_backup)};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_title, getString(R.string.app_name)))
                .setItems(items, (d, which) -> {
                    if (which == 0) ProfileDialogs.showPicker(this, this);
                    else if (which == 1) ProfileDialogs.showCurrentOptions(this, this);
                    else if (which == 2) VehicleDialogs.showPicker(this, this);
                    else if (which == 3) VehicleDialogs.showCurrentOptions(this, this);
                    else if (which == 4) showAllParkingHistory();
                    else if (which == 5) LocationFilterDialogs.showMenu(this);
                    else BackupFlow.showMenu(this);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    /** 삭제된 주차장·차량에 속한 기록까지 포함해 개별 편집기로 연다. */
    private void showAllParkingHistory() {
        JSONArray history = Store.history(this);
        if (history.length() == 0) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[history.length()];
        for (int i = 0; i < history.length(); i++) {
            JSONObject record = history.optJSONObject(i);
            if (record == null) {
                items[i] = getString(R.string.history_unknown);
                continue;
            }
            items[i] = getString(R.string.history_row,
                    record.optString("z", getString(R.string.record_label_zone)),
                    Store.recordProfileName(this, record),
                    Store.recordVehicleName(this, record),
                    Store.formatFull(record.optLong("t", 0)));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_all_history)
                .setItems(items, (d, which) -> {
                    JSONObject record = history.optJSONObject(which);
                    if (record != null) openRecordEditor(record.optString("id"));
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    // ---------- 렌더링 ----------

    private void render() {
        JSONArray h = Store.activeHistory(this);
        String currentZone = null;
        btnProfile.setText(getString(R.string.profile_chip, Store.activeProfileName(this)));

        if (h.length() == 0) {
            statusZone.setText(getString(R.string.empty_location));
            statusZone.setTextColor(colorSubtext);
            statusTime.setText(getString(R.string.status_empty_hint,
                    Store.activeVehicleName(this)));
            statusMeta.setVisibility(View.GONE);
            btnEdit.setVisibility(View.INVISIBLE);
            btnDelete.setVisibility(View.INVISIBLE);
            btnTimer.setVisibility(View.GONE);
        } else {
            JSONObject latest = h.optJSONObject(0);
            currentZone = latest.optString("z", "?");
            long t = latest.optLong("t", 0);
            statusZone.setText(currentZone);
            statusZone.setTextColor(colorAccent);
            statusTime.setText(getString(R.string.status_time,
                    Store.formatFull(t), Store.formatRelative(t)));
            statusMeta.setText(statusMeta(latest));
            statusMeta.setVisibility(View.VISIBLE);
            btnEdit.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.VISIBLE);
            btnTimer.setVisibility(View.VISIBLE);
        }

        ZoneGrid.highlight(this, grid, currentZone, colorText);
        ZoneGrid.highlight(this, etcGrid, currentZone, colorSubtext);
        renderHabits();
        renderHistory(h);
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

    private void renderHistory(JSONArray h) {
        historyList.removeAllViews();
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

        // 예전에는 전체 기록으로 가는 길이 설정 메뉴 다섯 번째 항목뿐이었다.
        // 최근 목록이 5개에서 잘리는 바로 그 자리에 통로를 둔다.
        if (Store.history(this).length() > 0) historyList.addView(allHistoryRow());
    }

    private View allHistoryRow() {
        TextView more = new TextView(this);
        int total = Store.history(this).length();
        more.setText(getString(R.string.all_history_link, total));
        more.setTextSize(13);
        more.setTextColor(colorAccent);
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        more.setLayoutParams(lp);
        more.setBackgroundResource(R.drawable.bg_button);
        more.setOnClickListener(v -> showAllParkingHistory());
        return more;
    }

    /** 습관·기록 목록이 공유하는 행 껍데기. */
    private LinearLayout listRow(int height) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.setLayoutParams(lp);
        return row;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
