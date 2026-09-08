package com.ailife.track;

import java.util.Random;

/**
 * Exponential reconnect/report backoff: 1s x2 capped at 30s with +-20% jitter.
 * Deterministic under an injected Random (tests), random otherwise.
 */
public final class BackoffPolicy {
    public static final long BASE_MS = 1000L;
    public static final long CAP_MS = 30000L;

    private final Random random;
    private int attempt;

    public BackoffPolicy() {
        this(new Random());
    }

    public BackoffPolicy(Random random) {
        this.random = random;
    }

    /** Advance one failure and return the delay to wait before the next attempt. */
    public synchronized long nextDelayMs() {
        attempt++;
        long pure = Math.min(CAP_MS, BASE_MS * (1L << Math.min(attempt - 1, 20)));
        if (pure > CAP_MS) {
            pure = CAP_MS;
        }
        double jitter = 1.0 + (random.nextDouble() * 0.4 - 0.2);
        long delay = (long) (pure * jitter);
        if (delay < 0) {
            delay = CAP_MS;
        }
        return Math.min(delay, CAP_MS + CAP_MS / 5);
    }

    public synchronized int attempt() {
        return attempt;
    }

    public synchronized void reset() {
        attempt = 0;
    }
}
