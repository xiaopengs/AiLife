package com.ailife.track;

import java.io.IOException;
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
        if (!network.isOnline() || store.count() == 0) {
            return false;
        }
        if (retryIndex >= 0) {
            return nowMs >= nextAttemptAt;
        }
        if (nowMs - lastDrainAt >= TRIGGER_WINDOW_MS) {
            return true;
        }
        return store.count() >= TRIGGER_COUNT || store.sizeBytes() >= TRIGGER_BYTES;
    }

    /** Drain up to one cloud batch; returns number of events uploaded. */
    public synchronized int drain() {
        long now = time.nowMs();
        if (!network.isOnline()) {
            return 0; // offline: data stays queued
        }
        List<TrackEvent> events = store.queryEvents(0, Long.MAX_VALUE, null, TRIGGER_COUNT);
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
        try {
            byte[] gz = Gzip.compress(proto);
            long ts = time.nowMs();
            String appKey = "default-appkey";
            boolean ok = sink.sendBatch(batchId, gz, Signature.signBatch(appKey, ts, gz), ts);
            if (ok) {
                metrics.incSendSuccess();
                retryIndex = -1;
                lastDrainAt = now;
                store.removeUploaded(events);
                return events.size();
            }
            onCloudFailure();
            return 0;
        } catch (IOException e) {
            log.e("ReportScheduler", "cloud upload failed", e);
            onCloudFailure();
            return 0;
        }
    }

    private void onCloudFailure() {
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
}
