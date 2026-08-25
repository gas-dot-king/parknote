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
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * 위치 탭 — "내 차까지 얼마나 남았나".
 *
 * <p><b>지도 타일은 그리지 않는다.</b> 타일을 띄우려면 지도 SDK와 INTERNET 권한이
 * 필요하고, 그러면 "좌표는 기기 밖으로 나가지 않는다"는 이 앱의 약속(개인정보
 * 처리방침에 명시)이 깨진다. 실제로 필요한 정보 — 거리, 방향, 주소 — 는 전부
 * 기기 안에서 만들 수 있다:
 * <ul>
 *   <li>거리: {@link Store#metersBetween} (이미 있던 하버사인)</li>
 *   <li>방향: 두 좌표의 방위각 − 나침반이 읽은 기기 방위</li>
 *   <li>주소: {@link Geocoder} — 시스템 서비스라 앱에 INTERNET 권한이 필요 없다</li>
 * </ul>
 * 진짜 지도가 필요한 순간에는 {@code geo:} 인텐트로 사용자가 이미 쓰는 지도 앱에
 * 넘긴다. 우리가 좌표를 어디로 보내는 게 아니라, 사용자가 자기 앱을 여는 것이다.
 *
 * <p>나침반은 이 탭이 보일 때만 켠다. 항상 켜 두면 다른 탭을 보는 내내 센서가
 * 돌아 배터리를 먹는데, 이 앱은 "배터리를 거의 안 쓴다"를 설계 전제로 삼고 있다.
 */
class LocationTab {

    /** 걷는 속도(m/분). 도보 시간 어림에 쓴다. */
    private static final double WALK_M_PER_MIN = 75.0;

    /** 이보다 가까우면 거리 대신 "거의 다 왔어요"를 띄운다. GPS 오차가 이 정도다. */
    private static final int ARRIVED_M = 15;

    private final Activity host;
    private final Runnable goHome;

    private final TextView distance;
    private final TextView bearingText;
    private final ImageView arrow;
    private final View card;
    private final View addressRow;
    private final TextView address;
    private final TextView copy;
    private final Button openMap;
    private final Button refresh;
    private final TextView empty;
    private final Button emptyAction;

    private final SensorManager sensors;
    private final Sensor rotation;
    private boolean listening;

    /** 차 좌표. 없으면 안내 문구 모드. */
    private double carLat;
    private double carLon;
    private boolean hasCar;

    /** 내 좌표. 아직 못 잡았으면 hasMe가 false. */
    private double myLat;
    private double myLon;
    private boolean hasMe;

    /** 차가 있는 방위각(내 위치 기준, 0=북). 나침반과 빼서 화살표를 돌린다. */
    private float carBearing;

    /** 지오코딩 결과가 늦게 도착했을 때 이미 다른 기록을 보고 있으면 버린다. */
    private String addressToken;

    private final SensorEventListener compass = new SensorEventListener() {
        private final float[] matrix = new float[9];
        private final float[] orientation = new float[3];
        private float shown = Float.NaN;

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            SensorManager.getOrientation(matrix, orientation);
            float azimuth = (float) Math.toDegrees(orientation[0]);
            float target = normalize(carBearing - azimuth);
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
        this.addressRow = host.findViewById(R.id.locAddressRow);
        this.address = host.findViewById(R.id.locAddress);
        this.copy = host.findViewById(R.id.locCopy);
        this.openMap = host.findViewById(R.id.locOpenMap);
        this.refresh = host.findViewById(R.id.locRefresh);
        this.empty = host.findViewById(R.id.locEmpty);
        this.emptyAction = host.findViewById(R.id.locEmptyAction);

        this.sensors = (SensorManager) host.getSystemService(Context.SENSOR_SERVICE);
        this.rotation = sensors == null
                ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        addressRow.setOnClickListener(v -> copyAddress());
        copy.setOnClickListener(v -> copyAddress());
        openMap.setOnClickListener(v -> openInMapApp());
        refresh.setOnClickListener(v -> requestFreshFix());
    }

    // ---------- 생명주기 ----------

    /** 탭이 보이기 시작했다. 나침반은 render()가 조건을 보고 켠다. */
    void onShow() {
        render();
    }

    /** 탭을 벗어났거나 앱이 백그라운드로 갔다. 센서를 반드시 끈다. */
    void onHide() {
        stopCompass();
    }

    /**
     * 차 좌표와 내 좌표를 둘 다 알 때만 켠다.
     *
     * <p>내 위치를 모르면 방위각을 계산할 수 없어 화살표가 0도(정북)에 붙어 있게
     * 되는데, 그건 "북쪽에 차가 있다"는 틀린 정보다. 화살표를 숨기는 것으로는
     * 부족하고 센서도 같이 꺼야 배터리를 안 쓴다.
     */
    private void startCompass() {
        if (listening || sensors == null || rotation == null || !hasCar || !hasMe) return;
        // UI 지연은 초당 ~5회로 충분하다. 방향 화살표는 사람이 걸으면서 보는 것이라
        // 게임용 샘플링 속도가 필요 없다.
        sensors.registerListener(compass, rotation, SensorManager.SENSOR_DELAY_UI);
        listening = true;
    }

    private void stopCompass() {
        if (!listening || sensors == null) return;
        sensors.unregisterListener(compass);
        listening = false;
    }

    // ---------- 그리기 ----------

    void render() {
        JSONObject latest = Store.latestRecord(host);
        hasCar = Store.recordHasCoords(latest);
        if (hasCar) {
            carLat = Store.recordLat(latest);
            carLon = Store.recordLon(latest);
        }

        if (!hasCar) {
            showEmpty(latest);
            stopCompass();
            return;
        }

        card.setVisibility(View.VISIBLE);
        addressRow.setVisibility(View.VISIBLE);
        openMap.setVisibility(View.VISIBLE);
        refresh.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        emptyAction.setVisibility(View.GONE);

        Location me = Nearby.lastFix(host);
        hasMe = me != null;
        if (hasMe) {
            myLat = me.getLatitude();
            myLon = me.getLongitude();
        }
        // 나침반은 renderDistance가 켜고 끈다. 여기서 한 번 더 켜면 "거의 다 왔어요"
        // 상태에서 방금 끈 센서를 되살리게 된다.
        renderDistance();
        loadAddress();
    }

    private void renderDistance() {
        if (!hasMe) {
            // 차 좌표는 아는데 내 위치를 모르는 상태. 주소와 지도 열기는 여전히
            // 쓸모가 있으므로 거리만 대기 문구로 둔다.
            distance.setText(R.string.location_waiting);
            bearingText.setText("");
            arrow.setVisibility(View.GONE);
            stopCompass();
            return;
        }

        double meters = Store.metersBetween(myLat, myLon, carLat, carLon);
        if (meters < ARRIVED_M) {
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

        carBearing = (float) bearingTo(myLat, myLon, carLat, carLon);
        startCompass();
        String compassName = host.getString(directionName(carBearing));
        int walkMinutes = (int) Math.ceil(meters / WALK_M_PER_MIN);
        // 30분 넘게 걸어갈 거리면 도보 시간은 의미가 없다. 차를 타고 온 곳이거나
        // 아직 집에서 출발도 안 한 상태다.
        bearingText.setText(walkMinutes <= 30
                ? host.getString(R.string.location_bearing, compassName, walkMinutes)
                : host.getString(R.string.location_bearing_far, compassName));

        arrow.setVisibility(rotation == null ? View.GONE : View.VISIBLE);
    }

    /** 좌표가 없는 세 가지 이유를 구분해 안내한다. 셋 다 사용자가 할 일이 다르다. */
    private void showEmpty(JSONObject latest) {
        card.setVisibility(View.GONE);
        addressRow.setVisibility(View.GONE);
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

    // ---------- 주소 ----------

    /**
     * 좌표 → 주소. Geocoder는 백엔드에 물어보느라 몇 초가 걸릴 수 있어서 워커
     * 스레드에서 돌린다. 시스템 서비스로 가는 IPC라 앱에는 INTERNET 권한이 없어도 된다.
     */
    private void loadAddress() {
        if (!Geocoder.isPresent()) {
            address.setText(R.string.location_address_none);
            return;
        }
        final String token = carLat + "," + carLon;
        addressToken = token;
        address.setText(R.string.location_address_loading);

        final double lat = carLat;
        final double lon = carLon;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String found = null;
            try {
                List<Address> list =
                        new Geocoder(host, Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (list != null && !list.isEmpty()) found = describe(list.get(0));
            } catch (Exception ignored) {
                // 기기에 지오코더 백엔드가 없거나 네트워크가 없으면 그냥 실패한다.
                // 주소는 거들 뿐이고, 거리와 방향만으로도 차는 찾을 수 있다.
            }
            final String result = found;
            main.post(() -> {
                // 결과가 오는 사이에 다른 기록을 보고 있으면 버린다.
                if (!token.equals(addressToken)) return;
                address.setText(result == null
                        ? host.getString(R.string.location_address_none) : result);
            });
        }, "geocode").start();
    }

    /** 한국 주소는 getAddressLine(0)에 "대한민국"이 앞에 붙는다. 그 부분을 떼어 낸다. */
    private String describe(Address a) {
        String line = a.getAddressLine(0);
        if (line == null || line.trim().isEmpty()) return null;
        String country = a.getCountryName();
        if (country != null && line.startsWith(country)) {
            line = line.substring(country.length()).trim();
        }
        return line.isEmpty() ? null : line;
    }

    private void copyAddress() {
        CharSequence text = address.getText();
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
     * <p>{@code geo:} 인텐트라 우리가 어디로도 좌표를 보내지 않는다. 사용자가 자기
     * 지도 앱을 여는 것이고, 그 앱이 뭘 하는지는 그 앱의 몫이다.
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
        refresh.setEnabled(false);
        distance.setText(R.string.location_waiting);
        Nearby.requestFix(host, fix -> {
            refresh.setEnabled(true);
            hasMe = fix != null;
            if (hasMe) {
                myLat = fix.getLatitude();
                myLon = fix.getLongitude();
            }
            renderDistance();
        });
    }

    // ---------- 방위 계산 ----------

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

    /** 두 각도의 최단 차이 (-180~180). 359도와 1도가 2도 차이임을 알게 한다. */
    private static float delta(float a, float b) {
        return (a - b + 540) % 360 - 180;
    }
}
