package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Hub-side zero-loss and self-protection: dedup idempotency, TTL/quota
 * eviction counters, report retry ladder, offline queueing, health
 * degradation and recovery gating.
 */
public class HubTest {
    private File dir;
    private FakeClock clock;

    private static class FakeClock implements TimeSource {
        long now = 2000000L;
        public long nowMs() {
            return now;
        }
        void advance(long ms) {
            now += ms;
        }
    }

    private static class Probe implements ReportScheduler.NetworkProbe {
        boolean online = true;
        public boolean isOnline() {
            return online;
        }
    }

    /** Records batches; configurable failure count before success. */
    private static class FakeSink implements CloudSink {
        int failures = 0;
        final List<Integer> batchSizes = new ArrayList<Integer>();
        final AtomicIntegerSent sent = new AtomicIntegerSent();

        public boolean sendBatch(String batchId, byte[] gzipProto, String signature, long ts) {
            if (failures > 0) {
                failures--;
                sent.calls++;
                return false;
            }
            sent.calls++;
            batchSizes.add(1);
            return true;
        }
        public String fetchConfig() {
            return null;
        }
    }

    private static final class AtomicIntegerSent {
        int calls = 0;
    }

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "hub-test-" + System.nanoTime());
        clock = new FakeClock();
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

    private static TrackEvent ev(String id) {
        TrackEvent e = TrackEvent.of(id, null);
        e.id = id;
        e.dedupKey = id;
        e.eventTime = 100L;
        return e;
    }

    @Test
    public void duplicateBatchesAreIdempotent() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        IngestionPipeline p = new IngestionPipeline(24L * 3600 * 1000, m);
        assertNotNull(p.accept(ev("d1"), clock.nowMs()));
        assertNull("duplicate within window rejected", p.accept(ev("d1"), clock.nowMs() + 1000));
        assertNotNull(p.accept(ev("d1"), clock.nowMs() + 25L * 3600 * 1000)); // window passed
        assertEquals(2, m.accepted());
        assertEquals(1, m.duplicates());
    }

    @Test
    public void clockSkewBeyondToleranceRejected() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        IngestionPipeline p = new IngestionPipeline(24L * 3600 * 1000, m);
        TrackEvent future = ev("skew");
        future.eventTime = clock.nowMs() + 6L * 60 * 1000; // > +5min
        assertNull(p.accept(future, clock.nowMs()));
        TrackEvent nearFuture = ev("skew2");
        nearFuture.eventTime = clock.nowMs() + 4L * 60 * 1000; // within +5min
        assertNotNull(p.accept(nearFuture, clock.nowMs()));
        assertEquals(1, m.invalid());
        assertEquals(1, m.accepted());
    }

    @Test
    public void quotaEvictionIsCounted() throws IOException {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 512, 3, m, clock, Logger.NOOP);
        int accepted = 0;
        for (int i = 0; i < 40 && accepted < 1000; i++) {
            TrackEvent e = ev("quota-" + i);
            e.eventTime = clock.nowMs();
            if (store.put(e, "s", "a")) {
                accepted++;
            }
        }
        assertTrue("some events stored", accepted > 0);
        assertTrue("quota pressure counted", m.evictedQuota() > 0);
        store.close();
    }

    @Test
    public void ttlSweepRemovesOnlyExpired() throws IOException {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 1 << 20, 1, m, clock, Logger.NOOP);
        TrackEvent old = ev("old");
        old.eventTime = clock.nowMs() - 2L * 24 * 3600 * 1000; // 2 days old
        TrackEvent fresh = ev("fresh");
        fresh.eventTime = clock.nowMs();
        assertTrue(store.put(old, "s", "a"));
        assertTrue(store.put(fresh, "s", "a"));
        assertEquals(2, store.count());
        store.evictExpired(clock.nowMs());
        assertEquals("only fresh survives", 1, store.count());
        assertEquals(1, m.evictedTtl());
        assertEquals("fresh", store.queryEvents(0, Long.MAX_VALUE, null, 10).get(0).eventId);
        store.close();
    }

    @Test
    public void reportRetryLadder30sTo30m() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 1 << 20, 3, m, clock, Logger.NOOP);
        TrackEvent e = ev("r1");
        e.eventTime = clock.nowMs();
        store.put(e, "s", "a");
        Probe probe = new Probe();
        FakeSink sink = new FakeSink();
        sink.failures = 4; // first four cloud attempts fail
        ReportScheduler s = new ReportScheduler(store, sink, probe, clock, Logger.NOOP, m);

        assertTrue(s.shouldReport(clock.nowMs()));
        assertEquals(0, s.drain());
        assertEquals(30_000, s.nextAttemptAt() - clock.nowMs(), 1);
        clock.advance(30_000);
        assertEquals(0, s.drain());
        assertEquals(60_000, s.nextAttemptAt() - clock.nowMs(), 1);
        clock.advance(60_000);
        assertEquals(0, s.drain());
        assertEquals(300_000, s.nextAttemptAt() - clock.nowMs(), 1);
        clock.advance(300_000);
        assertEquals(0, s.drain());
        assertEquals(1_800_000, s.nextAttemptAt() - clock.nowMs(), 1);
        clock.advance(1_800_000);
        assertEquals("fifth attempt succeeds; data uploaded once", 1, s.drain());
        assertEquals("store emptied after cloud 2xx", 0, store.count());
        assertEquals(-1, s.retryIndex());
        store.close();
    }

    @Test
    public void offlineHoldsDataThenDrainsOnReconnect() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 1 << 20, 3, m, clock, Logger.NOOP);
        TrackEvent e = ev("off1");
        e.eventTime = clock.nowMs();
        store.put(e, "s", "a");
        Probe probe = new Probe();
        probe.online = false;
        FakeSink sink = new FakeSink();
        ReportScheduler s = new ReportScheduler(store, sink, probe, clock, Logger.NOOP, m);
        assertFalse(s.shouldReport(clock.nowMs()));
        assertEquals(0, s.drain());
        assertEquals("offline: data held", 1, store.count());
        probe.online = true;
        assertTrue(s.shouldReport(clock.nowMs()));
        assertEquals(1, s.drain());
        assertEquals(0, store.count());
        store.close();
    }

    @Test
    public void healthDegradesAndRecoversOnRamp() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HealthManager h = new HealthManager(m, clock);
        // simulate all sends failing + invalid flood -> health below 0.6
        for (int i = 0; i < 20; i++) {
            m.incSendFailure();
        }
        for (int i = 0; i < 100; i++) {
            m.incInvalid();
        }
        h.tick(0, 20L * 1024 * 1024);
        assertTrue("unhealthy", h.health() < 0.6);
        assertEquals(2, h.degradeLevel());
        assertTrue(h.isCacheOnly());

        // recovery requires sustained health >= 0.8 for 10 minutes
        for (int i = 0; i < 2000; i++) {
            m.incSendSuccess();
            m.incAccepted();
        }
        h.tick(0, 20L * 1024 * 1024);
        assertTrue(h.health() >= 0.8);
        assertEquals("still cache-only until sustained", 2, h.degradeLevel());
        clock.advance(10 * 60 * 1000L);
        h.tick(0, 20L * 1024 * 1024);
        assertEquals("ramping at 1/4", 1, h.degradeLevel());
        assertEquals(0.25, h.rampFraction(), 0.001);
        clock.advance(5 * 60 * 1000L);
        h.tick(0, 20L * 1024 * 1024);
        assertEquals("half traffic", 0.5, h.rampFraction(), 0.001);
        clock.advance(5 * 60 * 1000L);
        h.tick(0, 20L * 1024 * 1024);
        assertEquals("fully recovered", 0, h.degradeLevel());
        assertEquals(1.0, h.rampFraction(), 0.001);
    }

    @Test
    public void endToEndClientToHubZeroLoss() throws IOException {
        // --- business process side ---
        TrackConfig cfg = TrackConfig.builder("e2e-app").build();
        TrackEngine engine = new TrackEngine(cfg, dir,
                new LoopbackTransport(hubIngest), clock, Logger.NOOP);
        for (int i = 0; i < 7; i++) {
            TrackEvent e = TrackEvent.of("e2e-" + i, null);
            e.id = "e2e-" + i;
            e.dedupKey = "e2e-" + i;
            e.eventTime = clock.nowMs();
            engine.enqueue(e);
        }
        engine.drainNow();
        engine.shutdown();

        // --- hub process side ---
        assertEquals(7, hubIngest.size());
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        IngestionPipeline pipeline = new IngestionPipeline(24L * 3600 * 1000, m);
        HubEventStore store = new HubEventStore(
                new File(dir.getParent(), "hub-" + System.nanoTime()),
                1 << 20, 3, m, clock, Logger.NOOP);
        for (TrackEvent e : hubIngest) {
            assertNotNull(pipeline.accept(e, clock.nowMs()));
            assertTrue(store.put(e, "s", "a"));
        }
        assertEquals(7, store.count());
        // duplicate replay (client retry after lost ack) is fully idempotent
        for (TrackEvent e : hubIngest) {
            assertNull(pipeline.accept(e, clock.nowMs()));
        }
        assertEquals(7, m.duplicates());
        assertEquals(7, store.count());
        store.close();
    }

    /** In-memory capture used as the "hub side" of the loopback transport. */
    static final List<TrackEvent> hubIngest = new ArrayList<TrackEvent>();

    static final class LoopbackTransport implements Transport {
        private final List<TrackEvent> sink;
        LoopbackTransport(List<TrackEvent> sink) {
            this.sink = sink;
        }
        public Result send(String batchId, byte[] gzipBatch, String signature, long ts) {
            try {
                byte[] proto = Gzip.decompress(gzipBatch);
                byte[] key = Signature.deriveAesKey("e2e-app");
                byte[] maybePlain = Signature.decrypt(key, proto);
                if (maybePlain != null) {
                    proto = maybePlain;
                }
                for (byte[] raw : BatchCodec.decodeBatch(proto)) {
                    sink.add(BatchCodec.decodeEvent(raw));
                }
                return Result.ok();
            } catch (IOException e) {
                return new Result(Code.DEAD_OBJECT, e.getMessage());
            }
        }
    }
}
