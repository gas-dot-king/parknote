package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * 층 · 구역 · 기타 구역을 한 줄에 하나씩 편집한다.
 *
 * <p>다이얼로그가 아니라 <b>액티비티</b>인 이유: 여기가 앱에서 입력량이 가장 많은 화면인데,
 * AlertDialog로 띄우면 화면을 돌리는 순간 입력이 통째로 사라진다. 액티비티로 두고
 * EditText에 고정 id를 주면 프레임워크가 회전 시 텍스트를 알아서 지켜 준다.
 *
 * <p>층과 구역을 조합해 버튼이 만들어지므로 층 3개 × 구역 4개면 12칸 격자가 되고,
 * 층을 비우면 구역 이름이 그대로 버튼이 된다.
 */
public class ZoneSettingsActivity extends Activity {

    private static final int MAX_ZONES_PER_GROUP = 30;
    private static final int MAX_ZONE_NAME_LENGTH = 24;

    private EditText rowInput;
    private EditText colInput;
    private EditText etcInput;
    private LinearLayout preview;
    private TextView previewNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildScreen());
        // savedInstanceState가 있으면 프레임워크가 EditText 텍스트를 이미 복원해 두었다.
        // 우리가 다시 채우면 편집 중이던 내용을 저장값으로 덮어써 버린다.
        if (savedInstanceState == null) fillFromStore();
        rowInput.post(this::refreshPreview);
    }

    // ---------- 화면 ----------

    private View buildScreen() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.bg));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        scroll.addView(body);

        body.addView(Ui.text(this, getString(R.string.zone_edit), 24, R.color.text, true));
        Ui.add(body, Ui.hint(this, getString(R.string.zone_edit_guide)), 8);

        rowInput = zoneField(R.id.zoneRows, R.string.zone_rows_hint, R.string.zone_rows_cd);
        Ui.addField(body, getString(R.string.zone_rows_label, Store.MAX_ROWS), rowInput, 20);

        colInput = zoneField(R.id.zoneCols, R.string.zone_cols_hint, R.string.zone_cols_cd);
        Ui.addField(body, getString(R.string.zone_cols_label,
                Store.MAX_COLS, Store.MAX_FLAT_ZONES), colInput, 16);

        etcInput = zoneField(R.id.zoneEtc, R.string.zone_etc_hint, R.string.zone_etc_cd);
        Ui.addField(body, getString(R.string.zone_etc_label), etcInput, 16);

        // 예전에는 저장하고 화면으로 돌아가야만 결과를 볼 수 있었다. 온보딩에는 있던
        // 미리보기를 여기에도 둔다. 같은 ZoneGrid를 쓰므로 실제 화면과 어긋나지 않는다.
        Ui.add(body, Ui.text(this, getString(R.string.zone_preview),
                12, R.color.subtext, true), 22);
        preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        Ui.add(body, preview, 6);
        previewNote = Ui.hint(this, "");
        Ui.add(body, previewNote, 6);

        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(buildActionBar());

        if (Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        return page;
    }

    private EditText zoneField(int id, int hintRes, int contentDescriptionRes) {
        EditText input = Ui.multilineInput(this, getString(hintRes), 3, 8);
        input.setId(id); // 회전 시 프레임워크가 텍스트를 지켜 주는 조건
        input.setContentDescription(getString(contentDescriptionRes));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                refreshPreview();
            }
        });
        return input;
    }

    private View buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));

        bar.addView(barButton(getString(R.string.action_reset), false, this::confirmReset));
        View filler = new View(this);
        bar.addView(filler, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(barButton(getString(R.string.action_cancel), false, this::finish));
        bar.addView(barButton(getString(R.string.action_save), true, this::save));
        return bar;
    }

    private Button barButton(String label, boolean primary, Runnable onTap) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(getColor(primary ? R.color.on_accent : R.color.subtext));
        b.setBackgroundResource(primary ? R.drawable.bg_button_active : R.drawable.bg_button);
        b.setStateListAnimator(null);
        b.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        lp.leftMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    private void fillFromStore() {
        rowInput.setText(joinLines(Store.activeRows(this)));
        colInput.setText(joinLines(Store.activeCols(this)));
        etcInput.setText(joinLines(Store.etcZones(this)));
    }

    // ---------- 미리보기 ----------

    private void refreshPreview() {
        String[] rows = readLines(rowInput);
        String[] cols = readLines(colInput);
        // 검증 전 입력이라 상한을 넘을 수 있다. 미리보기에서만 잘라 그린다.
        String[] shownRows = clamp(rows, Store.MAX_ROWS);
        String[] shownCols = clamp(cols, Store.colLimit(shownRows));
        ZoneGrid.build(this, preview, shownRows, shownCols, Store.DEFAULT_SEP, true, null);

        int count = Store.flatten(shownCols.length == 0 ? new String[0] : shownRows,
                shownCols, Store.DEFAULT_SEP).length;
        String note;
        if (cols.length == 0) {
            note = getString(R.string.zone_required);
        } else {
            note = getString(R.string.zone_preview_count, count);
            String[] etc = readLines(etcInput);
            if (etc.length > 0) note = getString(R.string.zone_preview_etc, note, etc.length);
            if (rows.length > shownRows.length || cols.length > shownCols.length) {
                note = getString(R.string.zone_preview_clipped, note);
            }
        }
        previewNote.setText(note);
    }

    private static String[] clamp(String[] values, int max) {
        if (values.length <= max) return values;
        String[] out = new String[max];
        System.arraycopy(values, 0, out, 0, max);
        return out;
    }

    /** 검증 없이 화면에 보이는 그대로 읽는다. 미리보기 전용. */
    private static String[] readLines(EditText input) {
        ArrayList<String> out = new ArrayList<>();
        for (String line : input.getText().toString().split("\\r?\\n", -1)) {
            String zone = line.trim();
            if (!zone.isEmpty()) out.add(zone);
        }
        return out.toArray(new String[0]);
    }

    // ---------- 저장 ----------

    private void save() {
        // 층과 구역은 서로 겹쳐도 되므로(A층 A구역) 중복 검사를 목록별로 따로 한다.
        String[] rows = parseZones(rowInput, new HashSet<>(), true);
        if (rows == null) return;
        String[] cols = parseZones(colInput, new HashSet<>(), false);
        if (cols == null) return;
        if (!withinLimit(rowInput, rows.length, Store.MAX_ROWS,
                getString(R.string.zone_rows_name))) return;
        if (!withinLimit(colInput, cols.length, Store.colLimit(rows),
                getString(R.string.zone_cols_name))) return;

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
        setResult(RESULT_OK);
        Toast.makeText(this, R.string.zone_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.zone_reset_confirm)
                .setPositiveButton(R.string.zone_reset_action, (d, w) -> {
                    Store.resetZones(this);
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.zone_reset_done, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String[] parseZones(EditText input, HashSet<String> used, boolean emptyAllowed) {
        ArrayList<String> zones = new ArrayList<>();
        for (String line : input.getText().toString().split("\\r?\\n", -1)) {
            String zone = line.trim();
            if (zone.isEmpty()) continue;
            if (zone.length() > MAX_ZONE_NAME_LENGTH) {
                return fail(input,
                        getString(R.string.zone_name_too_long, MAX_ZONE_NAME_LENGTH));
            }
            if (!used.add(zone.toLowerCase(Locale.ROOT))) {
                return fail(input, getString(R.string.zone_duplicate, zone));
            }
            zones.add(zone);
            if (zones.size() > MAX_ZONES_PER_GROUP) {
                return fail(input,
                        getString(R.string.zone_group_too_many, MAX_ZONES_PER_GROUP));
            }
        }
        if (!emptyAllowed && zones.isEmpty()) {
            return fail(input, getString(R.string.zone_required));
        }
        input.setError(null);
        return zones.toArray(new String[0]);
    }

    private String[] fail(EditText input, String message) {
        input.setError(message);
        input.requestFocus();
        return null;
    }

    private boolean withinLimit(EditText input, int count, int max, String label) {
        if (count <= max) return true;
        input.setError(getString(R.string.zone_limit, label, max));
        input.requestFocus();
        return false;
    }

    private static String joinLines(String[] values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('\n');
            out.append(value);
        }
        return out.toString();
    }
}
