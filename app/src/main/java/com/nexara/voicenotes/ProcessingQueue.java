package com.nexara.voicenotes;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Фоновая очередь обработки заметок с retry и экспоненциальным backoff.
 *
 * Решает главную проблему: LLM-вызовы не имели retry (только autoProcess
 * в MainActivity когда приложение открыто). Теперь ProcessingQueue работает
 * в фоне, пока запущен RelayService, и автоматически обрабатывает:
 *
 * - state=pending: заметки с транскриптом, но без LLM-структуры
 * - state=failed: заметки с ошибкой, ретрай через backoff
 *
 * Backoff: 30s → 60s → 2min → 5min → 10min (макс 5 попыток).
 * После 5 неудач заметка остаётся в state=failed, видна в UI.
 *
 * Идемпотентность: календарь создаётся только если calendarEventId <= 0.
 */
public class ProcessingQueue {
    private static final String TAG = "vn-queue";

    private static final long CHECK_INTERVAL_SEC = 30;
    private static final int MAX_RETRIES = NotesDbHelper.MAX_RETRIES;
    /** PROCESSING старше этого считается зависшим (процесс убит) → восстановить в PENDING. */
    private static final long STALE_PROCESSING_MS = 5 * 60 * 1000; // 5 минут

    // Экспоненциальный backoff: [30, 60, 120, 300, 600] секунд
    private static final long[] BACKOFF_MS = {
        30_000, 60_000, 120_000, 300_000, 600_000
    };

    private final Context ctx;
    private final NotesStore store;
    private ScheduledExecutorService scheduler;
    private volatile boolean processing = false;

    private static ProcessingQueue instance;

    public static synchronized ProcessingQueue getInstance(Context ctx) {
        if (instance == null) {
            instance = new ProcessingQueue(ctx.getApplicationContext());
        }
        return instance;
    }

    private ProcessingQueue(Context ctx) {
        this.ctx = ctx;
        this.store = new NotesStore(ctx);
    }

