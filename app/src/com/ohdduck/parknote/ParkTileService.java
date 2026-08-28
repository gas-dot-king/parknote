package com.ohdduck.parknote;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 퀵설정 타일: 현재 주차 위치를 바로 보여주고, 누르면 앱을 연다. */
public class ParkTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        String zone = Store.latestZone(this);
        // 기록이 없으면 비활성으로 둔다. 항상 ACTIVE면 "지금 어딘가에 대 있다"는
        // 잘못된 신호를 준다.
        t.setState(zone == null ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        t.setLabel(zone == null ? getString(R.string.app_name) : zone);
        if (Build.VERSION.SDK_INT >= 29 && zone != null) {
            t.setSubtitle(getString(R.string.tile_subtitle,
                    getString(R.string.app_name),
                    Store.activeProfileName(this),
                    Store.activeVehicleName(this),
                    Fmt.time(Store.latestTime(this))));
        }
        t.updateTile();
    }

    /**
     * Android 14부터 Intent를 받는 오버로드가 UnsupportedOperationException을 던진다.
     * 버전으로 갈라 쓰므로 실제로는 안전하고, lint가 그 분기를 못 읽어 경고만 남는다.
     */
    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
