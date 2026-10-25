package com.nexara.voicenotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Тесты интерпретатора команд: локальные правила (без сети) и разбор JSON от LLM.
 */
public class AppCommandParserTest {

    // ---------- локальные правила ----------

    @Test
    public void resetPhrasesReturnReset() {
        String[] phrases = {
            "верни как было",
            "сбрось",
            "стандарт",
            "верни стандарт",
            "по умолчанию",
            "обычный",
        };
        for (String p : phrases) {
            AppCommandParser.Command c = AppCommandParser.tryFastRules(p);
            assertNotNull("не распознано: " + p, c);
            assertTrue("ожидался reset для: " + p, c.isReset());
        }
    }

    @Test
    public void namedColorsResolve() {
        assertEquals(0xFFFF6D00, color(tryRules("сделай акцент оранжевым")));
        assertEquals(0xFFFF6D00, color(tryRules("оранжевый")));
        assertEquals(0xFF7C4DFF, color(tryRules("хочу фиолетовый")));
        assertEquals(0xFF7C4DFF, color(tryRules("лаванда")));
        assertEquals(0xFF2E7D32, color(tryRules("сделай зелёным")));
        assertEquals(0xFF37474F, color(tryRules("графит")));
        assertEquals(0xFFC8A165, color(tryRules("песочный")));
        assertEquals(0xFF039BE5, color(tryRules("небесный")));
    }

    @Test
    public void explicitHexWins() {
        AppCommandParser.Command c = tryRules("поставь #AB12EF пожалуйста");
        assertNotNull(c);
        assertTrue(c.isTheme());
        assertEquals(0xFFAB12EF, c.color);
    }

    @Test
    public void unknownPhraseFallsThroughToLlm() {
        // должно вернуть null — тогда подключается LLM
        assertEquals(null, AppCommandParser.tryFastRules("сделай поспокойнее"));
    }

    // ---------- разбор ответа LLM ----------

    @Test
    public void parsesCleanJson() {
        AppCommandParser.Command c = AppCommandParser.parseJson(
            "{\"do\":\"theme\",\"accent\":\"#FF6D00\"}");
        assertNotNull(c);
        assertTrue(c.isTheme());
        assertEquals(0xFFFF6D00, c.color);
    }

    @Test
    public void parsesJsonInsideMarkdownFence() {
        AppCommandParser.Command c = AppCommandParser.parseJson(
            "```json\n{\"do\":\"theme\",\"accent\":\"#7C4DFF\"}\n```");
        assertNotNull(c);
        assertEquals(0xFF7C4DFF, c.color);
    }

    @Test
    public void parsesShortHex() {
        AppCommandParser.Command c = AppCommandParser.parseJson(
            "{\"do\":\"theme\",\"accent\":\"#f80\"}");
        assertNotNull(c);
        assertEquals(0xFFFF8800, c.color);
    }

    @Test
    public void parsesReset() {
        AppCommandParser.Command c = AppCommandParser.parseJson("{\"do\":\"reset\"}");
        assertNotNull(c);
        assertTrue(c.isReset());
    }

    @Test
    public void parsesWarn() {
        AppCommandParser.Command c = AppCommandParser.parseJson(
            "{\"do\":\"warn\",\"message\":\"это не про оформление\"}");
        assertNotNull(c);
        assertTrue(c.isWarn());
        assertEquals("это не про оформление", c.message);
    }

    @Test
    public void rejectsGarbage() {
        assertEquals(null, AppCommandParser.parseJson("не знаю"));
        assertEquals(null, AppCommandParser.parseJson(null));
        assertEquals(null, AppCommandParser.parseJson("{\"do\":\"theme\"}"));
        assertEquals(null, AppCommandParser.parseJson("{\"do\":\"teleport\"}"));
    }

    // ---------- защита смысловых цветов ----------

    @Test
    public void reservedColorsNotOverwritten() {
        assertTrue(AppMutator.isReserved(0xFF00E676)); // зелёный записи
        assertTrue(AppMutator.isReserved(0xFFFF1744)); // красный стоп
        assertTrue(!AppMutator.isReserved(0xFF2C6FE8)); // синий — можно
        assertTrue(!AppMutator.isReserved(0xFFFF6D00)); // оранжевый — можно
    }

    @Test
    public void hexFormatting() {
        assertEquals("#2C6FE8", AppMutator.hex(0xFF2C6FE8));
        assertEquals("#000000", AppMutator.hex(0xFF000000));
    }

    // ---------- помощники ----------

    private static AppCommandParser.Command tryRules(String p) {
        return AppCommandParser.tryFastRules(p);
    }

    private static int color(AppCommandParser.Command c) {
        assertNotNull("команда не распознана", c);
        assertTrue("ожидалась theme", c.isTheme());
        return c.color;
    }
}
