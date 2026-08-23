package com.ohdduck.parknote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

/** 홈 화면 위젯: 메인 구역 원탭 저장 + 현재 위치 표시 */
public class ParkWidgetProvider extends AppWidgetProvider {

    static final String ACTION_RECORD = "com.ohdduck.parknote.RECORD_ZONE";
    static final String EXTRA_ZONE = "zone";
    static final String EXTRA_PROFILE_ID = "profile_id";
    static final String EXTRA_VEHICLE_ID = "vehicle_id";

    // 위젯은 고정 크기라 설정된 자주 쓰는 구역 중 앞 6개를 표시한다.
    private static final int[] BTN_IDS = {
            R.id.btnB1A, R.id.btnB1B,
            R.id.btnB2A, R.id.btnB2B,
            R.id.btnB3A, R.id.btnB3B};
    private static final int[] ROW_IDS = {
            R.id.widgetRow1, R.id.widgetRow2, R.id.widgetRow3};

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_RECORD.equals(intent.getAction())) {
            String zone = intent.getStringExtra(EXTRA_ZONE);
            if (zone != null) {
                String recordId = Store.recordInContext(ctx,
                        intent.getStringExtra(EXTRA_PROFILE_ID),
                        intent.getStringExtra(EXTRA_VEHICLE_ID), zone, "");
                Toast.makeText(ctx, recordId == null
                                ? ctx.getString(R.string.record_context_changed)
                                : ctx.getString(R.string.record_saved, zone),
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        mgr.updateAppWidget(ids, buildViews(ctx));
    }

    /** 기록이 바뀔 때마다 Store가 호출 */
    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ParkWidgetProvider.class));
        if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, buildViews(ctx));
    }

    private static RemoteViews buildViews(Context ctx) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_park);

        String profileId = Store.activeProfileId(ctx);
        String vehicleId = Store.activeVehicleId(ctx);
        String latest = Store.latestZone(ctx);
        String brand = ctx.getString(R.string.app_name);
        rv.setTextViewText(R.id.widgetStatus, latest == null
                ? ctx.getString(R.string.widget_status_empty, brand,
                        Store.activeProfileName(ctx), Store.activeVehicleName(ctx))
                : ctx.getString(R.string.widget_status, brand, latest,
                        Store.formatShort(Store.latestTime(ctx)),
                        Store.activeProfileName(ctx), Store.activeVehicleName(ctx)));

        // 격자가 크면 앞 6칸이 전부 첫 번째 층이라, 늘 B2에 대는 사람은 위젯에서
        // 자기 자리를 영영 못 누른다. 최근에 쓴 구역으로 골라 담는다.
        String[] zones = Store.widgetZones(ctx, profileId, vehicleId, BTN_IDS.length);
        for (int row = 0; row < ROW_IDS.length; row++) {
            rv.setViewVisibility(ROW_IDS[row], row * 2 < zones.length
                    ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < BTN_IDS.length; i++) {
            if (i >= zones.length) {
                // 마지막 행의 버튼 폭은 유지하되 빈 칸 자체는 보이지 않게 한다.
                rv.setViewVisibility(BTN_IDS[i], View.INVISIBLE);
                continue;
            }
            String zone = zones[i];
            boolean active = zone.equals(latest);
            rv.setViewVisibility(BTN_IDS[i], View.VISIBLE);
            rv.setTextViewText(BTN_IDS[i], zone);
            rv.setInt(BTN_IDS[i], "setBackgroundResource",
                    active
                            ? R.drawable.bg_button_active : R.drawable.bg_button);
            rv.setTextColor(BTN_IDS[i], ctx.getColor(
                    active ? R.color.on_accent : R.color.text));
            Intent it = new Intent(ctx, ParkWidgetProvider.class)
                    .setAction(ACTION_RECORD)
                    .setData(Uri.fromParts("parknote",
                            "widget/" + profileId + "/" + vehicleId + "/" + i, null))
                    .putExtra(EXTRA_ZONE, zone)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_VEHICLE_ID, vehicleId);
            rv.setOnClickPendingIntent(BTN_IDS[i], PendingIntent.getBroadcast(
                    ctx, i, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        // 상단 상태 줄 탭 → 앱 열기 (기타 구역·직접 입력·기록 확인용)
        rv.setOnClickPendingIntent(R.id.widgetStatus, PendingIntent.getActivity(
                ctx, 100, new Intent(ctx, MainActivity.class)
                        .putExtra(EXTRA_PROFILE_ID, profileId)
                        .putExtra(EXTRA_VEHICLE_ID, vehicleId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return rv;
    }
}
