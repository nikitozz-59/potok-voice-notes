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
    private static final int MAX_RETRIES = 5;

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
            // 1. Pending — новые заметки с транскриптом, ждущие LLM
            List<NotesStore.Note> pending = store.getByState(NotesDbHelper.STATE_PENDING);
            for (NotesStore.Note n : pending) {
                if (n.transcript != null && !n.transcript.isEmpty()) {
                    processNote(n);
                } else if (n.text != null && !n.text.isEmpty()) {
                    // Обратная совместимость: transcript может быть в text
                    n.transcript = n.text;
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
        // Отметить как processing
        store.updateState(n.id, NotesDbHelper.STATE_PROCESSING, null, n.retryCount);

        String transcript = (n.transcript != null && !n.transcript.isEmpty())
            ? n.transcript : n.text;
        if (transcript == null || transcript.isEmpty()) {
            store.markFailed(n.id, "empty transcript");
            return;
        }

        try {
            // LLM extraction через провайдер
            LlmProvider llm = ProviderFactory.createLlmProvider(ctx);
            if (llm == null) {
                store.markFailed(n.id, "no LLM key configured");
                return;
            }
            LlmExtractor.Struct st = llm.extract(transcript);

            if (st == null || st.summary == null || st.summary.isEmpty()
                    || "(тишина)".equals(st.summary) || "(ошибка разбора)".equals(st.summary)) {
                store.markFailed(n.id, "LLM returned empty/invalid: " + st.summary);
                return;
            }

            // Обновить заметку с результатами LLM
            n.type = st.type;
            n.summary = st.summary;
            n.eventTitle = st.eventTitle;
            n.dueIso = st.dueIso;
            n.remindOffsetMin = st.remindOffsetMin;
            n.items = CheckItem.wrap(st.items);
            n.raw = st.raw;
            n.transcript = transcript;
            n.state = NotesDbHelper.STATE_DONE; // будет перезаписано если календарь упадёт

            // Локальное напоминание — AlarmManager будит в dueIso − remindOffset.
            // (заменило интеграцию с системным календарём)
            if (n.dueIso != null
                    && ("reminder".equals(n.type) || "task".equals(n.type))) {
                ReminderScheduler.schedule(ctx, n);
            }

            // Сохранить и отметить как done
            store.addOrUpdate(n);
            store.markDone(n.id);
            Log.i(TAG, "note " + n.id + " done: type=" + n.type + " sum='" + n.summary + "'");

            // Автосвязи с существующими мыслями (фон, ошибки не критичны)
            AutoLinker.linkAsync(ctx, n.id);

            // Уведомить UI
            ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));

        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
            Log.e(TAG, "note " + n.id + " failed: " + error, e);
            store.markFailed(n.id, error);
            ctx.sendBroadcast(new Intent(RelayService.ACTION_NOTE_ADDED));
        }
    }
}
