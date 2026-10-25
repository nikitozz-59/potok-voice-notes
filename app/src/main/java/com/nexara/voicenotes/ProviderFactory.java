package com.nexara.voicenotes;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Фабрика провайдеров STT и LLM.
 *
 * Читает настройки из SharedPreferences ("vn") и создаёт нужный провайдер.
 * По умолчанию:
 * - STT: nexara (api.nexara.ru)
 * - LLM: openai-compatible (plusvibeapi.ru/v1, GLM-5.2)
 *
 * Настройки для альтернативных провайдеров:
 * - stt_provider: "nexara" (по умолчанию) или "openai"
 * - stt_base_url: URL для OpenAI-совместимого STT (например "https://api.groq.com/openai/v1")
 * - stt_model: модель STT (например "whisper-large-v3")
 * - stt_key: ключ для альтернативного STT (если не nexara_key)
 *
 * LLM уже провайдер-агностик через llm_base_url + llm_model + llm_key.
 */
public class ProviderFactory {

    /**
     * Создать STT-провайдер по настройкам.
     * Возвращает null если ключ не задан.
     */
    public static SttProvider createSttProvider(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("vn", Context.MODE_PRIVATE);
        String provider = prefs.getString("stt_provider", "nexara");

        if ("openai".equals(provider)) {
            String baseUrl = prefs.getString("stt_base_url", "https://api.openai.com/v1");
            String key = prefs.getString("stt_key", "");
            String model = prefs.getString("stt_model", "whisper-1");
            if (key == null || key.isEmpty()) return null;
            return new OpenAiSttProvider(baseUrl, key, model);
        }

        // По умолчанию — nexara
        String nexaraKey = prefs.getString("nexara_key", "");
        if (nexaraKey == null || nexaraKey.isEmpty()) return null;
        return new NexaraSttProvider(nexaraKey);
    }

    /**
     * Создать LLM-провайдер по настройкам.
     * Возвращает null если ключ не задан.
     */
    public static LlmProvider createLlmProvider(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("vn", Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("llm_base_url", "https://plusvibeapi.ru/v1");
        String apiKey = prefs.getString("llm_key", "");
        String model = prefs.getString("llm_model", "z-ai/glm-5.2");
        if (apiKey == null || apiKey.isEmpty()) return null;
        return new OpenAiLlmProvider(baseUrl, apiKey, model);
    }

    /**
     * Получить Nexara API ключ (для обратной совместимости с RelayService.getKey()).
     */
    public static String getNexaraKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("vn", Context.MODE_PRIVATE);
        String provider = prefs.getString("stt_provider", "nexara");
        if ("openai".equals(provider)) {
            return prefs.getString("stt_key", "");
        }
        return prefs.getString("nexara_key", "");
    }
}
