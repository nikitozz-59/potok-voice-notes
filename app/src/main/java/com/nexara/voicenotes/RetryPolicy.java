package com.nexara.voicenotes;

/**
 * Классификация ошибок STT/LLM: transient (сетевая/таймаут — ретрай имеет смысл)
 * vs permanent (auth/ключ/мусор — ретрай бесполезен, сразу терминальная ошибка).
 *
 * Чистая логика, без Android-зависимостей — покрывается unit-тестами.
 *
 * Правило: unknown ошибка → transient (безопаснее ретраить, чем терять заметку).
 * Permanent помечаем только когда уверены: нет ключа, auth failed, файл ушёл,
 * LLM вернул пустоту/мусор. В этих случаях повтор ничего не изменит.
 */
public final class RetryPolicy {

    private RetryPolicy() {}

    /**
     * Permanent — повторная попытка не поможет. Сразу терминальная ошибка
     * (retryCount = MAX_RETRIES), часы видят «ОШИБКА: …» без 18-минутного ожидания.
     */
    public static boolean isPermanent(String error) {
        if (error == null || error.isEmpty()) return false;
        String e = error.toLowerCase();
        // Нет ключей / конфигурации провайдера
        if (e.contains("no stt key") || e.contains("no llm key")) return true;
        if (e.contains("not configured") || e.contains("no provider")) return true;
        // Аудиофайл ушёл (cacheDir eviction, переустановка) — не вернётся
        if (e.contains("audio file missing") || e.contains("no such file")) return true;
        if (e.contains("filenotfound") || e.contains("file not found")) return true;
        // Auth / bad request — повтор не поможет (401/403/400/404)
        if (e.contains("http 401") || e.contains("http 403")) return true;
        if (e.contains("http 400") || e.contains("http 404")) return true;
        if (e.contains("unauthorized") || e.contains("forbidden")) return true;
        if (e.contains("invalid api key") || e.contains("incorrect api key")) return true;
        if (e.contains("authentication")) return true;
        // LLM вернул мусор / пустоту — повтор запроса не изменит результат
        if (e.contains("no choices")) return true;
        if (e.contains("returned empty") || e.contains("empty/invalid")) return true;
        if (e.contains("empty transcript")) return true;
        if (e.contains("(тишина)") || e.contains("(ошибка разбора)")) return true;
        if (e.contains("llm empty") || e.contains("llm returned empty")) return true;
        // STT Content-Type / размер — запрос кривой, повтор не поможет
        if (e.contains("http 413") || e.contains("payload too large")) return true;
        if (e.contains("http 415") || e.contains("unsupported media type")) return true;
        return false;
    }

    /**
     * Transient — сетевая ошибка/таймаут/5xx/перегруз. Ретрай с backoff имеет смысл.
     * ВНИМАНИЕ: unknown ошибка → true (безопаснее ретраить, чем терять заметку).
     */
    public static boolean isTransient(String error) {
        if (error == null || error.isEmpty()) return true;
        if (isPermanent(error)) return false;
        return true;
    }
}
