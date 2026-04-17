package org.chromium.base;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;

/**
 * A shim class for org.chromium.base.android.net.http.internal.org.chromium.base.ThreadUtils.
 * Delegates all static method calls to the actual ThreadUtils class.
 */
public final class ThreadUtils {

    public static void setWillOverrideUiThread() {
        android.net.http.internal.org.chromium.base.ThreadUtils.setWillOverrideUiThread();
    }

    public static void setUiThread(Looper looper) {
        android.net.http.internal.org.chromium.base.ThreadUtils.setUiThread(looper);
    }

    public static Handler getUiThreadHandler() {
        return android.net.http.internal.org.chromium.base.ThreadUtils.getUiThreadHandler();
    }

    public static void runOnUiThreadBlocking(Runnable r) {
        android.net.http.internal.org.chromium.base.ThreadUtils.runOnUiThreadBlocking(r);
    }

    public static <T extends Object> T runOnUiThreadBlocking(Callable<T> c) {
        return android.net.http.internal.org.chromium.base.ThreadUtils.runOnUiThreadBlocking(c);
    }

    public static void runOnUiThread(Runnable r) {
        android.net.http.internal.org.chromium.base.ThreadUtils.runOnUiThread(r);
    }

    public static void postOnUiThread(Runnable r) {
        android.net.http.internal.org.chromium.base.ThreadUtils.postOnUiThread(r);
    }

    public static void postOnUiThreadDelayed(Runnable task, long delayMillis) {
        android.net.http.internal.org.chromium.base.ThreadUtils.postOnUiThreadDelayed(
                task, delayMillis);
    }

    public static void assertOnUiThread() {
        android.net.http.internal.org.chromium.base.ThreadUtils.assertOnUiThread();
    }

    public static boolean runningOnUiThread() {
        return android.net.http.internal.org.chromium.base.ThreadUtils.runningOnUiThread();
    }

    public static Looper getUiThreadLooper() {
        return android.net.http.internal.org.chromium.base.ThreadUtils.getUiThreadLooper();
    }

    public static void setThreadPriorityAudio(int tid) {
        android.net.http.internal.org.chromium.base.ThreadUtils.setThreadPriorityAudio(tid);
    }
}
