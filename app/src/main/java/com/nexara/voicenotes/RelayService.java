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

    /**
     * Обработка POST /transcribe — вызывается из NanoServer. Возвращает JSON-ответ для side.
     *
     * v3.4: ОТВЯЗАН от STT+LLM. Декодирует Opus→WAV (быстро, локально), сохраняет
     * WAV в cacheDir, создаёт заметку с state=pending и ставит в ProcessingQueue.
     * STT и LLM идёт асинхронно в очереди — часы получают {accepted} мгновенно
     * и больше не висят 8-165с на облаке. Транскрипт приезжает на часы через SYNC.
     */
    String handleTranscribe(String body) {
        try {
            JSONObject req = new JSONObject(body);
            String audioB64 = req.optString("audio", "");
            String name = req.optString("name", "rec.opus");
            if (audioB64.isEmpty()) return jsonError("no audio");

            byte[] opusBytes = Base64.decode(audioB64, Base64.NO_WRAP);
            Log.i("vn-relay", "zepp " + opusBytes.length + "B → decode");
            byte[] pcm = OpusDecoderWrapper.decode(opusBytes);
            byte[] wav = WavWriter.write(pcm, 16000, 1, 16);
            Log.i("vn-relay", "pcm " + pcm.length + "B → wav " + wav.length + "B → queue");

            // id — явным полем из body (часы присылают свой id). Fallback на parseId(name)
            // только для совместимости со старыми часами; при расхождении часы не найдут
            // свою заметку в SYNC и зависнут — поэтому WARN.
            long id = req.optLong("id", 0);
            if (id <= 0) {
                id = parseId(name);
                Log.w("vn-relay", "no explicit id in body, parsed from name: " + id);
            }

            // Дедуп: если заметка с этим id уже обрабатывается (state=PROCESSING),
            // не затираем in-flight работу (CONFLICT_REPLACE сбросил бы state и retryCount,
            // перезаписал бы WAV). Просто подтверждаем приём — часы не висят.
            NotesStore.Note existing = store.getById(id);
            if (existing != null && NotesDbHelper.STATE_PROCESSING.equals(existing.state)) {
                Log.i("vn-relay", "note " + id + " already processing — dedup ack");
                return "{\"accepted\":true,\"id\":" + id + ",\"dedup\":true}";
            }

            // WAV во временный файл — ProcessingQueue сделает STT асинхронно.
            java.io.File wavFile = new java.io.File(getCacheDir(), "note_" + id + ".wav");
            java.nio.file.Files.write(wavFile.toPath(), wav);

            NotesStore.Note n = new NotesStore.Note();
            n.id = id;
            n.ts = id; // ID = timestamp с часов, а не время телефона
            n.durSec = pcm.length / 32000;
            n.sizeBytes = opusBytes.length;
            n.text = "";
            n.transcript = "";
            // status не выставляем — noteToCv вычисляет как проекцию state.
            n.type = "note";
            n.state = NotesDbHelper.STATE_PENDING;
            n.audioPath = wavFile.getAbsolutePath();
            n.updatedAt = System.currentTimeMillis();
            store.addOrUpdate(n);
            Log.i("vn-relay", "accepted note " + id + " → queue (wav " + wavFile.getName() + ")");

            // Асинхронная обработка: STT → LLM → reminder → broadcast.
            ProcessingQueue.getInstance(this).trigger();
            lastError = null;

            android.content.Intent bc = new android.content.Intent(ACTION_NOTE_ADDED);
            sendBroadcast(bc);

            return "{\"accepted\":true,\"id\":" + id + "}";
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
            Log.e("vn-relay", "transcribe err: " + msg, e);
            lastError = msg;
            android.content.Intent bc = new android.content.Intent(ACTION_NOTE_ADDED);
            sendBroadcast(bc);
            return jsonError("relay: " + msg);
        }
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
                  .append(",\"status\":").append(jsonString(
                      NotesStore.statusForState(n.state, n.retryCount)))
                  .append(",\"type\":").append(jsonString(n.type == null ? "note" : n.type))
                  .append(",\"summary\":").append(jsonString(n.summary == null ? "" : n.summary));
                if (n.dueIso != null) sb.append(",\"due_iso\":").append(jsonString(n.dueIso));
                if (n.calendarEventId > 0) sb.append(",\"calId\":").append(n.calendarEventId);
                // При терминальной ошибке — отдаём причину (укороченную), чтобы часы
                // показали «ОШИБКА: ...» вместо вечного «обработка…».
                String st = NotesStore.statusForState(n.state, n.retryCount);
                if ("error".equals(st) && n.lastError != null && !n.lastError.isEmpty()) {
                    String le = n.lastError;
                    if (le.length() > 120) le = le.substring(0, 117) + "…";
                    sb.append(",\"last_error\":").append(jsonString(le));
                }
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
