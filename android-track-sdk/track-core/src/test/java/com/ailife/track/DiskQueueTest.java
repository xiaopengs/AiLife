package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Core durability: journal FIFO, quota, torn-tail recovery, restart survival. */
public class DiskQueueTest {
    private File dir;
    private TimeSource time;

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "track-test-" + System.nanoTime());
        time = new TimeSource() {
            public long nowMs() {
                return 1000000L;
            }
        };
    }

    @After
    public void tearDown() {
        delete(dir);
    }

    private static void delete(File f) {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                delete(c);
            }
        }
        f.delete();
    }

    private static byte[] rec(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void fifoOrderAndPeekDoesNotConsume() throws IOException {
        DiskQueue q = new DiskQueue(dir, 1 << 20, time, Logger.NOOP, "t-");
        q.offer(rec("a"));
        q.offer(rec("b"));
        q.offer(rec("c"));
        List<byte[]> peek = q.peek(10);
        assertEquals(3, peek.size());
        assertEquals("a", new String(peek.get(0)));
        assertEquals("b", new String(peek.get(1)));
        assertEquals("c", new String(peek.get(2)));
        // peek again: same content (no consumption)
        assertEquals(3, q.peek(10).size());
        // remove first two
        q.removeFirst(2);
        List<byte[]> left = q.peek(10);
        assertEquals(1, left.size());
        assertEquals("c", new String(left.get(0)));
        q.close();
    }

    @Test
    public void quotaEvictsOldestWhenFull() throws IOException {
        DiskQueue q = new DiskQueue(dir, 31, time, Logger.NOOP, "t-");
        assertTrue(q.offer(rec("aaaa"))); // 4+4=8B
        assertTrue(q.offer(rec("bbbb")));
        assertTrue(q.offer(rec("cccc")));
        assertTrue(q.offer(rec("dddd"))); // evicts "aaaa" to admit newest
        assertEquals(1, q.lastOfferEvictedCount());
        assertEquals(3, q.peek(10).size());
        assertEquals("bbbb", new String(q.peek(10).get(0)));
        q.close();
    }

    @Test
    public void survivesRestart() throws IOException {
        DiskQueue q = new DiskQueue(dir, 1 << 20, time, Logger.NOOP, "t-");
        q.offer(rec("x1"));
        q.offer(rec("x2"));
        q.close();
        DiskQueue q2 = new DiskQueue(dir, 1 << 20, time, Logger.NOOP, "t-");
        List<byte[]> peek = q2.peek(10);
        assertEquals(2, peek.size());
        assertEquals("x1", new String(peek.get(0)));
        q2.close();
    }

    @Test
    public void tornTailIsTruncatedNotFatal() throws IOException {
        // simulate kill mid-write: valid frames + torn tail
        DiskQueue q = new DiskQueue(dir, 1 << 20, time, Logger.NOOP, "t-");
        q.offer(rec("good1"));
        q.offer(rec("good2"));
        q.close();
        File part = dir.listFiles()[0];
        FileOutputStream out = new FileOutputStream(part, true);
        out.write(new byte[] {0, 0, 0, 50}); // claims 50B, never completed
        out.write("half".getBytes("UTF-8"));
        out.close();

        DiskQueue q2 = new DiskQueue(dir, 1 << 20, time, Logger.NOOP, "t-");
        List<byte[]> peek = q2.peek(10);
        assertEquals(2, peek.size());
        assertEquals("good1", new String(peek.get(0)));
        // torn tail must not break subsequent appends
        assertTrue(q2.offer(rec("good3")));
        assertEquals(3, q2.peek(10).size());
        q2.close();
    }

    @Test
    public void dedupWindowSemantics() {
        Deduplicator d = new Deduplicator(90, 1000);
        assertTrue(d.isNew("k1", 0));
        assertFalse(d.isNew("k1", 89));
        assertTrue(d.isNew("k1", 90)); // window expired
        assertTrue(d.isNew("k2", 0));
        assertFalse(d.isNew("k2", 10));
        // null/empty keys never dedup
        assertTrue(d.isNew(null, 0));
        assertTrue(d.isNew("", 0));
    }

    @Test
    public void backoffGrowthAndCap() {
        BackoffPolicy b = new BackoffPolicy(new java.util.Random(42));
        long max = 0;
        long last = 0;
        for (int i = 0; i < 10; i++) {
            long d = b.nextDelayMs();
            max = Math.max(max, d);
            last = d;
            assertTrue("jitter keeps delay positive", d > 0);
        }
        assertTrue("grows to the 30s cap region", last >= 16000);
        assertTrue("never exceeds cap*1.2", max <= 36000);
        b.reset();
        assertEquals(0, b.attempt());
    }

    @Test
    public void rateLimiterBurstThenRefill() {
        TimeSource t = new TimeSource() {
            long now = 0;
            public long nowMs() {
                return now;
            }
        };
        RateLimiter r = new RateLimiter(1000, 10, t);
        for (int i = 0; i < 10; i++) {
            assertTrue(r.tryAcquire(0));
        }
        assertFalse(r.tryAcquire(0));
        assertTrue(r.tryAcquire(1)); // 1ms -> 1 token
        assertFalse(r.tryAcquire(1));
        assertTrue(r.tryAcquire(1000)); // 1s later fully refilled
    }

    @Test
    public void codecRoundtrip() throws IOException {
        TrackEvent e = new TrackEvent();
        e.id = "id-1";
        e.dedupKey = "dk-1";
        e.eventId = "app_launch";
        e.eventTime = 1234567890L;
        e.sentTime = 0;
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<String, Object>();
        props.put("page", "home");
        props.put("dur", 42);
        e.properties = props;
        byte[] enc = BatchCodec.encodeEvent(e, "sdk-1.0.0", "1.2.3", "14", "Xiaomi/M12");
        TrackEvent back = BatchCodec.decodeEvent(enc);
        assertEquals("id-1", back.id);
        assertEquals("dk-1", back.dedupKey);
        assertEquals("app_launch", back.eventId);
        assertEquals(1234567890L, back.eventTime);
        assertEquals("home", back.properties.get("page"));
        assertEquals("42", back.properties.get("dur"));

        // batch roundtrip with gzip
        byte[] b2 = BatchCodec.encodeEvent(TrackEvent.of("ev2", null), "s", "a", "o", "d");
        List<byte[]> events = Arrays.asList(enc, b2);
        byte[] batch = BatchCodec.encodeBatch(events);
        byte[] gz = BatchCodec.gzipBatch(batch);
        byte[] ungz = BatchCodec.decodeGzipBatch(gz);
        assertTrue(Arrays.equals(batch, ungz));
        List<byte[]> decoded = BatchCodec.decodeBatch(ungz);
        assertEquals(2, decoded.size());
        assertEquals("app_launch", BatchCodec.decodeEvent(decoded.get(0)).eventId);
        assertEquals("ev2", BatchCodec.decodeEvent(decoded.get(1)).eventId);
    }

    @Test
    public void signatureAndEncryptionRoundtrip() throws IOException {
        byte[] gz = Gzip.compress("payload".getBytes("UTF-8"));
        String sig = Signature.signBatch("appkey", 1000L, gz);
        assertTrue(Signature.safeEquals(sig,
                Signature.signBatch("appkey", 1000L, gz)));
        assertFalse(Signature.safeEquals(sig,
                Signature.signBatch("other", 1000L, gz)));
        assertFalse(Signature.safeEquals(null, sig));

        byte[] key = Signature.deriveAesKey("appkey");
        byte[] sealed = Signature.encrypt(key, gz);
        assertFalse(Arrays.equals(sealed, gz));
        assertTrue(Arrays.equals(gz, Signature.decrypt(key, sealed)));
        assertNull("tampered payload must fail GCM auth",
                Signature.decrypt(key, Arrays.copyOf(sealed, sealed.length - 1)));
        assertNull(Signature.decrypt(key, new byte[5]));
    }
}
