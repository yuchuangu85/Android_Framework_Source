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

import android.compat.annotation.UnsupportedAppUsage;
import android.system.Os;
import android.system.OsConstants;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.lang.ref.FinalizerReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import libcore.util.EmptyArray;

import dalvik.system.VMRuntime;
import dalvik.system.VMDebug;

import jdk.internal.ref.CleanerImpl;

/**
 * Calls Object.finalize() on objects in the finalizer reference queue. The VM
 * will abort if any finalize() call takes more than the maximum finalize time
 * to complete.
 *
 * @hide
 */
public final class Daemons {
    private static final int NANOS_PER_MILLI = 1000 * 1000;

    // This used to be final. IT IS NOW ONLY WRITTEN. We now update it when we look at the command
    // line argument, for the benefit of mis-behaved apps that might read it.  SLATED FOR REMOVAL.
    // There is no reason to use this: Finalizers should not rely on the value. If a finalizer takes
    // appreciable time, the work should be done elsewhere.  Based on disassembly of Daemons.class,
    // the value is effectively inlined, so changing the field never did have an effect.
    // DO NOT USE. FOR ANYTHING. THIS WILL BE REMOVED SHORTLY.
    @UnsupportedAppUsage
    private static long MAX_FINALIZE_NANOS = 10L * 1000 * NANOS_PER_MILLI;

    private static final Daemon[] DAEMONS = new Daemon[] {
            HeapTaskDaemon.INSTANCE,
            ReferenceQueueDaemon.INSTANCE,
            FinalizerDaemon.INSTANCE,
            FinalizerWatchdogDaemon.INSTANCE,
    };
    private static final CountDownLatch POST_ZYGOTE_START_LATCH = new CountDownLatch(DAEMONS.length);
    private static final CountDownLatch PRE_ZYGOTE_START_LATCH = new CountDownLatch(DAEMONS.length);

    private static boolean postZygoteFork = false;

    @UnsupportedAppUsage
    public static void start() {
        for (Daemon daemon : DAEMONS) {
            daemon.start();
        }
    }

    public static void startPostZygoteFork() {
        postZygoteFork = true;
        for (Daemon daemon : DAEMONS) {
            daemon.startPostZygoteFork();
        }
    }

    @UnsupportedAppUsage
    public static void stop() {
        for (Daemon daemon : DAEMONS) {
            daemon.stop();
        }
    }

    private static void waitForDaemonStart() throws Exception {
        if (postZygoteFork) {
            POST_ZYGOTE_START_LATCH.await();
        } else {
            PRE_ZYGOTE_START_LATCH.await();
        }
    }

    /**
     * A background task that provides runtime support to the application.
     * Daemons can be stopped and started, but only so that the zygote can be a
     * single-threaded process when it forks.
     */
    private static abstract class Daemon implements Runnable {
        @UnsupportedAppUsage
        private Thread thread;
        private String name;
        private boolean postZygoteFork;

        protected Daemon(String name) {
            this.name = name;
        }

        @UnsupportedAppUsage
        public synchronized void start() {
            startInternal();
        }

        public synchronized void startPostZygoteFork() {
            postZygoteFork = true;
            startInternal();
        }

        public void startInternal() {
            if (thread != null) {
                throw new IllegalStateException("already running");
            }
            thread = new Thread(ThreadGroup.systemThreadGroup, this, name);
            thread.setDaemon(true);
            thread.setSystemDaemon(true);
            thread.start();
        }

        public final void run() {
            if (postZygoteFork) {
                // We don't set the priority before the Thread.start() call above because
                // Thread.start() will call SetNativePriority and overwrite the desired native
                // priority. We (may) use a native priority that doesn't have a corresponding
                // java.lang.Thread-level priority (native priorities are more coarse-grained.)
                VMRuntime.getRuntime().setSystemDaemonThreadPriority();
                POST_ZYGOTE_START_LATCH.countDown();
            } else {
                PRE_ZYGOTE_START_LATCH.countDown();
            }
            try {
                runInternal();
            } catch (Throwable ex) {
                // Should never happen, but may not o.w. get reported, e.g. in zygote.
                // Risk logging redundantly, rather than losing it.
                System.logE("Uncaught exception in system thread " + name, ex);
                throw ex;
            }
        }

        public abstract void runInternal();

