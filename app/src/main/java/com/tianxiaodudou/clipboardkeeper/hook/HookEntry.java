package com.tianxiaodudou.clipboardkeeper.hook;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 注入 system_server 的入口：只做 Hook、临时日志与节流。
 *
 * <p>硬约束：本包不得引用 ui/ 包任何类、R 资源或 App SharedPreferences；
 * {@code io.github.libxposed.*} 只允许出现在 hook/ 包内。</p>
 */
public class HookEntry extends XposedModule {

    private static final String TAG = "ClipboardKeeper";

    /** 模块包名（字符串常量，禁止 import 模块类）。 */
    public static final String MODULE_PKG = "com.tianxiaodudou.clipboardkeeper";
    private static final String ACTION_UPDATED = MODULE_PKG + ".CLIP_UPDATED";

    /** Hook 侧临时缓冲文件：优先 /data/local/tmp，不可写时退回 /data/system。 */
    private static final String PRIMARY_TMP = "/data/local/tmp/clipboard_keeper.tmp";
    private static final String FALLBACK_TMP = "/data/system/clipboard_keeper.tmp";
    private static final int MAX_LINES = 200;
    private static final long MAX_BYTES = 200L * 1024L;

    private static final String KEY_WRITE_ENABLED = "clipboard_keeper_write_enabled";
    private static final String KEY_FILTER_RULES = "clipboard_keeper_filter_rules";
    private static final String KEY_WRITE_THROTTLE = "clipboard_keeper_broadcast_throttle_ms";
    private static final String KEY_READ_ENABLED = "clipboard_keeper_read_enabled";
    private static final String KEY_READ_THROTTLE = "clipboard_keeper_read_throttle_ms";

    private static final long WRITE_DEDUP_MS = 200L;

    private final Map<String, Long> broadcastStamp = new HashMap<>();
    private final Map<String, Long> readStamp = new HashMap<>();
    private final Map<String, Long> writeDedup = new HashMap<>();

    private boolean installed;
    private Context systemContext;
    private File logFile;
    private int logLines = -1;
    /** 主路径一旦写失败（多为 SELinux），后续固定用回退路径 */
    private boolean fallbackLocked;
    /** 过滤规则原始串 + 解析结果缓存，规则未变就不重新解析 */
    private volatile String filterRaw;
    private volatile FilterRuleSet filterRuleSet;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            install(param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onSystemServerStarting failed", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            if (!"android".equals(param.getPackageName())) return;
            install(param.getDefaultClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onPackageLoaded failed", t);
        }
    }

    // ------------------------------------------------------------------- Hook

    private static final String[] TARGET_CLASSES = {
            "com.android.server.clipboard.ClipboardService",
            // 真正声明 getPrimaryClip / setPrimaryClip 的 Binder 实现
            "com.android.server.clipboard.ClipboardService$ClipboardImpl",
    };

