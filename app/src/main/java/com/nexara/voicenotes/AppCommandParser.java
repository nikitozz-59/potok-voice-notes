package com.nexara.voicenotes;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Интерпретатор фраз на естественном языке: «сделай акцент оранжевым».
 *
 * Пользователь не водит ползунки — он говорит, что хочет измениться.
 * Модель превращает фразу в набор действий (JSON), AppMutator их применяет.
 *
 * Схема действий:
 *   {"do":"theme","accent":"#FF6D00"}
 *   {"do":"theme","accent":"#7C4DFF","reason":"лаванда"}
 *   {"do":"reset"}
 *   {"do":"warn","message":"..."}   — не понял / не про приложение
 *
 * Парсер двухступенчатый:
 * 1) быстрые локальные правила (без сети) — «верни как было», «обычный синий»;
 * 2) LLM — для свободных фраз. Если ключа нет или сеть упала, отдаём warn.
 */
public class AppCommandParser {

    private static final String SYSTEM_PROMPT =
        "Ты — интерпретатор команд для Android-приложения заметок «Поток».\n" +
        "Пользователь пишет по-русски, чего он хочет от внешнего вида приложения.\n" +
        "Твоя задача — вернуть ОДИН JSON-объект без пояснений и без markdown.\n\n" +
        "Доступные действия:\n" +
        "1. Сменить акцентный цвет:\n" +
        "   {\"do\":\"theme\",\"accent\":\"#RRGGBB\"}\n" +
        "   Подбирай именно тот оттенок, о котором просят. «Оранжевый» -> #FF6D00,\n" +
        "   «мятный» -> #00BFA5, «лаванда» -> #7C4DFF, «бордовый» -> #8E1F3C,\n" +
        "   «песочный» -> #C8A165, «небесный» -> #4FC3F7, «тёмно-зелёный» -> #1B5E20.\n" +
        "   Если просят «потемнее/посветлее» — сдвинь текущий цвет в нужную сторону.\n" +
        "2. Вернуть стандарт:\n" +
        "   {\"do\":\"reset\"}\n" +
        "3. Не про оформление (погода, математика, посторонний текст):\n" +
        "   {\"do\":\"warn\",\"message\":\"короткий ответ по-русски, что это не про оформление\"}\n\n" +
        "Правила:\n" +
        "- Никогда не меняй зелёный индикатор записи и красную кнопку стоп.\n" +
        "- Если пользователь просит «сделай красивее» без конкретики — предложи\n" +
        "  один конкретный вариант через theme с осмысленным цветом.\n" +
        "- Только JSON, ничего больше.";

    public static class Command {
        public String action;   // theme | reset | warn
        public int color;       // для theme
        public String message;  // для warn
        public String reason;   // чем мотивировано (для ответа пользователю)

        boolean isTheme() { return "theme".equals(action); }
        boolean isReset() { return "reset".equals(action); }
        boolean isWarn()  { return "warn".equals(action); }
    }

    private AppCommandParser() {}

    // ---------- точка входа ----------

    /**
     * Разобрать фразу. Никогда не бросает: при любой проблеме вернёт warn.
     */
    public static Command parse(Context ctx, String phrase) {
        String p = phrase == null ? "" : phrase.trim();
        if (p.isEmpty()) return warn("Напишите, что изменить — например «сделай акцент оранжевым»");

        // 1) локальные правила — без сети и без расхода токенов
        Command fast = tryFastRules(p);
        if (fast != null) return fast;

        // 2) LLM
        try {
            String raw = callLlm(ctx, buildPrompt(ctx, p));
            if (raw == null) return warn("Нет связи с LLM — проверьте ключ в настройках");
            Command c = parseJson(raw);
            if (c != null) return c;
            return warn("Не понял фразу. Скажите иначе: «сделай оранжевым», «фиолетовый», «верни стандарт»");
        } catch (Exception e) {
            return warn("Ошибка разбора: " + shortMsg(e));
        }
    }

    // ---------- локальные правила ----------