        /**
         * Returns true while the current thread should continue to run; false
         * when it should return.
         */
        @UnsupportedAppUsage
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
        @UnsupportedAppUsage
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
                } catch (OutOfMemoryError ignored) {
                    // An OOME may be thrown if allocating the InterruptedException failed.
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
        @UnsupportedAppUsage
        private static final ReferenceQueueDaemon INSTANCE = new ReferenceQueueDaemon();

        // Monitored by FinalizerWatchdogDaemon to make sure we're still working.
        private final AtomicInteger progressCounter = new AtomicInteger(0);

        ReferenceQueueDaemon() {
            super("ReferenceQueueDaemon");
        }

        @Override public void runInternal() {
            FinalizerWatchdogDaemon.INSTANCE.monitoringNeeded(FinalizerWatchdogDaemon.RQ_DAEMON);
            while (isRunning()) {
                Reference<?> list;
                try {
                    synchronized (ReferenceQueue.class) {
                        if (ReferenceQueue.unenqueued == null) {
                            progressCounter.incrementAndGet();
                            FinalizerWatchdogDaemon.INSTANCE.monitoringNotNeeded(
                                    FinalizerWatchdogDaemon.RQ_DAEMON);
                            do {
                               ReferenceQueue.class.wait();
                            } while (ReferenceQueue.unenqueued == null);
                            progressCounter.incrementAndGet();
                            FinalizerWatchdogDaemon.INSTANCE.monitoringNeeded(
                                    FinalizerWatchdogDaemon.RQ_DAEMON);
                        }
                        list = ReferenceQueue.unenqueued;
                        ReferenceQueue.unenqueued = null;
                    }
                } catch (InterruptedException e) {
                    continue;
                } catch (OutOfMemoryError e) {
                    continue;
                }
                ReferenceQueue.enqueuePending(list, progressCounter);
                FinalizerWatchdogDaemon.INSTANCE.resetTimeouts();
            }
        }

        ReferenceQueue currentlyProcessing() {
          return ReferenceQueue.getCurrentQueue();
        }
    }

    private static class FinalizerDaemon extends Daemon {
        @UnsupportedAppUsage
        private static final FinalizerDaemon INSTANCE = new FinalizerDaemon();
        private final ReferenceQueue<Object> queue = FinalizerReference.queue;
        private final AtomicInteger progressCounter = new AtomicInteger(0);
        // Object (not reference!) being finalized. Accesses may race!
        @UnsupportedAppUsage
        private Object finalizingObject = null;

        FinalizerDaemon() {
            super("FinalizerDaemon");
        }

        @Override public void runInternal() {
            // This loop may be performance critical, since we need to keep up with mutator
            // generation of finalizable objects.
            // We minimize the amount of work we do per finalizable object. For example, we avoid
            // reading the current time here, since that involves a kernel call per object.  We
            // limit fast path communication with FinalizerWatchDogDaemon to what's unavoidable: A
            // non-volatile store to communicate the current finalizable object, e.g. for
            // reporting, and a release store (lazySet) to a counter.
            // We do stop the  FinalizerWatchDogDaemon if we have nothing to do for a
            // potentially extended period.  This prevents the device from waking up regularly
            // during idle times.

            // Local copy of progressCounter; saves a fence per increment on ARM.
            int localProgressCounter = progressCounter.get();

            FinalizerWatchdogDaemon.INSTANCE.monitoringNeeded(
                    FinalizerWatchdogDaemon.FINALIZER_DAEMON);
            while (isRunning()) {
                try {
                    // Use non-blocking poll to avoid FinalizerWatchdogDaemon communication
                    // when busy.
                    Object nextReference = queue.poll();
                    if (nextReference != null) {
                        progressCounter.lazySet(++localProgressCounter);
                        processReference(nextReference);
                    } else {
                        finalizingObject = null;
                        progressCounter.lazySet(++localProgressCounter);
                        // Slow path; block.
                        FinalizerWatchdogDaemon.INSTANCE.monitoringNotNeeded(
                                FinalizerWatchdogDaemon.FINALIZER_DAEMON);
                        nextReference = queue.remove();
                        progressCounter.set(++localProgressCounter);
                        FinalizerWatchdogDaemon.INSTANCE.monitoringNeeded(
                                FinalizerWatchdogDaemon.FINALIZER_DAEMON);
                        processReference(nextReference);
                    }
                } catch (InterruptedException ignored) {
                } catch (OutOfMemoryError ignored) {
                }
            }
        }

