package com.ailife.track;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hub-side pipeline stage 1: validate + dedup incoming events from any
 * business process. Invalid events -> quarantine counters (never crash);
 * duplicates within the dedup window -> counted, not stored.
 */
public final class IngestionPipeline {
    private final Deduplicator dedup;
    private final Metrics metrics;

    public IngestionPipeline(long dedupWindowMs, Metrics metrics) {
        this.dedup = new Deduplicator(dedupWindowMs, 200000);
        this.metrics = metrics;
    }

    /**
     * Validate + dedup one event. @return the event when accepted, null when
     * rejected (invalid or duplicate); rejection reasons counted in metrics.
     */
    public TrackEvent accept(TrackEvent e, long nowMs) {
        if (e == null || e.eventId == null || e.eventId.trim().isEmpty()) {
            metrics.incInvalid();
            return null;
        }
        if (e.eventTime <= 0 || e.eventTime > nowMs + 5L * 60 * 1000) {
            // clock-skew tolerance +5min (E5): reject beyond the window
            metrics.incInvalid();
            return null;
        }
        if (e.sentTime > 0 && e.eventTime > e.sentTime) {
            metrics.incInvalid();
            return null;
        }
        String key = e.dedupKey == null || e.dedupKey.isEmpty() ? e.id : e.dedupKey;
        if (!dedup.isNew(key, nowMs)) {
            metrics.incDuplicate();
            return null;
        }
        metrics.incAccepted();
        return e;
    }

    /** Counters hub exposes via queryMetrics and health scoring. */
    public static final class Metrics {
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong invalid = new AtomicLong();
        private final AtomicLong duplicates = new AtomicLong();
        private final AtomicLong throttled = new AtomicLong();
        private final AtomicLong stored = new AtomicLong();
        private final AtomicLong evictedTtl = new AtomicLong();
        private final AtomicLong evictedQuota = new AtomicLong();
        private final AtomicLong oversize = new AtomicLong();
        private final AtomicLong sendFailures = new AtomicLong();
        private final AtomicLong sendSuccess = new AtomicLong();

        public void incAccepted() { accepted.incrementAndGet(); }
        public void incInvalid() { invalid.incrementAndGet(); }
        public void incDuplicate() { duplicates.incrementAndGet(); }
        public void incThrottled() { throttled.incrementAndGet(); }
        public void incStored() { stored.incrementAndGet(); }
        public void incEvictedTtl(int n) { evictedTtl.addAndGet(n); }
        public void incEvictedQuota() { evictedQuota.incrementAndGet(); }
        public void incOversize() { oversize.incrementAndGet(); }
        public void incSendFailure() { sendFailures.incrementAndGet(); }
        public void incSendSuccess() { sendSuccess.incrementAndGet(); }

        public long accepted() { return accepted.get(); }
        public long invalid() { return invalid.get(); }
        public long duplicates() { return duplicates.get(); }
        public long throttled() { return throttled.get(); }
        public long stored() { return stored.get(); }
        public long evictedTtl() { return evictedTtl.get(); }
        public long evictedQuota() { return evictedQuota.get(); }
        public long oversize() { return oversize.get(); }
        public long sendFailures() { return sendFailures.get(); }
        public long sendSuccess() { return sendSuccess.get(); }

        public Map<String, Long> snapshot() {
            Map<String, Long> m = new java.util.LinkedHashMap<String, Long>();
            m.put("accepted", accepted.get());
            m.put("invalid", invalid.get());
            m.put("duplicates", duplicates.get());
            m.put("throttled", throttled.get());
            m.put("stored", stored.get());
            m.put("evicted_ttl", evictedTtl.get());
            m.put("evicted_quota", evictedQuota.get());
            m.put("oversize", oversize.get());
            m.put("send_failures", sendFailures.get());
            m.put("send_success", sendSuccess.get());
            return m;
        }
    }
}
