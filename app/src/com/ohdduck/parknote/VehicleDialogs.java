package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 차량 전환 · 추가 · 정보 수정 · 삭제. */
class VehicleDialogs {

    private VehicleDialogs() {
    }

    static void showPicker(Activity a, ScreenHost host) {
        JSONArray vehicles = Store.vehicles(a);
        String active = Store.activeVehicleId(a);
        String[] items = new String[vehicles.length()];
        for (int i = 0; i < vehicles.length(); i++) {
            JSONObject vehicle = vehicles.optJSONObject(i);
            String id = vehicle == null ? "" : vehicle.optString("id");
            String label = RecordEditor.vehicleLabel(a, vehicle);
            items[i] = id.equals(active)
                    ? a.getString(R.string.profile_selected, label) : label;
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.vehicle_switch_title)
                .setItems(items, (d, which) -> {
                    JSONObject vehicle = vehicles.optJSONObject(which);
                    if (vehicle == null) return;
                    Store.setActiveVehicle(a, vehicle.optString("id"));
                    host.refresh(false);
                })
                .setNeutralButton(R.string.action_add, (d, w) -> showEditor(a, host, null))
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    static void showCurrentOptions(Activity a, ScreenHost host) {
        JSONObject vehicle = Store.activeVehicle(a);
        if (vehicle == null) return;
        ArrayList<String> items = new ArrayList<>();
        items.add(a.getString(R.string.vehicle_edit_title));
        if (Store.vehicleCount(a) > 1) items.add(a.getString(R.string.action_delete));
        new AlertDialog.Builder(a)
                .setTitle(vehicle.optString("n",
                        a.getString(R.string.vehicle_default_name)))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) showEditor(a, host, vehicle);
                    else confirmDelete(a, host);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static void showEditor(Activity a, ScreenHost host, JSONObject existing) {
        boolean adding = existing == null;
        LinearLayout form = Ui.form(a);

        form.addView(Ui.hint(a, a.getString(R.string.vehicle_bt_guide)));

        EditText name = Ui.input(a, a.getString(R.string.vehicle_name_hint));
        name.setText(adding ? "" : existing.optString("n", ""));
        Ui.addField(form, a.getString(R.string.vehicle_name_label), name, 16);

        // 이름을 손으로 적으면 한 글자만 달라도 자동 알림이 조용히 안 뜬다 → 목록에서 고르게 한다.
        final String[] bt = {Store.vehicleBtName(existing), Store.vehicleBtAddress(existing)};
        Button btButton = Ui.pickerButton(a, btLabel(a, bt[0]));
        btButton.setOnClickListener(v -> BtPicker.show(a, bt[0], (pickedName, pickedAddress) -> {
            bt[0] = pickedName;
            bt[1] = pickedAddress;
            btButton.setText(btLabel(a, pickedName));
        }));
        Ui.addField(form, a.getString(R.string.vehicle_bt_label), btButton, 12);

        AlertDialog dialog = Ui.validating(
                new AlertDialog.Builder(a)
                        .setTitle(adding
                                ? R.string.vehicle_add_title : R.string.vehicle_edit_title)
                        .setView(Ui.scroll(a, form))
                        .setPositiveButton(R.string.action_save, null)
                        .setNegativeButton(R.string.action_cancel, null),
                () -> {
                    try {
                        if (adding) {
                            Store.addVehicle(a, name.getText().toString(), bt[0], bt[1]);
                        } else {
                            Store.updateVehicle(a, existing.optString("id"),
                                    name.getText().toString(), bt[0], bt[1]);
                        }
                    } catch (IllegalArgumentException e) {
                        name.setError(e.getMessage());
                        name.requestFocus();
                        return false;
                    }
                    host.refresh(false);
                    Toast.makeText(a, adding ? R.string.vehicle_added : R.string.vehicle_saved,
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
        dialog.show();
    }

    private static void confirmDelete(Activity a, ScreenHost host) {
        String id = Store.activeVehicleId(a);
        String name = Store.activeVehicleName(a);
        new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.vehicle_delete_confirm, name))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    if (Store.deleteVehicle(a, id)) {
                        host.refresh(false);
                        Toast.makeText(a, R.string.vehicle_deleted_toast,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    static String btLabel(Context c, String bt) {
        return bt == null || bt.trim().isEmpty()
                ? c.getString(R.string.vehicle_bt_none) : bt;
    }
}
