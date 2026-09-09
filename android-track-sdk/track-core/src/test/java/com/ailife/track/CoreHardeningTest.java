package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Regressions for defensive core behavior and durable-queue invariants. */
public class CoreHardeningTest {
    private File dir;

    private static final class FixedTime implements TimeSource {
        long now = 1000000L;
        @Override public long nowMs() { return now; }
    }

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "core-hardening-" + System.nanoTime());
    }

    @After
    public void tearDown() {
        delete(dir);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    private static byte[] record(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static TrackEvent event(String id) {
        TrackEvent event = TrackEvent.of(id, null);
        event.id = id;
        event.dedupKey = id;
        event.eventTime = 1L;
        return event;
    }

    @Test
    public void invalidBatchDoesNotPoisonFollowingValidBatch() {
        final java.util.List<Transport.Code> results = new java.util.ArrayList<Transport.Code>();
        results.add(Transport.Code.RESULT_INVALID);
        results.add(Transport.Code.RESULT_SUCCEEDED);
        Transport transport = new Transport() {
            @Override public Result send(String id, byte[] payload, String sig, long ts) {
                return new Result(results.remove(0), null);
            }
        };
        TrackEngine engine = new TrackEngine(TrackConfig.builder("app").build(), dir,
                transport, new FixedTime(), Logger.NOOP);
        engine.enqueue(event("invalid"));
        engine.drainNow();
        assertEquals(0, engine.outbox().pendingCount());
        assertEquals(ChannelCore.State.CONNECTED, engine.channel().state());
        assertEquals(1, engine.status().droppedCount);

        engine.enqueue(event("valid"));
        engine.drainNow();
        assertEquals("legal batch after invalid must send", 0, engine.outbox().pendingCount());
        assertEquals(1, engine.status().sentCount);
        engine.shutdown();
    }

    @Test
    public void drainAsyncCoalescesConcurrentTriggersAndShutdownIsNoThrow() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Transport transport = new Transport() {
            @Override public Result send(String id, byte[] payload, String sig, long ts) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        return new Result(Code.TIMEOUT, "test timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(Code.TIMEOUT, "interrupted");
                }
                return new Result(Code.RESULT_SUCCEEDED, null);
            }
        };
        final TrackEngine engine = new TrackEngine(TrackConfig.builder("app").build(), dir,
                transport, new FixedTime(), Logger.NOOP);
        engine.enqueue(event("async"));
        engine.drainAsync();
        assertTrue("worker should be in the blocking transport", entered.await(5, TimeUnit.SECONDS));

        Thread[] callers = new Thread[8];
        for (int i = 0; i < callers.length; i++) {
            callers[i] = new Thread(new Runnable() {
                @Override public void run() {
                    for (int n = 0; n < 250; n++) {
                        engine.drainAsync();
                    }
                }
            });
            callers[i].start();
        }
        for (Thread caller : callers) {
            caller.join();
        }
        assertEquals("only the in-flight worker may be queued", 1, engine.drainSubmissionCount());
        release.countDown();
        for (int i = 0; i < 50 && engine.outbox().pendingCount() != 0; i++) {
            Thread.sleep(10L);
        }
        assertEquals(0, engine.outbox().pendingCount());
        engine.shutdown();
        engine.drainAsync();
    }

    @Test
    public void decodePreservesEnvironmentSnapshotFields() {
        TrackEvent original = event("launch");
        original.sentTime = 9L;
        TrackEvent decoded = BatchCodec.decodeEvent(BatchCodec.encodeEvent(original,
                "sdk-7", "app-8", "os-9", "device-10"));
        assertEquals("sdk-7", decoded.sdkVer);
        assertEquals("app-8", decoded.appVer);
        assertEquals("os-9", decoded.osVer);
        assertEquals("device-10", decoded.device);
    }

    @Test
    public void hostilePropertiesCannotBreakEventEncoding() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(null, "ignored");
        values.put("good", "kept");
        values.put("bad", new Object() {
            @Override public String toString() {
                throw new IllegalStateException("application value failure");
            }
        });
        TrackEvent original = event("props");
        original.properties = values;
        TrackEvent decoded = BatchCodec.decodeEvent(BatchCodec.encodeEvent(original,
                null, null, null, null));
        assertEquals("kept", decoded.properties.get("good"));
        assertFalse(decoded.properties.containsKey("bad"));

        Map<String, Object> throwingEntries = new AbstractMap<String, Object>() {
            @Override public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<Entry<String, Object>>() {
                    @Override public Iterator<Entry<String, Object>> iterator() {
                        return new Iterator<Entry<String, Object>>() {
                            @Override public boolean hasNext() { return true; }
                            @Override public Entry<String, Object> next() {
                                throw new IllegalStateException("application map failure");
                            }
                            @Override public void remove() { throw new UnsupportedOperationException(); }
                        };
                    }
                    @Override public int size() { return 1; }
                };
            }
        };
        original.properties = throwingEntries;
        assertNotNull(BatchCodec.encodeEvent(original, null, null, null, null));
    }

    @Test(expected = IOException.class)
    public void gzipBombIsRejectedByDefaultLimit() throws IOException {
        byte[] bomb = new byte[Gzip.DEFAULT_MAX_DECOMPRESSED_BYTES + 1];
        Gzip.decompress(Gzip.compress(bomb));
    }

    @Test
    public void gzipExplicitLimitAllowsKnownSize() throws IOException {
        byte[] raw = new byte[4097];
        Arrays.fill(raw, (byte) 7);
        assertTrue(Arrays.equals(raw, Gzip.decompress(Gzip.compress(raw), raw.length)));
    }

    @Test
    public void acknowledgedQueueSurvivesRestartAndFailedReplaceKeepsData() throws IOException {
        FixedTime time = new FixedTime();
        DiskQueue queue = new DiskQueue(dir, 1024, time, Logger.NOOP, "t-");
        queue.offer(record("a"));
        queue.offer(record("b"));
        queue.offer(record("c"));
        queue.removeFirstByHash(java.util.Collections.singletonList(record("b")));
        queue.close();

        DiskQueue restarted = new DiskQueue(dir, 1024, time, Logger.NOOP, "t-");
        List<byte[]> afterAck = restarted.peek(10);
        assertEquals(2, afterAck.size());
        assertEquals("a", new String(afterAck.get(0), StandardCharsets.UTF_8));
        assertEquals("c", new String(afterAck.get(1), StandardCharsets.UTF_8));
        restarted.close();

        File separate = new File(dir, "failure");
        DiskQueue failing = new DiskQueue(separate, 1024, time, Logger.NOOP, "t-");
        failing.offer(record("old-a"));
        failing.offer(record("old-b"));
        failing.close();
        assertTrue(new File(separate, "t-current.log").mkdir());
        failing.removeFirst(1);
        List<byte[]> preserved = failing.peek(10);
        assertEquals(2, preserved.size());
        assertEquals("old-a", new String(preserved.get(0), StandardCharsets.UTF_8));
        assertEquals("old-b", new String(preserved.get(1), StandardCharsets.UTF_8));
        failing.close();
    }

    @Test
    public void capacityEvictsOldestAndReportsCountAcrossRestart() throws IOException {
        FixedTime time = new FixedTime();
        DiskQueue queue = new DiskQueue(dir, 24, time, Logger.NOOP, "t-");
        assertTrue(queue.offer(record("aaaa")));
        assertTrue(queue.offer(record("bbbb")));
        assertTrue(queue.offer(record("cccc")));
        assertTrue(queue.offer(record("dddd")));
        assertEquals(1, queue.lastOfferEvictedCount());
        List<byte[]> retained = queue.peek(10);
        assertEquals(3, retained.size());
        assertEquals("bbbb", new String(retained.get(0), StandardCharsets.UTF_8));
        assertEquals("dddd", new String(retained.get(2), StandardCharsets.UTF_8));
        queue.close();

        DiskQueue restarted = new DiskQueue(dir, 24, time, Logger.NOOP, "t-");
        assertEquals(3, restarted.peek(10).size());
        assertEquals("bbbb", new String(restarted.peek(10).get(0), StandardCharsets.UTF_8));
        restarted.close();
    }

    @Test
    public void malformedStoredRecordsAreRejectedWithoutBoundsException() {
        assertEquals(null, StoredEvent.fromRecord(null));
        assertEquals(null, StoredEvent.fromRecord(new byte[0]));
        assertEquals(null, StoredEvent.fromRecord(new byte[] {0}));
        assertEquals(null, StoredEvent.fromRecord(new byte[] {0, 4, 1, 2}));
    }

    @Test
    public void rateLimiterRecoversImmediatelyAfterClockRollback() {
        FixedTime time = new FixedTime();
        time.now = 1000L;
        RateLimiter limiter = new RateLimiter(1000, 1, time);
        assertTrue(limiter.tryAcquire(1000L));
        assertFalse(limiter.tryAcquire(900L));
        assertTrue("rollback must not freeze refill until the old clock catches up",
                limiter.tryAcquire(901L));
    }

    @Test
    public void missingAppKeyRetainsLocalDataWithoutCallingTransport() {
        final int[] calls = new int[1];
        Transport transport = new Transport() {
            @Override public Result send(String id, byte[] payload, String sig, long ts) {
                calls[0]++;
                return Result.ok();
            }
        };
        TrackConfig config = TrackConfig.builder(" ").build();
        TrackEngine engine = new TrackEngine(config, dir, transport, new FixedTime(), Logger.NOOP);
        assertTrue(engine.enqueue(event("local-only")));
        engine.drainNow();
        assertEquals(0, calls[0]);
        assertEquals(1, engine.outbox().pendingCount());
        engine.shutdown();
    }

    @Test
    public void eventFactorySnapshotsPropertiesBeforeCallerMutation() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("screen", "home");
        TrackEvent event = TrackEvent.of("open", properties);
        properties.put("screen", "detail");
        assertEquals("home", event.properties.get("screen"));
    }

    @Test
    public void cacheOnlyChannelPollsHealthAndResumesWhenHubRecovers() {
        final boolean[] healthy = new boolean[] {false};
        final int[] sends = new int[1];
        Transport transport = new Transport() {
            @Override public Result send(String id, byte[] payload, String sig, long ts) {
                sends[0]++;
                return Result.ok();
            }

            @Override public HubStatus getHubStatus() {
                return healthy[0] ? new HubStatus(1.0, 0) : new HubStatus(0.5, 2);
            }
        };
        TrackEngine engine = new TrackEngine(TrackConfig.builder("app").build(), dir,
                transport, new FixedTime(), Logger.NOOP);
        assertTrue(engine.enqueue(event("recover")));
        engine.drainNow();
        assertEquals(0, sends[0]);
        healthy[0] = true;
        engine.drainNow();
        assertEquals(1, sends[0]);
        assertEquals(0, engine.outbox().pendingCount());
        engine.shutdown();
    }
}
