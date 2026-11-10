package dev.echo.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Echo 的 SQLite 数据库。Store 从 JSON 切换到这里，API 不变。 */
public final class EchoDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "echo.db";
    private static final int DB_VERSION = 1;
    private static EchoDb instance;

    public static synchronized EchoDb get(Context context) {
        if (instance == null) instance = new EchoDb(context.getApplicationContext());
        return instance;
    }

    private EchoDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session(id TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER)");
        db.execSQL("CREATE TABLE segment(id TEXT PRIMARY KEY, session_id TEXT, path TEXT,"
                + " started_at INTEGER, duration_ms INTEGER, transcript TEXT DEFAULT '',"
                + " state TEXT DEFAULT 'pending', speaker TEXT DEFAULT '',"
                + " attempts INTEGER DEFAULT 0, last_failed_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_seg_pending ON segment(state, started_at)");
        db.execSQL("CREATE TABLE todo(id TEXT PRIMARY KEY, task TEXT UNIQUE, time_text TEXT DEFAULT '',"
                + " due_at INTEGER DEFAULT 0, priority TEXT DEFAULT 'normal', who TEXT DEFAULT '',"
                + " evidence TEXT DEFAULT '', done INTEGER DEFAULT 0, escalated_at INTEGER DEFAULT 0,"
                + " source_session_id TEXT DEFAULT '', created_at INTEGER)");
        db.execSQL("CREATE INDEX idx_todo_due ON todo(done, due_at)");
        db.execSQL("CREATE TABLE memory(id TEXT PRIMARY KEY, kind TEXT DEFAULT 'fact',"
                + " subject TEXT, value TEXT, confidence REAL DEFAULT 0.8, evidence TEXT DEFAULT '',"
                + " embedding TEXT, status TEXT DEFAULT 'active', superseded_by TEXT,"
                + " source_session_id TEXT DEFAULT '', created_at INTEGER, last_confirmed_at INTEGER,"
                + " review_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_mem_status ON memory(status, kind)");
        db.execSQL("CREATE TABLE activity(id TEXT PRIMARY KEY, start_time INTEGER, end_time INTEGER,"
                + " title TEXT DEFAULT '', summary TEXT DEFAULT '', transcript TEXT DEFAULT '',"
                + " todo_count INTEGER DEFAULT 0, memory_count INTEGER DEFAULT 0,"
                + " session_id TEXT DEFAULT '', created_at INTEGER)");
        db.execSQL("CREATE TABLE digest(date_key TEXT PRIMARY KEY, content TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 后续 schema 变更在这里处理
    }
}
