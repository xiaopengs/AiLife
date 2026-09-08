package com.ailife.track.aidl;

import com.ailife.track.aidl.ITrackCallback;

/**
 * AIDL transport interface (business process -> data platform process).
 * Alternative to the ContentProvider channel; selected via
 * TrackConfig.ChannelMode.AIDL. One-way oneway calls keep the client from
 * blocking; the callback delivers the four-value result code.
 */
interface ITrackService {

    /**
     * Send one gzip-compressed batch.
     * @param batchId  content hash id (client diagnostics)
     * @param blob     gzip(proto EventBatch), optionally AES-GCM wrapped
     * @param sig      HMAC-SHA256(appKey, ts + "." + sha256(blob))
     * @param ts       client epoch ms (anti-replay window ±5min)
     * @param callback async result callback; code is one of
     *                 1 SUCCEEDED / 2 THROTTLED / 3 RETRY_LATER / 4 INVALID
     */
    oneway void sendBatch(String batchId, in byte[] blob, String sig, long ts,
            ITrackCallback callback);

    /** Status probe: returns [state, pending, health_x100, degrade_level]. */
    int[] getStatus();

    /** Read-only authorized query (dashboards/agents). */
    oneway void queryEvents(long fromTs, long toTs, String eventIdLike,
            int limit, int offset, ITrackCallback callback);
}
