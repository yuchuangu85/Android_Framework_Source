/*
 * Copyright (C) 2011 The Android Open Source Project
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

package java.lang;

import android.system.Os;
import android.system.OsConstants;
import dalvik.system.VMRuntime;
import java.lang.ref.FinalizerReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;
import libcore.util.EmptyArray;

/**
 * Calls Object.finalize() on objects in the finalizer reference queue. The VM
 * will abort if any finalize() call takes more than the maximum finalize time
 * to complete.
 *
 * @hide
 */
public final class Daemons {
    private static final int NANOS_PER_MILLI = 1000 * 1000;
    private static final int NANOS_PER_SECOND = NANOS_PER_MILLI * 1000;
    private static final long MAX_FINALIZE_NANOS = 10L * NANOS_PER_SECOND;

    public static void start() {
        ReferenceQueueDaemon.INSTANCE.start();
        FinalizerDaemon.INSTANCE.start();
        FinalizerWatchdogDaemon.INSTANCE.start();
        HeapTaskDaemon.INSTANCE.start();
    }

    public static void stop() {
        HeapTaskDaemon.INSTANCE.stop();
        ReferenceQueueDaemon.INSTANCE.stop();
        FinalizerDaemon.INSTANCE.stop();
        FinalizerWatchdogDaemon.INSTANCE.stop();
    }

    /**
     * A background task that provides runtime support to the application.
     * Daemons can be stopped and started, but only so that the zygote can be a
     * single-threaded process when it forks.
     */
    private static abstract class Daemon implements Runnable {
        private Thread thread;
        private String name;

        protected Daemon(String name) {
            this.name = name;
        }

        public synchronized void start() {
            if (thread != null) {
                throw new IllegalStateException("already running");
            }
            thread = new Thread(ThreadGroup.systemThreadGroup, this, name);
            thread.setDaemon(true);
            thread.start();
        }

        public abstract void run();

        /**
         * Returns true while the current thread should continue to run; false
         * when it should return.
         */
        protected synchronized boolean isRunning() {
            return thread != null;
        }

        public synchronized void interrupt() {
            interrupt(thread);
        }

        public synchronized void interrupt(Thread thread) {
            if (thread == null) {
                throw new IllegalStateException("not running");
            }
            thread.interrupt();
        }

        /**
         * Waits for the runtime thread to stop. This interrupts the thread
         * currently running the runnable and then waits for it to exit.
         */
        public void stop() {
            Thread threadToStop;
            synchronized (this) {
                threadToStop = thread;
                thread = null;
            }
            if (threadToStop == null) {
                throw new IllegalStateException("not running");
            }
            interrupt(threadToStop);
            while (true) {
                try {
                    threadToStop.join();
                    return;
                } catch (InterruptedException ignored) {
                }
            }
        }

        /**
         * Returns the current stack trace of the thread, or an empty stack trace
         * if the thread is not currently running.
         */
        public synchronized StackTraceElement[] getStackTrace() {
            return thread != null ? thread.getStackTrace() : EmptyArray.STACK_TRACE_ELEMENT;
        }
    }

    /**
     * This heap management thread moves elements from the garbage collector's
     * pending list to the managed reference queue.
     */
    private static class ReferenceQueueDaemon extends Daemon {
        private static final ReferenceQueueDaemon INSTANCE = new ReferenceQueueDaemon();

        ReferenceQueueDaemon() {
            super("ReferenceQueueDaemon");
        }

        @Override public void run() {
            while (isRunning()) {
                Reference<?> list;
                try {
                    synchronized (ReferenceQueue.class) {
                        while (ReferenceQueue.unenqueued == null) {
                            ReferenceQueue.class.wait();
                        }
                        list = ReferenceQueue.unenqueued;
                        ReferenceQueue.unenqueued = null;
                    }
                } catch (InterruptedException e) {
                    continue;
                }
                enqueue(list);
            }
        }

        private void enqueue(Reference<?> list) {
            Reference<?> start = list;
            do {
                // pendingNext is owned by the GC so no synchronization is required.
                Reference<?> next = list.pendingNext;
                list.pendingNext = null;
                list.enqueueInternal();
                list = next;
            } while (list != start);
        }
    }

    private static class FinalizerDaemon extends Daemon {
        private static final FinalizerDaemon INSTANCE = new FinalizerDaemon();
        private final ReferenceQueue<Object> queue = FinalizerReference.queue;
        private volatile Object finalizingObject;
        private volatile long finalizingStartedNanos;

        FinalizerDaemon() {
            super("FinalizerDaemon");
        }

        @Override public void run() {
            while (isRunning()) {
                // Take a reference, blocking until one is ready or the thread should stop
                try {
                    doFinalize((FinalizerReference<?>) queue.remove());
                } catch (InterruptedException ignored) {
                }
            }
        }

        @FindBugsSuppressWarnings("FI_EXPLICIT_INVOCATION")
        private void doFinalize(FinalizerReference<?> reference) {
            FinalizerReference.remove(reference);
            Object object = reference.get();
            reference.clear();
            try {
                finalizingStartedNanos = System.nanoTime();
                finalizingObject = object;
                synchronized (FinalizerWatchdogDaemon.INSTANCE) {
                    FinalizerWatchdogDaemon.INSTANCE.notify();
                }
                object.finalize();
            } catch (Throwable ex) {
                // The RI silently swallows these, but Android has always logged.
                System.logE("Uncaught exception thrown by finalizer", ex);
            } finally {
                // Done finalizing, stop holding the object as live.
                finalizingObject = null;
            }
        }
    }

