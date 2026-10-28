package com.nexara.voicenotes;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Тесты классификации ошибок RetryPolicy.
 *
 * Главное правило: unknown ошибка → transient (ретраим, не теряем заметку).
 * Permanent — только уверенные случаи: нет ключа, auth, файл ушёл, LLM мусор.
 */
public class RetryPolicyTest {

    // === PERMANENT ===

    @Test
    public void permanent_noSttKey() {
        assertTrue(RetryPolicy.isPermanent("no STT key configured"));
    }

    @Test
    public void permanent_noLlmKey() {
        assertTrue(RetryPolicy.isPermanent("no LLM key configured"));
    }

    @Test
    public void permanent_audioFileMissing() {
        assertTrue(RetryPolicy.isPermanent("audio file missing: /data/.../note_123.wav"));
    }

    @Test
    public void permanent_http401() {
        assertTrue(RetryPolicy.isPermanent("STT HTTP 401: Unauthorized"));
        assertTrue(RetryPolicy.isPermanent("LLM HTTP 401: {\"error\":\"invalid api key\"}"));
    }

    @Test
    public void permanent_http403() {
        assertTrue(RetryPolicy.isPermanent("HTTP 403: Forbidden"));
    }

    @Test
    public void permanent_http400() {
        assertTrue(RetryPolicy.isPermanent("LLM HTTP 400: bad request"));
    }

    @Test
    public void permanent_invalidApiKey() {
        assertTrue(RetryPolicy.isPermanent("Invalid API key provided"));
        assertTrue(RetryPolicy.isPermanent("incorrect api key"));
    }

    @Test
    public void permanent_noChoices() {
        assertTrue(RetryPolicy.isPermanent("LLM: no choices in response"));
    }

    @Test
    public void permanent_emptyTranscript() {
        assertTrue(RetryPolicy.isPermanent("empty transcript"));
    }

    @Test
    public void permanent_llmReturnedEmpty() {
        assertTrue(RetryPolicy.isPermanent("LLM returned empty/invalid: (тишина)"));
    }

    @Test
    public void permanent_silenceSummary() {
        assertTrue(RetryPolicy.isPermanent("(тишина)"));
        assertTrue(RetryPolicy.isPermanent("(ошибка разбора)"));
    }

    @Test
    public void permanent_http413_payloadTooLarge() {
        assertTrue(RetryPolicy.isPermanent("STT HTTP 413: Payload Too Large"));
    }

    // === TRANSIENT ===

    @Test
    public void transient_timeout() {
        assertTrue(RetryPolicy.isTransient("STT: connect timed out"));
        assertTrue(RetryPolicy.isTransient("timeout"));
    }

    @Test
    public void transient_http500() {
        assertTrue(RetryPolicy.isTransient("LLM HTTP 500: Internal Server Error"));
    }

    @Test
    public void transient_http502() {
        assertTrue(RetryPolicy.isTransient("HTTP 502: Bad Gateway"));
    }

    @Test
    public void transient_http503() {
        assertTrue(RetryPolicy.isTransient("relay HTTP 503: service unavailable"));
    }

    @Test
    public void transient_http429_rateLimit() {
        assertTrue(RetryPolicy.isTransient("LLM HTTP 429: Too Many Requests"));
    }

    @Test
    public void transient_connectionReset() {
        assertTrue(RetryPolicy.isTransient("Connection reset"));
        assertTrue(RetryPolicy.isTransient("broken pipe"));
    }

    @Test
    public void transient_unknownHost() {
        assertTrue(RetryPolicy.isTransient("Unknown host: api.openai.com"));
    }

    @Test
    public void transient_networkUnreachable() {
        assertTrue(RetryPolicy.isTransient("Network is unreachable"));
    }

    // === UNKNOWN → TRANSIENT (safe default) ===

    @Test
    public void unknownError_isTransient_notPermanent() {
        String err = "something weird happened";
        assertFalse(RetryPolicy.isPermanent(err));
        assertTrue(RetryPolicy.isTransient(err));
    }

    @Test
    public void nullError_isTransient_notPermanent() {
        assertFalse(RetryPolicy.isPermanent(null));
        assertTrue(RetryPolicy.isTransient(null));
    }

    @Test
    public void emptyError_isTransient_notPermanent() {
        assertFalse(RetryPolicy.isPermanent(""));
        assertTrue(RetryPolicy.isTransient(""));
    }

    // === MUTUAL EXCLUSION ===

    @Test
    public void permanentAndTransient_areMutuallyExclusive() {
        String[] all = {
            "no STT key", "HTTP 401", "timeout", "HTTP 500", "no choices",
            "connection reset", "audio file missing", "HTTP 429",
            "empty transcript", "unknown weird error", "HTTP 503"
        };
        for (String e : all) {
            boolean p = RetryPolicy.isPermanent(e);
            boolean t = RetryPolicy.isTransient(e);
            assertTrue("error '" + e + "' is both permanent and transient", !(p && t));
        }
    }
}