    private synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        installed = true;
        try {
            StringBuilder hooked = new StringBuilder();
            int writes = 0;
            int reads = 0;
            int classes = 0;
            for (String className : TARGET_CLASSES) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, false, cl);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "class not found: " + className);
                    continue;
                }
                for (Class<?> k = clazz; k != null && k != Object.class; k = k.getSuperclass()) {
                    classes++;
                    for (Method m : k.getDeclaredMethods()) {
                        String name = m.getName();
                        if (name.contains("setPrimaryClip")) {
                            if (hookWrite(m)) {
                                writes++;
                                hooked.append('\n').append("  W ").append(k.getSimpleName())
                                        .append('#').append(name);
                            }
                        } else if (name.contains("getPrimaryClip")) {
                            if (hookRead(m)) {
                                reads++;
                                hooked.append('\n').append("  R ").append(k.getSimpleName())
                                        .append('#').append(name);
                            }
                        }
                    }
                }
            }
            log(Log.INFO, TAG, "hooks installed: classes=" + classes + ", write=" + writes
                    + ", read=" + reads + hooked);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install hooks failed", t);
        }
    }

    private boolean hookWrite(Method m) {
        try {
            // 安装前 deoptimize，绕过内联
            deoptimize(m);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        boolean blocked = false;
                        try {
                            blocked = onWrite(chain);
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "write hook error: " + safeName(chain), t);
                            blocked = false;
                        }
                        if (blocked) return blockedValue(chain);
                        return chain.proceed();
                    });
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook write failed: " + m, t);
            return false;
        }
    }

    private boolean hookRead(Method m) {
        try {
            deoptimize(m);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            onRead(chain, result);
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "read hook error", t);
                        }
                        return result;
                    });
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook read failed: " + m, t);
            return false;
        }
    }

    // -------------------------------------------------------------- 写入监测

    /** 返回 true 表示被过滤规则拦截，调用方应短路（不把内容写进剪贴板） */
    private boolean onWrite(XposedInterface.Chain chain) {
        if (setting(KEY_WRITE_ENABLED, 1L) != 1L) return false;
        ClipData clip = null;
        String pkg = null;
        List<Object> args = chain.getArgs();
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof ClipData && clip == null) {
                clip = (ClipData) arg;
            } else if (arg instanceof String && pkg == null) {
                pkg = (String) arg;
            }
        }
        if (clip == null) return false;
        if (pkg == null || pkg.isEmpty()) pkg = "unknown";

        String content = clipToText(clip);
        // 内层实现（setPrimaryClipNoLock 等）会被同样匹配到，按短窗口去重，避免重复记录
        if (!throttle(writeDedup, pkg + "#" + content.hashCode(), WRITE_DEDUP_MS)) return false;

        String hit = matchFilter(content);
        if (hit != null) {
            appendRecord("blocked", pkg, content);
            long blockWindow = setting(KEY_WRITE_THROTTLE, 1000L);
            if (throttle(broadcastStamp, pkg + "|blocked", blockWindow)) {
                sendUpdated();
            }
            log(Log.INFO, TAG, "blocked by rule [" + hit + "] pkg=" + pkg);
            return true;
        }

        appendRecord("write", pkg, content);

        long window = setting(KEY_WRITE_THROTTLE, 1000L);
        if (throttle(broadcastStamp, pkg + "|write", window)) {
            sendUpdated();
        }
        return false;
    }

    // -------------------------------------------------------------- 读取监测

    private void onRead(XposedInterface.Chain chain, Object result) {
        // getPrimaryClipDescription / getPrimaryClipSource 之类不算读取
        if (!(result instanceof ClipData)) return;
        if (setting(KEY_READ_ENABLED, 0L) != 1L) return;

        String pkg = null;
        List<Object> args = chain.getArgs();
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof String) {
                pkg = (String) arg;
                break;
            }
        }
        if (pkg == null || pkg.isEmpty()) pkg = "unknown";

        long window = setting(KEY_READ_THROTTLE, 1000L);
        if (!throttle(readStamp, pkg, window)) return;

        appendRecord("read", pkg, clipToText((ClipData) result));
        sendUpdated();
    }

    // -------------------------------------------------------------- 过滤规则

    private String matchFilter(String content) {
        try {
            String raw = settingString(KEY_FILTER_RULES);
            if (raw == null || raw.trim().isEmpty()) return null;
            if (!raw.equals(filterRaw)) {
                filterRuleSet = FilterRuleSet.parse(raw);
                filterRaw = raw;
            }
            FilterRuleSet rules = filterRuleSet;
            return rules == null ? null : rules.match(content);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "filter match failed", t);
            return null;
        }
    }

    private String settingString(String key) {
        try {
            Context ctx = systemContext();
            if (ctx == null) return "";
            String value = Settings.Global.getString(ctx.getContentResolver(), key);
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 拦截写入时按被 Hook 方法的返回类型给出安全的短路值 */
    private static Object blockedValue(XposedInterface.Chain chain) {
        try {
            java.lang.reflect.Method method = (java.lang.reflect.Method) chain.getExecutable();
            Class<?> type = method.getReturnType();
            if (type == void.class) return null;
            if (type == boolean.class) return Boolean.FALSE;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 规则集合：与 App 侧 FilterRule 的 JSON 约定保持一致 */
    private static final class FilterRuleSet {
        private final Rule[] rules;

        private FilterRuleSet(Rule[] rules) {
            this.rules = rules;
        }

        String match(String content) {
            if (content == null || rules == null) return null;
            for (Rule rule : rules) {
                if (rule.matches(content)) return rule.name;
            }
            return null;
        }

        static FilterRuleSet parse(String raw) {
            try {
                org.json.JSONObject root = new org.json.JSONObject(raw);
                org.json.JSONArray array = root.optJSONArray("rules");
                if (array == null) return null;
                java.util.List<Rule> parsed = new java.util.ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject obj = array.optJSONObject(i);
                    if (obj == null) continue;
                    org.json.JSONArray conds = obj.optJSONArray("conditions");
                    if (conds == null) continue;
                    java.util.List<Condition> list = new java.util.ArrayList<>();
                    for (int j = 0; j < conds.length(); j++) {
                        org.json.JSONObject item = conds.optJSONObject(j);
                        if (item == null) continue;
                        String op = item.optString("op", "");
                        String value = item.optString("value", "");
                        if (op.isEmpty() || value.isEmpty()) continue;
                        list.add(new Condition(op, value));
                    }
                    if (list.isEmpty()) continue;
                    parsed.add(new Rule(obj.optString("name", "规则" + (i + 1)),
                            obj.optBoolean("enabled", true),
                            "OR".equalsIgnoreCase(obj.optString("logic", "AND")),
                            obj.optBoolean("negate", false),
                            list.toArray(new Condition[0])));
                }
                return parsed.isEmpty() ? null : new FilterRuleSet(parsed.toArray(new Rule[0]));
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final class Rule {
        final String name;
        final boolean enabled;
        final boolean any;
        final boolean negate;
        final Condition[] conditions;

        Rule(String name, boolean enabled, boolean any, boolean negate, Condition[] conditions) {
            this.name = name;
            this.enabled = enabled;
            this.any = any;
            this.negate = negate;
            this.conditions = conditions;
        }

        boolean matches(String text) {
            if (!enabled || conditions.length == 0) return false;
            boolean all = true;
            boolean some = false;
            for (Condition cond : conditions) {
                boolean hit = cond.matches(text);
                all &= hit;
                some |= hit;
            }
            boolean mid = any ? some : all;
            return negate ? !mid : mid;
        }
    }

    private static final class Condition {
        final String op;
        final String value;
        final java.util.regex.Pattern pattern;

        Condition(String op, String value) {
            this.op = op;
            this.value = value;
            java.util.regex.Pattern compiled = null;
            if ("regex".equals(op) || "not_regex".equals(op)) {
                try {
                    compiled = java.util.regex.Pattern.compile(value);
                } catch (Throwable t) {
                    compiled = null;
                }
            }
            this.pattern = compiled;
        }

        private static int asInt(String raw, int defValue) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (Throwable t) {
                return defValue;
            }
        }

        boolean matches(String text) {
            switch (op) {
                case "contains":
                    return text.contains(value);
                case "not_contains":
                    return !text.contains(value);
                case "starts_with":
                    return text.startsWith(value);
                case "not_starts_with":
                    return !text.startsWith(value);
                case "ends_with":
                    return text.endsWith(value);
                case "not_ends_with":
                    return !text.endsWith(value);
                case "equals":
                    return text.equals(value);
                case "not_equals":
                    return !text.equals(value);
                case "regex":
                    return pattern != null && pattern.matcher(text).find();
                case "not_regex":
                    return pattern != null && !pattern.matcher(text).find();
                case "char_at": {
                    // value = "位置|字符"，位置从 1 开始
                    String[] parts = value.split("\\|", 2);
                    if (parts.length < 2) return false;
                    int pos = asInt(parts[0], -1);
                    if (pos < 1) return false;
                    int start = pos - 1;
                    String expect = parts[1];
                    return start + expect.length() <= text.length() && text.startsWith(expect, start);
                }
                case "length_equals":
                    return text.length() == asInt(value, -1);
                case "length_greater":
                    return text.length() > asInt(value, Integer.MAX_VALUE);
                case "length_less":
                    return text.length() < asInt(value, Integer.MIN_VALUE);
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String clipToText(ClipData clip) {
        if (clip == null) return "";
        StringBuilder sb = new StringBuilder();
        int count = clip.getItemCount();
        for (int i = 0; i < count; i++) {
            ClipData.Item item;
            try {
                item = clip.getItemAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (item == null) continue;
            CharSequence text = null;
            Uri uri = null;
            try {
                text = item.getText();
            } catch (Throwable ignored) {
                // 忽略
            }
            if (text == null) {
                try {
                    uri = item.getUri();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (sb.length() > 0) sb.append('\n');
            if (text != null) {
                sb.append(text);
            } else if (uri != null) {
                sb.append(uri.toString());
            }
        }
        return sb.toString();
    }

    private static boolean throttle(Map<String, Long> stamps, String key, long windowMs) {
        long now = SystemClock.elapsedRealtime();
        if (windowMs <= 0L) {
            stamps.put(key, now);
            return true;
        }
        Long last = stamps.get(key);
        if (last != null && now - last < windowMs) return false;
        stamps.put(key, now);
        if (stamps.size() > 512) stamps.clear();
        return true;
    }

    private long setting(String key, long defValue) {
        try {
            Context ctx = systemContext();
            if (ctx == null) return defValue;
            return Settings.Global.getLong(ctx.getContentResolver(), key, defValue);
        } catch (Throwable t) {
            return defValue;
        }
    }

    private Context systemContext() {
        if (systemContext != null) return systemContext;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = null;
            try {
                thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (thread == null) {
                try {
                    thread = at.getDeclaredMethod("systemMain").invoke(null);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            Object ctx = null;
            if (thread != null) {
                try {
                    Method m = at.getDeclaredMethod("getSystemContext");
                    m.setAccessible(true);
                    ctx = m.invoke(java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : thread);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (ctx instanceof Context) {
                systemContext = (Context) ctx;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "system context unavailable", t);
        }
        return systemContext;
    }

    private void sendUpdated() {
        try {
            Context ctx = systemContext();
            if (ctx == null) return;
            Intent intent = new Intent(ACTION_UPDATED);
            intent.setPackage(MODULE_PKG);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcastAsUser(intent, android.os.Process.myUserHandle());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "send broadcast failed", t);
        }
    }

    private static String safeName(XposedInterface.Chain chain) {
        try {
            return String.valueOf(chain.getExecutable());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // -------------------------------------------------------------- 临时缓冲

    /** 追加一行：时间 \t 操作 \t 包名 \t 内容 */
    private void appendRecord(String operation, String pkg, String content) {
        try {
            File file = resolveLogFile();
            if (logLines < 0 || file.length() == 0L) logLines = 0;
            byte[] line = (System.currentTimeMillis() + "\t" + operation + "\t" + pkg + "\t"
                    + escape(content) + "\n").getBytes(StandardCharsets.UTF_8);
            boolean needReset = logLines >= MAX_LINES || file.length() + line.length > MAX_BYTES;
            if (needReset) {
                try (FileOutputStream fos = new FileOutputStream(file, false)) {
                    fos.write(new byte[0]);
                }
                logLines = 0;
            }
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(line);
            }
            logLines++;
        } catch (Throwable t) {
            // 主路径写失败就锁定回退路径，避免每条事件都重试
            fallbackLocked = true;
            logFile = null;
            logLines = -1;
            log(Log.ERROR, TAG, "append temp log failed", t);
        }
    }

    private File resolveLogFile() {
        if (logFile != null) return logFile;
        File primary = new File(PRIMARY_TMP);
        if (!fallbackLocked && writable(primary)) {
            logFile = primary;
            return primary;
        }
        File fallback = new File(FALLBACK_TMP);
        if (writable(fallback)) {
            logFile = fallback;
            log(Log.WARN, TAG, "primary temp log not writable, using " + FALLBACK_TMP);
            return fallback;
        }
        logFile = primary;
        return primary;
    }

    private static boolean writable(File file) {
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int countLines(File file) {
        if (!file.isFile() || file.length() == 0L) return 0;
        int lines = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                for (int i = 0; i < read; i++) {
                    if (buf[i] == '\n') lines++;
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        return lines;
    }

    private static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
