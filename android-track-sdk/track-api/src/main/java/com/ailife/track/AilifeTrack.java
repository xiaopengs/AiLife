package com.ailife.track;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Public facade (contracts/api.md AilifeTrack). Thread-safe; all methods
 * tolerate being called before init (tracked as invalid, never throwing).
 *
 * Fast path guarantees: track() only merges props, stamps ids/times and
 * persists one journal frame (P95 <= 2ms); encode/gzip/sign/IPC run on the
 * engine's private single thread.
 */
public final class AilifeTrack {
    private static volatile TrackEngine engine;
    private static volatile TrackConfig config;
    private static volatile Environment env;
    private static volatile ScheduledExecutorService timer;
    private static volatile boolean suspended; // optOut state
    private static volatile long lastToggleAt;
    private static final Object LOCK = new Object();

    private static final RateLimiter LIMITER = new RateLimiter(
            TrackConfig.DEFAULT_RATE_LIMIT, TrackConfig.DEFAULT_RATE_DEPTH,
            TimeSource.SYSTEM);
    private static final AtomicLong invalidCount = new AtomicLong();
    private static final AtomicLong throttledCount = new AtomicLong();
    private static final AtomicLong togglesIgnored = new AtomicLong();

    private static final long OPT_DEBOUNCE_MS = 10000L;

    private AilifeTrack() { }

