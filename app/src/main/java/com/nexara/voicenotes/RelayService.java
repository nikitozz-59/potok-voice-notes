package com.nexara.voicenotes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;

/**
 * Фоновый сервис: держит NanoServer на 127.0.0.1:8080, обрабатывает /ping и /transcribe.
 * Декодирует Zepp-OPUS → WAV → Nexara, хранит транскрипт в NotesStore.
 * Ключ Nexara берёт из SharedPreferences ("vn" / "nexara_key") — меняется в SettingsActivity.
 */
public class RelayService extends Service {
    static final String ACTION_NOTE_ADDED = "vn.NOTE_ADDED";
    private static final String CHAN = "vn_relay";
    private static final int NOTIF_ID = 1;

    /** Последняя ошибка транскрипции — для отладки без logcat. */
    static volatile String lastError = null;

    private NanoServer server;
    private NotesStore store;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new NotesStore(this);
        prefs = getSharedPreferences("vn", Context.MODE_PRIVATE);
        createChannel();
        try {
            server = new NanoServer(8080, this);
            new Thread(server, "vn-server").start();
            startForegroundCompat("Релей активен · :8080");
            // Запуск фоновой очереди обработки с retry
            ProcessingQueue.getInstance(this).start();
        } catch (Exception e) {
            Log.e("vn-relay", "start err", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        ProcessingQueue.getInstance(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    String getKey() { return ProviderFactory.getNexaraKey(this); }
    boolean hasKey() { String k = getKey(); return k != null && !k.isEmpty(); }

    /** Обработка POST /transcribe — вызывается из NanoServer. Возвращает JSON-ответ для side. */
    String handleTranscribe(String body) {
        try {
            JSONObject req = new JSONObject(body);
            String audioB64 = req.optString("audio", "");
            String name = req.optString("name", "rec.opus");
            String lang = req.optString("language", "ru");
            if (audioB64.isEmpty()) return jsonError("no audio");

            byte[] opusBytes = Base64.decode(audioB64, Base64.NO_WRAP);
            Log.i("vn-relay", "zepp " + opusBytes.length + "B → decode");
            byte[] pcm = OpusDecoderWrapper.decode(opusBytes);
            byte[] wav = WavWriter.write(pcm, 16000, 1, 16);
            Log.i("vn-relay", "pcm " + pcm.length + "B → wav " + wav.length + "B → nexara");

            String key = getKey();
            if (key == null || key.isEmpty()) return jsonError("нет ключа STT — задайте в Настройках");

            // STT через провайдер (nexara или openai-whisper)
            SttProvider stt = ProviderFactory.createSttProvider(this);
            if (stt == null) return jsonError("нет ключа STT — задайте в Настройках");
            String text = stt.transcribe(wav, wavName(name), lang);
            Log.i("vn-relay", "stt(" + stt.getName() + ") 200, text " + text.length() + " chars");

            NotesStore.Note n = new NotesStore.Note();
            n.id = parseId(name);
            n.ts = n.id; // ID = timestamp с часов, а не время телефона
            n.durSec = pcm.length / 32000;
            n.sizeBytes = opusBytes.length;
            n.text = text;
            n.transcript = text;
            n.status = "done";

            try {
                LlmProvider llm = ProviderFactory.createLlmProvider(this);
                LlmExtractor.Struct st = llm != null
                    ? llm.extract(text)
                    : LlmExtractor.parseResponse(null);
                n.type = st.type;
                n.summary = st.summary;
                n.eventTitle = st.eventTitle;
                n.dueIso = st.dueIso;
                n.remindOffsetMin = st.remindOffsetMin;
                n.items = CheckItem.wrap(st.items);
                n.raw = st.raw;
                Log.i("vn-relay", "llm type=" + st.type + " due=" + st.dueIso + " remind=" + st.remindOffsetMin);
            } catch (Exception e) {
                Log.w("vn-relay", "llm failed: " + e.getMessage());
            }

            // Локальное напоминание — AlarmManager будит в dueIso − remindOffset.
            // (заменило интеграцию с системным календарём). Повторная отправка
            // той же заметки с часов безопасна: schedule перезаписывает
            // существующий PendingIntent (FLAG_UPDATE_CURRENT).
            if (n.dueIso != null
                    && ("reminder".equals(n.type) || "task".equals(n.type))) {
                ReminderScheduler.schedule(this, n);
            }

            store.addOrUpdate(n);
            lastError = null;

            // Отслеживание состояния для очереди обработки
            boolean llmOk = n.summary != null && !n.summary.isEmpty();
            if (llmOk) {
                store.markDone(n.id);
            } else {
                // LLM не сработал — в очередь на retry
                store.markFailed(n.id, "LLM returned empty summary");
                ProcessingQueue.getInstance(this).trigger();
            }

            RecordWidgetProvider.updateAll(this);

            android.content.Intent bc = new android.content.Intent(ACTION_NOTE_ADDED);
            sendBroadcast(bc);

            return "{\"text\":" + jsonString(text) + "}";
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
            Log.e("vn-relay", "transcribe err: " + msg, e);
            String low = msg.toLowerCase();
            boolean transientErr = low.contains("abort") || low.contains("reset")
                    || low.contains("timed out") || low.contains("timeout")
                    || low.contains("unable to resolve") || low.contains("failed to connect")
                    || low.contains("stream closed") || low.contains("eof");
            if (transientErr) {
                // Не «ошибка навсегда»: заметку оставляем в очереди — часы повторят.
                lastError = null;
                android.content.Intent bc = new android.content.Intent(ACTION_NOTE_ADDED);
                sendBroadcast(bc);
                return "{\"transient\":true,\"error\":" + jsonString(msg) + "}";
            }
            lastError = msg;
            android.content.Intent bc = new android.content.Intent(ACTION_NOTE_ADDED);
            sendBroadcast(bc);
            return jsonError("relay: " + msg);
        }
    }

    private static String wavName(String opusName) {
        if (opusName.endsWith(".opus")) return opusName.substring(0, opusName.length() - 5) + ".wav";
        return opusName + ".wav";
    }

    public static long parseIsoToMillis(String iso) {
        if (iso == null || iso.isEmpty()) return System.currentTimeMillis();
        try {
            String tzFix = iso.trim();
            // Z → +00:00 (UTC)
            if (tzFix.endsWith("Z")) tzFix = tzFix.substring(0, tzFix.length() - 1) + "+00:00";
            // Дописываем секунды если их нет: "2026-10-24T09:00" → "2026-10-24T09:00:00"
            if (tzFix.length() == 16) tzFix += ":00";
            // Если есть смещение (+03:00 или -05:00) — парсим с ним
            if (tzFix.length() > 19) {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(tzFix);
                return odt.toInstant().toEpochMilli();
            }
            // Нет смещения → наивная дата, интерпретируем как ЛОКАЛЬНОЕ время устройства
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(tzFix);
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                // Fallback: SimpleDateFormat с локальным поясом
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getDefault());
                return f.parse(iso.trim()).getTime();
            } catch (Exception e2) {
                Log.w("vn-relay", "parseIso failed: " + iso);
                return System.currentTimeMillis();
            }
        }
    }

    private static long parseId(String name) {
        try {
            int s = name.indexOf("note_");
            int e = name.lastIndexOf('.');
            if (s >= 0 && e > s) return Long.parseLong(name.substring(s + 5, e));
        } catch (Exception e) {}
        return System.currentTimeMillis();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHAN, "Релей", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundCompat(String text) {
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder b = new Notification.Builder(this, CHAN);
            b.setSmallIcon(android.R.drawable.ic_btn_speak_now);
            b.setContentTitle(getString(R.string.service_title));
            b.setContentText(text);
            b.setOngoing(true);
            n = b.build();
        } else {
            Notification.Builder b = new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_btn_speak_now);
            b.setContentTitle(getString(R.string.service_title));
            b.setContentText(text);
            b.setOngoing(true);
            n = b.build();
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    static String jsonError(String msg) {
        return "{\"error\":" + jsonString(msg) + "}";
    }

    /** Все заметки в JSON — для GET /sync (часы подтягивают изменения с телефона). */
    String getSyncData() {
        try {
            java.util.List<NotesStore.Note> notes = store.list();
            StringBuilder sb = new StringBuilder("{\"notes\":[");
            boolean first = true;
            for (NotesStore.Note n : notes) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(n.id)
                  .append(",\"ts\":").append(n.ts)
                  .append(",\"dur\":").append(n.durSec)
                  .append(",\"size\":").append(n.sizeBytes)
                  .append(",\"text\":").append(jsonString(n.text == null ? "" : n.text))
                  .append(",\"status\":").append(jsonString(n.status == null ? "done" : n.status))
                  .append(",\"type\":").append(jsonString(n.type == null ? "note" : n.type))
                  .append(",\"summary\":").append(jsonString(n.summary == null ? "" : n.summary));
                if (n.dueIso != null) sb.append(",\"due_iso\":").append(jsonString(n.dueIso));
                if (n.calendarEventId > 0) sb.append(",\"calId\":").append(n.calendarEventId);
                if (n.items != null && !n.items.isEmpty()) {
                    sb.append(",\"items\":[");
                    for (int i = 0; i < n.items.size(); i++) {
                        if (i > 0) sb.append(",");
                        CheckItem ci = n.items.get(i);
                        sb.append("{\"t\":").append(jsonString(ci.text == null ? "" : ci.text))
                          .append(",\"d\":").append(ci.done).append("}");
                    }
                    sb.append("]");
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            return jsonError("sync: " + e.getMessage());
        }
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
