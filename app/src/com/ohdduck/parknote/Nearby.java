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
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 공급자 콜백 시각의 작은 오차는 허용하되 오래된 초기값은 fresh 결과로 받지 않는다. */
    private static final long FRESH_FIX_TOLERANCE_NS = 5_000_000_000L;

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
     * 위치 권한을 요청할 때 반드시 함께 넣어야 하는 조합.
     *
     * <p>Android 12부터 FINE을 단독으로 요청하면 시스템이 다이얼로그조차 띄우지 않고
     * 요청을 무시한다. 사용자에게는 "거부됨"으로만 돌아와서 원인을 알 수 없다.
     */
    static final String[] FOREGROUND_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION};

    /**
     * FINE이든 COARSE든 하나라도 허용됐는가.
     *
     * <p>판정 반경이 {@value Store#DEFAULT_RADIUS_M}m라 COARSE(대략 100~2000m 오차)로도
     * 충분히 쓸 만하다. Android 12의 권한 다이얼로그에서 사용자가 "대략적인 위치"를
     * 고르면 FINE은 거부로 떨어지는데, 그걸 실패로 처리하면 멀쩡히 쓸 수 있는 기능이
     * 이유 없이 막힌다.
     */
    static boolean hasAnyLocationPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** 정밀 위치까지 허용됐는가. 새 측위를 요청할 때 GPS를 쓸 수 있는지 판단에만 쓴다. */
    private static boolean hasFinePermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 백그라운드에서 위치를 읽으려면 Android 10부터 "항상 허용"이 필요하다.
     * 블루투스 끊김은 브로드캐스트 수신이라 앱이 백그라운드 상태로 취급된다.
     */
    static boolean hasPermission(Context c) {
        if (!hasAnyLocationPermission(c)) return false;
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** 앱 화면에서 좌표를 저장할 때는 "앱 사용 중" 권한만 있으면 된다. */
    static boolean hasForegroundPermission(Context c) {
        return hasAnyLocationPermission(c);
    }

    /**
     * 권한 요청 결과에 위치 권한이 하나라도 허용됐는지 본다.
     *
     * <p>{@code results[0]}만 보면 안 된다. FINE+COARSE를 함께 요청했을 때 사용자가
     * "대략적인 위치"를 고르면 배열 0번(FINE)은 거부, 1번(COARSE)만 허용으로 온다.
     */
    static boolean anyLocationGranted(String[] permissions, int[] results) {
        for (int i = 0; i < permissions.length && i < results.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) continue;
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])
                    || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permissions[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 각 공급자가 캐시해 둔 마지막 좌표 중 가장 최신인 것. 새 측위는 요청하지 않는다.
     * 너무 오래됐으면 null.
     */
    static Location lastFix(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        List<String> providers;
        try {
            providers = lm.getProviders(true);
        } catch (SecurityException ignored) {
            return null;
        }
        if (providers == null) return null;
        Location best = null;
        for (String provider : providers) {
            // 공급자별로 따로 감싼다. COARSE만 허용된 상태에서 GPS를 물으면
            // SecurityException이 나는데, 바깥에서 한 번에 잡으면 그 뒤의
            // NETWORK 좌표까지 통째로 버리게 된다.
            try {
                Location candidate = lm.getLastKnownLocation(provider);
                if (!isValid(candidate)) continue;
                if (best == null || candidate.getTime() > best.getTime()) best = candidate;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // 이 공급자만 건너뛴다
            }
        }
        if (best == null) return null;
        long age = System.currentTimeMillis() - best.getTime();
        return age >= 0 && age <= MAX_FIX_AGE_MS ? best : null;
    }

    interface FixCallback {
        /** 실패하면 fix가 null. */
        void onFix(Location fix);
    }

    /** 진행 중인 단발 위치 요청. 탭이나 화면이 사라지면 반드시 취소한다. */
    interface FixRequest {
        void cancel();
    }

    private static final FixRequest COMPLETED_REQUEST = () -> { };

    /**
     * 주차장 좌표를 등록할 때 쓴다. 캐시된 좌표가 쓸 만하면 그대로 주고,
     * 없으면 앱이 떠 있는 동안 한 번만 새로 잡는다. 15초 안에 못 잡으면 포기한다.
     */
    static FixRequest requestFix(Context c, FixCallback cb) {
        return requestFix(c, cb, true);
    }

    /**
     * 캐시를 반환하지 않고 공급자에 실제 단발 측위를 요청한다.
     * "현재 위치 다시 잡기"처럼 사용자가 명시적으로 갱신을 요구한 경로에서만 쓴다.
     */
    static FixRequest requestFreshFix(Context c, FixCallback cb) {
        return requestFix(c, cb, false);
    }

    private static FixRequest requestFix(Context c, FixCallback cb, boolean allowCached) {
        if (allowCached) {
            Location cached = lastFix(c);
            if (cached != null) {
                cb.onFix(cached);
                return COMPLETED_REQUEST;
            }
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasForegroundPermission(c)) {
            cb.onFix(null);
            return COMPLETED_REQUEST;
        }
        List<String> providers = providersFor(c, lm);
        if (providers.isEmpty()) {
            cb.onFix(null);
            return COMPLETED_REQUEST;
        }

        OneShotRequest request = new OneShotRequest(lm, providers, cb, !allowCached);
        request.start();
        return request;
    }

    /**
     * 단발 측위에 걸 공급자.
     *
     * <p>Android 12+는 융합 공급자 하나면 된다 — GPS와 네트워크를 시스템이 합쳐 준다.
     * 그 아래에서는 GPS와 NETWORK를 <b>함께</b> 건다. 예전처럼 GPS 하나만 걸면 실내에서
     * 네트워크가 잡아 줄 수 있는데도 15초 타임아웃으로 끝났다. 먼저 오는 유효한 좌표가
     * 이기고, 나머지는 removeUpdates 한 번으로 같이 풀린다.
     *
     * <p>COARSE만 허용됐으면 GPS를 요청할 수 없다. 그대로 요청하면 SecurityException이
     * 나면서 "위치를 잡지 못했어요"로 끝나 버리므로 목록에서 뺀다.
     */
    private static List<String> providersFor(Context c, LocationManager lm) {
        ArrayList<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && isEnabled(lm, LocationManager.FUSED_PROVIDER)) {
            out.add(LocationManager.FUSED_PROVIDER);
            return out;
        }
        if (hasFinePermission(c) && isEnabled(lm, LocationManager.GPS_PROVIDER)) {
            out.add(LocationManager.GPS_PROVIDER);
        }
        if (isEnabled(lm, LocationManager.NETWORK_PROVIDER)) {
            out.add(LocationManager.NETWORK_PROVIDER);
        }
        return out;
    }

    /**
     * LocationManager 콜백과 타임아웃을 한 객체가 소유하게 해 취소 경로를 빠뜨리지 않는다.
     * 완료/취소 경쟁은 AtomicBoolean으로 한 번만 이기게 한다.
     */
    private static final class OneShotRequest implements FixRequest, LocationListener {
        private final LocationManager manager;
        private final List<String> providers;
        private final FixCallback callback;
        private final boolean requireFresh;
        private final long requestedAtElapsedNanos;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Runnable timeout = () -> finish(null, true);

        private OneShotRequest(LocationManager manager, List<String> providers,
                               FixCallback callback, boolean requireFresh) {
            this.manager = manager;
            this.providers = providers;
            this.callback = callback;
            this.requireFresh = requireFresh;
            this.requestedAtElapsedNanos = SystemClock.elapsedRealtimeNanos();
        }

        private void start() {
            boolean listening = false;
            for (String provider : providers) {
                try {
                    manager.requestLocationUpdates(
                            provider, 0L, 0f, this, Looper.getMainLooper());
                    listening = true;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // 이 공급자만 건너뛴다. 하나라도 걸렸으면 계속 기다린다.
                }
            }
            if (!listening) {
                finish(null, true);
                return;
            }
            if (!finished.get()) main.postDelayed(timeout, 15000L);
        }

        @Override
        public void cancel() {
            finish(null, false);
        }

        @Override
        public void onLocationChanged(Location location) {
            // 일부 공급자는 잘못된 좌표를 먼저 내보내기도 한다. 그런 값은 타임아웃까지
            // 기다리되 UI나 저장 경로로 전달하지 않는다.
            if (!isValid(location)) return;
            if (requireFresh && location.getElapsedRealtimeNanos()
                    < requestedAtElapsedNanos - FRESH_FIX_TOLERANCE_NS) return;
            finish(location, true);
        }

        @Override public void onStatusChanged(String p, int status, Bundle extras) { }

        @Override public void onProviderEnabled(String p) { }

        @Override public void onProviderDisabled(String p) { }

        private void finish(Location fix, boolean deliver) {
            if (!finished.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout);
            stop(manager, this);
            if (deliver) callback.onFix(fix);
        }
    }

    /** Location 객체가 실제 지구 좌표를 담고 있는지 확인한다. */
    static boolean isValid(Location location) {
        return location != null
                && validCoordinates(location.getLatitude(), location.getLongitude());
    }

    /** NaN/무한대와 위경도 범위 밖의 값을 저장·거리 계산에 흘려보내지 않는다. */
    static boolean validCoordinates(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }

    /** 공급자 조회는 기기에 따라 IllegalArgumentException을 던진다. */
    private static boolean isEnabled(LocationManager lm, String provider) {
        try {
            return lm.isProviderEnabled(provider);
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void stop(LocationManager lm, LocationListener listener) {
        try {
            lm.removeUpdates(listener);
        } catch (SecurityException ignored) {
            // 권한이 도중에 회수된 경우
        }
    }

    /** 좌표를 저장할 때 쓸 설명 문구. */
    static String describeAccuracy(Context c, Location fix) {
        if (fix == null) return "";
        int meters = Math.round(fix.getAccuracy());
        long minutes = (System.currentTimeMillis() - fix.getTime()) / 60000L;
        String freshness = minutes < 1
                ? c.getString(R.string.location_just_now)
                : c.getString(R.string.location_minutes_ago, minutes);
        return c.getString(R.string.location_accuracy, freshness, meters);
    }
}
