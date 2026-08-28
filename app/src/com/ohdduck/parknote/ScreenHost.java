package com.ohdduck.parknote;

/**
 * 다이얼로그가 화면을 갱신하거나, 결과를 돌려받아야 하는 시스템 화면(카메라·음성 인식)을
 * 열어 달라고 부탁하는 통로. MainActivity가 구현한다.
 */
interface ScreenHost {

    /** 데이터가 바뀌었다. zonesChanged가 참이면 구역 격자까지 다시 만든다. */
    void refresh(boolean zonesChanged);

    void openZoneSettings();

    /** 카메라를 열어 이 기록에 사진을 붙인다. 결과는 Activity가 받아 Photos.onCaptured로 넘긴다. */
    void capturePhoto(String recordId);

    /** 음성 인식을 연다. 결과 문장은 열려 있는 기록 편집기의 메모에 붙는다. */
    void captureVoice();
}
