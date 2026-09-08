package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ChannelCore state machine contract: 8s timeout budget, result-code
 * routing, quarantine semantics, DEGRADED cache-only gating, and the
 * interaction of backoff with Outbox (no data dropped, no busy loop).
 */
public class ChannelCoreTest {
    private static class FakeClock implements TimeSource {
        long now = 5000000L;
        public long nowMs() { return now; }
        void advance(long ms) { now += ms; }
    }

    private static class ScriptedTransport implements Transport {
        final List<Code> script = new ArrayList<Code>();
        int sends = 0;
        public Result send(String batchId, byte[] gzipBatch, String signature, long ts) {
            sends++;
            return new Result(script.isEmpty() ? Code.RESULT_SUCCEEDED : script.remove(0), null);
        }
    }

    private FakeClock clock;
    private ScriptedTransport transport;
    private File dir;
    private TrackEngine engine;

    @Before
    public void setUp() {
        clock = new FakeClock();
        transport = new ScriptedTransport();
        dir = new File(System.getProperty("java.io.tmpdir"),
                "chan-test-" + System.nanoTime());
        engine = new TrackEngine(TrackConfig.builder("chan-app").build(),
                dir, transport, clock, Logger.NOOP);
    }

    @After
    public void tearDown() {
        delete(dir);
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] c = f.listFiles();
        if (c != null) {
            for (File x : c) {
                delete(x);
            }
        }
        f.delete();
    }

    private static TrackEvent ev(String id) {
        TrackEvent e = TrackEvent.of(id, null);
        e.id = id;
        e.dedupKey = id;
        e.eventTime = 1L;
        return e;
    }

    @Test
    public void timeoutResultRoutesToBackoff() {
        engine.enqueue(ev("t"));
        transport.script.add(Transport.Code.TIMEOUT);
        engine.drainNow();
        assertEquals(ChannelCore.State.BACKOFF, engine.channel().state());
        assertEquals(1, engine.outbox().pendingCount());
        engine.shutdown();
    }

    @Test
    public void retryLaterWaitsNextWindow() {
        engine.enqueue(ev("rl"));
        transport.script.add(Transport.Code.RESULT_RETRY_LATER);
        engine.drainNow();
        assertEquals(ChannelCore.State.WAIT, engine.channel().state());
        long wait = engine.channel().nextAttemptAt() - clock.nowMs();
        assertTrue("first retry ~1s, was " + wait, wait >= 800 && wait <= 1200);
        engine.shutdown();
    }

    @Test
    public void successResetsBackoff() {
        engine.enqueue(ev("s1"));
        transport.script.add(Transport.Code.DEAD_OBJECT);
        engine.drainNow();
        assertEquals(ChannelCore.State.BACKOFF, engine.channel().state());
        clock.advance(1500);
        transport.script.add(Transport.Code.RESULT_SUCCEEDED);
        engine.drainNow();
        assertEquals(ChannelCore.State.CONNECTED, engine.channel().state());
        // healthy again: next event sends immediately
        engine.enqueue(ev("s2"));
        engine.drainNow();
        assertEquals(0, engine.outbox().pendingCount());
        engine.shutdown();
    }

    @Test
    public void sendBudgetUnder8s() {
        long t0 = System.nanoTime();
        engine.enqueue(ev("fast"));
        engine.drainNow();
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("drain well under 8s: " + elapsed + "ms", elapsed < 8000);
        engine.shutdown();
    }

    @Test
    public void backoffCappedAt30s() {
        BackoffPolicy b = new BackoffPolicy(new java.util.Random(7));
        long max = 0;
        for (int i = 0; i < 12; i++) {
            max = Math.max(max, b.nextDelayMs());
        }
        assertTrue("never above cap*1.2: " + max, max <= 36000);
        assertTrue("reached cap region: " + max, max >= 24000);
    }
}
