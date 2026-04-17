/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * A {@link Thread} that has a {@link Looper}. The {@link Looper} can then be used to create {@link
 * Handler}s. Just like with a regular {@link Thread}, {@link #start()} must still be called.
 *
 * <p><em>Use this class only if you must work with the {@link Handler} API and need a {@link
 * Thread} to do the handling on that is not an existing {@link Looper} thread, such as {@link
 * Looper#getMainLooper()}.</em> Otherwise, prefer {@link java.util.concurrent.Executor} or {@link
 * java.util.concurrent.ExecutorService}, or Kotlin <a
 * href="https://developer.android.com/topic/libraries/architecture/coroutines">coroutines</a>.
 *
 * <p>Note that many APIs that required a {@link Handler} in older SDK versions offer a newer
 * alternative that accepts an {@link java.util.concurrent.Executor} instead. Always prefer to use
 * the newer API if available.
 *
 * <h2>Alternatives to {@code HandlerThread}</h2>
 *
 * <p>{@link java.util.concurrent.Executor}s offer more flexibility with regards to threading. Work
 * submitted to an {@link java.util.concurrent.Executor} can be set to run on another thread, on one
 * of several threads from a static or dynamic pool, or on the caller's thread, depending on your
 * needs.
 *
 * <p>{link @link java.util.concurrent.Executor} offers a simpler API that is easier to use compared
 * to {@link Handler}. {link @link java.util.concurrent.ExecutorService} offers the richer {@link
 * java.util.concurrent.Future} API, which you can use to monitor task status, cancel tasks,
 * propagate exceptions, and chain multiple pending tasks.
 *
 * <p>{link @link java.util.concurrent.Executors} is a factory for various {@link
 * java.util.concurrent.Executor}s that meet common concurrency needs. These {@link
 * java.util.concurrent.Executor}s use work queues that offer better concurrency and reduced
 * contention than {@code HandlerThread}.
 *
 * <p>On Kotlin, <a
 * href="https://developer.android.com/topic/libraries/architecture/coroutines">coroutines</a> may
 * be used to handle concurrency.
 *
 * <h2>Common {@code HandlerThread} performance issues</h2>
 *
 * <p>Apps that use {@code HandlerThread} may encounter the following performance issues:
 *
 * <ul>
 *   <li><b>Excessive thread creation</b>: A {@code HandlerThread} is a {@link java.lang.Thread}.
 *       Every system thread costs some resident memory, whether it is working or if it's idle. If
 *       your app has a large number of {@code HandlerThread}s each dedicated to a single type of
 *       task, rather than for instance a {@link java.util.concurrent.ThreadPoolExecutor} that can
 *       grow and shrink in size according to demand, then the additional idle {@code
 *       HandlerThread}s will be wasting memory.
 *   <li><b>Lock contention</b>: {@code HandlerThread} uses a {@link Looper} which in turn uses a
 *       {@link MessageQueue}. {@link MessageQueue} uses a single lock to synchronize access to its
 *       underlying queue. Any threads attempting to enqueue messages at the same time, and the
 *       {@code HandlerThread} itself when attempting to dequeue the next message to handle, will
 *       block each other.
 *   <li><b>Priority inversion</b>: A high-priority {@code HandlerThread} can become blocked by a
 *       lower-priority thread, for instance if the former is trying to enqueue a message while the
 *       latter is trying to dequeue the next message to handle, or vice versa.
 * </ul>
 *
 * The best way to avoid these issues is to use {@link java.util.concurrent.Executor}s or <a
 * href="https://developer.android.com/topic/libraries/architecture/coroutines">Kotlin
 * coroutines</a> instead of {@code HandlerThread}.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class HandlerThread extends Thread {
    int mPriority;
    int mTid = -1;
    Looper mLooper;
    private volatile @Nullable Handler mHandler;
    private volatile @Nullable Executor mExecutor;

    public HandlerThread(String name) {
        super(name);
        mPriority = Process.THREAD_PRIORITY_DEFAULT;
        onCreated();
    }

    /**
     * Constructs a HandlerThread.
     * @param name
     * @param priority The priority to run the thread at. The value supplied must be from
     * {@link android.os.Process} and not from java.lang.Thread.
     */
    public HandlerThread(String name, int priority) {
        super(name);
        mPriority = priority;
        onCreated();
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodReplace
    protected void onCreated() {
    }

    /** @hide */
    protected void onCreated$ravenwood() {
        // Mark ourselves as daemon to enable tests to terminate quickly when finished, despite
        // any HandlerThread instances that may be lingering around
        setDaemon(true);
    }

    /**
     * Call back method that can be explicitly overridden if needed to execute some
     * setup before Looper loops.
     */
    protected void onLooperPrepared() {
    }

    @Override
    public void run() {
        mTid = Process.myTid();
        Looper.prepare();
        synchronized (this) {
            mLooper = Looper.myLooper();
            notifyAll();
        }
        Process.setThreadPriority(mPriority);
        onLooperPrepared();
        Looper.loop();
        mTid = -1;
    }

    /**
     * This method returns the Looper associated with this thread. If this thread not been started
     * or for any reason isAlive() returns false, this method will return null. If this thread
     * has been started, this method will block until the looper has been initialized.
     * @return The looper.
     */
    public Looper getLooper() {
        if (!isAlive()) {
            return null;
        }

        boolean wasInterrupted = false;

        // If the thread has been started, wait until the looper has been created.
        synchronized (this) {
            while (isAlive() && mLooper == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    wasInterrupted = true;
                }
            }
        }

        /*
         * We may need to restore the thread's interrupted flag, because it may
         * have been cleared above since we eat InterruptedExceptions
         */
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }

        return mLooper;
    }

    /**
     * @return a shared {@link Handler} associated with this thread
     * @hide
     */
    @NonNull
    public Handler getThreadHandler() {
        if (mHandler == null) {
            mHandler = new Handler(getLooper());
        }
        return mHandler;
    }

    /**
     * @return a shared {@link Executor} associated with this thread
     * @hide
     */
    @NonNull
    public Executor getThreadExecutor() {
        if (mExecutor == null) {
            mExecutor = new HandlerExecutor(getThreadHandler());
        }
        return mExecutor;
    }

    /**
     * Quits the handler thread's looper.
     * <p>
     * Causes the handler thread's looper to terminate without processing any
     * more messages in the message queue.
     * </p><p>
     * Any attempt to post messages to the queue after the looper is asked to quit will fail.
     * For example, the {@link Handler#sendMessage(Message)} method will return false.
     * </p><p>
     * If {@link #quit} or {@link #quitSafely} is called multiple times, the first call
     * will have an effect and the subsequent calls will be no-ops.
     * </p><p class="note">
     * Using this method may be unsafe because some messages may not be delivered
     * before the looper terminates.  Consider using {@link #quitSafely} instead to ensure
     * that all pending work is completed in an orderly manner.
     * </p>
     *
     * @return True if the looper looper has been asked to quit or false if the
     * thread had not yet started running.
     *
     * @see #quitSafely
     */
    public boolean quit() {
        Looper looper = getLooper();
        if (looper != null) {
            looper.quit();
            return true;
        }
        return false;
    }

    /**
     * Quits the handler thread's looper safely.
     * <p>
     * Causes the handler thread's looper to terminate as soon as all remaining messages
     * in the message queue that are already due to be delivered have been handled.
     * Pending delayed messages with due times in the future will not be delivered.
     * </p><p>
     * Any attempt to post messages to the queue after the looper is asked to quit will fail.
     * For example, the {@link Handler#sendMessage(Message)} method will return false.
     * </p><p>
     * If the thread has not been started or has finished (that is if
     * {@link #getLooper} returns null), then false is returned.
     * Otherwise the looper is asked to quit and true is returned.
     * </p><p>
     * If {@link #quit} or {@link #quitSafely} is called multiple times, the first call
     * will have an effect and the subsequent calls will be no-ops.
     * </p>
     *
     * @return True if the looper looper has been asked to quit or false if the
     * thread had not yet started running.
     */
    public boolean quitSafely() {
        Looper looper = getLooper();
        if (looper != null) {
            looper.quitSafely();
            return true;
        }
        return false;
    }

    /**
     * Returns the identifier of this thread. See Process.myTid().
     */
    public int getThreadId() {
        return mTid;
    }
}
