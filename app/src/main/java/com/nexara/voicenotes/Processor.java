package com.nexara.voicenotes;

/**
 * Пользовательский обработчик — LLM-роль, применяемая к заметке.
 *
 * Пример: name="Финаналитик",
 * prompt="Ты серьёзный финансовый аналитик. Оцени мысль с точки зрения
 * выгоды и заработка. Назови риски и возможности."
 *
 * Хранится в ProcessorStore (SharedPreferences), не в БД заметок.
 */
public class Processor {
    public long id;
    public String name;
    public String prompt;
    /** Цвет облачка (ARGB). 0 — выбрать автоматически по индексу. */
    public int color;

    public Processor() {}

    public Processor(long id, String name, String prompt) {
        this(id, name, prompt, 0);
    }

    public Processor(long id, String name, String prompt, int color) {
        this.id = id;
        this.name = name;
        this.prompt = prompt;
        this.color = color;
    }

    /** Палитра облачко — на случай, если цвет не выбран. Без зелёного/красного записи. */
    public static final int[] CLOUD_COLORS = {
        0xFF2C6FE8, // синий
        0xFF7C4DFF, // лаванда
        0xFFFF6D00, // оранжевый
        0xFF00BFA5, // мята
        0xFFEC407A, // розовый
        0xFFF9A825, // янтарь
        0xFF5C6BC0, // индиго
        0xFF8E1F3C, // бордо
    };
    public static final String[] CLOUD_COLOR_NAMES = {
        "Синий", "Лаванда", "Оранжевый", "Мята",
        "Розовый", "Янтарь", "Индиго", "Бордо",
    };

    /** Цвет по индексу в списке — для облачко без явного цвета. */
    public static int colorForIndex(int i) {
        int n = CLOUD_COLORS.length;
        return CLOUD_COLORS[((i % n) + n) % n];
    }
}
