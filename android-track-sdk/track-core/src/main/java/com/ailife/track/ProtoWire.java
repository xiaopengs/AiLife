package com.ailife.track;

/**
 * Minimal protocol-buffers wire-format writer/reader (proto2/proto3 wire
 * compatible) used by EventPb. Wire-compatible with the schema shipped in
 * proto/track.proto so protobuf-javalite on a real device decodes the same
 * bytes. Only the field types needed by the schema are implemented:
 * varint (int32/int64) and length-delimited (string/bytes/sub-message).
 */
public final class ProtoWire {
    public static final int WT_VARINT = 0;
    public static final int WT_LEN = 2;

    private ProtoWire() { }

    public static void writeVarintField(DataOutput out, int fieldNumber, long value) {
        writeTag(out, fieldNumber, WT_VARINT);
        writeRawVarint(out, value);
    }

    public static void writeLenField(DataOutput out, int fieldNumber, byte[] payload) {
        writeTag(out, fieldNumber, WT_LEN);
        writeRawVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    public static void writeTag(DataOutput out, int fieldNumber, int wireType) {
        writeRawVarint(out, (((long) fieldNumber) << 3) | wireType);
    }

    public static void writeRawVarint(DataOutput out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0L) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    /** Streaming reader over one encoded message. */
    public static final class Reader {
        private final byte[] buf;
        private int pos;
        private int limit;
        private int fieldNumber;
        private int wireType;
        private long varint;
        private byte[] lenValue;

        public Reader(byte[] buf, int offset, int length) {
            this.buf = buf;
            this.pos = offset;
            this.limit = offset + length;
        }

        public boolean next() {
            if (pos >= limit) {
                return false;
            }
            long tag = readRawVarint();
            if (tag < 0) {
                throw new IllegalArgumentException("corrupt tag at " + pos);
            }
            this.fieldNumber = (int) (tag >>> 3);
            this.wireType = (int) (tag & 0x7);
            if (wireType == WT_VARINT) {
                this.varint = readRawVarint();
                this.lenValue = null;
            } else if (wireType == WT_LEN) {
                long len = readRawVarint();
                if (len < 0 || pos + len > limit) {
                    throw new IllegalArgumentException("corrupt length " + len + " at " + pos);
                }
                this.lenValue = new byte[(int) len];
                System.arraycopy(buf, pos, lenValue, 0, (int) len);
                pos += (int) len;
                this.varint = 0;
            } else {
                throw new IllegalArgumentException("unsupported wire type " + wireType);
            }
            return true;
        }

        public int fieldNumber() {
            return fieldNumber;
        }

        public long varint() {
            if (wireType != WT_VARINT) {
                throw new IllegalStateException("field " + fieldNumber + " is not varint");
            }
            return varint;
        }

        public byte[] bytes() {
            if (wireType != WT_LEN) {
                throw new IllegalStateException("field " + fieldNumber + " is not length-delimited");
            }
            return lenValue;
        }

        public String string() {
            return new String(bytes(),java.nio.charset.StandardCharsets.UTF_8);
        }

        private long readRawVarint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= limit) {
                    return -1;
                }
                byte b = buf[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            return -1;
        }
    }

    /** Tiny output buffer replacement for java.io.ByteArrayOutputStream with direct access. */
    public static final class DataOutput {
        private byte[] buf = new byte[256];
        private int len = 0;

        public void write(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }

        public void write(byte[] src, int off, int count) {
            ensure(count);
            System.arraycopy(src, off, buf, len, count);
            len += count;
        }

        private void ensure(int n) {
            if (len + n > buf.length) {
                int cap = buf.length;
                while (cap < len + n) {
                    cap <<= 1;
                }
                byte[] nb = new byte[cap];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
        }

        public byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
