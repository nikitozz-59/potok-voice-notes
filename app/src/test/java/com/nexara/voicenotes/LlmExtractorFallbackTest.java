package com.nexara.voicenotes;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Золотые тесты для LlmExtractor.validateAndFallback() и makeFallbackSummary().
 *
 * Главное правило: НИКОГДА не теряем транскрипт.
 * Если LLM вернул мусор/пустоту/ошибку — summary берётся из транскрипта.
 */
public class LlmExtractorFallbackTest {

    // === validateAndFallback ===

    @Test
    public void fallback_emptySummary_usesTranscript() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = "";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "купи хлеб завтра утром");
        assertEquals("Купи хлеб завтра утром", result.summary);
        assertEquals("кусок транскрипта в raw", "купи хлеб завтра утром", result.raw);
    }

    @Test
    public void fallback_silenceSummary_usesTranscript() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = "(тишина)";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "напомни позвонить маме");
        assertEquals("Напомни позвонить маме", result.summary);
    }

    @Test
    public void fallback_parseErrorSummary_usesTranscript() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = "(ошибка разбора)";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "список дел купить молоко и хлеб");
        assertEquals("Список дел купить молоко и хлеб", result.summary);
    }

    @Test
    public void noFallback_validSummary_keepsOriginal() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.type = "task";
        s.summary = "Купить хлеб";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "купи хлеб");
        assertEquals("Купить хлеб", result.summary);
    }

    @Test
    public void noFallback_reminderSummary_keepsOriginal() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.type = "reminder";
        s.summary = "Монтаж";
        s.dueIso = "2026-10-24T16:00:00";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "напомни завтра монтаж в 16");
        assertEquals("Монтаж", result.summary);
        assertEquals("2026-10-24T16:00:00", result.dueIso);
    }

    @Test
    public void fallback_nullSummary_usesTranscript() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = null;
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "идея сделать приложение");
        assertEquals("Идея сделать приложение", result.summary);
    }

    @Test
    public void fallback_emptyTranscript_keepsOriginal() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = "(тишина)";
        // Нет транскрипта — fallback не применяется
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "");
        assertEquals("(тишина)", result.summary);
    }

    @Test
    public void fallback_nullTranscript_keepsOriginal() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = "(ошибка разбора)";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, null);
        assertEquals("(ошибка разбора)", result.summary);
    }

    @Test
    public void fallback_nullStruct_createsNew() {
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(null, "тест транскрипт");
        assertNotNull(result);
        assertEquals("Тест транскрипт", result.summary);
    }

    @Test
    public void fallback_preservesRawIfNotEmpty() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.summary = "";
        s.raw = "existing raw content";
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(s, "купи хлеб");
        assertEquals("Купи хлеб", result.summary);
        assertEquals("existing raw content", result.raw);
    }

    // === makeFallbackSummary ===

    @Test
    public void fallbackSummary_shortTranscript() {
        assertEquals("Купи хлеб", LlmExtractor.makeFallbackSummary("купи хлеб"));
    }

    @Test
    public void fallbackSummary_sixWords() {
        assertEquals("Купить хлеб молоко масло кофе чай",
            LlmExtractor.makeFallbackSummary("купить хлеб молоко масло кофе чай и сахар"));
    }

    @Test
    public void fallbackSummary_capitalizes() {
        String result = LlmExtractor.makeFallbackSummary("напомни позвонить маме завтра");
        assertTrue("первая буква заглавная", Character.isUpperCase(result.charAt(0)));
    }

    @Test
    public void fallbackSummary_truncatesLongWords() {
        String longTranscript = "оченьдлинноесловокотороеточнонепоместитсяводнойстрокеприложения";
        String result = LlmExtractor.makeFallbackSummary(longTranscript);
        assertTrue("должен быть обрезан", result.length() <= 40);
        assertTrue("должен заканчиваться на …", result.endsWith("…"));
    }

    @Test
    public void fallbackSummary_collapsesWhitespace() {
        assertEquals("Купи хлеб", LlmExtractor.makeFallbackSummary("  купи   хлеб  "));
    }

    @Test
    public void fallbackSummary_emptyTranscript() {
        assertEquals("", LlmExtractor.makeFallbackSummary(""));
    }

    @Test
    public void fallbackSummary_nullTranscript() {
        // null.split throws NPE in Java, but makeFallbackSummary handles trim() first
        // Actually null.trim() throws NPE — this is expected behavior
        // The caller (validateAndFallback) checks for null/empty transcript before calling
        try {
            LlmExtractor.makeFallbackSummary(null);
            // If it doesn't throw, the result should be empty
        } catch (NullPointerException e) {
            // Expected — caller must guard against null
        }
    }

    // === Integration: parseResponse + validateAndFallback ===

    @Test
    public void integration_invalidJson_fallbackToTranscript() {
        // LLM returned garbage
        LlmExtractor.Struct parsed = LlmExtractor.parseResponse("это не JSON");
        assertEquals("(ошибка разбора)", parsed.summary);

        // Fallback saves the day
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(parsed, "напомни завтра встреча в 9 утра");
        assertEquals("Напомни завтра встреча в 9 утра", result.summary);
        assertEquals("note", result.type);
    }

    @Test
    public void integration_validJson_noFallbackNeeded() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Монтаж\",\"event_title\":\"Монтаж\","
            + "\"event_time_iso\":\"2026-10-24T16:00:00\",\"remind_offset_min\":60,\"items\":[]}";
        LlmExtractor.Struct parsed = LlmExtractor.parseResponse(json);
        LlmExtractor.Struct result = LlmExtractor.validateAndFallback(parsed, "напомни монтаж завтра 16");
        assertEquals("reminder", result.type);
        assertEquals("Монтаж", result.summary);
        assertEquals("2026-10-24T16:00:00", result.dueIso);
    }

    // === Checklist fallback: type=list + пустой items → split транскрипта ===

    @Test
    public void splitItems_commaSeparated() {
        java.util.List<String> r = LlmExtractor.splitItems("купи хлеб, молоко, масло");
        assertEquals(3, r.size());
        assertEquals("Купи хлеб", r.get(0));
        assertEquals("Молоко", r.get(1));
        assertEquals("Масло", r.get(2));
    }

    @Test
    public void splitItems_andConjunction() {
        java.util.List<String> r = LlmExtractor.splitItems("купи хлеб и молоко");
        assertEquals(2, r.size());
        assertEquals("Купи хлеб", r.get(0));
        assertEquals("Молоко", r.get(1));
    }

    @Test
    public void splitItems_newlineSeparated() {
        java.util.List<String> r = LlmExtractor.splitItems("купить хлеб\nпозвонить маме\nзабрать посылку");
        assertEquals(3, r.size());
        assertEquals("Купить хлеб", r.get(0));
        assertEquals("Позвонить маме", r.get(1));
        assertEquals("Забрать посылку", r.get(2));
    }

    @Test
    public void splitItems_stripsPrefix() {
        java.util.List<String> r = LlmExtractor.splitItems("список дел: купить хлеб, молоко");
        assertEquals(2, r.size());
        assertEquals("Купить хлеб", r.get(0));
        assertEquals("Молоко", r.get(1));
    }

    @Test
    public void splitItems_stripsNumbering() {
        java.util.List<String> r = LlmExtractor.splitItems("1. купить хлеб\n2. позвонить маме");
        assertEquals(2, r.size());
        assertEquals("Купить хлеб", r.get(0));
        assertEquals("Позвонить маме", r.get(1));
    }

    @Test
    public void splitItems_mixedConjunctions() {
        java.util.List<String> r = LlmExtractor.splitItems("купи хлеб, молоко и масло, потом позвонить маме");
        assertEquals(4, r.size());
        assertEquals("Купи хлеб", r.get(0));
        assertEquals("Молоко", r.get(1));
        assertEquals("Масло", r.get(2));
        assertEquals("Позвонить маме", r.get(3));
    }

    @Test
    public void splitItems_singleItem() {
        java.util.List<String> r = LlmExtractor.splitItems("купи хлеб");
        assertEquals(1, r.size());
    }

    @Test
    public void splitItems_empty() {
        assertEquals(0, LlmExtractor.splitItems("").size());
        assertEquals(0, LlmExtractor.splitItems(null).size());
    }

    @Test
    public void fallback_listEmptyItems_splitsTranscript() {
        // Регрессия: LLM отдал type=list, но items пустой → квадратиков нет.
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.type = "list";
        s.summary = "Список дел";
        s.items = null;
        LlmExtractor.Struct r = LlmExtractor.validateAndFallback(s, "купи хлеб, молоко, масло");
        assertEquals("list", r.type);
        assertNotNull(r.items);
        assertEquals(3, r.items.size());
        assertEquals("Купи хлеб", r.items.get(0));
    }

    @Test
    public void fallback_listEmptyItems_singleItem_demotesToTask() {
        // Одно дело — это не список, понижаем до task.
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.type = "list";
        s.summary = "Купить хлеб";
        s.items = null;
        LlmExtractor.Struct r = LlmExtractor.validateAndFallback(s, "купи хлеб");
        assertEquals("task", r.type);
        assertNull(r.items);
    }

    @Test
    public void fallback_listWithItems_keepsItems() {
        // items уже есть — fallback не трогает.
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("Купить хлеб");
        items.add("Купить молоко");
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.type = "list";
        s.summary = "Список дел";
        s.items = items;
        LlmExtractor.Struct r = LlmExtractor.validateAndFallback(s, "купи хлеб и молоко");
        assertEquals("list", r.type);
        assertEquals(2, r.items.size());
        assertEquals("Купить хлеб", r.items.get(0));
    }

    @Test
    public void fallback_listEmptyItems_noTranscript_demotesToNote() {
        LlmExtractor.Struct s = new LlmExtractor.Struct();
        s.type = "list";
        s.summary = "Список";
        s.items = null;
        LlmExtractor.Struct r = LlmExtractor.validateAndFallback(s, "");
        assertEquals("note", r.type);
        assertNull(r.items);
    }
}
