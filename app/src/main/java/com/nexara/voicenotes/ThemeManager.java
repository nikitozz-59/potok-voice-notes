package com.nexara.voicenotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

/**
 * Единственный источник цвета в приложении.
 *
 * Раньше цвета были захардкожены по всем Activity (25+ литералов).
 * Теперь всё берётся отсюда: пользователь меняет акцент в системной
 * заметке «Оформление», и смена подхватывается при пересоздании экранов.
 *
 * Инварианты палитры:
 * - зелёный НИКОГДА не теряется как индикатор записи (он отдельный, REC_GREEN);
 * - красный «стоп» тоже неприкосновенен (STOP_RED);
 * - от выбранного акцента зависят его светлая подложка и «нажатый» тон.
 */
public class ThemeManager {

    public static final String PREFS = "vn";
    public static final String KEY_ACCENT = "theme_accent";

    /** Базовый акцент палитры B. */
    public static final int DEFAULT_ACCENT = 0xFF2C6FE8;

    /** Индикатор записи — зелёный, не участвует в теме. */
    public static final int REC_GREEN = 0xFF00E676;
    /** Стоп/удаление — красный, не участвует в теме. */
    public static final int STOP_RED = 0xFFFF1744;

    public static final int TEXT_PRIMARY = 0xFF121212;
    public static final int TEXT_SECONDARY = 0xFF8E939A;
    public static final int BG_CARD = 0xFFFFFFFF;

    /** Кураторские варианты первого ряда — проверенные сочетания. */
    public static final int[] PRESETS = {
        0xFF2C6FE8, // синий (по умолчанию)
        0xFF7C4DFF, // фиолетовый — в тон иконке
        0xFFFF6D00, // закат
        0xFF00897B, // хвоя
        0xFF37474F, // графит
    };
    public static final String[] PRESET_NAMES = {
        "Синий", "Лаванда", "Закат", "Хвоя", "Графит",
    };

    private ThemeManager() {}

    // ---------- чтение / запись ----------

    public static int accent(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getInt(KEY_ACCENT, DEFAULT_ACCENT);
    }

    public static void setAccent(Context ctx, int color) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACCENT, color & 0xFFFFFFFF).apply();
    }

    public static boolean isDefault(Context ctx) {
        return accent(ctx) == DEFAULT_ACCENT;
    }

    // ---------- производные цвета ----------

    /** Светлая подложка под акцент (для бейджей и карточки календаря). */
    public static int softBg(Context ctx) {
        return blend(accent(ctx), Color.WHITE, 0.10f);
    }

    /** Контур для светлой подложки. */
    public static int softBorder(Context ctx) {
        return blend(accent(ctx), Color.WHITE, 0.28f);
    }

    /** Затемнённый акцент для нажатого состояния. */
    public static int accentPressed(Context ctx) {
        return darken(accent(ctx), 0.18f);
    }

    /** Тёмный акцент для статус-бара. */
    public static int accentDark(Context ctx) {
        return darken(accent(ctx), 0.22f);
    }

    /** Читаемый цвет текста поверх акцентной заливки (белый или чёрный). */
    public static int onAccent(Context ctx) {
        return onColor(accent(ctx));
    }

    /** То же для произвольного цвета фона (для цветных облачко обработчиков). */
    public static int onColor(int bg) {
        return luminance(bg) > 0.6 ? 0xFF121212 : 0xFFFFFFFF;
    }

    // ---------- фабрики фонов ----------

    /** Заливка выбранного чипа / акцентной кнопки. */
    public static GradientDrawable accentFill(Context ctx, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(accent(ctx));
        d.setCornerRadius(dip(ctx, radiusDp));
        return d;
    }

    /** Акцентная кнопка с нажатым состоянием. */
    public static StateListDrawable accentButton(Context ctx, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setColor(accentPressed(ctx));
        pressed.setCornerRadius(dip(ctx, radiusDp));

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setColor(accent(ctx));
        normal.setCornerRadius(dip(ctx, radiusDp));

        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[0], normal);
        return s;
    }

    /** Белая плашка с тонким контуром (невыбранный чип). */
    public static GradientDrawable outlineChip(Context ctx, float radiusDp, int borderColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(BG_CARD);
        d.setCornerRadius(dip(ctx, radiusDp));
        d.setStroke(Math.max(1, (int) dip(ctx, 1f)), borderColor);
        return d;
    }

    /** Белая карточка со скруглением (без контура). */
    public static GradientDrawable card(Context ctx, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(BG_CARD);
        d.setCornerRadius(dip(ctx, radiusDp));
        return d;
    }

    // ---------- помощники ----------

    public static int dip(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Применить тему к уже созданным вьюхам (вызывать в onCreate/onResume). */
    public static void applyToWindow(android.app.Activity a) {
        if (a.getWindow() == null) return;
        a.getWindow().setStatusBarColor(accentDark(a));
    }

    /** Перекрасить чипы фильтров: выбранный — акцентом, остальные — контурные. */
    public static void styleChip(Context ctx, TextView chip, boolean selected) {
        float density = ctx.getResources().getDisplayMetrics().density;
        if (selected) {
            chip.setBackground(accentFill(ctx, 14));
            chip.setTextColor(onAccent(ctx));
        } else {
            chip.setBackground(outlineChip(ctx, 14, 0xFFE0E0E0));
            chip.setTextColor(TEXT_PRIMARY);
            chip.setPadding((int) (16 * density), (int) (8 * density),
                            (int) (16 * density), (int) (8 * density));
        }
    }

    static int blend(int fg, int bg, float fgWeight) {
        float w = Math.max(0f, Math.min(1f, fgWeight));
        int r = (int) (Color.red(fg) * w + Color.red(bg) * (1 - w));
        int g = (int) (Color.green(fg) * w + Color.green(bg) * (1 - w));
        int b = (int) (Color.blue(fg) * w + Color.blue(bg) * (1 - w));
        return Color.rgb(clamp(r), clamp(g), clamp(b));
    }

    static int darken(int c, float amount) {
        float k = Math.max(0f, Math.min(1f, 1f - amount));
        return Color.rgb(clamp((int) (Color.red(c) * k)),
                         clamp((int) (Color.green(c) * k)),
                         clamp((int) (Color.blue(c) * k)));
    }

    static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
