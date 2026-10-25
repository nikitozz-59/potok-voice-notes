package com.nexara.voicenotes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище заметок на SQLite. Заменяет хрупкий notes.json.
 *
 * Сохраняет полный API старой версии (list/addOrUpdate/delete/Note)
 * для обратной совместимости. Добавляет методы очереди обработки:
 * - getByState() — заметки в определённом состоянии
 * - updateState() — сменить состояние
 * - getPending() — заметки для ретрая (state=failed или pending)
 * - markDone/markFailed — результат обработки
 *
 * Миграция из notes.json выполняется один раз при первом создании БД.
 */
public class NotesStore {
    private final NotesDbHelper dbHelper;
    private final Context ctx;

    public NotesStore(Context ctx) {
        this.ctx = ctx;
        this.dbHelper = new NotesDbHelper(ctx);
        migrateFromJsonIfNeeded();
    }

    // === Модель ===

    public static class Note {
        public long id;
        public long ts;
        public int durSec;
        public int sizeBytes;
        public String text;
        public String status;
        public String type = "note";
        public String summary = "";
        public String eventTitle = "";
        public String dueIso = null;
        public Integer remindOffsetMin = null;
        public long calendarEventId = -1;
        public List<CheckItem> items = null;
        public String raw = ""; // raw LLM response (debug)
        public long parentId = -1; // parent note for thought chains
        public String author = "me";   // me | llm (block model v3)
        public String kind = "root";   // root | develop | chain | mine | questions | links | summary

        /**
         * Флаг is_event — заметка является событием с конкретным временем.
         * Вычисляемый: true если задан dueIso. Не хранится отдельной колонкой —
         * дублировало бы (dueIso != null). Используется UI (чип времени,
         * иконка) и будет основой для экспорта в Keep (события с временем →
         * напоминания Keep с датой; без — простые заметки).
         */
        public boolean isEvent() {
            return dueIso != null && !dueIso.isEmpty();
        }

        // Processing queue fields
        public String state = NotesDbHelper.STATE_DONE;
        public int retryCount = 0;
        public String lastError = null;
        public String audioPath = null;
        public String transcript = "";
        public long updatedAt = 0;
        /** Виртуальная системная заметка (настройки) — не хранится в БД. */
        public boolean system = false;
    }

    // === Основной API (обратная совместимость) ===

    public List<Note> list() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(NotesDbHelper.TABLE, null, null, null, null, null,
            NotesDbHelper.COL_TS + " DESC");
        ArrayList<Note> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                out.add(cursorToNote(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Note getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(NotesDbHelper.TABLE, null,
            NotesDbHelper.COL_ID + "=?", new String[]{String.valueOf(id)},
            null, null, null);
        try {
            if (c.moveToFirst()) return cursorToNote(c);
            return null;
        } finally {
            c.close();
        }
    }

    public void addOrUpdate(Note n) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = noteToCv(n);
        db.insertWithOnConflict(NotesDbHelper.TABLE, null, cv,
            SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(NotesDbHelper.TABLE, NotesDbHelper.COL_ID + "=?",
            new String[]{String.valueOf(id)});
    }

    // === API очереди обработки ===

    /**
     * Заметки в указанном состоянии, отсортированные по времени (старые первыми).
     */
    public List<Note> getByState(String state) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(NotesDbHelper.TABLE, null,
            NotesDbHelper.COL_STATE + "=?", new String[]{state},
            null, null, NotesDbHelper.COL_TS + " ASC");
        ArrayList<Note> out = new ArrayList<>();
        try {
            while (c.moveToNext()) out.add(cursorToNote(c));
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Обновить только состояние и связанные поля (без перезаписи остального).
     */
    public void updateState(long id, String state, String error, int retryCount) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(NotesDbHelper.COL_STATE, state);
        cv.put(NotesDbHelper.COL_LAST_ERROR, error);
        cv.put(NotesDbHelper.COL_RETRY_COUNT, retryCount);
        cv.put(NotesDbHelper.COL_UPDATED_AT, System.currentTimeMillis());
        db.update(NotesDbHelper.TABLE, cv, NotesDbHelper.COL_ID + "=?",
            new String[]{String.valueOf(id)});
    }

    /**
     * Обновить транскрипт (после STT).
     */
    public void updateTranscript(long id, String transcript) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(NotesDbHelper.COL_TRANSCRIPT, transcript);
        cv.put(NotesDbHelper.COL_UPDATED_AT, System.currentTimeMillis());
        db.update(NotesDbHelper.TABLE, cv, NotesDbHelper.COL_ID + "=?",
            new String[]{String.valueOf(id)});
    }

