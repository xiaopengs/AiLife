package com.ailife.track;

import java.util.ArrayList;
import java.util.List;

/**
 * Hub report scheduler: drains the HubEventStore to the cloud with the
 * 3-dimension trigger model (count/bytes/window) and the 30s/1m/5m/30m
 * retry ladder. Failures keep data in the store (zero loss); network state
 * is injected so tests and the Android layer can drive it.
 */
public final class ReportScheduler {
    /** Report trigger thresholds (3-dimension model). */
    public static final int TRIGGER_COUNT = 50;
    public static final int TRIGGER_BYTES = 256 * 1024;
    public static final long TRIGGER_WINDOW_MS = 5000L;
    /** Retry ladder 30s/1m/5m/30m, then stay at 30s. */
    private static final long[] RETRY_LADDER = {30000L, 60000L, 300000L, 1800000L};

    public interface NetworkProbe {
        boolean isOnline();
    }

    private final HubEventStore store;
    private final CloudSink sink;
    private final NetworkProbe network;
    private final TimeSource time;
    private final Logger log;
    private final IngestionPipeline.Metrics metrics;

    private int retryIndex = -1; // -1 = healthy
    private long nextAttemptAt = 0;
    private long lastDrainAt = 0;
    private int batchLimit = TRIGGER_COUNT;
    private boolean stoppedForAuth;

    public ReportScheduler(HubEventStore store, CloudSink sink, NetworkProbe network,
                           TimeSource time, Logger log, IngestionPipeline.Metrics metrics) {
        this.store = store;
        this.sink = sink;
        this.network = network;
        this.time = time;
        this.log = log;
        this.metrics = metrics;
    }

    /** @return true when a report should fire now (any trigger dimension). */
    public synchronized boolean shouldReport(long nowMs) {
        if (stoppedForAuth || !network.isOnline() || store.count() == 0) {
            return false;
        }
        if (nowMs < nextAttemptAt) {
            return false;
        }
        if (retryIndex >= 0) {
            return true;
        }
        if (nowMs - lastDrainAt >= TRIGGER_WINDOW_MS) {
            return true;
        }
        return store.count() >= TRIGGER_COUNT || store.sizeBytes() >= TRIGGER_BYTES;
    }

    /** Drain up to one cloud batch; returns number of events uploaded. */
    public synchronized int drain() {
        long now = time.nowMs();
        if (stoppedForAuth || !network.isOnline() || now < nextAttemptAt) {
            return 0; // offline: data stays queued
        }
        // 413 is safe to retry immediately only after shrinking this selected
        // batch. A single-record 413 is retained and backed off rather than
        // being deleted or spun forever.
        while (true) {
            List<TrackEvent> events = store.queryEvents(0, Long.MAX_VALUE, null, batchLimit);
            if (events.isEmpty()) {
                return 0;
            }
            List<byte[]> encoded = new ArrayList<byte[]>(events.size());
            for (TrackEvent e : events) {
                encoded.add(BatchCodec.encodeEvent(e, TrackVersion.VERSION, e.appVer,
                        e.osVer, e.device));
            }
            byte[] proto = BatchCodec.encodeBatch(encoded);
            String batchId = Signature.sha256Hex(proto).substring(0, 16);
            final byte[] gz;
            try {
                gz = Gzip.compress(proto);
            } catch (java.io.IOException e) {
                log.e("ReportScheduler", "batch compression failed", e);
                onRetryableFailure();
                return 0;
            }
            long ts = time.nowMs();
            String appKey = "default-appkey";
            CloudSink.Result result = sink.sendBatch(batchId, gz,
                    Signature.signBatch(appKey, ts, gz), ts);
            if (result == null) {
                // Defensively classify a broken custom sink as retryable.
                result = CloudSink.Result.retryable();
            }
            if (result.kind == CloudSink.Result.Kind.SUCCESS) {
                metrics.incSendSuccess();
                retryIndex = -1;
                lastDrainAt = now;
                nextAttemptAt = 0;
                // This is the only queue deletion path: an explicit cloud 2xx.
                store.removeUploaded(events);
                return events.size();
            }
            if (result.kind == CloudSink.Result.Kind.AUTH_FAILURE) {
                metrics.incSendFailure();
                stoppedForAuth = true;
                nextAttemptAt = Long.MAX_VALUE;
                log.w("ReportScheduler", "cloud authorization failed; reporting stopped");
                return 0;
            }
            if (result.kind == CloudSink.Result.Kind.RATE_LIMITED) {
                if (result.retryAfterMs >= 0) {
                    metrics.incSendFailure();
                    // Retry-After is a server contract, not a hint for the
                    // normal exponential ladder: preserve it exactly.
                    retryIndex = -1;
                    nextAttemptAt = time.nowMs() + result.retryAfterMs;
                } else {
                    onRetryableFailure();
                }
                return 0;
            }
            if (result.kind == CloudSink.Result.Kind.TOO_LARGE
                    && events.size() > 1) {
                metrics.incSendFailure();
                batchLimit = Math.max(1, events.size() / 2);
                // Retain every record and immediately retry the smaller prefix.
                continue;
            }
            onRetryableFailure();
            return 0;
        }
    }

    private void onRetryableFailure() {
        metrics.incSendFailure();
        retryIndex = Math.min(retryIndex + 1, RETRY_LADDER.length - 1);
        nextAttemptAt = time.nowMs() + RETRY_LADDER[retryIndex];
    }

    /** Next allowed report attempt (for status/debug). */
    public synchronized long nextAttemptAt() {
        return nextAttemptAt;
    }

    public synchronized int retryIndex() {
        return retryIndex;
    }

    /** True after 401/403; reports remain queued until a scheduler is recreated. */
    public synchronized boolean isStoppedForAuth() {
        return stoppedForAuth;
    }

    /** Current maximum records per cloud attempt; exposed for deterministic tests. */
    public synchronized int batchLimit() {
        return batchLimit;
    }
}
