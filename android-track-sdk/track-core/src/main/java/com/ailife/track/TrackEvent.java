package com.ailife.track;

import java.util.Map;

/** One track event (api model; see contracts/api.md TrackEvent). */
public class TrackEvent {
    /** Client-generated UUID for the raw log. */
    public String id;
    /** Idempotency key for dedup (90s client window, 24h hub window). */
    public String dedupKey;
    /** Business event id, e.g. "app_launch". */
    public String eventId;
    /** Client timestamp (ms). */
    public long eventTime;
    /** Last send timestamp (ms), 0 before first send. */
    public long sentTime;
    /** App version snapshot. */
    public String appVer;
    /** OS version snapshot. */
    public String osVer;
    /** Device model snapshot. */
    public String device;
    /** Business properties (values stringified, truncated to 1KB by codec). */
    public Map<String, Object> properties;

    public static TrackEvent of(String eventId, Map<String, Object> properties) {
        TrackEvent e = new TrackEvent();
        e.eventId = eventId;
        e.properties = properties;
        return e;
    }
}