    /**
     * The watchdog exits the VM if the finalizer ever gets stuck. We consider
     * the finalizer to be stuck if it spends more than MAX_FINALIZATION_MILLIS
     * on one instance.
     */
    private static class FinalizerWatchdogDaemon extends Daemon {
        private static final FinalizerWatchdogDaemon INSTANCE = new FinalizerWatchdogDaemon();

        FinalizerWatchdogDaemon() {
            super("FinalizerWatchdogDaemon");
        }

        @Override public void run() {
            while (isRunning()) {
                boolean waitSuccessful = waitForObject();
                if (waitSuccessful == false) {
                    // We have been interrupted, need to see if this daemon has been stopped.
                    continue;
                }
                boolean finalized = waitForFinalization();
                if (!finalized && !VMRuntime.getRuntime().isDebuggerActive()) {
                    Object finalizedObject = FinalizerDaemon.INSTANCE.finalizingObject;
                    // At this point we probably timed out, look at the object in case the finalize
                    // just finished.
                    if (finalizedObject != null) {
                        finalizerTimedOut(finalizedObject);
                        break;
                    }
                }
            }
        }

        private boolean waitForObject() {
            while (true) {
                Object object = FinalizerDaemon.INSTANCE.finalizingObject;
                if (object != null) {
                    return true;
                }
                synchronized (this) {
                    // wait until something is ready to be finalized
                    // http://code.google.com/p/android/issues/detail?id=22778
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Daemon.stop may have interrupted us.
                        return false;
                    }
                }
            }
        }

        private void sleepFor(long startNanos, long durationNanos) {
            while (true) {
                long elapsedNanos = System.nanoTime() - startNanos;
                long sleepNanos = durationNanos - elapsedNanos;
                long sleepMills = sleepNanos / NANOS_PER_MILLI;
                if (sleepMills <= 0) {
                    return;
                }
                try {
                    Thread.sleep(sleepMills);
                } catch (InterruptedException e) {
                    if (!isRunning()) {
                        return;
                    }
                }
            }
        }

        private boolean waitForFinalization() {
            long startTime = FinalizerDaemon.INSTANCE.finalizingStartedNanos;
            sleepFor(startTime, MAX_FINALIZE_NANOS);
            // If we are finalizing an object and the start time is the same, it must be that we
            // timed out finalizing something. It may not be the same object that we started out
            // with but this doesn't matter.
            return FinalizerDaemon.INSTANCE.finalizingObject == null ||
                   FinalizerDaemon.INSTANCE.finalizingStartedNanos != startTime;
        }

        private static void finalizerTimedOut(Object object) {
            // The current object has exceeded the finalization deadline; abort!
            String message = object.getClass().getName() + ".finalize() timed out after "
                    + (MAX_FINALIZE_NANOS / NANOS_PER_SECOND) + " seconds";
            Exception syntheticException = new TimeoutException(message);
            // We use the stack from where finalize() was running to show where it was stuck.
            syntheticException.setStackTrace(FinalizerDaemon.INSTANCE.getStackTrace());
            Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
            // Send SIGQUIT to get native stack traces.
            try {
                Os.kill(Os.getpid(), OsConstants.SIGQUIT);
                // Sleep a few seconds to let the stack traces print.
                Thread.sleep(5000);
            } catch (Exception e) {
                System.logE("failed to send SIGQUIT", e);
            }
            if (h == null) {
                // If we have no handler, log and exit.
                System.logE(message, syntheticException);
                System.exit(2);
            }
            // Otherwise call the handler to do crash reporting.
            // We don't just throw because we're not the thread that
            // timed out; we're the thread that detected it.
            h.uncaughtException(Thread.currentThread(), syntheticException);
        }
    }

    // Adds a heap trim task ot the heap event processor, not called from java. Left for
    // compatibility purposes due to reflection.
    public static void requestHeapTrim() {
        VMRuntime.getRuntime().requestHeapTrim();
    }

    // Adds a concurrent GC request task ot the heap event processor, not called from java. Left
    // for compatibility purposes due to reflection.
    public static void requestGC() {
        VMRuntime.getRuntime().requestConcurrentGC();
    }

    private static class HeapTaskDaemon extends Daemon {
        private static final HeapTaskDaemon INSTANCE = new HeapTaskDaemon();

        HeapTaskDaemon() {
            super("HeapTaskDaemon");
        }

        // Overrides the Daemon.interupt method which is called from Daemons.stop.
        public synchronized void interrupt(Thread thread) {
            VMRuntime.getRuntime().stopHeapTaskProcessor();
        }

        @Override public void run() {
            synchronized (this) {
                if (isRunning()) {
                  // Needs to be synchronized or else we there is a race condition where we start
                  // the thread, call stopHeapTaskProcessor before we start the heap task
                  // processor, resulting in a deadlock since startHeapTaskProcessor restarts it
                  // while the other thread is waiting in Daemons.stop().
                  VMRuntime.getRuntime().startHeapTaskProcessor();
                }
            }
            // This runs tasks until we are stopped and there is no more pending task.
            VMRuntime.getRuntime().runHeapTasks();
        }
    }
}
