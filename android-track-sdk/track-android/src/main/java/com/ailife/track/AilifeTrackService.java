package com.ailife.track;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.ailife.track.aidl.ITrackCallback;
import com.ailife.track.aidl.ITrackService;

/**
 * Hub-side AIDL service (alternative entry point to AilifeTrackProvider).
 * Runs in the data-platform process; delegates into the same HubController
 * so both channels share ingestion/dedup/storage/health/report logic.
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
            public void sendBatch(String batchId, byte[] blob, String sig, long ts,
                                  ITrackCallback callback) {
                int code = Transport.Code.RESULT_INVALID.wire;
                String detail = null;
                try {
                    long now = System.currentTimeMillis();
                    if (blob == null || Math.abs(now - ts) > 5L * 60 * 1000) {
                        code = Transport.Code.RESULT_INVALID.wire;
                        detail = "ts window";
                    } else if (!Signature.safeEquals(sig,
                            Signature.signBatch(appKeyOf(), ts, blob))) {
                        code = Transport.Code.RESULT_INVALID.wire;
                        detail = "bad signature";
                    } else {
                        byte[] proto = Gzip.decompress(blob);
                        java.util.List<TrackEvent> events =
                                new java.util.ArrayList<TrackEvent>();
                        for (byte[] raw : BatchCodec.decodeBatch(proto)) {
                            events.add(BatchCodec.decodeEvent(raw));
                        }
                        code = hub.ingestBatch(events).wire;
                    }
                } catch (Exception e) {
                    detail = e.getClass().getSimpleName();
                }
                reply(callback, code, detail);
            }

            @Override
            public int[] getStatus() {
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
                java.util.List<TrackEvent> events =
                        hub.queryEvents(fromTs, toTs, eventIdLike, limit);
                String[] rows = new String[events.size()];
                for (int i = 0; i < events.size(); i++) {
                    TrackEvent e = events.get(i);
                    rows[i] = e.eventTime + "," + e.eventId + ","
                            + (e.dedupKey == null ? "" : e.dedupKey);
                }
                try {
                    callback.onQueryResult(rows);
                } catch (RemoteException ignore) {
                    // client died; nothing to do
                }
            }

            private void reply(ITrackCallback callback, int code, String detail) {
                if (callback != null) {
                    try {
                        callback.onResult(code, detail);
                    } catch (RemoteException ignore) {
                        // client died before result; client keeps batch and retries
                    }
                }
            }
        };
    }

    private String appKeyOf() {
        return "default-appkey";
    }

    @Override
    public void onDestroy() {
        if (hub != null) {
            hub.shutdown();
        }
        super.onDestroy();
    }
}
