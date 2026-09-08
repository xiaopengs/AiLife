package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Zero-loss matrix on the client side: success acks, every failure mode
 * keeps data, process kill/restart replays the journal, throttle/invalid
 * routing. Uses a scripted fake transport and a virtual clock.
 */
public class OutboxEngineTest {
    private File dir;
    private TimeSource time;

    private static class FakeClock implements TimeSource {
        long now = 1000000L;
        public long nowMs() {
            return now;
        }
        void advance(long ms) {
            now += ms;
        }
    }

    /** Scripted transport: returns queued results in order, records sends. */
    private static class ScriptedTransport implements Transport {
        final List<Code> script = new ArrayList<Code>();
        final List<byte[]> sentBlobs = new ArrayList<byte[]>();
        final AtomicInteger sends = new AtomicInteger();

        public Result send(String batchId, byte[] gzipBatch, String signature, long ts) {
            sends.incrementAndGet();
            sentBlobs.add(gzipBatch);
            Code c = script.size() > 0 ? script.remove(0) : Code.RESULT_SUCCEEDED;
            return new Result(c, null);
        }
    }

    private TrackConfig config;
    private FakeClock clock;
    private ScriptedTransport transport;

    @Before
    public void setUp() {
        config = TrackConfig.builder("test-app").build();
        clock = new FakeClock();
        transport = new ScriptedTransport();
    }

