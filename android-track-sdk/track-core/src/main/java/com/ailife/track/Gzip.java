package com.ailife.track;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** gzip helpers (java.util.zip, available on API 21+). */
public final class Gzip {
    /** Default ceiling for untrusted gzip input (also bounds allocation). */
    public static final int DEFAULT_MAX_DECOMPRESSED_BYTES = 1024 * 1024;

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

    /** Decompress using the SDK's safe default output ceiling. */
    public static byte[] decompress(byte[] src) throws IOException {
        return decompress(src, DEFAULT_MAX_DECOMPRESSED_BYTES);
    }

    /**
     * Decompress untrusted gzip data while enforcing an explicit output cap.
     * The check occurs before each write so a gzip bomb cannot grow the
     * output buffer beyond {@code maxBytes}.
     */
    public static byte[] decompress(byte[] src, int maxBytes) throws IOException {
        if (src == null) {
            throw new IOException("gzip source is null");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be >= 0");
        }
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(src), 512);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                Math.min(maxBytes, Math.max(64, src.length * 2)));
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = gz.read(buf)) > 0) {
                if (n > maxBytes - bos.size()) {
                    throw new IOException("gzip output exceeds " + maxBytes + " bytes");
                }
                bos.write(buf, 0, n);
            }
        } finally {
            gz.close();
        }
        return bos.toByteArray();
    }
}
