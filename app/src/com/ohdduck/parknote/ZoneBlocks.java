package com.ohdduck.parknote;

import android.app.Activity;
import android.content.ClipData;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 이름 목록을 "블록"으로 그리고 손으로 편집하게 한다. 층 · 구역 · 기타 구역이 함께 쓴다.
 *
 * <p>예전에는 이 편집이 여러 줄 EditText 세 개였다. "한 줄에 하나씩 적으세요"라는
 * 규칙을 사람이 지켜야 했고, 순서를 바꾸려면 텍스트를 잘라내 옮겨 붙여야 했으며,
 * 무엇보다 편집 중인 것이 결국 화면의 어느 버튼이 되는지가 눈에 보이지 않았다.
 *
 * <p>블록은 실제 화면에 생길 버튼과 같은 모양이라, 편집 화면이 곧 미리보기다.
 * <ul>
 *   <li>탭 → 이름 바꾸기 / 삭제</li>
 *   <li>길게 눌러 끌기 → 순서 바꾸기 (놓은 자리로 이동)</li>
 *   <li>마지막 + 블록 → 추가</li>
 * </ul>
 *
 * <p>한 줄에 최대 {@link #PER_ROW}개를 둔다. 그보다 촘촘하면 글자가 잘리고,
 * 이 이름들은 대개 "B1", "A"처럼 짧아서 네 개면 화면 폭이 알맞게 찬다.
 */
class ZoneBlocks {

    /** 한 줄에 놓을 블록 수. */
    static final int PER_ROW = 4;

    private static final int BLOCK_HEIGHT_DP = 56;
    private static final int ACTION_MOVE_PREVIOUS =
            R.id.accessibility_action_zone_move_previous;
    private static final int ACTION_MOVE_NEXT = R.id.accessibility_action_zone_move_next;

    interface Listener {
        /** 블록을 탭했다. index 자리의 이름을 바꾸거나 지운다. */
        void onEdit(int index);

        /** + 블록을 탭했다. */
        void onAdd();

        /** from 자리의 블록을 to 자리로 옮겼다. */
        void onMove(int from, int to);
    }

    private ZoneBlocks() {
    }

    /**
     * container를 비우고 블록 격자를 새로 채운다.
     *
     * @param max     이 목록이 가질 수 있는 최대 개수. 다 찼으면 + 블록을 숨긴다.
     * @param addable + 블록을 아예 그릴지 여부
     */
    static void build(Activity host, LinearLayout container, List<String> items,
                      int max, boolean addable, Listener listener) {
        container.removeAllViews();

        int count = items.size();
        boolean showAdd = addable && count < max;
        int cells = count + (showAdd ? 1 : 0);
        if (cells == 0) return;

        LinearLayout row = null;
        for (int i = 0; i < cells; i++) {
            if (i % PER_ROW == 0) row = addRow(host, container);
            row.addView(i < count
                    ? block(host, container, items.get(i), i, count, listener)
                    : addBlock(host, listener));
        }
        // 마지막 줄이 덜 찼을 때 남은 블록이 갑자기 넓어지지 않게 빈칸을 채운다.
        int remainder = cells % PER_ROW;
        if (row != null && remainder != 0) {
            for (int i = remainder; i < PER_ROW; i++) row.addView(spacer(host));
        }
    }

    // ---------- 조각 ----------

    private static LinearLayout addRow(Activity host, LinearLayout container) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static View block(Activity host, LinearLayout container, String label,
                              int index, int count, Listener listener) {
        TextView view = baseBlock(host, label);
        view.setTextColor(host.getColor(R.color.text));
        view.setBackgroundResource(R.drawable.bg_button);
        view.setContentDescription(host.getString(R.string.zone_block_cd, label));

        view.setOnClickListener(v -> listener.onEdit(index));
        addNonTouchMoveActions(host, container, view, label, index, count, listener);

        // 끌어서 순서 바꾸기. 시작한 블록은 자기 자리를 반투명으로 비워 둬서
        // "지금 이걸 들고 있다"가 보이게 한다.
        view.setOnLongClickListener(v -> {
            ClipData clip = ClipData.newPlainText(DRAG_LABEL, String.valueOf(index));
            // 드래그가 시작되지 않으면(접근성 서비스, 창 상태 등) 반투명 표시를 남기지 않는다.
            // DRAG_ENDED가 오지 않으니 되돌릴 기회가 없다.
            if (!v.startDragAndDrop(clip, new View.DragShadowBuilder(v), index, 0)) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            v.setAlpha(0.3f);
            return true;
        });

        view.setOnDragListener((target, event) -> onDrag(host, target, event, index, listener));
        return view;
    }

    /** 드래그를 쓸 수 없는 키보드·스크린리더 사용자에게 같은 순서 변경 기능을 준다. */
    private static void addNonTouchMoveActions(Activity host, LinearLayout container,
                                                TextView view, String label, int index,
                                                int count, Listener listener) {
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                    || (!event.isAltPressed() && !event.isCtrlPressed())) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index > 0) {
                move(host, container, v, label, index, index - 1, listener);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index < count - 1) {
                move(host, container, v, label, index, index + 1, listener);
                return true;
            }
            return false;
        });

        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View hostView,
                                                          AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(hostView, info);
                if (index > 0) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_MOVE_PREVIOUS,
                            host.getString(R.string.zone_move_previous)));
                }
                if (index < count - 1) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_MOVE_NEXT, host.getString(R.string.zone_move_next)));
                }
            }

            @Override
            public boolean performAccessibilityAction(View hostView, int action,
                                                      Bundle arguments) {
                if (action == ACTION_MOVE_PREVIOUS && index > 0) {
                    move(host, container, hostView, label, index, index - 1, listener);
                    return true;
                }
                if (action == ACTION_MOVE_NEXT && index < count - 1) {
                    move(host, container, hostView, label, index, index + 1, listener);
                    return true;
                }
                return super.performAccessibilityAction(hostView, action, arguments);
            }
        });
    }

    private static void move(Activity host, LinearLayout container, View source,
                             String label, int from, int to, Listener listener) {
        boolean hadKeyboardFocus = source.hasFocus();
        boolean hadAccessibilityFocus = source.isAccessibilityFocused();
        listener.onMove(from, to);
        // onMove가 removeAllViews→재렌더를 동기 실행한다. 새 블록이 붙은 다음 프레임에
        // 논리적으로 같은 항목으로 포커스를 옮겨 연속 재정렬이 가능하게 한다.
        container.post(() -> {
            View moved = blockAt(container, to);
            if (moved == null) return;
            // 입력 포커스는 복원하되 스크린리더의 접근성 포커스를 강제로 빼앗지 않는다.
            // 접근성 사용자는 아래 announce로 이동 결과를 듣고 탐색을 이어갈 수 있다.
            if (hadKeyboardFocus || hadAccessibilityFocus) moved.requestFocus();
            moved.announceForAccessibility(host.getString(
                    R.string.zone_move_announce, label, to + 1));
        });
    }

    private static View blockAt(LinearLayout container, int index) {
        int rowIndex = index / PER_ROW;
        int columnIndex = index % PER_ROW;
        if (rowIndex < 0 || rowIndex >= container.getChildCount()) return null;
        View row = container.getChildAt(rowIndex);
        if (!(row instanceof LinearLayout)) return null;
        LinearLayout rowLayout = (LinearLayout) row;
        return columnIndex < rowLayout.getChildCount()
                ? rowLayout.getChildAt(columnIndex) : null;
    }

    private static final String DRAG_LABEL = "zone-block";

    /**
     * 드래그 이벤트 처리.
     *
     * <p>local state에 출발 index를 담아 보내므로 ClipData를 파싱할 필요가 없고,
     * 다른 앱에서 날아온 드래그와도 섞이지 않는다.
     */
    private static boolean onDrag(Activity host, View target, DragEvent event,
                                  int index, Listener listener) {
        Object local = event.getLocalState();
        if (!(local instanceof Integer)) return false;
        int from = (Integer) local;

        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (from != index) target.setBackgroundResource(R.drawable.bg_button_active);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                target.setBackgroundResource(R.drawable.bg_button);
                return true;
            case DragEvent.ACTION_DROP:
                target.setBackgroundResource(R.drawable.bg_button);
                if (from != index) {
                    target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    listener.onMove(from, index);
                }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                // 어디에도 못 놓고 끝났을 수 있다. 들고 있던 표시를 반드시 되돌린다.
                target.setAlpha(1f);
                target.setBackgroundResource(R.drawable.bg_button);
                return true;
            default:
                return false;
        }
    }

    private static View addBlock(Activity host, Listener listener) {
        TextView view = baseBlock(host, "+");
        view.setTextSize(22);
        view.setTextColor(host.getColor(R.color.accent_text));
        view.setBackgroundResource(R.drawable.bg_button);
        view.setContentDescription(host.getString(R.string.zone_block_add));
        view.setOnClickListener(v -> listener.onAdd());
        return view;
    }

    private static View spacer(Activity host) {
        View view = new View(host);
        view.setLayoutParams(cellParams(host));
        return view;
    }

    private static TextView baseBlock(Activity host, String label) {
        TextView view = new TextView(host);
        view.setText(label);
        view.setTextSize(16);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(Ui.dp(host, 4), 0, Ui.dp(host, 4), 0);
        view.setClickable(true);
        view.setFocusable(true);
        view.setLayoutParams(cellParams(host));
        return view;
    }

    private static LinearLayout.LayoutParams cellParams(Activity host) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, Ui.dp(host, BLOCK_HEIGHT_DP), 1f);
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.gutter);
        lp.setMargins(gutter, gutter, gutter, gutter);
        return lp;
    }
}
