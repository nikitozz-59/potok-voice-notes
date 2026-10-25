package com.nexara.voicenotes;

import android.content.Context;

/**
 * Применяет разобранную команду к приложению.
 *
 * Отдельный класс от парсера: парсер отвечает «что хочет пользователь»,
 * мутатор — «что из этого реально применить». Это позволяет тестировать
 * применение без сети.
 */
public class AppMutator {

    /** Результат применения — что показать пользователю. */
    public static class Result {
        public boolean changed;
        public String message;
        public int color; // применённый (для theme), иначе -1
    }

    private AppMutator() {}

    public static Result apply(Context ctx, AppCommandParser.Command cmd) {
        Result r = new Result();
        if (cmd == null) {
            r.message = "Не понял команду";
            return r;
        }
        if (cmd.isWarn()) {
            r.message = cmd.message == null ? "Не понял команду" : cmd.message;
            return r;
        }
        if (cmd.isReset()) {
            int before = ThemeManager.accent(ctx);
            ThemeManager.setAccent(ctx, ThemeManager.DEFAULT_ACCENT);
            r.changed = before != ThemeManager.DEFAULT_ACCENT;
            r.color = ThemeManager.DEFAULT_ACCENT;
            r.message = r.changed ? "Готово: вернул стандартный синий" : "Уже стандартный синий";
            return r;
        }
        if (cmd.isTheme()) {
            int before = ThemeManager.accent(ctx);

            // Защита смысловых цветов: зелёный индикатор и красный стоп не трогаем.
            if (isReserved(cmd.color)) {
                r.message = "Этот цвет зарезервирован: зелёный — индикатор записи, "
                          + "красный — кнопка стоп. Выберите другой оттенок.";
                return r;
            }

            ThemeManager.setAccent(ctx, cmd.color);
            r.changed = before != cmd.color;
            r.color = cmd.color;
            r.message = r.changed
                ? "Готово: акцент теперь " + hex(cmd.color)
                : "Акцент уже такой";
            return r;
        }
        r.message = "Неизвестное действие";
        return r;
    }

    /** Зелёный/красный из палитры не участвуют в теме — не даём их случайно занять. */
    static boolean isReserved(int color) {
        return near(color, ThemeManager.REC_GREEN, 40) || near(color, ThemeManager.STOP_RED, 40);
    }

    static boolean near(int a, int b, int tolerance) {
        return Math.abs(red(a) - red(b)) <= tolerance
            && Math.abs(green(a) - green(b)) <= tolerance
            && Math.abs(blue(a) - blue(b)) <= tolerance;
    }

    // Битовая арифметика вместо android.graphics.Color:
    // в unit-тестах Android SDK — заглушка, Color.* возвращает нули.
    static int red(int c)   { return (c >> 16) & 0xFF; }
    static int green(int c) { return (c >> 8) & 0xFF; }
    static int blue(int c)  { return c & 0xFF; }

    public static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }
}
