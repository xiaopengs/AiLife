package com.ailife.track;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hub-side pipeline stage 1: inspect incoming events before persistence and
 * commit their dedup identity only after persistence has succeeded.  This is
 * deliberately a two-phase API: recording a dedup key during validation
 * would turn a failed disk write into a false successful duplicate on retry.
 */
public final class IngestionPipeline {
    /** The possible result of inspecting one inbound event. */
    public enum Status {
        VALID,
        INVALID,
        DUPLICATE
    }

    /**
     * An inspected event. Only a {@link Status#VALID} decision may be
     * committed, and callers must do so only after their durable write.
     */
    public static final class Decision {
        private final Status status;
        private final TrackEvent event;
        private final String dedupKey;
        private boolean committed;

        private Decision(Status status, TrackEvent event, String dedupKey) {
            this.status = status;
            this.event = event;
            this.dedupKey = dedupKey;
        }

        public Status status() {
            return status;
        }

        public TrackEvent event() {
            return event;
        }

        public String dedupKey() {
            return dedupKey;
        }
    }

    private final long dedupWindowMs;
    private final int dedupCapacity;
    private final Map<String, Long> committedKeys = new HashMap<String, Long>();
    private final ArrayDeque<String> commitOrder = new ArrayDeque<String>();
    private final Metrics metrics;

    public IngestionPipeline(long dedupWindowMs, Metrics metrics) {
        this.dedupWindowMs = dedupWindowMs;
        this.dedupCapacity = 200000;
        this.metrics = metrics;
    }

    /**
     * Validate one event against previously committed dedup keys. This method
     * never mutates dedup state for a VALID result.
     */
    public synchronized Decision inspect(TrackEvent e, long nowMs) {
        return inspect(e, nowMs, null);
    }

    /**
     * Inspect an event as part of a batch. {@code pendingBatchKeys} represents
     * earlier VALID events in the same batch; it prevents a repeated key in
     * that batch from being written twice without reserving it globally.
     */
    public synchronized Decision inspect(TrackEvent e, long nowMs,
                                         Set<String> pendingBatchKeys) {
        if (e == null || e.eventId == null || e.eventId.trim().isEmpty()) {
            metrics.incInvalid();
            return new Decision(Status.INVALID, e, null);
        }
        if (e.eventTime <= 0 || e.eventTime > nowMs + 5L * 60 * 1000) {
            // clock-skew tolerance +5min (E5): reject beyond the window
            metrics.incInvalid();
            return new Decision(Status.INVALID, e, null);
        }
        if (e.sentTime > 0 && e.eventTime > e.sentTime) {
            metrics.incInvalid();
            return new Decision(Status.INVALID, e, null);
        }
        String key = dedupKey(e);
        if (isCommittedDuplicate(key, nowMs)
                || (hasDedupKey(key) && pendingBatchKeys != null
                && pendingBatchKeys.contains(key))) {
            metrics.incDuplicate();
            return new Decision(Status.DUPLICATE, e, key);
        }
        return new Decision(Status.VALID, e, key);
    }

    /**
     * Record a successful durable write. Calling commit before the store has
     * acknowledged the write is a correctness bug: a retry would otherwise
     * be discarded as a duplicate even though it was never stored.
     */
    public synchronized void commit(Decision decision, long nowMs) {
        if (decision == null || decision.status != Status.VALID || decision.committed) {
            return;
        }
        if (hasDedupKey(decision.dedupKey)) {
            purge(nowMs);
            if (commitOrder.size() >= dedupCapacity) {
                String evicted = commitOrder.pollFirst();
                if (evicted != null) {
                    committedKeys.remove(evicted);
                }
            }
            committedKeys.put(decision.dedupKey, nowMs);
            commitOrder.remove(decision.dedupKey);
            commitOrder.addLast(decision.dedupKey);
        }
        decision.committed = true;
        metrics.incAccepted();
    }

    private boolean isCommittedDuplicate(String key, long nowMs) {
        if (!hasDedupKey(key)) {
            return false;
        }
        Long previous = committedKeys.get(key);
        if (previous == null) {
            return false;
        }
        if (nowMs - previous >= dedupWindowMs) {
            committedKeys.remove(key);
            commitOrder.remove(key);
            return false;
        }
        // Each duplicate extends the configured idempotency window. Without
        // this refresh, a continuous duplicate stream becomes new again at
        // first-seen + windowMs even though it never stopped arriving.
        committedKeys.put(key, nowMs);
        commitOrder.remove(key);
        commitOrder.addLast(key);
        return true;
    }

    private void purge(long nowMs) {
        while (!commitOrder.isEmpty()) {
            String oldest = commitOrder.peekFirst();
            Long committedAt = committedKeys.get(oldest);
            if (committedAt != null && nowMs - committedAt < dedupWindowMs) {
                return;
            }
            commitOrder.pollFirst();
            committedKeys.remove(oldest);
        }
    }

    private static boolean hasDedupKey(String key) {
        return key != null && !key.isEmpty();
    }

    private static String dedupKey(TrackEvent event) {
        return event.dedupKey == null || event.dedupKey.isEmpty() ? event.id : event.dedupKey;
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
        public void incEvictedQuota(int n) {
            if (n > 0) {
                evictedQuota.addAndGet(n);
            }
        }
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
