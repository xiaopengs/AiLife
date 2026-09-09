package com.ailife.track;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.ailife.track.aidl.ITrackCallback;
import com.ailife.track.aidl.ITrackService;

/**
 * Hub-side AIDL service, sharing the same ingress validation and HubController
 * process singleton as {@link AilifeTrackProvider}.
 */
public class AilifeTrackService extends Service {

    private HubController hub;

    @Override
    public void onCreate() {
        super.onCreate();
        hub = AilifeTrackProvider.HubHolder.get(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new ITrackService.Stub() {
            @Override
            public void sendBatch(String batchId, int version, String appKey,
                                  boolean encrypted, byte[] blob, String sig, long ts,
                                  ITrackCallback callback) {
                Transport.Code result = Transport.Code.RESULT_INVALID;
                String detail;
                try {
                    InboundBatchDecoder.DecodedBatch decoded = InboundBatchDecoder.decode(
                            version, appKey, encrypted, blob, sig, ts,
                            System.currentTimeMillis());
                    if (!decoded.isValid()) {
                        detail = decoded.detail;
                    } else if (hub == null) {
                        detail = "hub unavailable";
                    } else {
                        result = hub.ingestBatch(decoded.events);
                        detail = null;
                    }
                } catch (RuntimeException e) {
                    detail = "ingest failure";
                }
                reply(callback, result.wire, detail);
            }

            @Override
            public int[] getStatus() {
                if (hub == null) {
                    return new int[] {0, 0, 0, 0};
                }
                java.util.Map<String, Object> row = hub.statusRow();
                return new int[] {
                        "DEGRADED".equals(row.get("state")) ? 2 : 1,
                        (int) ((Number) row.get("pending")).longValue(),
                        (int) Math.round(((Number) row.get("health")).doubleValue() * 100),
                        ((Number) row.get("degrade_level")).intValue()
                };
            }

            @Override
            public void queryEvents(long fromTs, long toTs, String eventIdLike,
                                    int limit, int offset, ITrackCallback callback) {
                java.util.List<TrackEvent> events = hub == null
                        ? java.util.Collections.<TrackEvent>emptyList()
                        : hub.queryEvents(fromTs, toTs, eventIdLike, limit);
                String[] rows = new String[events.size()];
                for (int i = 0; i < events.size(); i++) {
                    TrackEvent e = events.get(i);
                    rows[i] = e.eventTime + "," + e.eventId + ","
                            + (e.dedupKey == null ? "" : e.dedupKey);
                }
                try {
                    if (callback != null) {
                        callback.onQueryResult(rows);
                    }
                } catch (RemoteException ignore) {
                    // Client died; there is no local state to roll back.
                }
            }

            private void reply(ITrackCallback callback, int code, String detail) {
                if (callback != null) {
                    try {
                        callback.onResult(code, detail);
                    } catch (RemoteException ignore) {
                        // Client died before the result; it keeps and retries its batch.
                    }
                }
            }
        };
    }

    @Override
    public void onDestroy() {
        // The provider can outlive this service (or vice versa). Only explicit
        // process/test shutdown is allowed to close HubController resources.
        super.onDestroy();
    }
}
