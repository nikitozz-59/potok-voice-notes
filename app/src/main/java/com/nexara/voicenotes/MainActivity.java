package com.nexara.voicenotes;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Главный экран (редизайн v0.2 / Qwen3.8 Max).
 * Заголовок + синхро-баннер + чипы-фильтры + карточки заметок + FAB-микрофон.
 * STT/LLM идут автоматически — кнопки «Обработать» нет.
 */
public class MainActivity extends Activity {
    private TextView syncText;
    private TextView syncIcon;
    private LinearLayout chipsRow;
    private ListView list;
    private TextView emptyView;
    private ImageButton recFab;
    private Button cancelBtn;

    private SharedPreferences prefs;
    private NotesStore store;

    private final List<NotesStore.Note> allNotes = new ArrayList<>();
    private final List<NotesStore.Note> visibleNotes = new ArrayList<>();
    private NoteAdapter adapter;
    private int filterPos = 0;
    private final List<TextView> chipViews = new ArrayList<>();

    private MediaRecorder recorder;
    private boolean recording = false;
    private long startTime = 0;
    private File recFile;
    private Handler timerHandler;
    private Runnable timerTick;
    private boolean autoProcessing = false;

    private static final String[] FILTER_NAMES = {"Все", "Задачи", "Списки", "Напоминания"};
    private static final String[] FILTER_TYPES = {null, "task", "list", "reminder"};

    // Два веса строк: заметки с детьми/связями — крупные карточки
    private final java.util.HashMap<Long, int[]> devStats = new java.util.HashMap<>();
    private boolean developedOnly = false;
    private ImageButton devToggle;
    private View syncBanner;

    /** Идентификатор виртуальной системной заметки (в БД не хранится). */
    private static final long SYSTEM_NOTE_ID = -1L;

