package com.nexara.voicenotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Экран «Обработчики» — создание и правка пользовательских LLM-ролей.
 *
 * Список существующих + кнопка «новый». Тап по карточке — редактировать,
 * корзина — удалить. Форма: название + рамки (роль). Без пресетов,
 * пользователь пишет свободно.
 *
 * Открывается из Настроек. Облачка этих обработчиков появляются в экране
 * заметки (ThoughtDetailActivity).
 */
public class ProcessorsActivity extends Activity {

    private ProcessorStore store;
    private LinearLayout listContainer;
    private TextView emptyHint;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        ThemeManager.applyToWindow(this);
        store = new ProcessorStore(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F9FC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(32));

        // шапка
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(32);
        back.setTextColor(ThemeManager.TEXT_PRIMARY);
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("Обработчики");
        title.setTextSize(20);
        title.setTextColor(ThemeManager.TEXT_PRIMARY);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);
        root.addView(header);

        TextView desc = new TextView(this);
        desc.setText("Свои роли для разбора заметок. Назовите и опишите рамки — "
                   + "например «Финаналитик»: «ты серьёзный финансовый аналитик, "
                   + "оцени с точки зрения выгоды и заработка».");
        desc.setTextSize(13);
        desc.setTextColor(ThemeManager.TEXT_SECONDARY);
        desc.setPadding(0, 0, 0, dp(14));
        root.addView(desc);

        // новый обработчик
        Button addBtn = new Button(this);
        addBtn.setText("+ Новый обработчик");
        addBtn.setTextColor(0xFFFFFFFF);
        addBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        addBtn.setTextSize(14);
        addBtn.setBackground(ThemeManager.accentButton(this, 14));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        addBtn.setLayoutParams(alp);
        addBtn.setOnClickListener(v -> showEditor(null));
        root.addView(addBtn);

        // список
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(12);
        listContainer.setLayoutParams(llp);
        root.addView(listContainer);

        emptyHint = new TextView(this);
        emptyHint.setText("Пока нет обработчиков.\nСоздайте первого — он появится облачком в заметке.");
        emptyHint.setTextSize(13);
        emptyHint.setTextColor(ThemeManager.TEXT_SECONDARY);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(16), dp(24), dp(16), dp(24));
        root.addView(emptyHint);

        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderList();
    }

    private void renderList() {
        listContainer.removeAllViews();
        List<Processor> all = store.list();
        emptyHint.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);

        for (Processor p : all) {
            listContainer.addView(makeCard(p));
        }
    }

    private View makeCard(final Processor p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ThemeManager.card(this, 16));
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        // строка: цветная точка + имя + корзина
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int color = p.color != 0 ? p.color : 0xFF9E9E9E;
        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dlp.setMarginEnd(dp(10));
        dot.setLayoutParams(dlp);
        GradientDrawable dotD = new GradientDrawable();
        dotD.setShape(GradientDrawable.OVAL);
        dotD.setColor(color);
        dot.setBackground(dotD);
        row.addView(dot);

        TextView name = new TextView(this);
        name.setText(p.name);
        name.setTextSize(16);
        name.setTextColor(ThemeManager.TEXT_PRIMARY);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nlp);
        row.addView(name);

        ImageButton del = new ImageButton(this);
        del.setImageResource(android.R.drawable.ic_menu_delete);
        del.setBackground(null);
        del.setColorFilter(ThemeManager.STOP_RED);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Удалить «" + p.name + "»?")
            .setPositiveButton("Удалить", (d, w) -> { store.delete(p.id); renderList(); })
            .setNegativeButton("Отмена", null)
            .show());
        row.addView(del);

        card.addView(row);

        TextView prompt = new TextView(this);
        prompt.setText(p.prompt);
        prompt.setTextSize(13);
        prompt.setTextColor(0xFF555555);
        prompt.setPadding(dp(22), dp(6), 0, 0);
        card.addView(prompt);

        card.setOnClickListener(v -> showEditor(p));
        return card;
    }

    /** editor == null → создание, иначе правка. */
    private void showEditor(final Processor existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, dp(8), pad, dp(4));

        // выбранный цвет (int в массиве — для замыкания)
        final int[] selected = { existing != null && existing.color != 0
            ? existing.color : Processor.colorForIndex(store.list().size()) };

        // ряд плашек цвета
        TextView colorLabel = new TextView(this);
        colorLabel.setText("Цвет облачка");
        colorLabel.setTextSize(13);
        colorLabel.setTextColor(ThemeManager.TEXT_SECONDARY);
        form.addView(colorLabel);

        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        slp.bottomMargin = dp(10);
        swatches.setLayoutParams(slp);

        for (int i = 0; i < Processor.CLOUD_COLORS.length; i++) {
            final int c = Processor.CLOUD_COLORS[i];
            View sw = new View(this);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(32), dp(32));
            wlp.setMarginEnd(dp(8));
            sw.setLayoutParams(wlp);
            GradientDrawable sd = new GradientDrawable();
            sd.setShape(GradientDrawable.OVAL);
            sd.setColor(c);
            sd.setStroke(dp(2), c == selected[0] ? 0xFFFFFFFF : 0x22000000);
            sw.setBackground(sd);
            sw.setOnClickListener(v -> {
                selected[0] = c;
                // обновить обводки
                for (int k = 0; k < swatches.getChildCount(); k++) {
                    View ch = swatches.getChildAt(k);
                    int cc = Processor.CLOUD_COLORS[k];
                    GradientDrawable g = (GradientDrawable) ch.getBackground();
                    g.setStroke(dp(2), cc == selected[0] ? 0xFFFFFFFF : 0x22000000);
                }
            });
            swatches.addView(sw);
        }
        form.addView(swatches);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Название (Финаналитик)");
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameInput.setTextSize(15);
        if (existing != null) nameInput.setText(existing.name);
        form.addView(nameInput);

        final EditText promptInput = new EditText(this);
        promptInput.setHint("Рамки: ты серьёзный финансовый аналитик, оцени с точки зрения выгоды…");
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptInput.setMinLines(3);
        promptInput.setTextSize(15);
        if (existing != null) promptInput.setText(existing.prompt);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        promptInput.setLayoutParams(plp);
        form.addView(promptInput);

        String title = existing == null ? "Новый обработчик" : "Изменить";
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(form)
            .setPositiveButton("Сохранить", (d, w) -> {
                String name = nameInput.getText().toString().trim();
                String prompt = promptInput.getText().toString().trim();
                if (name.isEmpty()) return;
                if (existing == null) store.add(name, prompt, selected[0]);
                else store.update(existing.id, name, prompt, selected[0]);
                renderList();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
