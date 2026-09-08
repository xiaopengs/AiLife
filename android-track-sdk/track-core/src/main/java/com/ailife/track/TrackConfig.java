package com.ailife.track;

import java.util.Map;

/**
 * SDK configuration (contracts/api.md TrackConfig). Builder validates every
 * field; out-of-range values fall back to defaults and are counted (E8),
 * never throwing on bad business input.
 */
public final class TrackConfig {
    public static final String DEFAULT_AUTHORITY = "com.ailife.dataplatform.track";
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 5000L;
    public static final long MIN_FLUSH_INTERVAL_MS = 1000L;
    public static final long MAX_FLUSH_INTERVAL_MS = 10000L;
    public static final int DEFAULT_BATCH_COUNT = 50;
    public static final int MIN_BATCH_COUNT = 10;
    public static final int MAX_BATCH_COUNT = 200;
    public static final int DEFAULT_BATCH_SIZE_BYTES = 256 * 1024;
    public static final long DEFAULT_MAX_QUEUE_BYTES = 20L * 1024 * 1024;
    public static final long MIN_MAX_QUEUE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_MAX_QUEUE_BYTES = 50L * 1024 * 1024;
    public static final int DEFAULT_EVENT_TTL_DAYS = 3;
    public static final int MIN_EVENT_TTL_DAYS = 1;
    public static final int MAX_EVENT_TTL_DAYS = 7;
    public static final long DEFAULT_DEDUP_WINDOW_MS = 90000L;
    public static final int DEFAULT_RATE_LIMIT = 1000;
    public static final int DEFAULT_RATE_DEPTH = 1000;

    /** Transport selection: ContentProvider (default) or AIDL. */
    public enum ChannelMode { PROVIDER, AIDL }

    public final String appKey;
    public final String providerAuthority;
    public final ChannelMode channelMode;
    public final long flushIntervalMs;
    public final int batchCount;
    public final int batchSizeBytes;
    public final long maxQueueBytes;
    public final int eventTtlDays;
    public final long dedupWindowMs;
    public final boolean enableAutoTrack;
    public final boolean encryptPayload;
    public final int rateLimitPerSec;
    public final int rateDepth;
    public final Map<String, Object> globalProps;
    /** Non-fatal config corrections applied during validation (E8 diagnostics). */
    public final String validationNotes;

    private TrackConfig(Builder b) {
        this.appKey = b.appKey;
        this.providerAuthority = b.providerAuthority;
        this.channelMode = b.channelMode;
        this.flushIntervalMs = b.flushIntervalMs;
        this.batchCount = b.batchCount;
        this.batchSizeBytes = b.batchSizeBytes;
        this.maxQueueBytes = b.maxQueueBytes;
        this.eventTtlDays = b.eventTtlDays;
        this.dedupWindowMs = b.dedupWindowMs;
        this.enableAutoTrack = b.enableAutoTrack;
        this.encryptPayload = b.encryptPayload;
        this.rateLimitPerSec = b.rateLimitPerSec;
        this.rateDepth = b.rateDepth;
        this.globalProps = b.globalProps;
        this.validationNotes = b.notes.toString();
    }

    public static Builder builder(String appKey) {
        return new Builder(appKey);
    }

    public static final class Builder {
        private final String appKey;
        private final StringBuilder notes = new StringBuilder();
        private String providerAuthority = DEFAULT_AUTHORITY;
        private ChannelMode channelMode = ChannelMode.PROVIDER;
        private long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        private int batchCount = DEFAULT_BATCH_COUNT;
        private int batchSizeBytes = DEFAULT_BATCH_SIZE_BYTES;
        private long maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES;
        private int eventTtlDays = DEFAULT_EVENT_TTL_DAYS;
        private long dedupWindowMs = DEFAULT_DEDUP_WINDOW_MS;
        private boolean enableAutoTrack = true;
        private boolean encryptPayload = true;
        private int rateLimitPerSec = DEFAULT_RATE_LIMIT;
        private int rateDepth = DEFAULT_RATE_DEPTH;
        private Map<String, Object> globalProps;