    private final BroadcastReceiver refresher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateSyncBanner();
            refresh();
            // Пришло новое — запустим авто-обработку (на случай необработанных).
            autoProcess();
        }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("vn", MODE_PRIVATE);
        store = new NotesStore(this);

        // Перепланировать все напоминания — AlarmManager не переживает
        // переустановку/обновление приложения. На BOOT_COMPLETED тоже.
        ReminderScheduler.rescheduleAll(this);

        syncText = findViewById(R.id.syncText);
        syncIcon = findViewById(R.id.syncIcon);
        chipsRow = findViewById(R.id.chipsRow);
        list = findViewById(R.id.notes);
        emptyView = findViewById(R.id.empty);
        recFab = findViewById(R.id.recFab);
        cancelBtn = findViewById(R.id.cancelBtn);
        devToggle = findViewById(R.id.devToggle);
        syncBanner = findViewById(R.id.syncBanner);

        devToggle.setOnClickListener(v -> {
            developedOnly = !developedOnly;
            styleDevToggle();
            applyFilter();
        });
        styleDevToggle();

        buildChips();

        adapter = new NoteAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            NotesStore.Note n = visibleNotes.get(pos);
            if (n.system) {
                openSystemNote();
                return;
            }
            Intent i = new Intent(this, ThoughtDetailActivity.class);
            i.putExtra("note_id", n.id);
            startActivity(i);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            NotesStore.Note n = visibleNotes.get(pos);
            if (n.system) return true; // системную не удалить и не редактировать
            showNoteActions(n);
            return true;
        });

        recFab.setOnClickListener(v -> {
            if (recording) stopRecording();
            else startRecording();
        });

        findViewById(R.id.searchBtn).setOnClickListener(v -> {
            startActivity(new Intent(this, SearchActivity.class));
        });

        findViewById(R.id.addTextBtn).setOnClickListener(v -> showTextNoteDialog());

        cancelBtn.setOnClickListener(v -> {
            cancelRecording();
        });

        timerHandler = new Handler(Looper.getMainLooper());
        timerTick = new Runnable() {
            @Override
            public void run() {
                if (recording) {
                    long sec = (System.currentTimeMillis() - startTime) / 1000;
                    syncText.setText(String.format(Locale.US, "● Запись %02d:%02d — тап для стоп", sec / 60, sec % 60));
                    syncIcon.setText("●");
                    syncIcon.setTextColor(ThemeManager.REC_GREEN);
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };

        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.WRITE_CALENDAR);
            req.add(Manifest.permission.READ_CALENDAR);
        }
        if (!req.isEmpty()) {
            requestPermissions(req.toArray(new String[0]), 1);
        }

        startRelay();

        // Запуск по тапу на виджете рабочего стола.
        if (getIntent() != null && getIntent().getBooleanExtra("start_recording", false)) {
            getIntent().removeExtra("start_recording");
            recFab.postDelayed(() -> startRecording(), 400);
        }
    }

    private void buildChips() {
        chipsRow.removeAllViews();
        chipViews.clear();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < FILTER_NAMES.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(FILTER_NAMES[i]);
            chip.setTextSize(14);
            chip.setPadding((int) (16 * density), (int) (8 * density), (int) (16 * density), (int) (8 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * density));
            chip.setLayoutParams(lp);
            final int idx = i;
            chip.setOnClickListener(v -> selectChip(idx));
            chipsRow.addView(chip);
            chipViews.add(chip);
        }
        styleChips();
    }

    private void selectChip(int idx) {
        filterPos = idx;
        styleChips();
        applyFilter();
    }

    private void styleChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            ThemeManager.styleChip(this, chipViews.get(i), i == filterPos);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(refresher, new IntentFilter(RelayService.ACTION_NOTE_ADDED));

        // Тема могла смениться в «Оформлении» — перекрашиваем и пересобираем список.
        applyTheme();
        autoProcess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(refresher);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Настройки");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startRelay() {
        Intent i = new Intent(this, RelayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    // ---------- список ----------

    private void refresh() {
        allNotes.clear();
        allNotes.addAll(store.list());
        Collections.reverse(allNotes);
        devStats.clear();
        devStats.putAll(store.getDevelopedStats());
        applyFilter();
    }

    private void applyFilter() {
        visibleNotes.clear();
        // Системная заметка «Оформление» стоит первой всегда — это вход в тему.
        NotesStore.Note sys = systemNote();
        if (sys != null) visibleNotes.add(sys);

        String fType = FILTER_TYPES[filterPos];
        for (NotesStore.Note n : allNotes) {
            if (n.system) continue;
            if (fType != null && !fType.equals(n.type)) continue;
            if (developedOnly) {
                int[] st = devStats.get(n.id);
                if (st == null || (st[0] == 0 && st[1] == 0)) continue;
            }
            visibleNotes.add(n);
        }
        adapter.setSystemOffset(sys != null ? 1 : 0);
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(visibleNotes.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Виртуальная заметка-настройка: не хранится в БД, не уходит в LLM, не индексируется поиском. */
    private NotesStore.Note systemNote() {
        NotesStore.Note n = new NotesStore.Note();
        n.id = SYSTEM_NOTE_ID;
        n.system = true;
        n.type = "system";
        n.summary = "Оформление";
        n.text = "скажите, что поменять";
        return n;
    }

    private void openSystemNote() {
        AppCommandDialog dlg = new AppCommandDialog(this);
        dlg.setOnApplied(this::applyTheme);
        dlg.show();
    }

    private void styleDevToggle() {
        if (developedOnly) {
            devToggle.setBackground(ThemeManager.accentFill(this, 20));
            devToggle.setColorFilter(ThemeManager.onAccent(this));
        } else {
            devToggle.setBackground(null);
            devToggle.setColorFilter(0xFF555555);
        }
    }

    /** Перекрасить всё, что задаётся темой. Вызывается в onCreate и onResume. */
    private void applyTheme() {
        ThemeManager.applyToWindow(this);
        if (recFab != null) recFab.setBackground(ThemeManager.accentButton(this, 28));
        ImageButton addTextBtn = findViewById(R.id.addTextBtn);
        if (addTextBtn != null) addTextBtn.setBackground(ThemeManager.accentButton(this, 22));
        styleChips();
        styleDevToggle();
        if (syncBanner != null) {
            GradientDrawable sb = new GradientDrawable();
            sb.setShape(GradientDrawable.RECTANGLE);
            sb.setColor(ThemeManager.softBg(this));
            syncBanner.setBackground(sb);
        }
        updateSyncBanner();
        refresh();
    }

    private void updateSyncBanner() {
        if (recording) {
            syncBanner.setVisibility(View.VISIBLE);
            return;
        }
        String k = prefs.getString("nexara_key", "");
        String lk = prefs.getString("llm_key", "");
        boolean hasKey = k != null && !k.isEmpty();
        boolean hasLlm = lk != null && !lk.isEmpty();

        String text;
        int iconColor = ThemeManager.accent(this);
        String icon = "✓";
        boolean problem = true;

        if (!hasKey || !hasLlm) {
            text = "Введите ключи Nexara и PlusVibe в Настройках";
            icon = "!";
            iconColor = 0xFFFF1744;
        } else if (RelayService.lastError != null) {
            String e = RelayService.lastError;
            if (e.length() > 50) e = e.substring(0, 50) + "…";
            text = "Ошибка: " + e;
            icon = "!";
            iconColor = 0xFFFF1744;
        } else {
            int queued = 0;
            int unproc = 0;
            for (NotesStore.Note n : allNotes) {
                if ("queued".equals(n.status) || "sending".equals(n.status)) queued++;
                if (n.text != null && !n.text.isEmpty()
                        && (n.summary == null || n.summary.isEmpty())) unproc++;
            }
            if (queued > 0) {
                text = queued + " ждут отправки с часов…";
                icon = "⇪";
                iconColor = ThemeManager.accent(this);
            } else if (unproc > 0) {
                text = "Обрабатываю через LLM…";
                icon = "…";
                iconColor = ThemeManager.accent(this);
            } else {
                // Всё чисто — баннер не занимаeт место
                problem = false;
                text = "";
            }
        }
        if (!problem) {
            syncBanner.setVisibility(View.GONE);
            return;
        }
        syncBanner.setVisibility(View.VISIBLE);
        syncText.setText(text);
        syncIcon.setText(icon);
        syncIcon.setTextColor(iconColor);
    }

    private class NoteAdapter extends ArrayAdapter<NotesStore.Note> {
        private int systemOffset = 0;

        NoteAdapter() { super(MainActivity.this, R.layout.item_note, visibleNotes); }

        void setSystemOffset(int v) { systemOffset = v; }

        private boolean isDeveloped(NotesStore.Note n) {
            if (n.system) return false;
            int[] st = devStats.get(n.id);
            return st != null && (st[0] > 0 || st[1] > 0);
        }

        @Override
        public int getViewTypeCount() { return 2; }

        @Override
        public int getItemViewType(int pos) {
            return isDeveloped(visibleNotes.get(pos)) ? 0 : 1;
        }

        @Override
        public View getView(int pos, View conv, ViewGroup parent) {
            NotesStore.Note n = visibleNotes.get(pos);
            int viewType = getItemViewType(pos);
            int wantLayout = viewType == 0 ? R.layout.item_note : R.layout.item_note_compact;

            if (conv == null || ((Integer) (conv.getTag() != null ? conv.getTag() : -1)) != wantLayout) {
                conv = LayoutInflater.from(MainActivity.this).inflate(wantLayout, parent, false);
                conv.setTag(wantLayout);
            }

            String title;
            if (n.summary != null && !n.summary.isEmpty()) {
                title = n.summary;
            } else {
                title = (n.text != null && !n.text.isEmpty())
                        ? n.text : "Запись " + n.durSec + "с";
            }

            TextView icon = conv.findViewById(R.id.icon);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            float corner = (viewType == 0 ? 10 : 8) * getResources().getDisplayMetrics().density;
            d.setCornerRadius(corner);
            if (n.system) {
                icon.setText("⚙");
                d.setColor(ThemeManager.softBg(MainActivity.this));
            } else {
                icon.setText(typeIcon(n.type));
                d.setColor(typeBgColor(n.type));
            }
            icon.setBackground(d);

            if (viewType == 0) {
                // Крупная карточка: развитая мысль
                TextView titleV = conv.findViewById(R.id.title);
                if (title.length() > 90) title = title.substring(0, 90) + "…";
                titleV.setText(title);

                TextView prev = conv.findViewById(R.id.preview);
                String preview;
                if ("list".equals(n.type) && n.items != null && !n.items.isEmpty()) {
                    preview = joinItems(n.items);
                } else if (n.text != null && !n.text.isEmpty()) {
                    preview = n.text;
                } else if ("queued".equals(n.status) || "sending".equals(n.status)) {
                    preview = "⇪ отправляется с часов…";
                } else {
                    preview = new SimpleDateFormat("HH:mm, dd.MM", Locale.getDefault())
                        .format(new Date(n.ts)) + " · " + n.durSec + "с";
                }
                if (preview.length() > 110) preview = preview.substring(0, 110) + "…";
                prev.setText(preview);

                int[] st = devStats.get(n.id);
                TextView devMeta = conv.findViewById(R.id.devMeta);
                if (st != null && (st[0] > 0 || st[1] > 0)) {
                    StringBuilder sb = new StringBuilder();
                    if (st[0] > 0) sb.append(st[0]).append(st[0] == 1 ? " слой" : (st[0] < 5 ? " слоя" : " слоёв"));
                    if (st[1] > 0) {
                        if (sb.length() > 0) sb.append(" · ");
                        sb.append(st[1]).append(st[1] == 1 ? " связь" : (st[1] < 5 ? " связи" : " связей"));
                    }
                    devMeta.setText(sb.toString());
                    devMeta.setVisibility(View.VISIBLE);
                } else {
                    devMeta.setVisibility(View.GONE);
                }

                TextView calChip = conv.findViewById(R.id.calChip);
                if (n.dueIso != null && !n.dueIso.isEmpty()) {
                    try {
                        long ev = RelayService.parseIsoToMillis(n.dueIso);
                        calChip.setText("⏰ " + ReminderFmt.format(ev));
                        calChip.setVisibility(View.VISIBLE);
                    } catch (Exception e) {
                        calChip.setVisibility(View.GONE);
                    }
                } else {
                    calChip.setVisibility(View.GONE);
                }
            } else {
                // Компактная строка
                TextView titleV = conv.findViewById(R.id.title);
                if (title.length() > 60) title = title.substring(0, 60) + "…";
                titleV.setText(title);
                titleV.setTextColor(n.system ? ThemeManager.accent(MainActivity.this)
                                             : ThemeManager.TEXT_PRIMARY);
                TextView timeV = conv.findViewById(R.id.time);
                if (n.system) {
                    timeV.setText(n.text);
                    timeV.setTextColor(ThemeManager.accent(MainActivity.this));
                } else {
                    timeV.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(n.ts)));
                    timeV.setTextColor(ThemeManager.TEXT_SECONDARY);
                }
            }

            return conv;
        }
    }

    // ---------- bottom sheet: действия с заметкой ----------

    private void showNoteActions(final NotesStore.Note n) {
        final Dialog dlg = new Dialog(this);
        dlg.setContentView(R.layout.dialog_note_actions);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().setGravity(Gravity.BOTTOM);
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.setCanceledOnTouchOutside(true);
        }

        String title = (n.summary != null && !n.summary.isEmpty()) ? n.summary
            : ((n.text != null && !n.text.isEmpty()) ? n.text : "Заметка");
        if (title.length() > 70) title = title.substring(0, 70) + "…";

        // Смахнуть шторку вниз: тянем за ручку, заголовок или пустое место
        final float[] startY = new float[1];
        View.OnTouchListener swipeDown = (v, ev) -> {
            switch (ev.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startY[0] = ev.getRawY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dy = ev.getRawY() - startY[0];
                    if (dy > 40 * getResources().getDisplayMetrics().density) {
                        dlg.dismiss();
                        return true;
                    }
                    return true;
            }
            return false;
        };
        View handle = dlg.findViewById(R.id.sheetHandle);
        if (handle != null) handle.setOnTouchListener(swipeDown);
        TextView titleV = dlg.findViewById(R.id.sheetTitle);
        titleV.setText(title);
        titleV.setOnTouchListener(swipeDown);

        LinearLayout box = dlg.findViewById(R.id.sheetActions);
        addSheetRow(box, "📋 Копировать текст", 0xFF333333, v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String txt = (n.text != null && !n.text.isEmpty()) ? n.text : (n.summary != null ? n.summary : "");
            cm.setPrimaryClip(ClipData.newPlainText("potok", txt));
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
            dlg.dismiss();
        });
        addSheetRow(box, "✏️ Редактировать", 0xFF333333, v -> {
            Intent i = new Intent(this, EditNoteActivity.class);
            i.putExtra("id", n.id);
            startActivity(i);
            dlg.dismiss();
        });
        addSheetRow(box, "➕ Дополнить мысль", 0xFF333333, v -> {
            dlg.dismiss();
            showAddDialog(n);
        });
        addSheetRow(box, "🔬 Разобрать", 0xFF333333, v -> {
            Intent i = new Intent(this, ThoughtDetailActivity.class);
            i.putExtra("note_id", n.id);
            startActivity(i);
            dlg.dismiss();
        });
        addSheetRow(box, "🗑 Удалить", 0xFFD32F2F, v -> {
            dlg.dismiss();
            new android.app.AlertDialog.Builder(this)
                .setMessage("Удалить заметку?")
                .setPositiveButton("Удалить", (dd, w) -> {
                    ReminderScheduler.cancel(this, n.id);
                    store.delete(n.id);
                    refresh();
                    updateSyncBanner();
                })
                .setNegativeButton("Отмена", null)
                .show();
        });

        dlg.show();
    }

    private void addSheetRow(LinearLayout box, String text, int color, View.OnClickListener l) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(15);
        row.setTextColor(color);
        float density = getResources().getDisplayMetrics().density;
        row.setPadding((int) (20 * density), (int) (13 * density),
            (int) (20 * density), (int) (13 * density));
        row.setBackground(getDrawable(android.R.drawable.list_selector_background));
        row.setOnClickListener(l);
        box.addView(row);
    }

    // ---------- дополнить мысль своим текстом ----------

    private void showAddDialog(final NotesStore.Note n) {
        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("Дополнить мысль");
        final EditText input = new EditText(this);
        input.setHint("Своя идея, уточнение, следующий шаг…");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        float density = getResources().getDisplayMetrics().density;
        input.setPadding((int) (16 * density), (int) (12 * density),
            (int) (16 * density), (int) (12 * density));
        b.setView(input);
        b.setPositiveButton("Добавить", (dd, w) -> {
            String txt = input.getText().toString().trim();
            if (txt.isEmpty()) return;
            String sum = txt.replaceAll("\\s+", " ");
            String[] words = sum.split(" ");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(words.length, 5); i++) {
                if (i > 0) sb.append(" ");
                sb.append(words[i]);
            }
            store.createChildNote(n.id, txt, sb.toString(), n.type, "me", "mine");
            refresh();
            updateSyncBanner();
            Toast.makeText(this, "Добавлено в цепочку", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("Отмена", null);
        b.show();
    }

    // ---------- запись с телефона ----------

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            recFile = new File(getCacheDir(), "rec_" + ts + ".m4a");

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            recording = true;
            startTime = System.currentTimeMillis();
            recFab.setImageResource(R.drawable.ic_stop);
            recFab.setBackgroundResource(R.drawable.bg_fab_rec);
            cancelBtn.setVisibility(View.VISIBLE);
            timerHandler.postDelayed(timerTick, 1000);
        } catch (Exception e) {
            recording = false;
            Toast.makeText(this, "Ошибка записи: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cancelRecording() {
        if (!recording) return;
        recording = false;
        timerHandler.removeCallbacks(timerTick);
        try { recorder.stop(); recorder.release(); recorder = null; } catch (Exception e) {}
        if (recFile != null && recFile.exists()) recFile.delete();
        recFab.setImageResource(R.drawable.ic_mic);
        recFab.setBackgroundResource(R.drawable.bg_fab);
        cancelBtn.setVisibility(View.GONE);
        Toast.makeText(this, "Запись отменена", Toast.LENGTH_SHORT).show();
        updateSyncBanner();
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        timerHandler.removeCallbacks(timerTick);
        try { recorder.stop(); recorder.release(); recorder = null; } catch (Exception e) {}

        recFab.setImageResource(R.drawable.ic_mic);
        recFab.setBackgroundResource(R.drawable.bg_fab);
        cancelBtn.setVisibility(View.GONE);
        syncText.setText("Распознавание…");
        syncIcon.setText("…");
        syncIcon.setTextColor(ThemeManager.accent(this));

        final File f = recFile;
        new Thread(() -> transcribeAndSave(f)).start();
    }

    private void transcribeAndSave(File f) {
        SttProvider stt = ProviderFactory.createSttProvider(this);
        if (stt == null) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Задайте ключ STT в Настройках", Toast.LENGTH_LONG).show();
                updateSyncBanner();
            });
            return;
        }

        try {
            byte[] audioData = java.nio.file.Files.readAllBytes(f.toPath());
            String text = stt.transcribe(audioData, f.getName(), "ru");
            int durSec = Math.max(1, (int) ((System.currentTimeMillis() - startTime) / 1000));
            int sizeBytes = (int) f.length();
            processTranscript(text, durSec, sizeBytes);
            f.delete();
        } catch (Exception e) {
            final String msg = e.getMessage() != null ? e.getMessage() : "ошибка";
            runOnUiThread(() -> {
                Toast.makeText(this, "Ошибка: " + msg, Toast.LENGTH_LONG).show();
                updateSyncBanner();
            });
        }
    }

    /**
     * Обработка текста заметки: LLM-разбор (тип/суть/время/пункты) + сохранение +
     * планирование напоминания + автосвязи + broadcast.
     *
     * Используется двумя путями:
     *  - голосовой поток: STT отдаёт текст → processTranscript(text, durSec, sizeBytes);
     *  - ручная текстовая заметка: пользователь вводит текст → processTranscript(text, 0, 0).
     * STT здесь нет — только LLM-разбор готового текста.
     */
    private void processTranscript(final String text, int durSec, int sizeBytes) {
        String type = "note";
        String summary = "";
        String eventTitle = "";
        String dueIso = null;
        Integer remindOffset = null;
        List<String> items = null;
        try {
            LlmProvider llm = ProviderFactory.createLlmProvider(this);
            LlmExtractor.Struct st = llm != null ? llm.extract(text) : LlmExtractor.parseResponse(null);
            type = st.type;
            summary = st.summary;
            eventTitle = st.eventTitle;
            dueIso = st.dueIso;
            remindOffset = st.remindOffsetMin;
            items = st.items;
        } catch (Exception e) {}

        NotesStore store = new NotesStore(this);
        NotesStore.Note n = new NotesStore.Note();
        n.id = System.currentTimeMillis();
        n.ts = System.currentTimeMillis();
        n.durSec = durSec;
        n.sizeBytes = sizeBytes;
        n.text = text;
        n.transcript = text;
        n.status = "done";
        n.type = type;
        n.summary = summary;
        n.eventTitle = eventTitle;
        n.dueIso = dueIso;
        n.remindOffsetMin = remindOffset;
        n.items = CheckItem.wrap(items);

        store.addOrUpdate(n);
        // Локальное напоминание — AlarmManager будит в dueIso − remindOffset.
        // (заменило интеграцию с системным календарём)
        if (dueIso != null && ("reminder".equals(type) || "task".equals(type))) {
            ReminderScheduler.schedule(this, n);
        }
        // State tracking: если LLM не отработал — в очередь
        if (n.summary != null && !n.summary.isEmpty()) {
            store.markDone(n.id);
            // Автосвязи с существующими мыслями (фон)
            AutoLinker.linkAsync(MainActivity.this, n.id);
        } else {
            store.markFailed(n.id, "LLM returned empty summary (phone)");
            ProcessingQueue.getInstance(this).trigger();
        }

        RecordWidgetProvider.updateAll(this);

        sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));

        final String finType = type;
        final String finSummary = summary;
        runOnUiThread(() -> {
            updateSyncBanner();
            Toast.makeText(this, "Сохранено: " + finType
                    + (finSummary.isEmpty() ? "" : " · " + finSummary), Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Диалог ввода ручной текстовой заметки (без записи голосом).
     * Многострочный EditText → processTranscript(text, 0, 0) в фоне.
     * LLM-разбор работает как для голосовых: тип/суть/время/чек-лист.
     */
    private void showTextNoteDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Напечатайте мысль, дело или список…");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        float density = getResources().getDisplayMetrics().density;
        int p = (int) (16 * density);
        input.setPadding(p, p, p, p);
        input.setMinLines(3);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Текстовая заметка")
            .setView(input)
            .setPositiveButton("Сохранить", (d, w) -> {
                String txt = input.getText().toString().trim();
                if (txt.isEmpty()) return;
                syncText.setText("Обработка…");
                syncIcon.setText("…");
                syncIcon.setTextColor(ThemeManager.accent(this));
                new Thread(() -> processTranscript(txt, 0, 0)).start();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    // ---------- авто-обработка необработанных (без кнопки) ----------

    private void autoProcess() {
        if (autoProcessing) return;
        boolean hasUnprocessed = false;
        for (NotesStore.Note n : allNotes) {
            if (n.text != null && !n.text.isEmpty()
                    && (n.summary == null || n.summary.isEmpty())) {
                hasUnprocessed = true;
                // Убедиться что state=failed для очереди (для мигрированных заметок)
                if (!NotesDbHelper.STATE_FAILED.equals(n.state)
                        && !NotesDbHelper.STATE_PENDING.equals(n.state)) {
                    new NotesStore(this).markFailed(n.id, "autoProcess: empty summary");
                }
                break;
            }
        }
        if (!hasUnprocessed) {
            updateSyncBanner();
            return;
        }
        autoProcessing = true;
        updateSyncBanner();

        // Делегируем в ProcessingQueue — он обработает с retry и backoff
        ProcessingQueue.getInstance(this).trigger();

        // Через 3с проверим результат
        timerHandler.postDelayed(() -> {
            autoProcessing = false;
            runOnUiThread(() -> {
                refresh();
                updateSyncBanner();
            });
        }, 3000);
    }

    static String joinItems(List<CheckItem> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null) return "";
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(" • ");
            CheckItem ci = items.get(i);
            sb.append(ci.done ? "☑ " : "☐ ").append(ci.text);
        }
        return sb.toString();
    }

    static String typeIcon(String type) {
        if ("idea".equals(type)) return "💡";
        if ("reminder".equals(type)) return "⏰";
        if ("task".equals(type)) return "☑";
        if ("list".equals(type)) return "📋";
        if ("note".equals(type)) return "📝";
        return "•";
    }

    private int typeBgColor(String type) {
        if ("idea".equals(type)) return 0xFFFFFDE7;
        if ("reminder".equals(type)) return 0xFFFFE0B2;
        if ("task".equals(type)) return 0xFFF3E5F5;
        if ("list".equals(type)) return 0xFFE0F7FA;
        return 0xFFECEFF1; // note
    }
}
