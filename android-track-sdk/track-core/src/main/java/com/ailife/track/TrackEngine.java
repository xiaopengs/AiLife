package com.ailife.track;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TrackEngine: ties Outbox + ChannelCore together on a single-thread
 * executor (business process side). All heavy work (encode/gzip/sign/IPC)
 * happens here so AilifeTrack.track() stays O(enqueue) <= 2ms.
 */
public final class TrackEngine {
    private final TrackConfig config;
    private final Outbox outbox;
    private final ChannelCore channel;
    private final TimeSource time;
    private final Logger log;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean drainRequested = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicInteger drainSubmissions = new AtomicInteger();
    private final java.util.concurrent.ExecutorService executor;
    private final String appKey;
    private final boolean encrypt;

    public TrackEngine(TrackConfig config, File queueDir, Transport transport,
                       TimeSource time, Logger log) {
        this.config = config;
        this.time = time;
        this.log = log;
        this.outbox = new Outbox(config, queueDir, time, log);
        this.channel = new ChannelCore(transport, time, log);
        this.appKey = config.appKey;
        this.encrypt = config.encryptPayload;
        this.executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "ailife-track-engine");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY + 1);
                        return t;
                    }
                });
    }

    /** Enqueue + persist synchronously (fast path); sending happens async. */
    public boolean enqueue(TrackEvent event) {
        StoredEvent stored = StoredEvent.of(event, "sdk-" + TrackVersion.VERSION,
                event.appVer, event.osVer, event.device);
        try {
            return outbox.add(stored);
        } catch (IOException e) {
            dropped.incrementAndGet();
            log.e("TrackEngine", "persist failed", e);
            return false;
        }
    }

    /**
     * Kick the send loop without creating an unbounded executor backlog.
     * Concurrent/repeated requests collapse into at most one queued worker;
     * requests racing worker completion are observed before it exits.
     */
    public void drainAsync() {
        if (shutdown.get()) {
            return;
        }
        drainRequested.set(true);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            drainSubmissions.incrementAndGet();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runDrainWorker();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutdown can race drainAsync; the public API remains no-throw.
            drainScheduled.set(false);
        }
    }

    private void runDrainWorker() {
        while (true) {
            drainRequested.set(false);
            try {
                drain();
            } catch (RuntimeException e) {
                log.e("TrackEngine", "drain crashed", e);
            }
            drainScheduled.set(false);
            // If a trigger arrived while this worker was draining, it may have
            // intentionally coalesced. Schedule exactly one follow-up pass.
            if (!shutdown.get() && drainRequested.get()
                    && drainScheduled.compareAndSet(false, true)) {
                continue;
            }
            return;
        }
    }

    /** Package-private test diagnostic: actual executor submissions. */
    int drainSubmissionCount() {
        return drainSubmissions.get();
    }

    /** Send loop: batches until outbox empty or channel blocks. */
    void drain() {
        if (!config.sendEnabled) {
            log.w("TrackEngine", "appKey missing; retaining events in local cache only");
            return;
        }
        int guard = 0;
        while (guard++ < 64) {
            channel.refreshHealth();
            if (channel.isCacheOnly()) {
                return; // degraded: stay queued, zero loss
            }
            Outbox.Batch batch;
            try {
                batch = outbox.takeBatch();
            } catch (IOException e) {
                log.e("TrackEngine", "takeBatch failed", e);
                return;
            }
            if (batch == null) {
                return;
            }
            Transport.Result r = channel.send(batch, appKey, encrypt);
            if (r.code == Transport.Code.RESULT_SUCCEEDED) {
                outbox.ack(batch.records);
                sent.addAndGet(batch.count());
            } else if (channel.shouldDropBatch(r)) {
                // INVALID: drop this poisoned batch only. ChannelCore is
                // immediately reusable, so continue with a later legal batch.
                outbox.ack(batch.records);
                dropped.addAndGet(batch.count());
            } else {
                // THROTTLED / RETRY_LATER / DEAD_OBJECT / TIMEOUT: keep and wait
                return;
            }
        }
    }

    /** Blocking drain for tests. */
    public void drainNow() {
        drain();
    }

    public TrackStatus status() {
        TrackStatus s = new TrackStatus();
        s.pendingCount = outbox.pendingCount();
        s.pendingBytes = outbox.pendingBytes();
        s.health = channel.health();
        s.degradeLevel = channel.degradeLevel();
        s.droppedCount = dropped.get() + outbox.evictedCount();
        s.sentCount = sent.get();
        return s;
    }

    public Outbox outbox() {
        return outbox;
    }

    public ChannelCore channel() {
        return channel;
    }

    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            executor.shutdown();
            outbox.close();
        }
    }
}
