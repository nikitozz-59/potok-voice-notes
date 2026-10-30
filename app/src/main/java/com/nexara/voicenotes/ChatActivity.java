package com.nexara.voicenotes;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Чат с LLM (v3.7 — заглушка, полная реализация следующим батчем).
 *
 * Multi-turn диалог для формулирования идеи → «зафиксировать» последний ответ LLM
 * как заметку через LlmExtractor.
 *
 * Entry points: кнопка в шапке MainActivity (глобальный) + «Обсудить» в карточке
 * заметки (с seed-контекстом).
 */
public class ChatActivity extends Activity {

    private LinearLayout messagesList;
    private ProgressBar progress;
    private LlmProvider llm;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        llm = ProviderFactory.createLlmProvider(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F9FC);

        // Header
        TextView header = new TextView(this);
        header.setText("💬 Чат с LLM");
        header.setTextSize(20);
        header.setTextColor(0xFF121212);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        int p = dp(16);
        header.setPadding(p, p, p, dp(8));
        root.addView(header);

        if (llm == null) {
            TextView noLlm = new TextView(this);
            noLlm.setText("LLM не настроен.\nЗадайте ключ в Настройках.");
            noLlm.setTextColor(0xFFFF1744);
            noLlm.setPadding(p, dp(20), p, dp(20));
            root.addView(noLlm);
            setContentView(root);
            return;
        }

        // Placeholder — полная реализация в v3.7.
        TextView placeholder = new TextView(this);
        placeholder.setText("Чат-интерфейс в разработке (v3.7).\n\nЗдесь будет многоходовый диалог с LLM: формулируете мысль, уточняете, и итог фиксируется как заметка.");
        placeholder.setTextSize(14);
        placeholder.setTextColor(0xFF666666);
        placeholder.setPadding(p, dp(20), p, dp(20));
        root.addView(placeholder);

        String seedText = getIntent().getStringExtra("seed_text");
        if (seedText != null && !seedText.isEmpty()) {
            TextView seed = new TextView(this);
            seed.setText("Контекст заметки:\n" + (seedText.length() > 120 ? seedText.substring(0, 117) + "…" : seedText));
            seed.setTextSize(13);
            seed.setTextColor(0xFF888888);
            seed.setPadding(p, 0, p, dp(20));
            root.addView(seed);
        }

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
