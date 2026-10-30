package com.nexara.voicenotes;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI-compatible LLM-провайдер для извлечения структуры из транскрипта.
 *
 * Работает с любым chat-completions endpoint:
 * - PlusVibe API (plusvibeapi.ru/v1) — GLM-5.2 (по умолчанию)
 * - OpenRouter, Groq, DeepSeek, Ollama, и т.д.
 *
 * Системный промпт генерируется через LlmExtractor.buildSystemPrompt().
 * Парсинг ответа — через LlmExtractor.parseResponse() (чистая функция, покрытая тестами).
 *
 * Добавлена валидация: если LLM вернул пустой summary или невалидный JSON,
 * используется умный fallback — никогда не теряем транскрипт.
 */
public class OpenAiLlmProvider implements LlmProvider {
    private static final String TAG = "vn-llm-prov";

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiLlmProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public LlmExtractor.Struct extract(String transcript) throws Exception {
        if (transcript == null || transcript.trim().isEmpty()) {
            LlmExtractor.Struct s = new LlmExtractor.Struct();
            s.summary = "(тишина)";
            return s;
        }

        Log.i(TAG, "extracting " + transcript.length() + " chars via " + model);

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

            String sys = LlmExtractor.buildSystemPrompt();

            JSONObject req = new JSONObject();
            req.put("model", model);
            req.put("temperature", 0.0);
            try {
                req.put("response_format", new JSONObject().put("type", "json_object"));
            } catch (Exception ignore) {}
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", sys));
            messages.put(new JSONObject().put("role", "user").put("content", transcript));
            req.put("messages", messages);

            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = new String(readAll(is), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new Exception("LLM HTTP " + code + ": " + body.substring(0, Math.min(body.length(), 200)));
            }

            JSONObject resp = new JSONObject(body);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new Exception("LLM: no choices in response");
            }
            String content = choices.getJSONObject(0).getJSONObject("message").optString("content", "");

            // Парсинг через чистую функцию (покрыта золотыми тестами)
            LlmExtractor.Struct result = LlmExtractor.parseResponse(content);

            // Валидация + умный fallback: никогда не теряем транскрипт
            return LlmExtractor.validateAndFallback(result, transcript);

        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public String getName() {
        return "openai-llm (" + model + ")";
    }

    @Override
    public String chat(java.util.List<ChatMessage> messages, String systemPrompt) throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new Exception("empty chat messages");
        }

        Log.i(TAG, "chat " + messages.size() + " msgs via " + model);

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

            JSONObject req = new JSONObject();
            req.put("model", model);
            req.put("temperature", 0.7); // диалог — чуть креативнее, чем структура
            JSONArray msgs = new JSONArray();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            }
            for (ChatMessage m : messages) {
                msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            req.put("messages", msgs);

            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = new String(readAll(is), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new Exception("LLM HTTP " + code + ": " + body.substring(0, Math.min(body.length(), 200)));
            }

            JSONObject resp = new JSONObject(body);
            return parseChatResponse(resp);

        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Пакетно-приватный парсер ответа /chat/completions → текст ответа.
     * Вынесен из chat() ради юнит-тестирования без сети.
     *
     * @throws Exception если в ответе нет choices или пустой content
     */
    static String parseChatResponse(JSONObject resp) throws Exception {
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("LLM: no choices in response");
        }
        String content = choices.getJSONObject(0).getJSONObject("message").optString("content", "");
        if (content == null || content.trim().isEmpty()) {
            throw new Exception("LLM returned empty chat response");
        }
        return content.trim();
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is == null) return bos.toByteArray();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
