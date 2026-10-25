package com.nexara.voicenotes;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Золотые тесты для LlmExtractor.parseResponse().
 *
 * Тестируют парсинг JSON-ответа LLM в Struct без необходимости сети/ключей.
 * Каждая фикстура — реальный сценарий голосовой заметки.
 *
 * Покрытие:
 * - Все 5 типов: note, idea, reminder, task, list
 * - Markdown-wrapped ответы (```json ... ```)
 * - Dual field support: due_iso + event_time_iso
 * - Edge cases: пустой ответ, невалидный JSON, null поля, пустые items
 * - Auto-promotion: 2+ items → type=list
 * - Negative: "null" строка в due_iso, пустые строки в items
 */
public class LlmExtractorGoldenTest {

    // === Вспомогательные методы ===

    private void assertStruct(LlmExtractor.Struct s, String type, String summary,
                              String eventTitle, String dueIso, Integer remindOffset,
                              List<String> items) {
        assertEquals("type", type, s.type);
        assertEquals("summary", summary, s.summary);
        assertEquals("eventTitle", eventTitle, s.eventTitle);
        assertEquals("dueIso", dueIso, s.dueIso);
        assertEquals("remindOffsetMin", remindOffset, s.remindOffsetMin);
        if (items == null) {
            assertNull("items should be null", s.items);
        } else {
            assertNotNull("items should not be null", s.items);
            assertEquals("items count", items.size(), s.items.size());
            for (int i = 0; i < items.size(); i++) {
                assertEquals("item[" + i + "]", items.get(i), s.items.get(i));
            }
        }
    }

    // === REMINDER ===

