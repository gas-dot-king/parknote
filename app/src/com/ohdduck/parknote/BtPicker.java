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
 * 페어링된 기기 목록에서 차 블루투스 이름을 고르게 한다.
 *
 * <p>이름을 손으로 받아 적게 하면 한 글자만 달라도 자동 알림이 조용히 안 뜨고,
 * 사용자는 이유를 알 수 없다. 그래서 목록 선택을 기본 경로로 두고 직접 입력은 폴백으로 둔다.
 */
class BtPicker {

    interface OnPicked {
        void onPicked(String name);
    }

    /** 페어링된 기기 이름. 블루투스가 없거나 권한이 없으면 null. */
    private static ArrayList<String> bondedNames(Activity a) {
        BluetoothManager manager = a.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return null;
        ArrayList<String> names = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return names;
            for (BluetoothDevice device : bonded) {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        } catch (SecurityException ignored) {
            return null; // BLUETOOTH_CONNECT 미허용
        }
        return names;
    }

    static void show(Activity a, String current, OnPicked onPicked) {
        ArrayList<String> names = bondedNames(a);
        if (names == null || names.isEmpty()) {
            new AlertDialog.Builder(a)
                    .setTitle("고를 수 있는 기기가 없어요")
                    .setMessage(names == null
                            ? "블루투스 권한이 없거나 기기가 블루투스를 지원하지 않아요. "
                                    + "이름을 직접 입력할 수 있어요."
                            : "페어링된 기기가 없어요. 차에 타서 블루투스가 연결된 뒤 "
                                    + "다시 시도하거나, 이름을 직접 입력해 주세요.")
                    .setPositiveButton("직접 입력", (d, w) -> manual(a, current, onPicked))
                    .setNegativeButton("나중에", null)
                    .show();
            return;
        }
        ArrayList<String> items = new ArrayList<>(names);
        items.add("직접 입력…");
        if (current != null && !current.trim().isEmpty()) items.add("비우기 · 수동 기록만");
        String[] entries = items.toArray(new String[0]);

        new AlertDialog.Builder(a)
                .setTitle("차 블루투스 고르기")
                .setItems(entries, (d, which) -> {
                    String chosen = entries[which];
                    if (chosen.startsWith("직접 입력")) manual(a, current, onPicked);
                    else if (chosen.startsWith("비우기")) onPicked.onPicked("");
                    else onPicked.onPicked(chosen);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private static void manual(Activity a, String current, OnPicked onPicked) {
        EditText input = new EditText(a);
        input.setText(current == null ? "" : current);
        input.setHint("예: CAR-AUDIO");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSelection(input.getText().length());

        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(a)
                .setTitle("블루투스 이름 직접 입력")
                .setMessage("차에 연결됐을 때 표시되는 기기 이름과 정확히 같아야 해요.")
                .setView(wrap)
                .setPositiveButton("저장",
                        (d, w) -> onPicked.onPicked(input.getText().toString().trim()))
                .setNegativeButton("취소", null)
                .show();
    }
}
