package com.ailife.track;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HubController: assembles the data-platform process side (ingestion ->
 * store -> report scheduler -> health) on hub-private threads. The Android
 * layer (AilifeTrackProvider / ITrackService.Stub) delegates into this
 * controller; business code never touches it directly.
 */
public final class HubController {
    private final HubEventStore store;
    private final IngestionPipeline pipeline;
    private final IngestionPipeline.Metrics metrics;
    private final ReportScheduler scheduler;
    private final HealthManager health;
    private final ScheduledExecutorService timer;
    private final BatchRateLimiter ingestLimiter;
    private final TimeSource time;

    public HubController(File storeDir, long storeQuotaBytes, int ttlDays,
                         CloudSink sink, ReportScheduler.NetworkProbe network,
                         TimeSource time, Logger log) {
        this.time = time;
        this.metrics = new IngestionPipeline.Metrics();
        this.pipeline = new IngestionPipeline(24L * 3600 * 1000, metrics);
        this.store = new HubEventStore(storeDir, storeQuotaBytes, ttlDays,
                metrics, time, log);
        this.scheduler = new ReportScheduler(store, sink, network, time, log, metrics);
        this.health = new HealthManager(metrics, time);
        this.ingestLimiter = new BatchRateLimiter(
                TrackConfig.DEFAULT_RATE_LIMIT, TrackConfig.DEFAULT_RATE_DEPTH, time);
        this.timer = Executors.newSingleThreadScheduledExecutor();
        // hub tick: report trigger + TTL sweep + health recompute, every 5s
        timer.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 5000, 5000, TimeUnit.MILLISECONDS);
    }

    /** Ingest one already-decoded event from a transport callback. */
    public synchronized Transport.Code ingest(TrackEvent e) {
        long now = time.nowMs();
        IngestionPipeline.Decision decision = pipeline.inspect(e, now);
        if (decision.status() == IngestionPipeline.Status.INVALID) {
            return Transport.Code.RESULT_INVALID;
        }
        if (decision.status() == IngestionPipeline.Status.DUPLICATE) {
            return Transport.Code.RESULT_SUCCEEDED;
        }
        if (!ingestLimiter.tryAcquire(1, now)) {
            metrics.throttled();
            return Transport.Code.RESULT_THROTTLED;
        }
        TrackEvent accepted = decision.event();
        accepted.sentTime = now;
        if (!store.put(accepted, TrackVersion.VERSION, accepted.appVer)) {
            return Transport.Code.RESULT_RETRY_LATER;
        }
        pipeline.commit(decision, now);
        return Transport.Code.RESULT_SUCCEEDED;
    }

    /**
     * Ingest a decoded transport batch without letting a partial write poison
     * a retry. The complete batch is inspected first, then all new events
     * reserve their rate budget as one unit. Invalid records are quarantined
     * locally while valid tail records continue; if anything needs a retry,
     * committed prefixes are idempotent successes on the replay.
     */
    public synchronized Transport.Code ingestBatch(List<TrackEvent> events) {
        if (events == null || events.isEmpty()) {
            return Transport.Code.RESULT_SUCCEEDED;
        }
        long now = time.nowMs();
        List<IngestionPipeline.Decision> valid = new ArrayList<IngestionPipeline.Decision>();
        Set<String> pendingKeys = new HashSet<String>();
        boolean sawInvalid = false;
        boolean sawDuplicate = false;
        for (TrackEvent e : events) {
            IngestionPipeline.Decision decision = pipeline.inspect(e, now, pendingKeys);
            if (decision.status() == IngestionPipeline.Status.INVALID) {
                sawInvalid = true;
            } else if (decision.status() == IngestionPipeline.Status.VALID) {
                valid.add(decision);
                String key = decision.dedupKey();
                if (key != null && !key.isEmpty()) {
                    pendingKeys.add(key);
                }
            } else {
                sawDuplicate = true;
            }
        }
        // An all-invalid batch is a poisoned batch; callers may discard it.
        if (valid.isEmpty()) {
            // A replay can contain both previously committed entries and the
            // same malformed record. It is already durably handled, so it
            // must acknowledge success rather than making the sender discard
            // a batch whose valid tail was accepted on its first attempt.
            return sawInvalid && !sawDuplicate ? Transport.Code.RESULT_INVALID
                    : Transport.Code.RESULT_SUCCEEDED;
        }
        if (!ingestLimiter.tryAcquire(valid.size(), now)) {
            metrics.throttled();
            return Transport.Code.RESULT_THROTTLED;
        }

        boolean persistenceFailed = false;
        for (IngestionPipeline.Decision decision : valid) {
            TrackEvent accepted = decision.event();
            accepted.sentTime = now;
            if (store.put(accepted, TrackVersion.VERSION, accepted.appVer)) {
                pipeline.commit(decision, now);
            } else {
                // Continue through the tail: a retry will treat successful
                // prefix entries as idempotent and retain any later success.
                persistenceFailed = true;
            }
        }
        return persistenceFailed ? Transport.Code.RESULT_RETRY_LATER
                : Transport.Code.RESULT_SUCCEEDED;
    }

    /** Token bucket with an atomic N-token reservation for transport batches. */
    private static final class BatchRateLimiter {
        private final double ratePerMs;
        private final long capacity;
        private double tokens;
        private long lastRefillMs;

        BatchRateLimiter(int ratePerSec, int capacity, TimeSource time) {
            if (ratePerSec <= 0) {
                ratePerSec = 1000;
            }
            this.ratePerMs = ratePerSec / 1000.0;
            this.capacity = Math.max(1, capacity);
            this.tokens = this.capacity;
            this.lastRefillMs = time.nowMs();
        }

        synchronized boolean tryAcquire(int count, long nowMs) {
            if (count <= 0) {
                return true;
            }
            if (nowMs > lastRefillMs) {
                tokens = Math.min(capacity, tokens + (nowMs - lastRefillMs) * ratePerMs);
                lastRefillMs = nowMs;
            }
            if (count <= capacity && tokens >= count) {
                tokens -= count;
                return true;
            }
            return false;
        }
    }

    private void tick() {
        long now = time.nowMs();
        store.evictExpired(now);
        health.tick(store.sizeBytes(), storeQuota());
        if (scheduler.shouldReport(now)) {
            scheduler.drain();
        }
    }

    private long storeQuota() {
        return 20L * 1024 * 1024; // matches default hub storage quota
    }

    /** Status row for client getStatus(): state/pending/health/degrade. */
    public Map<String, Object> statusRow() {
        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("state", health.isCacheOnly() ? "DEGRADED" : "CONNECTED");
        row.put("pending", (long) store.count());
        health.fillStatus(row);
        return row;
    }

    /** Read-only query surface (TrackQuery). */
    public List<TrackEvent> queryEvents(long fromTs, long toTs, String eventIdLike, int limit) {
        return store.queryEvents(fromTs, toTs, eventIdLike, limit);
    }

    public Map<String, Long> metricsSnapshot() {
        return metrics.snapshot();
    }

    public void shutdown() {
        timer.shutdownNow();
        store.close();
    }
}
