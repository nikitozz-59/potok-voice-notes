package com.nexara.voicenotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class LlmExtractor {
    private static final String TAG = "vn-llm";

    public static class Struct {
        public String type = "note";       // note | idea | list | reminder | task
        public String summary = "";        // 2-5 слов, кратко о сути
        public String eventTitle = "";     // только суть события, без времени
        public String dueIso = null;       // время события (когда ОНО), либо null
        public Integer remindOffsetMin;    // за сколько минут напомнить ДО события, null если не указано
        public java.util.List<String> items = null; // пункты списка дел (type=list)
        public String raw = "";
    }

    /**
     * Извлечь структуру из транскрипта.
     * @deprecated Используйте ProviderFactory.createLlmProvider(ctx).extract(transcript)
     */
    @Deprecated
    public static Struct extract(String transcript, Context ctx) {
        LlmProvider llm = ProviderFactory.createLlmProvider(ctx);
        if (llm == null) {
            Log.w(TAG, "no llm_key set - skipping");
            return new Struct();
        }
        try {
            return llm.extract(transcript);
        } catch (Exception e) {
            Log.e(TAG, "err: " + e.getMessage(), e);
            return new Struct();
        }
    }

    /**
     * Чистая функция парсинга ответа LLM в Struct.
     * Не требует Context, сети, SharedPreferences — тестируется изолированно.
     *
     * @param content — raw текст ответа LLM (содержимое choices[0].message.content)
     * @return Struct с распарсенными полями
     */
    public static Struct parseResponse(String content) {
        Struct s = new Struct();
        if (content == null || content.trim().isEmpty()) {
            s.summary = "(тишина)";
            return s;
        }
        s.raw = content;

        String cleaned = content.trim();
        // Stripping markdown code fences: ```json\n...\n```
        if (cleaned.startsWith("```")) {
            int s0 = cleaned.indexOf('\n');
            int s1 = cleaned.lastIndexOf("```");
            if (s0 > 0 && s1 > s0) cleaned = cleaned.substring(s0 + 1, s1).trim();
            else if (s1 > 3) cleaned = cleaned.substring(3, s1).trim();
        }

        try {
            JSONObject p = new JSONObject(cleaned);
            String t = p.optString("type", "note");
            if ("idea".equals(t) || "reminder".equals(t) || "task".equals(t) || "list".equals(t)) {
                s.type = t;
            }
            s.summary = p.optString("summary", "").trim();
            s.eventTitle = p.optString("event_title", "").trim();

            // Поддерживаем как due_iso, так и event_time_iso (из системного промпта LLM)
            String d = null;
            if (!p.isNull("due_iso")) {
                d = p.optString("due_iso", null);
            } else if (!p.isNull("event_time_iso")) {
                d = p.optString("event_time_iso", null);
            }
            if (d != null && !d.isEmpty() && !"null".equals(d)) {
                s.dueIso = d;
            }

            if (!p.isNull("remind_offset_min")) {
                try { s.remindOffsetMin = p.getInt("remind_offset_min"); } catch (Exception ignore) {}
            }

            // Список дел
            if (!p.isNull("items")) {
                JSONArray ia = p.optJSONArray("items");
                if (ia != null && ia.length() > 0) {
                    java.util.List<String> its = new java.util.ArrayList<>();
                    for (int i = 0; i < ia.length(); i++) {
                        String v = ia.optString(i, "").trim();
                        if (!v.isEmpty() && !"null".equals(v)) its.add(v);
                    }
                    if (!its.isEmpty()) s.items = its;
                }
            }

            // Два и более пунктов = список дел (если это не напоминание с датой).
            if (s.items != null && s.items.size() >= 2 && !"reminder".equals(s.type)) {
                s.type = "list";
            }
        } catch (Exception e) {
            // JSON невалидный — возвращаем Struct с defaults (type=note)
            s.summary = s.summary.isEmpty() ? "(ошибка разбора)" : s.summary;
        }

        return s;
    }

    /**
     * Валидация результата LLM с умным fallback.
     * Если summary пустой или невалидный — используем первые слова транскрипта.
     * Никогда не теряем транскрипт.
     *
     * @param s          результат parseResponse()
     * @param transcript оригинальный транскрипт (для fallback)
     * @return валидированный Struct
     */
    public static Struct validateAndFallback(Struct s, String transcript) {
        if (s == null) s = new Struct();

        boolean needsFallback = s.summary == null || s.summary.isEmpty()
                || "(тишина)".equals(s.summary)
                || "(ошибка разбора)".equals(s.summary);

        if (needsFallback && transcript != null && !transcript.trim().isEmpty()) {
            s.summary = makeFallbackSummary(transcript);
            if (s.raw == null || s.raw.isEmpty()) {
                s.raw = transcript;
            }
        }

        // Fallback для чек-листа: LLM иногда отдаёт type=list, но items пустым
        // (регрессия — «иконка list есть, а квадратиков нет»). Восстанавливаем
        // пункты из самого транскрипта, разбивая по разделителям перечислений.
        // Если восстановить не из чего — понижаем type, чтобы не показывать
        // иконку списка без пунктов.
        if ("list".equals(s.type) && (s.items == null || s.items.isEmpty())) {
            java.util.List<String> parts = (transcript != null && !transcript.trim().isEmpty())
                ? splitItems(transcript)
                : java.util.Collections.emptyList();
            if (parts.size() >= 2) {
                s.items = parts;
            } else if (parts.size() == 1) {
                // Одно дело — это не список, понижаем до task.
                s.type = "task";
                s.items = null;
            } else {
                // Не удалось разбить — понижаем до note, квадратиков не будет.
                s.type = "note";
                s.items = null;
            }
        }
        return s;
    }

    /**
     * Разбить транскрипт на пункты списка по разделителям перечислений.
     * Используется как fallback, когда LLM вернул type=list, но пустой items.
     *
     * Разделители: новая строка, запятая, точка с запятой, союзы
     * «и», «потом», «затем», «ещё», «после», «а также».
     * Удаляет служебные префиксы («список дел:», «надо:», «купить:» и т.п.).
     */
    static java.util.List<String> splitItems(String transcript) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (transcript == null) return out;
        // Нормализуем пробелы/табы (НЕ трогая newlines — они тоже разделители).
        String t = transcript.trim().replaceAll("[ \t]+", " ");
        t = t.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
        // Приводим разделители перечислений к \n.
        t = t.replaceAll("\\s*,\\s*", "\n");
        t = t.replaceAll("\\s*;\\s*", "\n");
        t = t.replaceAll("(?iu)\\s+и\\s+", "\n");
        t = t.replaceAll("(?iu)\\s+потом\\s+", "\n");
        t = t.replaceAll("(?iu)\\s+затем\\s+", "\n");
        t = t.replaceAll("(?iu)\\s+ещё\\s+", "\n");
        t = t.replaceAll("(?iu)\\s+еще\\s+", "\n");
        t = t.replaceAll("(?iu)\\s+после\\s+", "\n");
        t = t.replaceAll("(?iu)\\s+а также\\s+", "\n");
        String[] parts = t.split("\\n");
        for (String p : parts) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            // Срезаем служебные префиксы: «список дел:», «надо:», «нужно:», «купить:», «планы:».
            v = v.replaceFirst("(?iu)^\\s*(список\\s+дел|надо|нужно|купить|планы|задачи|список)\\s*[:\\-]\\s*", "");
            v = v.replaceFirst("(?iu)^\\s*\\d+[.)]\\s*", "");  // «1.», «2)»
            v = v.trim();
            if (v.isEmpty() || v.length() < 2) continue;
            // Заглавная первая буква.
            v = Character.toUpperCase(v.charAt(0)) + v.substring(1);
            out.add(v);
        }
        return out;
    }

    /**
     * Создать краткий summary из транскрипта (fallback).
     * Первые 5-6 слов, обрезаем до 40 символов, первая буква заглавная.
     */
    public static String makeFallbackSummary(String transcript) {
        String trimmed = transcript.trim().replaceAll("\\s+", " ");
        String[] words = trimmed.split(" ");
        int count = Math.min(words.length, 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        String result = sb.toString();
        if (result.length() > 40) result = result.substring(0, 37) + "…";
        if (!result.isEmpty()) {
            result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
        }
        return result;
    }

    static String buildSystemPrompt() {
        return "Ты — парсер голосовых заметок на русском языке.\n"
            + "На вход — транскрипт голосовой заметки. На выход — строго JSON, без markdown, без пояснений.\n\n"
            + "Формат:\n"
            + "{\n"
            + "  \"type\": \"note|idea|list|reminder|task\",\n"
            + "  \"summary\": \"2-5 слов, кратко о сути — это увидит пользователь в списке\",\n"
            + "  \"event_title\": \"ТОЛЬКО суть события без времени, без слов типа 'напомни', 'не забудь', 'через час', без даты\",\n"
            + "  \"event_time_iso\": \"YYYY-MM-DDTHH:mm:ss\" (БЕЗ смещения часового пояса, в локальном времени пользователя) или null,\n"
            + "  \"remind_offset_min\": целое число минут (>=0) или null,\n"
            + "  \"items\": [\"пункт 1\", \"пункт 2\"] — только для type=list, иначе []\n"
            + "}\n\n"
            + "Тип:\n"
            + "- reminder — если просят напомнить (напомни, не забудь, напомнить)\n"
            + "- task — ОДНО дело (купи хлеб, позвони маме)\n"
            + "- list — СПИСОК дел или покупок: перечислено два и более разных дела/покупок\n"
            + "- idea — идея, размышление, подумать\n"
            + "- note — всё остальное\n\n"
            + "items — ОБЯЗАТЕЛЬНО для type=list: массив из 2+ пунктов. Никогда не возвращай type=list с items: [].\n"
            + "Каждый пункт — отдельная короткая строка в повелительной форме («Купить хлеб», «Позвонить маме»).\n"
            + "Разбивай перечисление на отдельные дела. Если дел одно — это type=task, не list.\n"
            + "Если перечисления нет — выбери type=note/idea/task, items: [].\n\n"
            + "event_time_iso — это ВРЕМЯ САМОГО СОБЫТИЯ в локальном времени пользователя (БЕЗ смещения UTC):\n"
            + "- \"завтра в 16:00\" → завтра 16:00\n"
            + "- \"через 2 часа\" → сейчас + 2 часа\n"
            + "- \"в пятницу утром\" → ближайшая пятница 10:00\n"
            + "- \"сегодня вечером\" → сегодня 19:00\n"
            + "- если есть слово \"сегодня/завтра\" но нет времени → 09:00\n"
            + "- если даты нет вообще → null\n\n"
            + "remind_offset_min — ЗА СКОЛЬКО МИНУТ напомнить ДО события:\n"
            + "- \"за час\" → 60\n"
            + "- \"за 15 минут\" → 15\n"
            + "- \"за день\" → 1440\n"
            + "- \"за полчаса\" → 30\n"
            + "- если не указано → null\n\n"
            + "event_title — чистое название, без времени и служебных слов.\n"
            + "Плохо: \"Напомнить завтра монтаж в 16:00 за час\"\n"
            + "Хорошо: \"Монтаж\"\n\n"
            + "Примеры:\n"
            + "Вход: Напомнить завтра монтаж в 16:00, напомнить за час\n"
            + "Выход: {\"type\":\"reminder\",\"summary\":\"Монтаж\",\"event_title\":\"Монтаж\",\"event_time_iso\":\"ЗАВТРА_16:00\",\"remind_offset_min\":60,\"items\":[]}\n\n"
            + "Вход: Купи хлеб\n"
            + "Выход: {\"type\":\"task\",\"summary\":\"Купить хлеб\",\"event_title\":\"Купить хлеб\",\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}\n\n"
            + "Вход: Список дел: купить хлеб и молоко, позвонить маме, забрать посылку\n"
            + "Выход: {\"type\":\"list\",\"summary\":\"Список дел\",\"event_title\":\"Список дел\",\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[\"Купить хлеб\",\"Купить молоко\",\"Позвонить маме\",\"Забрать посылку\"]}\n\n"
            + "Плохо (type=list, но items пустой — ТАК НЕЛЬЗЯ, пункты потеряны):\n"
            + "Выход: {\"type\":\"list\",\"summary\":\"Список дел\",\"items\":[]}  ← никогда так\n\n"
            + "Вход: Идея сделать приложение для заметок\n"
            + "Выход: {\"type\":\"idea\",\"summary\":\"Приложение для заметок\",\"event_title\":\"Приложение для заметок\",\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}\n\n"
            + "Сейчас: " + todayLocal() + ". Время и даты считай в локальном часовом поясе пользователя.";
    }

    public static String normalize(String rawTranscript, Context ctx) {
        if (rawTranscript == null || rawTranscript.trim().isEmpty()) return rawTranscript;

        SharedPreferences prefs = ctx.getSharedPreferences("vn", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("llm_base_url", "https://plusvibeapi.ru/v1");
        String apiKey = prefs.getString("llm_key", "");
        String model = prefs.getString("llm_model", "z-ai/glm-5.2");
        if (apiKey == null || apiKey.isEmpty()) return rawTranscript;

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);

            String sys = "Перепиши голосовую заметку на русском языке в аккуратный текст.\n"
                + "Убери слова-паразиты (эээ, ну, типа, значит), исправь грамматику и пунктуацию, "
                + "добавь заглавные буквы. Сохрани смысл и все детали. Не добавляй ничего от себя, "
                + "не сокращай. Верни ТОЛЬКО нормализованный текст, без пояснений, без markdown.";

            JSONObject req = new JSONObject();
            req.put("model", model);
            req.put("temperature", 0.2);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", sys));
            messages.put(new JSONObject().put("role", "user").put("content", rawTranscript));
            req.put("messages", messages);

            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code != 200) return rawTranscript;
            InputStream is = c.getInputStream();
            String body = new String(readAll(is), StandardCharsets.UTF_8);

            JSONObject resp = new JSONObject(body);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return rawTranscript;
            String content = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            return content.isEmpty() ? rawTranscript : content;
        } catch (Exception e) {
            Log.e(TAG, "normalize err: " + e.getMessage());
            return rawTranscript;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is == null) return bos.toByteArray();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } catch (Exception e) {}
        return bos.toByteArray();
    }

    private static String todayLocal() {
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.forLanguageTag("ru"));
        f.setTimeZone(tz);
        String tzName = tz.getID();
        // Часовое смещение для подсказки LLM
        int offsetMin = tz.getOffset(System.currentTimeMillis()) / 60000;
        String offStr = (offsetMin >= 0 ? "+" : "") + (offsetMin / 60) + ":" + pad2(Math.abs(offsetMin) % 60);
        return f.format(new java.util.Date()) + " (часовой пояс " + tzName + ", смещение UTC" + offStr + ")";
    }

    private static String pad2(int n) {
        return (n < 10 ? "0" : "") + n;
    }
}
