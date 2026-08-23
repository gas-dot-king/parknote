package com.ohdduck.parknote;

/**
 * 다이얼로그가 데이터를 바꾼 뒤 화면에 알리는 통로.
 *
 * <p>다이얼로그들을 MainActivity에서 떼어내면서 생긴 유일한 결합점이다. 이 인터페이스
 * 하나만 주고받으면 각 다이얼로그는 자기가 어느 화면 위에 떠 있는지 몰라도 된다.
 */
interface ScreenHost {

    /**
     * 저장이 끝났으니 화면을 다시 그린다.
     *
     * @param zonesChanged 구역 구성(층·구역·기타·주차장 전환)이 바뀌었으면 true.
     *                     격자 버튼을 통째로 다시 만들어야 한다.
     */
    void refresh(boolean zonesChanged);

    /**
     * 구역 편집 화면을 연다.
     *
     * <p>다이얼로그가 아니라 액티비티라 {@code startActivityForResult}를 부를 화면이
     * 필요하다. 돌아오면 화면이 스스로 갱신한다.
     */
    void openZoneSettings();
}
