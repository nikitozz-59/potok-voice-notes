package com.nexara.voicenotes;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Ручная правка заметки: заголовок (summary) и текст.
 * Исправляет ошибки распознавания и уточняет суть без перезаписи голосом.
 */
public class EditNoteActivity extends Activity {

    private NotesStore store;
    private NotesStore.Note note;
    private EditText editSummary;
    private EditText editText;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_edit_note);

        long id = getIntent().getLongExtra("id", -1);
        if (id < 0) { finish(); return; }

        store = new NotesStore(this);
        note = store.getById(id);
        if (note == null) { finish(); return; }

        editSummary = findViewById(R.id.editSummary);
        editText = findViewById(R.id.editText);

        editSummary.setText(note.summary != null ? note.summary : "");
        editText.setText(note.text != null ? note.text : "");

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.saveBtn).setOnClickListener(v -> save());
    }

    private void save() {
        String sum = editSummary.getText().toString().trim();
        String txt = editText.getText().toString().trim();
        if (sum.isEmpty() && txt.isEmpty()) {
            Toast.makeText(this, "Пусто — не сохраняю", Toast.LENGTH_SHORT).show();
            return;
        }
        note.summary = sum;
        note.text = txt;
        store.addOrUpdate(note);
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
        finish();
    }
}
