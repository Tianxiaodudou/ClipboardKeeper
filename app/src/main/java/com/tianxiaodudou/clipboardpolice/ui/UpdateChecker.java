package com.tianxiaodudou.clipboardpolice.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 查 GitHub Releases 最新版；比当前 versionName 新则用 DownloadManager 下载（不自动安装）。 */
public final class UpdateChecker {

    private static final String REPO = "Tianxiaodudou/ClipboardPolice";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "ClipboardPolice-UpdateChecker";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(boolean ok, String version, String apkUrl, String message);
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
        String current = currentVersion(context);
        new Thread(() -> {
            boolean ok = false;
            String version = "";
            String apkUrl = "";
            String message = "";
            try {
                JSONObject release = fetch(new URL(API));
                version = normalize(release.optString("tag_name", ""));
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            if (name.contains("ClipboardPolice")) break;
                        }
                    }
                }
                if (version.isEmpty()) {
                    message = "Release 缺少 tag_name";
                } else if (compare(version, current) > 0) {
                    if (apkUrl.isEmpty()) {
                        message = "Release 中没有 APK 附件";
                    } else {
                        ok = true;
                    }
                } else {
                    message = "";
                    ok = true;
                    version = current;
                }
            } catch (Throwable t) {
                message = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            boolean finalOk = ok;
            String finalVersion = version;
            String finalUrl = apkUrl;
            String finalMessage = message;
            if (callback != null) {
                MAIN.post(() -> callback.onResult(finalOk, finalVersion, finalUrl, finalMessage));
            }
        }, "update-check").start();
    }

    public static void download(Context context, String version, String apkUrl) {
        String fileName = (version.isEmpty() ? "latest" : version) + "-ClipboardPolice.apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle(fileName);
        request.setDescription("剪贴板警察新版本下载");
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        request.setMimeType("application/vnd.android.package-archive");
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) manager.enqueue(request);
    }

    private static JSONObject fetch(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("GitHub API 返回 " + code
                        + (code == 404 ? "（仓库可能为私有或暂无 Release）" : ""));
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

    /** 只比较 a.b.c 三段数字。 */
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
}