    /**
     * Обновить путь к аудиофайлу.
     */
    public void updateAudioPath(long id, String path) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(NotesDbHelper.COL_AUDIO_PATH, path);
        cv.put(NotesDbHelper.COL_UPDATED_AT, System.currentTimeMillis());
        db.update(NotesDbHelper.TABLE, cv, NotesDbHelper.COL_ID + "=?",
            new String[]{String.valueOf(id)});
    }

    /**
     * Отметить как полностью обработанную.
     */
    public void markDone(long id) {
        updateState(id, NotesDbHelper.STATE_DONE, null, 0);
        // Очистить путь к аудио — больше не нужен
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.putNull(NotesDbHelper.COL_AUDIO_PATH);
        cv.put(NotesDbHelper.COL_UPDATED_AT, System.currentTimeMillis());
        db.update(NotesDbHelper.TABLE, cv, NotesDbHelper.COL_ID + "=?",
            new String[]{String.valueOf(id)});
    }

    /**
     * Отметить как неудачную с ошибкой и инкрементом retry.
     */
    public void markFailed(long id, String error) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // Сначала читаем текущий retry_count
        Cursor c = db.query(NotesDbHelper.TABLE,
            new String[]{NotesDbHelper.COL_RETRY_COUNT},
            NotesDbHelper.COL_ID + "=?", new String[]{String.valueOf(id)},
            null, null, null);
        int retry = 0;
        try {
            if (c.moveToFirst()) retry = c.getInt(0);
        } finally {
            c.close();
        }
        updateState(id, NotesDbHelper.STATE_FAILED, error, retry + 1);
    }

    /**
     * Сбросить failed -> pending для retry.
     */
    public void resetToPending(long id) {
        updateState(id, NotesDbHelper.STATE_PENDING, null, 0);
    }

    // === Thought chains & links (Phase 2) ===

    /**
     * Дочерние заметки (развитья/ответы) для указанной родительской.
     */
    public List<Note> getChildren(long parentId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(NotesDbHelper.TABLE, null,
            NotesDbHelper.COL_PARENT_ID + "=?", new String[]{String.valueOf(parentId)},
            null, null, NotesDbHelper.COL_TS + " ASC");
        ArrayList<Note> out = new ArrayList<>();
        try {
            while (c.moveToNext()) out.add(cursorToNote(c));
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Создать дочернюю заметку (развитьё/ответ) с parent_id.
     */
    public long createChildNote(long parentId, String text, String summary, String type) {
        return createChildNote(parentId, text, summary, type, "llm", "develop");
    }

    /**
     * Создать дочерний блок с указанием автора и вида.
     * @param author "me" | "llm"
     * @param kind   "develop" | "chain" | "mine" | "questions" | "links" | "summary"
     */
    public long createChildNote(long parentId, String text, String summary, String type,
                                String author, String kind) {
        Note n = new Note();
        n.id = System.currentTimeMillis();
        n.ts = n.id;
        n.text = text;
        n.transcript = text;
        n.summary = summary;
        n.type = type;
        n.status = "done";
        n.parentId = parentId;
        n.author = author;
        n.kind = kind;
        n.state = NotesDbHelper.STATE_DONE;
        addOrUpdate(n);
        return n.id;
    }

    /**
     * Статистика развитости: для каждой заметки [число детей, число связей].
     * Используется главным экраном для двух весов строк.
     */
    public java.util.HashMap<Long, int[]> getDevelopedStats() {
        java.util.HashMap<Long, int[]> out = new java.util.HashMap<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(NotesDbHelper.TABLE,
            new String[]{NotesDbHelper.COL_PARENT_ID, "COUNT(*)"},
            NotesDbHelper.COL_PARENT_ID + ">0", null,
            NotesDbHelper.COL_PARENT_ID, null, null);
        try {
            while (c.moveToNext()) {
                long pid = c.getLong(0);
                int[] v = out.get(pid);
                if (v == null) { v = new int[2]; out.put(pid, v); }
                v[0] = c.getInt(1);
            }
        } finally {
            c.close();
        }
        // Связи: считаем и исходящие, и входящие
        Cursor l = db.query(NotesDbHelper.TABLE_LINKS,
            new String[]{NotesDbHelper.COL_LINK_FROM, NotesDbHelper.COL_LINK_TO},
            null, null, null, null, null);
        try {
            while (l.moveToNext()) {
                long a = l.getLong(0), b = l.getLong(1);
                int[] va = out.get(a);
                if (va == null) { va = new int[2]; out.put(a, va); }
                va[1]++;
                int[] vb = out.get(b);
                if (vb == null) { vb = new int[2]; out.put(b, vb); }
                vb[1]++;
            }
        } finally {
            l.close();
        }
        return out;
    }

    /**
     * Добавить связь между двумя мыслями.
     * @param linkType "similar" | "develops" | "answers"
     */
    public void addLink(long fromId, long toId, String linkType) {
        addLink(fromId, toId, linkType, "");
    }

    /**
     * Добавить связь между двумя мыслями с объяснением.
     * @param linkType "similar" | "develops" | "answers"
     * @param reason   почему связано (пишет LLM при автосвязях)
     */
    public void addLink(long fromId, long toId, String linkType, String reason) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(NotesDbHelper.COL_LINK_FROM, fromId);
        cv.put(NotesDbHelper.COL_LINK_TO, toId);
        cv.put(NotesDbHelper.COL_LINK_TYPE, linkType);
        cv.put(NotesDbHelper.COL_LINK_CREATED, System.currentTimeMillis());
        cv.put(NotesDbHelper.COL_LINK_REASON, reason == null ? "" : reason);
        db.insertWithOnConflict(NotesDbHelper.TABLE_LINKS, null, cv,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Удалить связь между двумя заметками (в обе стороны, все типы).
     * Пользователь удаляет неверные автосвязи.
     */
    public void removeLink(long a, long b) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(NotesDbHelper.TABLE_LINKS,
            "(" + NotesDbHelper.COL_LINK_FROM + "=? AND " + NotesDbHelper.COL_LINK_TO + "=?) OR ("
                + NotesDbHelper.COL_LINK_FROM + "=? AND " + NotesDbHelper.COL_LINK_TO + "=?)",
            new String[]{String.valueOf(a), String.valueOf(b),
                         String.valueOf(b), String.valueOf(a)});
    }

    /**
     * Связи заметки с объяснениями (для UI «Связи» в экране мысли).
     * @return список Object[] {Long otherId, String linkType, String reason}
     */
    public List<Object[]> getLinksDetailed(long noteId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        ArrayList<Object[]> out = new ArrayList<>();
        Cursor c = db.query(NotesDbHelper.TABLE_LINKS,
            new String[]{NotesDbHelper.COL_LINK_TO, NotesDbHelper.COL_LINK_TYPE, NotesDbHelper.COL_LINK_REASON},
            NotesDbHelper.COL_LINK_FROM + "=?", new String[]{String.valueOf(noteId)},
            null, null, null);
        try {
            while (c.moveToNext()) {
                out.add(new Object[]{c.getLong(0), c.getString(1), c.getString(2)});
            }
        } finally {
            c.close();
        }
        c = db.query(NotesDbHelper.TABLE_LINKS,
            new String[]{NotesDbHelper.COL_LINK_FROM, NotesDbHelper.COL_LINK_TYPE, NotesDbHelper.COL_LINK_REASON},
            NotesDbHelper.COL_LINK_TO + "=?", new String[]{String.valueOf(noteId)},
            null, null, null);
        try {
            while (c.moveToNext()) {
                out.add(new Object[]{c.getLong(0), c.getString(1), c.getString(2)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Получить все связи для заметки (в обе стороны).
     * @return список [otherNoteId, linkType]
     */
    public List<long[]> getLinks(long noteId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        ArrayList<long[]> out = new ArrayList<>();
        // Связи где noteId = from
        Cursor c = db.query(NotesDbHelper.TABLE_LINKS,
            new String[]{NotesDbHelper.COL_LINK_TO, NotesDbHelper.COL_LINK_TYPE},
            NotesDbHelper.COL_LINK_FROM + "=?", new String[]{String.valueOf(noteId)},
            null, null, null);
        try {
            while (c.moveToNext()) {
                out.add(new long[]{c.getLong(0), linkTypeToInt(c.getString(1))});
            }
        } finally {
            c.close();
        }
        // Связи где noteId = to
        c = db.query(NotesDbHelper.TABLE_LINKS,
            new String[]{NotesDbHelper.COL_LINK_FROM, NotesDbHelper.COL_LINK_TYPE},
            NotesDbHelper.COL_LINK_TO + "=?", new String[]{String.valueOf(noteId)},
            null, null, null);
        try {
            while (c.moveToNext()) {
                out.add(new long[]{c.getLong(0), linkTypeToInt(c.getString(1))});
            }
        } finally {
            c.close();
        }
        return out;
    }

    private long linkTypeToInt(String type) {
        switch (type != null ? type : "") {
            case "similar": return 1;
            case "develops": return 2;
            case "answers": return 3;
            default: return 0;
        }
    }

    // === Diagnostics ===

    /**
     * Количество заметок в каждом состоянии.
     */
    public String getQueueStats() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        StringBuilder sb = new StringBuilder();
        for (String state : new String[]{NotesDbHelper.STATE_PENDING,
                NotesDbHelper.STATE_PROCESSING, NotesDbHelper.STATE_DONE,
                NotesDbHelper.STATE_FAILED}) {
            Cursor c = db.query(NotesDbHelper.TABLE, new String[]{"COUNT(*)"},
                NotesDbHelper.COL_STATE + "=?", new String[]{state},
                null, null, null);
            try {
                if (c.moveToFirst()) {
                    sb.append(state).append("=").append(c.getInt(0)).append(" ");
                }
            } finally {
                c.close();
            }
        }
        return sb.toString().trim();
    }

    // === Внутренние методы ===

    private Note cursorToNote(Cursor c) {
        Note n = new Note();
        n.id = c.getLong(c.getColumnIndexOrThrow(NotesDbHelper.COL_ID));
        n.ts = c.getLong(c.getColumnIndexOrThrow(NotesDbHelper.COL_TS));
        n.durSec = c.getInt(c.getColumnIndexOrThrow(NotesDbHelper.COL_DUR));
        n.sizeBytes = c.getInt(c.getColumnIndexOrThrow(NotesDbHelper.COL_SIZE));
        n.text = c.getString(c.getColumnIndexOrThrow(NotesDbHelper.COL_TEXT));
        n.status = c.getString(c.getColumnIndexOrThrow(NotesDbHelper.COL_STATUS));
        n.type = c.getString(c.getColumnIndexOrThrow(NotesDbHelper.COL_TYPE));
        n.summary = c.getString(c.getColumnIndexOrThrow(NotesDbHelper.COL_SUMMARY));
        n.eventTitle = c.getString(c.getColumnIndexOrThrow(NotesDbHelper.COL_EVENT_TITLE));
        int dueIdx = c.getColumnIndex(NotesDbHelper.COL_DUE_ISO);
        n.dueIso = c.isNull(dueIdx) ? null : c.getString(dueIdx);
        int remindIdx = c.getColumnIndex(NotesDbHelper.COL_REMIND_OFFSET);
        n.remindOffsetMin = c.isNull(remindIdx) ? null : c.getInt(remindIdx);
        n.calendarEventId = c.getLong(c.getColumnIndexOrThrow(NotesDbHelper.COL_CAL_EVENT_ID));

        // items: JSON array → List<CheckItem>
        int itemsIdx = c.getColumnIndex(NotesDbHelper.COL_ITEMS);
        if (!c.isNull(itemsIdx)) {
            String itemsJson = c.getString(itemsIdx);
            n.items = parseItems(itemsJson);
        }

        int rawIdx = c.getColumnIndex(NotesDbHelper.COL_RAW);
        n.raw = rawIdx >= 0 && !c.isNull(rawIdx) ? c.getString(rawIdx) : "";

        // Поля очереди
        int stateIdx = c.getColumnIndex(NotesDbHelper.COL_STATE);
        n.state = stateIdx >= 0 && !c.isNull(stateIdx)
            ? c.getString(stateIdx) : NotesDbHelper.STATE_DONE;
        int retryIdx = c.getColumnIndex(NotesDbHelper.COL_RETRY_COUNT);
        n.retryCount = retryIdx >= 0 && !c.isNull(retryIdx) ? c.getInt(retryIdx) : 0;
        int errIdx = c.getColumnIndex(NotesDbHelper.COL_LAST_ERROR);
        n.lastError = errIdx >= 0 && !c.isNull(errIdx) ? c.getString(errIdx) : null;
        int audioIdx = c.getColumnIndex(NotesDbHelper.COL_AUDIO_PATH);
        n.audioPath = audioIdx >= 0 && !c.isNull(audioIdx) ? c.getString(audioIdx) : null;
        int trIdx = c.getColumnIndex(NotesDbHelper.COL_TRANSCRIPT);
        n.transcript = trIdx >= 0 && !c.isNull(trIdx) ? c.getString(trIdx) : "";
        int updIdx = c.getColumnIndex(NotesDbHelper.COL_UPDATED_AT);
        n.updatedAt = updIdx >= 0 && !c.isNull(updIdx) ? c.getLong(updIdx) : 0;

        int parentIdx = c.getColumnIndex(NotesDbHelper.COL_PARENT_ID);
        n.parentId = parentIdx >= 0 && !c.isNull(parentIdx) ? c.getLong(parentIdx) : -1;

        int authorIdx = c.getColumnIndex(NotesDbHelper.COL_AUTHOR);
        n.author = authorIdx >= 0 && !c.isNull(authorIdx) ? c.getString(authorIdx) : "me";

        int kindIdx = c.getColumnIndex(NotesDbHelper.COL_KIND);
        n.kind = kindIdx >= 0 && !c.isNull(kindIdx) ? c.getString(kindIdx) : "root";

        return n;
    }

    private ContentValues noteToCv(Note n) {
        ContentValues cv = new ContentValues();
        cv.put(NotesDbHelper.COL_ID, n.id);
        cv.put(NotesDbHelper.COL_TS, n.ts);
        cv.put(NotesDbHelper.COL_DUR, n.durSec);
        cv.put(NotesDbHelper.COL_SIZE, n.sizeBytes);
        cv.put(NotesDbHelper.COL_TEXT, n.text == null ? "" : n.text);
        cv.put(NotesDbHelper.COL_STATUS, n.status == null ? "done" : n.status);
        cv.put(NotesDbHelper.COL_TYPE, n.type == null ? "note" : n.type);
        cv.put(NotesDbHelper.COL_SUMMARY, n.summary == null ? "" : n.summary);
        cv.put(NotesDbHelper.COL_EVENT_TITLE, n.eventTitle == null ? "" : n.eventTitle);
        if (n.dueIso != null) cv.put(NotesDbHelper.COL_DUE_ISO, n.dueIso);
        else cv.putNull(NotesDbHelper.COL_DUE_ISO);
        if (n.remindOffsetMin != null) cv.put(NotesDbHelper.COL_REMIND_OFFSET, n.remindOffsetMin);
        else cv.putNull(NotesDbHelper.COL_REMIND_OFFSET);
        cv.put(NotesDbHelper.COL_CAL_EVENT_ID, n.calendarEventId);

        // items: List<CheckItem> → JSON array
        if (n.items != null && !n.items.isEmpty()) {
            JSONArray ia = new JSONArray();
            for (CheckItem it : n.items) {
                JSONObject jo = new JSONObject();
                try {
                    jo.put("t", it.text == null ? "" : it.text);
                    jo.put("d", it.done);
                } catch (Exception ignore) {}
                ia.put(jo);
            }
            cv.put(NotesDbHelper.COL_ITEMS, ia.toString());
        } else {
            cv.putNull(NotesDbHelper.COL_ITEMS);
        }

        cv.put(NotesDbHelper.COL_RAW, n.raw == null ? "" : n.raw);
        cv.put(NotesDbHelper.COL_STATE, n.state == null ? NotesDbHelper.STATE_DONE : n.state);
        cv.put(NotesDbHelper.COL_RETRY_COUNT, n.retryCount);
        cv.put(NotesDbHelper.COL_LAST_ERROR, n.lastError);
        cv.put(NotesDbHelper.COL_AUDIO_PATH, n.audioPath);
        cv.put(NotesDbHelper.COL_TRANSCRIPT, n.transcript == null ? "" : n.transcript);
        cv.put(NotesDbHelper.COL_UPDATED_AT, System.currentTimeMillis());
        cv.put(NotesDbHelper.COL_PARENT_ID, n.parentId);
        cv.put(NotesDbHelper.COL_AUTHOR, n.author == null ? "me" : n.author);
        cv.put(NotesDbHelper.COL_KIND, n.kind == null ? "root" : n.kind);
        return cv;
    }

    @SuppressWarnings("unchecked")
    private List<CheckItem> parseItems(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONArray ia = new JSONArray(json);
            if (ia.length() == 0) return null;
            List<CheckItem> items = new ArrayList<>();
            for (int i = 0; i < ia.length(); i++) {
                Object el = ia.opt(i);
                if (el instanceof JSONObject) {
                    JSONObject jo = (JSONObject) el;
                    items.add(new CheckItem(jo.optString("t", ""), jo.optBoolean("d", false)));
                } else {
                    items.add(new CheckItem(String.valueOf(el), false));
                }
            }
            return items.isEmpty() ? null : items;
        } catch (Exception e) {
            return null;
        }
    }

    // === Миграция из notes.json ===

    private void migrateFromJsonIfNeeded() {
        java.io.File jsonFile = new java.io.File(ctx.getFilesDir(), "notes.json");
        java.io.File migratedFlag = new java.io.File(ctx.getFilesDir(), "notes_migrated.flag");
        if (!jsonFile.exists() || migratedFlag.exists()) return;

        try {
            byte[] data = readFile(jsonFile);
            if (data == null || data.length == 0) {
                // Пустой файл — просто помечаем как мигрированный
                migratedFlag.createNewFile();
                return;
            }
            JSONArray arr = new JSONArray(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        JSONObject o = arr.getJSONObject(i);
                        Note n = new Note();
                        n.id = o.optLong("id", 0);
                        n.ts = o.optLong("ts", 0);
                        n.durSec = o.optInt("dur", 0);
                        n.sizeBytes = o.optInt("size", 0);
                        n.text = o.optString("text", "");
                        n.status = o.optString("status", "done");
                        n.type = o.optString("type", "note");
                        n.summary = o.optString("summary", "");
                        n.eventTitle = o.optString("event_title", "");
                        n.dueIso = o.isNull("due_iso") ? null : o.optString("due_iso", null);
                        if (!o.isNull("remind_offset_min")) {
                            try { n.remindOffsetMin = o.getInt("remind_offset_min"); } catch (Exception ignore) {}
                        }
                        n.calendarEventId = o.optLong("calendar_event_id", -1);
                        if (!o.isNull("items")) {
                            try {
                                JSONArray ia = o.getJSONArray("items");
                                n.items = new ArrayList<>();
                                for (int j = 0; j < ia.length(); j++) {
                                    Object el = ia.opt(j);
                                    if (el instanceof JSONObject) {
                                        JSONObject jo = (JSONObject) el;
                                        n.items.add(new CheckItem(jo.optString("t", ""), jo.optBoolean("d", false)));
                                    } else {
                                        n.items.add(new CheckItem(String.valueOf(el), false));
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                        // Мигрированные заметки: done если LLM отработал, failed если summary пустой
                        n.state = (n.summary != null && !n.summary.isEmpty())
                            ? NotesDbHelper.STATE_DONE
                            : NotesDbHelper.STATE_FAILED;
                        n.transcript = n.text;
                        ContentValues cv = noteToCv(n);
                        db.insertWithOnConflict(NotesDbHelper.TABLE, null, cv,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    } catch (Exception ignore) {}
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            // Помечаем миграцию завершённой, не удаляем notes.json (бэкап)
            migratedFlag.createNewFile();
            android.util.Log.i("vn-store", "Migrated " + arr.length() + " notes from JSON to SQLite");
        } catch (Exception e) {
            android.util.Log.e("vn-store", "Migration failed: " + e.getMessage(), e);
        }
    }

    private static byte[] readFile(java.io.File f) throws Exception {
        if (!f.exists()) return null;
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        return bos.toByteArray();
    }
}
