package com.nexara.voicenotes;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * Планировщик локальных напоминаний через AlarmManager.
 *
 * Заменяет прежнюю интеграцию с системным календарем (CalendarWriter):
 * приложение само будит AlarmManager в момент (dueIso − remindOffset)
 * и показывает свою нотификацию. Не нужно разрешение на календарь и
 * аккаунт календаря на устройстве; напоминания не засоряют общий
 * календарь.
 *
 * Точка планирования — после LLM-обработки заметки (ProcessingQueue /
 * RelayService / ручной захват в MainActivity). rescheduleAll — на
 * старте приложения и после загрузки телефона (BootReceiver), чтобы
 * будильники пережили перезагрузку (AlarmManager не переживает).
 *
 * Надёжность: setExactAndAllowWhileIdle будит из Doze. На API 31+ без
 * grants SCHEDULE_EXACT_ALERTS падает в setAndAllowWhileIdle (неточный,
 * но всё равно сработает в окне Doze).
 */
public final class ReminderScheduler {

    private static final String TAG = "vn-reminder";

    private ReminderScheduler() {}

    /**
     * Запланировать напоминание для заметки. Если у заметки нет dueIso —
     * снять существующее (на случай, что время убрали при редактировании).
     * Если время уже прошло — не планировать.
     */
    public static void schedule(Context ctx, NotesStore.Note n) {
        if (n == null) return;
        if (n.dueIso == null || n.dueIso.isEmpty()) {
            cancel(ctx, n.id);
            return;
        }
        long eventMillis;
        try {
            eventMillis = RelayService.parseIsoToMillis(n.dueIso);
        } catch (Exception e) {
            Log.w(TAG, "bad dueIso for note " + n.id + ": " + n.dueIso);
            return;
        }

        long offsetMin = (n.remindOffsetMin != null && n.remindOffsetMin > 0) ? n.remindOffsetMin : 0;
        long triggerAt = eventMillis - offsetMin * 60_000L;
        long now = System.currentTimeMillis();
        if (triggerAt <= now) {
            // Время прошло — будильник не ставим, существующий снимаем
            Log.i(TAG, "note " + n.id + " due already passed, skip");
            cancel(ctx, n.id);
            return;
        }

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(ctx, n);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // Нет grants на точные будильники — неточный fallback
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.i(TAG, "scheduled (inexact) note " + n.id + " at " + triggerAt);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.i(TAG, "scheduled (exact) note " + n.id + " at " + triggerAt);
            }
        } catch (SecurityException se) {
            // На некоторых OEM setExactAndAllowWhileIdle бросает SecurityException
            // без гранта — откатываемся к неточному
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.w(TAG, "fallback to inexact for note " + n.id);
            } catch (Exception e2) {
                Log.e(TAG, "schedule failed: " + e2.getMessage());
            }
        }
    }

    /** Снять напоминание (удаление заметки / убрали время). */
    public static void cancel(Context ctx, long noteId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(buildPendingIntent(ctx, noteId, "", 0));
    }

    /**
     * Перепланировать все заметки с dueIso. Вызывать на старте приложения
     * и после BOOT_COMPLETED — AlarmManager не переживает перезагрузку.
     */
    public static void rescheduleAll(Context ctx) {
        try {
            NotesStore store = new NotesStore(ctx);
            int count = 0;
            for (NotesStore.Note n : store.list()) {
                if (n.dueIso != null && !n.dueIso.isEmpty()) {
                    schedule(ctx, n);
                    count++;
                }
            }
            Log.i(TAG, "rescheduleAll: " + count + " reminders");
        } catch (Exception e) {
            Log.e(TAG, "rescheduleAll failed: " + e.getMessage());
        }
    }

    // ---------- внутреннее ----------

    private static PendingIntent buildPendingIntent(Context ctx, NotesStore.Note n) {
        String title = (n.eventTitle != null && !n.eventTitle.isEmpty())
            ? n.eventTitle
            : ((n.summary != null && !n.summary.isEmpty()) ? n.summary : "Напоминание");
        return buildPendingIntent(ctx, n.id, title, n.ts);
    }

    private static PendingIntent buildPendingIntent(Context ctx, long noteId, String title, long ts) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction("com.nexara.voicenotes.REMINDER");
        intent.putExtra("note_id", noteId);
        intent.putExtra("title", title);
        intent.putExtra("ts", ts);
        // Уникальность по noteId — requestCode = младшие 32 бита id
        int requestCode = (int) noteId;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags);
    }
}
