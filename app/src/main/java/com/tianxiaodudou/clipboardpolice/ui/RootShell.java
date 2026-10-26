package com.tianxiaodudou.clipboardpolice.ui;

import android.text.TextUtils;

import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.List;

/** 所有需要 root 的操作集中在此，避免散落 su 调用。 */
public final class RootShell {

    public static final String TMP_PRIMARY = "/data/local/tmp/clipboard_watch.tmp";
    public static final String TMP_FALLBACK = "/data/system/clipboard_watch.tmp";

    private static volatile boolean checked = false;
    private static volatile boolean granted = false;

    private RootShell() {
    }

    /** 只执行一次：反复 su 只会骚扰用户。 */
    public static synchronized boolean ensureRoot() {
        if (checked) return granted;
        checked = true;
        try {
            Shell.getShell();
            granted = Boolean.TRUE.equals(Shell.isAppGrantedRoot());
        } catch (Throwable t) {
            granted = false;
        }
        return granted;
    }

    public static boolean isGranted() {
        return granted;
    }

    /** 预创建临时文件并放开写权限，让 system_server 侧的 Hook 能追加。 */
    public static void prepareTmp() {
        run("touch " + TMP_PRIMARY + " 2>/dev/null; chmod 666 " + TMP_PRIMARY
                + " 2>/dev/null; touch " + TMP_FALLBACK + " 2>/dev/null; chmod 666 " + TMP_FALLBACK
                + " 2>/dev/null; true");
    }

    public static String readTmp(String path) {
        return run("cat " + path + " 2>/dev/null");
    }

    public static void clearTmp() {
        run(": > " + TMP_PRIMARY + " 2>/dev/null; chmod 666 " + TMP_PRIMARY
                + " 2>/dev/null; : > " + TMP_FALLBACK + " 2>/dev/null; chmod 666 " + TMP_FALLBACK
                + " 2>/dev/null; true");
    }

    public static boolean writeSetting(String key, String value) {
        String out = run("settings put global " + key + " " + value + "; echo OK_$?").trim();
        return out.endsWith("OK_0");
    }

    public static String readSetting(String key, String defValue) {
        String out = run("settings get global " + key);
        if (out.isEmpty()) return defValue;
        out = out.trim();
        if (out.isEmpty() || "null".equals(out)) return defValue;
        return out;
    }

    public static boolean copyToDownload(String srcPath, String dstPath) {
        String out = run("cp -f " + srcPath + " " + dstPath + "; chmod 644 " + dstPath
                + "; echo OK_$?").trim();
        return out.endsWith("OK_0");
    }

    private static String run(String command) {
        try {
            List<String> out = new ArrayList<>();
            Shell.Result result = Shell.cmd(command).to(out).exec();
            if (result.getCode() != 0) return "";
            return TextUtils.join("\n", out);
        } catch (Throwable t) {
            return "";
        }
    }
}
