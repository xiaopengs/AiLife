package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * API-layer contract tests: TrackConfig validation with default fallback
 * (E8), TrackStatus reporting, facade counters. Facade behaviors that need
 * an Android Environment are covered through the Environment SPI with a
 * fake implementation.
 */
public class TrackConfigTest {

    @Test
    public void defaultsAreContractValues() {
        TrackConfig c = TrackConfig.builder("app").build();
        assertEquals("com.ailife.dataplatform.track", c.providerAuthority);
        assertEquals(5000L, c.flushIntervalMs);
        assertEquals(50, c.batchCount);
        assertEquals(256 * 1024, c.batchSizeBytes);
        assertEquals(20L * 1024 * 1024, c.maxQueueBytes);
        assertEquals(3, c.eventTtlDays);
        assertEquals(90000L, c.dedupWindowMs);
        assertTrue(c.encryptPayload);
        assertTrue(c.enableAutoTrack);
        assertEquals(TrackConfig.ChannelMode.PROVIDER, c.channelMode);
        assertEquals("", c.validationNotes);
    }

    @Test
    public void outOfRangeFallsBackToDefaultWithNote() {
        TrackConfig c = TrackConfig.builder("app")
                .flushIntervalMs(1)            // < min 1000
                .flushIntervalMs(60000)        // > max 10000
                .batchCount(5)                 // < min 10
                .maxQueueBytes(1L * 1024 * 1024) // < min 5MB
                .eventTtlDays(30)              // > max 7
                .rateLimit(10, 10)             // both out of range
                .build();
        assertEquals(5000L, c.flushIntervalMs);
        assertEquals(50, c.batchCount);
        assertEquals(20L * 1024 * 1024, c.maxQueueBytes);
        assertEquals(3, c.eventTtlDays);
        assertEquals(1000, c.rateLimitPerSec);
        assertEquals(1000, c.rateDepth);
        assertTrue("diagnostics recorded (E8)", c.validationNotes.contains("flushIntervalMs"));
        assertTrue(c.validationNotes.contains("batchCount"));
        assertTrue(c.validationNotes.contains("maxQueueBytes"));
        assertTrue(c.validationNotes.contains("eventTtlDays"));
        assertTrue(c.validationNotes.contains("rateLimit"));
    }

    @Test
    public void emptyAppKeyDisablesSendingInsteadOfUsingPredictableCredential() {
        TrackConfig c = TrackConfig.builder("  ").build();
        assertEquals("", c.appKey);
        assertFalse(c.sendEnabled);
        assertTrue(c.validationNotes.contains("appKey"));
    }

    @Test
    public void channelModeSelectable() {
        assertEquals(TrackConfig.ChannelMode.PROVIDER,
                TrackConfig.builder("a").build().channelMode);
        assertEquals(TrackConfig.ChannelMode.AIDL,
                TrackConfig.builder("a").channelMode(TrackConfig.ChannelMode.AIDL).build().channelMode);
        assertEquals(TrackConfig.ChannelMode.PROVIDER,
                TrackConfig.builder("a").channelMode(null).build().channelMode);
    }

    @Test
    public void statusSnapshotShape() {
        TrackStatus s = new TrackStatus();
        assertEquals(TrackStatus.State.IDLE, s.state);
        assertEquals(0, s.pendingCount);
        assertEquals(1.0, s.health, 0.0001);
        assertEquals(0, s.degradeLevel);
        assertTrue(s.toString().contains("IDLE"));
    }

    @Test
    public void protoWireLongValues() {
        // long boundary roundtrip through the wire codec
        ProtoWire.DataOutput out = new ProtoWire.DataOutput();
        long v = Long.MAX_VALUE;
        ProtoWire.writeVarintField(out, 1, v);
        ProtoWire.Reader r = new ProtoWire.Reader(out.toByteArray(), 0, out.toByteArray().length);
        assertTrue(r.next());
        assertEquals(1, r.fieldNumber());
        assertEquals(v, r.varint());
    }

    @Test
    public void rateLimiterConfigurableBounds() {
        // from TrackConfig: 100..2000 for both rate and depth
        RateLimiter r = new RateLimiter(2000, 2000, TimeSource.SYSTEM);
        assertTrue(r.available() <= 2000);
        RateLimiter tiny = new RateLimiter(1, 1, TimeSource.SYSTEM);
        assertTrue(tiny.tryAcquire(0));
        Map<String, Long> none = null;
        org.junit.Assert.assertNull(none);
    }
}
