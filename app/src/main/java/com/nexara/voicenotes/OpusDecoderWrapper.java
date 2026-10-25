package com.nexara.voicenotes;

import android.util.Log;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Декодер Zepp-OPUS контейнера → PCM через Concentus (pure Java port libopus).
 *
 * Контейнер от часов (см. relay.py): повторяющиеся кадры
 *   [4B длина BE][4B ?][OPUS payload длиной len байт]
 * Поведение 1:1 как у python-релея с libopus — никаких MediaCodec-квирков.
 * На выходе 16-bit little-endian PCM, 16 кГц, моно.
 */
public final class OpusDecoderWrapper {
    private static final String TAG = "vn-opus";
    private static final int SAMPLE_RATE = 16000;
    // Максимальный размер фрейма OPUS — 120 мс при 48 кГц = 5760 сэмплов.
    // При 16 кГц — 1920. Берём с запасом.
    private static final int MAX_FRAME = 5760;

    private OpusDecoderWrapper() {}

    public static byte[] decode(byte[] container) throws IOException {
        // Парсинг: [4B len BE][4B ?][payload] — повторяется.
        int n = 0;
        int p = 0;
        while (p + 8 <= container.length) {
            int len = ((container[p] & 0xFF) << 24) | ((container[p + 1] & 0xFF) << 16)
                    | ((container[p + 2] & 0xFF) << 8) | (container[p + 3] & 0xFF);
            p += 8;
            if (len <= 0 || p + len > container.length) break;
            n++;
            p += len;
        }
        if (n == 0) {
            throw new IOException("no opus frames (container " + container.length
                + "B, head=" + hexHead(container, 16) + ")");
        }

        Log.i(TAG, "container=" + container.length + "B frames=" + n);

        OpusDecoder dec;
        try {
            dec = new OpusDecoder(SAMPLE_RATE, 1);
        } catch (OpusException e) {
            throw new IOException("opus init: " + e.getMessage(), e);
        }

        try {
            short[] pcm = new short[MAX_FRAME];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int pos = 0;
            int decoded = 0;
            int totalSamples = 0;

            while (pos + 8 <= container.length) {
                int len = ((container[pos] & 0xFF) << 24) | ((container[pos + 1] & 0xFF) << 16)
                        | ((container[pos + 2] & 0xFF) << 8) | (container[pos + 3] & 0xFF);
                pos += 8;
                if (len <= 0 || pos + len > container.length) break;

                int got;
                try {
                    got = dec.decode(container, pos, len, pcm, 0, MAX_FRAME, false);
                } catch (OpusException e) {
                    throw new IOException("opus decode frame " + decoded
                        + " (len=" + len + "): " + e.getMessage(), e);
                }
                if (got > 0) {
                    // short[] → little-endian байты
                    for (int i = 0; i < got; i++) {
                        out.write(pcm[i] & 0xFF);
                        out.write((pcm[i] >> 8) & 0xFF);
                    }
                    totalSamples += got;
                }
                pos += len;
                decoded++;
            }

            Log.i(TAG, "decoded " + decoded + " frames -> " + totalSamples
                + " samples (" + (totalSamples / SAMPLE_RATE) + "s) " + out.size() + "B pcm");
            return out.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            String cls = e.getClass().getName();
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String detail = "opus decode cls=" + cls + " msg=" + msg
                + " frames=" + n + " container=" + container.length + "B";
            Log.e(TAG, detail, e);
            throw new IOException(detail, e);
        }
    }

    private static String hexHead(byte[] b, int n) {
        int lim = Math.min(n, b.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lim; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }
}