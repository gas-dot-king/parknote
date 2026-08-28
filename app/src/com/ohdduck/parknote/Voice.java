package com.ohdduck.parknote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.util.ArrayList;

/**
 * 음성 → 텍스트. 차에서 막 내린 사람에게는 "B3 기둥 47번 근처"를 말하는 쪽이 타이핑보다 빠르다.
 *
 * <p>녹음 파일을 남기지 않는다. 텍스트는 기존 메모 칸(m)에 들어가 백업·검색·한눈에 읽기가
 * 그대로 되고, RECORD_AUDIO 권한도 필요 없다 — 인식은 시스템의 음성 서비스가 한다.
 * 서비스가 없는 기기에서는 버튼을 숨긴다.
 */
final class Voice {

    private Voice() {
    }

    static boolean available(Context c) {
        return intent().resolveActivity(c.getPackageManager()) != null;
    }

    static boolean start(Activity a, int requestCode) {
        try {
            a.startActivityForResult(intent()
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, a.getString(R.string.voice_prompt)),
                    requestCode);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static Intent intent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    /** 인식 결과 첫 문장. 없으면 빈 문자열. */
    static String result(Intent data) {
        if (data == null) return "";
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        return results == null || results.isEmpty() ? "" : Json.clean(results.get(0));
    }
}
