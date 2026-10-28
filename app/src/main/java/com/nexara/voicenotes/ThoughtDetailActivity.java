package com.nexara.voicenotes;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Экран работы с мыслью: развей, задай вопросы, найди связи, собери цепочку.
 *
 * Открывается по кнопке "Разобрать" из карточки заметки.
 * Использует LlmProvider для LLM-вызовов, NotesStore для связей и дочерних мыслей.
 */
public class ThoughtDetailActivity extends Activity {

    private NotesStore store;
    private NotesStore.Note note;
    private LlmProvider llm;
    private TextView titleView;
    private TextView contentView;
    private LinearLayout resultsLayout;
    private ProgressBar progress;
    private LinearLayout childrenContainer;
    private LinearLayout linksContainer;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);

        long noteId = getIntent().getLongExtra("note_id", -1);
        if (noteId < 0) { finish(); return; }

        store = new NotesStore(this);
        note = store.getById(noteId);
        if (note == null) { finish(); return; }

        llm = ProviderFactory.createLlmProvider(this);

        buildUI();
        loadChildren();
        loadLinks();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F9FC);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));

        // Шапка: кнопка назад + «Разобрать мысль» как в макете
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(32);
        back.setTextColor(0xFF121212);
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView hTitle = new TextView(this);
        hTitle.setText("Разобрать мысль");
        hTitle.setTextSize(20);
        hTitle.setTextColor(0xFF121212);
        hTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(hTitle);
        root.addView(header);

        // Карточка мысли: белый фон, скругление 16dp, padding 16dp
        LinearLayout thoughtCard = new LinearLayout(this);
        thoughtCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cd = new android.graphics.drawable.GradientDrawable();
        cd.setColor(0xFFFFFFFF);
        cd.setCornerRadius(dp(16));
        thoughtCard.setBackground(cd);
        thoughtCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Мета-строка: «Идея · 14:32»
        TextView metaView = new TextView(this);
        String typeName = typeRu(note.type);
        String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(note.ts));
        metaView.setText(typeName + " · " + timeStr);
        metaView.setTextSize(12);
        metaView.setTextColor(ThemeManager.accent(this));
        metaView.setTypeface(null, android.graphics.Typeface.BOLD);
        thoughtCard.addView(metaView);

        // Чип времени события — для заметок с dueIso (is_event).
        // Локальное напоминание будит AlarmManager в (dueIso − remindOffset).
        if (note.isEvent()) {
            try {
                long ev = RelayService.parseIsoToMillis(note.dueIso);
                String chip = "⏰ " + ReminderFmt.format(ev);
                String off = ReminderFmt.offsetLabel(note.remindOffsetMin);
                if (!off.isEmpty()) chip += "  ·  напомнить " + off;
                TextView eventChip = new TextView(this);
                eventChip.setText(chip);
                eventChip.setTextSize(12);
                eventChip.setTextColor(0xFF2C6FE8);
                eventChip.setTypeface(null, android.graphics.Typeface.BOLD);
                eventChip.setPadding(0, dp(6), 0, 0);
                thoughtCard.addView(eventChip);
            } catch (Exception e) { /* плохая дата — не показываем чип */ }
        }

        // Текст мысли
        contentView = new TextView(this);
        String content = (note.summary != null && !note.summary.isEmpty())
            ? note.summary : (note.text != null ? note.text : "");
        contentView.setText(content);
        contentView.setTextSize(16);
        contentView.setTextColor(0xFF121212);
        contentView.setPadding(0, dp(8), 0, 0);
        thoughtCard.addView(contentView);

        if (note.text != null && !note.text.equals(content)) {
            TextView fullText = new TextView(this);
            fullText.setText(note.text);
            fullText.setTextSize(13);
            fullText.setTextColor(0xFF757575);
            fullText.setPadding(0, dp(8), 0, 0);
            thoughtCard.addView(fullText);
        }

        // Чек-лист: настоящие тапаемые квадратики (item_checklist.xml).
        // Раньше чек-лист показывался только текстом ☑/☐ в превью списка (joinItems),
        // и в экране заметки квадратиков не было. Теперь — реальные CheckBox:
        // тап сохраняет состояние done в БД (store.addOrUpdate), зачёркивает текст.
        if ("list".equals(note.type) && note.items != null && !note.items.isEmpty()) {
            LinearLayout checkList = new LinearLayout(this);
            checkList.setOrientation(LinearLayout.VERTICAL);
            checkList.setPadding(0, dp(12), 0, 0);
            for (int i = 0; i < note.items.size(); i++) {
                final int idx = i;
                final CheckItem ci = note.items.get(i);
                View row = getLayoutInflater().inflate(R.layout.item_checklist, checkList, false);
                CheckBox cb = row.findViewById(R.id.checkDone);
                TextView itemText = row.findViewById(R.id.checkText);
                cb.setChecked(ci.done);
                cb.setClickable(true);
                itemText.setText(ci.text);
                applyDoneStyle(itemText, ci.done);
                cb.setOnCheckedChangeListener((btn, checked) -> {
                    ci.done = checked;
                    applyDoneStyle(itemText, checked);
                    store.addOrUpdate(note);
                });
                checkList.addView(row);
            }
            thoughtCard.addView(checkList);
        }
        root.addView(thoughtCard);

        // Кнопка «Связи» — единственное встроенное LLM-действие в экране заметки.
        // Развить/Вопросы/Цепочка убраны: разбор через облачка-обработчики (ниже),
        // эта часть переосмысляется. Связи оставлено — это поиск родственных заметок,
        // функционально отдельный от «разбора одной мысли».
        if (llm != null) {
            root.addView(makeButton("∞  Связи", v -> doFindLinks()));
        } else {
            TextView noLlm = new TextView(this);
            noLlm.setText("LLM не настроен — задайте ключ в Настройках");
            noLlm.setTextColor(0xFFFF1744);
            noLlm.setPadding(0, dp(12), 0, dp(4));
            root.addView(noLlm);
        }

        // Кнопка «Дополнить мысль» (контурная белая)
        Button addBtn = makeButton("➕  Дополнить своим", v -> showAddInput());
        root.addView(addBtn);

        // Облачка — пользовательские обработчики (LLM-роли).
        // Создаются в Настройках → Обработчики. Тап по облачку — разбор через эту роль.
        renderProcessorClouds(root);

        // Индикатор загрузки LLM
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        // Результаты LLM (карточка с развитием мысли)
        resultsLayout = new LinearLayout(this);
        resultsLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsLayout);

        // Связи
        TextView linksTitle = new TextView(this);
        linksTitle.setText("Связи");
        linksTitle.setTextSize(15);
        linksTitle.setTextColor(ThemeManager.accent(this));
        linksTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        linksTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(linksTitle);

        linksContainer = new LinearLayout(this);
        linksContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(linksContainer);

        // Цепочка мыслей
        TextView childrenTitle = new TextView(this);
        childrenTitle.setText("Цепочка мыслей");
        childrenTitle.setTextSize(15);
        childrenTitle.setTextColor(ThemeManager.accent(this));
        childrenTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        childrenTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(childrenTitle);

        childrenContainer = new LinearLayout(this);
        childrenContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(childrenContainer);

        scroll.addView(root);
        setContentView(scroll);
    }

    private String typeRu(String t) {
        if ("task".equals(t)) return "Задача";
        if ("list".equals(t)) return "Список";
        if ("reminder".equals(t)) return "Напоминание";
        if ("idea".equals(t)) return "Идея";
        return "Заметка";
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(ThemeManager.accent(this));
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xFFFFFFFF);
        gd.setCornerRadius(dp(14));
        btn.setBackground(gd);
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.setMargins(0, dp(12), 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    /** Стиль пункта чек-листа: выполненный — серый + зачёркнутый, невыполненный — обычный. */
    private void applyDoneStyle(TextView tv, boolean done) {
        tv.setTextColor(done ? 0xFF9E9E9E : 0xFF121212);
        int flags = tv.getPaintFlags();
        tv.setPaintFlags(done
            ? flags | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            : flags & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
    }

    // === Облачка: пользовательские обработчики ===

    private void renderProcessorClouds(LinearLayout root) {
        final ProcessorStore pstore = new ProcessorStore(this);
        final List<Processor> procs = pstore.list();
        if (procs.isEmpty()) return;

        TextView label = new TextView(this);
        label.setText("Обработчики");
        label.setTextSize(15);
        label.setTextColor(ThemeManager.accent(this));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, dp(20), 0, dp(8));
        root.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int idx = 0; idx < procs.size(); idx++) {
            final Processor p = procs.get(idx);
            int color = p.color != 0 ? p.color : Processor.colorForIndex(idx);
            TextView cloud = new TextView(this);
            cloud.setText(p.name);
            cloud.setTextSize(13);
            cloud.setTextColor(ThemeManager.onColor(color));
            cloud.setTypeface(null, android.graphics.Typeface.BOLD);
            cloud.setGravity(android.view.Gravity.CENTER);
            int hp = dp(7), wp = dp(14);
            cloud.setPadding(wp, hp, wp, hp);
            GradientDrawable cd = new GradientDrawable();
            cd.setShape(GradientDrawable.RECTANGLE);
            cd.setColor(color);
            cd.setCornerRadius(dp(18));
            cloud.setBackground(cd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            cloud.setLayoutParams(lp);
            cloud.setOnClickListener(v -> runProcessor(p));
            row.addView(cloud);
        }
        root.addView(row);
    }

    private void runProcessor(final Processor p) {
        String prompt = p.prompt + "\n\n" + noteContext()
            + "\n\nДай развёрнутый ответ в своей роли на основе ПОЛНОГО текста заметки.";
        runLlm(prompt, result -> {
            long childId = store.createChildNote(note.id, result, makeSummary(result),
                note.type, "llm", "processor");
            store.addLink(note.id, childId, "processor:" + p.name);
            showResult(p.name, result);
            loadChildren();
        });
    }

    // === Найти связи ===

    private void doFindLinks() {
        List<NotesStore.Note> allNotes = store.list();
        if (allNotes.size() < 2) {
            showResult("Связи", "Недостаточно заметок для поиска связей (нужно 2+)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Найди связи между этой мыслью и другими заметками. ");
        sb.append("Верни JSON-массив объектов {\"id\": число, \"reason\": \"краткое объяснение связи\"}. ");
        sb.append("Только реально связанные заметки (2-5 шт).\n\n");
        sb.append("Текущая мысль:\n").append(noteContext()).append("\n\nДругие заметки:\n");
        int count = 0;
        for (NotesStore.Note n : allNotes) {
            if (n.id == note.id) continue;
            if (n.summary == null || n.summary.isEmpty()) continue;
            sb.append("[").append(n.id).append("] ").append(n.summary).append("\n");
            if (++count >= 20) break;
        }
        runLlm(sb.toString(), result -> {
            parseAndSaveLinks(result);
        });
    }

    // === Дополнить своим (блок author=me) ===

    private void showAddInput() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Своя идея, уточнение, следующий шаг…");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int p = dp(12);
        input.setPadding(p, p, p, p);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Дополнить мысль")
            .setView(input)
            .setPositiveButton("Добавить", (dd, w) -> {
                String txt = input.getText().toString().trim();
                if (txt.isEmpty()) return;
                store.createChildNote(note.id, txt, makeSummary(txt), note.type, "me", "mine");
                loadChildren();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    // === Helpers ===

    /**
     * Полный текст заметки для LLM — БОЛЬШЕЕ из transcript/text.
     *
     * Раньше возвращался transcript первым, но при повторной обработке он
     * мог стать короче text (например, пересохранённый обрезок), и LLM
     * получал только начало заметки. Берём длиннейший, чтобы ничего не
     * потерять. Поля дублируют друг друга в нормальном случае (оба = STT),
     * так что выбор длиннейшего безопасен.
     */
    private String getNoteText() {
        String t = note.transcript != null ? note.transcript : "";
        String x = note.text != null ? note.text : "";
        return t.length() >= x.length() ? t : x;
    }

    /**
     * Контекст заметки для промптов LLM — с явной разметкой заголовка и
     * полного текста. Раньше LLM видел только getNoteText() без подписи,
     * и summary (короткое LLM-название заметки, ~40 символов) на экране
     * выглядел как «весь текст» — пользователь думал, что LLM читает
     * всё, а тот получал обрезок. Теперь промпт явно говорит: вот
     * заголовок (краткое название), вот полный текст — разбирай полный.
     */
    private String noteContext() {
        StringBuilder sb = new StringBuilder();
        String summary = note.summary != null ? note.summary.trim() : "";
        String full = getNoteText();
        if (!summary.isEmpty() && !summary.equals(full)) {
            sb.append("Заголовок: ").append(summary).append("\n");
        }
        sb.append("Полный текст заметки:\n").append(full);
        return sb.toString();
    }

    private void runLlm(String prompt, LlmCallback callback) {
        progress.setVisibility(View.VISIBLE);
        resultsLayout.removeAllViews();
        new Thread(() -> {
            try {
                // Используем LLM с простым промптом (не JSON extraction)
                String result = callLlmRaw(prompt);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    callback.onResult(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    showResult("Ошибка", e.getMessage() != null ? e.getMessage() : String.valueOf(e));
                });
            }
        }).start();
    }

    /**
     * Прямой вызов LLM с произвольным промптом (не extraction).
     * Возвращает текстовый ответ.
     */
    private String callLlmRaw(String prompt) throws Exception {
        android.content.SharedPreferences prefs = getSharedPreferences("vn", MODE_PRIVATE);
        String baseUrl = prefs.getString("llm_base_url", "https://plusvibeapi.ru/v1");
        String apiKey = prefs.getString("llm_key", "");
        String model = prefs.getString("llm_model", "z-ai/glm-5.2");
        if (apiKey == null || apiKey.isEmpty()) throw new Exception("LLM key not set");

        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(baseUrl + "/chat/completions").openConnection();
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);

            JSONObject req = new JSONObject();
            req.put("model", model);
            req.put("temperature", 0.7);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", prompt));
            req.put("messages", messages);

            java.io.OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            byte[] body = readAll(is);
            if (code != 200) {
                throw new Exception("LLM HTTP " + code);
            }
            JSONObject resp = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new Exception("No choices");
            return choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void parseAndSaveLinks(String jsonResult) {
        try {
            String cleaned = jsonResult.trim();
            if (cleaned.startsWith("```")) {
                int s0 = cleaned.indexOf('\n');
                int s1 = cleaned.lastIndexOf("```");
                if (s0 > 0 && s1 > s0) cleaned = cleaned.substring(s0 + 1, s1).trim();
            }
            JSONArray arr = new JSONArray(cleaned);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long otherId = o.optLong("id", -1);
                String reason = o.optString("reason", "");
                if (otherId > 0 && otherId != note.id) {
                    store.addLink(note.id, otherId, "similar", reason);
                    NotesStore.Note other = store.getById(otherId);
                    String otherTitle = other != null ? other.summary : "#" + otherId;
                    sb.append("→ ").append(otherTitle).append("\n   ").append(reason).append("\n\n");
                }
            }
            loadLinks();
            if (sb.length() == 0) {
                showResult("Связи", "Связей с другими заметками не найдено");
            } else {
                showResult("Найденные связи", sb.toString().trim());
            }
        } catch (Exception e) {
            showResult("Связи (raw)", jsonResult);
        }
    }

    private void showResult(String title, String content) {
        resultsLayout.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xFFFFFFFF);
        gd.setCornerRadius(dp(16));
        card.setBackground(gd);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, 0);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTextColor(ThemeManager.accent(this));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);

        TextView c = new TextView(this);
        c.setText(content);
        c.setTextSize(15);
        c.setTextColor(0xFF121212);
        c.setPadding(0, dp(8), 0, 0);
        card.addView(c);

        resultsLayout.addView(card);
    }

    private void loadChildren() {
        childrenContainer.removeAllViews();
        List<NotesStore.Note> children = store.getChildren(note.id);
        if (children.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Пока нет развитых мыслей. Нажмите «Развить» или «Собрать цепочку».");
            empty.setTextSize(13);
            empty.setTextColor(0xFF999999);
            childrenContainer.addView(empty);
            return;
        }
        for (NotesStore.Note child : children) {
            TextView tv = new TextView(this);
            boolean mine = "me".equals(child.author) || "mine".equals(child.kind);
            String title = child.summary != null && !child.summary.isEmpty() ? child.summary : child.text;
            if (title == null) title = "";
            tv.setText((mine ? "Вы: " : "") + (title.length() > 80 ? title.substring(0, 80) + "…" : title));
            tv.setTextSize(13);
            tv.setTextColor(0xFF333333);
            tv.setBackgroundColor(0xFFFFFFFF);
            // Свои блоки — акцентная полоса слева (палитра B)
            android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
            gd.setColor(0xFFFFFFFF);
            gd.setStroke(dp(mine ? 0 : 0), 0);
            tv.setBackground(gd);
            tv.setPadding(dp(mine ? 10 : 12), dp(10), dp(12), dp(10));
            if (mine) {
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{
                        gd,
                        makeBar()
                    });
                ld.setLayerInsetLeft(0, 0);
                tv.setBackground(ld);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(6));
            tv.setLayoutParams(lp);
            final long childId = child.id;
            tv.setOnClickListener(v -> {
                Intent intent = new Intent(this, ThoughtDetailActivity.class);
                intent.putExtra("note_id", childId);
                startActivity(intent);
            });
            childrenContainer.addView(tv);
        }
    }

    private android.graphics.drawable.Drawable makeBar() {
        android.graphics.drawable.GradientDrawable bar =
            new android.graphics.drawable.GradientDrawable();
        bar.setColor(ThemeManager.accent(this));
        android.graphics.drawable.InsetDrawable inset =
            new android.graphics.drawable.InsetDrawable(bar, 0, 0, 0, 0) {
                @Override
                public void setBounds(int left, int top, int right, int bottom) {
                    super.setBounds(left, top, left + dp(4), bottom);
                }
            };
        return inset;
    }

    // === Связи: список с причинами, удаление ✕ ===

    private void loadLinks() {
        if (linksContainer == null) return;
        linksContainer.removeAllViews();
        java.util.List<Object[]> links = store.getLinksDetailed(note.id);

        if (links.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Пока нет. Появляются сами при сохранении новых мыслей "
                + "или по кнопке «Найти связи».");
            tv.setTextSize(12);
            tv.setTextColor(0xFF888888);
            linksContainer.addView(tv);
            return;
        }

        for (Object[] l : links) {
            final long otherId = (Long) l[0];
            String reason = l[2] == null ? "" : (String) l[2];
            NotesStore.Note other = store.getById(otherId);
            String otherTitle;
            if (other == null) {
                otherTitle = "(заметка удалена)";
            } else {
                otherTitle = (other.summary != null && !other.summary.isEmpty())
                    ? other.summary : (other.text != null ? other.text : "");
            }
            if (otherTitle.length() > 60) otherTitle = otherTitle.substring(0, 60) + "…";

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(0xFFFFFFFF);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(6));
            row.setLayoutParams(lp);

            TextView tv = new TextView(this);
            StringBuilder t = new StringBuilder("↔ ").append(otherTitle);
            if (!reason.isEmpty()) t.append("\n     почему: ").append(reason);
            tv.setText(t);
            tv.setTextSize(13);
            tv.setTextColor(0xFF333333);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(tvLp);
            row.addView(tv);

            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextSize(16);
            del.setTextColor(0xFFD32F2F);
            del.setPadding(dp(8), dp(4), dp(8), dp(4));
            del.setOnClickListener(v -> {
                store.removeLink(note.id, otherId);
                loadLinks();
            });
            row.addView(del);

            row.setOnClickListener(v -> {
                if (other == null) return;
                Intent intent = new Intent(this, ThoughtDetailActivity.class);
                intent.putExtra("note_id", otherId);
                startActivity(intent);
            });

            linksContainer.addView(row);
        }
    }

    private static String makeSummary(String text) {
        String trimmed = text.trim().replaceAll("\\s+", " ");
        String[] words = trimmed.split(" ");
        int count = Math.min(words.length, 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        String result = sb.toString();
        if (result.length() > 40) result = result.substring(0, 37) + "…";
        return result;
    }

    private static byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        if (is == null) return bos.toByteArray();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private interface LlmCallback {
        void onResult(String result);
    }
}
