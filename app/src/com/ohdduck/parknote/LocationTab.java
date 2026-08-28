package com.ohdduck.parknote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 위치 탭 — "내 차까지 얼마나 남았나".
 *
 * <p><b>지도 타일은 그리지 않는다.</b> 앱 자체는 좌표를 네트워크로 전송하지 않고,
 * 실제로 필요한 정보 — 거리, 방향, 좌표 — 를 전부 기기 안에서 만든다:
 * <ul>
 *   <li>거리: {@link Store#metersBetween} (이미 있던 하버사인)</li>
 *   <li>방향: 두 좌표의 방위각 − 나침반이 읽은 기기 방위</li>
 *   <li>좌표: 저장된 위경도를 네트워크 없이 사람이 읽을 문자열로 변환</li>
 * </ul>
 * 사용자가 "지도 앱으로 열기"를 명시적으로 누른 경우에만 {@code geo:} 인텐트로
 * 선택한 외부 지도 앱에 좌표를 전달한다.
 *
 * <p>나침반은 이 탭이 보일 때만 켠다. 항상 켜 두면 다른 탭을 보는 내내 센서가
 * 돌아 배터리를 먹는데, 이 앱은 "배터리를 거의 안 쓴다"를 설계 전제로 삼고 있다.
 */
class LocationTab {

    /** 걷는 속도(m/분). 도보 시간 어림에 쓴다. */
    private static final double WALK_M_PER_MIN = 75.0;

    /** 이보다 가까우면 거리 대신 "거의 다 왔어요"를 띄운다. GPS 오차가 이 정도다. */
    private static final int ARRIVED_M = 15;

    /** 저장 시점보다 이만큼 오래된 좌표는 방향·도착 판정에 쓰지 않는다. */
    private static final long MAX_STORED_FIX_AGE_MS = 30 * 60 * 1000L;

    /** 저장 시각보다 조금 미래인 fix는 서로 다른 시계 읽기 순서를 감안해 허용한다. */
    private static final long FIX_FUTURE_TOLERANCE_MS = 2 * 60 * 1000L;

    /** 이보다 오차가 큰 위치는 15m 도착 판정과 나침반을 확정적으로 보여주지 않는다. */
    private static final float MAX_RELIABLE_ACCURACY_M = 50f;

    private final Activity host;
    private final Runnable goHome;

    private final TextView distance;
    private final TextView bearingText;
    private final ImageView arrow;
    private final View card;
    private final View coordinateRow;
    private final TextView coordinates;
    private final TextView copy;
    private final Button openMap;
    private final Button refresh;
    private final TextView empty;
    private final Button emptyAction;

    private final SensorManager sensors;
    private final Sensor rotation;
    private boolean listening;
    private boolean visible;
    private long fixGeneration;
    private Nearby.FixRequest pendingFix;

    /** 차 좌표. 없으면 안내 문구 모드. */
    private double carLat;
    private double carLon;
    private boolean hasCar;
    private boolean carFixReliable;

    /** 내 좌표. 아직 못 잡았으면 hasMe가 false. */
    private double myLat;
    private double myLon;
    private boolean hasMe;
    private boolean myFixReliable;

    /** 차가 있는 방위각(내 위치 기준, 0=북). 나침반과 빼서 화살표를 돌린다. */
    private float carBearing;

    private final SensorEventListener compass = new SensorEventListener() {
        private final float[] matrix = new float[9];
        private final float[] remapped = new float[9];
        private final float[] orientation = new float[3];
        private float shown = Float.NaN;

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!canUseCompass()) {
                stopCompass();
                return;
            }
            if (event == null || event.values == null || event.values.length < 3) return;
            try {
                SensorManager.getRotationMatrixFromVector(matrix, event.values);
            } catch (IllegalArgumentException ignored) {
                return;
            }
            int displayRotation = host.getWindowManager().getDefaultDisplay().getRotation();
            if (!SensorManager.remapCoordinateSystem(
                    matrix, displayAxisX(displayRotation), displayAxisY(displayRotation),
                    remapped)) return;
            SensorManager.getOrientation(remapped, orientation);
            float azimuth = (float) Math.toDegrees(orientation[0]);
            float target = normalize(carBearing - azimuth);
            if (!isFinite(target)) return;
            // 센서는 초당 수십 번 올라온다. 1도 미만 변화까지 매번 뷰를 돌리면
            // 화살표가 떨리기만 하고 정보는 늘지 않는다.
            if (!Float.isNaN(shown) && Math.abs(delta(target, shown)) < 2f) return;
            shown = target;
            arrow.setRotation(target);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    LocationTab(Activity host, Runnable goHome) {
        this.host = host;
        this.goHome = goHome;
        this.card = host.findViewById(R.id.locCard);
        this.distance = host.findViewById(R.id.locDistance);
        this.bearingText = host.findViewById(R.id.locBearing);
        this.arrow = host.findViewById(R.id.locArrow);
        this.coordinateRow = host.findViewById(R.id.locAddressRow);
        this.coordinates = host.findViewById(R.id.locAddress);
        this.copy = host.findViewById(R.id.locCopy);
        this.openMap = host.findViewById(R.id.locOpenMap);
        this.refresh = host.findViewById(R.id.locRefresh);
        this.empty = host.findViewById(R.id.locEmpty);
        this.emptyAction = host.findViewById(R.id.locEmptyAction);

        this.sensors = (SensorManager) host.getSystemService(Context.SENSOR_SERVICE);
        this.rotation = sensors == null
                ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        coordinateRow.setOnClickListener(v -> copyCoordinates());
        copy.setOnClickListener(v -> copyCoordinates());
        openMap.setOnClickListener(v -> openInMapApp());
        refresh.setOnClickListener(v -> requestFreshFix());
    }

    // ---------- 생명주기 ----------

    /** 탭이 보이기 시작했다. 나침반은 render()가 조건을 보고 켠다. */
    void onShow() {
        visible = true;
        if (!hostIsAlive()) return;
        render();
    }

    /** 탭을 벗어났거나 앱이 백그라운드로 갔다. 센서와 단발 측위를 반드시 끈다. */
    void onHide() {
        visible = false;
        cancelPendingFix();
        stopCompass();
    }

    /** Activity가 끝날 때도 탭 상태와 무관하게 남은 시스템 콜백을 정리한다. */
    void onDestroy() {
        onHide();
    }

    /**
     * 차 좌표와 내 좌표를 둘 다 알 때만 켠다.
     *
     * <p>내 위치를 모르면 방위각을 계산할 수 없어 화살표가 0도(정북)에 붙어 있게
     * 되는데, 그건 "북쪽에 차가 있다"는 틀린 정보다. 화살표를 숨기는 것으로는
     * 부족하고 센서도 같이 꺼야 배터리를 안 쓴다.
     */
    private boolean startCompass() {
        if (listening) return true;
        if (sensors == null || rotation == null || !canUseCompass()) return false;
        // UI 지연은 초당 ~5회로 충분하다. 방향 화살표는 사람이 걸으면서 보는 것이라
        // 게임용 샘플링 속도가 필요 없다.
        listening = sensors.registerListener(
                compass, rotation, SensorManager.SENSOR_DELAY_UI);
        return listening;
    }

    private void stopCompass() {
        if (!listening || sensors == null) return;
        sensors.unregisterListener(compass);
        listening = false;
    }

    private boolean canUseCompass() {
        return visible && hostIsAlive() && hasCar && hasMe
                && carFixReliable && myFixReliable;
    }

    private boolean hostIsAlive() {
        return !host.isFinishing() && !host.isDestroyed();
    }

    // ---------- 그리기 ----------

    void render() {
        JSONObject latest = Store.latestRecord(host);
        hasCar = Store.recordHasCoords(latest);
        if (hasCar) {
            carLat = Store.recordLat(latest);
            carLon = Store.recordLon(latest);
            carFixReliable = storedFixIsReliable(
                    latest.optLong("t", 0L),
                    Store.recordLocationTime(latest),
                    Store.recordLocationAccuracy(latest));
        } else {
            carFixReliable = false;
        }

        if (!hasCar) {
            showEmpty(latest);
            stopCompass();
            return;
        }

        card.setVisibility(View.VISIBLE);
        coordinateRow.setVisibility(View.VISIBLE);
        openMap.setVisibility(View.VISIBLE);
        refresh.setVisibility(View.VISIBLE);
        refresh.setEnabled(pendingFix == null);
        empty.setVisibility(View.GONE);
        emptyAction.setVisibility(View.GONE);

        Location me = Nearby.lastFix(host);
        hasMe = Nearby.isValid(me);
        if (hasMe) {
            myLat = me.getLatitude();
            myLon = me.getLongitude();
            myFixReliable = hasReliableAccuracy(me);
        } else {
            myFixReliable = false;
        }
        // 나침반은 renderDistance가 켜고 끈다. 여기서 한 번 더 켜면 "거의 다 왔어요"
        // 상태에서 방금 끈 센서를 되살리게 된다.
        renderDistance();
        coordinates.setText(formatCoordinates(carLat, carLon));
    }

    private void renderDistance() {
        if (!hasMe) {
            // 차 좌표는 아는데 내 위치를 모르는 상태. 좌표 복사와 지도 열기는 여전히
            // 쓸모가 있으므로 거리만 대기 문구로 둔다.
            distance.setText(R.string.location_waiting);
            bearingText.setText("");
            arrow.setVisibility(View.GONE);
            stopCompass();
            return;
        }

        double meters = Store.metersBetween(myLat, myLon, carLat, carLon);
        if (!isFinite(meters) || meters < 0) {
            distance.setText(R.string.location_waiting);
            bearingText.setText("");
            arrow.setVisibility(View.GONE);
            stopCompass();
            return;
        }
        boolean reliable = carFixReliable && myFixReliable;
        if (reliable && meters < ARRIVED_M) {
            // 이 거리에서는 방위각이 GPS 오차에 휘둘려 화살표가 빙빙 돈다.
            distance.setText(R.string.location_here);
            bearingText.setText("");
            arrow.setVisibility(View.GONE);
            stopCompass();
            return;
        }

        distance.setText(meters < 1000
                ? host.getString(R.string.location_distance_m, (int) Math.round(meters))
                : host.getString(R.string.location_distance_km, meters / 1000.0));

        if (!reliable) {
            bearingText.setText(R.string.location_accuracy_warning);
            arrow.setVisibility(View.GONE);
            stopCompass();
            return;
        }

        carBearing = (float) bearingTo(myLat, myLon, carLat, carLon);
        if (!isFinite(carBearing)) {
            bearingText.setText(R.string.location_accuracy_warning);
            arrow.setVisibility(View.GONE);
            stopCompass();
            return;
        }
        String compassName = host.getString(directionName(carBearing));
        int walkMinutes = (int) Math.ceil(meters / WALK_M_PER_MIN);
        // 30분 넘게 걸어갈 거리면 도보 시간은 의미가 없다. 차를 타고 온 곳이거나
        // 아직 집에서 출발도 안 한 상태다.
        bearingText.setText(walkMinutes <= 30
                ? host.getString(R.string.location_bearing, compassName, walkMinutes)
                : host.getString(R.string.location_bearing_far, compassName));

        arrow.setVisibility(startCompass() ? View.VISIBLE : View.GONE);
    }

    /** 좌표가 없는 세 가지 이유를 구분해 안내한다. 셋 다 사용자가 할 일이 다르다. */
    private void showEmpty(JSONObject latest) {
        card.setVisibility(View.GONE);
        coordinateRow.setVisibility(View.GONE);
        openMap.setVisibility(View.GONE);
        refresh.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);

        if (latest == null) {
            empty.setText(R.string.location_empty_no_record);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(R.string.location_empty_action_record);
            emptyAction.setOnClickListener(v -> goHome.run());
            return;
        }
        if (!Nearby.hasForegroundPermission(host)) {
            empty.setText(R.string.location_empty_no_permission);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(R.string.location_empty_action_permission);
            emptyAction.setOnClickListener(v -> LocationFilterDialogs.showMenu(host));
            return;
        }
        // 권한은 있는데 이 기록에 좌표가 없다 = v3.2 이전에 저장했거나, 저장 순간
        // 캐시된 좌표가 30분보다 낡아서 붙지 못한 경우.
        empty.setText(R.string.location_empty_no_coords);
        emptyAction.setVisibility(View.GONE);
    }

    // ---------- 좌표 ----------

    /** 외부 서비스 없이 기기 안에서 만든, 지도 앱에도 붙여넣기 쉬운 위경도 문자열. */
    static String formatCoordinates(double latitude, double longitude) {
        if (!Nearby.validCoordinates(latitude, longitude)) return "";
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }

    private void copyCoordinates() {
        CharSequence text = coordinates.getText();
        if (text == null || text.length() == 0) return;
        ClipboardManager cm =
                (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(
                host.getString(R.string.location_tab_title), text));
        // Android 13+는 복사하면 시스템이 스스로 알려 준다. 두 번 띄우지 않는다.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(host, R.string.location_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 바깥으로 ----------

    /**
     * 기기에 설치된 지도 앱으로 넘긴다.
     *
     * <p>사용자가 이 버튼을 누른 경우에만 {@code geo:} 인텐트로 선택한 외부 지도 앱에
     * 좌표가 전달된다. 앱 자체에서 자동으로 네트워크 전송하는 경로는 아니다.
     */
    private void openInMapApp() {
        String label = Uri.encode(Store.latestZone(host) == null
                ? host.getString(R.string.location_to_car) : Store.latestZone(host));
        Uri geo = Uri.parse(String.format(Locale.US,
                "geo:%f,%f?q=%f,%f(%s)", carLat, carLon, carLat, carLon, label));
        try {
            host.startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (Exception e) {
            Toast.makeText(host, R.string.location_no_map_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** 지하 주차장에서 나오면 좌표가 갱신되므로, 눌러서 다시 잡을 길을 준다. */
    private void requestFreshFix() {
        cancelPendingFix();
        final long generation = fixGeneration;
        if (!visible || !hostIsAlive()) return;
        refresh.setEnabled(false);
        distance.setText(R.string.location_waiting);
        final boolean[] delivered = {false};
        Nearby.FixRequest request = Nearby.requestFreshFix(host, fix -> {
            delivered[0] = true;
            if (!canAcceptFix(generation)) return;
            pendingFix = null;
            refresh.setEnabled(true);
            hasMe = Nearby.isValid(fix);
            if (!hasMe) {
                myFixReliable = false;
                showFixFailed();
                return;
            }
            myLat = fix.getLatitude();
            myLon = fix.getLongitude();
            myFixReliable = hasReliableAccuracy(fix);
            renderDistance();
        });
        // 권한/공급자 오류는 위 콜백을 동기 호출한다. 그 경우 이미 완료된 handle을
        // pending으로 되살리지 않는다.
        if (!delivered[0] && canAcceptFix(generation)) pendingFix = request;
    }

    /**
     * 사용자가 직접 다시 잡기를 눌렀는데 15초 안에 못 잡은 경우.
     * "잡는 중…"을 그대로 두면 영원히 기다리는 것처럼 보인다.
     */
    private void showFixFailed() {
        distance.setText(R.string.location_fix_failed);
        bearingText.setText("");
        arrow.setVisibility(View.GONE);
        stopCompass();
    }

    private boolean canAcceptFix(long generation) {
        return visible && generation == fixGeneration && hostIsAlive();
    }

    private void cancelPendingFix() {
        fixGeneration++;
        Nearby.FixRequest request = pendingFix;
        pendingFix = null;
        if (request != null) request.cancel();
        refresh.setEnabled(true);
    }

    // ---------- 방위 계산 ----------

    /** 저장 시점에 이미 오래됐거나 오차가 큰 좌표인지 판별한다. */
    static boolean storedFixIsReliable(long recordTimeMs, long fixTimeMs, float accuracyM) {
        if (recordTimeMs <= 0L || fixTimeMs <= 0L) return false;
        if (!isFinite(accuracyM) || accuracyM < 0f
                || accuracyM > MAX_RELIABLE_ACCURACY_M) return false;
        long ageMs = recordTimeMs - fixTimeMs;
        return ageMs >= -FIX_FUTURE_TOLERANCE_MS && ageMs <= MAX_STORED_FIX_AGE_MS;
    }

    private static boolean hasReliableAccuracy(Location fix) {
        if (fix == null || !fix.hasAccuracy()) return false;
        float accuracy = fix.getAccuracy();
        return isFinite(accuracy) && accuracy >= 0f
                && accuracy <= MAX_RELIABLE_ACCURACY_M;
    }

    /** 화면 회전에 맞춰 센서 좌표축을 기기 화면의 위쪽으로 변환한다. */
    static int displayAxisX(int displayRotation) {
        switch (displayRotation) {
            case Surface.ROTATION_90:
                return SensorManager.AXIS_Y;
            case Surface.ROTATION_180:
                return SensorManager.AXIS_MINUS_X;
            case Surface.ROTATION_270:
                return SensorManager.AXIS_MINUS_Y;
            default:
                return SensorManager.AXIS_X;
        }
    }

    static int displayAxisY(int displayRotation) {
        switch (displayRotation) {
            case Surface.ROTATION_90:
                return SensorManager.AXIS_MINUS_X;
            case Surface.ROTATION_180:
                return SensorManager.AXIS_MINUS_Y;
            case Surface.ROTATION_270:
                return SensorManager.AXIS_X;
            default:
                return SensorManager.AXIS_Y;
        }
    }

    /** 내 위치에서 차를 볼 때의 방위각(도, 0=북, 시계 방향). */
    private static double bearingTo(double fromLat, double fromLon,
                                    double toLat, double toLon) {
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double dLon = Math.toRadians(toLon - fromLon);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** 방위각을 8방위 이름으로. 각 구간은 45도이고 북은 -22.5~22.5도다. */
    private static int directionName(double bearing) {
        int index = (int) Math.floor(((bearing + 22.5) % 360) / 45);
        switch (index) {
            case 1: return R.string.dir_ne;
            case 2: return R.string.dir_e;
            case 3: return R.string.dir_se;
            case 4: return R.string.dir_s;
            case 5: return R.string.dir_sw;
            case 6: return R.string.dir_w;
            case 7: return R.string.dir_nw;
            default: return R.string.dir_n;
        }
    }

    private static float normalize(float degrees) {
        return (degrees % 360 + 360) % 360;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** 두 각도의 최단 차이 (-180~180). 359도와 1도가 2도 차이임을 알게 한다. */
    private static float delta(float a, float b) {
        return (a - b + 540) % 360 - 180;
    }
}
