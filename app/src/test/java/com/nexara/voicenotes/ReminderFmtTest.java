package com.nexara.voicenotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.Test;

/**
 * Тесты форматтера времени напоминания.
 *
 * Чистая функция от (eventMillis, now) — без Android и без реального
 * времени. Проверяет относительную форму (Сегодня/Завтра/Вчера),
 * абсолютную с/без года, полночь, смещения offsetLabel.
 */
public class ReminderFmtTest {

    private static final ZoneId TZ = ZoneId.systemDefault();

    private static long at(LocalDate d, int h, int m) {
        return d.atTime(LocalTime.of(h, m)).atZone(TZ).toInstant().toEpochMilli();
    }

    private static long at(LocalDate d) {
        return d.atStartOfDay(TZ).toInstant().toEpochMilli();
    }

    @Test
    public void todayWithTime() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        assertEquals("Сегодня 16:00", ReminderFmt.format(at(today, 16, 0), now));
    }

    @Test
    public void tomorrowWithTime() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        assertEquals("Завтра 09:00", ReminderFmt.format(at(today.plusDays(1), 9, 0), now));
    }

    @Test
    public void yesterdayWithTime() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        assertEquals("Вчера 18:30", ReminderFmt.format(at(today.minusDays(1), 18, 30), now));
    }

    @Test
    public void roundHourHasZeroMinutes() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        assertEquals("Сегодня 09:00", ReminderFmt.format(at(today, 9, 0), now));
    }

    @Test
    public void sameYearAbsoluteDate() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        // 30 ноября 2026 — не сегодня/завтра/вчера
        LocalDate event = LocalDate.of(2026, 11, 30);
        assertEquals("30 ноя, 14:00", ReminderFmt.format(at(event, 14, 0), now));
    }

    @Test
    public void differentYearIncludesYear() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        LocalDate event = LocalDate.of(2027, 1, 5);
        assertEquals("5 янв 2027, 12:00", ReminderFmt.format(at(event, 12, 0), now));
    }

    @Test
    public void midnightFormatsAsZeroZero() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        long now = at(today, 10, 0);
        assertEquals("Завтра 00:00", ReminderFmt.format(at(today.plusDays(1), 0, 0), now));
    }

    @Test
    public void offsetNullIsEmpty() {
        assertEquals("", ReminderFmt.offsetLabel(null));
    }

    @Test
    public void offsetZeroIsEmpty() {
        assertEquals("", ReminderFmt.offsetLabel(0));
    }

    @Test
    public void offsetMinutes() {
        assertEquals("за 30 мин", ReminderFmt.offsetLabel(30));
        assertEquals("за 15 мин", ReminderFmt.offsetLabel(15));
    }

    @Test
    public void offsetHours() {
        assertEquals("за 1 ч", ReminderFmt.offsetLabel(60));
        assertEquals("за 2 ч", ReminderFmt.offsetLabel(120));
    }

    @Test
    public void offsetDays() {
        assertEquals("за 1 день", ReminderFmt.offsetLabel(1440));
        assertEquals("за 2 дня", ReminderFmt.offsetLabel(2880));
        assertEquals("за 5 дней", ReminderFmt.offsetLabel(5 * 1440));
    }

    @Test
    public void allTwelveMonthsCovered() {
        // Проверяем, что каждый месяц форматируется корректным сокращением.
        // today = 1 января, event = 15-е каждого месяца того же года →
        // абсолютная форма «15 <месяц>, 12:00».
        LocalDate today = LocalDate.of(2026, 1, 1);
        long now = at(today, 10, 0);
        String[] expected = {"янв", "фев", "мар", "апр", "мая", "июн",
                             "июл", "авг", "сен", "окт", "ноя", "дек"};
        for (int mo = 1; mo <= 12; mo++) {
            LocalDate event = LocalDate.of(2026, mo, 15);
            String out = ReminderFmt.format(at(event, 12, 0), now);
            // «15 янв, 12:00» → пробел перед сокращением месяца
            assertTrue("month " + mo + " output: " + out,
                out.contains(" " + expected[mo - 1]));
        }
    }
}
