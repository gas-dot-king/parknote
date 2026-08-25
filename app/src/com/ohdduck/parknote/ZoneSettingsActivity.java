package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/**
 * 층 · 구역 · 기타 구역을 블록으로 편집한다.
 *
 * <p>예전에는 여러 줄 EditText 세 개였다. "한 줄에 하나씩 적으세요"라는 규칙을 사람이
 * 지켜야 했고, 순서를 바꾸려면 텍스트를 잘라 옮겨 붙여야 했으며, 편집 중인 글자가
 * 결국 화면의 어느 버튼이 되는지 눈에 보이지 않았다. 이제 편집 대상이 실제로 생길
 * 버튼과 같은 모양의 블록이라, 편집 화면이 그 자체로 미리보기다.
 *
 * <p>다이얼로그가 아니라 <b>액티비티</b>인 이유는 그대로다: 여기가 앱에서 입력이 가장
 * 많은 화면이라 회전에 살아남아야 한다. 다만 이제 프레임워크의 EditText 자동 복원에
 * 기대지 않고 목록 자체를 {@link #onSaveInstanceState}에 담는다.
 */
public class ZoneSettingsActivity extends Activity {

    private static final int MAX_ZONES_PER_GROUP = 30;
    private static final int MAX_ZONE_NAME_LENGTH = 24;

    private static final String STATE_ROWS = "rows";
    private static final String STATE_COLS = "cols";
    private static final String STATE_ETC = "etc";

    /** 편집 중인 목록. 저장을 누르기 전까지 Store는 건드리지 않는다. */
    private final ArrayList<String> rows = new ArrayList<>();
    private final ArrayList<String> cols = new ArrayList<>();
    private final ArrayList<String> etc = new ArrayList<>();

