package com.nexara.voicenotes;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite storage for notes. Replaces fragile notes.json.
 *
 * Atomic transactions, reliable writes, processing queue support.
 * JSON migration runs automatically on first launch.
 *
 * DB v2: added parent_id (thought chains) + thought_links table.
 */
public class NotesDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "potok_notes.db";
    private static final int DB_VERSION = 4;

    public static final String TABLE = "notes";
    public static final String TABLE_LINKS = "thought_links";

    // Existing fields (NotesStore API compatibility)
    public static final String COL_ID = "id";
    public static final String COL_TS = "ts";
    public static final String COL_DUR = "dur_sec";
    public static final String COL_SIZE = "size_bytes";
    public static final String COL_TEXT = "text";
    public static final String COL_STATUS = "status";        // sync: done/queued
    public static final String COL_TYPE = "type";
    public static final String COL_SUMMARY = "summary";
    public static final String COL_EVENT_TITLE = "event_title";
    public static final String COL_DUE_ISO = "due_iso";
    public static final String COL_REMIND_OFFSET = "remind_offset_min";
    public static final String COL_CAL_EVENT_ID = "calendar_event_id";
    public static final String COL_ITEMS = "items";           // JSON array [{t,d}]
    public static final String COL_RAW = "raw";               // raw LLM response

    // Processing queue fields
    public static final String COL_STATE = "state";           // pending/processing/done/failed
    public static final String COL_RETRY_COUNT = "retry_count";
    public static final String COL_LAST_ERROR = "last_error";
    public static final String COL_AUDIO_PATH = "audio_path";
    public static final String COL_TRANSCRIPT = "transcript";
    public static final String COL_UPDATED_AT = "updated_at";

    // parent_id for thought chains (Phase 2)
    public static final String COL_PARENT_ID = "parent_id";

    // Block model (v3): who wrote the block and what kind it is
    public static final String COL_AUTHOR = "author";   // me | llm
    public static final String COL_KIND = "kind";       // root | develop | chain | mine | questions | links | summary

    // Thought links table
    public static final String COL_LINK_FROM = "from_id";
    public static final String COL_LINK_TO = "to_id";
    public static final String COL_LINK_TYPE = "link_type"; // similar/develops/answers
    public static final String COL_LINK_CREATED = "created_at";
    public static final String COL_LINK_REASON = "reason"; // почему связано (avtolink/LLM)

    // Processing states
    public static final String STATE_PENDING = "pending";
    public static final String STATE_PROCESSING = "processing";
    public static final String STATE_DONE = "done";
    public static final String STATE_FAILED = "failed";

    /** Лимит попыток обработки; после него FAILED считается терминальной ошибкой. */
    public static final int MAX_RETRIES = 5;

    public NotesDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
            + COL_ID + " INTEGER PRIMARY KEY,"
            + COL_TS + " INTEGER NOT NULL DEFAULT 0,"
            + COL_DUR + " INTEGER DEFAULT 0,"
            + COL_SIZE + " INTEGER DEFAULT 0,"
            + COL_TEXT + " TEXT DEFAULT '',"
            + COL_STATUS + " TEXT DEFAULT 'done',"
            + COL_TYPE + " TEXT DEFAULT 'note',"
            + COL_SUMMARY + " TEXT DEFAULT '',"
            + COL_EVENT_TITLE + " TEXT DEFAULT '',"
            + COL_DUE_ISO + " TEXT,"
            + COL_REMIND_OFFSET + " INTEGER,"
            + COL_CAL_EVENT_ID + " INTEGER DEFAULT -1,"
            + COL_ITEMS + " TEXT,"
            + COL_RAW + " TEXT DEFAULT '',"
            + COL_STATE + " TEXT DEFAULT '" + STATE_DONE + "',"
            + COL_RETRY_COUNT + " INTEGER DEFAULT 0,"
            + COL_LAST_ERROR + " TEXT,"
            + COL_AUDIO_PATH + " TEXT,"
            + COL_TRANSCRIPT + " TEXT DEFAULT '',"
            + COL_UPDATED_AT + " INTEGER DEFAULT 0,"
            + COL_PARENT_ID + " INTEGER DEFAULT -1,"
            + COL_AUTHOR + " TEXT DEFAULT 'me',"
            + COL_KIND + " TEXT DEFAULT 'root'"
            + ")");
        db.execSQL("CREATE INDEX idx_notes_state ON " + TABLE + "(" + COL_STATE + ")");
        db.execSQL("CREATE INDEX idx_notes_ts ON " + TABLE + "(" + COL_TS + ")");
        db.execSQL("CREATE INDEX idx_notes_parent ON " + TABLE + "(" + COL_PARENT_ID + ")");

        db.execSQL("CREATE TABLE " + TABLE_LINKS + " ("
            + COL_LINK_FROM + " INTEGER NOT NULL,"
            + COL_LINK_TO + " INTEGER NOT NULL,"
            + COL_LINK_TYPE + " TEXT NOT NULL,"
            + COL_LINK_CREATED + " INTEGER NOT NULL,"
            + COL_LINK_REASON + " TEXT DEFAULT '',"
            + "PRIMARY KEY (" + COL_LINK_FROM + ", " + COL_LINK_TO + ", " + COL_LINK_TYPE + ")"
            + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_PARENT_ID + " INTEGER DEFAULT -1");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_parent ON " + TABLE + "(" + COL_PARENT_ID + ")");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_LINKS + " ("
                + COL_LINK_FROM + " INTEGER NOT NULL,"
                + COL_LINK_TO + " INTEGER NOT NULL,"
                + COL_LINK_TYPE + " TEXT NOT NULL,"
                + COL_LINK_CREATED + " INTEGER NOT NULL,"
                + "PRIMARY KEY (" + COL_LINK_FROM + ", " + COL_LINK_TO + ", " + COL_LINK_TYPE + ")"
                + ")");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_AUTHOR + " TEXT DEFAULT 'me'");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_KIND + " TEXT DEFAULT 'root'");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_LINKS + " ADD COLUMN " + COL_LINK_REASON + " TEXT DEFAULT ''");
        }
    }
}
