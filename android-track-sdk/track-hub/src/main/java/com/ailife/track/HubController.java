package com.ailife.track;

import java.io.File;
import java.util.List;
import java.util.Map;
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
    private final RateLimiter ingestLimiter;
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
        this.ingestLimiter = new RateLimiter(
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
    public Transport.Code ingest(TrackEvent e) {
        long now = time.nowMs();
        if (!ingestLimiter.tryAcquire(now)) {
            metrics.throttled();
            return Transport.Code.RESULT_THROTTLED;
        }
        TrackEvent ok = pipeline.accept(e, now);
        if (ok == null) {
            return Transport.Code.RESULT_INVALID;
        }
        ok.sentTime = now;
        if (!store.put(ok, TrackVersion.VERSION, ok.appVer)) {
            return Transport.Code.RESULT_RETRY_LATER;
        }
        return Transport.Code.RESULT_SUCCEEDED;
    }

    /** Ingest a whole decoded batch; returns the worst-case result code. */
    public Transport.Code ingestBatch(List<TrackEvent> events) {
        Transport.Code worst = Transport.Code.RESULT_SUCCEEDED;
        for (TrackEvent e : events) {
            Transport.Code r = ingest(e);
            if (r == Transport.Code.RESULT_INVALID) {
                return Transport.Code.RESULT_INVALID;
            }
            if (r != Transport.Code.RESULT_SUCCEEDED) {
                worst = r;
            }
        }
        return worst;
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
