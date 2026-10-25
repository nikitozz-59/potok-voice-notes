package com.nexara.voicenotes;

/** PCM 16-bit little-endian → WAV (44-байтный заголовок + данные). */
public final class WavWriter {
    private WavWriter() {}

    public static byte[] write(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int dataLen = pcm.length;
        int frameSize = channels * (bitsPerSample / 8);
        byte[] out = new byte[44 + dataLen];
        int p = 0;
        out[p++]='R'; out[p++]='I'; out[p++]='F'; out[p++]='F';
        put32(out, p, 36 + dataLen); p += 4;
        out[p++]='W'; out[p++]='A'; out[p++]='V'; out[p++]='E';
        out[p++]='f'; out[p++]='m'; out[p++]='t'; out[p++]=' ';
        put32(out, p, 16); p += 4;
        put16(out, p, 1); p += 2; // PCM
        put16(out, p, channels); p += 2;
        put32(out, p, sampleRate); p += 4;
        put32(out, p, sampleRate * frameSize); p += 4; // byte rate
        put16(out, p, frameSize); p += 2;
        put16(out, p, bitsPerSample); p += 2;
        out[p++]='d'; out[p++]='a'; out[p++]='t'; out[p++]='a';
        put32(out, p, dataLen); p += 4;
        System.arraycopy(pcm, 0, out, p, dataLen);
        return out;
    }

    private static void put32(byte[] b, int o, int v) {
        b[o]=(byte)v; b[o+1]=(byte)(v>>8); b[o+2]=(byte)(v>>16); b[o+3]=(byte)(v>>24);
    }
    private static void put16(byte[] b, int o, int v) {
        b[o]=(byte)v; b[o+1]=(byte)(v>>8);
    }
}
