package com.ohdduck.parknote;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 다이얼로그와 폼을 만드는 공용 조각.
 *
 * <p>예전에는 이 조각들이 MainActivity와 OnboardingActivity에 흩어져 있었고, 그래서
 * 입력 필드가 세 가지 모양(온보딩 박스, 구역 편집 박스, 플랫폼 기본 밑줄)으로 갈렸다.
 * 여기 모아 두면 폼 스타일이 한 곳에서 정해지고, 색을 명시적으로 칠하므로 다이얼로그
 * 테마가 어느 쪽으로 해석되든 글자가 사라지지 않는다.
 */
class Ui {

    private Ui() {
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---------- 글자 ----------

    static TextView text(Context c, String value, int sizeSp, int colorRes, boolean medium) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(c.getColor(colorRes));
        if (medium) {
            view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }
        return view;
    }

    /** 폼 항목 위에 붙는 이름표. */
    static TextView label(Context c, String value) {
        return text(c, value, 14, R.color.text, false);
    }

    /** 폼 위쪽의 설명 문구. */
    static TextView hint(Context c, String value) {
        return text(c, value, 13, R.color.subtext, false);
    }

    // ---------- 입력 ----------

    /**
     * 앱 전체가 쓰는 한 줄 입력. 배경과 글자색을 직접 칠하므로 다이얼로그 테마가
     * 라이트로 해석돼도 흰 글씨가 흰 배경에 묻히지 않는다.
     */
    static EditText input(Context c, String hint) {
        EditText input = new EditText(c);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(c.getColor(R.color.text));
        input.setHintTextColor(c.getColor(R.color.subtext));
        input.setBackgroundResource(R.drawable.bg_button);
        input.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    /** 메모·구역 목록처럼 여러 줄을 받는 입력. */
    static EditText multilineInput(Context c, String hint, int minLines, int maxLines) {
        EditText input = input(c, hint);
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP);
        input.setMinLines(minLines);
        input.setMaxLines(maxLines);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    /** 눌러서 값을 고르는 폼 항목 (주차장, 차량, 시각, 타이머, 블루투스). */
    static Button pickerButton(Context c, String text) {
        Button button = new Button(c);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(c.getColor(R.color.text));
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        button.setBackgroundResource(R.drawable.bg_button);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 44)));
        return button;
    }

    // ---------- 폼 ----------

    /** 다이얼로그 안에 들어가는 세로 폼 컨테이너. */
    static LinearLayout form(Context c) {
        LinearLayout form = new LinearLayout(c);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 8));
        return form;
    }

    /** 폼을 스크롤 가능하게 감싼다. 키보드가 올라와도 항목이 잘리지 않는다. */
    static ScrollView scroll(Context c, View content) {
        ScrollView scroll = new ScrollView(c);
        scroll.addView(content);
        return scroll;
    }

    static void add(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), topMarginDp);
        parent.addView(child, lp);
    }

    /** 이름표 + 값 한 쌍을 붙인다. */
    static void addField(LinearLayout form, String labelText, View field, int topMarginDp) {
        add(form, label(form.getContext(), labelText), topMarginDp);
        add(form, field, 4);
    }

    // ---------- 다이얼로그 ----------

    /** 저장 버튼이 눌렸을 때. 저장에 성공했으면 true, 실패해 다이얼로그를 열어 두려면 false. */
    interface SaveAction {
        boolean onSave();
    }

    /** 중립 버튼처럼 다이얼로그 자신을 닫아야 하는 동작. */
    interface DialogAction {
        void run(AlertDialog dialog);
    }

    /**
     * 저장에 실패하면 닫히지 않는 다이얼로그.
     *
     * <p>기본 AlertDialog는 버튼을 누르면 검증 결과와 무관하게 닫힌다. 그래서
     * {@code setOnShowListener}로 버튼 리스너를 바꿔 끼우는 패턴이 앱 곳곳에 복붙돼
     * 있었다. 그 패턴을 여기 한 곳에 둔다.
     *
     * @param onNeutral 중립 버튼 동작. null이면 기본 동작(닫기)을 그대로 둔다.
     */
    static AlertDialog validating(AlertDialog.Builder builder, SaveAction onSave,
                                  DialogAction onNeutral) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (onSave.onSave()) dialog.dismiss();
            });
            if (onNeutral != null) {
                Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (neutral != null) neutral.setOnClickListener(v -> onNeutral.run(dialog));
            }
        });
        return dialog;
    }

    static AlertDialog validating(AlertDialog.Builder builder, SaveAction onSave) {
        return validating(builder, onSave, null);
    }

    /** 다이얼로그를 띄우면서 키보드를 함께 올린다. */
    static void showWithKeyboard(AlertDialog dialog, EditText focus) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        if (focus != null) focus.requestFocus();
    }

    /** 다이얼로그를 띄우면서 키보드가 폼을 가리지 않게 화면을 줄인다. */
    static void showResizing(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /**
     * 한 줄 입력만 받는 다이얼로그. 저장을 누르면 {@code onSave}가 입력값을 받는다.
     * 빈 값이면 아무 일도 하지 않고 닫힌다.
     */
    static void inputDialog(Context c, String title, String hint, String initial,
                            ValueAction onSave) {
        EditText input = input(c, hint);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(input.getText().length());
        }
        LinearLayout wrap = form(c);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("저장", (d, w) -> onSave.onValue(input.getText().toString().trim()))
                .setNegativeButton("취소", null)
                .create();
        showWithKeyboard(dialog, input);
    }

    interface ValueAction {
        void onValue(String value);
    }
}
