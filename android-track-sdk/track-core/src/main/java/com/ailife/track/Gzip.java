package com.ailife.track;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** gzip helpers (java.util.zip, available on API 21+). */
public final class Gzip {
    private Gzip() { }

    public static byte[] compress(byte[] src) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(src.length / 2 + 64);
        GZIPOutputStream gz = new GZIPOutputStream(bos, 512) {
            { def.setLevel(Deflater.BEST_SPEED); }
        };
        gz.write(src);
        gz.close();
        return bos.toByteArray();
    }

    public static byte[] decompress(byte[] src) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(src), 512);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(src.length * 2 + 64);
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            gz.close();
        }
        return bos.toByteArray();
    }
}
