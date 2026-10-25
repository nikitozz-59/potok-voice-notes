package com.nexara.voicenotes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Ресивер локального напоминания — будит AlarmManager в момент
 * (dueIso − remindOffset). Показывает нотификацию от «Поток», тап
 * открывает заметку в ThoughtDetailActivity.
 *
 * Канал «Напоминания» с IMPORTANCE_HIGH — heads-up попап, звук.
 * Нотификация авто-закрывается по тапу (setAutoCancel).
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "vn-reminder-rx";
    public static final String CHANNEL = "potok_reminders";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long noteId = intent.getLongExtra("note_id", 0);
        String title = intent.getStringExtra("title");
        if (title == null || title.isEmpty()) title = "Напоминание";
        Log.i(TAG, "reminder fired: note=" + noteId + " title=" + title);

        ensureChannel(ctx);
        showNotification(ctx, noteId, title);
    }

    private void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Локальные напоминания из заметок");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void showNotification(Context ctx, long noteId, String title) {
        // Тап → открывает заметку
        Intent open = new Intent(ctx, ThoughtDetailActivity.class);
        open.putExtra("note_id", noteId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(ctx, (int) noteId, open, piFlags);

        // Тело — короткий текст заметки, если есть
        String body = "Открыть заметку";
        try {
            NotesStore store = new NotesStore(ctx);
            NotesStore.Note n = store.getById(noteId);
            if (n != null && n.text != null && !n.text.isEmpty()) {
                String t = n.text.replaceAll("\\s+", " ").trim();
                if (t.length() > 80) t = t.substring(0, 80) + "…";
                body = t;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not load note body: " + e.getMessage());
        }

        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        } else {
            n = new Notification.Builder(ctx)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        }

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            // notificationId = noteId — чтобы повторные не дублировались, а обновлялись
            nm.notify((int) noteId, n);
        }
    }
}
