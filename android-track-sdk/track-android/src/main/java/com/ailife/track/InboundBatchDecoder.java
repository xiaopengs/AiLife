package com.ailife.track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates and decodes a batch received by an Android IPC entry point.
 *
 * <p>The sender signs the wire blob, which is gzip(encrypted-or-plain-proto).
 * Consequently signature verification precedes decompression, and optional
 * AES-GCM decryption happens only after gzip decompression. Both the provider
 * and AIDL service use this class so neither transport can drift from that
 * ordering or from the ingress limits.</p>
 */
final class InboundBatchDecoder {
    static final int PROTOCOL_VERSION = 1;
    static final int MAX_BLOB_BYTES = BatchCodec.MAX_BATCH_BYTES;
    static final long MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L;
    private static final int AES_GCM_OVERHEAD_BYTES = 12 + 16;

    private InboundBatchDecoder() { }

    static DecodedBatch decode(int version, String appKey, boolean encrypted, byte[] blob,
                               String signature, long timestampMs, long nowMs) {
        if (version != PROTOCOL_VERSION) {
            return DecodedBatch.invalid("unsupported protocol version");
        }
        if (appKey == null || appKey.trim().isEmpty()) {
            return DecodedBatch.invalid("missing app key");
        }
        if (blob == null || blob.length == 0 || blob.length > MAX_BLOB_BYTES) {
            return DecodedBatch.invalid("invalid blob size");
        }
        if (timestampMs < nowMs - MAX_CLOCK_SKEW_MS
                || timestampMs > nowMs + MAX_CLOCK_SKEW_MS) {
            return DecodedBatch.invalid("timestamp outside replay window");
        }
        if (!Signature.safeEquals(signature,
                Signature.signBatch(appKey, timestampMs, blob))) {
            return DecodedBatch.invalid("bad signature");
        }

        try {
            int compressedPayloadLimit = encrypted
                    ? MAX_BLOB_BYTES + AES_GCM_OVERHEAD_BYTES : MAX_BLOB_BYTES;
            byte[] payload = Gzip.decompress(blob, compressedPayloadLimit);
            byte[] proto = encrypted
                    ? Signature.decrypt(Signature.deriveAesKey(appKey), payload)
                    : payload;
            if (proto == null || proto.length == 0 || proto.length > MAX_BLOB_BYTES) {
                return DecodedBatch.invalid(encrypted ? "decrypt failed" : "invalid batch");
            }
            List<TrackEvent> events = new ArrayList<TrackEvent>();
            for (byte[] raw : BatchCodec.decodeBatch(proto)) {
                events.add(BatchCodec.decodeEvent(raw));
            }
            return DecodedBatch.valid(events);
        } catch (IOException e) {
            return DecodedBatch.invalid("invalid gzip payload");
        } catch (RuntimeException e) {
            // Includes malformed protobuf and crypto-provider failures. Do not
            // expose exception details across IPC boundaries.
            return DecodedBatch.invalid("invalid batch payload");
        }
    }

    static final class DecodedBatch {
        final List<TrackEvent> events;
        final String detail;

        private DecodedBatch(List<TrackEvent> events, String detail) {
            this.events = events;
            this.detail = detail;
        }

        static DecodedBatch valid(List<TrackEvent> events) {
            return new DecodedBatch(Collections.unmodifiableList(events), null);
        }

        static DecodedBatch invalid(String detail) {
            return new DecodedBatch(null, detail);
        }

        boolean isValid() {
            return events != null;
        }
    }
}
