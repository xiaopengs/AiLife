package com.ailife.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class InboundBatchDecoderTest {
    private static final String APP_KEY = "decoder-test-key";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void decodesPlaintextGzipBatch() throws Exception {
        byte[] gzip = Gzip.compress(protoBatch());
        InboundBatchDecoder.DecodedBatch result = InboundBatchDecoder.decode(
                1, APP_KEY, false, gzip, Signature.signBatch(APP_KEY, NOW, gzip), NOW, NOW);

        assertTrue(result.isValid());
        assertEquals(1, result.events.size());
        assertEquals("open", result.events.get(0).eventId);
        assertEquals(APP_KEY, result.events.get(0).appKey);
    }

    @Test
    public void decryptsEncryptedPayloadAfterGzip() throws Exception {
        byte[] encrypted = Signature.encrypt(Signature.deriveAesKey(APP_KEY), protoBatch());
        byte[] gzip = Gzip.compress(encrypted);
        InboundBatchDecoder.DecodedBatch result = InboundBatchDecoder.decode(
                1, APP_KEY, true, gzip, Signature.signBatch(APP_KEY, NOW, gzip), NOW, NOW);

        assertTrue(result.isValid());
        assertEquals("open", result.events.get(0).eventId);
    }

    @Test
    public void rejectsWrongVersionClockSignatureAndBlobLimits() throws Exception {
        byte[] gzip = Gzip.compress(protoBatch());
        String signature = Signature.signBatch(APP_KEY, NOW, gzip);

        assertFalse(InboundBatchDecoder.decode(2, APP_KEY, false, gzip, signature, NOW, NOW)
                .isValid());
        assertFalse(InboundBatchDecoder.decode(1, APP_KEY, false, gzip, signature,
                NOW - InboundBatchDecoder.MAX_CLOCK_SKEW_MS - 1, NOW).isValid());
        assertFalse(InboundBatchDecoder.decode(1, APP_KEY, false, gzip, "bad", NOW, NOW)
                .isValid());
        assertFalse(InboundBatchDecoder.decode(1, APP_KEY, false,
                new byte[InboundBatchDecoder.MAX_BLOB_BYTES + 1], signature, NOW, NOW)
                .isValid());
    }

    @Test
    public void rejectsDecryptFailure() throws Exception {
        byte[] gzip = Gzip.compress(protoBatch());
        InboundBatchDecoder.DecodedBatch result = InboundBatchDecoder.decode(
                1, APP_KEY, true, gzip, Signature.signBatch(APP_KEY, NOW, gzip), NOW, NOW);

        assertFalse(result.isValid());
    }

    private static byte[] protoBatch() {
        TrackEvent event = TrackEvent.of("open", null);
        event.id = "event-id";
        event.dedupKey = "dedup";
        event.eventTime = NOW;
        event.sentTime = NOW;
        List<byte[]> events = new ArrayList<byte[]>();
        events.add(BatchCodec.encodeEvent(event, "1.0.0", "1", "34", "device"));
        return BatchCodec.encodeBatch(events);
    }
}
