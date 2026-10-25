package com.nexara.voicenotes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистая сериализация обработчиков в/из JSON — без Android-зависимостей.
 *
 * Вынесена из ProcessorStore, чтобы покрываться unit-тестами без заглушки
 * SharedPreferences. Тот же паттерн, что у LlmExtractor: чистая логика
 * здесь, I/O в сторедже.
 */
public class ProcessorCodec {

    private ProcessorCodec() {}

    public static String serialize(List<Processor> procs) {
        JSONArray arr = new JSONArray();
        if (procs == null) return arr.toString();
        for (Processor p : procs) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", p.id);
                o.put("name", p.name == null ? "" : p.name);
                o.put("prompt", p.prompt == null ? "" : p.prompt);
                o.put("color", p.color);
                arr.put(o);
            } catch (Exception e) { /* skip */ }
        }
        return arr.toString();
    }

    public static List<Processor> deserialize(String json) {
        List<Processor> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Processor p = new Processor();
                p.id = o.optLong("id", 0);
                p.name = o.optString("name", "");
                p.prompt = o.optString("prompt", "");
                p.color = o.optInt("color", 0);
                out.add(p);
            }
        } catch (Exception e) {
            // повреждённый JSON — чистый список
        }
        return out;
    }
}
