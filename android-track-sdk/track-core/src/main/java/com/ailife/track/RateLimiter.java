package com.ailife.track;

/**
 * Token-bucket rate limiter (client queue admission and hub ingest guard).
 * Default contract: 1000 events/s, depth 1000. Tokens refill continuously.
 */
public final class RateLimiter {
    private final double ratePerMs;
    private final long capacity;
    private double tokens;
    private long lastRefillMs;

    public RateLimiter(int ratePerSec, int capacity, TimeSource time) {
        if (ratePerSec <= 0) {
            ratePerSec = 1000;
        }
        this.ratePerMs = ratePerSec / 1000.0;
        this.capacity = Math.max(1, capacity);
        this.tokens = capacity;
        this.lastRefillMs = time.nowMs();
    }

    /** Deterministic variant used by tests and the engine (single clock). */
    public synchronized boolean tryAcquire(long nowMs) {
        if (nowMs < lastRefillMs) {
            // Wall clocks can be corrected backwards. Rebase without adding
            // tokens so the limiter resumes on the new clock immediately.
            lastRefillMs = nowMs;
        } else if (nowMs > lastRefillMs) {
            tokens = Math.min(capacity, tokens + (nowMs - lastRefillMs) * ratePerMs);
            lastRefillMs = nowMs;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized long available() {
        return (long) tokens;
    }
}
