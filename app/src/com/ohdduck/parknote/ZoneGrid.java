package com.ohdduck.parknote;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
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
     * 탭할 수 있는 칸의 최소 높이 (dp). res/values/dimens.xml의 touch_min과 같은 값이고,
     * cellHeightDp를 단위 테스트에서 검증하려고 여기 상수로도 둔다.
     */
    static final int MIN_TOUCH_DP = 48;

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
                row.addView(cell(ctx, cols[i], cols[i], height, textSp,
                        R.color.text, tap, 0));
            }
            if (row != null) fillRow(ctx, row, perRow, height);
            return;
        }

        // 6~8열의 실제 기록 격자는 화면에 억지로 압축하지 않는다. 모든 행을 같은
        // 가로 스크롤 하나에 넣어 열 정렬을 유지하고, 각 칸은 최소 48dp를 보장한다.
        // compact 미리보기는 비활성이라 기존 압축 배치를 그대로 쓴다.
        boolean scrollable = !compact && tap != null && cols.length >= 6;
        LinearLayout rowsContainer = container;
        if (scrollable) {
            HorizontalScrollView scroll = new HorizontalScrollView(ctx);
            scroll.setFillViewport(false);
            scroll.setHorizontalScrollBarEnabled(true);
            rowsContainer = new LinearLayout(ctx);
            rowsContainer.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rowsContainer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            container.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        // 가로 스크롤 격자의 칸 폭. 높이와 같은 48dp 하한 — 열 수가 늘었다고 폭을 줄이면
        // 높이만 48dp인 가늘고 잘못 누르기 쉬운 칸이 된다.
        int fixedCellWidth = scrollable ? dp(ctx, MIN_TOUCH_DP) : 0;

        // 격자에서는 왼쪽 층 라벨이 행을 알려 주므로 칸에는 구역 이름만 쓴다.
        // "B1-A"를 칸마다 반복하면 구역이 6~8개일 때 폭이 모자라 잘려 버린다.
        for (String rowLabel : rows) {
            LinearLayout row = addRow(ctx, rowsContainer, scrollable);
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
                        R.color.text, tap, fixedCellWidth));
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
        int height = dp(ctx, MIN_TOUCH_DP);
        LinearLayout row = null;
        for (int i = 0; i < zones.length; i++) {
            if (i % perRow == 0) row = addRow(ctx, container);
            row.addView(cell(ctx, zones[i], zones[i], height, 13,
                    R.color.subtext, tap, 0));
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
                               int textColorRes, OnZoneTap tap, int fixedWidth) {
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
        LinearLayout.LayoutParams lp = fixedWidth > 0
                ? new LinearLayout.LayoutParams(fixedWidth, height)
                : new LinearLayout.LayoutParams(0, height, 1f);
        int gutter = gutter(ctx);
        lp.setMargins(gutter, gutter, gutter, gutter);
        b.setLayoutParams(lp);
        if (tap == null) b.setEnabled(false); // 온보딩 미리보기
        else b.setOnClickListener(v -> tap.onTap(zone, v));
        return b;
    }

    private static LinearLayout addRow(Context ctx, LinearLayout container) {
        return addRow(ctx, container, false);
    }

    private static LinearLayout addRow(Context ctx, LinearLayout container, boolean wrapWidth) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(row, new LinearLayout.LayoutParams(
                wrapWidth ? LinearLayout.LayoutParams.WRAP_CONTENT
                        : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 마지막 줄이 덜 찼을 때 버튼 폭이 갑자기 커지지 않게 빈칸을 채운다. */
    private static void fillRow(Context ctx, LinearLayout row, int perRow, int height) {
        int gutter = gutter(ctx);
        while (row.getChildCount() < perRow) {
            View spacer = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            lp.setMargins(gutter, gutter, gutter, gutter);
            row.addView(spacer, lp);
        }
    }

    /** 섹션 머리글·목록 행과 같은 바깥 여백. 값은 dimens.xml이 정한다. */
    private static int gutter(Context ctx) {
        return ctx.getResources().getDimensionPixelSize(R.dimen.gutter);
    }

    /**
     * 구역이 많아질수록 칸을 낮춰 한 화면에 격자가 다 들어오게 한다 (dp 단위).
     *
     * <p>단, 탭할 수 있는 칸은 구역이 몇 개든 {@link #MIN_TOUCH_DP} 아래로 내려가지
     * 않는다. 마진은 히트 영역이 아니라서(LayoutParams의 height가 그대로 터치 타깃이다)
     * 여백으로 벌충되지 않는다. 격자 칸은 이 앱에서 제일 많이 눌리는 곳이고, 그것도
     * 운전 직후 한 손으로 눌린다. 화면에 다 안 들어오면 스크롤하면 되지만, 잘못 눌러
     * 엉뚱한 구역이 기록되면 앱이 존재할 이유가 없어진다.
     *
     * <p>compact는 온보딩·구역 편집의 미리보기다. 거기서는 tap이 null이라 버튼이
     * 아예 비활성이므로 터치 타깃 하한을 적용하지 않는다.
     */
    static int cellHeightDp(int cols, boolean grid, boolean compact) {
        if (compact) return cols <= 5 ? 26 : 20;
        if (!grid) return 58;
        if (cols <= 3) return 56;
        return cols <= 5 ? 52 : MIN_TOUCH_DP;
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
