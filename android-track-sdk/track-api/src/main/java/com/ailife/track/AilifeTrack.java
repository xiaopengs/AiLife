package com.ailife.track;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
 * Public facade for business-process tracking. Public entry points never let
 * an application's {@link RuntimeException} escape: invalid input is counted
 * and the rest of a batch continues. Rate limiting deliberately lives in the
 * hub, not in this pre-persistence facade.
 */
public final class AilifeTrack {
    private static final int MAX_EVENT_ID_LENGTH = 128;
    private static final String CONSENT_FILE = ".ailife-track-consent";
    private static final String CONSENT_OUT = "out";
    private static final String CONSENT_IN = "in";

    private static volatile TrackEngine engine;
    private static volatile TrackConfig config;
    private static volatile Environment env;
    private static volatile File queueDir;
    private static volatile ScheduledExecutorService timer;
    private static volatile boolean suspended;
    private static volatile boolean initialized;
    /* Records an opt-in/out made before an Environment has supplied a directory. */
    private static volatile boolean consentExplicitlySet;

    private static final Object LOCK = new Object();
    /* Serializes a drain with consent transitions and queue deletion. */
    private static final Object DRAIN_LOCK = new Object();
    private static final AtomicLong invalidCount = new AtomicLong();
    private static boolean drainQueued;

    private AilifeTrack() { }

