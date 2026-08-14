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
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 첫 실행 안내. 주차장 격자와 차량 블루투스를 여기서 정하므로,
 * 남의 기기에 개발자 차 이름 같은 기본값이 남지 않는다.
 *
 * <p>타이핑 없이 넘어갈 수 있게 모든 단계에 기본값을 채워 두고, 격자는 버튼으로만 조절한다.
 */
public class OnboardingActivity extends Activity {

    private static final int REQ_BLUETOOTH = 10;
    private static final int REQ_FINISH = 11;
    private static final int LAST_STEP = 3;

    private interface IntChange {
        void onChange(int value);
    }

    private int step;
    private String profileName = "우리 집";
    private boolean useFloors = true;
    private int floorCount = 3;
    private int zoneCount = 2;
    private String vehicleName = "내 차";
    private String btName = "";

    private TextView btValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 화면을 돌렸다고 설정을 처음부터 다시 하게 만들지 않는다.
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step", step);
            profileName = savedInstanceState.getString("profileName", profileName);
            useFloors = savedInstanceState.getBoolean("useFloors", useFloors);
            floorCount = savedInstanceState.getInt("floorCount", floorCount);
            zoneCount = savedInstanceState.getInt("zoneCount", zoneCount);
            vehicleName = savedInstanceState.getString("vehicleName", vehicleName);
            btName = savedInstanceState.getString("btName", btName);
        }
        render();
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
    }

    // ---------- 화면 ----------

    private void render() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.bg));
        page.setPadding(dp(24), dp(28), dp(24), dp(20));

        page.addView(text("STEP " + (step + 1) + " / " + (LAST_STEP + 1), 12,
                R.color.accent_text, true));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(6);

        if (step == 0) buildProfileStep(body);
        else if (step == 1) buildGridStep(body);
        else if (step == 2) buildVehicleStep(body);
        else buildPermissionStep(body);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        page.addView(scroll, bodyLp);
        page.addView(buildNav());

        setContentView(page);
        if (Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(dp(24), dp(28) + bars.top, dp(24), dp(20) + bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
    }

    private void buildProfileStep(LinearLayout body) {
        body.addView(text("어디에 주차하세요?", 26, R.color.text, true));
        body.addView(gap(text("자주 대는 주차장 이름이에요. 나중에 회사·부모님 댁처럼 "
                + "더 추가할 수 있어요.", 14, R.color.subtext, false), 8));

        EditText input = field(profileName, "예: 우리 집");
        input.addTextChangedListener(new SimpleWatcher(value -> profileName = value));
        body.addView(gap(input, 24));
    }

    private void buildGridStep(LinearLayout body) {
        body.addView(text("주차장이 어떻게 나뉘어 있나요?", 26, R.color.text, true));
        body.addView(gap(text("층만 기억해선 못 찾는 곳이라면 구역까지 나눠 두세요.",
                14, R.color.subtext, false), 8));

        LinearLayout choice = new LinearLayout(this);
        choice.setOrientation(LinearLayout.HORIZONTAL);
        choice.addView(choiceButton("층 + 구역\nB2-C", useFloors, () -> {
            useFloors = true;
            render();
        }));
        choice.addView(choiceButton("층만\nB2", !useFloors, () -> {
            useFloors = false;
            if (floorCount < 2) floorCount = 2;
            render();
        }));
        body.addView(gap(choice, 16));

        // 층만 쓰는 경우 층이 곧 버튼이므로 최소 2개는 있어야 고를 의미가 있다.
        body.addView(gap(stepper("지하 몇 층까지 있나요?", floorCount, useFloors ? 1 : 2,
                Store.MAX_ROWS,
                value -> {
                    floorCount = value;
                    render();
                }), 16));
        if (useFloors) {
            body.addView(gap(stepper("한 층에 구역이 몇 개인가요?", zoneCount, 2, Store.MAX_COLS,
                    value -> {
                        zoneCount = value;
                        render();
                    }), 8));
        }

        String[] rows = gridRows();
        String[] cols = gridCols();
        body.addView(gap(text("미리보기", 12, R.color.subtext, true), 20));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        ZoneGrid.build(this, preview, rows, cols, Store.DEFAULT_SEP, true, null);
        body.addView(gap(preview, 6));

        String[] zones = Store.flatten(rows, cols, Store.DEFAULT_SEP);
        String sample = zones.length == 0 ? "" : " · 예: " + zones[0] + " 처럼 저장돼요";
        body.addView(gap(text("버튼 " + zones.length + "개" + sample + "\n"
                + "이름은 설정에서 언제든 바꿀 수 있어요.", 13, R.color.subtext, false), 8));
    }

    private void buildVehicleStep(LinearLayout body) {
        body.addView(text("어떤 차를 타세요?", 26, R.color.text, true));
        body.addView(gap(text("차 블루투스가 끊기면 = 차에서 내리면, 주차 위치를 남길지 "
                + "알림으로 물어봐요.", 14, R.color.subtext, false), 8));

        EditText input = field(vehicleName, "예: 내 차, 아빠 차");
        input.addTextChangedListener(new SimpleWatcher(value -> vehicleName = value));
        body.addView(gap(input, 20));

        body.addView(gap(text("차 블루투스", 13, R.color.subtext, true), 16));
        btValue = text(btLabel(), 15, R.color.text, false);
        btValue.setBackgroundResource(R.drawable.bg_button);
        btValue.setPadding(dp(14), dp(14), dp(14), dp(14));
        btValue.setOnClickListener(v -> pickBluetoothDevice());
        body.addView(gap(btValue, 4));

        body.addView(gap(text("탭하면 페어링된 기기 목록에서 고를 수 있어요. "
                + "지금 차에 안 타고 있다면 비워 두고 나중에 설정에서 골라도 돼요.",
                13, R.color.subtext, false), 8));
    }

    private void buildPermissionStep(LinearLayout body) {
        body.addView(text("마지막이에요", 26, R.color.text, true));
        body.addView(gap(text("아래 권한만 쓰고, 어떤 정보도 기기 밖으로 내보내지 않아요.",
                14, R.color.subtext, false), 8));

        body.addView(gap(permissionRow("알림",
                "주차 위치를 남길지 묻고, 출차 시간을 알려 줘요."), 20));
        body.addView(gap(permissionRow("근처 기기 (블루투스)",
                "차 블루투스가 끊기는 순간만 감지해요. 위치 추적은 하지 않아요."), 12));

        body.addView(gap(text("상주 알림이나 배터리 최적화 해제는 요구하지 않아요.",
                13, R.color.accent_text, false), 20));
    }

    private View permissionRow(String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.addView(text(title, 15, R.color.text, true));
        row.addView(gap(text(detail, 13, R.color.subtext, false), 2));
        return row;
    }

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        navLp.topMargin = dp(12);
        nav.setLayoutParams(navLp);

        TextView back = text(step == 0 ? "건너뛰기" : "이전", 14, R.color.subtext, false);
        back.setPadding(dp(4), dp(12), dp(16), dp(12));
        back.setOnClickListener(v -> {
            if (step == 0) confirmSkip();
            else {
                step--;
                render();
            }
        });
        nav.addView(back);

        View filler = new View(this);
        nav.addView(filler, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button next = new Button(this);
        next.setText(step == LAST_STEP ? "시작하기" : "다음");
        next.setAllCaps(false);
        next.setTextSize(15);
        next.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        next.setTextColor(getColor(R.color.on_accent));
        next.setBackgroundResource(R.drawable.bg_button_active);
        next.setStateListAnimator(null);
        next.setPadding(dp(28), 0, dp(28), 0);
        next.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(50)));
        next.setOnClickListener(v -> onNext());
        nav.addView(next);
        return nav;
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

    private EditText field(String value, String hint) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.subtext));
        input.setBackgroundResource(R.drawable.bg_button);
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSelection(input.getText().length());
        return input;
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
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

    private View stepButton(String label, boolean enabled, Runnable onTap) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(19);
        b.setTextColor(getColor(enabled ? R.color.text : R.color.outline));
        b.setBackgroundResource(R.drawable.bg_button);
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setEnabled(enabled);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(42)));
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
        if (step == 0 && profileName.trim().isEmpty()) {
            Toast.makeText(this, "주차장 이름을 입력해 주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        if (step < LAST_STEP) {
            step++;
            render();
            return;
        }
        requestPermissionsThenFinish();
    }

    private void confirmSkip() {
        new AlertDialog.Builder(this)
                .setMessage("기본 설정(지하 3층 · 구역 2개)으로 시작할까요?\n"
                        + "설정에서 언제든 바꿀 수 있어요.")
                .setPositiveButton("이대로 시작", (d, w) -> {
                    Store.skipOnboarding(this);
                    openMain();
                })
                .setNegativeButton("계속 설정", null)
                .show();
    }

    private void requestPermissionsThenFinish() {
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
        if (need.isEmpty()) {
            saveAndOpenMain();
            return;
        }
        requestPermissions(need.toArray(new String[0]), REQ_FINISH);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_FINISH) {
            // 권한을 거부해도 수동 기록은 되므로 온보딩은 그대로 끝낸다.
            saveAndOpenMain();
            return;
        }
        if (requestCode != REQ_BLUETOOTH) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            showBondedDevices();
        } else {
            Toast.makeText(this, "블루투스 권한이 없으면 기기 목록을 볼 수 없어요",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndOpenMain() {
        Store.completeOnboarding(this, profileName, gridRows(), gridCols(),
                Store.DEFAULT_SEP, vehicleName, btName);
        openMain();
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // ---------- 블루투스 ----------

    private String btLabel() {
        return btName.isEmpty() ? "고르지 않음 · 수동 기록만" : btName;
    }

    private void pickBluetoothDevice() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BLUETOOTH);
            return;
        }
        showBondedDevices();
    }

    private void showBondedDevices() {
        BtPicker.show(this, btName, this::setBtName);
    }

    private void setBtName(String name) {
        btName = name == null ? "" : name.trim();
        if (btValue != null) btValue.setText(btLabel());
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
