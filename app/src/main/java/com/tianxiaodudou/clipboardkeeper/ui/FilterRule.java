package com.tianxiaodudou.clipboardkeeper.ui;

import android.content.Context;

import com.tianxiaodudou.clipboardkeeper.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一条过滤规则；条件之间可选「与 / 或」，整体可反向（非）。 */
public class FilterRule {

    /** 与 Hook 侧约定的条件类型（JSON 里同名） */
    public static final String[] OP_VALUES = {
            "contains", "not_contains", "starts_with", "not_starts_with",
            "ends_with", "not_ends_with", "equals", "not_equals", "regex", "not_regex"
    };

    public String name = "";
    public boolean enabled = true;
    public boolean logicOr = false;
    public boolean negate = false;
    public final List<Cond> conditions = new ArrayList<>();

    public static class Cond {
        public String op = OP_VALUES[0];
        public String value = "";
    }

    public boolean empty() {
        return conditions.isEmpty();
    }

    /** 条件不全（含空值）就不参与匹配，避免误拦 */
    public boolean usable() {
        if (!enabled || conditions.isEmpty()) return false;
        for (Cond cond : conditions) {
            if (cond.value == null || cond.value.isEmpty()) return false;
        }
        return true;
    }

    public String summary(Context context) {
        String base = context.getString(
                logicOr ? R.string.filter_summary_any : R.string.filter_summary_all,
                conditions.size());
        return negate ? context.getString(R.string.filter_summary_negate, base) : base;
    }

    public JSONObject toJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("name", name);
        obj.put("enabled", enabled);
        obj.put("logic", logicOr ? "OR" : "AND");
        obj.put("negate", negate);
        JSONArray array = new JSONArray();
        for (Cond cond : conditions) {
            JSONObject item = new JSONObject();
            item.put("op", cond.op);
            item.put("value", cond.value);
            array.put(item);
        }
        obj.put("conditions", array);
        return obj;
    }

    public static FilterRule fromJson(JSONObject obj) {
        FilterRule rule = new FilterRule();
        rule.name = obj.optString("name", "");
        rule.enabled = obj.optBoolean("enabled", true);
        rule.logicOr = "OR".equalsIgnoreCase(obj.optString("logic", "AND"));
        rule.negate = obj.optBoolean("negate", false);
        JSONArray array = obj.optJSONArray("conditions");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Cond cond = new Cond();
                cond.op = item.optString("op", OP_VALUES[0]);
                cond.value = item.optString("value", "");
                rule.conditions.add(cond);
            }
        }
        return rule;
    }
}
