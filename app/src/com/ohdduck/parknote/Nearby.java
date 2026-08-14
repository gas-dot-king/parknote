package com.ohdduck.parknote;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;

/**
 * "차에서 내린 이 자리가 어느 주차장인가"만 판정한다. 위치와 관련된 코드는 전부 여기 있다.
 *
 * <p>설계 두 가지가 핵심이다.
 * <ul>
 *   <li><b>새 측위를 요청하지 않는다.</b> 마지막으로 잡힌 좌표만 읽는다. 집 주차장은 지하라
 *       그 자리에서는 어차피 위성이 안 잡히고, 지하로 내려가기 직전 지상에서 잡힌 좌표면
 *       충분하다. 덕분에 배터리 소모가 사실상 없다.</li>
 *   <li><b>모르면 알린다.</b> 좌표가 없거나 낡았으면 판정을 포기하고 평소대로 알림을 띄운다.
 *       엉뚱한 곳에서 알림이 한 번 더 뜨는 건 손해가 없지만, 집에서 알림이 안 뜨면
 *       그날 주차 위치를 통째로 놓친다.</li>
 * </ul>
 */
class Nearby {

    /** 이보다 오래된 좌표는 없는 것으로 친다. 어제 회사 좌표로 오늘 집을 판정하면 안 된다. */
    private static final long MAX_FIX_AGE_MS = 30 * 60 * 1000L;

    /** 판정 결과. profile이 null이면 "아는 주차장이 아니다", unknown이면 "알 수 없다". */
    static class Where {
        final JSONObject profile;
        final boolean unknown;

        private Where(JSONObject profile, boolean unknown) {
            this.profile = profile;
            this.unknown = unknown;
        }

        /** 알림을 평소대로(소리·헤드업) 띄워야 하는가. */
        boolean shouldAlert() {
            return unknown || profile != null;
        }
    }

    private static final Where UNKNOWN = new Where(null, true);
    private static final Where ELSEWHERE = new Where(null, false);

    /**
     * 지금 위치가 등록된 주차장 중 어디인지 본다.
     * 기능이 꺼져 있거나 권한·좌표가 없으면 항상 {@code unknown}을 돌려준다.
     */
    static Where locate(Context c) {
        if (!Store.locationFilterOn(c)) return UNKNOWN;
        if (!hasPermission(c)) return UNKNOWN;
        if (!Store.anyProfileHasCoords(c)) return UNKNOWN;

        Location fix = lastFix(c);
        if (fix == null) return UNKNOWN;

        JSONObject profile = Store.profileNear(c, fix.getLatitude(), fix.getLongitude());
        return profile == null ? ELSEWHERE : new Where(profile, false);
    }

    /**
     * 백그라운드에서 위치를 읽으려면 Android 10부터 "항상 허용"이 필요하다.
     * 블루투스 끊김은 브로드캐스트 수신이라 앱이 백그라운드 상태로 취급된다.
     */
    static boolean hasPermission(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** 앱 화면에서 좌표를 저장할 때는 "앱 사용 중" 권한만 있으면 된다. */
    static boolean hasForegroundPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 각 공급자가 캐시해 둔 마지막 좌표 중 가장 최신인 것. 새 측위는 요청하지 않는다.
     * 너무 오래됐으면 null.
     */
    static Location lastFix(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            List<String> providers = lm.getProviders(true);
            if (providers == null) return null;
            for (String provider : providers) {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate == null) continue;
                if (best == null || candidate.getTime() > best.getTime()) best = candidate;
            }
        } catch (SecurityException ignored) {
            return null;
        }
        if (best == null) return null;
        long age = System.currentTimeMillis() - best.getTime();
        return age >= 0 && age <= MAX_FIX_AGE_MS ? best : null;
    }

    interface FixCallback {
        /** 실패하면 fix가 null. */
        void onFix(Location fix);
    }

    /**
     * 주차장 좌표를 등록할 때 쓴다. 캐시된 좌표가 쓸 만하면 그대로 주고,
     * 없으면 앱이 떠 있는 동안 한 번만 새로 잡는다. 15초 안에 못 잡으면 포기한다.
     */
    static void requestFix(Context c, FixCallback cb) {
        Location cached = lastFix(c);
        if (cached != null) {
            cb.onFix(cached);
            return;
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasForegroundPermission(c)) {
            cb.onFix(null);
            return;
        }
        String provider = null;
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        }
        if (provider == null) {
            cb.onFix(null);
            return;
        }

        final boolean[] done = {false};
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (done[0]) return;
                done[0] = true;
                stop(lm, this);
                cb.onFix(location);
            }

            @Override public void onStatusChanged(String p, int status, Bundle extras) { }

            @Override public void onProviderEnabled(String p) { }

            @Override public void onProviderDisabled(String p) { }
        };
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
        } catch (SecurityException e) {
            cb.onFix(null);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            stop(lm, listener);
            cb.onFix(null);
        }, 15000L);
    }

    private static void stop(LocationManager lm, LocationListener listener) {
        try {
            lm.removeUpdates(listener);
        } catch (SecurityException ignored) {
            // 권한이 도중에 회수된 경우
        }
    }

    /** 좌표를 저장할 때 쓸 설명 문구. */
    static String describeAccuracy(Location fix) {
        if (fix == null) return "";
        int meters = Math.round(fix.getAccuracy());
        long minutes = (System.currentTimeMillis() - fix.getTime()) / 60000L;
        String freshness = minutes < 1 ? "방금" : minutes + "분 전";
        return freshness + " 측정 · 오차 약 " + meters + "m";
    }
}