    /** Initialize the SDK. Safe to call twice (second call is a no-op). */
    public static void init(TrackConfig cfg, Environment environment) {
        if (cfg == null || environment == null) {
            invalidCount.incrementAndGet();
            return;
        }
        synchronized (LOCK) {
            if (engine != null) {
                return;
            }
            config = cfg;
            env = environment;
            engine = new TrackEngine(cfg, environment.queueDir(),
                    environment.createTransport(cfg), TimeSource.SYSTEM,
                    environment.logger());
            timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ailife-track-timer");
                    t.setDaemon(true);
                    return t;
                }
            });
            timer.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    TrackEngine e = engine;
                    if (e != null) {
                        e.drainAsync();
                    }
                }
            }, cfg.flushIntervalMs, cfg.flushIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Track one business event. Never throws; bad input counted (E1). */
    public static void track(String eventId, Map<String, Object> properties) {
        if (eventId == null || eventId.trim().isEmpty() || engine == null) {
            invalidCount.incrementAndGet();
            return;
        }
        TrackEvent e = newEvent(eventId, properties);
        if (e == null) {
            return;
        }
        dispatch(e);
    }

    /** Track a batch; auto-splits into per-event dispatch (max 200 per call). */
    public static void trackList(List<TrackEvent> events) {
        if (events == null || events.isEmpty()) {
            invalidCount.incrementAndGet();
            return;
        }
        int max = Math.min(events.size(), TrackConfig.MAX_BATCH_COUNT);
        for (int i = 0; i < max; i++) {
            TrackEvent e = events.get(i);
            if (e == null || e.eventId == null || e.eventId.trim().isEmpty()) {
                invalidCount.incrementAndGet();
                continue;
            }
            if (e.eventTime <= 0) {
                e.eventTime = System.currentTimeMillis();
            }
            if (e.id == null) {
                e.id = UUID.randomUUID().toString();
            }
            if (e.dedupKey == null || e.dedupKey.isEmpty()) {
                e.dedupKey = e.id;
            }
            fillSnapshot(e, mergeGlobals(e.properties));
            dispatch(e);
        }
        if (events.size() > max) {
            invalidCount.incrementAndGet();
        }
    }

    /** Force a send attempt of everything currently queued. */
    public static void flush() {
        TrackEngine e = engine;
        if (e != null) {
            e.drainAsync();
        }
    }

    /** Profile updates travel as a profile_sync event (contract behavior). */
    public static void setUserProfile(Map<String, Object> profile) {
        if (profile == null || profile.isEmpty()) {
            invalidCount.incrementAndGet();
            return;
        }
        track("profile_sync", profile);
    }

    /** Stop collecting. Debounced per E12 (10s). */
    public static void optOut() {
        toggleSuspend(true);
    }

    /** Resume collecting. Debounced per E12 (10s). */
    public static void optIn() {
        toggleSuspend(false);
    }

    private static void toggleSuspend(boolean out) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastToggleAt < OPT_DEBOUNCE_MS) {
                togglesIgnored.incrementAndGet();
                return;
            }
            lastToggleAt = now;
            suspended = out;
        }
    }

    /** Current status snapshot (engine + facade counters). */
    public static TrackStatus getStatus() {
        TrackStatus s = new TrackStatus();
        TrackEngine e = engine;
        if (e == null) {
            s.state = TrackStatus.State.IDLE;
            s.droppedCount = invalidCount.get();
            if (config != null) {
                s.channel = config.channelMode == TrackConfig.ChannelMode.AIDL
                        ? "aidl" : "provider";
            }
            return s;
        }
        TrackStatus es = e.status();
        s.pendingCount = es.pendingCount;
        s.pendingBytes = es.pendingBytes;
        s.health = es.health;
        s.degradeLevel = es.degradeLevel;
        s.sentCount = es.sentCount;
        s.droppedCount = es.droppedCount + invalidCount.get() + throttledCount.get();
        s.state = suspended ? TrackStatus.State.SUSPENDED
                : (es.degradeLevel >= 2 ? TrackStatus.State.DEGRADED
                    : (es.pendingCount > 0 ? TrackStatus.State.CONNECTING
                        : TrackStatus.State.CONNECTED));
        if (config != null) {
            s.channel = config.channelMode == TrackConfig.ChannelMode.AIDL
                    ? "aidl" : "provider";
        }
        return s;
    }

    /** Test/debug hook: reset facade state (never call in production). */
    static synchronized void resetForTest() {
        TrackEngine e = engine;
        if (e != null) {
            e.shutdown();
        }
        ScheduledExecutorService t = timer;
        if (t != null) {
            t.shutdownNow();
        }
        engine = null;
        config = null;
        env = null;
        timer = null;
        suspended = false;
        lastToggleAt = 0;
        invalidCount.set(0);
        throttledCount.set(0);
        togglesIgnored.set(0);
    }

    private static TrackEvent newEvent(String eventId, Map<String, Object> properties) {
        TrackEvent e = new TrackEvent();
        e.eventId = eventId;
        e.id = UUID.randomUUID().toString();
        e.dedupKey = e.id;
        e.eventTime = System.currentTimeMillis();
        fillSnapshot(e, mergeGlobals(properties));
        return e;
    }

    private static Map<String, Object> mergeGlobals(Map<String, Object> properties) {
        TrackConfig c = config;
        Map<String, Object> g = c == null ? null : c.globalProps;
        if (g == null || g.isEmpty()) {
            return properties;
        }
        Map<String, Object> merged = new LinkedHashMap<String, Object>(g);
        if (properties != null) {
            merged.putAll(properties);
        }
        return merged;
    }

    private static void fillSnapshot(TrackEvent e, Map<String, Object> props) {
        Environment m = env;
        if (m != null) {
            e.appVer = m.appVersionName();
            e.osVer = m.osVersion();
            e.device = m.deviceModel();
        }
        e.properties = props;
    }

    private static void dispatch(TrackEvent e) {
        if (suspended) {
            return; // opted out: silently dropped by design
        }
        if (!LIMITER.tryAcquire(e.eventTime)) {
            throttledCount.incrementAndGet();
            return;
        }
        TrackEngine en = engine;
        if (en == null) {
            invalidCount.incrementAndGet();
            return;
        }
        boolean persisted = en.enqueue(e);
        TrackConfig c = config;
        if (persisted && c != null && en.outbox().pendingCount() >= c.batchCount) {
            en.drainAsync();
        }
    }

    /** Visible for tests: number of E12-debounce-suppressed toggles. */
    static long togglesIgnored() {
        return togglesIgnored.get();
    }

    /** Visible for tests. */
    static List<TrackEvent> asList(TrackEvent... evs) {
        List<TrackEvent> out = new ArrayList<TrackEvent>();
        for (TrackEvent e : evs) {
            out.add(e);
        }
        return out;
    }
}
