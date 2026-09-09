package com.ailife.track.aidl;

import com.ailife.track.aidl.ITrackCallback;

/**
 * AIDL transport interface (business process -> data platform process).
 * Every batch carries the sender's app key and encryption state so the hub
 * verifies and decodes exactly the same wire format as the ContentProvider.
 */
interface ITrackService {

    /**
     * Send one gzip-compressed batch.
     * @param batchId content hash id (client diagnostics)
     * @param version protocol version; currently 1
     * @param appKey sender application key used for HMAC and optional AES key
     * @param encrypted true iff the bytes inside gzip are AES-GCM wrapped
     * @param blob gzip(proto EventBatch) or gzip(AES-GCM(proto EventBatch))
     * @param sig HMAC-SHA256(appKey, ts + "." + sha256(blob))
     * @param ts client epoch ms (anti-replay window +/-5 minutes)
     */
    oneway void sendBatch(String batchId, int version, String appKey, boolean encrypted,
            in byte[] blob, String sig, long ts, ITrackCallback callback);

    /** Status probe: returns [state, pending, health_x100, degrade_level]. */
    int[] getStatus();

    /** Read-only authorized query (dashboards/agents). */
    oneway void queryEvents(long fromTs, long toTs, String eventIdLike,
            int limit, int offset, ITrackCallback callback);
}
