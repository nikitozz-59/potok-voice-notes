package com.nexara.voicenotes;

/**
 * Интерфейс провайдера LLM для извлечения структуры из транскрипта.
 *
 * Любой OpenAI-compatible chat-completions endpoint реализует этот интерфейс.
 * Выбор — через SharedPreferences ("vn" / "llm_base_url", "llm_model", "llm_key").
 *
 * Парсинг ответа делегируется в LlmExtractor.parseResponse() — чистая функция,
 * покрытая золотыми тестами.
 */
public interface LlmProvider {

    /**
     * Извлечь структуру (тип, саммари, дата, чек-лист) из транскрипта.
     *
     * @param transcript текст транскрипта
     * @return Struct с распарсенными полями (type, summary, dueIso, items, ...)
     * @throws Exception при ошибке (сеть, авторизация, невалидный ответ)
     */
    LlmExtractor.Struct extract(String transcript) throws Exception;

    /**
     * Multi-turn диалог: послать историю (system + user/assistant ...) → текст ответа.
     * Отличие от extract(): возвращает чистый текст (свободный разговор, без JSON-структуры).
     *
     * @param messages история: [system, user, assistant, user, ...] — full context.
     * @return ответ ассистента (чистый текст).
     * @throws Exception при ошибке (сеть, авторизация, невалидный ответ).
     */
    String chat(java.util.List<ChatMessage> messages, String systemPrompt) throws Exception;

    /**
     * Человекочитаемое имя провайдера (для логов и UI).
     */
    String getName();
}
