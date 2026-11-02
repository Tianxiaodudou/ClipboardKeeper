package com.tianxiaodudou.clipboardkeeper.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 过滤规则存 Settings.Global 的一个字符串键里，APK 用 su 写，Hook 侧直接读。 */
public final class FilterStore {

    public static final String KEY = "clipboard_keeper_filter_rules";

    private FilterStore() {
    }

    public static List<FilterRule> load() {
        return parse(RootShell.readSettingRaw(KEY));
    }

    public static List<FilterRule> parse(String raw) {
        List<FilterRule> rules = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return rules;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray array = root.optJSONArray("rules");
            if (array == null) return rules;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) rules.add(FilterRule.fromJson(obj));
            }
        } catch (Throwable ignored) {
            // 配置损坏就当没有规则
        }
        return rules;
    }

    public static String toJson(List<FilterRule> rules) {
        try {
            JSONObject root = new JSONObject();
            JSONArray array = new JSONArray();
            for (FilterRule rule : rules) array.put(rule.toJson());
            root.put("rules", array);
            return root.toString();
        } catch (Throwable t) {
            return "{\"rules\":[]}";
        }
    }

    public static boolean save(List<FilterRule> rules) {
        return RootShell.writeSettingString(KEY, toJson(rules));
    }
}
