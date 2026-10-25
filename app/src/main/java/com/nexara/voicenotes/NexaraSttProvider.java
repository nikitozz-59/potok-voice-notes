package com.nexara.voicenotes;

/**
 * STT-провайдер Nexara (api.nexara.ru). Обёртка над NexaraClient с интерфейсом SttProvider.
 * Сохраняет 3 retry с backoff из NexaraClient.
 */
public class NexaraSttProvider implements SttProvider {
    private final String apiKey;

    public NexaraSttProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String transcribe(byte[] wavData, String filename, String language) throws Exception {
        return NexaraClient.transcribe(wavData, filename, apiKey, language);
    }

    @Override
    public String getName() {
        return "nexara";
    }
}
