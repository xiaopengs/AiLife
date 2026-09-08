package com.ailife.track;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * dedupKey window with LRU capacity bound. Used by the client (90s window)
 * to suppress accidental double sends and by the hub to make retries from
 * any client idempotent (at-least-once -> exactly-once effect).
 */
public final class Deduplicator {
    private final long windowMs;
    private final int capacity;
    private final Map<String, Long> seen = new HashMap<String, Long>();
    private final ArrayDeque<String> order = new ArrayDeque<String>();

    public Deduplicator(long windowMs, int capacity) {
        this.windowMs = windowMs;
        this.capacity = Math.max(16, capacity);
    }

    /** @return true if the key is new inside the window, false if duplicate. */
    public synchronized boolean isNew(String dedupKey, long nowMs) {
        if (dedupKey == null || dedupKey.isEmpty()) {
            return true;
        }
        Long prev = seen.get(dedupKey);
        if (prev != null && nowMs - prev < windowMs) {
            // refresh LRU position
            order.remove(dedupKey);
            order.addLast(dedupKey);
            return false;
        }
        if (order.size() >= capacity) {
            String evicted = order.pollFirst();
            if (evicted != null) {
                seen.remove(evicted);
            }
        }
        seen.put(dedupKey, nowMs);
        order.addLast(dedupKey);
        return true;
    }

    public synchronized int size() {
        return seen.size();
    }

    /** Drop entries older than the window (called opportunistically). */
    public synchronized void purge(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (nowMs - e.getValue() >= windowMs) {
                order.remove(e.getKey());
                it.remove();
            }
        }
    }
}