        private void processReference(Object ref) {
            if (ref instanceof FinalizerReference finalizingReference) {
                finalizingObject = finalizingReference.get();
                doFinalize(finalizingReference);
            } else if (ref instanceof Cleaner.Cleanable cleanableReference) {
                finalizingObject = cleanableReference;
                doClean(cleanableReference);
            } else {
                throw new AssertionError("Unknown class was placed into queue: " + ref);
            }
        }

        @FindBugsSuppressWarnings("FI_EXPLICIT_INVOCATION")
        private void doFinalize(FinalizerReference<?> reference) {
            FinalizerReference.remove(reference);
            Object object = reference.get();
            reference.clear();
            try {
                object.finalize();
            } catch (Throwable ex) {
                // The RI silently swallows these, but Android has always logged.
                System.logE("Uncaught exception thrown by finalizer", ex);
            } finally {
                // Done finalizing, stop holding the object as live.
                finalizingObject = null;
            }
        }

        private void doClean(Cleaner.Cleanable cleanable) {
            try {
                cleanable.clean();
            } catch (Throwable ex) {
                System.logE("Uncaught exception thrown by cleaner", ex);
            } finally {
                // No need to hold cleaner object.
                finalizingObject = null;
            }
        }
    }

    /**
     * The watchdog exits the VM if either the FinalizerDaemon, or the ReferenceQueueDaemon
     * gets stuck. We consider the finalizer to be stuck if it spends more than
     * MAX_FINALIZATION_MILLIS on one instance. We consider ReferenceQueueDaemon to be
     * potentially stuck if it spends more than MAX_FINALIZATION_MILLIS processing a single
     * Cleaner or transferring objects into a single queue, but only report if this happens
     * a few times in a row, to compensate for the fact that multiple Cleaners may be involved.
     */
    private static class FinalizerWatchdogDaemon extends Daemon {
        // Single bit values to identify daemon to be watched.
        static final int FINALIZER_DAEMON = 1;
        static final int RQ_DAEMON = 2;

