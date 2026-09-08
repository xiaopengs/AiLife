package com.ailife.track;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal-style durable FIFO of byte[] records (persist-before-transmit,
 * survives process kill, system freeze and reboot). One append-only journal
 * file per day partition; records are [4B big-endian length][payload].
 * Reads stream from disk so memory stays bounded even with a large backlog.
 * Torn tail frames (kill mid-write) are truncated to the last intact frame.
 */
public final class DiskQueue implements Closeable {
    private static final int MAX_FRAME = 8 * 1024 * 1024;
    private final File dir;
    private final long maxBytes;
    private final TimeSource time;
    private final Logger log;
    private final String prefix;
    private long bytes = 0;
    private BufferedOutputStream headOut;

    public DiskQueue(File dir, long maxBytes, TimeSource time, Logger log, String prefix) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.time = time;
        this.log = log;
        this.prefix = prefix;
        if (!dir.exists() && !dir.mkdirs()) {
            log.w("DiskQueue", "mkdirs failed: " + dir);
        }
        for (File f : listPartitions()) {
            bytes += f.length();
        }
    }

    private File[] listPartitions() {
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        java.util.Arrays.sort(files);
        List<File> kept = new ArrayList<File>();
        for (File f : files) {
            if (f.getName().startsWith(prefix)) {
                kept.add(f);
            }
        }
        return kept.toArray(new File[0]);
    }

    /** Append one record; returns false when quota exhausted (record NOT kept). */
    public synchronized boolean offer(byte[] record) throws IOException {
        int frame = 4 + record.length;
        if (bytes + frame > maxBytes) {
            return false;
        }
        if (headOut == null) {
            openHead();
        }
        writeInt(headOut, frame);
        headOut.write(record, 0, record.length);
        headOut.flush();
        bytes += frame;
        return true;
    }

    private void openHead() throws IOException {
        String name = prefix + (time.nowMs() / 86400000L) + ".log";
        for (File f : listPartitions()) {
            if (f.getName().equals(name)) {
                headOut = new BufferedOutputStream(new FileOutputStream(f, true), 8192);
                return;
            }
        }
        headOut = new BufferedOutputStream(
                new FileOutputStream(new File(dir, name), true), 8192);
    }

    private static void writeInt(BufferedOutputStream out, int v) throws IOException {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    /** Counts bytes actually consumed from the underlying file. */
    private static final class CountingIn extends InputStream {
        private final InputStream in;
        long count = 0;
        CountingIn(InputStream in) { this.in = in; }
        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    /**
     * Snapshot up to limit records (oldest first) without removing them.
     * Corrupt or torn tail frames are truncated away.
     */
    public synchronized List<byte[]> peek(int limit) {
        List<byte[]> out = new ArrayList<byte[]>(Math.min(limit, 256));
        for (File f : listPartitions()) {
            if (out.size() >= limit) {
                break;
            }
            readPartition(f, out, limit);
        }
        return out;
    }

    private void readPartition(File f, List<byte[]> out, int limit) {
        CountingIn in = null;
        boolean truncate = false;
        long truncateAt = 0;
        try {
            in = new CountingIn(new BufferedInputStream(new FileInputStream(f), 16384));
            while (out.size() < limit) {
                int len = readInt(in);
                if (len < 0) {
                    break; // clean EOF
                }
                if (len <= 4 || len > MAX_FRAME) { // min legal frame: 4B hdr + 1B payload
                    log.w("DiskQueue", "corrupt frame len=" + len + " in " + f.getName());
                    truncate = true;
                    truncateAt = in.count - 4;
                    break;
                }
                int payloadLen = len - 4; // frame length includes the 4B header
                byte[] rec = new byte[payloadLen];
                int done = 0;
                boolean torn = false;
                while (done < payloadLen) {
                    int n = in.read(rec, done, payloadLen - done);
                    if (n < 0) {
                        torn = true;
                        break;
                    }
                    done += n;
                }
                if (torn) {
                    log.w("DiskQueue", "torn tail in " + f.getName());
                    truncate = true;
                    truncateAt = in.count - 4 - done;
                    break;
                }
                out.add(rec);
            }
        } catch (IOException e) {
            log.e("DiskQueue", "read failed " + f.getName(), e);
        } finally {
            closeQuietly(in);
        }
        if (truncate && truncateAt >= 0) {
            truncate(f, truncateAt);
        }
    }

    private void truncate(File f, long keepBytes) {
        long sz = f.length();
        if (keepBytes < sz) {
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.getChannel().truncate(keepBytes);
                bytes -= (sz - keepBytes);
                if (bytes < 0) {
                    bytes = 0;
                }
            } catch (IOException e) {
                log.e("DiskQueue", "truncate failed " + f.getName(), e);
            }
        }
    }

    private static int readInt(CountingIn in) throws IOException {
        int ch1 = in.read();
        int ch2 = in.read();
        int ch3 = in.read();
        int ch4 = in.read();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            return -1;
        }
        return (ch1 << 24) | (ch2 << 16) | (ch3 << 8) | ch4;
    }

    /** Remove the first n records (after successful hub acceptance). */
    public synchronized void removeFirst(int n) {
        if (n <= 0) {
            return;
        }
        List<byte[]> all = peek(peekCountTotal());
        Map<String, Integer> skip = new LinkedHashMap<String, Integer>();
        for (byte[] rec : peek(n)) {
            String k = key(rec);
            Integer c = skip.get(k);
            skip.put(k, c == null ? 1 : c + 1);
        }
        removeByHashInner(all, skip);
    }

    /** Remove records matching the given list by content hash. */
    public synchronized void removeFirstByHash(List<byte[]> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<byte[]> all = peek(peekCountTotal());
        Map<String, Integer> skip = new LinkedHashMap<String, Integer>();
        for (byte[] rec : records) {
            String k = key(rec);
            Integer c = skip.get(k);
            skip.put(k, c == null ? 1 : c + 1);
        }
        removeByHashInner(all, skip);
    }

    private void removeByHashInner(List<byte[]> all, Map<String, Integer> skip) {
        List<byte[]> kept = new ArrayList<byte[]>();
        int removed = 0;
        for (byte[] rec : all) {
            String k = key(rec);
            Integer c = skip.get(k);
            if (c != null && c > 0) {
                skip.put(k, c - 1);
                removed++;
            } else {
                kept.add(rec);
            }
        }
        if (removed == 0) {
            return;
        }
        closeHead();
        for (File f : listPartitions()) {
            if (!f.delete()) {
                log.w("DiskQueue", "delete failed " + f);
            }
        }
        bytes = 0;
        try {
            for (byte[] rec : kept) {
                offer(rec);
            }
        } catch (IOException e) {
            log.e("DiskQueue", "rewrite failed; some records may be lost", e);
        }
    }

    private static String key(byte[] rec) {
        return Signature.sha256Hex(rec) + ":" + rec.length;
    }

    private int peekCountTotal() {
        int n = 0;
        for (File f : listPartitions()) {
            long sz = f.length();
            // records average >= 8B frame; count conservatively via peek later
            n += (int) Math.max(1, sz / 8);
        }
        return n + 16;
    }

    private void closeHead() {
        closeQuietly(headOut);
        headOut = null;
    }

    public synchronized long sizeBytes() {
        return bytes;
    }

    public synchronized int sizeCount() {
        return peek(peekCountTotal()).size();
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
                // best effort
            }
        }
    }

    @Override
    public synchronized void close() {
        closeHead();
    }
}
