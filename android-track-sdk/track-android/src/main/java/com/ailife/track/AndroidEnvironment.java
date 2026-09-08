package com.ailife.track;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;

/**
 * Android binding of the Environment SPI. Builds the configured transport
 * (ContentProvider by default, AIDL when TrackConfig.ChannelMode.AIDL).
 * Mirrors androidx.startup-style initialization; no androidx dependency
 * required (plain ContentProvider bootstrap in AilifeTrackInit).
 */
public final class AndroidEnvironment implements Environment {

    private final Context context;
    private final Logger logger;
    private String appVersionName;
    private String osVersion;
    private String deviceModel;

    private AndroidEnvironment(Context context) {
        this.context = context.getApplicationContext();
        this.logger = new LoggerImpl("AilifeTrack");
        this.appVersionName = readVersion(context);
        this.osVersion = android.os.Build.VERSION.RELEASE;
        this.deviceModel = android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL;
    }

    public static AndroidEnvironment of(Context context) {
        return new AndroidEnvironment(context);
    }

    @Override
    public File queueDir() {
        File d = new File(context.getNoBackupFilesDir(), "ailife-track-queue");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    @Override
    public String packageName() {
        return context.getPackageName();
    }

    @Override
    public String appVersionName() {
        return appVersionName;
    }

    @Override
    public String osVersion() {
        return osVersion;
    }

    @Override
    public String deviceModel() {
        return deviceModel;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Transport createTransport(TrackConfig config) {
        if (config.channelMode == TrackConfig.ChannelMode.AIDL) {
            return new AidlTransport(context,
                    context.getPackageName(), // same-app hub service by default
                    "com.ailife.track.AilifeTrackService",
                    config.appKey);
        }
        return new ProviderTransport(context, config.providerAuthority, config.appKey);
    }

    private static String readVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    /** android.util.Log binding. */
    static final class LoggerImpl implements Logger {
        private final String tag;

        LoggerImpl(String tag) {
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
