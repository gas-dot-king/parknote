package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * 주차 기록 하나를 고치는 화면. 주차장·차량·위치·시각·메모·출차 알림을 함께 다룬다.
 *
 * <p>기록에 붙는 선택지(주차장/차량 고르기, 시각 고르기, 타이머 고르기)가 전부 여기 있어서,
 * 상태 카드의 "수정"과 알림에서 열린 편집기와 전체 기록 목록이 같은 코드를 탄다.
 */
class RecordEditor {

    private static final int MAX_MEMO_LENGTH = 200;

    private RecordEditor() {
    }

    private interface StringChoice {
        void onChosen(String value);
    }

    private interface LongChoice {
        void onChosen(long value);
    }

    // ---------- 기록 편집 ----------

    /**
     * 기록 편집기를 연다. 기록이 없으면 알리고 null을 돌려준다.
     *
     * @return 떠 있는 다이얼로그. 호출한 화면이 회전 대비로 상태를 추적할 수 있게 넘긴다.
     */
    static AlertDialog show(Activity a, ScreenHost host, String recordId) {
        JSONObject record = Store.recordById(a, recordId);
        if (record == null) {
            Toast.makeText(a, R.string.record_not_found, Toast.LENGTH_SHORT).show();
            return null;
        }
        final String[] profileId = {record.optString("p", Store.activeProfileId(a))};
        final String[] vehicleId = {record.optString("c", Store.activeVehicleId(a))};
        final long[] parkedAt = {record.optLong("t", System.currentTimeMillis())};
        final long[] due = {record.optLong("due", 0)};

        LinearLayout form = Ui.form(a);

        Button profileButton = Ui.pickerButton(a, profileButtonText(a, record));
        Ui.addField(form, a.getString(R.string.record_label_profile), profileButton, 0);

        Button vehicleButton = Ui.pickerButton(a, vehicleButtonText(a, record));
        Ui.addField(form, a.getString(R.string.record_label_vehicle), vehicleButton, 12);

        EditText zone = Ui.input(a, a.getString(R.string.record_zone_hint));
        zone.setText(record.optString("z", ""));
        Ui.addField(form, a.getString(R.string.record_label_zone), zone, 12);

        Button timeButton = Ui.pickerButton(a, Store.formatFull(parkedAt[0]));
        Ui.addField(form, a.getString(R.string.record_label_time), timeButton, 12);

        EditText memo = Ui.multilineInput(a, a.getString(R.string.record_memo_hint), 2, 4);
        memo.setText(Store.recordMemo(record));
        Ui.addField(form, a.getString(R.string.record_label_memo), memo, 12);

        Button timerButton = Ui.pickerButton(a, timerButtonText(a, due[0]));
        Ui.addField(form, a.getString(R.string.parking_timer), timerButton, 12);

        profileButton.setOnClickListener(v -> chooseProfile(a, profileId[0], id -> {
            profileId[0] = id;
            profileButton.setText(Store.profileName(a, id));
        }));
        vehicleButton.setOnClickListener(v -> chooseVehicle(a, vehicleId[0], id -> {
            vehicleId[0] = id;
            vehicleButton.setText(Store.vehicleName(a, id));
        }));
        timeButton.setOnClickListener(v -> pickDateTime(a, parkedAt[0], value -> {
            parkedAt[0] = value;
            timeButton.setText(Store.formatFull(value));
        }));
        timerButton.setOnClickListener(v -> pickTimer(a, due[0], value -> {
            due[0] = value;
            timerButton.setText(timerButtonText(a, value));
        }));

        AlertDialog dialog = Ui.validating(
                new AlertDialog.Builder(a)
                        .setTitle(R.string.record_edit_title)
                        .setView(Ui.scroll(a, form))
                        .setPositiveButton(R.string.action_save, null)
                        .setNeutralButton(R.string.action_delete, null)
                        .setNegativeButton(R.string.action_cancel, null),
                () -> save(a, host, recordId, profileId[0], vehicleId[0], zone, memo,
                        parkedAt[0], due[0]),
                parent -> confirmDelete(a, host, recordId, parent));
        dialog.show();
        return dialog;
    }

    private static boolean save(Activity a, ScreenHost host, String recordId,
                                String profileId, String vehicleId,
                                EditText zone, EditText memo, long parkedAt, long due) {
        String z = zone.getText().toString().trim();
        String m = memo.getText().toString().trim();
        if (z.isEmpty()) {
            zone.setError(a.getString(R.string.record_zone_required));
            zone.requestFocus();
            return false;
        }
        if (m.length() > MAX_MEMO_LENGTH) {
            memo.setError(a.getString(R.string.record_memo_too_long, MAX_MEMO_LENGTH));
            memo.requestFocus();
            return false;
        }
        if (!Store.updateRecord(a, recordId, profileId, vehicleId, z, parkedAt, m, due)) {
            Toast.makeText(a, R.string.record_save_error, Toast.LENGTH_SHORT).show();
            return false;
        }
        host.refresh(true);
        Toast.makeText(a, R.string.record_updated, Toast.LENGTH_SHORT).show();
        return true;
    }

