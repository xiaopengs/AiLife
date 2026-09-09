package com.ailife.track;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Cloud report sink (contracts/api.md cloud API conventions):
 * POST {endpoint}/v1/track/batch, GET /v1/track/config. Pure-Java; the
 * Android hub binds an OkHttp/HttpURLConnection transport at runtime.
 */
public interface CloudSink {
    /** Structured upload outcome; only {@link Kind#SUCCESS} permits deletion. */
    final class Result {
        public enum Kind {
            SUCCESS,
            AUTH_FAILURE,
            TOO_LARGE,
            RATE_LIMITED,
            RETRYABLE
        }

        public final Kind kind;
        /** Delay requested by the service for 429, or -1 when unspecified. */
        public final long retryAfterMs;

        private Result(Kind kind, long retryAfterMs) {
            this.kind = kind;
            this.retryAfterMs = retryAfterMs;
        }

        public static Result success() { return new Result(Kind.SUCCESS, -1); }
        public static Result authFailure() { return new Result(Kind.AUTH_FAILURE, -1); }
        public static Result tooLarge() { return new Result(Kind.TOO_LARGE, -1); }
        public static Result rateLimited(long retryAfterMs) {
            return new Result(Kind.RATE_LIMITED, retryAfterMs);
        }
        public static Result retryable() { return new Result(Kind.RETRYABLE, -1); }
    }

    /** Never throws for ordinary HTTP or I/O failures; classify them instead. */
    Result sendBatch(String batchId, byte[] gzipProto, String signature, long ts);

    /** Fetch remote config JSON; null on failure (caller keeps current). */
    String fetchConfig();

    /** Simple HttpURLConnection implementation with 8s timeouts. */
    final class Http implements CloudSink {
        private final String endpoint;

        public Http(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public Result sendBatch(String batchId, byte[] gzipProto, String signature, long ts) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpoint + "/v1/track/batch").openConnection();
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
                if (code >= 200 && code < 300) {
                    return Result.success();
                }
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                    return Result.authFailure();
                }
                if (code == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
                    return Result.tooLarge();
                }
                if (code == 429) {
                    return Result.rateLimited(parseRetryAfterMs(conn));
                }
                // 5xx and unexpected transport/status failures leave the
                // queue intact and are retried with the scheduler's ladder.
                return Result.retryable();
            } catch (IOException ignored) {
                return Result.retryable();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private static long parseRetryAfterMs(HttpURLConnection conn) {
            String value = conn.getHeaderField("Retry-After");
            if (value == null) {
                return -1;
            }
            value = value.trim();
            try {
                long seconds = Long.parseLong(value);
                return seconds < 0 ? -1 : seconds * 1000L;
            } catch (NumberFormatException ignored) {
                long until = conn.getHeaderFieldDate("Retry-After", -1);
                return until < 0 ? -1 : Math.max(0, until - System.currentTimeMillis());
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
