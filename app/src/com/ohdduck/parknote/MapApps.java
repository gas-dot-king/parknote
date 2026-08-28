package com.ohdduck.parknote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

/**
 * 폰에 깔린 지도 앱으로 "내 차까지 도보 길찾기"를 넘긴다.
 *
 * <p>지도를 앱 안에 그리지 않는 이유는 위치 탭 주석에 있다(SDK·INTERNET·좌표 전송).
 * 길찾기는 이미 깔린 네이버 지도·카카오맵이 우리보다 훨씬 잘하고, URL 스킴은 API 키도
 * SDK도 필요 없다. 좌표는 사용자가 버튼을 누른 그 순간에만 그 앱으로 넘어간다.
 */
final class MapApps {

    static final String NAVER = "com.nhn.android.nmap";
    static final String KAKAO = "net.daum.android.map";

    private MapApps() {
    }

    /** 설치 여부. 매니페스트의 &lt;queries&gt;에 두 패키지를 적어 둬야 Android 11+에서 보인다. */
    static boolean installed(Context c, String packageName) {
        return c.getPackageManager().getLaunchIntentForPackage(packageName) != null;
    }

    /** 네이버 지도: 현재 위치에서 목적지까지 도보. 출발지를 비우면 현재 위치다. */
    static Intent naverWalk(Context c, double lat, double lon, String name) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("nmap://route/walk"
                + "?dlat=" + coord(lat) + "&dlng=" + coord(lon)
                + "&dname=" + Uri.encode(name)
                + "&appname=" + c.getPackageName()))
                .setPackage(NAVER);
    }

    /** 카카오맵: 현재 위치에서 목적지까지 도보. */
    static Intent kakaoWalk(double lat, double lon) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("kakaomap://route"
                + "?ep=" + coord(lat) + "," + coord(lon) + "&by=FOOT"))
                .setPackage(KAKAO);
    }

    /** 둘 다 없을 때의 폴백. 설치된 아무 지도 앱이 좌표에 핀을 찍는다. */
    static Intent geo(double lat, double lon, String label) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                "geo:%f,%f?q=%f,%f(%s)", lat, lon, lat, lon, Uri.encode(label))));
    }

    static boolean open(Activity a, Intent intent) {
        try {
            a.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private static String coord(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
