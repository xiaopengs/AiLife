# Public Android SDK entry points referenced from AndroidManifest.xml or AIDL.
-keep public class com.ailife.track.AilifeTrackProvider { public <init>(); }
-keep public class com.ailife.track.AilifeTrackService { public <init>(); }
-keep public class com.ailife.track.AilifeTrackInit { public <init>(); }
-keep class com.ailife.track.aidl.** { *; }
