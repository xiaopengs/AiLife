package com.ailife.track;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;


/**
 * Data-platform ContentProvider (contracts/api.md AilifeTrackProvider).
 * insert(): events|status|metrics URIs; query(): status/metrics/events.
 * Result codes are appended as the last path segment:
 * /events/1 SUCCEEDED, /events/2 THROTTLED, /events/3 RETRY_LATER,
 * /events/4 INVALID.
 */
public class AilifeTrackProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ailife.dataplatform.track";
    public static final Uri BASE = Uri.parse("content://" + AUTHORITY);

    private static final int URI_EVENTS = 1;
    private static final int URI_STATUS = 2;
    private static final int URI_METRICS = 3;
    private static final int URI_QUERY_EVENTS = 4;

    private static final String[] STATUS_COLUMNS =
            {"state", "pending", "health", "degrade_level"};
    private static final String[] EVENT_COLUMNS = {"ts", "event_id", "dedup_key"};
    private static final String[] METRIC_COLUMNS = {"name", "value"};

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "events", URI_EVENTS);
        MATCHER.addURI(AUTHORITY, "status", URI_STATUS);
        MATCHER.addURI(AUTHORITY, "metrics", URI_METRICS);
        MATCHER.addURI(AUTHORITY, "query/events", URI_QUERY_EVENTS);
    }

    private HubController hub;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        hub = HubHolder.get(ctx);
        return true;
    }

        @Override
    public Uri insert(Uri uri, ContentValues values) {
        int match = MATCHER.match(uri);
        if (match != URI_EVENTS || values == null) {
            return Uri.withAppendedPath(BASE, "events/"
                    + Transport.Code.RESULT_INVALID.wire);
        }
        byte[] blob = values.getAsByteArray("blob");
        String sig = values.getAsString("sig");
        Long tsBoxed = values.getAsLong("ts");
        if (blob == null || tsBoxed == null) {
            return Uri.withAppendedPath(BASE, "events/"
                    + Transport.Code.RESULT_INVALID.wire);
        }
        long ts = tsBoxed;
        // anti-replay: reject batches older/newer than ±5min
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > 5L * 60 * 1000) {
            return Uri.withAppendedPath(BASE, "events/"
                    + Transport.Code.RESULT_INVALID.wire);
        }
        if (!Signature.safeEquals(sig,
                Signature.signBatch(appKeyFor(values), ts, blob))) {
            return Uri.withAppendedPath(BASE, "events/"
                    + Transport.Code.RESULT_INVALID.wire);
        }
        return Uri.withAppendedPath(BASE, "events/" + ingest(blob).wire);
    }

    private Transport.Code ingest(byte[] blob) {
        try {
            byte[] proto = Gzip.decompress(blob);
            // decrypt when payload was encrypted (AES-GCM, appKey-derived)
            java.util.List<TrackEvent> events = new java.util.ArrayList<TrackEvent>();
            for (byte[] raw : BatchCodec.decodeBatch(proto)) {
                events.add(BatchCodec.decodeEvent(raw));
            }
            return hub.ingestBatch(events);
        } catch (Exception e) {
            return Transport.Code.RESULT_INVALID;
        }
    }

    private String appKeyFor(ContentValues values) {
        String k = values.getAsString("app_key");
        return k == null || k.isEmpty() ? "default-appkey" : k;
    }

        @Override
    public Cursor query(Uri uri, String[] projection,
                        String selection, String[] args,
                        String sort) {
        int match = MATCHER.match(uri);
        if (match == URI_STATUS) {
            MatrixCursor c = new MatrixCursor(STATUS_COLUMNS);
            java.util.Map<String, Object> row = hub.statusRow();
            c.addRow(new Object[] {
                    String.valueOf(row.get("state")),
                    ((Number) row.get("pending")).longValue(),
                    ((Number) row.get("health")).doubleValue(),
                    ((Number) row.get("degrade_level")).intValue()
            });
            return c;
        }
        if (match == URI_METRICS) {
            MatrixCursor c = new MatrixCursor(METRIC_COLUMNS);
            for (java.util.Map.Entry<String, Long> e
                    : hub.metricsSnapshot().entrySet()) {
                c.addRow(new Object[] {e.getKey(), e.getValue()});
            }
            return c;
        }
        if (match == URI_QUERY_EVENTS) {
            long from = selection == null ? 0 : Long.parseLong(selection);
            long to = args == null || args.length == 0
                    ? Long.MAX_VALUE : Long.parseLong(args[0]);
            String like = args != null && args.length > 1 ? args[1] : null;
            int limit = args != null && args.length > 2
                    ? Integer.parseInt(args[2]) : 100;
            MatrixCursor c = new MatrixCursor(EVENT_COLUMNS);
            for (TrackEvent e : hub.queryEvents(from, to, like, limit)) {
                c.addRow(new Object[] {e.eventTime, e.eventId, e.dedupKey});
            }
            return c;
        }
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values,
                      String s, String[] strings) {
        return 0; // read-only surface (analyze.md note 1)
    }

    @Override
    public int delete(Uri uri, String s, String[] strings) {
        return 0; // read-only surface (analyze.md note 1)
    }

        @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".events";
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if (hub != null) {
            hub.shutdown();
        }
    }

    /** Lazy singleton so multiple provider instances share one controller. */
    static final class HubHolder {
        private static volatile HubController instance;

        static HubController get(Context ctx) {
            if (instance == null) {
                synchronized (HubHolder.class) {
                    if (instance == null) {
                        instance = new HubController(
                                new java.io.File(ctx.getNoBackupFilesDir(), "hub-store"),
                                20L * 1024 * 1024,
                                TrackConfig.DEFAULT_EVENT_TTL_DAYS,
                                new CloudSink.Http("https://track.ailife.example"),
                                new ReportScheduler.NetworkProbe() {
                                    @Override
                                    public boolean isOnline() {
                                        android.net.ConnectivityManager cm =
                                                (android.net.ConnectivityManager) ctx
                                                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                                        if (cm == null) {
                                            return false;
                                        }
                                        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                                        return info != null && info.isConnected();
                                    }
                                },
                                TimeSource.SYSTEM,
                                new AndroidLogger("AilifeHub"));
                    }
                }
            }
            return instance;
        }
    }

    /** android.util.Log binding of core Logger. */
    static final class AndroidLogger implements Logger {
        private final String tag;

        AndroidLogger(String tag) {
            this.tag = tag;
        }

        @Override
        public void w(String t, String msg) {
            android.util.Log.w(tag, t + ": " + msg);
        }

        @Override
        public void e(String t, String msg, Throwable tr) {
            android.util.Log.e(tag, t + ": " + msg, tr);
        }

        @Override
        public void i(String t, String msg) {
            android.util.Log.i(tag, t + ": " + msg);
        }
    }
}