    static Command tryFastRules(String p) {
        String s = p.toLowerCase().replace('ё', 'е').replaceAll("[!,.?]+$", "").trim();

        // сброс
        if (s.matches(".*(верни|сброс|стандарт|по умолчанию|обычн|дефолт).*")) {
            Command c = new Command();
            c.action = "reset";
            c.reason = "возвращаю стандартный синий";
            return c;
        }

        // типовые названия без обращения к модели
        String hex = namedColor(s);
        if (hex != null) {
            Command c = new Command();
            c.action = "theme";
            c.color = parseHexColor(hex);
            c.reason = "подобрал " + hex;
            return c;
        }

        // прямой hex в тексте
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("#([0-9a-fA-F]{6})").matcher(p);
        if (m.find()) {
            Command c = new Command();
            c.action = "theme";
            c.color = parseHexColor("#" + m.group(1));
            c.reason = "ставлю #" + m.group(1).toUpperCase();
            return c;
        }
        return null;
    }

    static String namedColor(String s) {
        if (s.contains("оранж")) return "#FF6D00";
        if (s.contains("фиолет") || s.contains("лаванд") || s.contains("сирен")) return "#7C4DFF";
        if (s.contains("бирюз") || s.contains("мятн")) return "#00BFA5";
        if (s.contains("красн") && !s.contains("стоп")) return "#E53935";
        if (s.contains("розов")) return "#EC407A";
        if (s.contains("желт")) return "#F9A825";
        if (s.contains("золот") || s.contains("янтар")) return "#FFB300";
        if (s.contains("зелен") || s.contains("хво")) return "#2E7D32";
        if (s.contains("голуб") || s.contains("небес") || s.contains("бирюз")) return "#039BE5";
        if (s.contains("син") || s.contains("исход") || s.contains("обычн")) return "#2C6FE8";
        if (s.contains("графит") || s.contains("сер") || s.contains("сталь")) return "#37474F";
        if (s.contains("песоч") || s.contains("беж") || s.contains("корич")) return "#C8A165";
        if (s.contains("бордо") || s.contains("винн")) return "#8E1F3C";
        if (s.contains("молоч") || s.contains("персик")) return "#FFAB91";
        return null;
    }

    // ---------- JSON ----------

    static Command parseJson(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        // модели любят оборачивать в ```json ... ```
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        t = t.substring(start, end + 1);
        try {
            JSONObject o = new JSONObject(t);
            String doWhat = o.optString("do", "");
            Command c = new Command();
            c.reason = o.optString("reason", "");
            if ("reset".equals(doWhat)) {
                c.action = "reset";
                return c;
            }
            if ("warn".equals(doWhat)) {
                c.action = "warn";
                c.message = o.optString("message", "Не понял, что изменить");
                return c;
            }
            if ("theme".equals(doWhat)) {
                String accent = o.optString("accent", "");
                if (accent.isEmpty()) return null;
                if (!accent.startsWith("#")) accent = "#" + accent;
                c.action = "theme";
                c.color = parseHexColor(normalizeHex(accent));
                return c;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Допускаем #RGB → #RRGGBB, иначе как есть. */
    static String normalizeHex(String hex) {
        String h = hex.trim().toUpperCase();
        if (!h.startsWith("#")) h = "#" + h;
        if (h.length() == 4) {
            return "#" + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3);
        }
        if (h.length() != 7) throw new IllegalArgumentException("bad hex");
        return h;
    }

    /** Разбор #RRGGBB без зависимости от Android — чтобы логика была тестируемой. */
    static int parseHexColor(String hex) {
        String h = normalizeHex(hex);
        int r = Integer.parseInt(h.substring(1, 3), 16);
        int g = Integer.parseInt(h.substring(3, 5), 16);
        int b = Integer.parseInt(h.substring(5, 7), 16);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static Command warn(String msg) {
        Command c = new Command();
        c.action = "warn";
        c.message = msg;
        return c;
    }

    // ---------- LLM ----------

    static String buildPrompt(Context ctx, String phrase) {
        int cur = ThemeManager.accent(ctx);
        String curHex = String.format("#%06X", 0xFFFFFF & cur);
        return "Текущий акцент приложения: " + curHex + "\n"
             + "Фраза пользователя: «" + phrase + "»\n"
             + "Верни JSON по схеме.";
    }

    static String callLlm(Context ctx, String userContent) throws Exception {
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
            c.setReadTimeout(45000);

            JSONObject req = new JSONObject();
            req.put("model", model);
            req.put("temperature", 0.1);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
            messages.put(new JSONObject().put("role", "user").put("content", userContent));
            req.put("messages", messages);

            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = readAll(is);
            if (code != 200) return null;

            JSONObject resp = new JSONObject(body);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            return choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readAll(InputStream is) {
        if (is == null) return "";
        Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A");
        return sc.hasNext() ? sc.next() : "";
    }

    static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }
}