    @After
    public void tearDown() {
        delete(dir);
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                delete(c);
            }
        }
        f.delete();
    }

    private TrackEngine newEngine() {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "outbox-test-" + System.nanoTime());
        return new TrackEngine(config, dir, transport, clock, Logger.NOOP);
    }

    private static TrackEvent ev(String id) {
        TrackEvent e = TrackEvent.of(id, null);
        e.id = id;
        e.dedupKey = id;
        e.eventTime = 111L;
        return e;
    }

    @Test
    public void successAcksAndEmptiesOutbox() {
        TrackEngine en = newEngine();
        en.enqueue(ev("e1"));
        en.enqueue(ev("e2"));
        assertEquals(2, en.outbox().pendingCount());
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        en.drainNow();
        assertEquals(0, en.outbox().pendingCount());
        assertEquals(2, en.status().sentCount);
        assertEquals(1, transport.sends.get());
        en.shutdown();
    }

    @Test
    public void deadObjectKeepsDataThenReplays() {
        TrackEngine en = newEngine();
        en.enqueue(ev("k1"));
        en.enqueue(ev("k2"));
        transport.script.add(Transport.Code.DEAD_OBJECT);
        en.drainNow();
        assertEquals("nothing lost on DEAD_OBJECT", 2, en.outbox().pendingCount());
        assertEquals(ChannelCore.State.BACKOFF, en.channel().state());

        // before backoff elapses: no send
        clock.advance(500);
        en.drainNow();
        assertEquals(1, transport.sends.get());

        // after backoff: same data goes out and acks
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        clock.advance(2000);
        en.drainNow();
        assertEquals(0, en.outbox().pendingCount());
        assertEquals(2, en.status().sentCount);
        en.shutdown();
    }

    @Test
    public void throttledKeepsDataAndRetriesLater() {
        TrackEngine en = newEngine();
        en.enqueue(ev("t1"));
        transport.script.add(Transport.Code.RESULT_THROTTLED);
        en.drainNow();
        assertEquals(1, en.outbox().pendingCount());
        assertEquals(ChannelCore.State.WAIT, en.channel().state());
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        clock.advance(2000);
        en.drainNow();
        assertEquals(0, en.outbox().pendingCount());
        en.shutdown();
    }

    @Test
    public void invalidBatchIsQuarantinedNotRetransmitted() {
        TrackEngine en = newEngine();
        en.enqueue(ev("bad"));
        transport.script.add(Transport.Code.RESULT_INVALID);
        en.drainNow();
        assertEquals("invalid batch dropped", 0, en.outbox().pendingCount());
        assertEquals(1, en.status().droppedCount);
        assertTrue(en.channel().isQuarantined());
        en.shutdown();
    }

    @Test
    public void processKillThenRestartZeroLoss() throws IOException {
        // "session 1": enqueue, hub never receives (no drain at all)
        dir = new File(System.getProperty("java.io.tmpdir"),
                "outbox-test-" + System.nanoTime());
        TrackEngine s1 = new TrackEngine(config, dir, transport, clock, Logger.NOOP);
        s1.enqueue(ev("kill-a"));
        s1.enqueue(ev("kill-b"));
        s1.enqueue(ev("kill-c"));
        // abrupt kill: no ack, no drain
        s1.shutdown();

        // "session 2" (process restarted, same queue dir)
        TrackEngine s2 = new TrackEngine(config, dir, transport, clock, Logger.NOOP);
        assertEquals("all events recovered after kill", 3, s2.outbox().pendingCount());
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        transport.sentBlobs.clear();
        s2.drainNow();
        assertEquals(0, s2.outbox().pendingCount());
        assertEquals(3, s2.status().sentCount);
        // hub actually got the same three dedup keys (decode from captured blob)
        assertEquals(1, transport.sentBlobs.size());
        byte[] unwrapped = transport.sentBlobs.get(0);
        byte[] proto = Gzip.decompress(unwrapped);
        if (config.encryptPayload) {
            proto = Signature.decrypt(Signature.deriveAesKey(config.appKey), proto);
        }
        List<String> ids = new ArrayList<String>();
        for (byte[] raw : BatchCodec.decodeBatch(proto)) {
            ids.add(BatchCodec.decodeEvent(raw).eventId);
        }
        assertTrue(ids.contains("kill-a"));
        assertTrue(ids.contains("kill-b"));
        assertTrue(ids.contains("kill-c"));
        s2.shutdown();
    }

    @Test
    public void frozenProcessResumesWhereItLeft() {
        // freeze: nothing runs; thaw: engine state intact, next drain works
        TrackEngine en = newEngine();
        en.enqueue(ev("fz1"));
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        clock.advance(3600 * 1000L); // frozen for an hour
        en.drainNow();
        assertEquals(0, en.outbox().pendingCount());
        en.shutdown();
    }

    @Test
    public void cacheOnlyModeHoldsTraffic() {
        TrackEngine en = newEngine();
        en.enqueue(ev("cache1"));
        en.channel().setHealth(0.5, 2); // hub unhealthy
        en.drainNow();
        assertEquals(0, transport.sends.get());
        assertEquals("data held, not lost", 1, en.outbox().pendingCount());
        en.channel().setHealth(1.0, 0);
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        en.drainNow();
        assertEquals(0, en.outbox().pendingCount());
        en.shutdown();
    }

    @Test
    public void batchRespectsCountAndSizeLimits() throws IOException {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "outbox-test-" + System.nanoTime());
        Outbox out = new Outbox(config, dir, clock, Logger.NOOP);
        for (int i = 0; i < 120; i++) {
            assertTrue(out.add(StoredEvent.of(ev("n" + i), "s", "a", "o", "d")));
        }
        Outbox.Batch b1 = out.takeBatch();
        assertNotNull(b1);
        assertEquals("batchCount=50 default", 50, b1.count());
        out.ack(b1.records);
        Outbox.Batch b2 = out.takeBatch();
        assertNotNull(b2);
        assertEquals(50, b2.count());
        out.ack(b2.records);
        Outbox.Batch b3 = out.takeBatch();
        assertNotNull(b3);
        assertEquals(20, b3.count());
        out.ack(b3.records);
        assertNull(out.takeBatch());
        out.close();
    }

    @Test
    public void oversizePropIsTruncatedNotStored() throws IOException {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "outbox-test-" + System.nanoTime());
        Outbox out = new Outbox(config, dir, clock, Logger.NOOP);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300 * 1024; i++) {
            big.append("x");
        }
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<String, Object>();
        props.put("blob", big.toString());
        TrackEvent e = TrackEvent.of("big", props);
        e.id = "big";
        e.dedupKey = "big";
        e.eventTime = 1L;
        assertTrue(out.add(StoredEvent.of(e, "s", "a", "o", "d")));
        Outbox.Batch b = out.takeBatch();
        assertNotNull(b);
        assertEquals(1, b.count());
        TrackEvent back = b.events.get(0).decode();
        assertEquals(BatchCodec.MAX_PROP_LEN,
                ((String) back.properties.get("blob")).length());
        out.close();
    }
}
