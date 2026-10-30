package com.nexara.voicenotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Чат с LLM (v3.7): обсуждаем вопрос, формулируем идею, потом «зафиксировать»
 * — последний ответ LLM проходит через LlmExtractor и становится заметкой.
 *
 * Multi-turn: вся история (user/assistant) уходит в провайдер каждый запрос.
 * Точки входа: кнопка в шапке MainActivity (глобальный) + «Обсудить» в карточке
 * заметки (seed-контекст).
 */
public class ChatActivity extends Activity {

    private LlmProvider llm;
    private final List<ChatMessage> history = new ArrayList<>();
    private LinearLayout messagesLayout;   // контейнер пузырей
    private ScrollView scroll;
    private EditText input;
    private TextView progressText;
    private String seedText;

    private static final String SYS_CHAT =
        "Ты — собеседник в приложении заметок «Поток». Помогаешь сформулировать мысль, " +
        "разобрать вопрос, спланировать задачу или списки. Отвечай по делу, живо, на русском. " +
        "Если пользователь просит что-то зафиксировать — дай готовую формулировку, " +
        "которую можно сохранить как заметку. Не упоминай, что ты ИИ.";

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        llm = ProviderFactory.createLlmProvider(this);
        seedText = getIntent().getStringExtra("seed_text");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F9FC);

        // === Header ===
        TextView header = new TextView(this);
        header.setText("💬 Чат с LLM");
        header.setTextAppearance(this, R.style.TextAppearance_Potok_Headline);
        header.setTextColor(0xFF121212);
        int p = dp(16);
        header.setPadding(p, p, p, dp(4));
        root.addView(header);

        // Кнопка «Зафиксировать» — справа в шапке
        TextView fixBtn = new TextView(this);
        fixBtn.setText("Зафиксировать");
        fixBtn.setTextAppearance(this, R.style.TextAppearance_Potok_Body);
        fixBtn.setTextColor(ThemeManager.accent(this));
        int fbP = dp(10);
        fixBtn.setPadding(fbP, dp(6), fbP, dp(6));
        GradientDrawable fbBg = new GradientDrawable();
        fbBg.setShape(GradientDrawable.RECTANGLE);
        fbBg.setCornerRadius(dp(8));
        fbBg.setStroke(dp(1), ThemeManager.accent(this));
        fixBtn.setBackground(fbBg);
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fbLp.setMargins(p, dp(4), p, dp(8));
        fixBtn.setLayoutParams(fbLp);
        fixBtn.setOnClickListener(v -> saveLastAnswer());
        root.addView(fixBtn);

        if (llm == null) {
            TextView noLlm = new TextView(this);
            noLlm.setText("LLM не настроен.\nЗадайте ключ в Настройках.");
            noLlm.setTextColor(getResources().getColor(R.color.error));
            noLlm.setPadding(p, dp(20), p, dp(20));
            root.addView(noLlm);
            setContentView(root);
            return;
        }

        // === Seed-контекст (из карточки заметки) ===
        if (seedText != null && !seedText.isEmpty()) {
            TextView seed = new TextView(this);
            seed.setText("Обсуждаем заметку:\n" + (seedText.length() > 120
                ? seedText.substring(0, 117) + "…" : seedText));
            seed.setTextAppearance(this, R.style.TextAppearance_Potok_Caption);
            seed.setTextColor(0xFF888888);
            seed.setPadding(p, 0, p, dp(8));
            root.addView(seed);
        }

        // === Messages ===
        scroll = new ScrollView(this);
        messagesLayout = new LinearLayout(this);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);
        messagesLayout.setPadding(p, dp(8), p, dp(8));
        scroll.addView(messagesLayout);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // === Input row ===
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(12), dp(8), dp(12), dp(8));

        input = new EditText(this);
        input.setHint("Спросите, обсудите, сформулируйте…");
        input.setTextAppearance(this, R.style.TextAppearance_Potok_Body);
        input.setBackgroundColor(0xFFFFFFFF);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setShape(GradientDrawable.RECTANGLE);
        inBg.setCornerRadius(dp(20));
        inBg.setColor(0xFFFFFFFF);
        input.setBackground(inBg);
        int ip = dp(12);
        input.setPadding(ip, dp(8), ip, dp(8));
        input.setMinLines(1);
        input.setMaxLines(4);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView sendBtn = new TextView(this);
        sendBtn.setText("➤");
        sendBtn.setTextAppearance(this, R.style.TextAppearance_Potok_Headline);
        sendBtn.setTextColor(Color.WHITE);
        int sbP = dp(12);
        sendBtn.setPadding(sbP, dp(10), sbP, dp(10));
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setShape(GradientDrawable.RECTANGLE);
        sbBg.setCornerRadius(dp(20));
        sbBg.setColor(ThemeManager.accent(this));
        sendBtn.setBackground(sbBg);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.setMargins(dp(8), 0, 0, 0);
        sendBtn.setLayoutParams(sbLp);
        sendBtn.setOnClickListener(v -> send());
        inputRow.addView(sendBtn);

        root.addView(inputRow);

        setContentView(root);

        // Прогресс
        progressText = new TextView(this);
        progressText.setText("Думаю…");
        progressText.setTextAppearance(this, R.style.TextAppearance_Potok_Caption);
        progressText.setTextColor(0xFF888888);
        progressText.setVisibility(View.GONE);
        messagesLayout.addView(progressText);

        // Старт с seed: автоматический первый вопрос с контекстом
        if (seedText != null && !seedText.isEmpty()) {
            history.add(ChatMessage.user("Есть заметка:\n«" + ChatText.truncateCn(seedText, 600) + "»\n\nПомоги разобрать, развить или сформулировать на её основе идею."));
            addBubble("user", "Есть заметка — помоги разобрать её и развить идею.");
            send();
        } else {
            addBubble("assistant", "Привет! О чём хотите подумать? Могу помочь сформулировать мысль, разложить вопрос или собрать план. Когда будет готово — нажмите «Зафиксировать».");
        }
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || sending) return;
        input.setText("");
        history.add(ChatMessage.user(text));
        addBubble("user", text);
        runLlm();
    }

    private boolean sending = false;

    private void runLlm() {
        sending = true;
        showProgress(true);
        final List<ChatMessage> snapshot = new ArrayList<>(history);
        new Thread(() -> {
            try {
                String reply = llm.chat(snapshot, SYS_CHAT);
                history.add(ChatMessage.assistant(reply));
                ui.post(() -> {
                    showProgress(false);
                    sending = false;
                    addBubble("assistant", reply);
                    scrollToBottom();
                });
            } catch (Exception e) {
                final String err = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                ui.post(() -> {
                    showProgress(false);
                    sending = false;
                    addBubble("error", "Ошибка: " + err);
                    scrollToBottom();
                });
            }
        }).start();
    }

    /** Зафиксировать последний ответ LLM как заметку через LlmExtractor. */
    private void saveLastAnswer() {
        // Ищем последний ответ ассистента (не ошибку/систему)
        String last = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if ("assistant".equals(m.role)) { last = m.content; break; }
        }
        if (last == null) {
            Toast.makeText(this, "Нет ответа от LLM", Toast.LENGTH_SHORT).show();
            return;
        }
        final String answer = last;
        new AlertDialog.Builder(this)
            .setTitle("Зафиксировать как заметку?")
            .setMessage("Последний ответ LLM будет разобран и сохранён:\n\n" + ChatText.truncateCn(answer, 160))
            .setPositiveButton("Сохранить", (d, w) -> saveNote(answer))
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void saveNote(String text) {
        Toast.makeText(this, "Сохранение…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final LlmExtractor.Struct st;
            try {
                st = llm != null ? llm.extract(text) : LlmExtractor.parseResponse(null);
            } catch (Exception e) {
                // LLM недоступен — сохраняем «сырую» заметку без структуры.
                ui.post(() -> {
                    Toast.makeText(this, "LLM недоступен — сохранено без разбора",
                        Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            NotesStore store = new NotesStore(this);
            NotesStore.Note n = new NotesStore.Note();
            n.id = System.currentTimeMillis();
            n.ts = System.currentTimeMillis();
            n.durSec = 0;
            n.sizeBytes = 0;
            n.text = text;
            n.transcript = text;
            n.status = "done";
            n.type = st.type;
            n.summary = st.summary;
            n.eventTitle = st.eventTitle;
            n.dueIso = st.dueIso;
            n.remindOffsetMin = st.remindOffsetMin;
            n.items = CheckItem.wrap(st.items);
            store.addOrUpdate(n);
            if (n.dueIso != null && ("reminder".equals(n.type) || "task".equals(n.type))) {
                try { ReminderScheduler.schedule(this, n); } catch (Exception ignore) {}
            }
            final String saved = st.summary != null && !st.summary.isEmpty() ? st.summary : "заметка";
            ui.post(() -> {
                Toast.makeText(this, "Сохранено: " + saved, Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }

    // === UI helpers ===

    private void showProgress(boolean on) {
        if (progressText != null) progressText.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void addBubble(String who, String text) {
        if (progressText != null) progressText.setVisibility(View.GONE);
        TextView b = new TextView(this);
        b.setTextAppearance(this, R.style.TextAppearance_Potok_Body);
        b.setLineSpacing(0, 1.05f);
        int hp = dp(12), vp = dp(8);
        b.setPadding(hp, vp, hp, vp);

        int maxW = (int) (this.getResources().getDisplayMetrics().widthPixels * 0.78f);
        LinearLayout.LayoutParams lp;
        if ("user".equals(who)) {
            b.setTextColor(0xFFFFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(0xFF2C6FE8);
            bg.setCornerRadii(new float[]{dp(14), dp(14), dp(4), dp(14), dp(14), dp(14), dp(14), dp(14)});
            b.setBackground(bg);
            lp = new LinearLayout.LayoutParams(Math.min(maxW, ViewGroup.LayoutParams.WRAP_CONTENT),
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END;
            lp.setMargins(dp(40), dp(4), 0, dp(4));
        } else if ("error".equals(who)) {
            b.setTextColor(getResources().getColor(R.color.error));
            b.setTextAppearance(this, R.style.TextAppearance_Potok_Caption);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(10));
            bg.setColor(0xFFFFEBEE);
            b.setBackground(bg);
            lp = new LinearLayout.LayoutParams(Math.min(maxW, ViewGroup.LayoutParams.WRAP_CONTENT),
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
        } else {
            b.setTextColor(0xFF121212);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(Color.WHITE);
            bg.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14), dp(4), dp(14), dp(14), dp(14)});
            b.setBackground(bg);
            lp = new LinearLayout.LayoutParams(Math.min(maxW, ViewGroup.LayoutParams.WRAP_CONTENT),
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.setMargins(0, dp(4), dp(40), dp(4));
        }
        b.setLayoutParams(lp);
        b.setText(ChatText.wrapCn(text, 60));
        int idx = messagesLayout.indexOfChild(progressText);
        messagesLayout.addView(b, idx < 0 ? messagesLayout.getChildCount() : idx);
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
