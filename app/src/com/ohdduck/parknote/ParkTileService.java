package com.ohdduck.parknote;

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
        t.setState(Tile.STATE_ACTIVE);
        t.setLabel(zone == null ? getString(R.string.app_name) : zone);
        if (Build.VERSION.SDK_INT >= 29 && zone != null) {
            t.setSubtitle(getString(R.string.app_name) + " · "
                    + Store.activeProfileName(this) + " · "
                    + Store.activeVehicleName(this) + " · "
                    + Store.formatShort(Store.latestTime(this)));
        }
        t.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation")
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
