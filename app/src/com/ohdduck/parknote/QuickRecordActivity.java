package com.ohdduck.parknote;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 잠금 화면 위 빠른 입력. 블루투스 끊김 알림을 탭하면 열린다.
 *
 * <p>차에서 막 내린 사람은 한 손에 짐을 들고 있다. 잠금을 풀고 앱을 찾아 홈까지 가는 대신,
 * 알림 탭 → 구역 탭 두 번으로 끝낸다. 격자가 커서(20칸) 알림 버튼 두 개에 안 들어가는
 * 주차장에서 특히 그렇다. 사진·음성 메모도 여기서 바로 붙인다.
 *
 * <p>대상은 초안 기록 하나다. 구역을 고르면 그 초안을 채우고 닫힌다. 초안이 그새
 * 사라졌으면(오탐으로 지워짐, 앱에서 삭제) 알리고 닫는다.
 */
public class QuickRecordActivity extends Activity {

    static final String EXTRA_RECORD_ID = "record_id";

    private static final int REQ_PHOTO = 40;
    private static final int REQ_VOICE = 41;

    private String recordId;
    private String profileId;
    private TextView subtitle;
    private TextView time;
    private TextView memo;
    private LinearLayout grid;
    private LinearLayout etcGrid;
    private Button photo;
    private Button voice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recordId = getIntent().getStringExtra(EXTRA_RECORD_ID);
        // 잠금 화면 위에 그대로 뜬다. 여기서 할 수 있는 일은 이 기록의 구역·사진·메모뿐이라
        // 잠금을 우회해 볼 수 있는 정보가 없다.
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        setContentView(R.layout.activity_quick_record);
        subtitle = findViewById(R.id.quickSubtitle);
        time = findViewById(R.id.quickTime);
        memo = findViewById(R.id.quickMemo);
        grid = findViewById(R.id.quickGrid);
        etcGrid = findViewById(R.id.quickEtc);
        photo = findViewById(R.id.quickPhoto);
        voice = findViewById(R.id.quickVoice);

        findViewById(R.id.quickCustom).setOnClickListener(v -> Ui.inputDialog(this,
                getString(R.string.custom_input_title), getString(R.string.custom_input_hint),
                null, zone -> {
                    if (!zone.isEmpty()) pick(zone, null);
                }));
        photo.setOnClickListener(v -> {
            if (!Photos.startCapture(this, recordId, REQ_PHOTO)) {
                Toast.makeText(this, R.string.photo_no_camera, Toast.LENGTH_SHORT).show();
            }
        });
        voice.setVisibility(Voice.available(this) ? View.VISIBLE : View.GONE);
        voice.setOnClickListener(v -> {
            if (!Voice.start(this, REQ_VOICE)) {
                Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.quickLater).setOnClickListener(v -> finish());
        applyWindowInsets();
        render();
    }

    private void applyWindowInsets() {
        if (Build.VERSION.SDK_INT < 35) return;
        View root = findViewById(R.id.quickRoot);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // 카메라·음성 인식에 다녀오면 사진·메모 상태가 바뀌어 있다
    }

    private void render() {
        JSONObject record = Store.recordById(this, recordId);
        if (record == null) {
            Toast.makeText(this, R.string.quick_record_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        JSONObject profile = Store.profileById(this, record.optString("p"));
        if (profile == null) profile = Store.activeProfile(this);
        profileId = profile == null ? Store.activeProfileId(this) : profile.optString("id");

        subtitle.setText(getString(R.string.quick_subtitle,
                Store.recordVehicleName(this, record), Store.recordProfileName(this, record)));
        long t = record.optLong("t", 0);
        time.setText(getString(R.string.quick_time, Fmt.full(t), Fmt.relative(t)));

        ZoneGrid.build(this, grid, Store.rows(profile), Store.cols(profile), Store.sep(profile),
                false, this::pick);
        String[] etc = Store.etc(profile);
        etcGrid.setVisibility(etc.length == 0 ? View.GONE : View.VISIBLE);
        ZoneGrid.buildSecondary(this, etcGrid, etc, this::pick);

        photo.setText(Photos.exists(this, Store.photoOf(record))
                ? R.string.photo_retake : R.string.quick_photo);
        String note = Store.recordMemo(record);
        memo.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        memo.setText(getString(R.string.quick_memo, note));
    }

    private void pick(String zone, View tapped) {
        if (!Store.setZone(this, recordId, profileId, zone)) {
            Toast.makeText(this, R.string.quick_record_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (tapped != null) tapped.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        Toast.makeText(this, getString(R.string.record_saved, zone), Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_PHOTO) {
            Toast.makeText(this, Photos.onCaptured(this, recordId)
                    ? R.string.photo_saved : R.string.photo_failed, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_VOICE) {
            String text = Voice.result(data);
            if (text.isEmpty()) return;
            Store.appendMemo(this, recordId, text);
            Toast.makeText(this, getString(R.string.quick_memo_saved, text),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
