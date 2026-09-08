package com.ailife.track;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Recovery queue in the client process. Ordering guarantee: memory batch
 * (not yet sent, freshest) -> disk journal (all not-yet-accepted events,
 * oldest first). Everything in this queue is delivered with at-least-once
 * semantics; the hub's dedupKey window makes retransmission idempotent.
 * Guarantees zero loss across: process kill mid-batch, system freeze/thaw
 * (queue unchanged, no keeper process needed), and device reboot.
 */
public final class Outbox {
    private final DiskQueue journal;
    private final TrackConfig config;
    private final TimeSource time;
    private final Logger log;

    /** In-memory staging for events since the last journal flush. */
    private final List<StoredEvent> mem = new ArrayList<StoredEvent>(64);
    private long memBytes = 0;

    public Outbox(TrackConfig config, File dir, TimeSource time, Logger log) {
        this.config = config;
        this.time = time;
        this.log = log;
        this.journal = new DiskQueue(dir, config.maxQueueBytes, time, log, "track-");
    }

    /** Persist one event; false means disk quota full (caller drops + counts). */
    public synchronized boolean add(StoredEvent ev) throws IOException {
        byte[] rec = ev.toRecord();
        if (!journal.offer(rec)) {
            log.w("Outbox", "queue full, dropping oldest-first policy disabled; record dropped");
            return false;
        }
        return true;
    }

    /** Build a batch from oldest journal entries, honoring count/size limits. */
    public synchronized Batch takeBatch() throws IOException {
        List<byte[]> raw = journal.peek(config.batchCount);
        if (raw.isEmpty()) {
            return null;
        }
        List<byte[]> selected = new ArrayList<byte[]>();
        List<StoredEvent> events = new ArrayList<StoredEvent>();
        int bytes = 0;
        for (byte[] r : raw) {
            StoredEvent ev = StoredEvent.fromRecord(r);
            int evSize = ev.size();
            if (evSize > BatchCodec.MAX_EVENT_BYTES) {
                journal.removeFirst(1);
                log.w("Outbox", "oversize event dropped " + evSize + "B");
                continue;
            }
            if (!selected.isEmpty()
                    && (bytes + evSize > BatchCodec.MAX_BATCH_BYTES
                        || selected.size() >= config.batchCount)) {
                break;
            }
            selected.add(r);
            events.add(ev);
            bytes += evSize;
        }
        if (selected.isEmpty()) {
            return null;
        }
        return new Batch(selected, events, bytes);
    }

    /** One unsent batch held by the sender (retries use the same records). */
    public static final class Batch {
        public final List<byte[]> records;
        public final List<StoredEvent> events;
        public final int bytes;

        Batch(List<byte[]> records, List<StoredEvent> events, int bytes) {
            this.records = records;
            this.events = events;
            this.bytes = bytes;
        }

        public int count() {
            return events.size();
        }

        /** Encoded events for the wire (protobuf batch, then gzip+sign at sender). */
        public byte[] protoBatch() {
            List<byte[]> encs = new ArrayList<byte[]>(events.size());
            for (StoredEvent ev : events) {
                encs.add(ev.encoded);
            }
            return BatchCodec.encodeBatch(encs);
        }
    }

    /** Remove exactly the given records (ack by record identity, not count). */
    public synchronized void ack(List<byte[]> records) {
        // removeFirst matches by content hash so a concurrent re-add cannot
        // delete a newer identical event by accident (dedup would anyway).
        journal.removeFirstByHash(records);
    }

    public synchronized long pendingBytes() {
        return journal.sizeBytes();
    }

    public synchronized int pendingCount() {
        return journal.sizeCount();
    }

    public void close() {
        journal.close();
    }
}
