package com.ohdduck.parknote;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 기록 탭. 저장된 주차 기록 전체를 재활용 목록으로 보여 준다.
 *
 * <p>홈의 "최근 기록"과 달리 여기는 현재 맥락(주차장×차량)으로 거르지 않는다.
 * 차를 두 대 쓰거나 주차장을 지운 뒤에도 과거 기록을 찾을 수 있어야 하기 때문이다.
 * 최대 240개를 모두 View로 만들지 않고 {@link ListView}가 보이는 행만 재사용한다.
 */
class HistoryTab {

    /** 기록을 눌렀을 때. 편집기를 여는 일은 MainActivity가 맡는다(회전 복원 때문). */
    interface OnOpen {
        void open(String recordId);
    }

    private final Activity host;
    private final OnOpen onOpen;
    private final TextView hint;
    private final HistoryAdapter adapter;

    HistoryTab(Activity host, OnOpen onOpen) {
        this.host = host;
        this.onOpen = onOpen;
        this.hint = host.findViewById(R.id.historyAllHint);
        ListView list = host.findViewById(R.id.historyAllList);
        this.adapter = new HistoryAdapter();
        list.setAdapter(adapter);
    }

    void render() {
        JSONArray history = Store.history(host);
        adapter.replace(history);
        if (history.length() == 0) hint.setText(R.string.history_tab_empty);
        else hint.setText(host.getString(R.string.history_tab_hint, Store.MAX_HISTORY));
    }

    private final class HistoryAdapter extends BaseAdapter {
        private JSONArray history = new JSONArray();

        void replace(JSONArray next) {
            history = next == null ? new JSONArray() : next;
            notifyDataSetChanged();
        }

        @Override public int getCount() {
            return history.length();
        }

        @Override public JSONObject getItem(int position) {
            return history.optJSONObject(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                holder = createRow();
                convertView = holder.root;
                convertView.setTag(holder);
                convertView.setOnClickListener(v -> {
                    ViewHolder clicked = (ViewHolder) v.getTag();
                    if (clicked.recordId != null && !clicked.recordId.isEmpty()) {
                        onOpen.open(clicked.recordId);
                    }
                });
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            JSONObject record = getItem(position);
            if (record == null) record = new JSONObject();
            bind(holder, record);
            return convertView;
        }
    }

    private ViewHolder createRow() {
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.gutter);

        LinearLayout wrapper = new LinearLayout(host);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(gutter, gutter, gutter, gutter);
        wrapper.setFocusable(true);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_button);
        row.setDuplicateParentStateEnabled(true);
        row.setPadding(Ui.dp(host, 16), Ui.dp(host, 12),
                Ui.dp(host, 16), Ui.dp(host, 12));
        row.setMinimumHeight(host.getResources().getDimensionPixelSize(R.dimen.touch_min));

        TextView name = Ui.text(host, "", 16, R.color.text, true);
        TextView sub = Ui.text(host, "", 12, R.color.subtext, false);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(name);
        column.addView(sub);

        TextView time = Ui.text(host, "", 12, R.color.subtext, false);
        time.setGravity(Gravity.END);
        time.setPadding(Ui.dp(host, 10), 0, 0, 0);

        row.addView(column);
        row.addView(time);
        wrapper.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ViewHolder holder = new ViewHolder();
        holder.root = wrapper;
        holder.name = name;
        holder.sub = sub;
        holder.time = time;
        return holder;
    }

    private void bind(ViewHolder holder, JSONObject record) {
        holder.recordId = record.optString("id", "");
        String zone = record.optString("z", "?");
        holder.name.setText(zone);

        // 주차장 · 차량 · 메모. 지운 주차장/차량이면 Store가 "삭제됨"을 붙여 준다.
        StringBuilder meta = new StringBuilder();
        meta.append(Store.recordProfileName(host, record));
        meta.append(" · ").append(Store.recordVehicleName(host, record));
        String memo = Store.recordMemo(record);
        if (!memo.isEmpty()) meta.append(" · ").append(memo);
        holder.sub.setText(meta);
        holder.time.setText(Fmt.full(record.optLong("t", 0)));
        holder.root.setContentDescription(host.getString(R.string.cd_edit_history, zone));
    }

    private static final class ViewHolder {
        LinearLayout root;
        TextView name;
        TextView sub;
        TextView time;
        String recordId;
    }
}
