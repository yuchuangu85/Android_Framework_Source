/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.SparseIntArray;

import com.android.internal.util.Preconditions;

import dalvik.system.VMRuntime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Private and debugging Binder APIs.
 *
 * @see IBinder
 */
public class BinderInternal {
    private static final String TAG = "BinderInternal";
    static WeakReference<GcWatcher> sGcWatcher
            = new WeakReference<GcWatcher>(new GcWatcher());
    static ArrayList<Runnable> sGcWatchers = new ArrayList<>();
    static Runnable[] sTmpWatchers = new Runnable[1];
    static long sLastGcTime;
    static final BinderProxyLimitListenerDelegate sBinderProxyLimitListenerDelegate =
            new BinderProxyLimitListenerDelegate();

    static final class GcWatcher {
        @Override
        protected void finalize() throws Throwable {
            handleGc();
            sLastGcTime = SystemClock.uptimeMillis();
            synchronized (sGcWatchers) {
                sTmpWatchers = sGcWatchers.toArray(sTmpWatchers);
            }
            for (int i=0; i<sTmpWatchers.length; i++) {
                if (sTmpWatchers[i] != null) {
                    sTmpWatchers[i].run();
                }
            }
            sGcWatcher = new WeakReference<GcWatcher>(new GcWatcher());
        }
    }

    public static void addGcWatcher(Runnable watcher) {
        synchronized (sGcWatchers) {
            sGcWatchers.add(watcher);
        }
    }

    /**
     * A session used by {@link Observer} in order to keep track of some data.
     */
    public static class CallSession {
        // Binder interface descriptor.
        public Class<? extends Binder> binderClass;
        // Binder transaction code.
        public int transactionCode;
        // CPU time at the beginning of the call.
        long cpuTimeStarted;
        // System time at the beginning of the call.
        long timeStarted;
        // Should be set to one when an exception is thrown.
        boolean exceptionThrown;
        // Detailed information should be recorded for this call when it ends.
        public boolean recordedCall;
    }

    /**
     * Responsible for resolving a work source.
     */
    @FunctionalInterface
    public interface WorkSourceProvider {
        /**
         * <p>This method is called in a critical path of the binder transaction.
         * <p>The implementation should never execute a binder call since it is called during a
         * binder transaction.
         *
         * @param untrustedWorkSourceUid The work source set by the caller.
         * @return the uid of the process to attribute the binder transaction to.
         */
        int resolveWorkSourceUid(int untrustedWorkSourceUid);
    }

    /**
     * Allows to track various steps of an API call.
     */
    public interface Observer {
        /**
         * Called when a binder call starts.
         *
         * @return a CallSession to pass to the callEnded method.
         */
        CallSession callStarted(Binder binder, int code, int workSourceUid);

        /**
         * Called when a binder call stops.
         *
         * <li>This method will be called even when an exception is thrown by the binder stub
         * implementation.
         */
        void callEnded(CallSession s, int parcelRequestSize, int parcelReplySize,
                int workSourceUid);

        /**
         * Called if an exception is thrown while executing the binder transaction.
         *
         * <li>BinderCallsStats#callEnded will be called afterwards.
         * <li>Do not throw an exception in this method, it will swallow the original exception
         * thrown by the binder transaction.
         */
        public void callThrewException(CallSession s, Exception exception);
    }

    /**
     * Allows to track observe incoming binder call stats.
     */
    public interface CallStatsObserver {
        /**
         * Notes incoming binder call stats associated with this work source UID.
         */
        void noteCallStats(int workSourceUid, long incrementalCallCount,
                Collection<BinderCallsStats.CallStat> callStats);

        /**
         * Notes the native IDs of threads taking incoming binder calls.
         */
        void noteBinderThreadNativeIds(int[] binderThreadNativeTids);
    }

    /**
     * Add the calling thread to the IPC thread pool.  This function does
     * not return until the current process is exiting.
     */
    public static final native void joinThreadPool();

