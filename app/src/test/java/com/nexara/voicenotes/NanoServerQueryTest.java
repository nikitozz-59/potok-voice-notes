package com.nexara.voicenotes;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Тесты разбора query-строки для /sync пагинации (NanoServer.parseQuery/pathNoQuery).
 *
 * Чистые static-хелперы без Android-зависимостей.
 */
public class NanoServerQueryTest {

    @Test
    public void pathNoQuery_plainPath() {
        assertEquals("/sync", NanoServer.pathNoQuery("/sync"));
    }

    @Test
    public void pathNoQuery_stripsQuery() {
        assertEquals("/sync", NanoServer.pathNoQuery("/sync?since=123&limit=40"));
        assertEquals("/ping", NanoServer.pathNoQuery("/ping?x=1"));
    }

    @Test
    public void pathNoQuery_noQueryChar_unchanged() {
        assertEquals("/transcribe", NanoServer.pathNoQuery("/transcribe"));
    }

    @Test
    public void parseQuery_simple() {
        Map<String, String> q = NanoServer.parseQuery("/sync?since=1700000000000&limit=40");
        assertEquals("1700000000000", q.get("since"));
        assertEquals("40", q.get("limit"));
        assertEquals(2, q.size());
    }

    @Test
    public void parseQuery_noQuery_returnsEmpty() {
        assertTrue(NanoServer.parseQuery("/sync").isEmpty());
    }

    @Test
    public void parseQuery_unknownOtherParamsIgnored() {
        // Сервер читает только since/limit; посторонние параметры не мешают.
        Map<String, String> q = NanoServer.parseQuery("/sync?a=1&b=2");
        assertEquals("1", q.get("a"));
        assertEquals(2, q.size());
    }

    @Test
    public void parseQuery_urlDecoded() {
        Map<String, String> q = NanoServer.parseQuery("/sync?since=abc%20def");
        assertEquals("abc def", q.get("since"));
    }

    @Test
    public void parseQuery_emptyAfterQuestion() {
        assertTrue(NanoServer.parseQuery("/sync?").isEmpty());
    }

    @Test
    public void parseQuery_equalsInValue() {
        // Парсится по первому '='; остальные '=' остаются в значении.
        Map<String, String> q = NanoServer.parseQuery("/sync?since=1&limit=4=0");
        assertEquals("1", q.get("since"));
        assertEquals("4=0", q.get("limit"));
    }
}
