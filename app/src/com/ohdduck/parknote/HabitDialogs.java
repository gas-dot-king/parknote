package com.ohdduck.parknote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/** "나가기 전 체크" 항목 추가 · 리마인더 · 삭제. */
class HabitDialogs {

    private static final int DEFAULT_REMINDER_MINUTES = 21 * 60; // 오후 9시

    private HabitDialogs() {
    }

    static void showAdd(Activity a, ScreenHost host) {
        Ui.inputDialog(a, a.getString(R.string.habit_add_title),
                a.getString(R.string.habit_name_hint), null, name -> {
            if (name.isEmpty()) return;
            if (Store.habitByName(a, name) != null) {
                Toast.makeText(a, R.string.habit_duplicate, Toast.LENGTH_SHORT).show();
                return;
            }
            Store.addHabit(a, name);
            Toast.makeText(a, R.string.habit_added_tip, Toast.LENGTH_LONG).show();
            host.refresh(false);
        });
    }

    static void showOptions(Activity a, ScreenHost host, int index) {
        JSONObject habit = Store.habits(a).optJSONObject(index);
        if (habit == null) return;
        String name = habit.optString("n");
        int reminder = habit.optInt("r", -1);

        ArrayList<String> items = new ArrayList<>();
        items.add(reminder < 0
                ? a.getString(R.string.habit_reminder_set)
                : a.getString(R.string.habit_reminder_change,
                        Store.formatMinutesOfDay(reminder)));
        if (reminder >= 0) items.add(a.getString(R.string.habit_reminder_clear));
        items.add(a.getString(R.string.action_delete));

        new AlertDialog.Builder(a)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        pickReminderTime(a, host, index, reminder);
                    } else if (reminder >= 0 && which == 1) {
                        Store.setReminder(a, index, -1);
                        Reminders.cancel(a, name);
                        Toast.makeText(a, R.string.habit_reminder_cleared,
                                Toast.LENGTH_SHORT).show();
                        host.refresh(false);
                    } else {
                        confirmDelete(a, host, index, name);
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static void pickReminderTime(Activity a, ScreenHost host, int index, int current) {
        int initial = current >= 0 ? current : DEFAULT_REMINDER_MINUTES;
        new TimePickerDialog(a, (view, hour, minute) -> {
            int minutesOfDay = hour * 60 + minute;
            Store.setReminder(a, index, minutesOfDay);
            Reminders.scheduleAll(a);
            Toast.makeText(a, a.getString(R.string.habit_reminder_daily,
                    Store.formatMinutesOfDay(minutesOfDay)), Toast.LENGTH_SHORT).show();
            host.refresh(false);
        }, initial / 60, initial % 60, false).show();
    }

    private static void confirmDelete(Activity a, ScreenHost host, int index, String name) {
        new AlertDialog.Builder(a)
                .setMessage(a.getString(R.string.habit_delete_confirm, name))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    Store.deleteHabit(a, index);
                    Reminders.cancel(a, name);
                    host.refresh(false);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
