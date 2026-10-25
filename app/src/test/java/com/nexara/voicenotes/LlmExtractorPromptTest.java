package com.nexara.voicenotes;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * Тесты системного промпта LLM.
 *
 * Промпт — это контракт между LLM и парсером. Если имена полей в промпте
 * не совпадают с тем, что читает parseResponse(), данные теряются (Баг #1).
 * Если промпт не упоминает локальный часовой пояс, LLM может использовать
 * Moscow time (Баг #5).
 */
public class LlmExtractorPromptTest {

    @Test
    public void prompt_containsEventTimeIsoField() {
        // Баг #1: промпт просил event_time_iso, парсер читал due_iso
        // Теперь оба поддерживаются, но промпт ДОЛЖЕН использовать event_time_iso
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен содержать event_time_iso", prompt.contains("event_time_iso"));
    }

    @Test
    public void prompt_containsAllTypeValues() {
        String prompt = LlmExtractor.buildSystemPrompt();
        for (String t : new String[]{"note", "idea", "list", "reminder", "task"}) {
            assertTrue("промпт должен упоминать тип " + t, prompt.contains(t));
        }
    }

    @Test
    public void prompt_containsRemindOffsetMin() {
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен содержать remind_offset_min", prompt.contains("remind_offset_min"));
    }

    @Test
    public void prompt_containsItemsField() {
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен содержать items", prompt.contains("items"));
    }

    @Test
    public void prompt_explicitlyNoTimezoneOffset() {
        // Баг #5: LLM не должен добавлять смещение UTC
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен явно просить БЕЗ смещения пояса",
            prompt.contains("БЕЗ смещения часового пояса") || prompt.contains("БЕЗ смещения UTC"));
    }

    @Test
    public void prompt_usesLocalTime() {
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен упоминать локальное время",
            prompt.contains("локальном времени") || prompt.contains("локальный"));
    }

    @Test
    public void prompt_containsCurrentDateTime() {
        // Промпт должен включать текущую дату/время для "завтра", "через 2 часа"
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен содержать 'Сейчас:'", prompt.contains("Сейчас:"));
        assertTrue("промпт должен содержать год 2026", prompt.contains("2026"));
    }

    @Test
    public void prompt_containsExamples() {
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен иметь пример reminder", prompt.contains("Монтаж"));
        assertTrue("промпт должен иметь пример task", prompt.contains("Купить хлеб"));
        assertTrue("промпт должен иметь пример list", prompt.contains("Список дел"));
    }

    @Test
    public void prompt_requestsJsonOnly() {
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("промпт должен просить строго JSON", prompt.contains("строго JSON"));
        assertTrue("промпт должен запрещать markdown", prompt.contains("без markdown"));
    }

    @Test
    public void prompt_containsOffsetExamples() {
        String prompt = LlmExtractor.buildSystemPrompt();
        assertTrue("должен быть пример 'за час → 60'", prompt.contains("60"));
        assertTrue("должен быть пример 'за 15 минут → 15'", prompt.contains("15"));
        assertTrue("должен быть пример 'за полчаса → 30'", prompt.contains("30"));
    }
}
