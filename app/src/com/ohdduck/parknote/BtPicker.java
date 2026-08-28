package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Set;

/**
 * 페어링된 기기 목록에서 차 블루투스를 고르게 한다.
 *
 * <p>이름을 손으로 받아 적게 하면 한 글자만 달라도 자동 알림이 조용히 안 뜨고,
 * 사용자는 이유를 알 수 없다. 그래서 목록 선택을 기본 경로로 두고 직접 입력은 폴백으로 둔다.
 * 목록에서 고르면 이름과 함께 주소도 넘긴다 — 같은 이름의 헤드유닛이 둘이어도 구분된다.
 */
class BtPicker {

    interface OnPicked {
        /** 직접 입력하거나 비우면 address가 빈 문자열이다. */
        void onPicked(String name, String address);
    }

    /** 페어링된 기기 {이름, 주소}. 블루투스가 없거나 권한이 없으면 null. */
    private static ArrayList<String[]> bondedDevices(Activity a) {
        BluetoothManager manager = a.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return null;
        ArrayList<String[]> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return devices;
            for (BluetoothDevice device : bonded) {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) {
                    devices.add(new String[]{name, device.getAddress()});
                }
            }
        } catch (SecurityException ignored) {
            return null; // BLUETOOTH_CONNECT 미허용
        }
        return devices;
    }

    static void show(Activity a, String current, OnPicked onPicked) {
        boolean permissionMissing = !ReadyCheck.hasBluetoothPermission(a);
        ArrayList<String[]> devices = bondedDevices(a);
        if (devices == null || devices.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(a)
                    .setTitle(R.string.bt_none_title)
                    .setMessage(permissionMissing || devices == null
                            ? R.string.bt_no_permission : R.string.bt_no_bonded)
                    .setNegativeButton(R.string.action_later, null);
            if (permissionMissing) {
                builder.setPositiveButton(R.string.bt_permission_button,
                                (d, w) -> ReadyCheck.openBluetoothPermissionSettings(a))
                        .setNeutralButton(R.string.bt_manual_button,
                                (d, w) -> manual(a, current, onPicked));
            } else {
                builder.setPositiveButton(R.string.bt_manual_button,
                        (d, w) -> manual(a, current, onPicked));
            }
            builder.show();
            return;
        }
        ArrayList<String> items = new ArrayList<>();
        for (String[] device : devices) items.add(label(devices, device));
        int manualIndex = items.size();
        items.add(a.getString(R.string.bt_manual_entry));
        int clearIndex = -1;
        if (current != null && !current.trim().isEmpty()) {
            clearIndex = items.size();
            items.add(a.getString(R.string.bt_clear));
        }
        final int manualAt = manualIndex;
        final int clearAt = clearIndex;

        // 문구가 아니라 인덱스로 분기한다. 예전에는 항목 텍스트의 접두사를 비교해서,
        // 문구를 다듬는 순간 조용히 엉뚱한 기기가 선택될 수 있었다.
        new AlertDialog.Builder(a)
                .setTitle(R.string.bt_pick_title)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == manualAt) manual(a, current, onPicked);
                    else if (which == clearAt) onPicked.onPicked("", "");
                    else onPicked.onPicked(devices.get(which)[0], devices.get(which)[1]);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 같은 이름이 둘 이상이면 주소 끝자리를 붙여 구분한다. */
    private static String label(ArrayList<String[]> devices, String[] device) {
        int sameName = 0;
        for (String[] other : devices) {
            if (other[0].equalsIgnoreCase(device[0])) sameName++;
        }
        if (sameName < 2 || device[1] == null || device[1].length() < 5) return device[0];
        return device[0] + " · " + device[1].substring(device[1].length() - 5);
    }

    private static void manual(Activity a, String current, OnPicked onPicked) {
        // 앱의 다른 폼과 같은 입력 필드(Ui.input). 기기 이름은 문장이 아니므로
        // 첫 글자 대문자화는 끈다 — "CAR-AUDIO"를 그대로 받아 적어야 한다.
        EditText input = Ui.input(a, a.getString(R.string.bt_manual_hint));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(current == null ? "" : current);
        input.setSelection(input.getText().length());

        LinearLayout wrap = Ui.form(a);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.bt_manual_title)
                .setMessage(R.string.bt_manual_message)
                .setView(wrap)
                .setPositiveButton(R.string.action_save,
                        (d, w) -> onPicked.onPicked(input.getText().toString().trim(), ""))
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        Ui.showWithKeyboard(dialog, input);
    }
}
