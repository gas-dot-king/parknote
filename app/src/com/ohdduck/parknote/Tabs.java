package com.ohdduck.parknote;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

/**
 * 하단 탭 네 칸의 선택 상태와 화면 전환.
 *
 * <p>네 화면이 항상 인플레이트돼 있고 visibility만 오간다. Fragment를 쓰지 않는
 * 이유는 이 앱이 프레임워크만으로 만들어져 있어서다(AndroidX 의존성 0개).
 * 대신 "지금 보이는 탭"이 바뀔 때마다 {@link Listener}로 알려 주므로, 위치 탭처럼
 * 센서를 쓰는 화면은 자기가 보일 때만 켜고 벗어나면 끌 수 있다.
 */
class Tabs {

    static final int HOME = 0;
    static final int HISTORY = 1;
    static final int LOCATION = 2;
    static final int SETTINGS = 3;

    interface Listener {
        /** 탭이 바뀌었다. 화면을 처음 그리거나 센서를 켜고 끄는 자리. */
        void onTabChanged(int tab);
    }

    private final Listener listener;
    private final View[] screens = new View[4];
    private final TextView[] buttons = new TextView[4];
    private final ColorStateList active;
    private final ColorStateList inactive;

    private int current = -1;

    Tabs(Activity host, Listener listener) {
        this.listener = listener;
        this.active = ColorStateList.valueOf(host.getColor(R.color.nav_active));
        this.inactive = ColorStateList.valueOf(host.getColor(R.color.nav_inactive));

        screens[HOME] = host.findViewById(R.id.tabHome);
        screens[HISTORY] = host.findViewById(R.id.tabHistory);
        screens[LOCATION] = host.findViewById(R.id.tabLocation);
        screens[SETTINGS] = host.findViewById(R.id.tabSettings);

        buttons[HOME] = host.findViewById(R.id.navHome);
        buttons[HISTORY] = host.findViewById(R.id.navHistory);
        buttons[LOCATION] = host.findViewById(R.id.navLocation);
        buttons[SETTINGS] = host.findViewById(R.id.navSettings);

        for (int i = 0; i < buttons.length; i++) {
            final int tab = i;
            buttons[i].setOnClickListener(v -> select(tab));
        }
    }

    int current() {
        return current;
    }

    /** 같은 탭을 다시 고르면 아무 일도 하지 않는다 (목록이 맨 위로 튀지 않게). */
    void select(int tab) {
        if (tab == current) return;
        current = tab;

        for (int i = 0; i < screens.length; i++) {
            boolean on = i == tab;
            screens[i].setVisibility(on ? View.VISIBLE : View.GONE);
            // 알약 배경은 bg_nav_active가 state_selected로 처리한다. 배경과 색
            // 두 가지로 표시하는 이유는 색만 쓰면 색각 이상이 있는 사용자에게
            // 지금 어느 탭인지가 사라지기 때문이다.
            buttons[i].setSelected(on);
            buttons[i].setTextColor(on ? active : inactive);
            buttons[i].setCompoundDrawableTintList(on ? active : inactive);
        }
        listener.onTabChanged(tab);
    }
}
