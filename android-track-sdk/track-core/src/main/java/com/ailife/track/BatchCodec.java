package com.ailife.track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encodes/decodes track batches in protobuf wire format (schema of
 * proto/track.proto, message EventBatch/Event) and applies gzip framing.
 * Wire-compatible with protobuf-javalite generated code.
 *
 * Event fields (proto): 1 id(str) 2 dedup_key(str) 3 event_id(str)
 * 4 event_time(int64) 5 sent_time(int64) 6 sdk_ver(str) 7 app_ver(str)
 * 8 os_ver(str) 9 device(str) 10 props(map<string,string>) 11 app_key(str).
 */
public final class BatchCodec {
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    public static final int MAX_EVENT_BYTES = 1024 * 1024;
    public static final int MAX_PROP_LEN = 1024;

    private BatchCodec() { }

    /** Encode one event to protobuf bytes (props values truncated to 1KB). */
    public static byte[] encodeEvent(TrackEvent e, String sdkVer, String appVer,
                                     String osVer, String device) {
        ProtoWire.DataOutput out = new ProtoWire.DataOutput();
        if (e == null) {
            return out.toByteArray();
        }
        if (e.id != null) {
            ProtoWire.writeLenField(out, 1, utf8(e.id));
        }
        if (e.dedupKey != null) {
            ProtoWire.writeLenField(out, 2, utf8(e.dedupKey));
        }
        if (e.eventId != null) {
            ProtoWire.writeLenField(out, 3, utf8(e.eventId));
        }
        ProtoWire.writeVarintField(out, 4, e.eventTime);
        ProtoWire.writeVarintField(out, 5, e.sentTime);
        if (sdkVer != null) {
            ProtoWire.writeLenField(out, 6, utf8(sdkVer));
        }
        if (appVer != null) {
            ProtoWire.writeLenField(out, 7, utf8(appVer));
        }
        if (osVer != null) {
            ProtoWire.writeLenField(out, 8, utf8(osVer));
        }
        if (device != null) {
            ProtoWire.writeLenField(out, 9, utf8(device));
        }
        writeProperties(out, e.properties);
        if (e.appKey != null) {
            ProtoWire.writeLenField(out, 11, utf8(e.appKey));
        }
        return out.toByteArray();
    }

    /** Encode a batch of already-encoded events into an EventBatch message. */
    public static byte[] encodeBatch(List<byte[]> encodedEvents) {
        ProtoWire.DataOutput out = new ProtoWire.DataOutput();
        for (byte[] ev : encodedEvents) {
            ProtoWire.writeLenField(out, 1, ev);
        }
        return out.toByteArray();
    }

    /** gzip the batch (batch wire format = gzip(proto bytes)). */
    public static byte[] gzipBatch(byte[] protoBatch) throws IOException {
        return Gzip.compress(protoBatch);
    }

    public static byte[] decodeGzipBatch(byte[] gzip) throws IOException {
        return Gzip.decompress(gzip);
    }

    /** Decode an EventBatch message back into raw event byte arrays. */
    public static List<byte[]> decodeBatch(byte[] protoBatch) {
        List<byte[]> events = new ArrayList<byte[]>();
        ProtoWire.Reader r = new ProtoWire.Reader(protoBatch, 0, protoBatch.length);
        while (r.next()) {
            if (r.fieldNumber() == 1) {
                events.add(r.bytes());
            }
        }
        return events;
    }

    /** Decode one event message back into a TrackEvent (props as strings). */
    public static TrackEvent decodeEvent(byte[] ev) {
        TrackEvent e = new TrackEvent();
        ProtoWire.Reader r = new ProtoWire.Reader(ev, 0, ev.length);
        while (r.next()) {
            switch (r.fieldNumber()) {
                case 1: e.id = r.string(); break;
                case 2: e.dedupKey = r.string(); break;
                case 3: e.eventId = r.string(); break;
                case 4: e.eventTime = r.varint(); break;
                case 5: e.sentTime = r.varint(); break;
                case 6: e.sdkVer = r.string(); break;
                case 7: e.appVer = r.string(); break;
                case 8: e.osVer = r.string(); break;
                case 9: e.device = r.string(); break;
                case 10:
                    parseProp(e, r.bytes());
                    break;
                case 11: e.appKey = r.string(); break;
                default:
                    break;
            }
        }
        return e;
    }

    private static void parseProp(TrackEvent e, byte[] kv) {
        String k = null;
        String v = null;
        ProtoWire.Reader r = new ProtoWire.Reader(kv, 0, kv.length);
        while (r.next()) {
            if (r.fieldNumber() == 1) {
                k = r.string();
            } else if (r.fieldNumber() == 2) {
                v = r.string();
            }
        }
        if (k != null && v != null) {
            if (e.properties == null) {
                e.properties = new java.util.LinkedHashMap<String, Object>();
            }
            e.properties.put(k, v);
        }
    }

    /**
     * Property values originate in application code. A malformed map entry or
     * an application's throwing {@code toString()} must not escape a public
     * tracking call; only that property is skipped.
     */
    private static void writeProperties(ProtoWire.DataOutput out, Map<String, Object> properties) {
        if (properties == null) {
            return;
        }
        try {
            for (Map.Entry<String, Object> en : properties.entrySet()) {
                try {
                    if (en == null) {
                        continue;
                    }
                    String k = en.getKey();
                    if (k == null) {
                        continue;
                    }
                    String v = String.valueOf(en.getValue());
                    if (v == null) {
                        continue;
                    }
                    if (v.length() > MAX_PROP_LEN) {
                        v = v.substring(0, MAX_PROP_LEN);
                    }
                    ProtoWire.DataOutput kv = new ProtoWire.DataOutput();
                    ProtoWire.writeLenField(kv, 1, utf8(k));
                    ProtoWire.writeLenField(kv, 2, utf8(v));
                    ProtoWire.writeLenField(out, 10, kv.toByteArray());
                } catch (RuntimeException ignored) {
                    // Isolate a hostile entry/value and retain the event.
                }
            }
        } catch (RuntimeException ignored) {
            // A concurrently-mutated or otherwise hostile map is optional.
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
