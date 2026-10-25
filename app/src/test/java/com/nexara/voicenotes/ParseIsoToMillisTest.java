package com.nexara.voicenotes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * Золотые тесты для RelayService.parseIsoToMillis().
 *
 * Баг #5: LLM возвращал наивные даты (без смещения UTC), а парсер
 * интерпретировал их как UTC. Теперь наивные даты парсятся как ЛОКАЛЬНОЕ
 * время устройства.
 *
 * Тестируются:
 * - Наивные даты (без смещения) → локальное время
 * - Даты со смещением (+03:00, -05:00) → точный UTC момент
 * - Z-суффикс → UTC
 * - Без секунд → дописываются :00
 * - Невалидный ввод → fallback на текущее время
 * - Разные часовые пояса устройства
 */
public class ParseIsoToMillisTest {

    private TimeZone originalTz;

    @Before
    public void setUp() {
        originalTz = TimeZone.getDefault();
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(originalTz);
    }

    /**
     * Наивная дата "2026-10-24T16:00:00" в UTC+3 →
     * 16:00 локального = 13:00 UTC = epoch для 2026-10-24T13:00:00Z
     */
    @Test
    public void naiveDate_interpretedAsLocalTime_utcPlus3() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow")); // UTC+3
        long millis = RelayService.parseIsoToMillis("2026-10-24T16:00:00");
        // 2026-10-24T16:00:00+03:00 = 2026-10-24T13:00:00Z
        long expected = java.time.LocalDateTime.parse("2026-10-24T16:00:00")
            .atZone(java.time.ZoneId.of("Europe/Moscow"))
            .toInstant().toEpochMilli();
        assertEquals(expected, millis);
    }

    /**
     * Та же наивная дата в UTC+5 →
     * 16:00 локального = 11:00 UTC → другой epoch
     */
    @Test
    public void naiveDate_differentTimezone_differentMillis() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg")); // UTC+5
        long millisUtc5 = RelayService.parseIsoToMillis("2026-10-24T16:00:00");

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow")); // UTC+3
        long millisUtc3 = RelayService.parseIsoToMillis("2026-10-24T16:00:00");

        assertNotEquals("наивная дата в разных поясах должна давать разный epoch",
            millisUtc5, millisUtc3);

        // Разница должна быть ровно 2 часа
        assertEquals(2 * 3600 * 1000L, millisUtc3 - millisUtc5);
    }

    /**
     * Дата со смещением +03:00 → точный UTC момент, независим от пояса устройства
     */
    @Test
    public void offsetDate_independentOfDeviceTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        long ms1 = RelayService.parseIsoToMillis("2026-10-24T16:00:00+03:00");

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg"));
        long ms2 = RelayService.parseIsoToMillis("2026-10-24T16:00:00+03:00");

        assertEquals("дата со смещением не зависит от пояса устройства", ms1, ms2);
    }

    /**
     * Z-суффикс = UTC
     */
    @Test
    public void zSuffix_treatedAsUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        long msZ = RelayService.parseIsoToMillis("2026-10-24T13:00:00Z");
        long msOffset = RelayService.parseIsoToMillis("2026-10-24T13:00:00+00:00");

        assertEquals("Z и +00:00 должны давать одинаковый результат", msZ, msOffset);
    }

    /**
     * Без секунд: "2026-10-24T09:00" → дописывается :00
     */
    @Test
    public void missingSeconds_appended() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        long msShort = RelayService.parseIsoToMillis("2026-10-24T09:00");
        long msFull = RelayService.parseIsoToMillis("2026-10-24T09:00:00");

        assertEquals("короткий формат должен дополняться секундами", msFull, msShort);
    }

    /**
     * Null → текущее время (fallback)
     */
    @Test
    public void nullInput_returnsCurrentTime() {
        long before = System.currentTimeMillis();
        long result = RelayService.parseIsoToMillis(null);
        long after = System.currentTimeMillis();
        assertTrue("null должен вернуть ~текущее время",
            result >= before && result <= after);
    }

    /**
     * Пустая строка → текущее время (fallback)
     */
    @Test
    public void emptyInput_returnsCurrentTime() {
        long before = System.currentTimeMillis();
        long result = RelayService.parseIsoToMillis("");
        long after = System.currentTimeMillis();
        assertTrue("пустая строка должна вернуть ~текущее время",
            result >= before && result <= after);
    }

    /**
     * Невалидный формат → fallback на текущее время (не бросает исключение)
     */
    @Test
    public void invalidInput_doesNotThrow() {
        long before = System.currentTimeMillis();
        long result = RelayService.parseIsoToMillis("not-a-date-at-all");
        long after = System.currentTimeMillis();
        assertTrue("невалидный ввод должен вернуть ~текущее время",
            result >= before && result <= after);
    }

    /**
     * Дата только с пробелами вокруг → trim работает
     */
    @Test
    public void whitespaceInput_trimmed() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        long ms = RelayService.parseIsoToMillis("  2026-10-24T16:00:00  ");
        long expected = java.time.LocalDateTime.parse("2026-10-24T16:00:00")
            .atZone(java.time.ZoneId.of("Europe/Moscow"))
            .toInstant().toEpochMilli();
        assertEquals(expected, ms);
    }

    /**
     * Отрицательное смещение (-05:00) — для пользователей в Америке
     */
    @Test
    public void negativeOffset_parsedCorrectly() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        long ms = RelayService.parseIsoToMillis("2026-10-24T16:00:00-05:00");
        // 16:00-05:00 = 21:00 UTC = 2026-10-25T00:00:00+03:00 (Moscow)
        long expected = java.time.OffsetDateTime.parse("2026-10-24T16:00:00-05:00")
            .toInstant().toEpochMilli();
        assertEquals(expected, ms);
    }

    /**
     * Конкретный сценарий Бага #5:
     * "завтра в 9 утра" → LLM возвращает 09:00 наивную
     * Пользователь в UTC+5 → событие должно быть в 09:00 локального = 04:00 UTC
     * Раньше парсило как 09:00 UTC = 14:00 локального (смещение +5 часов!)
     */
    @Test
    public void bug5_scenario_nineAmInUtcPlus5() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg")); // UTC+5
        long millis = RelayService.parseIsoToMillis("2026-10-24T09:00:00");

        // Должно быть 09:00 локального = 04:00 UTC
        java.time.ZonedDateTime expectedLocal = java.time.LocalDateTime.parse("2026-10-24T09:00:00")
            .atZone(java.time.ZoneId.of("Asia/Yekaterinburg"));
        assertEquals(expectedLocal.toInstant().toEpochMilli(), millis);

        // Проверяем что это НЕ 09:00 UTC (что было бы 14:00 локального)
        long nineUtc = java.time.OffsetDateTime.parse("2026-10-24T09:00:00+00:00")
            .toInstant().toEpochMilli();
        assertNotEquals("не должно быть 09:00 UTC (старый баг)", nineUtc, millis);
    }
}
