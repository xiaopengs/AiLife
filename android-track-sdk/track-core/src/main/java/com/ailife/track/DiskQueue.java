package com.ailife.track;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal-style durable FIFO of byte[] records (persist-before-transmit,
 * survives process kill, system freeze and reboot). Records are
 * [4B big-endian length][payload]. Acknowledgement compaction first writes a
 * fully synced replacement journal, then atomically renames it into place.
 */
public final class DiskQueue implements Closeable {
    private static final int MAX_FRAME = 8 * 1024 * 1024;
    private static final String CANONICAL_SUFFIX = "current.log";
    private final File dir;
    private final long maxBytes;
    private final TimeSource time;
    private final Logger log;
    private final String prefix;
    private long bytes = 0;
    private int lastOfferEvictedCount = 0;
    private BufferedOutputStream headOut;
    private File headFile;

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

    private File canonicalFile() {
        return new File(dir, prefix + CANONICAL_SUFFIX);
    }

    /**
     * Once a canonical journal exists it is the authoritative generation.
     * Older day partitions are retained only until the successful atomic
     * replacement and are never read again, which prevents partial multi-file
     * rewrites from losing or duplicating acknowledged records.
     */
    private File[] listPartitions() {
        File canonical = canonicalFile();
        if (canonical.isFile()) {
            return new File[] {canonical};
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        java.util.Arrays.sort(files);
        List<File> kept = new ArrayList<File>();
        for (File f : files) {
            String name = f.getName();
            if (f.isFile() && name.startsWith(prefix) && name.endsWith(".log")) {
                kept.add(f);
            }
        }
        return kept.toArray(new File[0]);
    }

    private File[] listAllPartitionFiles() {
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        List<File> kept = new ArrayList<File>();
        for (File f : files) {
            String name = f.getName();
            if (f.isFile() && name.startsWith(prefix) && name.endsWith(".log")) {
                kept.add(f);
            }
        }
        return kept.toArray(new File[0]);
    }

    /**
     * Append one record. At quota, evict oldest records before preserving the
     * new event. A record larger than the entire quota is rejected unchanged.
     */
    public synchronized boolean offer(byte[] record) throws IOException {
        lastOfferEvictedCount = 0;
        if (record == null || record.length == 0 || record.length > MAX_FRAME - 4) {
            return false;
        }
        int frame = 4 + record.length;
        if (frame > maxBytes) {
            return false;
        }
        if (bytes + frame > maxBytes) {
            List<byte[]> all = peek(peekCountTotal());
            List<byte[]> kept = new ArrayList<byte[]>(all);
            long keptBytes = frameBytes(kept);
            int evicted = 0;
            while (keptBytes + frame > maxBytes && !kept.isEmpty()) {
                byte[] oldest = kept.remove(0);
                keptBytes -= 4L + oldest.length;
                evicted++;
            }
            if (keptBytes + frame > maxBytes || !replaceSnapshot(kept)) {
                return false;
            }
            lastOfferEvictedCount = evicted;
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

    /** Number of records evicted by the immediately preceding offer. */
    public synchronized int lastOfferEvictedCount() {
        return lastOfferEvictedCount;
    }

    private void openHead() throws IOException {
        File canonical = canonicalFile();
        if (canonical.isFile()) {
            headFile = canonical;
        } else {
            headFile = new File(dir, prefix + (time.nowMs() / 86400000L) + ".log");
        }
        headOut = new BufferedOutputStream(new FileOutputStream(headFile, true), 8192);
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
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
        List<byte[]> out = new ArrayList<byte[]>(Math.min(Math.max(limit, 0), 256));
        if (limit <= 0) {
            return out;
        }
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
                    break;
                }
                if (len <= 4 || len > MAX_FRAME) {
                    log.w("DiskQueue", "corrupt frame len=" + len + " in " + f.getName());
                    truncate = true;
                    truncateAt = in.count - 4;
                    break;
                }
                int payloadLen = len - 4;
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
            try {
                if (f.equals(headFile)) {
                    closeHead();
                }
                FileOutputStream fos = new FileOutputStream(f, true);
                try {
                    fos.getChannel().truncate(keepBytes);
                } finally {
                    fos.close();
                }
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
        List<byte[]> kept = new ArrayList<byte[]>();
        for (int i = n; i < all.size(); i++) {
            kept.add(all.get(i));
        }
        replaceSnapshot(kept);
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
        if (removed > 0) {
            replaceSnapshot(kept);
        }
    }

    /**
     * Atomically install an authoritative compacted snapshot. Old journals are
     * not touched until the temp file is completely written and fsynced. On a
     * write or rename failure the old generation remains authoritative.
     */
    private boolean replaceSnapshot(List<byte[]> records) {
        File target = canonicalFile();
        File temp = new File(dir, target.getName() + ".tmp-" + System.nanoTime());
        try {
            writeSnapshot(temp, records);
        } catch (IOException e) {
            log.e("DiskQueue", "snapshot write failed; keeping original journal", e);
            if (temp.exists() && !temp.delete()) {
                log.w("DiskQueue", "temp cleanup failed " + temp);
            }
            return false;
        }
        closeHead();
        // rename within one directory is an atomic replace on supported local
        // filesystems. Failure leaves target and its old data untouched.
        if (!temp.renameTo(target)) {
            log.w("DiskQueue", "atomic journal replace failed; keeping original journal");
            if (temp.exists() && !temp.delete()) {
                log.w("DiskQueue", "temp cleanup failed " + temp);
            }
            return false;
        }
        for (File old : listAllPartitionFiles()) {
            if (!old.equals(target) && !old.delete()) {
                log.w("DiskQueue", "stale journal cleanup failed " + old);
            }
        }
        bytes = target.length();
        return true;
    }

    private void writeSnapshot(File temp, List<byte[]> records) throws IOException {
        FileOutputStream fos = new FileOutputStream(temp, false);
        try {
            BufferedOutputStream out = new BufferedOutputStream(fos, 8192);
            for (byte[] rec : records) {
                if (rec == null || rec.length == 0 || rec.length > MAX_FRAME - 4) {
                    throw new IOException("invalid record in snapshot");
                }
                writeInt(out, 4 + rec.length);
                out.write(rec);
            }
            out.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
    }

    private static long frameBytes(List<byte[]> records) {
        long total = 0;
        for (byte[] record : records) {
            total += 4L + record.length;
        }
        return total;
    }

    private static String key(byte[] rec) {
        return Signature.sha256Hex(rec) + ":" + rec.length;
    }

    private int peekCountTotal() {
        int n = 0;
        for (File f : listPartitions()) {
            long sz = f.length();
            n += (int) Math.max(1, sz / 8);
        }
        return n + 16;
    }

    private void closeHead() {
        closeQuietly(headOut);
        headOut = null;
        headFile = null;
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