    @Test
    public void reminder_withEventTimeAndOffset() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Монтаж\",\"event_title\":\"Монтаж\","
            + "\"event_time_iso\":\"2026-10-24T16:00:00\",\"remind_offset_min\":60,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Монтаж", "Монтаж", "2026-10-24T16:00:00", 60, null);
    }

    @Test
    public void reminder_viaDueIsoField() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Звонок\",\"event_title\":\"Звонок\","
            + "\"due_iso\":\"2026-10-24T09:00:00\",\"remind_offset_min\":15,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Звонок", "Звонок", "2026-10-24T09:00:00", 15, null);
    }

    @Test
    public void reminder_withoutRemindOffset() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Встреча\",\"event_title\":\"Встреча\","
            + "\"event_time_iso\":\"2026-10-25T10:00:00\",\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Встреча", "Встреча", "2026-10-25T10:00:00", null, null);
    }

    @Test
    public void reminder_withTimezoneBackwardCompat() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Дедлайн\",\"event_title\":\"Дедлайн\","
            + "\"event_time_iso\":\"2026-10-24T16:00:00+03:00\",\"remind_offset_min\":30,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Дедлайн", "Дедлайн", "2026-10-24T16:00:00+03:00", 30, null);
    }

    @Test
    public void reminder_remindOffsetZero() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Тест\",\"event_title\":\"Тест\","
            + "\"event_time_iso\":\"2026-10-24T09:00:00\",\"remind_offset_min\":0,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Тест", "Тест", "2026-10-24T09:00:00", 0, null);
    }

    // === TASK ===

    @Test
    public void task_buyBread() {
        String json = "{\"type\":\"task\",\"summary\":\"Купить хлеб\",\"event_title\":\"Купить хлеб\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "task", "Купить хлеб", "Купить хлеб", null, null, null);
    }

    @Test
    public void task_callMom() {
        String json = "{\"type\":\"task\",\"summary\":\"Позвонить маме\",\"event_title\":\"Позвонить маме\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "task", "Позвонить маме", "Позвонить маме", null, null, null);
    }

    // === LIST ===

    @Test
    public void list_shoppingList() {
        String json = "{\"type\":\"list\",\"summary\":\"Список покупок\",\"event_title\":\"Список покупок\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,"
            + "\"items\":[\"Купить хлеб\",\"Купить молоко\",\"Купить масло\",\"Купить кофе\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "list", "Список покупок", "Список покупок", null, null,
            Arrays.asList("Купить хлеб", "Купить молоко", "Купить масло", "Купить кофе"));
    }

    @Test
    public void list_todoList() {
        String json = "{\"type\":\"list\",\"summary\":\"Список дел\",\"event_title\":\"Список дел\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,"
            + "\"items\":[\"Купить хлеб\",\"Купить молоко\",\"Позвонить маме\",\"Забрать посылку\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "list", "Список дел", "Список дел", null, null,
            Arrays.asList("Купить хлеб", "Купить молоко", "Позвонить маме", "Забрать посылку"));
    }

    // === AUTO-PROMOTION ===

    @Test
    public void autoPromotion_taskWith2Items_becomesList() {
        String json = "{\"type\":\"task\",\"summary\":\"Дела\",\"event_title\":\"Дела\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,"
            + "\"items\":[\"Помыть посуду\",\"Вынести мусор\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "list", "Дела", "Дела", null, null,
            Arrays.asList("Помыть посуду", "Вынести мусор"));
    }

    @Test
    public void noPromotion_reminderWithItems_staysReminder() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Напоминание\",\"event_title\":\"Напоминание\","
            + "\"event_time_iso\":\"2026-10-24T12:00:00\",\"remind_offset_min\":null,"
            + "\"items\":[\"Пункт 1\",\"Пункт 2\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Напоминание", "Напоминание", "2026-10-24T12:00:00", null,
            Arrays.asList("Пункт 1", "Пункт 2"));
    }

    @Test
    public void noPromotion_singleItem_staysOriginalType() {
        String json = "{\"type\":\"task\",\"summary\":\"Дело\",\"event_title\":\"Дело\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,"
            + "\"items\":[\"Единственный пункт\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "task", "Дело", "Дело", null, null,
            Arrays.asList("Единственный пункт"));
    }

    // === IDEA ===

    @Test
    public void idea_notesApp() {
        String json = "{\"type\":\"idea\",\"summary\":\"Приложение для заметок\",\"event_title\":\"Приложение для заметок\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "idea", "Приложение для заметок", "Приложение для заметок", null, null, null);
    }

    @Test
    public void idea_thought() {
        String json = "{\"type\":\"idea\",\"summary\":\"Подумать об архитектуре\",\"event_title\":\"Подумать об архитектуре\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "idea", "Подумать об архитектуре", "Подумать об архитектуре", null, null, null);
    }

    // === NOTE ===

    @Test
    public void note_general() {
        String json = "{\"type\":\"note\",\"summary\":\"Встреча с командой\",\"event_title\":\"\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "note", "Встреча с командой", "", null, null, null);
    }

    @Test
    public void note_emptySummary() {
        String json = "{\"type\":\"note\",\"summary\":\"\",\"event_title\":\"\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "note", "", "", null, null, null);
    }

    // === MARKDOWN WRAPPED ===

    @Test
    public void markdown_jsonCodeFence() {
        String json = "```json\n"
            + "{\"type\":\"reminder\",\"summary\":\"Монтаж\",\"event_title\":\"Монтаж\","
            + "\"event_time_iso\":\"2026-10-24T16:00:00\",\"remind_offset_min\":60,\"items\":[]}\n"
            + "```";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Монтаж", "Монтаж", "2026-10-24T16:00:00", 60, null);
    }

    @Test
    public void markdown_codeFenceWithoutLang() {
        String json = "```\n"
            + "{\"type\":\"task\",\"summary\":\"Купить хлеб\",\"event_title\":\"Купить хлеб\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}\n"
            + "```";
        assertStruct(LlmExtractor.parseResponse(json),
            "task", "Купить хлеб", "Купить хлеб", null, null, null);
    }

    // === EDGE CASES ===

    @Test
    public void edge_unknownType_fallsToNote() {
        String json = "{\"type\":\"unknown_type\",\"summary\":\"Что-то\",\"event_title\":\"\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "note", "Что-то", "", null, null, null);
    }

    @Test
    public void edge_noTypeField_defaultsToNote() {
        String json = "{\"summary\":\"Заметка без типа\",\"event_title\":\"\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "note", "Заметка без типа", "", null, null, null);
    }

    @Test
    public void edge_dueIsoStringNull_ignored() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Тест\",\"event_title\":\"Тест\","
            + "\"due_iso\":\"null\",\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Тест", "Тест", null, null, null);
    }

    @Test
    public void edge_dueIsoEmpty_ignored() {
        String json = "{\"type\":\"reminder\",\"summary\":\"Тест\",\"event_title\":\"Тест\","
            + "\"due_iso\":\"\",\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Тест", "Тест", null, null, null);
    }

    @Test
    public void edge_itemsWithGarbage_filtered() {
        String json = "{\"type\":\"list\",\"summary\":\"Список\",\"event_title\":\"Список\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,"
            + "\"items\":[\"Хлеб\",\"\",\"null\",\" \",\"Молоко\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "list", "Список", "Список", null, null,
            Arrays.asList("Хлеб", "Молоко"));
    }

    @Test
    public void edge_extraFieldsIgnored() {
        String json = "{\"type\":\"task\",\"summary\":\"Тест\",\"event_title\":\"Тест\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[],"
            + "\"extra_field\":\"ignored\",\"another\":123}";
        assertStruct(LlmExtractor.parseResponse(json),
            "task", "Тест", "Тест", null, null, null);
    }

    // === NEGATIVE ===

    @Test
    public void negative_emptyContent() {
        LlmExtractor.Struct s = LlmExtractor.parseResponse("");
        assertStruct(s, "note", "(тишина)", "", null, null, null);
    }

    @Test
    public void negative_nullContent() {
        LlmExtractor.Struct s = LlmExtractor.parseResponse(null);
        assertStruct(s, "note", "(тишина)", "", null, null, null);
    }

    @Test
    public void negative_whitespaceOnly() {
        LlmExtractor.Struct s = LlmExtractor.parseResponse("   \n\t  ");
        assertStruct(s, "note", "(тишина)", "", null, null, null);
    }

    @Test
    public void negative_invalidJson() {
        LlmExtractor.Struct s = LlmExtractor.parseResponse("это не JSON вообще");
        assertStruct(s, "note", "(ошибка разбора)", "", null, null, null);
    }

    @Test
    public void negative_truncatedJson() {
        LlmExtractor.Struct s = LlmExtractor.parseResponse("{\"type\":\"reminder\",\"summary\":\"Тест\"");
        assertStruct(s, "note", "(ошибка разбора)", "", null, null, null);
    }

    // === РЕАЛИСТИЧНЫЕ СЦЕНАРИИ ===

    @Test
    public void realistic_remindMeTomorrowMontage() {
        // Транскрипт: "напомни завтра монтаж в 16:00, напомнить за час"
        String json = "{\"type\":\"reminder\",\"summary\":\"Монтаж\",\"event_title\":\"Монтаж\","
            + "\"event_time_iso\":\"2026-10-24T16:00:00\",\"remind_offset_min\":60,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "reminder", "Монтаж", "Монтаж", "2026-10-24T16:00:00", 60, null);
    }

    @Test
    public void realistic_todoListFourItems() {
        // Транскрипт: "список дел: купить хлеб и молоко, позвонить маме, забрать посылку"
        String json = "{\"type\":\"list\",\"summary\":\"Список дел\",\"event_title\":\"Список дел\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,"
            + "\"items\":[\"Купить хлеб\",\"Купить молоко\",\"Позвонить маме\",\"Забрать посылку\"]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "list", "Список дел", "Список дел", null, null,
            Arrays.asList("Купить хлеб", "Купить молоко", "Позвонить маме", "Забрать посылку"));
    }

    @Test
    public void realistic_quickNote() {
        // Транскрипт: "встреча с командой прошла хорошо"
        String json = "{\"type\":\"note\",\"summary\":\"Встреча с командой\",\"event_title\":\"\","
            + "\"event_time_iso\":null,\"remind_offset_min\":null,\"items\":[]}";
        assertStruct(LlmExtractor.parseResponse(json),
            "note", "Встреча с командой", "", null, null, null);
    }
}
