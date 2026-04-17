/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton background thread for each process.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BackgroundThread extends HandlerThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;

    // Note: These static fields are shadowed in Robolectric, and cannot be easily changed without
    // breaking downstream tests. This makes refactoring or optimization a bit messier than it could
    // be, e.g., using common lazy singleton abstractions or the holder init pattern.
    private static volatile BackgroundThread sInstance;
    private static volatile Handler sHandler;
    private static volatile HandlerExecutor sHandlerExecutor;

    private BackgroundThread() {
        super("android.bg", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    private static void ensureThreadStartedLocked() {
        if (sInstance == null) {
            sInstance = new BackgroundThread();
            sInstance.start();
        }
    }

    private static void ensureThreadReady() {
        // Note: Due to the way Robolectric shadows sHandler, we use it as the signal of readiness.
        if (sHandler != null) {
            return;
        }
        synchronized (BackgroundThread.class) {
            if (sHandler == null) {
                ensureThreadStartedLocked();
                // This will block until the looper is initialized on the background thread.
                final Looper looper = sInstance.getLooper();
                looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
                looper.setSlowLogThresholdMs(
                        SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
                Handler handler = new Handler(looper, /*callback=*/ null, /* async=*/ false,
                        /* shared=*/ true);
                sHandlerExecutor = new HandlerExecutor(handler);
                // Assigned last to signal full readiness.
                sHandler = handler;
            }
        }
    }

    /**
     * Starts the thread if needed, but doesn't block on thread initialization or readiness.
     */
    public static void startIfNeeded() {
        if (sInstance == null) {
            synchronized (BackgroundThread.class) {
                ensureThreadStartedLocked();
            }
        }
    }

    @NonNull
    public static BackgroundThread get() {
        ensureThreadReady();
        return sInstance;
    }

    @NonNull
    public static Handler getHandler() {
        ensureThreadReady();
        return sHandler;
    }

    @NonNull
    public static Executor getExecutor() {
        ensureThreadReady();
        return sHandlerExecutor;
    }
}
