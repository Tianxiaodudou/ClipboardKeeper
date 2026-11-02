package com.tianxiaodudou.clipboardkeeper.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tianxiaodudou.clipboardkeeper.R;

/**
 * 刷新流程：su 读临时文件 → 解析还原转义 → 批量插入 → 按规则裁剪 → 清空临时文件 → 查列表。
 * 全部在后台线程执行。
 */
public final class ClipSync {

    public interface Callback {
        void onFinished(boolean ok, String message, int inserted, List<ClipRecord> records);
    }

    /** 列表最多展示最近 500 条 */
    private static final String TAG = "ClipboardKeeper";

    public static final int LIST_LIMIT = 500;
    public static final String KEY_MAX_RECORDS = "clipboard_watch_max_records";
    public static final int DEFAULT_MAX_RECORDS = 10000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-sync");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ClipSync() {
    }

    public static void refresh(Context context, Callback callback) {
        if (!RUNNING.compareAndSet(false, true)) {
            // message 为 null 表示"正在刷新中"，由界面自己决定怎么提示
            deliver(callback, false, null, 0, Collections.emptyList());
            return;
        }
        Context appContext = context.getApplicationContext();
        IO.execute(() -> {
            boolean ok = false;
            String message = "";
            int inserted = 0;
            List<ClipRecord> records = Collections.emptyList();
            try {
                if (!RootShell.ensureRoot()) {
                    message = appContext.getString(R.string.refresh_no_root);
                } else {
                    RootShell.prepareTmp();
                    List<ClipRecord> parsed = new ArrayList<>();
                    parsed.addAll(parse(RootShell.readTmp(RootShell.TMP_PRIMARY)));
                    parsed.addAll(parse(RootShell.readTmp(RootShell.TMP_FALLBACK)));
                    Collections.sort(parsed, (a, b) -> Long.compare(a.timestamp, b.timestamp));

                    ClipDatabase db = ClipDatabase.get(appContext);
                    if (!parsed.isEmpty()) {
                        inserted = db.insertAll(parsed);
                        db.onInserted(inserted);
                        db.maybeTrim(readMaxRecords());
                    }
                    RootShell.clearTmp();
                    records = db.queryRecent(LIST_LIMIT);
                    ok = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "refresh failed", t);
                message = appContext.getString(R.string.refresh_internal);
            } finally {
                RUNNING.set(false);
            }
            deliver(callback, ok, message, inserted, records);
        });
    }

    public static void clearAll(Context context, Runnable done) {
        Context appContext = context.getApplicationContext();
        IO.execute(() -> {
            try {
                ClipDatabase.get(appContext).clearAll();
                if (RootShell.ensureRoot()) RootShell.clearTmp();
            } catch (Throwable ignored) {
                // 忽略
            } finally {
                if (done != null) MAIN.post(done);
            }
        });
    }

    public static List<ClipRecord> loadRecent(Context context) {
        return ClipDatabase.get(context.getApplicationContext()).queryRecent(LIST_LIMIT);
    }

    public static int readMaxRecords() {
        String raw = RootShell.readSetting(KEY_MAX_RECORDS, String.valueOf(DEFAULT_MAX_RECORDS));
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return DEFAULT_MAX_RECORDS;
        }
    }

    private static void deliver(Callback callback, boolean ok, String message, int inserted,
                                List<ClipRecord> records) {
        if (callback == null) return;
        MAIN.post(() -> callback.onFinished(ok, message, inserted, records));
    }

    /** 解析临时文件中每一行：时间 \t 操作 \t 包名 \t 内容 */
    static List<ClipRecord> parse(String raw) {
        List<ClipRecord> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] cols = line.split("\t", 4);
            if (cols.length < 4) continue;
            long timestamp;
            try {
                timestamp = Long.parseLong(cols[0].trim());
            } catch (Throwable t) {
                continue;
            }
            String operation = cols[1].trim();
            if (!"write".equals(operation) && !"read".equals(operation)
                    && !"blocked".equals(operation)) continue;
            String pkg = cols[2].trim();
            if (pkg.isEmpty()) pkg = "unknown";
            ClipRecord record = new ClipRecord();
            record.timestamp = timestamp;
            record.operation = operation;
            record.sourcePackage = pkg;
            record.content = ClipDatabase.truncate(unescape(cols[3]));
            list.add(record);
        }
        return list;
    }

    /** 还原 \n \r \t 与反斜杠转义 */
    static String unescape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                default:
                    sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }
}
