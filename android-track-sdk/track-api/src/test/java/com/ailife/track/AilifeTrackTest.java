package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** JVM contract tests for public facade input safety and consent behavior. */
public class AilifeTrackTest {
    private File dir;
    private CapturingTransport transport;
    private TestEnvironment environment;

    @Before
    public void setUp() {
        AilifeTrack.resetForTest();
        dir = new File(System.getProperty("java.io.tmpdir"), "ailife-api-test-" + System.nanoTime());
        transport = new CapturingTransport();
        environment = new TestEnvironment(dir, transport);
    }

    @After
    public void tearDown() {
        AilifeTrack.resetForTest();
        delete(dir);
    }

    @Test
    public void unsafePropertiesAreCountedButEventBodyAndSnapshotSurvive() throws Exception {
        init(50);
        final Map<String, Object> hostile = new AbstractMap<String, Object>() {
            @Override
            public java.util.Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("application map failure");
            }
        };
        final Object throwingValue = new Object() {
            @Override
            public String toString() {
                throw new IllegalArgumentException("application value failure");
            }
        };
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("good", "first");
        props.put(null, "skip");
        props.put("nan", Double.NaN);
        props.put("infinity", Float.POSITIVE_INFINITY);
        props.put("throws", throwingValue);

        long beforeInvalid = AilifeTrack.getStatus().droppedCount;
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.track("hostile_map", hostile); }
        });
        AilifeTrack.track("safe_props", props);
        props.put("good", "changed-after-track");
        AilifeTrack.flush();

        awaitSent(2);
        List<TrackEvent> sent = transport.events();
        assertEquals(2, sent.size());
        TrackEvent safe = event(sent, "safe_props");
        assertNotNull(safe);
        assertEquals("first", safe.properties.get("good"));
        assertFalse(safe.properties.containsKey("nan"));
        assertFalse(safe.properties.containsKey("infinity"));
        assertFalse(safe.properties.containsKey("throws"));
        assertTrue("every skipped property/map failure is counted",
                AilifeTrack.getStatus().droppedCount > beforeInvalid);

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            tooLong.append('e');
        }
        long beforeLongId = AilifeTrack.getStatus().droppedCount;
        AilifeTrack.track(tooLong.toString(), Collections.<String, Object>emptyMap());
        assertEquals("eventId > 128 is rejected and counted", beforeLongId + 1,
                AilifeTrack.getStatus().droppedCount);
    }

    @Test
    public void publicBusinessInputsNeverExposeRuntimeException() {
        init(50);
        final List<TrackEvent> explodingList = new AbstractListAdapter<TrackEvent>() {
            @Override public int size() { return 2; }
            @Override public TrackEvent get(int index) {
                if (index == 0) {
                    throw new IllegalArgumentException("hostile list");
                }
                return TrackEvent.of("still_processed", null);
            }
        };
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.track(null, null); }
        });
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.trackList(explodingList); }
        });
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.setUserProfile(null); }
        });
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.flush(); }
        });
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.optOut(); }
        });
        mustNotThrow(new Runnable() {
            @Override public void run() { AilifeTrack.optIn(); }
        });
        assertTrue(AilifeTrack.getStatus().droppedCount >= 3);
    }

    @Test
    public void trackListProcessesMoreThanTwoHundredAndUsesOutboxBatchCount() throws Exception {
        init(10);
        List<TrackEvent> input = new ArrayList<TrackEvent>();
        for (int i = 0; i < 251; i++) {
            input.add(TrackEvent.of("bulk_" + i, Collections.<String, Object>singletonMap("n", i)));
        }
        AilifeTrack.trackList(input);
        AilifeTrack.flush();

        awaitSent(251);
        List<List<TrackEvent>> batches = transport.batches();
        int total = 0;
        List<String> ids = new ArrayList<String>();
        for (List<TrackEvent> batch : batches) {
            assertTrue("wire batch obeys configured underlying batchCount", batch.size() <= 10);
            total += batch.size();
            for (TrackEvent event : batch) {
                ids.add(event.eventId);
            }
        }
        assertEquals(251, total);
        assertEquals(251, new java.util.HashSet<String>(ids).size());
        assertTrue(ids.contains("bulk_250"));
    }

    @Test
    public void optOutStopsDrainClearsQueueAndPersistsAcrossRestart() throws Exception {
        init(50);
        AilifeTrack.track("before_opt_out", Collections.<String, Object>emptyMap());
        assertEquals(1, AilifeTrack.getStatus().pendingCount);

        AilifeTrack.optOut();
        assertEquals(TrackStatus.State.SUSPENDED, AilifeTrack.getStatus().state);
        assertFalse(hasTrackJournal());
        Thread.sleep(1100L);
        assertEquals("suspended timer never drains cleared old data", 0, transport.events().size());

        /* Simulate process restart while retaining the directory and consent marker. */
        AilifeTrack.resetForTest();
        transport = new CapturingTransport();
        environment = new TestEnvironment(dir, transport);
        init(50);
        assertEquals("persisted opt-out wins on restart", TrackStatus.State.SUSPENDED,
                AilifeTrack.getStatus().state);
        AilifeTrack.track("blocked_after_restart", null);
        assertEquals(0, AilifeTrack.getStatus().pendingCount);

        AilifeTrack.optIn();
        AilifeTrack.track("after_opt_in", Collections.<String, Object>emptyMap());
        AilifeTrack.flush();
        awaitSent(1);
        assertEquals("only post-opt-in data is collected", "after_opt_in",
                transport.events().get(0).eventId);
    }

    private void init(int batchCount) {
        AilifeTrack.init(TrackConfig.builder("test-app")
                .batchCount(batchCount)
                .flushIntervalMs(1000L)
                .encryptPayload(false)
                .build(), environment);
    }

    private void awaitSent(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (transport.events().size() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals("events sent before timeout", expected, transport.events().size());
    }

    private boolean hasTrackJournal() {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.getName().startsWith("track-")) {
                return true;
            }
        }
        return false;
    }

    private static TrackEvent event(List<TrackEvent> events, String id) {
        for (TrackEvent event : events) {
            if (id.equals(event.eventId)) {
                return event;
            }
        }
        return null;
    }

    private static void mustNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            throw new AssertionError("public facade exposed RuntimeException", ex);
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        file.delete();
    }

    private static final class TestEnvironment implements Environment {
        private final File queueDir;
        private final Transport transport;

        TestEnvironment(File queueDir, Transport transport) {
            this.queueDir = queueDir;
            this.transport = transport;
        }

        @Override public File queueDir() { return queueDir; }
        @Override public String packageName() { return "test.package"; }
        @Override public String appVersionName() { return "1"; }
        @Override public String osVersion() { return "test-os"; }
        @Override public String deviceModel() { return "test-device"; }
        @Override public Logger logger() { return Logger.NOOP; }
        @Override public Transport createTransport(TrackConfig config) { return transport; }
    }

    private static final class CapturingTransport implements Transport {
        private final List<List<TrackEvent>> batches = new ArrayList<List<TrackEvent>>();

        @Override
        public synchronized Result send(String batchId, byte[] gzipBatch, String signature, long ts) {
            try {
                byte[] proto = Gzip.decompress(gzipBatch);
                List<TrackEvent> events = new ArrayList<TrackEvent>();
                for (byte[] raw : BatchCodec.decodeBatch(proto)) {
                    events.add(BatchCodec.decodeEvent(raw));
                }
                batches.add(events);
                return Result.ok();
            } catch (IOException ex) {
                return new Result(Code.RESULT_INVALID, ex.toString());
            }
        }

        synchronized List<List<TrackEvent>> batches() {
            List<List<TrackEvent>> copy = new ArrayList<List<TrackEvent>>();
            for (List<TrackEvent> batch : batches) {
                copy.add(new ArrayList<TrackEvent>(batch));
            }
            return copy;
        }

        synchronized List<TrackEvent> events() {
            List<TrackEvent> all = new ArrayList<TrackEvent>();
            for (List<TrackEvent> batch : batches) {
                all.addAll(batch);
            }
            return all;
        }
    }

    /** AbstractList with a small Java 8-friendly implementation surface. */
    private abstract static class AbstractListAdapter<E> extends java.util.AbstractList<E> {
        @Override public abstract int size();
        @Override public abstract E get(int index);
    }
}
