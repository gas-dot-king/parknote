package com.ohdduck.parknote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** SharedPreferences에 문자열로 들어 있는 JSON 구조를 다루는 도우미. Store·Migration·Habits가 함께 쓴다. */
final class Json {

    private Json() {
    }

    /** 문자열을 배열로. 없거나 깨졌으면 null. */
    static JSONArray array(String raw) {
        if (raw == null) return null;
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return null;
        }
    }

    /** 배열의 문자열 항목. 공백뿐인 항목은 버린다. */
    static String[] strings(JSONArray a) {
        if (a == null) return new String[0];
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String value = clean(a.optString(i, ""));
            if (!value.isEmpty()) out.add(value);
        }
        return out.toArray(new String[0]);
    }

    static JSONArray of(String[] values) {
        JSONArray out = new JSONArray();
        if (values != null) {
            for (String value : values) out.put(value);
        }
        return out;
    }

    static JSONObject byId(JSONArray items, String id) {
        if (items == null || id == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    static JSONObject copy(JSONObject source) throws JSONException {
        return new JSONObject(source.toString());
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
