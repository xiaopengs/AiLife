package com.ailife.track;

import java.util.Map;

/**
 * Hub self-monitoring: health score in [0,1] with the degradation ladder
 * (contracts/api.md ChannelCore state machine):
 *   health < 0.6            -> cache-only (degradeLevel 2), clients stop sending
 *   health >= 0.8 for 10min -> recovery ramp 1/4 -> 1/2 -> full (5min/level)
 * Health is computed from storage pressure, send failure ratio and
 * pipeline errors (quota evictions, oversize, invalid ratio).
 */
public final class HealthManager {
    public static final double DEGRADE_AT = 0.6;
    public static final double RECOVER_AT = 0.8;
    public static final long RECOVER_SUSTAIN_MS = 10 * 60 * 1000L;
    public static final long RAMP_STEP_MS = 5 * 60 * 1000L;

    private final IngestionPipeline.Metrics metrics;
    private final TimeSource time;

    private double health = 1.0;
    private int degradeLevel = 0; // 0 normal, 1 ramping, 2 cache-only
    private double rampFraction = 1.0; // traffic accepted while ramping
    private long healthySince = 0;
    private long lastRampAt = 0;

    public HealthManager(IngestionPipeline.Metrics metrics, TimeSource time) {
        this.metrics = metrics;
        this.time = time;
    }

    /** Recompute health from current counters; call on the hub timer. */
    public synchronized void tick(long storeBytes, long storeQuotaBytes) {
        long total = metrics.accepted() + metrics.invalid() + metrics.duplicates();
        double invalidRatio = total == 0 ? 0.0
                : (double) metrics.invalid() / (double) total;
        long sends = metrics.sendSuccess() + metrics.sendFailures();
        double failRatio = sends == 0 ? 0.0
                : (double) metrics.sendFailures() / (double) sends;
        double pressure = storeQuotaBytes == 0 ? 0.0
                : (double) storeBytes / (double) storeQuotaBytes;
        double score = 1.0
                - 0.4 * Math.min(1.0, failRatio * 2.0)
                - 0.3 * Math.min(1.0, invalidRatio * 4.0)
                - 0.3 * Math.min(1.0, Math.max(0.0, (pressure - 0.8) * 5.0));
        setHealth(score);
    }

    private synchronized void setHealth(double score) {
        health = Math.max(0.0, Math.min(1.0, score));
        long now = time.nowMs();
        if (health < DEGRADE_AT) {
            degradeLevel = 2;
            rampFraction = 0.25; // restart ramp floor when recovered later
            healthySince = 0;
        } else if (health >= RECOVER_AT) {
            if (degradeLevel == 2) {
                if (healthySince == 0) {
                    healthySince = now;
                } else if (now - healthySince >= RECOVER_SUSTAIN_MS) {
                    degradeLevel = 1;
                    rampFraction = 0.25;
                    lastRampAt = now;
                    healthySince = 0;
                }
            } else if (degradeLevel == 1 && now - lastRampAt >= RAMP_STEP_MS) {
                if (rampFraction < 0.5) {
                    rampFraction = 0.5;
                    lastRampAt = now;
                } else if (rampFraction < 1.0) {
                    rampFraction = 1.0;
                    degradeLevel = 0;
                }
            }
        }
    }

    public synchronized double health() {
        return health;
    }

    public synchronized int degradeLevel() {
        return degradeLevel;
    }

    /** Traffic fraction accepted while recovering (1/4 -> 1/2 -> 1). */
    public synchronized double rampFraction() {
        return rampFraction;
    }

    public synchronized boolean isCacheOnly() {
        return degradeLevel == 2;
    }

    /** Hub status row for the client getStatus query. */
    public void fillStatus(Map<String, Object> out) {
        out.put("health", health());
        out.put("degrade_level", (long) degradeLevel());
        out.put("ramp_fraction", rampFraction());
    }
}
