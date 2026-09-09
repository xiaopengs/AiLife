package com.ailife.track;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Data-platform ContentProvider. The write contract is
 * {@code content://com.ailife.dataplatform.track/events?ver=1}; insert returns
 * {@code .../events/<result-code>}.
 */
public class AilifeTrackProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ailife.dataplatform.track";
    public static final String TRACK_WRITE_PERMISSION = "com.ailife.permission.TRACK_WRITE";
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
        if (MATCHER.match(uri) != URI_EVENTS
                || !String.valueOf(InboundBatchDecoder.PROTOCOL_VERSION)
                        .equals(uri.getQueryParameter("ver"))
                || values == null) {
            return resultUri(Transport.Code.RESULT_INVALID);
        }

        byte[] blob = values.getAsByteArray("blob");
        String signature = values.getAsString("sig");
        Long timestamp = values.getAsLong("ts");
        String appKey = values.getAsString("app_key");
        Boolean encrypted = values.getAsBoolean("encrypted");
        if (timestamp == null || encrypted == null) {
            return resultUri(Transport.Code.RESULT_INVALID);
        }

        InboundBatchDecoder.DecodedBatch decoded = InboundBatchDecoder.decode(
                InboundBatchDecoder.PROTOCOL_VERSION, appKey, encrypted.booleanValue(), blob,
                signature, timestamp.longValue(), System.currentTimeMillis());
        if (!decoded.isValid() || hub == null) {
            return resultUri(Transport.Code.RESULT_INVALID);
        }
        return resultUri(hub.ingestBatch(decoded.events));
    }

    private static Uri resultUri(Transport.Code code) {
        return BASE.buildUpon()
                .appendPath("events")
                .appendPath(String.valueOf(code.wire))
                .build();
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
            for (java.util.Map.Entry<String, Long> e : hub.metricsSnapshot().entrySet()) {
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
    public int update(Uri uri, ContentValues values, String s, String[] strings) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String s, String[] strings) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".events";
    }

    @Override
    public void shutdown() {
        // Component teardown is not process teardown. HubHolder is shared by
        // this provider and AilifeTrackService and remains usable if either is
        // recreated by Android.
        super.shutdown();
    }

    /** Explicit process-level hooks for Android integration tests. */
    public static void shutdownHubProcessForTest() {
        HubHolder.shutdownForProcess();
    }

    /** Explicitly replaces the process singleton; intended for test isolation. */
    public static void recreateHubProcessForTest(Context context) {
        HubHolder.recreateForProcess(context);
    }

    /** Lazy process singleton shared by the provider and AIDL service. */
    static final class HubHolder {
        private static volatile HubController instance;

        static HubController get(Context context) {
            if (instance == null) {
                synchronized (HubHolder.class) {
                    if (instance == null) {
                        instance = create(context.getApplicationContext());
                    }
                }
            }
            return instance;
        }

        static void shutdownForProcess() {
            synchronized (HubHolder.class) {
                if (instance != null) {
                    instance.shutdown();
                    instance = null;
                }
            }
        }

        static HubController recreateForProcess(Context context) {
            shutdownForProcess();
            return get(context);
        }

        private static HubController create(final Context context) {
            return new HubController(
                    new java.io.File(context.getNoBackupFilesDir(), "hub-store"),
                    20L * 1024 * 1024,
                    TrackConfig.DEFAULT_EVENT_TTL_DAYS,
                    cloudSink(context),
                    new ReportScheduler.NetworkProbe() {
                        @Override
                        public boolean isOnline() {
                            android.net.ConnectivityManager cm =
                                    (android.net.ConnectivityManager) context.getSystemService(
                                            Context.CONNECTIVITY_SERVICE);
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

        /** Reads a deployment-owned HTTPS endpoint; absent/invalid means cache only. */
        private static CloudSink cloudSink(Context context) {
            try {
                android.content.pm.ApplicationInfo info = context.getPackageManager()
                        .getApplicationInfo(context.getPackageName(),
                                android.content.pm.PackageManager.GET_META_DATA);
                String endpoint = info.metaData == null ? null
                        : info.metaData.getString("com.ailife.track.CLOUD_ENDPOINT");
                if (endpoint != null && endpoint.startsWith("https://")) {
                    return new CloudSink.Http(endpoint.replaceAll("/+$", ""));
                }
            } catch (RuntimeException e) {
                android.util.Log.w("AilifeHub", "unable to read CLOUD_ENDPOINT", e);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                android.util.Log.w("AilifeHub", "package metadata missing", e);
            }
            android.util.Log.w("AilifeHub", "CLOUD_ENDPOINT missing or invalid; retaining data locally");
            return new CloudSink.Disabled();
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