    /**
     * Return the system time (as reported by {@link SystemClock#uptimeMillis
     * SystemClock.uptimeMillis()}) that the last garbage collection occurred
     * in this process.  This is not for general application use, and the
     * meaning of "when a garbage collection occurred" will change as the
     * garbage collector evolves.
     *
     * @return Returns the time as per {@link SystemClock#uptimeMillis
     * SystemClock.uptimeMillis()} of the last garbage collection.
     */
    public static long getLastGcTime() {
        return sLastGcTime;
    }

    /**
     * Return the global "context object" of the system.  This is usually
     * an implementation of IServiceManager, which you can use to find
     * other services.
     */
    @UnsupportedAppUsage
    public static final native IBinder getContextObject();

    /**
     * Special for system process to not allow incoming calls to run at
     * background scheduling priority.
     * @hide
     */
    public static final native void disableBackgroundScheduling(boolean disable);

    public static final native void setMaxThreads(int numThreads);

    @UnsupportedAppUsage
    static native final void handleGc();

    public static void forceGc(String reason) {
        EventLog.writeEvent(2741, reason);
        VMRuntime.getRuntime().requestConcurrentGC();
    }

    static void forceBinderGc() {
        forceGc("Binder");
    }

    /**
     * Enable/disable Binder Proxy Instance Counting by Uid. While enabled, the set callback will
     * be called if this process holds too many Binder Proxies on behalf of a Uid.
     * @param enabled true to enable counting, false to disable
     */
    public static final native void nSetBinderProxyCountEnabled(boolean enabled);

    /**
     * Get the current number of Binder Proxies held for each uid.
     * @return SparseIntArray mapping uids to the number of Binder Proxies currently held
     */
    public static final native SparseIntArray nGetBinderProxyPerUidCounts();

    /**
     * Get the current number of Binder Proxies held for an individual uid.
     * @param uid Requested uid for Binder Proxy count
     * @return int with the number of Binder proxies held for a uid
     */
    public static final native int nGetBinderProxyCount(int uid);

    /**
     * Set the Binder Proxy watermarks. Default high watermark = 2500. Default low watermark = 2000
     * @param high  The limit at which the BinderProxyListener callback will be called.
     * @param low   The threshold a binder count must drop below before the callback
     *              can be called again. (This is to avoid many repeated calls to the
     *              callback in a brief period of time)
     */
    public static final native void nSetBinderProxyCountWatermarks(int high, int low);

    /**
     * Interface for callback invocation when the Binder Proxy limit is reached. onLimitReached will
     * be called with the uid of the app causing too many Binder Proxies
     */
    public interface BinderProxyLimitListener {
        public void onLimitReached(int uid);
    }

    /**
     * Callback used by native code to trigger a callback in java code. The callback will be
     * triggered when too many binder proxies from a uid hits the allowed limit.
     * @param uid The uid of the bad behaving app sending too many binders
     */
    public static void binderProxyLimitCallbackFromNative(int uid) {
       sBinderProxyLimitListenerDelegate.notifyClient(uid);
    }

    /**
     * Set a callback to be triggered when a uid's Binder Proxy limit is reached for this process.
     * @param listener OnLimitReached of listener will be called in the thread provided by handler
     * @param handler must not be null, callback will be posted through the handler;
     *
     */
    public static void setBinderProxyCountCallback(BinderProxyLimitListener listener,
            @NonNull Handler handler) {
        Preconditions.checkNotNull(handler,
                "Must provide NonNull Handler to setBinderProxyCountCallback when setting "
                        + "BinderProxyLimitListener");
        sBinderProxyLimitListenerDelegate.setListener(listener, handler);
    }

    /**
     * Clear the Binder Proxy callback
     */
    public static void clearBinderProxyCountCallback() {
        sBinderProxyLimitListenerDelegate.setListener(null, null);
    }

    static private class BinderProxyLimitListenerDelegate {
        private BinderProxyLimitListener mBinderProxyLimitListener;
        private Handler mHandler;

        void setListener(BinderProxyLimitListener listener, Handler handler) {
            synchronized (this) {
                mBinderProxyLimitListener = listener;
                mHandler = handler;
            }
        }

        void notifyClient(final int uid) {
            synchronized (this) {
                if (mBinderProxyLimitListener != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mBinderProxyLimitListener.onLimitReached(uid);
                        }
                    });
                }
            }
        }
    }
}
