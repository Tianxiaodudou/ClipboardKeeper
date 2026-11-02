package com.tianxiaodudou.clipboardkeeper.ui;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 持久化数据库：/data/data/&lt;pkg&gt;/databases/clipboard_keeper.db */
public class ClipDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "clipboard_keeper.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "clipboard_records";

    /** 单条内容上限 100KB */
    public static final int MAX_CONTENT_BYTES = 100 * 1024;
    /** 裁剪触发：每插入 100 条 */
    public static final int TRIM_INSERT_THRESHOLD = 100;
    /** 裁剪触发：每 5 分钟 */
    public static final long TRIM_INTERVAL_MS = 5 * 60 * 1000L;

    private static final String SQL_CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "timestamp INTEGER NOT NULL,"
            + "operation TEXT NOT NULL,"
            + "source_package TEXT NOT NULL,"
            + "content TEXT NOT NULL,"
            + "created_at TEXT NOT NULL)";
    private static final String SQL_CREATE_INDEX_TIMESTAMP =
            "CREATE INDEX IF NOT EXISTS idx_timestamp ON " + TABLE + "(timestamp DESC)";
    private static final String SQL_CREATE_INDEX_OP_PKG =
            "CREATE INDEX IF NOT EXISTS idx_op_pkg ON " + TABLE + "(operation, source_package)";

    private static final SimpleDateFormat CREATED_AT_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static volatile ClipDatabase instance;

    private int insertedSinceTrim = 0;
    private long lastTrimAt = 0L;

    public static ClipDatabase get(Context context) {
        if (instance == null) {
            synchronized (ClipDatabase.class) {
                if (instance == null) {
                    instance = new ClipDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ClipDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_TABLE);
        db.execSQL(SQL_CREATE_INDEX_TIMESTAMP);
        db.execSQL(SQL_CREATE_INDEX_OP_PKG);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 仅 v1，暂无升级路径
    }

    /** 批量插入，返回实际插入条数。 */
    public int insertAll(List<ClipRecord> records) {
        if (records == null || records.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            String createdAt = CREATED_AT_FORMAT.format(new Date(System.currentTimeMillis()));
            for (ClipRecord record : records) {
                ContentValues values = new ContentValues();
                values.put("timestamp", record.timestamp);
                values.put("operation", record.operation);
                values.put("source_package", record.sourcePackage);
                values.put("content", truncate(record.content));
                values.put("created_at", record.createdAt == null || record.createdAt.isEmpty()
                        ? createdAt : record.createdAt);
                db.insert(TABLE, null, values);
                inserted++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    public void onInserted(int inserted) {
        if (inserted > 0) insertedSinceTrim += inserted;
    }

    /** 裁剪：仅超上限时才删，触发节奏为每 100 条或每 5 分钟。 */
    public void maybeTrim(int maxRecords) {
        int max = Math.max(1000, maxRecords);
        long now = System.currentTimeMillis();
        boolean byCount = insertedSinceTrim >= TRIM_INSERT_THRESHOLD;
        boolean byTime = insertedSinceTrim > 0 && now - lastTrimAt >= TRIM_INTERVAL_MS;
        if (!byCount && !byTime) return;
        if (count() <= max) return;
        trimTo(max);
        insertedSinceTrim = 0;
        lastTrimAt = now;
    }

    public void trimTo(int maxRecords) {
        int max = Math.max(1000, maxRecords);
        if (count() <= max) return;
        getWritableDatabase().execSQL(
                "DELETE FROM " + TABLE + " WHERE id NOT IN "
                        + "(SELECT id FROM " + TABLE + " ORDER BY timestamp DESC LIMIT ?)",
                new Object[]{max});
    }

    public List<ClipRecord> queryRecent(int limit) {
        List<ClipRecord> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, timestamp, operation, source_package, content, created_at FROM " + TABLE
                        + " ORDER BY timestamp DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))})) {
            while (cursor.moveToNext()) {
                ClipRecord record = new ClipRecord();
                record.id = cursor.getLong(0);
                record.timestamp = cursor.getLong(1);
                record.operation = cursor.getString(2);
                record.sourcePackage = cursor.getString(3);
                record.content = cursor.getString(4);
                record.createdAt = cursor.getString(5);
                list.add(record);
            }
        }
        return list;
    }

    public int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE, null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }

    public void clearAll() {
        getWritableDatabase().delete(TABLE, null, null);
        insertedSinceTrim = 0;
        lastTrimAt = System.currentTimeMillis();
    }

    /** 内容超过 100KB 时截断并加标记。 */
    public static String truncate(String content) {
        if (content == null) return "";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_CONTENT_BYTES) return content;
        int end = MAX_CONTENT_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + "\n[内容超过 100KB，已截断]";
    }
}
