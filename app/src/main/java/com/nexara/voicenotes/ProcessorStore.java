package com.nexara.voicenotes;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище пользовательских обработчиков (LLM-ролей).
 *
 * Хранится в SharedPreferences как JSON-массив — список короткий (десятки),
 * отдельная таблица не оправдана. Это чистая логика без Android-зависимостей
 * в разборе, поэтому покрыта unit-тестами.
 *
 * Ключ: "processors_json". Поле nextId хранится отдельно, чтобы id были
 * монотонными и стабильными (не индексом массива — иначе удаление
 * среднего элемента сбивало бы ссылки).
 */
public class ProcessorStore {

    private static final String PREFS = "vn";
    private static final String KEY_JSON = "processors_json";
    private static final String KEY_NEXT_ID = "processors_next_id";

    private final SharedPreferences prefs;

    public ProcessorStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Конструктор для тестов — принимает готовые prefs. */
    public ProcessorStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public List<Processor> list() {
        return ProcessorCodec.deserialize(prefs.getString(KEY_JSON, "[]"));
    }

    public long add(String name, String prompt, int color) {
        long id = nextId();
        List<Processor> all = list();
        Processor p = new Processor(id, trim(name), trim(prompt), color);
        all.add(p);
        save(all);
        return id;
    }

    public void update(long id, String name, String prompt, int color) {
        List<Processor> all = list();
        for (Processor p : all) {
            if (p.id == id) {
                p.name = trim(name);
                p.prompt = trim(prompt);
                p.color = color;
                break;
            }
        }
        save(all);
    }

    public void delete(long id) {
        List<Processor> all = list();
        List<Processor> kept = new ArrayList<>();
        for (Processor p : all) {
            if (p.id != id) kept.add(p);
        }
        save(kept);
    }

    public Processor getById(long id) {
        for (Processor p : list()) {
            if (p.id == id) return p;
        }
        return null;
    }

    // ---------- внутреннее ----------

    private long nextId() {
        long id = prefs.getLong(KEY_NEXT_ID, 1);
        prefs.edit().putLong(KEY_NEXT_ID, id + 1).apply();
        return id;
    }

    private void save(List<Processor> all) {
        prefs.edit().putString(KEY_JSON, ProcessorCodec.serialize(all)).apply();
    }

    static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
