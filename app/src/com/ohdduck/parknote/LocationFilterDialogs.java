package com.ohdduck.parknote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * "위치로 알림 조절" 설정. 주차장마다 좌표를 등록해 두면 차에서 내린 곳이 등록한
 * 주차장 근처일 때만 평소대로 알린다.
 *
 * <p>권한 요청은 반드시 {@link Nearby#FOREGROUND_PERMISSIONS}로 한다. FINE만 단독으로
 * 요청하면 Android 12부터 시스템이 요청 자체를 무시해서, 기능이 영영 켜지지 않는다.
 */
class LocationFilterDialogs {

    static final int REQ_FOREGROUND = 22;
    static final int REQ_BACKGROUND = 23;
    static final int REQ_CAPTURE = 24;

    private LocationFilterDialogs() {
    }

    static void showMenu(Activity a) {
        boolean on = Store.locationFilterOn(a);
        String name = Store.activeProfileName(a);
        boolean hasCoords = Store.hasCoords(Store.activeProfile(a));

        ArrayList<String> items = new ArrayList<>();
        items.add(a.getString(on ? R.string.location_disable : R.string.location_enable));
        items.add(hasCoords
                ? a.getString(R.string.location_recapture, name)
                : a.getString(R.string.location_capture, name));
        if (hasCoords) items.add(a.getString(R.string.location_clear, name));

        String registered = hasCoords
                ? a.getString(R.string.location_registered, Store.DEFAULT_RADIUS_M)
                : a.getString(R.string.location_unregistered);
        StringBuilder message = new StringBuilder(a.getString(R.string.location_message,
                a.getString(on ? R.string.state_on : R.string.state_off), name, registered));
        if (on && !Store.anyProfileHasCoords(a)) {
            message.append(a.getString(R.string.location_none_note));
        }

        new AlertDialog.Builder(a)
                .setTitle(R.string.location_title)
                .setMessage(message)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        if (on) {
                            Store.setLocationFilter(a, false);
                            Toast.makeText(a, R.string.location_turned_off,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            enable(a);
                        }
                    } else if (which == 1) {
                        captureLocation(a);
                    } else {
                        Store.clearProfileCoords(a, Store.activeProfileId(a));
                        Toast.makeText(a, R.string.location_cleared,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static void enable(Activity a) {
        if (!Nearby.hasForegroundPermission(a)) {
            a.requestPermissions(Nearby.FOREGROUND_PERMISSIONS, REQ_FOREGROUND);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !Nearby.hasPermission(a)) {
            a.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQ_BACKGROUND);
            return;
        }
        Store.setLocationFilter(a, true);
        if (Store.anyProfileHasCoords(a)) {
            Toast.makeText(a, R.string.location_turned_on, Toast.LENGTH_SHORT).show();
        } else {
            new AlertDialog.Builder(a)
                    .setMessage(a.getString(R.string.location_first_capture,
                            Store.activeProfileName(a)))
                    .setPositiveButton(R.string.location_capture_now,
                            (d, w) -> captureLocation(a))
                    .setNegativeButton(R.string.action_later, null)
                    .show();
        }
    }

    private static void captureLocation(Activity a) {
        if (!Nearby.hasForegroundPermission(a)) {
            a.requestPermissions(Nearby.FOREGROUND_PERMISSIONS, REQ_CAPTURE);
            return;
        }
        String profileId = Store.activeProfileId(a);
        String name = Store.activeProfileName(a);
        Toast.makeText(a, R.string.location_capturing, Toast.LENGTH_SHORT).show();
        Nearby.requestFix(a, fix -> {
            // 측위는 최대 15초 걸린다. 그 사이 화면을 닫았으면 다이얼로그를 띄우지 않는다.
            if (a.isFinishing() || a.isDestroyed()) return;
            if (fix == null) {
                new AlertDialog.Builder(a)
                        .setTitle(R.string.location_capture_failed_title)
                        .setMessage(R.string.location_capture_failed)
                        .setPositiveButton(R.string.action_ok, null)
                        .show();
                return;
            }
            new AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.location_save_title, name))
                    .setMessage(a.getString(R.string.location_save_message,
                            Nearby.describeAccuracy(a, fix), Store.DEFAULT_RADIUS_M, name))
                    .setPositiveButton(R.string.action_save, (d, w) -> {
                        Store.setProfileCoords(a, profileId,
                                fix.getLatitude(), fix.getLongitude(), Store.DEFAULT_RADIUS_M);
                        Toast.makeText(a, a.getString(R.string.location_saved, name),
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        });
    }

    /** 위치 권한 요청 결과를 처리한다. 이 흐름의 결과였으면 true. */
    static boolean handlePermissionResult(Activity a, int requestCode,
                                          String[] permissions, int[] results) {
        boolean granted = Nearby.anyLocationGranted(permissions, results);
        if (requestCode == REQ_FOREGROUND) {
            if (granted) enable(a);
            else Toast.makeText(a, R.string.location_denied, Toast.LENGTH_LONG).show();
            return true;
        }
        if (requestCode == REQ_BACKGROUND) {
            if (granted) enable(a);
            else showBackgroundHelp(a);
            return true;
        }
        if (requestCode == REQ_CAPTURE) {
            if (granted) captureLocation(a);
            else Toast.makeText(a, R.string.location_denied_capture,
                    Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    /** 백그라운드 위치는 시스템 다이얼로그가 아니라 설정 화면에서만 켤 수 있는 기기가 많다. */
    private static void showBackgroundHelp(Activity a) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.location_bg_title)
                .setMessage(R.string.location_bg_message)
                .setPositiveButton(R.string.location_open_settings, (d, w) -> {
                    try {
                        a.startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", a.getPackageName(), null)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(a, R.string.location_settings_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_later, null)
                .show();
    }
}
