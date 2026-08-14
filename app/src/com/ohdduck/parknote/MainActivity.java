package com.ohdduck.parknote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String EXTRA_EDIT_RECORD_ID = "edit_record_id";
    private static final int REQ_EXPORT = 20;
    private static final int REQ_IMPORT = 21;
    private static final int REQ_LOCATION_FG = 22;
    private static final int REQ_LOCATION_BG = 23;
    private static final int REQ_LOCATION_CAPTURE = 24;
    private static final int HISTORY_SHOWN = 5;
    private static final int MAX_ZONES_PER_GROUP = 30;
    private static final int MAX_ZONE_NAME_LENGTH = 24;
    private static final int MAX_MEMO_LENGTH = 200;

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
    private String pendingEditRecordId;

    private interface StringChoiceCallback {
        void onChosen(String value);
    }

    private interface LongChoiceCallback {
        void onChosen(long value);
    }

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

        // Android 15+ 강제 엣지-투-엣지: 상태바/내비게이션 바 높이만큼 여백 확보
        if (Build.VERSION.SDK_INT >= 35) {
            findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        colorText = getColor(R.color.text);
        colorSubtext = getColor(R.color.subtext);
        colorAccent = getColor(R.color.accent_text);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

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

        handleNavigationIntent(getIntent());
        rebuildZoneGrids();

        btnDelete.setOnClickListener(v -> confirmDeleteLast());
        btnEdit.setOnClickListener(v -> showEditLatestRecord());
        btnTimer.setOnClickListener(v -> {
            JSONObject latest = Store.latestRecord(this);
            if (latest != null) showQuickTimerOptions(latest.optString("id"));
        });
        btnProfile.setOnClickListener(v -> showProfilePicker());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsMenu());
        findViewById(R.id.statusCard).setOnLongClickListener(v -> {
            showEditLatestRecord();
            return true;
        });
        findViewById(R.id.btnCustom).setOnClickListener(v -> showCustomInput());
        findViewById(R.id.btnEditZones).setOnClickListener(v -> showZoneSettings());
        findViewById(R.id.btnAddHabit).setOnClickListener(v -> showAddHabit());

        requestNeededPermissions();
        Reminders.scheduleAll(this);
        ParkingTimers.scheduleAll(this);
        render();
        openPendingRecordEditor();
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
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
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
        showRecordEditor(recordId);
    }

    // ---------- 주차 ----------

    /** 구역 배열을 perRow개씩 끊어 행으로 배치 */
    private void buildGrid(LinearLayout container, String[] zones, int perRow,
                           int heightDp, int textSp, int textColor) {
        LinearLayout row = null;
        for (int i = 0; i < zones.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            String zone = zones[i];
            Button b = new Button(this);
            b.setText(zone);
            b.setTag(zone);
            b.setTextSize(textSp);
            b.setTypeface(medium);
            b.setTextColor(textColor);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> record(zone, v));
            row.addView(b);
        }

        // 홀수 개처럼 마지막 행이 덜 찼을 때 버튼 폭이 갑자기 커지지 않게 빈칸을 둔다.
        if (row != null) {
            while (row.getChildCount() < perRow) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(spacer, lp);
            }
        }
    }

    private void rebuildZoneGrids() {
        etcGrid.removeAllViews();

        // 층 × 구역 격자. 층이 없는 주차장은 구역 버튼만 흐르는 목록이 된다.
        ZoneGrid.build(this, grid, Store.activeRows(this), Store.activeCols(this),
                Store.activeSep(this), false, this::record);

        String[] etc = Store.etcZones(this);
        etcGrid.setVisibility(etc.length == 0 ? View.GONE : View.VISIBLE);
        if (etc.length > 0) buildGrid(etcGrid, etc, 4, 42, 13, colorSubtext);
    }

    private void record(String zone, View tapped) {
        Store.record(this, zone); // 저장 + 위젯 갱신 + 알림 제거
        if (tapped != null) {
            tapped.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        Toast.makeText(this, "아맞다! " + zone + "에 댔어요", Toast.LENGTH_SHORT).show();
        render();
    }

    /** 타 주차장: 주관식 입력 */
    private void showCustomInput() {
        EditText input = new EditText(this);
        input.setHint("예: 롯데몰 B2 F기둥");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        showInputDialog("다른 위치 직접 입력", input, () -> {
            String zone = input.getText().toString().trim();
            if (!zone.isEmpty()) record(zone, null);
        });
    }

    private void confirmDeleteLast() {
        String zone = Store.latestZone(this);
        if (zone == null) return;
        new AlertDialog.Builder(this)
                .setMessage("마지막 기록(" + zone + ")을 삭제할까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    Store.deleteLast(this);
                    render();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- 설정 ----------

    private void showSettingsMenu() {
        String[] items = {
                "주차장 전환 · " + Store.activeProfileName(this),
                "현재 주차장 관리",
                "차량 전환 · " + Store.activeVehicleName(this),
                "현재 차량 관리",
                "전체 주차 기록",
                "위치로 알림 조절 · " + (Store.locationFilterOn(this) ? "켜짐" : "꺼짐"),
                "백업 · 복원"};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name) + " 설정")
                .setItems(items, (d, which) -> {
                    if (which == 0) showProfilePicker();
                    else if (which == 1) showCurrentProfileOptions();
                    else if (which == 2) showVehiclePicker();
                    else if (which == 3) showCurrentVehicleOptions();
                    else if (which == 4) showAllParkingHistory();
                    else if (which == 5) showLocationFilterMenu();
                    else showBackupMenu();
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    // ---------- 위치로 알림 조절 ----------

    private void showLocationFilterMenu() {
        boolean on = Store.locationFilterOn(this);
        String name = Store.activeProfileName(this);
        boolean hasCoords = Store.hasCoords(Store.activeProfile(this));

        ArrayList<String> items = new ArrayList<>();
        items.add(on ? "끄기" : "켜기");
        items.add(hasCoords
                ? "'" + name + "' 위치 다시 잡기"
                : "지금 위치를 '" + name + "'으로 저장");
        if (hasCoords) items.add("'" + name + "' 위치 지우기");

        StringBuilder message = new StringBuilder();
        message.append("등록한 주차장 근처에서 차에서 내리면 평소대로 알리고, ")
                .append("그 밖에서는 소리 없이 알림함에만 남겨요.\n")
                .append("위치를 알 수 없을 때는 놓치지 않도록 평소대로 알려요.\n\n")
                .append("현재 ").append(on ? "켜짐" : "꺼짐").append(" · ")
                .append("'").append(name).append("' 위치 ")
                .append(hasCoords ? "등록됨 (반경 " + Store.DEFAULT_RADIUS_M + "m)" : "없음");
        if (on && !Store.anyProfileHasCoords(this)) {
            message.append("\n\n※ 등록된 위치가 하나도 없어 지금은 평소와 똑같이 동작해요.");
        }

        new AlertDialog.Builder(this)
                .setTitle("위치로 알림 조절")
                .setMessage(message)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        if (on) {
                            Store.setLocationFilter(this, false);
                            Toast.makeText(this, "위치 조절을 껐어요", Toast.LENGTH_SHORT).show();
                        } else {
                            enableLocationFilter();
                        }
                    } else if (which == 1) {
                        captureProfileLocation();
                    } else {
                        Store.clearProfileCoords(this, Store.activeProfileId(this));
                        Toast.makeText(this, "위치를 지웠어요", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void enableLocationFilter() {
        if (!Nearby.hasForegroundPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION_FG);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !Nearby.hasPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQ_LOCATION_BG);
            return;
        }
        Store.setLocationFilter(this, true);
        if (Store.anyProfileHasCoords(this)) {
            Toast.makeText(this, "위치 조절을 켰어요", Toast.LENGTH_SHORT).show();
        } else {
            new AlertDialog.Builder(this)
                    .setMessage("이제 주차장 위치를 등록하면 돼요. 지금 '"
                            + Store.activeProfileName(this) + "'에 계신가요?")
                    .setPositiveButton("지금 위치 저장", (d, w) -> captureProfileLocation())
                    .setNegativeButton("나중에", null)
                    .show();
        }
    }

    /** 백그라운드 위치는 시스템 다이얼로그가 아니라 설정 화면에서만 켤 수 있는 기기가 많다. */
    private void showBackgroundLocationHelp() {
        new AlertDialog.Builder(this)
                .setTitle("'항상 허용'이 필요해요")
                .setMessage("차에서 내리는 순간은 앱이 꺼져 있을 때라, 위치 권한을 "
                        + "'항상 허용'으로 바꿔야 어느 주차장인지 알 수 있어요.\n\n"
                        + "설정 → 권한 → 위치 → '항상 허용'을 선택해 주세요. "
                        + "좌표는 기기 안에서만 쓰고 어디로도 보내지 않아요.")
                .setPositiveButton("설정 열기", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "설정 화면을 열지 못했어요",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void captureProfileLocation() {
        if (!Nearby.hasForegroundPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION_CAPTURE);
            return;
        }
        String profileId = Store.activeProfileId(this);
        String name = Store.activeProfileName(this);
        Toast.makeText(this, "위치를 잡는 중이에요…", Toast.LENGTH_SHORT).show();
        Nearby.requestFix(this, fix -> {
            // 측위는 최대 15초 걸린다. 그 사이 화면을 닫았으면 다이얼로그를 띄우지 않는다.
            if (isFinishing() || isDestroyed()) return;
            if (fix == null) {
                new AlertDialog.Builder(this)
                        .setTitle("위치를 잡지 못했어요")
                        .setMessage("위치 서비스가 꺼져 있거나 실내라 신호가 약할 수 있어요.\n"
                                + "지하 주차장이라면 지상으로 올라와서, 또는 지도 앱으로 "
                                + "현재 위치를 한 번 확인한 뒤 다시 시도해 주세요.")
                        .setPositiveButton("확인", null)
                        .show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("'" + name + "' 위치로 저장할까요?")
                    .setMessage(Nearby.describeAccuracy(fix) + "\n\n"
                            + "이 지점 반경 " + Store.DEFAULT_RADIUS_M + "m 안에서 차에서 내리면 "
                            + "'" + name + "'에 댄 것으로 봅니다.\n"
                            + "지하 주차장은 신호가 안 잡히니, 진입 직전에 잡힌 좌표로 판정해요. "
                            + "그래서 반경을 넉넉히 잡습니다.")
                    .setPositiveButton("저장", (d, w) -> {
                        Store.setProfileCoords(this, profileId,
                                fix.getLatitude(), fix.getLongitude(), Store.DEFAULT_RADIUS_M);
                        Toast.makeText(this, "'" + name + "' 위치를 저장했어요",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("취소", null)
                    .show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_LOCATION_FG) {
            if (granted) enableLocationFilter();
            else Toast.makeText(this, "위치 권한이 없으면 이 기능을 쓸 수 없어요",
                    Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_LOCATION_BG) {
            if (granted) enableLocationFilter();
            else showBackgroundLocationHelp();
        } else if (requestCode == REQ_LOCATION_CAPTURE) {
            if (granted) captureProfileLocation();
            else Toast.makeText(this, "위치 권한이 없으면 주차장 위치를 저장할 수 없어요",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 백업 · 복원 ----------

    private void showBackupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("백업 · 복원")
                .setMessage("기록은 이 기기 안에만 있어요. 앱을 지웠다 깔거나 폰을 바꾸면 사라지니, "
                        + "가끔 파일로 내보내 두세요.")
                .setItems(new String[]{"파일로 내보내기", "백업에서 가져오기"}, (d, which) -> {
                    if (which == 0) startBackupExport();
                    else startBackupImport();
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void startBackupExport() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(Backup.MIME)
                    .putExtra(Intent.EXTRA_TITLE, Backup.suggestedFileName()), REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "파일을 저장할 앱을 찾지 못했어요", Toast.LENGTH_SHORT).show();
        }
    }

    private void startBackupImport() {
        try {
            // 파일 관리자마다 .json을 application/json으로 안 보는 경우가 있어 전체를 연다.
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "파일을 고를 앱을 찾지 못했어요", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            String error = Backup.writeTo(this, uri);
            Toast.makeText(this, error == null ? "백업을 저장했어요" : error,
                    Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_IMPORT) {
            confirmBackupImport(uri);
        }
    }

    private void confirmBackupImport(Uri uri) {
        Backup.Loaded loaded = Backup.readFrom(this, uri);
        if (!loaded.ok()) {
            Toast.makeText(this, loaded.error, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("백업에서 가져오기")
                .setMessage(loaded.summary
                        + "\n\n지금 이 기기에 있는 주차 기록·주차장·차량·체크가 모두 사라지고 "
                        + "백업 내용으로 바뀝니다. 예약된 알림도 다시 맞춰져요.")
                .setPositiveButton("가져오기", (d, w) -> {
                    Store.importData(this, loaded.data);
                    rebuildZoneGrids();
                    render();
                    Toast.makeText(this, "백업을 가져왔어요", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showProfilePicker() {
        JSONArray profiles = Store.profiles(this);
        String active = Store.activeProfileId(this);
        String[] items = new String[profiles.length()];
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            String id = profile == null ? "" : profile.optString("id");
            String name = profile == null ? "주차장" : profile.optString("n", "주차장");
            items[i] = (id.equals(active) ? "✓ " : "") + name;
        }
        new AlertDialog.Builder(this)
                .setTitle("주차장 전환")
                .setItems(items, (d, which) -> {
                    JSONObject profile = profiles.optJSONObject(which);
                    if (profile == null) return;
                    Store.setActiveProfile(this, profile.optString("id"));
                    rebuildZoneGrids();
                    render();
                })
                .setNeutralButton("추가", (d, w) -> showProfileNameDialog(true))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showCurrentProfileOptions() {
        ArrayList<String> items = new ArrayList<>();
        items.add("이름 변경");
        items.add("구역 관리");
        if (Store.profileCount(this) > 1) items.add("삭제");
        new AlertDialog.Builder(this)
                .setTitle(Store.activeProfileName(this))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) showProfileNameDialog(false);
                    else if (which == 1) showZoneSettings();
                    else confirmDeleteCurrentProfile();
                })
                .show();
    }

    private void showProfileNameDialog(boolean adding) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setHint("예: 우리 집, 회사");
        if (!adding) {
            input.setText(Store.activeProfileName(this));
            input.setSelection(input.getText().length());
        }
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(adding ? "새 주차장" : "주차장 이름 변경")
                .setView(wrap)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .create();
        dlg.setOnShowListener(ignored -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        if (adding) Store.addProfile(this, input.getText().toString());
                        else Store.renameProfile(this, Store.activeProfileId(this),
                                input.getText().toString());
                    } catch (IllegalArgumentException e) {
                        input.setError(e.getMessage());
                        input.requestFocus();
                        return;
                    }
                    rebuildZoneGrids();
                    render();
                    Toast.makeText(this, adding ? "새 주차장을 추가했어요" : "이름을 바꿨어요",
                            Toast.LENGTH_SHORT).show();
                    dlg.dismiss();
                }));
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        input.requestFocus();
    }

    private void confirmDeleteCurrentProfile() {
        String id = Store.activeProfileId(this);
        String name = Store.activeProfileName(this);
        new AlertDialog.Builder(this)
                .setMessage("'" + name + "' 주차장을 삭제할까요?\n"
                        + "기존 기록은 설정의 전체 주차 기록에서 계속 확인·수정할 수 있어요.\n"
                        + "이 주차장의 출차 알림은 해제돼요.")
                .setPositiveButton("삭제", (d, w) -> {
                    if (Store.deleteProfile(this, id)) {
                        rebuildZoneGrids();
                        render();
                        Toast.makeText(this, "주차장을 삭제했어요", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showVehiclePicker() {
        JSONArray vehicles = Store.vehicles(this);
        String active = Store.activeVehicleId(this);
        String[] items = new String[vehicles.length()];
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject vehicle = vehicles.optJSONObject(i);
            String id = vehicle == null ? "" : vehicle.optString("id");
            String name = vehicle == null ? "차량" : vehicle.optString("n", "차량");
            String bt = vehicle == null ? "" : vehicle.optString("b", "");
            items[i] = (id.equals(active) ? "✓ " : "") + name
                    + (bt.isEmpty() ? " · 수동 기록" : " · " + bt);
        }
        new AlertDialog.Builder(this)
                .setTitle("차량 전환")
                .setItems(items, (d, which) -> {
                    JSONObject vehicle = vehicles.optJSONObject(which);
                    if (vehicle == null) return;
                    Store.setActiveVehicle(this, vehicle.optString("id"));
                    render();
                })
                .setNeutralButton("추가", (d, w) -> showVehicleEditor(null))
                .setNegativeButton("닫기", null)
                .show();
    }

    /** 삭제된 주차장·차량에 속한 기록까지 포함해 개별 편집기로 연다. */
    private void showAllParkingHistory() {
        JSONArray history = Store.history(this);
        if (history.length() == 0) {
            Toast.makeText(this, "저장된 주차 기록이 없어요", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[history.length()];
        for (int i = 0; i < history.length(); i++) {
            JSONObject record = history.optJSONObject(i);
            if (record == null) {
                items[i] = "알 수 없는 기록";
                continue;
            }
            String profile = Store.recordProfileName(this, record);
            String vehicle = Store.recordVehicleName(this, record);
            items[i] = record.optString("z", "주차 위치") + "\n"
                    + profile + " · " + vehicle + " · "
                    + Store.formatFull(record.optLong("t", 0));
        }
        new AlertDialog.Builder(this)
                .setTitle("전체 주차 기록")
                .setItems(items, (d, which) -> {
                    JSONObject record = history.optJSONObject(which);
                    if (record != null) showRecordEditor(record.optString("id"));
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showCurrentVehicleOptions() {
        JSONObject vehicle = Store.activeVehicle(this);
        if (vehicle == null) return;
        ArrayList<String> items = new ArrayList<>();
        items.add("차량 정보 수정");
        if (Store.vehicleCount(this) > 1) items.add("삭제");
        new AlertDialog.Builder(this)
                .setTitle(vehicle.optString("n", "차량"))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) showVehicleEditor(vehicle);
                    else confirmDeleteCurrentVehicle();
                })
                .show();
    }

    private void showVehicleEditor(JSONObject existing) {
        boolean adding = existing == null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));

        TextView guide = settingText("블루투스를 고르지 않으면 수동 기록 전용 차량이 돼요.",
                13, colorSubtext);
        form.addView(guide);
        TextView nameLabel = settingText("차량 이름", 14, colorText);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(16);
        form.addView(nameLabel, nameLp);
        EditText name = new EditText(this);
        name.setHint("예: 내 차, 가족 차");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(adding ? "" : existing.optString("n", ""));
        form.addView(name);

        TextView btLabel = settingText("차 블루투스 (선택)", 14, colorText);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btLp.topMargin = dp(12);
        form.addView(btLabel, btLp);
        // 이름을 손으로 적으면 한 글자만 달라도 자동 알림이 조용히 안 뜬다 → 목록에서 고르게 한다.
        final String[] btName = {adding ? "" : existing.optString("b", "")};
        Button btButton = editorButton(btButtonText(btName[0]));
        btButton.setOnClickListener(v -> BtPicker.show(this, btName[0], picked -> {
            btName[0] = picked;
            btButton.setText(btButtonText(picked));
        }));
        form.addView(btButton);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(adding ? "차량 추가" : "차량 정보 수정")
                .setView(form)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .create();
        dlg.setOnShowListener(ignored -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        if (adding) Store.addVehicle(this, name.getText().toString(),
                                btName[0]);
                        else Store.updateVehicle(this, existing.optString("id"),
                                name.getText().toString(), btName[0]);
                    } catch (IllegalArgumentException e) {
                        name.setError(e.getMessage());
                        name.requestFocus();
                        return;
                    }
                    render();
                    Toast.makeText(this, adding ? "차량을 추가했어요" : "차량 정보를 저장했어요",
                            Toast.LENGTH_SHORT).show();
                    dlg.dismiss();
                }));
        dlg.show();
    }

    private void confirmDeleteCurrentVehicle() {
        String id = Store.activeVehicleId(this);
        String name = Store.activeVehicleName(this);
        new AlertDialog.Builder(this)
                .setMessage("'" + name + "' 차량을 삭제할까요?\n"
                        + "기존 기록은 설정의 전체 주차 기록에서 계속 확인·수정할 수 있어요.\n"
                        + "이 차량의 출차 알림과 블루투스 알림은 해제돼요.")
                .setPositiveButton("삭제", (d, w) -> {
                    if (Store.deleteVehicle(this, id)) {
                        render();
                        Toast.makeText(this, "차량을 삭제했어요", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /**
     * 층 · 구역 · 기타를 한 줄에 하나씩 편집한다. 층과 구역을 조합해 버튼이 만들어지므로
     * 층 3개 × 구역 4개면 12칸 격자가 되고, 층을 비우면 구역 이름이 그대로 버튼이 된다.
     */
    private void showZoneSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));

        TextView guide = settingText(
                "한 줄에 하나씩, 입력한 순서대로 표시돼요.\n"
                        + "예: 층 B1·B2 + 구역 A·B → B1-A, B1-B, B2-A, B2-B\n"
                        + "홈 위젯에는 앞 6칸만 표시되므로 짧은 이름을 권장해요.",
                13, colorSubtext);
        form.addView(guide);

        TextView rowLabel = settingText("층 · 선택 (최대 " + Store.MAX_ROWS + "개)", 14, colorText);
        LinearLayout.LayoutParams rowLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLabelLp.topMargin = dp(16);
        form.addView(rowLabel, rowLabelLp);

        EditText rowInput = zoneEditor(Store.activeRows(this), 3);
        rowInput.setHint("비워 두면 층 없이 구역 버튼만 표시해요");
        rowInput.setContentDescription("층, 한 줄에 하나, 비워 둘 수 있음");
        form.addView(rowInput);

        TextView colLabel = settingText("구역 (최대 "
                + Store.colLimit(Store.activeRows(this)) + "개)", 14, colorText);
        LinearLayout.LayoutParams colLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        colLabelLp.topMargin = dp(16);
        form.addView(colLabel, colLabelLp);

        EditText colInput = zoneEditor(Store.activeCols(this), 3);
        colInput.setContentDescription("구역, 한 줄에 하나");
        form.addView(colInput);

        TextView etcLabel = settingText("기타 구역 · 작은 버튼 (선택)", 14, colorText);
        LinearLayout.LayoutParams etcLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        etcLabelLp.topMargin = dp(16);
        form.addView(etcLabel, etcLabelLp);

        EditText etcInput = zoneEditor(Store.etcZones(this), 2);
        etcInput.setHint("비워 두면 기타 버튼을 숨겨요");
        etcInput.setContentDescription("기타 구역, 한 줄에 하나, 비워 둘 수 있음");
        form.addView(etcInput);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("구역 편집")
                .setView(scroll)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .setNeutralButton("기본값", null)
                .create();

        dlg.setOnShowListener(ignored -> {
            // 기본 AlertDialog의 저장 버튼은 검증 실패 때도 닫히므로 직접 처리한다.
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                // 층과 구역은 서로 겹쳐도 되므로(A층 A구역) 중복 검사를 따로 한다.
                String[] rows = parseZones(rowInput, new HashSet<>(), true);
                if (rows == null) return;
                String[] cols = parseZones(colInput, new HashSet<>(), false);
                if (cols == null) return;
                if (!withinLimit(rowInput, rows.length, Store.MAX_ROWS, "층")) return;
                if (!withinLimit(colInput, cols.length, Store.colLimit(rows), "구역")) return;

                // 기타 구역이 격자에서 만들어지는 이름과 겹치면 기록이 어느 쪽인지 모호해진다.
                HashSet<String> used = new HashSet<>();
                for (String zone : Store.flatten(rows, cols, Store.DEFAULT_SEP)) {
                    used.add(zone.toLowerCase(Locale.ROOT));
                }
                String[] etc = parseZones(etcInput, used, true);
                if (etc == null) return;

                try {
                    Store.setGrid(this, rows, cols, Store.DEFAULT_SEP, etc);
                } catch (IllegalArgumentException e) {
                    colInput.setError(e.getMessage());
                    colInput.requestFocus();
                    return;
                }
                rebuildZoneGrids();
                render();
                Toast.makeText(this, "구역을 저장했어요", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setMessage("구역을 처음 설치했을 때의 기본값으로 되돌릴까요?\n"
                                    + "기존 주차 기록은 그대로 남아요.")
                            .setPositiveButton("되돌리기", (d, w) -> {
                                Store.resetZones(this);
                                rebuildZoneGrids();
                                render();
                                Toast.makeText(this, "기본 구역으로 되돌렸어요",
                                        Toast.LENGTH_SHORT).show();
                                dlg.dismiss();
                            })
                            .setNegativeButton("취소", null)
                            .show());
        });
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private EditText zoneEditor(String[] zones, int minLines) {
        EditText input = new EditText(this);
        input.setText(joinLines(zones));
        input.setTextSize(15);
        input.setTextColor(colorText);
        input.setHintTextColor(colorSubtext);
        input.setGravity(Gravity.TOP);
        input.setMinLines(minLines);
        input.setMaxLines(8);
        input.setBackgroundResource(R.drawable.bg_button);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false);
        return input;
    }

    private String[] parseZones(EditText input, HashSet<String> used, boolean emptyAllowed) {
        ArrayList<String> zones = new ArrayList<>();
        String[] lines = input.getText().toString().split("\\r?\\n", -1);
        for (String line : lines) {
            String zone = line.trim();
            if (zone.isEmpty()) continue;
            if (zone.length() > MAX_ZONE_NAME_LENGTH) {
                input.setError("구역 이름은 " + MAX_ZONE_NAME_LENGTH + "자까지 입력할 수 있어요");
                input.requestFocus();
                return null;
            }
            String key = zone.toLowerCase(Locale.ROOT);
            if (!used.add(key)) {
                input.setError("중복된 구역이 있어요: " + zone);
                input.requestFocus();
                return null;
            }
            zones.add(zone);
            if (zones.size() > MAX_ZONES_PER_GROUP) {
                input.setError("각 목록은 " + MAX_ZONES_PER_GROUP + "개까지 저장할 수 있어요");
                input.requestFocus();
                return null;
            }
        }
        if (!emptyAllowed && zones.isEmpty()) {
            input.setError("구역을 하나 이상 입력해 주세요");
            input.requestFocus();
            return null;
        }
        input.setError(null);
        return zones.toArray(new String[0]);
    }

    private boolean withinLimit(EditText input, int count, int max, String label) {
        if (count <= max) return true;
        input.setError(label + "은 " + max + "개까지 만들 수 있어요");
        input.requestFocus();
        return false;
    }

    private TextView settingText(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private String joinLines(String[] values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('\n');
            out.append(value);
        }
        return out.toString();
    }

    // ---------- 주차 기록 수정 · 메모 · 타이머 ----------

    private void showEditLatestRecord() {
        JSONObject latest = Store.latestRecord(this);
        if (latest == null) {
            Toast.makeText(this, "수정할 주차 기록이 없어요", Toast.LENGTH_SHORT).show();
            return;
        }
        showRecordEditor(latest.optString("id"));
    }

    private void showRecordEditor(String recordId) {
        JSONObject record = Store.recordById(this, recordId);
        if (record == null) {
            Toast.makeText(this, "기록을 찾을 수 없어요", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] profileId = {record.optString("p", Store.activeProfileId(this))};
        final String[] vehicleId = {record.optString("c", Store.activeVehicleId(this))};
        final long[] parkedAt = {record.optLong("t", System.currentTimeMillis())};
        final long[] due = {record.optLong("due", 0)};

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));

        TextView profileLabel = settingText("주차장", 14, colorText);
        form.addView(profileLabel);
        Button profileButton = editorButton(profileButtonText(record));
        form.addView(profileButton);

        TextView vehicleLabel = settingText("차량", 14, colorText);
        addTopMargin(form, vehicleLabel, 12);
        Button vehicleButton = editorButton(vehicleButtonText(record));
        form.addView(vehicleButton);

        TextView zoneLabel = settingText("주차 위치", 14, colorText);
        addTopMargin(form, zoneLabel, 12);
        EditText zone = new EditText(this);
        zone.setText(record.optString("z", ""));
        zone.setSingleLine(true);
        zone.setHint("예: B2-A");
        zone.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(zone);

        TextView timeLabel = settingText("주차 시각", 14, colorText);
        addTopMargin(form, timeLabel, 12);
        Button timeButton = editorButton(Store.formatFull(parkedAt[0]));
        form.addView(timeButton);

        TextView memoLabel = settingText("메모 (선택)", 14, colorText);
        addTopMargin(form, memoLabel, 12);
        EditText memo = new EditText(this);
        memo.setText(Store.recordMemo(record));
        memo.setHint("예: 엘리베이터 옆, F기둥");
        memo.setGravity(Gravity.TOP);
        memo.setMinLines(2);
        memo.setMaxLines(4);
        memo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(memo);

        TextView timerLabel = settingText(getString(R.string.parking_timer), 14, colorText);
        addTopMargin(form, timerLabel, 12);
        Button timerButton = editorButton(timerButtonText(due[0]));
        form.addView(timerButton);

        profileButton.setOnClickListener(v -> showProfileChoice(profileId[0], id -> {
            profileId[0] = id;
            profileButton.setText(Store.profileName(this, id));
        }));
        vehicleButton.setOnClickListener(v -> showVehicleChoice(vehicleId[0], id -> {
            vehicleId[0] = id;
            vehicleButton.setText(Store.vehicleName(this, id));
        }));
        timeButton.setOnClickListener(v -> showDateTimePicker(parkedAt[0], value -> {
            parkedAt[0] = value;
            timeButton.setText(Store.formatFull(value));
        }));
        timerButton.setOnClickListener(v -> showTimerOptions(due[0], value -> {
            due[0] = value;
            timerButton.setText(timerButtonText(value));
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("주차 기록 수정")
                .setView(scroll)
                .setPositiveButton("저장", null)
                .setNeutralButton("삭제", null)
                .setNegativeButton("취소", null)
                .create();
        dlg.setOnShowListener(ignored -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String z = zone.getText().toString().trim();
                String m = memo.getText().toString().trim();
                if (z.isEmpty()) {
                    zone.setError("주차 위치를 입력해 주세요");
                    zone.requestFocus();
                    return;
                }
                if (m.length() > MAX_MEMO_LENGTH) {
                    memo.setError("메모는 " + MAX_MEMO_LENGTH + "자까지 입력할 수 있어요");
                    memo.requestFocus();
                    return;
                }
                if (!Store.updateRecord(this, recordId, profileId[0], vehicleId[0], z,
                        parkedAt[0], m, due[0])) {
                    Toast.makeText(this, "기록을 저장하지 못했어요", Toast.LENGTH_SHORT).show();
                    return;
                }
                rebuildZoneGrids();
                render();
                Toast.makeText(this, "주차 기록을 수정했어요", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setMessage("이 주차 기록을 삭제할까요?")
                            .setPositiveButton("삭제", (d, w) -> {
                                Store.deleteRecord(this, recordId);
                                render();
                                dlg.dismiss();
                            })
                            .setNegativeButton("취소", null)
                            .show());
        });
        dlg.show();
    }

    private String profileButtonText(JSONObject record) {
        String id = record.optString("p", "");
        return Store.profileById(this, id) == null
                ? "삭제된 주차장: " + Store.recordProfileName(this, record)
                : Store.recordProfileName(this, record);
    }

    private String vehicleButtonText(JSONObject record) {
        String id = record.optString("c", "");
        return Store.vehicleById(this, id) == null
                ? "삭제된 차량: " + Store.recordVehicleName(this, record)
                : Store.recordVehicleName(this, record);
    }

    private String btButtonText(String bt) {
        return bt == null || bt.trim().isEmpty() ? "고르지 않음 · 수동 기록만" : bt;
    }

    private Button editorButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(colorText);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackgroundResource(R.drawable.bg_button);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        return button;
    }

    private void addTopMargin(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(child, lp);
    }

    private void showProfileChoice(String selected, StringChoiceCallback callback) {
        JSONArray profiles = Store.profiles(this);
        String[] items = new String[profiles.length()];
        int checked = -1;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            String id = profile == null ? "" : profile.optString("id");
            items[i] = profile == null ? "주차장" : profile.optString("n", "주차장");
            if (id.equals(selected)) checked = i;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("주차장 선택")
                .setSingleChoiceItems(items, checked, null)
                .setNegativeButton("취소", null)
                .create();
        dlg.setOnShowListener(ignored -> dlg.getListView().setOnItemClickListener((parent, view, which, id) -> {
            JSONObject profile = profiles.optJSONObject(which);
            if (profile != null) callback.onChosen(profile.optString("id"));
            dlg.dismiss();
        }));
        dlg.show();
    }

    private void showVehicleChoice(String selected, StringChoiceCallback callback) {
        JSONArray vehicles = Store.vehicles(this);
        String[] items = new String[vehicles.length()];
        int checked = -1;
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject vehicle = vehicles.optJSONObject(i);
            String id = vehicle == null ? "" : vehicle.optString("id");
            String name = vehicle == null ? "차량" : vehicle.optString("n", "차량");
            String bt = vehicle == null ? "" : vehicle.optString("b", "");
            items[i] = bt.isEmpty() ? name + " · 수동 기록" : name + " · " + bt;
            if (id.equals(selected)) checked = i;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("차량 선택")
                .setSingleChoiceItems(items, checked, null)
                .setNegativeButton("취소", null)
                .create();
        dlg.setOnShowListener(ignored -> dlg.getListView().setOnItemClickListener((parent, view, which, id) -> {
            JSONObject vehicle = vehicles.optJSONObject(which);
            if (vehicle != null) callback.onChosen(vehicle.optString("id"));
            dlg.dismiss();
        }));
        dlg.show();
    }

    private void showDateTimePicker(long initial, LongChoiceCallback callback) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(initial);
        new DatePickerDialog(this, (view, year, month, day) ->
                new TimePickerDialog(this, (timeView, hour, minute) -> {
                    Calendar chosen = Calendar.getInstance();
                    chosen.set(year, month, day, hour, minute, 0);
                    chosen.set(Calendar.MILLISECOND, 0);
                    callback.onChosen(chosen.getTimeInMillis());
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show(),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showQuickTimerOptions(String recordId) {
        JSONObject record = Store.recordById(this, recordId);
        if (record == null) return;
        showTimerOptions(record.optLong("due", 0), due -> {
            Store.setParkingTimer(this, recordId, due);
            render();
            Toast.makeText(this, due > 0
                            ? getString(R.string.parking_timer) + "을 설정했어요"
                            : getString(R.string.parking_timer) + "을 해제했어요",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void showTimerOptions(long currentDue, LongChoiceCallback callback) {
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Long> values = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (currentDue > now) {
            items.add(getString(R.string.parking_timer) + " 해제");
            values.add(0L);
        }
        items.add("30분 후");
        values.add(now + 30 * 60_000L);
        items.add("1시간 후");
        values.add(now + 60 * 60_000L);
        items.add("2시간 후");
        values.add(now + 2 * 60 * 60_000L);
        items.add("직접 지정");
        values.add(-1L);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.parking_timer))
                .setMessage(getString(R.string.timer_notice))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    long value = values.get(which);
                    if (value >= 0) callback.onChosen(value);
                    else showFutureDateTimePicker(now + 60 * 60_000L, callback);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showFutureDateTimePicker(long initial, LongChoiceCallback callback) {
        showDateTimePicker(initial, value -> {
            if (value <= System.currentTimeMillis()) {
                Toast.makeText(this, "현재 시각 이후로 설정해 주세요", Toast.LENGTH_SHORT).show();
                return;
            }
            callback.onChosen(value);
        });
    }

    private String timerButtonText(long due) {
        return due > System.currentTimeMillis()
                ? "설정됨 · " + Store.formatFull(due)
                : "설정 안 함";
    }

    // ---------- 습관 체크 ----------

    private void showAddHabit() {
        EditText input = new EditText(this);
        input.setHint("예: 영양제");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        showInputDialog("체크 항목 추가", input, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            if (Store.habitByName(this, name) != null) {
                Toast.makeText(this, "이미 있는 항목이에요", Toast.LENGTH_SHORT).show();
                return;
            }
            Store.addHabit(this, name);
            Toast.makeText(this, "항목을 길게 누르면 리마인더를 설정할 수 있어요",
                    Toast.LENGTH_LONG).show();
            renderHabits();
        });
    }

    private void showHabitOptions(int index) {
        JSONObject h = Store.habits(this).optJSONObject(index);
        if (h == null) return;
        String name = h.optString("n");
        int r = h.optInt("r", -1);

        ArrayList<String> items = new ArrayList<>();
        items.add(r < 0 ? "리마인더 설정"
                : "리마인더 변경 (" + Store.formatMinutesOfDay(r) + ")");
        if (r >= 0) items.add("리마인더 해제");
        items.add("삭제");

        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        pickReminderTime(index, r);
                    } else if (r >= 0 && which == 1) {
                        Store.setReminder(this, index, -1);
                        Reminders.cancel(this, name);
                        Toast.makeText(this, "리마인더 해제", Toast.LENGTH_SHORT).show();
                    } else {
                        confirmDeleteHabit(index, name);
                    }
                })
                .show();
    }

    private void pickReminderTime(int index, int current) {
        int init = current >= 0 ? current : 21 * 60; // 기본 오후 9시
        new TimePickerDialog(this, (view, hh, mm) -> {
            Store.setReminder(this, index, hh * 60 + mm);
            Reminders.scheduleAll(this);
            Toast.makeText(this,
                    "매일 " + Store.formatMinutesOfDay(hh * 60 + mm) + "에 알림",
                    Toast.LENGTH_SHORT).show();
        }, init / 60, init % 60, false).show();
    }

    private void confirmDeleteHabit(int index, String name) {
        new AlertDialog.Builder(this)
                .setMessage("'" + name + "'을(를) 삭제할까요? 기록도 함께 지워져요.")
                .setPositiveButton("삭제", (d, w) -> {
                    Store.deleteHabit(this, index);
                    Reminders.cancel(this, name);
                    renderHabits();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- 렌더링 ----------

    private void render() {
        JSONArray h = Store.activeHistory(this);
        String currentZone = null;
        btnProfile.setText(Store.activeProfileName(this) + " ▾");

        if (h.length() == 0) {
            statusZone.setText(getString(R.string.empty_location));
            statusZone.setTextColor(colorSubtext);
            statusTime.setText(Store.activeVehicleName(this)
                    + " · 나가기 전에 한 번만 남겨두세요");
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
            statusTime.setText(Store.formatFull(t) + " · " + Store.formatRelative(t));
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
            statusMeta.setText(meta);
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

    private void renderHabits() {
        JSONArray hs = Store.habits(this);
        habitList.removeAllViews();

        if (hs.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("'추가'를 눌러 반복할 일을 등록해 보세요");
            empty.setTextSize(13);
            empty.setTextColor(colorSubtext);
            empty.setPadding(dp(8), dp(8), dp(8), dp(8));
            habitList.addView(empty);
            return;
        }

        for (int i = 0; i < hs.length(); i++) {
            final int index = i;
            JSONObject h = hs.optJSONObject(i);
            boolean done = Store.checkedToday(h);
            int streak = Store.streak(h);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_button);
            row.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
            rlp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.setLayoutParams(rlp);

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
            String s;
            if (done) {
                s = Store.formatShort(h.optLong("lt", 0));
                if (streak >= 2) s += " · " + streak + "일 연속";
            } else {
                s = streak >= 1 ? streak + "일 연속" : "";
            }
            info.setText(s);
            info.setTextSize(12);
            info.setTextColor(colorSubtext);

            row.addView(mark);
            row.addView(col);
            row.addView(info);
            row.setOnClickListener(v -> {
                Store.toggleToday(this, index);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                renderHabits();
            });
            row.setOnLongClickListener(v -> {
                showHabitOptions(index);
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
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_button);
            row.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(dp(4), dp(3), dp(4), dp(3));
            row.setLayoutParams(rowLp);

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
            row.setOnClickListener(v -> showRecordEditor(recordId));
            row.setContentDescription(e.optString("z", "주차 기록") + " 기록 수정");
            historyList.addView(row);
        }
    }

    // ---------- 공용 ----------

    /** EditText 하나짜리 입력 다이얼로그 (키보드 자동 표시) */
    private void showInputDialog(String title, EditText input, Runnable onSave) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("저장", (d, w) -> onSave.run())
                .setNegativeButton("취소", null)
                .create();
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        input.requestFocus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
