package com.nexara.voicenotes;

/**
 * Интерфейс провайдера Speech-to-Text.
 *
 * Любой STT-сервис (Nexara, OpenAI Whisper, Groq, локальный Whisper)
 * реализует этот интерфейс. Выбор провайдера — через SharedPreferences
 * ("vn" / "stt_provider"), по умолчанию "nexara".
 *
 * Реализации НЕ зависят от Android Context — ключи передаются через конструктор.
 */
public interface SttProvider {

    /**
     * Транскрибировать WAV-аудио в текст.
     *
     * @param wavData   PCM WAV данные (16kHz, mono, 16-bit)
     * @param filename  имя файла для multipart (например "rec.opus.wav")
     * @param language  язык аудио ("ru", "en", и т.д.)
     * @return распознанный текст
     * @throws Exception при ошибке (сеть, авторизация, невалидный ответ)
     */
    String transcribe(byte[] wavData, String filename, String language) throws Exception;

    /**
     * Человекочитаемое имя провайдера (для логов и UI).
     */
    String getName();
}
