package com.ailife.track.aidl;

/**
 * Async result callback for ITrackService. Runs on a Binder pool thread in
 * the client process; the client routes the code into ChannelCore.
 */
interface ITrackCallback {
    /**
     * @param code  1 SUCCEEDED / 2 THROTTLED / 3 RETRY_LATER / 4 INVALID
     * @param detail optional diagnostic (quarantine reason etc.)
     */
    oneway void onResult(int code, String detail);

    /** Reply to queryEvents: one CSV row per event (ts,eventId,dedupKey). */
    oneway void onQueryResult(in String[] rows);
}
