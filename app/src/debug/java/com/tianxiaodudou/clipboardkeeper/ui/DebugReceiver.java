package com.tianxiaodudou.clipboardkeeper.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * adb 调试接口（仅 debug 构建）。
 * adb shell am broadcast -a com.tianxiaodudou.clipboardkeeper.DEBUG.REFRESH
 * 仅允许 shell(2000) 与 root(0)。
 */
public class DebugReceiver extends BroadcastReceiver {

    private static final String TAG = "ClipboardKeeper";
    private static final String PREFIX = "com.tianxiaodudou.clipboardkeeper.DEBUG.";
    public static final String ACTION_REFRESH = PREFIX + "REFRESH";
    public static final String ACTION_CLEAR = PREFIX + "CLEAR";
    public static final String ACTION_EXPORT = PREFIX + "EXPORT";

    private static final String EXPORT_PATH = "/sdcard/Download/clipboard_keeper_export.txt";

    @Override
    public void onReceive(Context context, Intent intent) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            Log.w(TAG, "DebugReceiver rejected uid=" + uid);
            return;
        }
        if (intent == null || intent.getAction() == null) return;

        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();
        final String action = intent.getAction();
        new Thread(() -> {
            try {
                switch (action) {
                    case ACTION_REFRESH:
                        Log.i(TAG, "debug refresh");
                        RootShell.ensureRoot();
                        ClipSync.refresh(appContext, (ok, message, inserted, records) ->
                                Log.i(TAG, "debug refresh ok=" + ok + " inserted=" + inserted
                                        + " message=" + message));
                        break;
                    case ACTION_CLEAR:
                        Log.i(TAG, "debug clear");
                        ClipDatabase.get(appContext).clearAll();
                        RootShell.ensureRoot();
                        RootShell.clearTmp();
                        break;
                    case ACTION_EXPORT:
                        Log.i(TAG, "debug export");
                        export(appContext);
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                Log.e(TAG, "debug action failed", t);
            } finally {
                pendingResult.finish();
            }
        }, "debug-receiver").start();
    }

    private void export(Context context) {
        List<ClipRecord> records = ClipDatabase.get(context).queryRecent(50000);
        StringBuilder sb = new StringBuilder();
        for (ClipRecord record : records) {
            sb.append(record.id).append('\t')
                    .append(record.timestamp).append('\t')
                    .append(record.operation).append('\t')
                    .append(record.sourcePackage).append('\t')
                    .append(record.content.replace("\n", "\\n")).append('\n');
        }
        File cacheFile = new File(context.getCacheDir(), "clipboard_keeper_export.txt");
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.e(TAG, "write export failed", t);
            return;
        }
        RootShell.ensureRoot();
        boolean ok = RootShell.copyToDownload(cacheFile.getAbsolutePath(), EXPORT_PATH);
        Log.i(TAG, "debug export ok=" + ok + " path=" + EXPORT_PATH);
    }
}