    /** Запуск периодической проверки. Безопасно вызывать многократно. */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        // Восстановить зависшие PROCESSING (процесс убит во время STT/LLM) —
        // без этого они навсегда выпадают из getByState(PENDING).
        int recovered = store.recoverStaleProcessing(STALE_PROCESSING_MS);
        if (recovered > 0) Log.i(TAG, "recovered " + recovered + " stale PROCESSING notes");
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
            this::processOnce,
            5, // начальная задержка 5с
            CHECK_INTERVAL_SEC,
            TimeUnit.SECONDS
        );
        Log.i(TAG, "started, check every " + CHECK_INTERVAL_SEC + "s");
    }

    /** Остановка. Вызывается из RelayService.onDestroy. */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            Log.i(TAG, "stopped");
        }
    }

    /** Немедленный запуск обработки (не ждя таймера). */
    public void trigger() {
        if (processing) return;
        scheduler.submit(this::processOnce);
    }

    /**
     * Один цикл обработки: найти pending/failed заметки и обработать.
     */
    private void processOnce() {
        if (processing) return;
        processing = true;
        try {
            // 1. Pending — новые заметки с транскриптом, ждущие LLM,
            //    либо заметки с audioPath без транскрипта (асинхронный путь с часов:
            //    handleTranscribe сохраняет WAV и ставит в очередь, STT идёт здесь).
            List<NotesStore.Note> pending = store.getByState(NotesDbHelper.STATE_PENDING);
            for (NotesStore.Note n : pending) {
                if (n.transcript != null && !n.transcript.isEmpty()) {
                    processNote(n);
                } else if (n.text != null && !n.text.isEmpty()) {
                    // Обратная совместимость: transcript может быть в text
                    n.transcript = n.text;
                    processNote(n);
                } else if (n.audioPath != null && !n.audioPath.isEmpty()) {
                    // v3.4: заметка с часов — STT ещё не сделан, делается в processNote.
                    processNote(n);
                }
            }

            // 2. Failed — ретрай с backoff
            List<NotesStore.Note> failed = store.getByState(NotesDbHelper.STATE_FAILED);
            long now = System.currentTimeMillis();
            for (NotesStore.Note n : failed) {
                if (n.retryCount >= MAX_RETRIES) {
                    Log.i(TAG, "note " + n.id + " max retries reached (" + n.retryCount + ")");
                    continue;
                }
                long backoff = BACKOFF_MS[Math.min(n.retryCount, BACKOFF_MS.length - 1)];
                long elapsed = now - n.updatedAt;
                if (elapsed >= backoff) {
                    Log.i(TAG, "retrying note " + n.id + " attempt " + (n.retryCount + 1)
                        + " after " + (elapsed / 1000) + "s");
                    // Сброс в pending для обработки
                    store.updateState(n.id, NotesDbHelper.STATE_PENDING, null, n.retryCount);
                    n.state = NotesDbHelper.STATE_PENDING;
                    processNote(n);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "processOnce error: " + e.getMessage(), e);
        } finally {
            processing = false;
        }
    }

    /**
     * Обработка одной заметки: LLM → Calendar → markDone/markFailed.
     * Транскрипт уже есть (в n.transcript или n.text).
     */
    private void processNote(NotesStore.Note n) {
        Log.i(TAG, "processing note " + n.id + " state=" + n.state);

        // Атомарный захват PENDING→PROCESSING. Если другой воркЕР уже забрал — пропускаем.
        // Решает гонку повторной отправки с часов (CONFLICT_REPLACE затирал in-flight работу).
        if (!store.claimForProcessing(n.id)) {
            Log.i(TAG, "note " + n.id + " not in PENDING (already claimed/done) — skip");
            return;
        }
        n.state = NotesDbHelper.STATE_PROCESSING;

        String transcript = (n.transcript != null && !n.transcript.isEmpty())
            ? n.transcript : n.text;

        // v3.4: если транскрипта нет, но есть audioPath — STT из сохранённого WAV.
        // (handleTranscribe с часов сохраняет WAV и отвечает {accepted} мгновенно;
        // STT идёт здесь, асинхронно, не блокируя часы.)
        if ((transcript == null || transcript.isEmpty()) && n.audioPath != null && !n.audioPath.isEmpty()) {
            try {
                java.io.File wavFile = new java.io.File(n.audioPath);
                if (!wavFile.isFile()) {
                    failNote(n.id, "audio file missing: " + n.audioPath);
                    ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));
                    return;
                }
                SttProvider stt = ProviderFactory.createSttProvider(ctx);
                if (stt == null) {
                    failNote(n.id, "no STT key configured");
                    ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));
                    return;
                }
                byte[] wav = java.nio.file.Files.readAllBytes(wavFile.toPath());
                String text = stt.transcribe(wav, "note_" + n.id + ".wav", "ru");
                Log.i(TAG, "stt(" + stt.getName() + ") note " + n.id + ": " + text.length() + " chars");
                transcript = text;
                n.transcript = text;
                n.text = text;
                // Пишем ОБА поля — иначе при retry LLM (transcript уже в БД, STT-блок
                // пропускается) text остаётся пустым, часы получают пустой текст при done.
                store.updateTranscriptAndText(n.id, text);
            } catch (Throwable e) {
                // Throwable, не Exception — ловим OOM на длинной записи (readAllBytes WAV).
                String error = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                Log.e(TAG, "stt note " + n.id + " failed: " + error, e);
                failNote(n.id, "STT: " + error);
                ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));
                return;
            }
        }

        if (transcript == null || transcript.isEmpty()) {
            failNote(n.id, "empty transcript");
            return;
        }

        try {
            // LLM extraction через провайдер
            LlmProvider llm = ProviderFactory.createLlmProvider(ctx);
            if (llm == null) {
                failNote(n.id, "no LLM key configured");
                return;
            }
            LlmExtractor.Struct st = llm.extract(transcript);

            if (st == null || st.summary == null || st.summary.isEmpty()
                    || "(тишина)".equals(st.summary) || "(ошибка разбора)".equals(st.summary)) {
                failNote(n.id, "LLM returned empty/invalid: " + st.summary);
                return;
            }

            // Обновить заметку с результатами LLM.
            // status НЕ выставляем — noteToCv вычисляет как проекцию state.
            n.type = st.type;
            n.summary = st.summary;
            n.eventTitle = st.eventTitle;
            n.dueIso = st.dueIso;
            n.remindOffsetMin = st.remindOffsetMin;
            n.items = CheckItem.wrap(st.items);
            n.raw = st.raw;
            n.transcript = transcript;
            n.text = transcript;
            n.state = NotesDbHelper.STATE_DONE;

            // ПОРЯДОК: сначала persist результата, потом side effects.
            // Side effect (ReminderScheduler) не имеет права отменять результат:
            // на Android 12+ setExact без SCHEDULE_EXACT_ALARM бросает SecurityException.
            store.addOrUpdate(n);
            store.markDone(n.id);
            Log.i(TAG, "note " + n.id + " done: type=" + n.type + " sum='" + n.summary + "'");

            // Очистить временный WAV-файл — транскрипт сохранён, audioPath чистит markDone.
            if (n.audioPath != null) {
                try {
                    java.io.File f = new java.io.File(n.audioPath);
                    if (f.isFile()) f.delete();
                } catch (Exception ignore) {}
            }

            // Side effects — каждый в своём try/catch, ошибки не роняют результат.
            if (n.dueIso != null
                    && ("reminder".equals(n.type) || "task".equals(n.type))) {
                try {
                    ReminderScheduler.schedule(ctx, n);
                } catch (Throwable t) {
                    Log.e(TAG, "reminder schedule failed for note " + n.id + ": " + t.getMessage(), t);
                }
            }
            try {
                AutoLinker.linkAsync(ctx, n.id);
            } catch (Throwable t) {
                Log.w(TAG, "autolinker failed for note " + n.id + ": " + t.getMessage());
            }

            // Уведомить UI
            ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));

        } catch (Throwable e) {
            String error = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
            Log.e(TAG, "note " + n.id + " failed: " + error, e);
            failNote(n.id, error);
            ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));
        }
    }

    /**
     * Классификация ошибки и соответствующий fail.
     *
     * Permanent (нет ключа, auth, файл ушёл, LLM мусор) → markTerminalFailed:
     * retryCount сразу = MAX, ретраев не будет, часы видят «ОШИБКА» сразу,
     * а не через ~18 мин backoff. Плюс экономия денег — не шлём 5× запрос,
     * который никогда не succeed.
     *
     * Transient / unknown → markFailed: обычный retry с backoff. Unknown
     * считаем transient (безопаснее ретраить, чем терять заметку).
     */
    private void failNote(long id, String error) {
        if (RetryPolicy.isPermanent(error)) {
            Log.i(TAG, "note " + id + " permanent error → terminal: " + error);
            store.markTerminalFailed(id, error);
        } else {
            Log.i(TAG, "note " + id + " transient error → will retry: " + error);
            store.markFailed(id, error);
        }
    }
}
