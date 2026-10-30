package com.nexara.voicenotes;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Поиск заметок: по тексту, summary, типу.
 *
 * Semantic search (через embeddings) — в планах (Фаза 4, DB v3).
 * Пока: полнотекстовый LIKE + фильтр по категории.
 */
public class SearchActivity extends Activity {

    private EditText searchInput;
    private ListView resultsList;
    private TextView resultsCount;
    private NotesStore store;
    private List<NotesStore.Note> allNotes;
    private List<NotesStore.Note> filtered;
    private LinearLayout chipsRow;
    private int filterIdx = 0;

    private static final String[] FILTER_NAMES = {"Все", "Идеи", "Задачи", "Списки", "Напоминания", "Заметки"};
    private static final String[] FILTER_TYPES = {null, "idea", "task", "list", "reminder", "note"};

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F9FC);

        // Header
        TextView header = new TextView(this);
        header.setText("🔍 Поиск");
        header.setTextAppearance(this, R.style.TextAppearance_Potok_Headline);
        header.setTextColor(0xFF121212);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.addView(header);

        // Search input
        searchInput = new EditText(this);
        searchInput.setHint("Поиск по тексту, саммари…");
        searchInput.setTextAppearance(this, R.style.TextAppearance_Potok_Body);
        searchInput.setBackgroundColor(0xFFFFFFFF);
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(16), 0, dp(16), dp(8));
        searchInput.setLayoutParams(searchLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { doFilter(); }
        });
        root.addView(searchInput);

        // Filter chips
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(16), 0, dp(16), dp(8));
        for (int i = 0; i < FILTER_NAMES.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(FILTER_NAMES[i]);
            chip.setTextAppearance(this, R.style.TextAppearance_Potok_Caption);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            final int idx = i;
            chip.setOnClickListener(v -> { filterIdx = idx; styleChips(); doFilter(); });
            chipsRow.addView(chip);
        }
        root.addView(chipsRow);
        styleChips();

        // Results count
        resultsCount = new TextView(this);
        resultsCount.setTextAppearance(this, R.style.TextAppearance_Potok_Caption);
        resultsCount.setTextColor(0xFF888888);
        resultsCount.setPadding(dp(16), dp(4), dp(16), dp(4));
        root.addView(resultsCount);

        // Results list
        resultsList = new ListView(this);
        resultsList.setDivider(null);
        resultsList.setDividerHeight(0);
        resultsList.setPadding(dp(16), 0, dp(16), dp(16));
        root.addView(resultsList, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        store = new NotesStore(this);
        allNotes = store.list();
        filtered = new ArrayList<>(allNotes);
        resultsList.setAdapter(new SearchAdapter());
        updateCount();
    }

    private void doFilter() {
        String query = searchInput.getText().toString().trim().toLowerCase();
        String fType = FILTER_TYPES[filterIdx];
        filtered.clear();
        for (NotesStore.Note n : allNotes) {
            if (fType != null && !fType.equals(n.type)) continue;
            if (query.isEmpty()) {
                filtered.add(n);
            } else {
                String text = (n.text != null ? n.text.toLowerCase() : "");
                String summary = (n.summary != null ? n.summary.toLowerCase() : "");
                if (text.contains(query) || summary.contains(query)) {
                    filtered.add(n);
                }
            }
        }
        ((ArrayAdapter) resultsList.getAdapter()).notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        resultsCount.setText(filtered.size() + " результатов");
    }

    private void styleChips() {
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView c = (TextView) chipsRow.getChildAt(i);
            if (i == filterIdx) {
                c.setBackgroundColor(ThemeManager.accent(SearchActivity.this));
                c.setTextColor(0xFFFFFFFF);
            } else {
                c.setBackgroundColor(0xFFEEEEEE);
                c.setTextColor(0xFF121212);
            }
        }
    }

    private class SearchAdapter extends ArrayAdapter<NotesStore.Note> {
        SearchAdapter() { super(SearchActivity.this, android.R.layout.simple_list_item_2, filtered); }

        @Override
        public View getView(int pos, View conv, ViewGroup parent) {
            if (conv == null) {
                conv = LayoutInflater.from(SearchActivity.this)
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            NotesStore.Note n = filtered.get(pos);
            String time = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(n.ts));
            String title = (n.summary != null && !n.summary.isEmpty())
                ? n.summary : (n.text != null ? n.text.substring(0, Math.min(50, n.text.length())) : "?");
            ((TextView) conv.findViewById(android.R.id.text1)).setText(title);
            ((TextView) conv.findViewById(android.R.id.text2)).setText(
                time + " · " + n.type + (n.text != null && !n.text.isEmpty() ? " · " + n.text.substring(0, Math.min(60, n.text.length())) : ""));
            conv.setOnClickListener(v -> {
                Intent intent = new Intent(SearchActivity.this, ThoughtDetailActivity.class);
                intent.putExtra("note_id", n.id);
                startActivity(intent);
            });
            return conv;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
