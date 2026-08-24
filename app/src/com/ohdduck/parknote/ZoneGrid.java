package com.ohdduck.parknote;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 층 × 구역 격자를 그린다. 메인 화면(탭해서 기록)과 온보딩·구역 편집(미리보기)이 함께 쓴다.
 *
 * <p>층이 없으면 구역 버튼만 흐르는 1차원 목록이 되므로, 대단지 아파트(B2-C)와
 * 층만 있는 작은 주차장(B2)을 같은 코드로 처리한다.
 */
class ZoneGrid {

    interface OnZoneTap {
        void onTap(String zone, View view);
    }

    private static final int ROW_LABEL_WIDTH_DP = 34;

    /**
     * container를 비우고 격자를 새로 채운다.
     *
     * @param rows     층 라벨. 비어 있으면 1차원 목록으로 그린다.
     * @param cols     구역 라벨. 비어 있으면 아무것도 그리지 않는다.
     * @param compact  온보딩 미리보기처럼 작게 그릴지 여부
     * @param tap      null이면 탭할 수 없는 미리보기가 된다.
     */
    static void build(Context ctx, LinearLayout container, String[] rows, String[] cols,
                      String sep, boolean compact, OnZoneTap tap) {
        container.removeAllViews();
        if (cols == null || cols.length == 0) return;
        boolean grid = rows != null && rows.length > 0;

        int height = dp(ctx, cellHeightDp(cols.length, grid, compact));
        int textSp = cellTextSize(cols.length, grid, compact);
        int labelWidth = dp(ctx, ROW_LABEL_WIDTH_DP);

        if (!grid) {
            // 층이 없으면 기존 목록형 그대로: 한 줄에 2개(구역이 많으면 3개).
            // 버튼에 구역 이름을 통째로 쓴다.
            int perRow = cols.length > 8 ? 3 : 2;
            LinearLayout row = null;
            for (int i = 0; i < cols.length; i++) {
                if (i % perRow == 0) row = addRow(ctx, container);
                row.addView(cell(ctx, cols[i], cols[i], height, textSp, R.color.text, tap));
            }
            if (row != null) fillRow(ctx, row, perRow, height);
            return;
        }

        // 격자에서는 왼쪽 층 라벨이 행을 알려 주므로 칸에는 구역 이름만 쓴다.
        // "B1-A"를 칸마다 반복하면 구역이 6~8개일 때 폭이 모자라 잘려 버린다.
        for (String rowLabel : rows) {
            LinearLayout row = addRow(ctx, container);
            TextView label = new TextView(ctx);
            label.setText(rowLabel);
            label.setTextSize(compact ? 10 : 13);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setTextColor(ctx.getColor(R.color.subtext));
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(labelWidth, height));

            for (String col : cols) {
                row.addView(cell(ctx, rowLabel + sep + col, col, height, textSp,
                        R.color.text, tap));
            }
        }
    }

    /**
     * 기타 구역용 작은 버튼 줄. 격자 밖의 평면 목록을 4개씩 끊어 배치한다.
     *
     * <p>예전에는 MainActivity가 이 배치를 따로 구현했고, 거기서는 배경을 칠하지 않아
     * highlight()가 render 시점에 뒤늦게 칠해 주는 데 의존하고 있었다.
     */
    static void buildSecondary(Context ctx, LinearLayout container, String[] zones,
                               OnZoneTap tap) {
        container.removeAllViews();
        if (zones == null || zones.length == 0) return;
        final int perRow = 4;
        int height = dp(ctx, 42);
        LinearLayout row = null;
        for (int i = 0; i < zones.length; i++) {
            if (i % perRow == 0) row = addRow(ctx, container);
            row.addView(cell(ctx, zones[i], zones[i], height, 13, R.color.subtext, tap));
        }
        if (row != null) fillRow(ctx, row, perRow, height);
    }

    /**
     * 현재 주차한 구역만 강조한다. 태그가 구역 이름인 자식만 건드리므로
     * 층·구역 머리글이나 빈칸은 그대로 둔다.
     */
    static void highlight(Context ctx, ViewGroup container, String activeZone, int defaultColor) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ViewGroup) {
                highlight(ctx, (ViewGroup) child, activeZone, defaultColor);
                continue;
            }
            if (!(child instanceof Button) || !(child.getTag() instanceof String)) continue;
            boolean active = child.getTag().equals(activeZone);
            child.setBackgroundResource(
                    active ? R.drawable.bg_button_active : R.drawable.bg_button);
            ((Button) child).setTextColor(
                    active ? ctx.getColor(R.color.on_accent) : defaultColor);
        }
    }

    /** zone은 저장·강조에 쓰는 전체 이름, label은 칸에 실제로 찍히는 짧은 이름. */
    private static Button cell(Context ctx, String zone, String label, int height, int textSp,
                               int textColorRes, OnZoneTap tap) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTag(zone);
        b.setContentDescription(zone);
        b.setTextSize(textSp);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(ctx.getColor(textColorRes));
        b.setBackgroundResource(R.drawable.bg_button);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
        lp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
        b.setLayoutParams(lp);
        if (tap == null) b.setEnabled(false); // 온보딩 미리보기
        else b.setOnClickListener(v -> tap.onTap(zone, v));
        return b;
    }

    private static LinearLayout addRow(Context ctx, LinearLayout container) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 마지막 줄이 덜 찼을 때 버튼 폭이 갑자기 커지지 않게 빈칸을 채운다. */
    private static void fillRow(Context ctx, LinearLayout row, int perRow, int height) {
        while (row.getChildCount() < perRow) {
            View spacer = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            lp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
            row.addView(spacer, lp);
        }
    }

    /** 구역이 많아질수록 칸을 낮춰 한 화면에 격자가 다 들어오게 한다 (dp 단위). */
    private static int cellHeightDp(int cols, boolean grid, boolean compact) {
        if (compact) return cols <= 5 ? 26 : 20;
        if (!grid) return 58;
        if (cols <= 3) return 56;
        if (cols <= 5) return 48;
        return 40;
    }

    private static int cellTextSize(int cols, boolean grid, boolean compact) {
        if (compact) return 10;
        if (!grid) return 18;          // 목록형은 구역 이름 전체가 들어간다
        return cols <= 5 ? 17 : 14;    // 격자는 짧은 구역 라벨만 들어가 작아질 이유가 적다
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
