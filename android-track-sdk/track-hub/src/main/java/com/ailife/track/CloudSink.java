package com.ailife.track;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Cloud report sink (contracts/api.md cloud API conventions):
 * POST {endpoint}/v1/track/batch, GET /v1/track/config. Pure-Java; the
 * Android hub binds an OkHttp/HttpURLConnection transport at runtime.
 */
public interface CloudSink {
    /** @return true when the batch was accepted (2xx). */
    boolean sendBatch(String batchId, byte[] gzipProto, String signature, long ts)
            throws IOException;

    /** Fetch remote config JSON; null on failure (caller keeps current). */
    String fetchConfig();

    /** Simple HttpURLConnection implementation with 8s timeouts. */
    final class Http implements CloudSink {
        private final String endpoint;

        public Http(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public boolean sendBatch(String batchId, byte[] gzipProto, String signature, long ts)
                throws IOException {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(endpoint + "/v1/track/batch").openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("X-Ailife-Sig", signature);
                conn.setRequestProperty("X-Ailife-Ts", String.valueOf(ts));
                conn.setRequestProperty("X-Ailife-Batch", batchId);
                conn.getOutputStream().write(gzipProto);
                int code = conn.getResponseCode();
                return code >= 200 && code < 300;
            } finally {
                conn.disconnect();
            }
        }

        @Override
        public String fetchConfig() {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(endpoint + "/v1/track/config").openConnection();
                try {
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    if (conn.getResponseCode() != 200) {
                        return null;
                    }
                    java.io.InputStream in = conn.getInputStream();
                    ByteArrayOutputStream2 buf = new ByteArrayOutputStream2();
                    byte[] tmp = new byte[4096];
                    int n;
                    while ((n = in.read(tmp)) > 0) {
                        buf.write(tmp, 0, n);
                    }
                    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
                } finally {
                    conn.disconnect();
                }
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Tiny ByteArrayOutputStream with toByteArray (API 21-safe). */
    final class ByteArrayOutputStream2 {
        private byte[] buf = new byte[256];
        private int len;

        public void write(byte[] src, int off, int count) {
            if (len + count > buf.length) {
                int cap = buf.length;
                while (cap < len + count) {
                    cap <<= 1;
                }
                byte[] nb = new byte[cap];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(src, off, buf, len, count);
            len += count;
        }

        public byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
