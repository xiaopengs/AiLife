package com.ailife.track;

import java.io.File;

/**
 * Platform SPI: the only seam between the pure-Java SDK core and Android.
 * The track-android module binds this to android.content.Context (queue
 * directory, package/version info, LogCat logger, Provider/AIDL transport).
 * Unit tests inject a JVM implementation.
 */
public interface Environment {
    /** Private queue directory (Context.getNoBackupFilesDir or filesDir). */
    File queueDir();

    String packageName();

    String appVersionName();

    String osVersion();

    String deviceModel();

    Logger logger();

    /** Build the configured transport (Provider or AIDL per channelMode). */
    Transport createTransport(TrackConfig config);
}
