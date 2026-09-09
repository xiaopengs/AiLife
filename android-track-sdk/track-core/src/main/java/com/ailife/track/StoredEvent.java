package com.ailife.track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A durable record of one client event already encoded to protobuf bytes.
 * Small POJO so tests can construct it directly.
 */
public final class StoredEvent {
    public final String dedupKey;
    public final byte[] encoded;

    public StoredEvent(String dedupKey, byte[] encoded) {
        this.dedupKey = dedupKey;
        this.encoded = encoded;
    }

    public int size() {
        return encoded.length;
    }

    /** Build from a TrackEvent with env snapshot fields. */
    public static StoredEvent of(TrackEvent e, String sdkVer, String appVer,
                                 String osVer, String device) {
        return new StoredEvent(e.dedupKey,
                BatchCodec.encodeEvent(e, sdkVer, appVer, osVer, device));
    }

    public TrackEvent decode() {
        return BatchCodec.decodeEvent(encoded);
    }

    public static List<TrackEvent> decodeAll(List<byte[]> raw) {
        List<TrackEvent> out = new ArrayList<TrackEvent>(raw.size());
        for (byte[] b : raw) {
            out.add(BatchCodec.decodeEvent(b));
        }
        return out;
    }

    /** Serialize the event envelope into a journal record: [dedupLen][dedup][bytes]. */
    public byte[] toRecord() throws IOException {
        byte[] dk = dedupKey == null ? new byte[0]
                : dedupKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[2 + dk.length + encoded.length];
        out[0] = (byte) (dk.length >> 8);
        out[1] = (byte) dk.length;
        System.arraycopy(dk, 0, out, 2, dk.length);
        System.arraycopy(encoded, 0, out, 2 + dk.length, encoded.length);
        return out;
    }

    public static StoredEvent fromRecord(byte[] rec) {
        if (rec == null || rec.length < 2) {
            return null;
        }
        int dkLen = ((rec[0] & 0xFF) << 8) | (rec[1] & 0xFF);
        if (dkLen > rec.length - 2) {
            return null;
        }
        String dk = new String(rec, 2, dkLen, java.nio.charset.StandardCharsets.UTF_8);
        byte[] enc = new byte[rec.length - 2 - dkLen];
        System.arraycopy(rec, 2 + dkLen, enc, 0, enc.length);
        return new StoredEvent(dk, enc);
    }
}
