package com.ailife.track;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * No-op bootstrap ContentProvider (androidx.startup pattern without the
 * androidx dependency): runs AilifeTrack.init before any Activity/Application
 * code so every process (including :hub) has the SDK ready. Configure the
 * TrackConfig via meta-data in AndroidManifest.xml.
 */
public class AilifeTrackInit extends ContentProvider {

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        if ("com.ailife.dataplatform".equals(ctx.getPackageName())) {
            return true; // hub process: only the provider/service side runs here
        }
        try {
            ApplicationConfig cfg = ApplicationConfig.from(ctx);
            AilifeTrack.init(cfg.config, AndroidEnvironment.of(ctx));
        } catch (Exception e) {
            android.util.Log.w("AilifeTrack", "init failed; SDK disabled", e);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] strings) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String s, String[] strings) {
        return 0;
    }

    /** Manifest meta-data reader (fail-safe to defaults per E8). */
    static final class ApplicationConfig {
        final TrackConfig config;

        ApplicationConfig(TrackConfig config) {
            this.config = config;
        }

        static ApplicationConfig from(Context ctx) {
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(
                                ctx.getPackageName(),
                                android.content.pm.PackageManager.GET_META_DATA);
                android.os.Bundle md = ai.metaData;
                if (md == null) {
                    return new ApplicationConfig(defaultConfig(ctx));
                }
                String appKey = md.getString("com.ailife.track.APP_KEY");
                TrackConfig.Builder b = TrackConfig.builder(appKey);
                String mode = md.getString("com.ailife.track.CHANNEL");
                if ("aidl".equalsIgnoreCase(mode)) {
                    b.channelMode(TrackConfig.ChannelMode.AIDL);
                }
                if (md.containsKey("com.ailife.track.FLUSH_INTERVAL_MS")) {
                    b.flushIntervalMs(
                            (long) md.getInt("com.ailife.track.FLUSH_INTERVAL_MS"));
                }
                if (md.containsKey("com.ailife.track.BATCH_COUNT")) {
                    b.batchCount(md.getInt("com.ailife.track.BATCH_COUNT"));
                }
                if (md.containsKey("com.ailife.track.TTL_DAYS")) {
                    b.eventTtlDays(md.getInt("com.ailife.track.TTL_DAYS"));
                }
                if (md.containsKey("com.ailife.track.QUEUE_MB")) {
                    b.maxQueueBytes(md.getInt("com.ailife.track.QUEUE_MB")
                            * 1024L * 1024L);
                }
                b.encryptPayload(md.getBoolean("com.ailife.track.ENCRYPT", true));
                return new ApplicationConfig(b.build());
            } catch (Exception e) {
                return new ApplicationConfig(defaultConfig(ctx));
            }
        }

        private static TrackConfig defaultConfig(Context ctx) {
            // A package name is not a credential. Missing metadata intentionally
            // creates a local-cache-only SDK until the integrator configures APP_KEY.
            return TrackConfig.builder(null).build();
        }
    }
}
