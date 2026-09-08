package com.ailife.track;

/**
 * Pluggable time source. Java 8 / minSdk 21 safe (no java.time).
 * All latency windows, backoff, dedup windows and TTL logic take time from
 * this interface so tests can drive a virtual clock deterministically.
 */
public interface TimeSource {
    /** Monotonic-ish wall clock in milliseconds. */
    long nowMs();

    /** Default real-time source. */
    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }
    };
}
