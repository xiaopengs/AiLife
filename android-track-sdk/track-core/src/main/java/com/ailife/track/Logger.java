package com.ailife.track;

/**
 * Minimal logging abstraction so track-core stays android-free and unit
 * tests can capture diagnostics. Android layer binds android.util.Log.
 */
public interface Logger {
    void w(String tag, String msg);
    void e(String tag, String msg, Throwable t);
    void i(String tag, String msg);

    Logger NOOP = new Logger() {
        @Override
        public void w(String tag, String msg) { }
        @Override
        public void e(String tag, String msg, Throwable t) { }
        @Override
        public void i(String tag, String msg) { }
    };
}