        @UnsupportedAppUsage
        private static final FinalizerWatchdogDaemon INSTANCE = new FinalizerWatchdogDaemon();
        private static final VarHandle VH_ACTION;
        static {
            try {
                VH_ACTION = MethodHandles
                        .privateLookupIn(
                                CleanerImpl.PhantomCleanableRef.class, MethodHandles.lookup())
                        .findVarHandle(
                                CleanerImpl.PhantomCleanableRef.class, "action", Runnable.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new AssertionError("PhantomCleanableRef should have action field", e);
            }
        }

        private int activeWatchees;  // Only synchronized accesses.

        private long finalizerTimeoutNs = 0;  // Lazily initialized.

        // We tolerate this many timeouts during an enqueuePending call.
        // This number is > 1, since we may only report enqueuePending progress rarely.
        private static final int TOLERATED_REFERENCE_QUEUE_TIMEOUTS = 5;
        private static final AtomicInteger observedReferenceQueueTimeouts = new AtomicInteger(0);

        FinalizerWatchdogDaemon() {
            super("FinalizerWatchdogDaemon");
        }

        void resetTimeouts() {
            observedReferenceQueueTimeouts.lazySet(0);
        }

        @Override public void runInternal() {
            while (isRunning()) {
                if (!sleepUntilNeeded()) {
                    // We have been interrupted, need to see if this daemon has been stopped.
                    continue;
                }
                final TimeoutException exception = waitForProgress();
                if (exception != null && !VMDebug.isDebuggerConnected()) {
                    timedOut(exception);
                    break;
                }
            }
        }

        /**
         * Wait until something is ready to be finalized.
         * Return false if we have been interrupted
         * See also http://code.google.com/p/android/issues/detail?id=22778.
         */
        private synchronized boolean sleepUntilNeeded() {
            while (activeWatchees == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Daemon.stop may have interrupted us.
                    return false;
                } catch (OutOfMemoryError e) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Notify daemon that it's OK to sleep until notified that something is ready to be
         * finalized.
         */
        private synchronized void monitoringNotNeeded(int whichDaemon) {
            activeWatchees &= ~whichDaemon;
        }

        /**
         * Notify daemon that there is something ready to be finalized.
         */
        private synchronized void monitoringNeeded(int whichDaemon) {
            int oldWatchees = activeWatchees;
            activeWatchees |= whichDaemon;

            if (oldWatchees == 0) {
                notify();
            }
        }

        private synchronized boolean isActive(int whichDaemon) {
            return (activeWatchees & whichDaemon) != 0;
        }

        /**
         * Sleep for the given number of nanoseconds, or slightly longer.
         * @return false if we were interrupted.
         */
        private boolean sleepForNanos(long durationNanos) {
            // It's important to base this on nanoTime(), not currentTimeMillis(), since
            // the former stops counting when the processor isn't running.
            long startNanos = System.nanoTime();
            while (true) {
                long elapsedNanos = System.nanoTime() - startNanos;
                long sleepNanos = durationNanos - elapsedNanos;
                if (sleepNanos <= 0) {
                    return true;
                }
                // Ensure the nano time is always rounded up to the next whole millisecond,
                // ensuring the delay is >= the requested delay.
                long sleepMillis = (sleepNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI;
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    if (!isRunning()) {
                        return false;
                    }
                } catch (OutOfMemoryError ignored) {
                    if (!isRunning()) {
                        return false;
                    }
                }
            }
        }


        /**
         * Return null (normal case) or an exception describing what timed out.
         * Wait VMRuntime.getFinalizerTimeoutMs.  If the FinalizerDaemon took essentially the
         * whole time processing a single reference, or the ReferenceQueueDaemon failed to make
         * visible progress during that time, return an exception.  Only called from a single
         * thread.
         */
        private TimeoutException waitForProgress() {
            if (finalizerTimeoutNs == 0) {
                finalizerTimeoutNs =
                        NANOS_PER_MILLI * VMRuntime.getRuntime().getFinalizerTimeoutMs();
                // Temporary app backward compatibility. Remove eventually.
                MAX_FINALIZE_NANOS = finalizerTimeoutNs;
            }
            boolean monitorFinalizer = false;
            int finalizerStartCount = 0;
            if (isActive(FINALIZER_DAEMON)) {
                monitorFinalizer = true;
                finalizerStartCount = FinalizerDaemon.INSTANCE.progressCounter.get();
            }
            boolean monitorRefQueue = false;
            int refQueueStartCount = 0;
            if (isActive(RQ_DAEMON)) {
                monitorRefQueue = true;
                refQueueStartCount = ReferenceQueueDaemon.INSTANCE.progressCounter.get();
            }
            // Avoid remembering object being finalized, so as not to keep it alive.
            final long startMillis = System.currentTimeMillis();
            final long startNanos = System.nanoTime();

            if (!sleepForNanos(finalizerTimeoutNs)) {
                // Don't report possibly spurious timeout if we are interrupted.
                return null;
            }
            if (FinalizerDaemon.INSTANCE.progressCounter.get() == finalizerStartCount
                && monitorFinalizer && isActive(FINALIZER_DAEMON)) {
                // We assume that only remove() and doFinalize() may take time comparable to the
                // finalizer timeout.
                // We observed neither the effect of the monitoringNotNeeded() nor the increment
                // preceding a later wakeUp. Any remove() call by the FinalizerDaemon during our
                // sleep interval must have been followed by a monitoringNeeded() call before we
                // checked activeCallees.  But then we would have seen the counter increment.
                // Thus there cannot have been such a remove() call.
                // The FinalizerDaemon must not have progressed (from either the beginning or the
                // last progressCounter increment) to either the next increment or
                // monitoringNotNeeded() call.
                // Thus we must have taken essentially the whole finalizerTimeoutMs in a single
                // doFinalize() call.  Thus it's OK to time out.  finalizingObject was set just
                // before the counter increment, which preceded the doFinalize() call.  Thus we
                // are guaranteed to get the correct finalizing value below, unless doFinalize()
                // just finished as we were timing out, in which case we may get null or a later
                // one.  In this last case, we are very likely to discard it below.
                Object finalizing = FinalizerDaemon.INSTANCE.finalizingObject;
                System.logW("Watchdog: Considering throwing exception",
                    finalizerTimeoutException(finalizing));
                System.logW("Watchdog: Millis elapsed so far: "
                    + (System.currentTimeMillis() - startMillis));
                System.logW("Watchdog: Nanos so far: " + (System.nanoTime() - startNanos));
                sleepForNanos(500 * NANOS_PER_MILLI);
                // Recheck to make it even less likely we report the wrong finalizing object in
                // the case which a very slow finalization just finished as we were timing out.
                if (isActive(FINALIZER_DAEMON)
                        && FinalizerDaemon.INSTANCE.progressCounter.get() == finalizerStartCount) {
                    System.logE("Was finalizing " + finalizingObjectAsString(finalizing)
                        + ", now finalizing "
                        + finalizingObjectAsString(FinalizerDaemon.INSTANCE.finalizingObject));
                    System.logE("Total elapsed millis: "
                        + (System.currentTimeMillis() - startMillis));
                    System.logE("Total nanos: " + (System.nanoTime() - startNanos));
                    return finalizerTimeoutException(finalizing);
                }
            }
            if (ReferenceQueueDaemon.INSTANCE.progressCounter.get() == refQueueStartCount
                && monitorRefQueue && isActive(RQ_DAEMON)) {
                if (observedReferenceQueueTimeouts.incrementAndGet()
                        > TOLERATED_REFERENCE_QUEUE_TIMEOUTS) {
                    return refQueueTimeoutException(
                            ReferenceQueueDaemon.INSTANCE.currentlyProcessing());
                }
            }
            return null;
        }

        private static TimeoutException finalizerTimeoutException(Object object) {
            StringBuilder messageBuilder = new StringBuilder();

            if (object instanceof Cleaner.Cleanable) {
                messageBuilder.append(VH_ACTION.get(object).getClass().getName());
            } else {
                messageBuilder.append(object.getClass().getName()).append(".finalize()");
            }

            messageBuilder.append(" timed out after ")
                    .append(VMRuntime.getRuntime().getFinalizerTimeoutMs() / 1000)
                    .append(" seconds");
            TimeoutException syntheticException = new TimeoutException(messageBuilder.toString());
            // We use the stack from where finalize() was running to show where it was stuck.
            syntheticException.setStackTrace(FinalizerDaemon.INSTANCE.getStackTrace());
            return syntheticException;
        }

        private static String finalizingObjectAsString(Object obj) {
            if (obj instanceof Cleaner.Cleanable) {
                return VH_ACTION.get(obj).toString();
            } else {
                return obj.toString();
            }
        }

        private static TimeoutException refQueueTimeoutException(ReferenceQueue rq) {
            String message = "ReferenceQueueDaemon timed out while targeting " + rq;
            return new TimeoutException(message);
        }

        private static void timedOut(TimeoutException exception) {
            // Send SIGQUIT to get native stack traces.
            try {
                Os.kill(Os.getpid(), OsConstants.SIGQUIT);
                // Sleep a few seconds to let the stack traces print.
                Thread.sleep(5000);
            } catch (Exception e) {
                System.logE("failed to send SIGQUIT", e);
            } catch (OutOfMemoryError ignored) {
                // May occur while trying to allocate the exception.
            }

            // Ideally, we'd want to do this if this Thread had no handler to dispatch to.
            // Unfortunately, it's extremely to messy to query whether a given Thread has *some*
            // handler to dispatch to, either via a handler set on itself, via its ThreadGroup
            // object or via the defaultUncaughtExceptionHandler.
            //
            // As an approximation, we log by hand and exit if there's no pre-exception handler nor
            // a default uncaught exception handler.
            //
            // Note that this condition will only ever be hit by ART host tests and standalone
            // dalvikvm invocations. All zygote forked process *will* have a pre-handler set
            // in RuntimeInit and they cannot subsequently override it.
            if (Thread.getUncaughtExceptionPreHandler() == null &&
                    Thread.getDefaultUncaughtExceptionHandler() == null) {
                // If we have no handler, log and exit.
                System.logE(exception.getMessage(), exception);
                System.exit(2);
            }

            // Otherwise call the handler to do crash reporting.
            // We don't just throw because we're not the thread that
            // timed out; we're the thread that detected it.
            Thread.currentThread().dispatchUncaughtException(exception);
        }
    }

    // Adds a heap trim task to the heap event processor, not called from java. Left for
    // compatibility purposes due to reflection.
    @UnsupportedAppUsage
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

        @Override public void runInternal() {
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
