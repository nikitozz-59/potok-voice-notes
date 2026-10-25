package com.nexara.voicenotes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.util.List;

/**
 * Виджет рабочего стола Android:
 * - Кнопка микрофона сразу запускает запись в MainActivity.
 * - Показывает последнюю заметку или подсказку.
 */
public class RecordWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager man = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, RecordWidgetProvider.class);
        int[] ids = man.getAppWidgetIds(cn);
        for (int id : ids) {
            updateWidget(context, man, id);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager man, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_record);

        // Клик по кнопке микрофона — запуск MainActivity сразу в режиме записи.
        Intent recIntent = new Intent(context, MainActivity.class);
        recIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        recIntent.putExtra("start_recording", true);
        PendingIntent piRec = PendingIntent.getActivity(
                context, 101, recIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetBtnRec, piRec);

        // Клик по остальной части карточки — просто открыть список заметок.
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(
                context, 102, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetRoot, piOpen);

        // Подтягиваем последнюю заметку
        try {
            NotesStore store = new NotesStore(context);
            List<NotesStore.Note> all = store.list();
            if (!all.isEmpty()) {
                NotesStore.Note last = all.get(all.size() - 1);
                String title = (last.summary != null && !last.summary.isEmpty())
                        ? last.summary
                        : ((last.text != null && !last.text.isEmpty()) ? last.text : "Заметка");
                if (title.length() > 30) title = title.substring(0, 30) + "…";
                views.setTextViewText(R.id.widgetTitle, MainActivity.typeIcon(last.type) + " " + title);
                views.setTextViewText(R.id.widgetSub, "Тап для записи");
            } else {
                views.setTextViewText(R.id.widgetTitle, "Поток");
                views.setTextViewText(R.id.widgetSub, "Нажмите для записи");
            }
        } catch (Exception e) {
            views.setTextViewText(R.id.widgetTitle, "Поток");
            views.setTextViewText(R.id.widgetSub, "Нажмите для записи");
        }

        man.updateAppWidget(widgetId, views);
    }
}
