package com.nexara.voicenotes;

/**
 * Чистая текстовая логика для чата (без Android-зависимостей).
 *
 * - wrapCn: перенос строки по слову (приблизительно по символьной ширине),
 *   чтобы пузырь сообщения оставался компактным (не растягивался на всю ширину).
 * - truncateCn: безопасная обрезка длинного контекста/примера с многоточием.
 *
 * Вынесено из ChatActivity для юнит-тестирования.
 */
public final class ChatText {

    private ChatText() {}

    /**
     * Переносит текст по слову: вставляет '\n' каждые {@code width} символов,
     * уважая уже существующие переводы строк. Не добавляет висящую '\n' в конце.
     *
     * @param s     исходный текст (может быть null)
     * @param width максимальная длина строки (>= 1)
     */
    public static String wrapCn(String s, int width) {
        if (s == null) return "";
        if (width < 1) width = 1;
        StringBuilder sb = new StringBuilder(s.length() + s.length() / width + 1);
        int line = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                line = 0;
                sb.append('\n');
                continue;
            }
            sb.append(c);
            line++;
            // Переносим, только если дальше есть ещё символы и следующий не '\n'
            // (иначе существующий '\n' итак разорвёт строку — избегаем двойного переноса).
            if (line >= width && i + 1 < s.length() && s.charAt(i + 1) != '\n') {
                sb.append('\n');
                line = 0;
            }
        }
        return sb.toString();
    }

    /**
     * Обрезает строку до {@code n} символов, добавляя '…' на месте обрезки.
     * Если строка короче или равна n — возвращается как есть.
     *
     * @param s исходная строка (может быть null)
     * @param n максимальная длина (>= 1)
     */
    public static String truncateCn(String s, int n) {
        if (s == null) return "";
        if (n < 1) n = 1;
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
