package com.nexara.voicenotes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Generic OpenAI-compatible STT-провайдер (Whisper).
 *
 * Работает с любым endpoint формата OpenAI /v1/audio/transcriptions:
 * - OpenAI (api.openai.com)
 * - Groq (api.groq.com)
 * - DeepInfra, Together AI, и т.д.
 *
 * Тот же multipart format, 3 retry с backoff для транзиентных ошибок.
 */
public class OpenAiSttProvider implements SttProvider {
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    /**
     * @param baseUrl  например "https://api.openai.com/v1" (без /audio/transcriptions)
     * @param apiKey   API ключ
     * @param model    модель, например "whisper-1" или "whisper-large-v3"
     */
    public OpenAiSttProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String transcribe(byte[] wavData, String filename, String language) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return transcribeOnce(wavData, filename, language);
            } catch (Exception e) {
                last = e;
                String m = String.valueOf(e.getMessage()).toLowerCase();
                boolean retryable = m.contains("abort") || m.contains("reset")
                        || m.contains("timed out") || m.contains("timeout")
                        || m.contains("stream closed") || m.contains("eof")
                        || m.contains("unable to resolve") || m.contains("failed to connect")
                        || m.contains("http 5");
                if (!retryable || attempt == 3) throw e;
                try { Thread.sleep(800L * attempt); } catch (InterruptedException ie) { break; }
            }
        }
        throw last;
    }

    private String transcribeOnce(byte[] wav, String filename, String language) throws Exception {
        String boundary = "----vn" + System.currentTimeMillis();
        String endpoint = baseUrl + "/audio/transcriptions";
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);

            OutputStream os = c.getOutputStream();
            writePart(os, boundary, "file", filename, "application/octet-stream", wav);
            writeField(os, boundary, "model", model);
            writeField(os, boundary, "response_format", "json");
            if (language != null && !language.isEmpty()) {
                writeField(os, boundary, "language", language);
            }
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = new String(readAll(is), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new Exception("STT HTTP " + code + ": " + body.substring(0, Math.min(body.length(), 120)));
            }
            JSONObject o = new JSONObject(body);
            return o.optString("text", "");
        } finally {
            c.disconnect();
        }
    }

    @Override
    public String getName() {
        return "openai-whisper (" + model + ")";
    }

    // --- multipart helpers (аналогичны NexaraClient) ---

    private static void writePart(OutputStream os, String b, String name, String fn, String ct, byte[] data) throws Exception {
        os.write(("--" + b + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fn + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Type: " + ct + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeField(OutputStream os, String b, String name, String val) throws Exception {
        os.write(("--" + b + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(val.getBytes(StandardCharsets.UTF_8));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
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
