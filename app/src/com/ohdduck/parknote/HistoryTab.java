package com.ohdduck.parknote;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 기록 탭. 저장된 주차 기록 전체를 한 줄씩 그린다.
 *
 * <p>예전에는 이 목록이 {@code setItems} 다이얼로그였고, 거기로 가는 길은 설정
 * 메뉴의 다섯 번째 항목뿐이었다. 삭제한 주차장·차량의 기록까지 남아 있는 유일한
 * 화면인데도 사실상 숨어 있었다.
 *
 * <p>홈의 "최근 기록"과 달리 여기는 현재 맥락(주차장×차량)으로 거르지 않는다.
 * 차를 두 대 쓰거나 주차장을 지운 뒤에도 과거 기록을 찾을 수 있어야 하기 때문이다.
 */
class HistoryTab {

    /** 기록을 눌렀을 때. 편집기를 여는 일은 MainActivity가 맡는다(회전 복원 때문). */
    interface OnOpen {
        void open(String recordId);
    }

    private final Activity host;
    private final OnOpen onOpen;
    private final TextView hint;
    private final LinearLayout list;

    HistoryTab(Activity host, OnOpen onOpen) {
        this.host = host;
        this.onOpen = onOpen;
        this.hint = host.findViewById(R.id.historyAllHint);
        this.list = host.findViewById(R.id.historyAllList);
    }

    void render() {
        JSONArray history = Store.history(host);
        list.removeAllViews();

        if (history.length() == 0) {
            hint.setText(R.string.history_tab_empty);
            return;
        }
        hint.setText(host.getString(R.string.history_tab_hint, Store.MAX_HISTORY));

        for (int i = 0; i < history.length(); i++) {
            JSONObject record = history.optJSONObject(i);
            if (record == null) continue;
            list.addView(row(record));
        }
    }

    private View row(JSONObject record) {
        String recordId = record.optString("id", "");
        String zone = record.optString("z", "?");

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        row.setPadding(Ui.dp(host, 16), Ui.dp(host, 12), Ui.dp(host, 16), Ui.dp(host, 12));
        row.setMinimumHeight(host.getResources().getDimensionPixelSize(R.dimen.touch_min));

        TextView name = Ui.text(host, zone, 16, R.color.text, true);

        // 주차장 · 차량 · 메모. 지운 주차장/차량이면 Store가 "(삭제됨)"을 붙여 준다.
        StringBuilder meta = new StringBuilder();
        meta.append(Store.recordProfileName(host, record));
        meta.append(" · ").append(Store.recordVehicleName(host, record));
        String memo = Store.recordMemo(record);
        if (!memo.isEmpty()) meta.append(" · ").append(memo);

        TextView sub = Ui.text(host, meta.toString(), 12, R.color.subtext, false);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(name);
        column.addView(sub);

        TextView time = Ui.text(host, Store.formatFull(record.optLong("t", 0)),
                12, R.color.subtext, false);
        time.setGravity(Gravity.END);
        time.setPadding(Ui.dp(host, 10), 0, 0, 0);

        row.addView(column);
        row.addView(time);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.gutter);
        lp.setMargins(gutter, gutter, gutter, gutter);
        row.setLayoutParams(lp);

        row.setContentDescription(host.getString(R.string.cd_edit_history, zone));
        row.setOnClickListener(v -> onOpen.open(recordId));
        return row;
    }
}
