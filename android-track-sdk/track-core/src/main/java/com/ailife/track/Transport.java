package com.ailife.track;

/**
 * Transport abstraction: business process -> data-platform process.
 * Implementations: ContentProvider channel (ProviderTransport, api module)
 * and AIDL channel (AidlTransport, api module). Both deliver the same
 * (gzip batch, signature, ts) tuple and return one of the four contract
 * result codes plus transport-level failure codes.
 */
public interface Transport {

    /** Result codes per contracts/api.md plus transport failure signals. */
    enum Code {
        RESULT_SUCCEEDED(1),
        RESULT_THROTTLED(2),
        RESULT_RETRY_LATER(3),
        RESULT_INVALID(4),
        /** Binder dead / hub process gone: keep batch, reconnect with backoff. */
        DEAD_OBJECT(5),
        /** No answer within 8s: treat like DEAD_OBJECT. */
        TIMEOUT(6);

        public final int wire;

        Code(int wire) {
            this.wire = wire;
        }

        public static Code fromWire(int wire) {
            for (Code c : values()) {
                if (c.wire == wire) {
                    return c;
                }
            }
            return null;
        }
    }

    final class Result {
        public final Code code;
        public final String detail;

        public Result(Code code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public static Result ok() {
            return new Result(Code.RESULT_SUCCEEDED, null);
        }

        @Override
        public String toString() {
            return "Result{" + code + ", " + detail + '}';
        }
    }

    /**
     * Send one gzip-compressed batch to the data platform.
     * Implementations must respect an 8s budget (TIMEOUT after that).
     */
    Result send(String batchId, byte[] gzipBatch, String signature, long ts);

    /** Optional lightweight Hub health probe; null means retain the last value. */
    default HubStatus getHubStatus() {
        return null;
    }

    /** Snapshot returned by Provider/AIDL status probes. */
    final class HubStatus {
        public final double health;
        public final int degradeLevel;

        public HubStatus(double health, int degradeLevel) {
            this.health = health;
            this.degradeLevel = degradeLevel;
        }
    }
}
