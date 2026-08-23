package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 주차장 프로필 전환 · 추가 · 이름 변경 · 삭제. */
class ProfileDialogs {

    private ProfileDialogs() {
    }

    /** 주차장 전환. 목록에서 고르면 바로 전환되고, 중립 버튼으로 새로 추가한다. */
    static void showPicker(Activity a, ScreenHost host) {
        JSONArray profiles = Store.profiles(a);
        String active = Store.activeProfileId(a);
        String[] items = new String[profiles.length()];
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            String id = profile == null ? "" : profile.optString("id");
            String fallback = a.getString(R.string.profile_default_name);
            String name = profile == null ? fallback : profile.optString("n", fallback);
            items[i] = id.equals(active) ? a.getString(R.string.profile_selected, name) : name;
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.profile_switch_title)
                .setItems(items, (d, which) -> {
                    JSONObject profile = profiles.optJSONObject(which);
                    if (profile == null) return;
                    Store.setActiveProfile(a, profile.optString("id"));
                    host.refresh(true);
                })
                .setNeutralButton(R.string.action_add, (d, w) -> showNameDialog(a, host, true))
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    /** 현재 주차장 관리. */
    static void showCurrentOptions(Activity a, ScreenHost host) {
        ArrayList<String> items = new ArrayList<>();
        items.add(a.getString(R.string.profile_rename));
        items.add(a.getString(R.string.profile_manage_zones));
        if (Store.profileCount(a) > 1) items.add(a.getString(R.string.action_delete));
        new AlertDialog.Builder(a)
                .setTitle(Store.activeProfileName(a))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) showNameDialog(a, host, false);
                    else if (which == 1) host.openZoneSettings();
                    else confirmDelete(a, host);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static void showNameDialog(Activity a, ScreenHost host, boolean adding) {
        EditText input = Ui.input(a, a.getString(R.string.profile_name_hint));
        if (!adding) {
            input.setText(Store.activeProfileName(a));
            input.setSelection(input.getText().length());
        }
        LinearLayout wrap = Ui.form(a);
        wrap.addView(input);

        AlertDialog dialog = Ui.validating(
                new AlertDialog.Builder(a)
                        .setTitle(adding
                                ? R.string.profile_new_title : R.string.profile_rename_title)
                        .setView(wrap)
                        .setPositiveButton(R.string.action_save, null)
                        .setNegativeButton(R.string.action_cancel, null),
                () -> {
                    try {
                        if (adding) Store.addProfile(a, input.getText().toString());
                        else Store.renameProfile(a, Store.activeProfileId(a),
                                input.getText().toString());
                    } catch (IllegalArgumentException e) {
                        input.setError(e.getMessage());
                        input.requestFocus();
                        return false;
                    }
                    host.refresh(true);
                    Toast.makeText(a, adding ? R.string.profile_added : R.string.profile_renamed,
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
        Ui.showWithKeyboard(dialog, input);
    }

    private static void confirmDelete(Activity a, ScreenHost host) {
        String id = Store.activeProfileId(a);
        String name = Store.activeProfileName(a);
        new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.profile_delete_confirm, name))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    if (Store.deleteProfile(a, id)) {
                        host.refresh(true);
                        Toast.makeText(a, R.string.profile_deleted_toast,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