    private static void confirmDelete(Activity a, ScreenHost host, String recordId,
                                      AlertDialog parent) {
        new AlertDialog.Builder(a)
                .setMessage(R.string.record_delete_confirm)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    Store.deleteRecord(a, recordId);
                    host.refresh(false);
                    parent.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------- 출차 알림만 빠르게 ----------

    /** 상태 카드의 "출차 시간 알림" 버튼. 편집기를 거치지 않고 타이머만 바꾼다. */
    static void showTimerOnly(Activity a, ScreenHost host, String recordId) {
        JSONObject record = Store.recordById(a, recordId);
        if (record == null) return;
        pickTimer(a, record.optLong("due", 0), due -> {
            Store.setParkingTimer(a, recordId, due);
            host.refresh(false);
            Toast.makeText(a, a.getString(due > 0
                            ? R.string.timer_enabled : R.string.timer_disabled,
                    a.getString(R.string.parking_timer)), Toast.LENGTH_SHORT).show();
        });
    }

    // ---------- 선택지 ----------

    private static void chooseProfile(Activity a, String selected, StringChoice callback) {
        JSONArray profiles = Store.profiles(a);
        String[] items = new String[profiles.length()];
        int checked = -1;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            String id = profile == null ? "" : profile.optString("id");
            items[i] = profile == null
                    ? a.getString(R.string.profile_default_name)
                    : profile.optString("n", a.getString(R.string.profile_default_name));
            if (id.equals(selected)) checked = i;
        }
        pickOne(a, a.getString(R.string.profile_choose_title), items, checked,
                which -> {
                    JSONObject profile = profiles.optJSONObject(which);
                    if (profile != null) callback.onChosen(profile.optString("id"));
                });
    }

    private static void chooseVehicle(Activity a, String selected, StringChoice callback) {
        JSONArray vehicles = Store.vehicles(a);
        String[] items = new String[vehicles.length()];
        int checked = -1;
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject vehicle = vehicles.optJSONObject(i);
            String id = vehicle == null ? "" : vehicle.optString("id");
            items[i] = vehicleLabel(a, vehicle);
            if (id.equals(selected)) checked = i;
        }
        pickOne(a, a.getString(R.string.vehicle_choose_title), items, checked,
                which -> {
                    JSONObject vehicle = vehicles.optJSONObject(which);
                    if (vehicle != null) callback.onChosen(vehicle.optString("id"));
                });
    }

    /** 차량 이름 + 블루투스 연결 여부. 목록마다 같은 형식으로 보여 준다. */
    static String vehicleLabel(Context c, JSONObject vehicle) {
        String fallback = c.getString(R.string.vehicle_default_name);
        if (vehicle == null) return fallback;
        String name = vehicle.optString("n", fallback);
        String bt = vehicle.optString("b", "");
        return bt.isEmpty()
                ? c.getString(R.string.vehicle_manual_suffix, name)
                : c.getString(R.string.vehicle_bt_suffix, name, bt);
    }

    private interface IndexChoice {
        void onChosen(int which);
    }

    /** 라디오 목록에서 하나를 고르면 바로 닫힌다. 확인 버튼을 한 번 더 누르지 않게. */
    private static void pickOne(Activity a, String title, String[] items, int checked,
                                IndexChoice callback) {
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getListView().setOnItemClickListener((parent, view, which, id) -> {
                    callback.onChosen(which);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private static void pickDateTime(Activity a, long initial, LongChoice callback) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(initial);
        new DatePickerDialog(a, (view, year, month, day) ->
                new TimePickerDialog(a, (timeView, hour, minute) -> {
                    Calendar chosen = Calendar.getInstance();
                    chosen.set(year, month, day, hour, minute, 0);
                    chosen.set(Calendar.MILLISECOND, 0);
                    callback.onChosen(chosen.getTimeInMillis());
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show(),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static void pickTimer(Activity a, long currentDue, LongChoice callback) {
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Long> values = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (currentDue > now) {
            items.add(a.getString(R.string.timer_clear,
                    a.getString(R.string.parking_timer)));
            values.add(0L);
        }
        items.add(a.getString(R.string.timer_30m));
        values.add(now + 30 * 60_000L);
        items.add(a.getString(R.string.timer_1h));
        values.add(now + 60 * 60_000L);
        items.add(a.getString(R.string.timer_2h));
        values.add(now + 2 * 60 * 60_000L);
        items.add(a.getString(R.string.timer_custom));
        values.add(-1L);

        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.parking_timer))
                .setMessage(a.getString(R.string.timer_notice))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    long value = values.get(which);
                    if (value >= 0) callback.onChosen(value);
                    else pickFutureDateTime(a, now + 60 * 60_000L, callback);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void pickFutureDateTime(Activity a, long initial, LongChoice callback) {
        pickDateTime(a, initial, value -> {
            if (value <= System.currentTimeMillis()) {
                Toast.makeText(a, R.string.timer_must_be_future, Toast.LENGTH_SHORT).show();
                return;
            }
            callback.onChosen(value);
        });
    }

    // ---------- 라벨 ----------

    private static String profileButtonText(Activity a, JSONObject record) {
        String id = record.optString("p", "");
        return Store.profileById(a, id) == null
                ? a.getString(R.string.profile_deleted_prefix,
                        Store.recordProfileName(a, record))
                : Store.recordProfileName(a, record);
    }

    private static String vehicleButtonText(Activity a, JSONObject record) {
        String id = record.optString("c", "");
        return Store.vehicleById(a, id) == null
                ? a.getString(R.string.vehicle_deleted_prefix,
                        Store.recordVehicleName(a, record))
                : Store.recordVehicleName(a, record);
    }

    private static String timerButtonText(Context c, long due) {
        return due > System.currentTimeMillis()
                ? c.getString(R.string.timer_set, Store.formatFull(due))
                : c.getString(R.string.timer_unset);
    }
}
