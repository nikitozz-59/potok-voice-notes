package com.nexara.voicenotes;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Минимальный HTTP-сервер на 127.0.0.1:8080. Никаких зависимостей — только ServerSocket.
 * Маршруты: GET /ping, POST /transcribe (JSON {audio, name, language}).
 * Side-service Zepp App шлёт сюда те же запросы, что раньше шли в python relay.py.
 */
class NanoServer implements Runnable {
    private final int port;
    private final RelayService svc;
    private ServerSocket ssock;
    private volatile boolean running = true;
    // Cached-пул: один «зависший» клиент (Zepp App рвёт соединение) не блокирует остальные.
    private final ExecutorService pool = Executors.newCachedThreadPool();

    NanoServer(int port, RelayService svc) {
        this.port = port;
        this.svc = svc;
    }

    public void run() {
        try {
            ssock = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
            Log.i("vn-server", "listening on 127.0.0.1:" + port);
            while (running) {
                Socket s = ssock.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            if (running) Log.e("vn-server", "accept err", e);
        }
    }

    void stop() {
        running = false;
        try { if (ssock != null) ssock.close(); } catch (Exception e) {}
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            BufferedInputStream bis = new BufferedInputStream(s.getInputStream(), 8192);
            byte[] head = readHeaders(bis);
            String headStr = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = headStr.split("\r\n");
            String[] reqLine = lines[0].split(" ");
            String method = reqLine[0];
            String path = reqLine.length > 1 ? reqLine[1] : "/";
            int contentLen = 0;
            for (String l : lines) {
                if (l.toLowerCase().startsWith("content-length:")) {
                    try { contentLen = Integer.parseInt(l.substring(15).trim()); } catch (Exception e) {}
                }
            }
            byte[] body = readBody(bis, contentLen);

            if ("GET".equals(method) && "/ping".equals(pathNoQuery(path))) {
                respond(s, 200, "{\"pong\":true,\"service\":\"vn-android\","
                        + "\"libopus\":true,\"key\":" + (svc.hasKey()) + "}");
            } else if ("GET".equals(method) && "/sync".equals(pathNoQuery(path))) {
                // Пагинация: since=updated_at курсор (включительно не берём — next_since=+1),
                // limit=макс. заметок в ответе. Без параметров — полный дамп (легаси-часы).
                java.util.Map<String, String> q = parseQuery(path);
                long since = 0;
                int limit = 0;
                try {
                    String sv = q.get("since");
                    if (sv != null && !sv.isEmpty()) since = Long.parseLong(sv);
                    String lv = q.get("limit");
                    if (lv != null && !lv.isEmpty()) limit = Math.max(1, Math.min(200, Integer.parseInt(lv)));
                } catch (Exception ignore) {}
                respond(s, 200, svc.getSyncData(since, limit));
            } else if ("POST".equals(method) && "/transcribe".equals(pathNoQuery(path))) {
                String resp = svc.handleTranscribe(new String(body, StandardCharsets.UTF_8));
                respond(s, 200, resp);
            } else {
                respond(s, 404, RelayService.jsonError("not found"));
            }
        } catch (java.net.SocketException e) {
            // Клиент (Zepp App) оборвал соединение — обычное дело при уходе BLE.
            // НЕ пишем в lastError и не пытаемся отвечать.
            Log.d("vn-server", "client abort: " + e.getMessage());
        } catch (Exception e) {
            Log.w("vn-server", "handle err: " + e);
            try { respond(s, 500, RelayService.jsonError("server: " + e.getMessage())); } catch (Exception e2) {}
        } finally {
            try { s.close(); } catch (Exception e) {}
        }
    }

    private byte[] readHeaders(BufferedInputStream bis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        while (true) {
            int r = bis.read(one);
            if (r <= 0) break;
            bos.write(one[0]);
            byte[] a = bos.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                return java.util.Arrays.copyOf(a, n - 4); // без завершающего \r\n\r\n
            }
        }
        return bos.toByteArray();
    }

    private byte[] readBody(BufferedInputStream bis, int len) throws Exception {
        if (len <= 0) return new byte[0];
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int r = bis.read(body, off, len - off);
            if (r < 0) break;
            off += r;
        }
        return body;
    }

    /** Путь без query-части: "/sync?since=..&limit=.." → "/sync". */
    static String pathNoQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    /** Примитивный parse query string: a=1&b=x%20y → {a:"1", b:"x y"}. */
    static java.util.Map<String, String> parseQuery(String path) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        int q = path.indexOf('?');
        if (q < 0) return out;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? dec(pair.substring(0, eq)) : dec(pair);
            String v = eq >= 0 ? dec(pair.substring(eq + 1)) : "";
            if (!k.isEmpty()) out.put(k, v);
        }
        return out;
    }

    private static String dec(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void respond(Socket s, int code, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.ISO_8859_1));
        os.write(data);
        os.flush();
    }
}
