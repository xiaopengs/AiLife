package com.ailife.track;

import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

/**
 * Transport implementation over the hub ContentProvider
 * (content://authority). Synchronous insert with an 8s budget mapped to
 * TIMEOUT; DeadObjectException maps to DEAD_OBJECT for ChannelCore.
 */
public final class ProviderTransport implements Transport {
    static final long CALL_TIMEOUT_MS = 8000L;

    private final Context context;
    private final String authority;
    private final String appKey;

    public ProviderTransport(Context context, String authority, String appKey) {
        this.context = context.getApplicationContext();
        this.authority = authority == null || authority.isEmpty()
                ? TrackConfig.DEFAULT_AUTHORITY : authority;
        this.appKey = appKey;
    }

    @Override
    public Result send(final String batchId, final byte[] gzipBatch,
                       final String signature, final long ts) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle in = new Bundle();
            in.putByteArray("blob", gzipBatch);
            in.putString("sig", signature);
            in.putLong("ts", ts);
            in.putString("app_key", appKey);
            Bundle out = resolver.call(authorityUri().toString(), "insert_events", null, in);
            if (out == null) {
                return new Result(Code.TIMEOUT, "provider returned null");
            }
            int wire = out.getInt("code", Code.RESULT_INVALID.wire);
            Code c = Code.fromWire(wire);
            return c == null
                    ? new Result(Code.RESULT_INVALID, "unknown code " + wire)
                    : new Result(c, out.getString("detail"));
        } catch (IllegalArgumentException e) {
            // unknown authority: hub not installed / wrong authority string
            return new Result(Code.DEAD_OBJECT, "authority missing: " + authority);
        } catch (Exception e) {
            // covers RemoteException/DeadObjectException thrown on a real device
            if (e instanceof RemoteException) {
                return new Result(Code.DEAD_OBJECT, "hub process gone: "
                        + e.getMessage());
            }
            return new Result(Code.DEAD_OBJECT, e.getClass().getSimpleName());
        }
    }

    Uri authorityUri() {
        return Uri.parse("content://" + authority);
    }
}
