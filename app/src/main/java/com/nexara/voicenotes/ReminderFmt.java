package com.nexara.voicenotes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Чистый форматтер времени события для чипа в списке заметок.
 *
 * Возвращает относительную форму для близких дней (Сегодня/Завтра/Вчера)
 * и абсолютную для дальних («25 окт, 16:00», «25 окт 2027, 16:00»).
 * Чистая функция от (eventMillis, now) — покрывается unit-тестами без
 * Android и без реального времени.
 *
 * Русские сокращения месяцев захардкожены — локаль устройства не влияет,
 * UI «Потока» всегда русский.
 */
public final class ReminderFmt {

    private static final String[] MONTHS_SHORT = {
        "янв", "фев", "мар", "апр", "мая", "июн",
        "июл", "авг", "сен", "окт", "ноя", "дек"
    };

    private ReminderFmt() {}

    /**
     * @param eventMillis epoch ms времени события
     * @param now         текущее время (передаётся для тестов)
     * @return «Сегодня 16:00», «Завтра 09:00», «25 окт, 16:00», «25 окт 2027, 16:00»
     */
    public static String format(long eventMillis, long now) {
        ZoneId tz = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(tz).toLocalDate();
        LocalDate event = Instant.ofEpochMilli(eventMillis).atZone(tz).toLocalDate();
        LocalTime t = Instant.ofEpochMilli(eventMillis).atZone(tz).toLocalTime();
        String time = formatTime(t);

        long days = ChronoUnit.DAYS.between(today, event);
        if (days == 0) return "Сегодня " + time;
        if (days == 1) return "Завтра " + time;
        if (days == -1) return "Вчера " + time;

        String md = event.getDayOfMonth() + " " + monthShort(event.getMonthValue());
        if (event.getYear() == today.getYear()) return md + ", " + time;
        return md + " " + event.getYear() + ", " + time;
    }

    /** Сокращение для продакшена — текущее время подставляется само. */
    public static String format(long eventMillis) {
        return format(eventMillis, System.currentTimeMillis());
    }

    /**
     * Человекочитаемое описание смещения напоминания: «за 1 ч», «за 30 мин», «за день».
     * null/0 → пустая строка (без смещения).
     */
    public static String offsetLabel(Integer remindOffsetMin) {
        if (remindOffsetMin == null || remindOffsetMin <= 0) return "";
        int m = remindOffsetMin;
        if (m % 1440 == 0) {
            int d = m / 1440;
            return "за " + d + (d == 1 ? " день" : (d < 5 ? " дня" : " дней"));
        }
        if (m % 60 == 0) {
            int h = m / 60;
            return "за " + h + (h == 1 ? " ч" : " ч");
        }
        return "за " + m + " мин";
    }

    private static String formatTime(LocalTime t) {
        // Всегда 2-значные часы и минуты — стандартное 24-часовое отображение
        // («09:00», «16:30», «00:00»).
        return String.format(java.util.Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String monthShort(int month) {
        return MONTHS_SHORT[month - 1];
    }
}
