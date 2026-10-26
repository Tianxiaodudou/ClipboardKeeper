package com.tianxiaodudou.clipboardpolice.hook;

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

    private static final String TAG = "ClipboardPolice";

    /** 模块包名（字符串常量，禁止 import 模块类）。 */
    public static final String MODULE_PKG = "com.tianxiaodudou.clipboardpolice";
    private static final String ACTION_UPDATED = MODULE_PKG + ".CLIP_UPDATED";

    /** Hook 侧临时缓冲文件：优先 /data/local/tmp，不可写时退回 /data/system。 */
    private static final String PRIMARY_TMP = "/data/local/tmp/clipboard_watch.tmp";
    private static final String FALLBACK_TMP = "/data/system/clipboard_watch.tmp";
    private static final int MAX_LINES = 200;
    private static final long MAX_BYTES = 200L * 1024L;

    private static final String KEY_WRITE_THROTTLE = "clipboard_watch_broadcast_throttle_ms";
    private static final String KEY_READ_ENABLED = "clipboard_watch_read_enabled";
    private static final String KEY_READ_THROTTLE = "clipboard_watch_read_throttle_ms";

    private static final long WRITE_DEDUP_MS = 200L;

    private final Map<String, Long> broadcastStamp = new HashMap<>();
    private final Map<String, Long> readStamp = new HashMap<>();
    private final Map<String, Long> writeDedup = new HashMap<>();

    private boolean installed;
    private Context systemContext;
    private File logFile;
    private int logLines = -1;

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

    private synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        installed = true;
        try {
            Class<?> service = Class.forName("com.android.server.clipboard.ClipboardService", false, cl);
            int writes = 0;
            int reads = 0;
            for (Method m : service.getDeclaredMethods()) {
                String name = m.getName();
                if (name.contains("setPrimaryClip")) {
                    if (hookWrite(m)) writes++;
                } else if ("getPrimaryClip".equals(name)) {
                    if (hookRead(m)) reads++;
                }
            }
            log(Log.INFO, TAG, "hooks installed: write=" + writes + ", read=" + reads);
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
                        try {
                            onWrite(chain);
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "write hook error: " + safeName(chain), t);
                        }
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

    private void onWrite(XposedInterface.Chain chain) {
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
        if (clip == null) return;
        if (pkg == null || pkg.isEmpty()) pkg = "unknown";

        String content = clipToText(clip);
        // 内层实现（setPrimaryClipNoLock 等）会被同样匹配到，按短窗口去重，避免重复记录
        if (!throttle(writeDedup, pkg + "#" + content.hashCode(), WRITE_DEDUP_MS)) return;

        appendRecord("write", pkg, content);

        long window = setting(KEY_WRITE_THROTTLE, 1000L);
        if (throttle(broadcastStamp, pkg + "|write", window)) {
            sendUpdated();
        }
    }

    // -------------------------------------------------------------- 读取监测

    private void onRead(XposedInterface.Chain chain, Object result) {
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

        ClipData clip = result instanceof ClipData ? (ClipData) result : null;
        appendRecord("read", pkg, clipToText(clip));
        sendUpdated();
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
            if (logLines < 0) logLines = countLines(file);
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
            // 下次重新解析路径，允许主路径恢复后可写
            logFile = null;
            logLines = -1;
            log(Log.ERROR, TAG, "append temp log failed", t);
        }
    }

    private File resolveLogFile() {
        if (logFile != null && logFile.canWrite()) return logFile;
        File primary = new File(PRIMARY_TMP);
        if (writable(primary)) {
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
