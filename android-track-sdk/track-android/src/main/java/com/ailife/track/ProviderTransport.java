package com.ailife.track;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

/**
 * Transport implementation over the hub ContentProvider.
 *
 * <p>The provider contract is a synchronous {@link ContentResolver#insert}
 * on {@code content://authority/events?ver=1}. Its returned URI has the
 * result code as its last path segment.</p>
 */
public final class ProviderTransport implements Transport {
    static final long CALL_TIMEOUT_MS = 8000L;

    private final Context context;
    private final String authority;
    private final String appKey;
    private final boolean encrypted;

    /** Retained for source compatibility; SDK configuration defaults to encryption. */
    public ProviderTransport(Context context, String authority, String appKey) {
        this(context, authority, appKey, true);
    }

    public ProviderTransport(Context context, String authority, String appKey,
                             boolean encrypted) {
        this.context = context.getApplicationContext();
        this.authority = authority == null || authority.isEmpty()
                ? TrackConfig.DEFAULT_AUTHORITY : authority;
        this.appKey = appKey;
        this.encrypted = encrypted;
    }

    @Override
    public Result send(final String batchId, final byte[] gzipBatch,
                       final String signature, final long ts) {
        try {
            ContentValues values = new ContentValues();
            values.put("blob", gzipBatch);
            values.put("sig", signature);
            values.put("ts", Long.valueOf(ts));
            values.put("app_key", appKey);
            values.put("encrypted", Boolean.valueOf(encrypted));

            Uri response = context.getContentResolver().insert(eventsUri(), values);
            return parseResponse(response);
        } catch (SecurityException e) {
            return new Result(Code.DEAD_OBJECT, "provider permission denied");
        } catch (IllegalArgumentException e) {
            return new Result(Code.DEAD_OBJECT, "authority missing: " + authority);
        } catch (RuntimeException e) {
            // Binder/provider failures must retain the local batch for retry.
            return new Result(Code.DEAD_OBJECT, e.getClass().getSimpleName());
        }
    }

    Result parseResponse(Uri response) {
        if (response == null) {
            return new Result(Code.TIMEOUT, "provider returned null");
        }
        try {
            int wire = Integer.parseInt(response.getLastPathSegment());
            Code code = Code.fromWire(wire);
            if (code == null || wire > Code.RESULT_INVALID.wire) {
                return new Result(Code.DEAD_OBJECT, "unknown provider code " + wire);
            }
            return new Result(code, null);
        } catch (RuntimeException e) {
            return new Result(Code.DEAD_OBJECT, "malformed provider response");
        }
    }

    Uri eventsUri() {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("events")
                .appendQueryParameter("ver",
                        String.valueOf(InboundBatchDecoder.PROTOCOL_VERSION))
                .build();
    }
}
