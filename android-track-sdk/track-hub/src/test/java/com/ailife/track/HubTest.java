package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** Records batches and returns scripted cloud outcomes. */
    private static class FakeSink implements CloudSink {
        final List<Integer> batchSizes = new ArrayList<Integer>();
        final List<CloudSink.Result> results = new ArrayList<CloudSink.Result>();
        final AtomicIntegerSent sent = new AtomicIntegerSent();

        public CloudSink.Result sendBatch(String batchId, byte[] gzipProto, String signature, long ts) {
            sent.calls++;
            try {
                batchSizes.add(BatchCodec.decodeBatch(Gzip.decompress(gzipProto)).size());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return results.isEmpty() ? CloudSink.Result.success() : results.remove(0);
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
        IngestionPipeline.Decision first = p.inspect(ev("d1"), clock.nowMs());
        assertEquals(IngestionPipeline.Status.VALID, first.status());
        // Before durable persistence commits the key, a retry stays valid.
        assertEquals(IngestionPipeline.Status.VALID, p.inspect(ev("d1"), clock.nowMs()).status());
        p.commit(first, clock.nowMs());
        assertEquals("duplicate within window succeeds idempotently",
                IngestionPipeline.Status.DUPLICATE,
                p.inspect(ev("d1"), clock.nowMs() + 1000).status());
        IngestionPipeline.Decision afterWindow =
                p.inspect(ev("d1"), clock.nowMs() + 25L * 3600 * 1000);
        assertEquals(IngestionPipeline.Status.VALID, afterWindow.status());
        p.commit(afterWindow, clock.nowMs() + 25L * 3600 * 1000);
        assertEquals(2, m.accepted());
        assertEquals(1, m.duplicates());
    }

    @Test
    public void clockSkewBeyondToleranceRejected() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        IngestionPipeline p = new IngestionPipeline(24L * 3600 * 1000, m);
        TrackEvent future = ev("skew");
        future.eventTime = clock.nowMs() + 6L * 60 * 1000; // > +5min
        assertEquals(IngestionPipeline.Status.INVALID, p.inspect(future, clock.nowMs()).status());
        TrackEvent nearFuture = ev("skew2");
        nearFuture.eventTime = clock.nowMs() + 4L * 60 * 1000; // within +5min
        IngestionPipeline.Decision decision = p.inspect(nearFuture, clock.nowMs());
        assertEquals(IngestionPipeline.Status.VALID, decision.status());
        p.commit(decision, clock.nowMs());
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
        for (int i = 0; i < 4; i++) {
            sink.results.add(CloudSink.Result.retryable()); // first four attempts fail
        }
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
    public void authenticationFailureStopsReportingAndKeepsQueue() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 1 << 20, 3, m, clock, Logger.NOOP);
        TrackEvent e = ev("auth");
        e.eventTime = clock.nowMs();
        assertTrue(store.put(e, "s", "a"));
        Probe probe = new Probe();
        FakeSink sink = new FakeSink();
        sink.results.add(CloudSink.Result.authFailure());
        ReportScheduler s = new ReportScheduler(store, sink, probe, clock, Logger.NOOP, m);

        assertEquals(0, s.drain());
        assertTrue(s.isStoppedForAuth());
        assertEquals(1, store.count());
        assertFalse(s.shouldReport(clock.nowMs() + 24L * 3600 * 1000));
        assertEquals(0, s.drain());
        assertEquals("no retry after 401/403", 1, sink.sent.calls);
        store.close();
    }

    @Test
    public void rateLimitUsesRetryAfterExactlyAndKeepsQueue() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 1 << 20, 3, m, clock, Logger.NOOP);
        TrackEvent e = ev("rate-limit");
        e.eventTime = clock.nowMs();
        assertTrue(store.put(e, "s", "a"));
        Probe probe = new Probe();
        FakeSink sink = new FakeSink();
        sink.results.add(CloudSink.Result.rateLimited(12345));
        sink.results.add(CloudSink.Result.success());
        ReportScheduler s = new ReportScheduler(store, sink, probe, clock, Logger.NOOP, m);

        assertEquals(0, s.drain());
        assertEquals(12345, s.nextAttemptAt() - clock.nowMs());
        assertEquals(1, store.count());
        clock.advance(12344);
        assertEquals(0, s.drain());
        assertEquals(1, sink.sent.calls);
        clock.advance(1);
        assertEquals(1, s.drain());
        assertEquals(0, store.count());
        store.close();
    }

    @Test
    public void payloadTooLargeShrinks50RecordBatchAndRetainsUnsentRecords() {
        IngestionPipeline.Metrics m = new IngestionPipeline.Metrics();
        HubEventStore store = new HubEventStore(dir, 1 << 20, 3, m, clock, Logger.NOOP);
        for (int i = 0; i < ReportScheduler.TRIGGER_COUNT; i++) {
            TrackEvent e = ev("large-" + i);
            e.eventTime = clock.nowMs();
            assertTrue(store.put(e, "s", "a"));
        }
        Probe probe = new Probe();
        FakeSink sink = new FakeSink();
        sink.results.add(CloudSink.Result.tooLarge());
        sink.results.add(CloudSink.Result.success());
        ReportScheduler s = new ReportScheduler(store, sink, probe, clock, Logger.NOOP, m);

        assertEquals(25, s.drain());
        assertEquals(Arrays.asList(50, 25), sink.batchSizes);
        assertEquals(25, s.batchLimit());
        assertEquals("only explicit success deleted the smaller successful prefix", 25, store.count());
        assertEquals(25, s.drain());
        assertEquals(0, store.count());
        store.close();
    }

    @Test
    public void batchPersistsValidTailDespiteInvalidRecordAndReplayedDuplicatesSucceed() {
        HubController hub = new HubController(new File(dir, "hub"), 20L * 1024 * 1024, 3,
                new FakeSink(), new Probe(), clock, Logger.NOOP);
        TrackEvent first = ev("batch-first");
        first.eventTime = clock.nowMs();
        TrackEvent invalid = ev("bad");
        invalid.eventId = " ";
        invalid.eventTime = clock.nowMs();
        TrackEvent tail = ev("batch-tail");
        tail.eventTime = clock.nowMs();
        List<TrackEvent> batch = Arrays.asList(first, invalid, tail);

        assertEquals(Transport.Code.RESULT_SUCCEEDED, hub.ingestBatch(batch));
        assertEquals(2, hub.queryEvents(0, Long.MAX_VALUE, null, 10).size());
        assertEquals(Transport.Code.RESULT_SUCCEEDED, hub.ingestBatch(batch));
        assertEquals("duplicates and invalid replay must not discard accepted tail", 2,
                hub.queryEvents(0, Long.MAX_VALUE, null, 10).size());
        assertEquals(2L, hub.metricsSnapshot().get("duplicates").longValue());
        hub.shutdown();
    }

    @Test
    public void partialWriteCommitsOnlyStoredKeysSoRetryCannotLoseTail() {
        HubController hub = new HubController(new File(dir, "hub"), 20L * 1024 * 1024, 3,
                new FakeSink(), new Probe(), clock, Logger.NOOP);
        TrackEvent head = ev("partial-head");
        head.eventTime = clock.nowMs();
        TrackEvent rejected = ev("partial-oversize");
        rejected.eventTime = clock.nowMs();
        rejected.appVer = repeated('x', BatchCodec.MAX_EVENT_BYTES + 64);
        TrackEvent tail = ev("partial-tail");
        tail.eventTime = clock.nowMs();
        List<TrackEvent> batch = Arrays.asList(head, rejected, tail);

        assertEquals(Transport.Code.RESULT_RETRY_LATER, hub.ingestBatch(batch));
        assertEquals(Arrays.asList("partial-head", "partial-tail"), eventIds(hub));
        // The durable prefix/tail are duplicate successes, but the event whose
        // write failed remains VALID on every replay and is never falsely lost.
        assertEquals(Transport.Code.RESULT_RETRY_LATER, hub.ingestBatch(batch));
        assertEquals(Arrays.asList("partial-head", "partial-tail"), eventIds(hub));
        assertEquals(2L, hub.metricsSnapshot().get("duplicates").longValue());
        hub.shutdown();
    }

    @Test
    public void batchRateBudgetIsReservedAtomicallyBeforeAnyWrite() {
        HubController hub = new HubController(new File(dir, "hub"), 20L * 1024 * 1024, 3,
                new FakeSink(), new Probe(), clock, Logger.NOOP);
        List<TrackEvent> tooMany = new ArrayList<TrackEvent>();
        for (int i = 0; i < TrackConfig.DEFAULT_RATE_DEPTH + 1; i++) {
            TrackEvent e = ev("rate-" + i);
            e.eventTime = clock.nowMs();
            tooMany.add(e);
        }
        assertEquals(Transport.Code.RESULT_THROTTLED, hub.ingestBatch(tooMany));
        assertEquals("no prefix may be admitted when full reservation fails", 0,
                hub.queryEvents(0, Long.MAX_VALUE, null, 2000).size());
        TrackEvent one = ev("after-throttle");
        one.eventTime = clock.nowMs();
        assertEquals(Transport.Code.RESULT_SUCCEEDED, hub.ingest(one));
        hub.shutdown();
    }

    private static List<String> eventIds(HubController hub) {
        List<String> ids = new ArrayList<String>();
        for (TrackEvent event : hub.queryEvents(0, Long.MAX_VALUE, null, 10)) {
            ids.add(event.eventId);
        }
        return ids;
    }

    private static String repeated(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
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
            IngestionPipeline.Decision decision = pipeline.inspect(e, clock.nowMs());
            assertEquals(IngestionPipeline.Status.VALID, decision.status());
            assertTrue(store.put(e, "s", "a"));
            pipeline.commit(decision, clock.nowMs());
        }
        assertEquals(7, store.count());
        // duplicate replay (client retry after lost ack) is fully idempotent
        for (TrackEvent e : hubIngest) {
            assertEquals(IngestionPipeline.Status.DUPLICATE,
                    pipeline.inspect(e, clock.nowMs()).status());
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
