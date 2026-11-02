package com.tianxiaodudou.clipboardpolice.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.tianxiaodudou.clipboardpolice.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 更新弹窗：显示更新日志 + 内置下载器进度，下载完成直接拉起安装。 */
public final class UpdateDialog {

    private final Activity activity;
    private final String version;
    private final String apkUrl;
    private final String changelog;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private ProgressBar progressBar;
    private TextView progressText;

    private UpdateDialog(Activity activity, String version, String apkUrl, String changelog) {
        this.activity = activity;
        this.version = version;
        this.apkUrl = apkUrl;
        this.changelog = changelog;
    }

    public static void show(Activity activity, String version, String apkUrl, String changelog) {
        new UpdateDialog(activity, version, apkUrl, changelog).show();
    }

    private void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null);
        TextView tvVersion = view.findViewById(R.id.tvVersion);
        TextView tvChangelog = view.findViewById(R.id.tvChangelog);
        progressBar = view.findViewById(R.id.progressBar);
        progressText = view.findViewById(R.id.tvProgress);

        String current = UpdateChecker.currentVersion(activity);
        tvVersion.setText(activity.getString(R.string.update_found, version, current));
        String text = changelog == null ? "" : changelog.trim();
        tvChangelog.setText(text.isEmpty() ? activity.getString(R.string.update_no_changelog) : text);

        dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_title) + " · " + activity.getString(R.string.update_changelog))
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update_download, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> startDownload());
        });
        dialog.show();
    }

    private void startDownload() {
        if (dialog != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressText.setVisibility(View.VISIBLE);
        progressText.setText(activity.getString(R.string.update_downloading, 0));
        new Thread(this::download, "update-download").start();
    }

    private void download() {
        File dir = new File(activity.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            fail(activity.getString(R.string.refresh_internal));
            return;
        }
        File[] olds = dir.listFiles();
        if (olds != null) {
            for (File old : olds) {
                // 清掉上一版下载的安装包
                if (old.isFile()) old.delete();
            }
        }
        File target = new File(dir, version + "-ClipboardKeeper.apk");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(apkUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "ClipboardKeeper-Updater");
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            long total = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long read = 0;
                int len;
                int lastPercent = -1;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                    read += len;
                    if (total > 0) {
                        int percent = (int) (read * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            publishProgress(percent);
                        }
                    }
                }
            }
            mainHandler.post(() -> {
                publishProgress(100);
                if (dialog != null) dialog.dismiss();
                Toast.makeText(activity, R.string.update_installing, Toast.LENGTH_SHORT).show();
                install(target);
            });
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void publishProgress(int percent) {
        mainHandler.post(() -> {
            progressBar.setProgress(percent);
            progressText.setText(activity.getString(R.string.update_downloading, percent));
        });
    }

    private void fail(String reason) {
        mainHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            progressText.setVisibility(View.VISIBLE);
            progressText.setText(activity.getString(R.string.update_download_failed, reason));
            if (dialog != null) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }
        });
    }

    private void install(File apk) {
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }
}