    /** Initialize once. A persisted opt-out is restored before any engine is created. */
    public static void init(TrackConfig cfg, Environment environment) {
        try {
            if (cfg == null || environment == null) {
                countInvalid();
                return;
            }
            synchronized (DRAIN_LOCK) {
                synchronized (LOCK) {
                    if (initialized) {
                        return;
                    }
                    File directory;
                    try {
                        directory = environment.queueDir();
                    } catch (RuntimeException ex) {
                        countInvalid();
                        return;
                    }
                    if (directory == null) {
                        countInvalid();
                        return;
                    }
                    config = cfg;
                    env = environment;
                    queueDir = directory;
                    initialized = true;

                    boolean persistedOut = readPersistedOptOut(directory);
                    if (!consentExplicitlySet) {
                        suspended = persistedOut;
                    } else if (!persistConsentLocked(suspended)) {
                        countInvalid();
                    }
                    /* A pre-init opt-in cannot resurrect records from before opt-out. */
                    if (suspended || persistedOut) {
                        clearQueueFilesLocked(directory);
                    }
                    if (suspended) {
                        return;
                    }
                    engine = createEngineLocked();
                    if (engine != null) {
                        startTimerLocked();
                    }
                }
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /** Track one business event. Bad properties never discard the event body. */
    public static void track(String eventId, Map<String, Object> properties) {
        try {
            if (suspended) {
                return;
            }
            if (!isValidEventId(eventId)) {
                countInvalid();
                return;
            }
            dispatch(newEvent(eventId, properties));
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /**
     * Track every supplied element. Disk/outbox batching controls wire batches;
     * this method intentionally has no facade-side 200-event truncation.
     */
    public static void trackList(List<TrackEvent> events) {
        try {
            if (suspended) {
                return;
            }
            if (events == null) {
                countInvalid();
                return;
            }
            final int size;
            try {
                size = events.size();
            } catch (RuntimeException ex) {
                countInvalid();
                return;
            }
            if (size == 0) {
                countInvalid();
                return;
            }
            for (int i = 0; i < size; i++) {
                try {
                    TrackEvent source = events.get(i);
                    if (source == null || !isValidEventId(source.eventId)) {
                        countInvalid();
                        continue;
                    }
                    dispatch(copyEvent(source));
                } catch (RuntimeException ex) {
                    countInvalid();
                }
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /** Request a non-blocking send attempt of the currently queued records. */
    public static void flush() {
        try {
            synchronized (LOCK) {
                if (suspended) {
                    return;
                }
                TrackEngine current = engine;
                if (current == null) {
                    countInvalid();
                    return;
                }
                requestDrainLocked(current);
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /** Profile updates travel as a profile_sync event. */
    public static void setUserProfile(Map<String, Object> profile) {
        try {
            if (profile == null) {
                countInvalid();
                return;
            }
            track("profile_sync", profile);
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /**
     * Stop new collection, stop scheduled draining, remove durable queue files,
     * and persist the consent decision. There is intentionally no debounce.
     */
    public static void optOut() {
        try {
            /* Publish the collection gate before waiting behind an active drain. */
            suspended = true;
            synchronized (DRAIN_LOCK) {
                synchronized (LOCK) {
                    suspended = true;
                    consentExplicitlySet = true;
                    if (queueDir != null && !persistConsentLocked(true)) {
                        countInvalid();
                    }
                    stopTimerLocked();
                    TrackEngine old = engine;
                    engine = null;
                    drainQueued = false;
                    shutdownQuietly(old);
                    clearQueueFilesLocked(queueDir);
                }
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /** Resume only after opt-out has cleared previous durable data. */
    public static void optIn() {
        try {
            synchronized (DRAIN_LOCK) {
                synchronized (LOCK) {
                    boolean wasSuspended = suspended;
                    suspended = false;
                    consentExplicitlySet = true;
                    if (queueDir == null) {
                        /* Explicit degradation: no Environment means consent cannot persist yet. */
                        return;
                    }
                    if (!persistConsentLocked(false)) {
                        countInvalid();
                    }
                    if (wasSuspended) {
                        clearQueueFilesLocked(queueDir);
                    }
                    if (!initialized || engine != null) {
                        return;
                    }
                    engine = createEngineLocked();
                    if (engine != null) {
                        startTimerLocked();
                    }
                }
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    /** Current status snapshot (engine + facade counters). */
    public static TrackStatus getStatus() {
        TrackStatus status = new TrackStatus();
        try {
            TrackEngine current = engine;
            if (current == null) {
                status.state = suspended ? TrackStatus.State.SUSPENDED : TrackStatus.State.IDLE;
                status.droppedCount = invalidCount.get();
                status.channel = channelName(config);
                return status;
            }
            TrackStatus engineStatus = current.status();
            status.pendingCount = engineStatus.pendingCount;
            status.pendingBytes = engineStatus.pendingBytes;
            status.health = engineStatus.health;
            status.degradeLevel = engineStatus.degradeLevel;
            status.sentCount = engineStatus.sentCount;
            status.droppedCount = engineStatus.droppedCount + invalidCount.get();
            status.state = suspended ? TrackStatus.State.SUSPENDED
                    : (engineStatus.degradeLevel >= 2 ? TrackStatus.State.DEGRADED
                    : (engineStatus.pendingCount > 0 ? TrackStatus.State.CONNECTING
                    : TrackStatus.State.CONNECTED));
            status.channel = channelName(config);
        } catch (RuntimeException ex) {
            countInvalid();
            status.state = suspended ? TrackStatus.State.SUSPENDED : TrackStatus.State.IDLE;
            status.droppedCount = invalidCount.get();
            status.channel = channelName(config);
        }
        return status;
    }

    /** Test/debug hook: reset facade state (never call in production). */
    static void resetForTest() {
        synchronized (DRAIN_LOCK) {
            synchronized (LOCK) {
                stopTimerLocked();
                shutdownQuietly(engine);
                engine = null;
                config = null;
                env = null;
                queueDir = null;
                initialized = false;
                suspended = false;
                consentExplicitlySet = false;
                drainQueued = false;
                invalidCount.set(0);
            }
        }
    }

    private static TrackEngine createEngineLocked() {
        try {
            Environment environment = env;
            TrackConfig cfg = config;
            File directory = queueDir;
            if (environment == null || cfg == null || directory == null) {
                countInvalid();
                return null;
            }
            Transport transport = environment.createTransport(cfg);
            Logger logger = environment.logger();
            if (transport == null) {
                countInvalid();
                return null;
            }
            if (logger == null) {
                logger = Logger.NOOP;
            }
            return new TrackEngine(cfg, directory, transport, TimeSource.SYSTEM, logger);
        } catch (RuntimeException ex) {
            countInvalid();
            return null;
        }
    }

    private static void startTimerLocked() {
        if (timer != null || suspended || engine == null || config == null) {
            return;
        }
        try {
            final long interval = config.flushIntervalMs;
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
                    try {
                        synchronized (LOCK) {
                            if (!suspended && engine != null) {
                                requestDrainLocked(engine);
                            }
                        }
                    } catch (RuntimeException ex) {
                        countInvalid();
                    }
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            countInvalid();
            stopTimerLocked();
        }
    }

    private static void stopTimerLocked() {
        ScheduledExecutorService old = timer;
        timer = null;
        if (old != null) {
            try {
                old.shutdownNow();
            } catch (RuntimeException ignored) {
                // A failed shutdown must not expose a business exception.
            }
        }
    }

    /** Schedule an API-owned drain so consent transition can serialize it. LOCK held. */
    private static void requestDrainLocked(final TrackEngine current) {
        if (drainQueued || suspended || current == null || current != engine || timer == null) {
            return;
        }
        drainQueued = true;
        try {
            timer.execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (DRAIN_LOCK) {
                        try {
                            synchronized (LOCK) {
                                drainQueued = false;
                                if (suspended || current != engine) {
                                    return;
                                }
                            }
                            /* Do not call TrackEngine.drainAsync(): this task is consent-serialized. */
                            current.drainNow();
                        } catch (RuntimeException ex) {
                            countInvalid();
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            drainQueued = false;
            countInvalid();
        }
    }

    private static TrackEvent newEvent(String eventId, Map<String, Object> properties) {
        TrackEvent event = new TrackEvent();
        event.eventId = eventId;
        event.id = UUID.randomUUID().toString();
        event.dedupKey = event.id;
        event.eventTime = System.currentTimeMillis();
        fillSnapshot(event, properties);
        return event;
    }

    /** Copy a caller-owned event so caller mutations cannot change what is persisted. */
    private static TrackEvent copyEvent(TrackEvent source) {
        TrackEvent event = new TrackEvent();
        event.eventId = source.eventId;
        event.id = empty(source.id) ? UUID.randomUUID().toString() : source.id;
        event.dedupKey = empty(source.dedupKey) ? event.id : source.dedupKey;
        event.eventTime = source.eventTime <= 0 ? System.currentTimeMillis() : source.eventTime;
        event.sentTime = source.sentTime;
        event.sdkVer = source.sdkVer;
        fillSnapshot(event, source.properties);
        return event;
    }

    private static void fillSnapshot(TrackEvent event, Map<String, Object> properties) {
        Environment environment = env;
        if (environment != null) {
            event.appVer = safeEnvironmentString(environment, 0);
            event.osVer = safeEnvironmentString(environment, 1);
            event.device = safeEnvironmentString(environment, 2);
        }
        event.properties = safeProperties(config == null ? null : config.globalProps, properties);
    }

    private static String safeEnvironmentString(Environment environment, int field) {
        try {
            switch (field) {
                case 0: return environment.appVersionName();
                case 1: return environment.osVersion();
                default: return environment.deviceModel();
            }
        } catch (RuntimeException ex) {
            countInvalid();
            return null;
        }
    }

    /**
     * Converts values now, before persistence, and exposes only an immutable map
     * to the core. A throwing iterator, entry getter, or toString skips its one
     * property while retaining the event body and every already-safe property.
     */
    private static Map<String, Object> safeProperties(Map<String, Object> globals,
                                                        Map<String, Object> properties) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<String, Object>();
        copyProperties(snapshot, globals);
        copyProperties(snapshot, properties);
        if (snapshot.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.<String, Object>unmodifiableMap(snapshot);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void copyProperties(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        Iterator iterator;
        try {
            iterator = source.entrySet().iterator();
        } catch (RuntimeException ex) {
            countInvalid();
            return;
        }
        while (true) {
            final boolean hasNext;
            try {
                hasNext = iterator.hasNext();
            } catch (RuntimeException ex) {
                countInvalid();
                return;
            }
            if (!hasNext) {
                return;
            }
            final Object next;
            try {
                next = iterator.next();
            } catch (RuntimeException ex) {
                countInvalid();
                return;
            }
            if (!(next instanceof Map.Entry)) {
                countInvalid();
                continue;
            }
            Map.Entry entry = (Map.Entry) next;
            final Object rawKey;
            final Object rawValue;
            try {
                rawKey = entry.getKey();
                rawValue = entry.getValue();
            } catch (RuntimeException ex) {
                countInvalid();
                continue;
            }
            if (!(rawKey instanceof String)) {
                countInvalid();
                continue;
            }
            if (isNonFinite(rawValue)) {
                countInvalid();
                continue;
            }
            final String value;
            try {
                value = rawValue instanceof String ? (String) rawValue : String.valueOf(rawValue);
            } catch (RuntimeException ex) {
                countInvalid();
                continue;
            }
            if (value == null) {
                countInvalid();
                continue;
            }
            target.put((String) rawKey, value);
        }
    }

    private static boolean isNonFinite(Object value) {
        if (value instanceof Double) {
            double number = ((Double) value).doubleValue();
            return Double.isNaN(number) || Double.isInfinite(number);
        }
        if (value instanceof Float) {
            float number = ((Float) value).floatValue();
            return Float.isNaN(number) || Float.isInfinite(number);
        }
        return false;
    }

    private static void dispatch(TrackEvent event) {
        if (event == null) {
            countInvalid();
            return;
        }
        try {
            synchronized (LOCK) {
                if (suspended) {
                    return;
                }
                TrackEngine current = engine;
                if (current == null) {
                    countInvalid();
                    return;
                }
                boolean persisted = current.enqueue(event);
                TrackConfig cfg = config;
                if (persisted && cfg != null && current.outbox().pendingCount() >= cfg.batchCount) {
                    requestDrainLocked(current);
                }
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    private static boolean isValidEventId(String eventId) {
        return eventId != null && !eventId.trim().isEmpty() && eventId.length() <= MAX_EVENT_ID_LENGTH;
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }

    private static String channelName(TrackConfig cfg) {
        try {
            return cfg != null && cfg.channelMode == TrackConfig.ChannelMode.AIDL ? "aidl" : "provider";
        } catch (RuntimeException ex) {
            countInvalid();
            return "provider";
        }
    }

    private static boolean readPersistedOptOut(File directory) {
        File file = consentFile(directory);
        if (file == null || !file.isFile()) {
            return false;
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] bytes = new byte[(int) Math.min(file.length(), 32L)];
            int read = input.read(bytes);
            return read > 0 && CONSENT_OUT.equals(new String(bytes, 0, read, StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException ex) {
            countInvalid();
            return false;
        } finally {
            closeQuietly(input);
        }
    }

    /** LOCK held. A failed write is reported as invalid but never thrown. */
    private static boolean persistConsentLocked(boolean optedOut) {
        File file = consentFile(queueDir);
        if (file == null) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file, false);
            output.write((optedOut ? CONSENT_OUT : CONSENT_IN).getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        } finally {
            closeQuietly(output);
        }
    }

    /** LOCK held; TrackEngine is closed before this is called. */
    private static void clearQueueFilesLocked(File directory) {
        if (directory == null) {
            return;
        }
        try {
            File[] files = directory.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                try {
                    if (file != null && file.getName().startsWith("track-") && file.isFile()
                            && !file.delete()) {
                        countInvalid();
                    }
                } catch (RuntimeException ex) {
                    countInvalid();
                }
            }
        } catch (RuntimeException ex) {
            countInvalid();
        }
    }

    private static File consentFile(File directory) {
        try {
            return directory == null ? null : new File(directory, CONSENT_FILE);
        } catch (RuntimeException ex) {
            countInvalid();
            return null;
        }
    }

    private static void shutdownQuietly(TrackEngine current) {
        if (current != null) {
            try {
                current.shutdown();
            } catch (RuntimeException ex) {
                countInvalid();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best effort for the consent marker only.
            }
        }
    }

    private static void countInvalid() {
        invalidCount.incrementAndGet();
    }

    /** Visible for tests: retained for source compatibility; API debounce was removed. */
    static long togglesIgnored() {
        return 0L;
    }

    /** Visible for tests. */
    static List<TrackEvent> asList(TrackEvent... events) {
        List<TrackEvent> out = new ArrayList<TrackEvent>();
        if (events != null) {
            for (TrackEvent event : events) {
                out.add(event);
            }
        }
        return out;
    }
}
