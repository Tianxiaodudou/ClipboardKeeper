package com.tianxiaodudou.clipboardpolice.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

/** 检查更新：对比本地与线上版本，区分网络问题与仓库问题。 */
public final class UpdateChecker {

    /** 线上与本地一致 */
    public static final int STATE_SAME = 0;
    /** 线上有更高版本 */
    public static final int STATE_NEWER = 1;
    /** 本地比线上还高（测试版/未发布） */
    public static final int STATE_LOCAL_AHEAD = 2;
    /** 检查失败 */
    public static final int STATE_ERROR = 3;

    /** 网络不可用 / 超时 / DNS / SSL */
    public static final int ERROR_NETWORK = 0;
    /** 仓库侧问题：404（私有或没有 Release）、401/403、5xx */
    public static final int ERROR_REPO = 1;
    /** 其它未知错误 */
    public static final int ERROR_UNKNOWN = 2;

    private static final String REPO = "Tianxiaodudou/ClipboardPolice";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "ClipboardPolice-UpdateChecker";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final class Result {
        public int state = STATE_ERROR;
        public int errorKind = ERROR_UNKNOWN;
        public String localVersion = "";
        public String remoteVersion = "";
        public String apkUrl = "";
        public String changelog = "";
        public String message = "";
    }

    public interface Callback {
        void onResult(Result result);
    }

    private UpdateChecker() {
    }

    public static String currentVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void check(Context context, Callback callback) {
        Result result = new Result();
        result.localVersion = currentVersion(context);
        new Thread(() -> {
            try {
                JSONObject release = fetch(new URL(API));
                String tag = normalize(release.optString("tag_name", ""));
                String body = release.optString("body", "");
                String apkUrl = pickApkUrl(release.optJSONArray("assets"));
                if (tag.isEmpty()) {
                    result.state = STATE_ERROR;
                    result.errorKind = ERROR_REPO;
                    result.message = "Release 缺少版本号";
                } else {
                    result.remoteVersion = tag;
                    int cmp = compare(tag, result.localVersion);
                    if (cmp > 0) {
                        if (apkUrl.isEmpty()) {
                            result.state = STATE_ERROR;
                            result.errorKind = ERROR_REPO;
                            result.message = "Release 中没有 APK 附件";
                        } else {
                            result.state = STATE_NEWER;
                            result.apkUrl = apkUrl;
                            result.changelog = ChangelogFetcher.fetch(tag, body);
                        }
                    } else if (cmp == 0) {
                        result.state = STATE_SAME;
                    } else {
                        result.state = STATE_LOCAL_AHEAD;
                    }
                }
            } catch (Throwable t) {
                result.state = STATE_ERROR;
                result.errorKind = classify(t);
                result.message = describe(t);
            }
            if (callback != null) {
                Result finalResult = result;
                MAIN.post(() -> callback.onResult(finalResult));
            }
        }, "update-check").start();
    }

    private static String pickApkUrl(JSONArray assets) {
        if (assets == null) return "";
        String fallback = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (!name.endsWith(".apk")) continue;
            String url = asset.optString("browser_download_url", "");
            if (name.contains("ClipboardPolice")) return url;
            if (fallback.isEmpty()) fallback = url;
        }
        return fallback;
    }

    /** 区分「网络问题」与「仓库问题」 */
    static int classify(Throwable throwable) {
        Throwable cause = throwable;
        int depth = 0;
        while (cause != null && depth++ < 8) {
            if (cause instanceof RepoException) return ERROR_REPO;
            if (cause instanceof UnknownHostException
                    || cause instanceof ConnectException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException
                    || cause instanceof NoRouteToHostException
                    || cause instanceof SSLException) {
                return ERROR_NETWORK;
            }
            cause = cause.getCause();
        }
        if (throwable instanceof IOException) return ERROR_NETWORK;
        return ERROR_UNKNOWN;
    }

    static String describe(Throwable throwable) {
        if (throwable instanceof UnknownHostException) return "无法解析域名（DNS）";
        if (throwable instanceof SocketTimeoutException) return "连接超时";
        if (throwable instanceof ConnectException) return "无法建立连接";
        if (throwable instanceof SSLException) return "TLS 握手失败";
        if (throwable instanceof NoRouteToHostException) return "无路由到主机";
        if (throwable instanceof SocketException) return "网络中断";
        String message = throwable.getMessage();
        return message == null || message.isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static JSONObject fetch(URL url) throws IOException, RepoException, org.json.JSONException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code != 200) {
                String hint;
                switch (code) {
                    case 404:
                        hint = "仓库不存在、为私有或暂无 Release";
                        break;
                    case 401:
                    case 403:
                        hint = "访问被拒（限流或私有仓库）";
                        break;
                    default:
                        hint = code >= 500 ? "GitHub 服务端异常" : "仓库接口返回异常";
                }
                throw new RepoException("HTTP " + code + "（" + hint + "）");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            connection.disconnect();
        }
    }

    private static String normalize(String tag) {
        String value = tag == null ? "" : tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value;
    }

    /** 只比较 a.b.c 三段数字；本地与线上一致返回 0 */
    public static int compare(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i] ? 1 : -1;
        }
        return 0;
    }

    private static int[] parse(String version) {
        int[] parts = new int[]{0, 0, 0};
        if (version == null) return parts;
        String[] split = version.split("\\.");
        for (int i = 0; i < parts.length && i < split.length; i++) {
            try {
                parts[i] = Integer.parseInt(split[i].trim());
            } catch (Throwable t) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    /** 仓库侧异常（HTTP 非 200 等），与网络异常区分开 */
    static final class RepoException extends Exception {
        RepoException(String message) {
            super(message);
        }
    }
}