    private TextView rowsLabel;
    private TextView colsLabel;
    private TextView etcLabel;
    private LinearLayout rowsBlocks;
    private LinearLayout colsBlocks;
    private LinearLayout etcBlocks;
    private LinearLayout preview;
    private TextView previewNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zone_settings);

        rowsLabel = findViewById(R.id.rowsLabel);
        colsLabel = findViewById(R.id.colsLabel);
        etcLabel = findViewById(R.id.etcLabel);
        rowsBlocks = findViewById(R.id.rowsBlocks);
        colsBlocks = findViewById(R.id.colsBlocks);
        etcBlocks = findViewById(R.id.etcBlocks);
        preview = findViewById(R.id.zonePreview);
        previewNote = findViewById(R.id.zonePreviewNote);

        findViewById(R.id.zoneSave).setOnClickListener(v -> save());
        findViewById(R.id.zoneCancel).setOnClickListener(v -> finish());
        findViewById(R.id.zoneReset).setOnClickListener(v -> confirmReset());

        if (savedInstanceState == null) {
            rows.addAll(Arrays.asList(Store.activeRows(this)));
            cols.addAll(Arrays.asList(Store.activeCols(this)));
            etc.addAll(Arrays.asList(Store.etcZones(this)));
        } else {
            restore(savedInstanceState, STATE_ROWS, rows);
            restore(savedInstanceState, STATE_COLS, cols);
            restore(savedInstanceState, STATE_ETC, etc);
        }
        applyWindowInsets();
        render();
    }

    private void restore(Bundle state, String key, ArrayList<String> into) {
        ArrayList<String> saved = state.getStringArrayList(key);
        if (saved != null) into.addAll(saved);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList(STATE_ROWS, rows);
        out.putStringArrayList(STATE_COLS, cols);
        out.putStringArrayList(STATE_ETC, etc);
    }

    private void applyWindowInsets() {
        if (Build.VERSION.SDK_INT < 35) return;
        findViewById(R.id.zoneRoot).setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    // ---------- 그리기 ----------

    private void render() {
        int colMax = Store.colLimit(rows.toArray(new String[0]));

        rowsLabel.setText(getString(R.string.zone_rows_blocks_label,
                rows.size(), Store.MAX_ROWS));
        colsLabel.setText(getString(R.string.zone_cols_blocks_label, cols.size(), colMax));
        etcLabel.setText(getString(R.string.zone_etc_blocks_label,
                etc.size(), MAX_ZONES_PER_GROUP));

        ZoneBlocks.build(this, rowsBlocks, rows, Store.MAX_ROWS, true,
                listener(rows, R.string.zone_rows_name));
        ZoneBlocks.build(this, colsBlocks, cols, colMax, true,
                listener(cols, R.string.zone_cols_name));
        ZoneBlocks.build(this, etcBlocks, etc, MAX_ZONES_PER_GROUP, true,
                listener(etc, R.string.zone_etc_blocks_name));

        renderPreview();
    }

    /** 세 목록이 같은 편집 동작을 공유한다. 다른 건 대상 목록과 이름표뿐이다. */
    private ZoneBlocks.Listener listener(ArrayList<String> target, int nameRes) {
        return new ZoneBlocks.Listener() {
            @Override
            public void onEdit(int index) {
                showEditDialog(target, index, nameRes);
            }

            @Override
            public void onAdd() {
                showAddDialog(target, nameRes);
            }

            @Override
            public void onMove(int from, int to) {
                if (from < 0 || from >= target.size() || to < 0 || to >= target.size()) return;
                // 끌어다 놓은 자리로 밀어 넣는다. 자리 교환이 아니라 삽입이라야
                // "B3를 맨 앞으로" 같은 이동이 나머지 순서를 흐트러뜨리지 않는다.
                target.add(to, target.remove(from));
                render();
            }
        };
    }

    private void renderPreview() {
        // 상한을 넘을 수 있는 상태는 만들지 않지만, 미리보기는 방어적으로 잘라 그린다.
        String[] shownRows = rows.toArray(new String[0]);
        String[] shownCols = cols.toArray(new String[0]);
        ZoneGrid.build(this, preview, shownRows, shownCols, Store.DEFAULT_SEP, true, null);

        String note;
        if (cols.isEmpty()) {
            note = getString(R.string.zone_required);
        } else {
            int count = Store.flatten(shownRows, shownCols, Store.DEFAULT_SEP).length;
            note = getString(R.string.zone_preview_count, count);
            if (!etc.isEmpty()) note = getString(R.string.zone_preview_etc, note, etc.size());
        }
        previewNote.setText(note);
    }

    // ---------- 편집 ----------

    private void showAddDialog(ArrayList<String> target, int nameRes) {
        showNameDialog(target, -1, nameRes, "");
    }

    private void showEditDialog(ArrayList<String> target, int index, int nameRes) {
        if (index < 0 || index >= target.size()) return;
        showNameDialog(target, index, nameRes, target.get(index));
    }

    /**
     * 이름 한 칸 다이얼로그. index가 -1이면 추가, 아니면 수정이다.
     *
     * <p>수정일 때만 삭제 버튼을 단다. 추가 중에 "삭제"가 보이면 무엇이 지워지는지
     * 알 수 없다.
     */
    private void showNameDialog(ArrayList<String> target, int index, int nameRes,
                                String initial) {
        boolean editing = index >= 0;
        String groupName = getString(nameRes);
        EditText input = Ui.input(this, getString(R.string.zone_block_hint));
        input.setText(initial);
        input.setSelection(input.getText().length());

        LinearLayout form = Ui.form(this);
        form.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(editing
                        ? R.string.zone_block_edit_title : R.string.zone_block_add_title,
                        groupName))
                .setView(form)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, null);
        if (editing) builder.setNeutralButton(R.string.action_delete, null);

        AlertDialog dialog = Ui.validating(builder, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError(getString(R.string.err_zone_required));
                return false;
            }
            if (name.length() > MAX_ZONE_NAME_LENGTH) {
                input.setError(getString(R.string.zone_name_too_long, MAX_ZONE_NAME_LENGTH));
                return false;
            }
            // 같은 목록 안의 중복만 막는다. 층 "A"와 구역 "A"는 서로 겹쳐도 되고,
            // 실제로 A동 A구역 같은 주차장이 있다.
            for (int i = 0; i < target.size(); i++) {
                if (i == index) continue;
                if (target.get(i).equalsIgnoreCase(name)) {
                    input.setError(getString(R.string.zone_duplicate, name));
                    return false;
                }
            }
            if (editing) target.set(index, name);
            else target.add(name);
            render();
            return true;
        }, d -> {
            // 중립 버튼 = 삭제
            target.remove(index);
            render();
            d.dismiss();
        });
        Ui.showWithKeyboard(dialog, input);
    }

    // ---------- 저장 ----------

    private void save() {
        if (cols.isEmpty()) {
            Toast.makeText(this, R.string.zone_required, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] rowArray = rows.toArray(new String[0]);
        String[] colArray = cols.toArray(new String[0]);

        // 기타 구역이 격자에서 만들어지는 이름과 겹치면 기록이 어느 쪽인지 모호해진다.
        HashSet<String> gridNames = new HashSet<>();
        for (String zone : Store.flatten(rowArray, colArray, Store.DEFAULT_SEP)) {
            gridNames.add(zone.toLowerCase(Locale.ROOT));
        }
        for (String zone : etc) {
            if (gridNames.contains(zone.toLowerCase(Locale.ROOT))) {
                Toast.makeText(this, getString(R.string.err_zone_grid_overlap, zone),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        try {
            Store.setGrid(this, rowArray, colArray, Store.DEFAULT_SEP,
                    etc.toArray(new String[0]));
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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
}
