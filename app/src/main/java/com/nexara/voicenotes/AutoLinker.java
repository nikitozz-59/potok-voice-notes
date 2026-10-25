package com.nexara.voicenotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Автосвязи: после сохранения заметки спрашиваем LLM, с какими из существующих
 * мыслей она связана по смыслу, и создаём связи типа "similar" с объяснением.
 *
 * Решение пользователя (вместо ритуала ручного подтверждения): связи создаются
 * сами, неверные удаляются в экране мысли («Связи» → ✕). Так граф накапливается
 * без трения.
 *
 * Ограничения стоимости: не более 25 кандидатов в промпте и 3 связей на заметку.
 * Блоки (parent_id > 0) не связываются — связываются только корневые мысли.
 */
public class AutoLinker {
    private static final String TAG = "vn-autolink";
    private static final int MAX_CANDIDATES = 25;
    private static final int MAX_LINKS = 3;

    /** Запустить поиск связей в фоне; ошибки глушатся (связи — не критичный путь). */
    public static void linkAsync(final Context ctx, final long noteId) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                link(app, noteId);
            } catch (Exception e) {
                Log.w(TAG, "autolink err: " + e.getMessage());
            }
        }).start();
    }

    private static void link(Context ctx, long noteId) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences("vn", Context.MODE_PRIVATE);
        String key = prefs.getString("llm_key", "");
        if (key == null || key.isEmpty()) return;

        NotesStore store = new NotesStore(ctx);
        NotesStore.Note self = store.getById(noteId);
        if (self == null || self.parentId > 0) return; // блоки не связываем

        String selfText = (((self.summary != null ? self.summary : "") + " "
            + (self.text != null ? self.text : ""))).trim();
        if (selfText.isEmpty()) return;

        List<NotesStore.Note> all = store.list();
        StringBuilder sb = new StringBuilder();
        sb.append("Отметь существующие заметки, связанные по смыслу с новой ")
          .append("(та же тема, проект, продолжение мысли). ")
          .append("Верни СТРОГО JSON-массив не более ").append(MAX_LINKS)
          .append(" элементов вида [{\"id\":123,\"reason\":\"коротко почему связано\"}]. ")
          .append("Нет связей — верни [].\n\n")
          .append("Новая заметка: ").append(selfText).append("\n\n")
          .append("Существующие заметки:\n");
        int count = 0;
        for (NotesStore.Note n : all) {
            if (n.id == noteId || n.parentId > 0) continue;
            if (n.summary == null || n.summary.isEmpty()) continue;
            sb.append("[").append(n.id).append("] ").append(n.summary).append("\n");
            if (++count >= MAX_CANDIDATES) break;
        }
        if (count == 0) return;

        String resp = callLlmRaw(ctx, sb.toString());
        if (resp == null || resp.isEmpty()) return;

        int a = resp.indexOf('[');
        int b = resp.lastIndexOf(']');
        if (a < 0 || b <= a) return;
        JSONArray arr;
        try {
            arr = new JSONArray(resp.substring(a, b + 1));
        } catch (Exception e) {
            Log.w(TAG, "autolink bad json: " + e.getMessage());
            return;
        }

        int added = 0;
        for (int i = 0; i < arr.length() && added < MAX_LINKS; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id", -1);
            if (id <= 0 || id == noteId) continue;
            if (store.getById(id) == null) continue;
            String reason = o.optString("reason", "");
            store.addLink(noteId, id, "similar", reason);
            added++;
        }
        if (added > 0) {
            Log.i(TAG, "autolink: " + added + " links for note " + noteId);
            ctx.sendBroadcast(new android.content.Intent(RelayService.ACTION_NOTE_ADDED));
        }
    }

    /** Прямой вызов LLM (тот же паттерн, что ThoughtDetailActivity.callLlmRaw). */
    private static String callLlmRaw(Context ctx, String prompt) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences("vn", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("llm_base_url", "https://plusvibeapi.ru/v1");
        String apiKey = prefs.getString("llm_key", "");
        String model = prefs.getString("llm_model", "z-ai/glm-5.2");
        if (apiKey == null || apiKey.isEmpty()) return null;
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);

            JSONObject req = new JSONObject();
            req.put("model", model);
            req.put("temperature", 0.2);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", prompt));
            req.put("messages", messages);

            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            byte[] body = readAll(is);
            if (code != 200) {
                Log.w(TAG, "llm http " + code);
                return null;
            }
            JSONObject resp = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            return choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        if (is == null) return new byte[0];
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
        return bo.toByteArray();
    }
}