        public Builder(String appKey) {
            if (appKey == null || appKey.trim().isEmpty()) {
                this.appKey = "default-appkey";
                this.notes.append("appKey empty -> default-appkey; ");
            } else {
                this.appKey = appKey.trim();
            }
        }

        public Builder providerAuthority(String v) {
            this.providerAuthority = v == null || v.trim().isEmpty()
                    ? DEFAULT_AUTHORITY : v.trim();
            return this;
        }

        public Builder channelMode(ChannelMode v) {
            this.channelMode = v == null ? ChannelMode.PROVIDER : v;
            return this;
        }

        public Builder flushIntervalMs(long v) {
            if (v < MIN_FLUSH_INTERVAL_MS || v > MAX_FLUSH_INTERVAL_MS) {
                notes.append("flushIntervalMs ").append(v).append(" out of range -> default; ");
                this.flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
            } else {
                this.flushIntervalMs = v;
            }
            return this;
        }

        public Builder batchCount(int v) {
            if (v < MIN_BATCH_COUNT || v > MAX_BATCH_COUNT) {
                notes.append("batchCount ").append(v).append(" out of range -> default; ");
                this.batchCount = DEFAULT_BATCH_COUNT;
            } else {
                this.batchCount = v;
            }
            return this;
        }

        public Builder batchSizeBytes(int v) {
            if (v <= 0 || v > DEFAULT_BATCH_SIZE_BYTES) {
                notes.append("batchSizeBytes ").append(v).append(" out of range -> default; ");
                this.batchSizeBytes = DEFAULT_BATCH_SIZE_BYTES;
            } else {
                this.batchSizeBytes = v;
            }
            return this;
        }

        public Builder maxQueueBytes(long v) {
            if (v < MIN_MAX_QUEUE_BYTES || v > MAX_MAX_QUEUE_BYTES) {
                notes.append("maxQueueBytes ").append(v).append(" out of range -> default; ");
                this.maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES;
            } else {
                this.maxQueueBytes = v;
            }
            return this;
        }

        public Builder eventTtlDays(int v) {
            if (v < MIN_EVENT_TTL_DAYS || v > MAX_EVENT_TTL_DAYS) {
                notes.append("eventTtlDays ").append(v).append(" out of range -> default; ");
                this.eventTtlDays = DEFAULT_EVENT_TTL_DAYS;
            } else {
                this.eventTtlDays = v;
            }
            return this;
        }

        public Builder dedupWindowMs(long v) {
            if (v <= 0 || v > 24L * 3600 * 1000) {
                notes.append("dedupWindowMs ").append(v).append(" out of range -> default; ");
                this.dedupWindowMs = DEFAULT_DEDUP_WINDOW_MS;
            } else {
                this.dedupWindowMs = v;
            }
            return this;
        }

        public Builder enableAutoTrack(boolean v) {
            this.enableAutoTrack = v;
            return this;
        }

        public Builder encryptPayload(boolean v) {
            this.encryptPayload = v;
            return this;
        }

        public Builder rateLimit(int perSec, int depth) {
            if (perSec < 100 || perSec > 2000) {
                notes.append("rateLimit ").append(perSec).append(" out of range -> default; ");
                this.rateLimitPerSec = DEFAULT_RATE_LIMIT;
            } else {
                this.rateLimitPerSec = perSec;
            }
            if (depth < 100 || depth > 2000) {
                notes.append("rateDepth ").append(depth).append(" out of range -> default; ");
                this.rateDepth = DEFAULT_RATE_DEPTH;
            } else {
                this.rateDepth = depth;
            }
            return this;
        }

        public Builder globalProps(Map<String, Object> v) {
            this.globalProps = v;
            return this;
        }

        public TrackConfig build() {
            return new TrackConfig(this);
        }
    }
}
