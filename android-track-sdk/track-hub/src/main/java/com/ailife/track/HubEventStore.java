package com.ailife.track;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hub-side durable event store: day-partitioned journals with the same
 * frame format as the client Outbox (prefix hub-). At capacity the oldest
 * records are evicted so the newest events are retained; only TTL-expired,
 * quota-forced and oversize records are
 * discarded, all counted (zero accidental loss).
 */
public final class HubEventStore {
    private final DiskQueue journal;
    private final IngestionPipeline.Metrics metrics;
    private final long ttlMs;

    public HubEventStore(File dir, long maxBytes, int ttlDays,
                         IngestionPipeline.Metrics metrics, TimeSource time, Logger log) {
        this.journal = new DiskQueue(dir, maxBytes, time, log, "hub-");
        this.metrics = metrics;
        this.ttlMs = ttlDays * 86400000L;
    }

    /** Persist one accepted event; quota exhaustion increments the counter. */
    public boolean put(TrackEvent e, String sdkVer, String appVer) {
        byte[] encoded = BatchCodec.encodeEvent(e, sdkVer, appVer, e.osVer, e.device);
        if (encoded.length > BatchCodec.MAX_EVENT_BYTES) {
            metrics.incOversize();
            return false;
        }
        StoredEvent stored = new StoredEvent(e.dedupKey == null ? e.id : e.dedupKey, encoded);
        try {
            if (!journal.offer(stored.toRecord())) {
                metrics.incEvictedQuota();
                return false;
            }
            metrics.incEvictedQuota(journal.lastOfferEvictedCount());
            metrics.incStored();
            return true;
        } catch (IOException ioe) {
            metrics.incEvictedQuota();
            return false;
        }
    }

    /** TTL sweep: remove events older than eventTtlDays (counted). */
    public void evictExpired(long nowMs) {
        List<byte[]> all = journal.peek(100000);
        List<byte[]> expiredRecs = new ArrayList<byte[]>();
        int expired = 0;
        for (byte[] rec : all) {
            TrackEvent e = StoredEvent.fromRecord(rec).decode();
            if (nowMs - e.eventTime > ttlMs) {
                expired++;
                expiredRecs.add(rec);
            }
        }
        if (expired > 0) {
            journal.removeFirstByHash(expiredRecs);
            metrics.incEvictedTtl(expired);
        }
    }

    /** Read-only query (TrackQuery.queryEvents): events in window, newest last. */
    public List<TrackEvent> queryEvents(long fromTs, long toTs, String eventIdLike, int limit) {
        List<TrackEvent> out = new ArrayList<TrackEvent>();
        for (byte[] rec : journal.peek(100000)) {
            TrackEvent e = StoredEvent.fromRecord(rec).decode();
            if (e.eventTime < fromTs || e.eventTime > toTs) {
                continue;
            }
            if (eventIdLike != null && !eventIdLike.isEmpty()
                    && (e.eventId == null || !e.eventId.contains(eventIdLike))) {
                continue;
            }
            out.add(e);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** Remove uploaded events (ack by record identity after cloud 2xx). */
    public void removeUploaded(List<TrackEvent> uploaded) {
        List<byte[]> records = new ArrayList<byte[]>();
        for (byte[] rec : journal.peek(100000)) {
            StoredEvent ev = StoredEvent.fromRecord(rec);
            for (TrackEvent u : uploaded) {
                if (ev.dedupKey.equals(u.dedupKey == null ? u.id : u.dedupKey)
                        && ev.decode().eventId.equals(u.eventId)) {
                    records.add(rec);
                    break;
                }
            }
        }
        if (!records.isEmpty()) {
            journal.removeFirstByHash(records);
        }
    }

    public long sizeBytes() {
        return journal.sizeBytes();
    }

    public int count() {
        return journal.sizeCount();
    }

    public void close() {
        journal.close();
    }
}
