package com.ailife.track;

import java.util.ArrayList;
import java.util.List;

/**
 * ChannelCore state machine (contracts/api.md): manages send attempts,
 * backoff (1s x2 cap 30s +-20% jitter), result-code routing
 * (SUCCEEDED/THROTTLED/RETRY_LATER/INVALID/DEAD_OBJECT/TIMEOUT) and the
 * degraded cache-only mode driven by hub health.
 */
public final class ChannelCore {
    public enum State {
        CONNECTED, BACKOFF, WAIT, QUARANTINE, DEGRADED
    }

    private final Transport transport;
    private final BackoffPolicy backoff;
    private final TimeSource time;
    private final Logger log;

    private State state = State.CONNECTED;
    private long nextAttemptAt = 0;
    private String quarantineDetail;

    /** Health/degradation as reported by the hub (getStatus) or injected in tests. */
    private volatile double health = 1.0;
    private volatile int degradeLevel = 0;

    public ChannelCore(Transport transport, TimeSource time, Logger log) {
        this(transport, new BackoffPolicy(), time, log);
    }

    public ChannelCore(Transport transport, BackoffPolicy backoff, TimeSource time, Logger log) {
        this.transport = transport;
        this.backoff = backoff;
        this.time = time;
        this.log = log;
    }

    public synchronized State state() {
        return state;
    }

    /** @return true when sending is currently allowed. */
    public synchronized boolean canSend() {
        long now = time.nowMs();
        if (state == State.BACKOFF || state == State.WAIT) {
            return now >= nextAttemptAt;
        }
        return state == State.CONNECTED;
    }

    public synchronized boolean isQuarantined() {
        return state == State.QUARANTINE;
    }

    public synchronized long nextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * Send one batch through the state machine. The caller keeps the batch
     * records until this returns SUCCEEDED, so any non-success leaves the
     * data queued (zero-loss).
     */
    public synchronized Transport.Result send(Outbox.Batch batch, String appKey,
                                              boolean encrypt) {
        if (!canSend()) {
            return new Transport.Result(Transport.Code.TIMEOUT, "backoff pending");
        }
        long ts = time.nowMs();
        byte[] proto = batch.protoBatch();
        try {
            byte[] payload = encrypt
                    ? Signature.encrypt(Signature.deriveAesKey(appKey), proto)
                    : proto;
            byte[] gz = Gzip.compress(payload);
            String sig = Signature.signBatch(appKey, ts, gz);
            Transport.Result r = transport.send(batchHash(batch), gz, sig, ts);
            apply(batch, r);
            return r;
        } catch (java.io.IOException e) {
            log.e("ChannelCore", "encode failed", e);
            apply(batch, new Transport.Result(Transport.Code.DEAD_OBJECT, e.getMessage()));
            return new Transport.Result(Transport.Code.DEAD_OBJECT, e.getMessage());
        } catch (RuntimeException e) {
            log.e("ChannelCore", "send crashed", e);
            apply(batch, new Transport.Result(Transport.Code.DEAD_OBJECT, e.getMessage()));
            return new Transport.Result(Transport.Code.DEAD_OBJECT, e.getMessage());
        }
    }

    private String batchHash(Outbox.Batch batch) {
        return Signature.sha256Hex(batch.protoBatch()).substring(0, 16);
    }

    private void apply(Outbox.Batch batch, Transport.Result r) {
        switch (r.code) {
            case RESULT_SUCCEEDED:
                state = State.CONNECTED;
                nextAttemptAt = 0;
                backoff.reset();
                break;
            case RESULT_THROTTLED:
                // hub overloaded: keep batch, wait a backoff slot, then retry
                state = State.WAIT;
                nextAttemptAt = time.nowMs() + backoff.nextDelayMs();
                log.w("ChannelCore", "throttled; wait until " + nextAttemptAt);
                break;
            case RESULT_RETRY_LATER:
                state = State.WAIT;
                nextAttemptAt = time.nowMs() + backoff.nextDelayMs();
                break;
            case RESULT_INVALID:
                // INVALID is a property of this batch, not a permanent
                // channel failure. The caller drops only this batch; later
                // valid batches must remain sendable.
                state = State.CONNECTED;
                nextAttemptAt = 0;
                quarantineDetail = r.detail;
                log.w("ChannelCore", "invalid batch quarantined: " + r.detail);
                break;
            case DEAD_OBJECT:
            case TIMEOUT:
            default:
                state = State.BACKOFF;
                nextAttemptAt = time.nowMs() + backoff.nextDelayMs();
                log.w("ChannelCore", "transport down (" + r.code + "); backoff until "
                        + nextAttemptAt);
                break;
        }
    }

    /** Result of an INVALID batch: caller acks (drops) those records only. */
    public boolean shouldDropBatch(Transport.Result result) {
        return result != null && result.code == Transport.Code.RESULT_INVALID;
    }

    /**
     * @deprecated INVALID is batch-local; use {@link #shouldDropBatch(Transport.Result)}.
     */
    @Deprecated
    public synchronized boolean shouldDropBatch() {
        return false;
    }

    public synchronized String quarantineDetail() {
        return quarantineDetail;
    }

    public void setHealth(double health, int degradeLevel) {
        this.health = health;
        this.degradeLevel = degradeLevel;
    }

    public double health() {
        return health;
    }

    public int degradeLevel() {
        return degradeLevel;
    }

    /** Cache-only mode: hub is unhealthy; enqueue but do not send. */
    public boolean isCacheOnly() {
        return health < 0.6;
    }
}
