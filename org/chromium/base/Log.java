package org.chromium.base;

/**
 * A shim class for org.chromium.base.android.net.http.internal.org.chromium.base.Log. Delegates all
 * static method calls to the actual android.net.http.internal.org.chromium.base.Log.class.
 */
public final class Log {
    public static final int ASSERT = android.net.http.internal.org.chromium.base.Log.ASSERT;
    public static final int DEBUG = android.net.http.internal.org.chromium.base.Log.DEBUG;
    public static final int ERROR = android.net.http.internal.org.chromium.base.Log.ERROR;
    public static final int INFO = android.net.http.internal.org.chromium.base.Log.INFO;
    public static final int VERBOSE = android.net.http.internal.org.chromium.base.Log.VERBOSE;
    public static final int WARN = android.net.http.internal.org.chromium.base.Log.WARN;

    public static String normalizeTag(String tag) {
        return android.net.http.internal.org.chromium.base.Log.normalizeTag(tag);
    }

    public static boolean isLoggable(String tag, int level) {
        return android.net.http.internal.org.chromium.base.Log.isLoggable(tag, level);
    }

    // Verbose
    public static void v(String tag, String message, Object... args) {
        android.net.http.internal.org.chromium.base.Log.v(tag, message, args);
    }

    public static void v(String tag, String message, Throwable t) {
        android.net.http.internal.org.chromium.base.Log.v(tag, message, t);
    }

    // Debug
    public static void d(String tag, String message, Object... args) {
        android.net.http.internal.org.chromium.base.Log.d(tag, message, args);
    }

    public static void d(String tag, String message, Throwable t) {
        android.net.http.internal.org.chromium.base.Log.d(tag, message, t);
    }

    // Info
    public static void i(String tag, String message, Object... args) {
        android.net.http.internal.org.chromium.base.Log.i(tag, message, args);
    }

    public static void i(String tag, String message, Throwable t) {
        android.net.http.internal.org.chromium.base.Log.i(tag, message, t);
    }

    // Warn
    public static void w(String tag, String message, Object... args) {
        android.net.http.internal.org.chromium.base.Log.w(tag, message, args);
    }

    public static void w(String tag, String message, Throwable t) {
        android.net.http.internal.org.chromium.base.Log.w(tag, message, t);
    }

    // Error
    public static void e(String tag, String message, Object... args) {
        android.net.http.internal.org.chromium.base.Log.e(tag, message, args);
    }

    public static void e(String tag, String message, Throwable t) {
        android.net.http.internal.org.chromium.base.Log.e(tag, message, t);
    }

    // What a Terrible Failure
    public static void wtf(String tag, String message, Object... args) {
        android.net.http.internal.org.chromium.base.Log.wtf(tag, message, args);
    }

    public static void wtf(String tag, String message, Throwable t) {
        android.net.http.internal.org.chromium.base.Log.wtf(tag, message, t);
    }

    public static String getStackTraceString(Throwable tr) {
        return android.net.http.internal.org.chromium.base.Log.getStackTraceString(tr);
    }
}
