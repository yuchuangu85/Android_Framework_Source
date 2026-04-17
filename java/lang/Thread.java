/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (c) 1994, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import dalvik.annotation.compat.VersionCodes;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import jdk.internal.event.ThreadSleepEvent;
import jdk.internal.misc.TerminatingThreadLocal;
import jdk.internal.misc.Unsafe;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Hidden;
import jdk.internal.vm.annotation.IntrinsicCandidate;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.StackableScope;
import jdk.internal.vm.ThreadContainer;
import jdk.internal.vm.annotation.Stable;
import sun.nio.ch.Interruptible;
import sun.security.util.SecurityConstants;

import dalvik.annotation.optimization.NeverInline;
import dalvik.system.VMRuntime;
import dalvik.system.VirtualThreadContext;
import dalvik.system.VirtualThreadParkingError;
import dalvik.system.VirtualThreadParkedStates;
import dalvik.system.VMStack;

import libcore.util.EmptyArray;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;

/**
 * A <i>thread</i> is a thread of execution in a program. The Java
 * Virtual Machine allows an application to have multiple threads of
 * execution running concurrently.
 * <p>
 * Every thread has a priority. Threads with higher priority are
 * executed in preference to threads with lower priority. Each thread
 * may or may not also be marked as a daemon. When code running in
 * some thread creates a new {@code Thread} object, the new
 * thread has its priority initially set equal to the priority of the
 * creating thread, and is a daemon thread if and only if the
 * creating thread is a daemon.
 * <p>
 * When a Java Virtual Machine starts up, there is usually a single
 * non-daemon thread (which typically calls the method named
 * {@code main} of some designated class). The Java Virtual
 * Machine continues to execute threads until either of the following
 * occurs:
 * <ul>
 * <li>The {@code exit} method of class {@code Runtime} has been
 *     called and the security manager has permitted the exit operation
 *     to take place.
 * <li>All threads that are not daemon threads have died, either by
 *     returning from the call to the {@code run} method or by
 *     throwing an exception that propagates beyond the {@code run}
 *     method.
 * </ul>
 * <p>
 * There are two ways to create a new thread of execution. One is to
 * declare a class to be a subclass of {@code Thread}. This
 * subclass should override the {@code run} method of class
 * {@code Thread}. An instance of the subclass can then be
 * allocated and started. For example, a thread that computes primes
 * larger than a stated value could be written as follows:
 * <hr><blockquote><pre>
 *     class PrimeThread extends Thread {
 *         long minPrime;
 *         PrimeThread(long minPrime) {
 *             this.minPrime = minPrime;
 *         }
 *
 *         public void run() {
 *             // compute primes larger than minPrime
 *             &nbsp;.&nbsp;.&nbsp;.
 *         }
 *     }
 * </pre></blockquote><hr>
 * <p>
 * The following code would then create a thread and start it running:
 * <blockquote><pre>
 *     PrimeThread p = new PrimeThread(143);
 *     p.start();
 * </pre></blockquote>
 * <p>
 * The other way to create a thread is to declare a class that
 * implements the {@code Runnable} interface. That class then
 * implements the {@code run} method. An instance of the class can
 * then be allocated, passed as an argument when creating
 * {@code Thread}, and started. The same example in this other
 * style looks like the following:
 * <hr><blockquote><pre>
 *     class PrimeRun implements Runnable {
 *         long minPrime;
 *         PrimeRun(long minPrime) {
 *             this.minPrime = minPrime;
 *         }
 *
 *         public void run() {
 *             // compute primes larger than minPrime
 *             &nbsp;.&nbsp;.&nbsp;.
 *         }
 *     }
 * </pre></blockquote><hr>
 * <p>
 * The following code would then create a thread and start it running:
 * <blockquote><pre>
 *     PrimeRun p = new PrimeRun(143);
 *     new Thread(p).start();
 * </pre></blockquote>
 * <p>
 * Every thread has a name for identification purposes. More than
 * one thread may have the same name. If a name is not specified when
 * a thread is created, a new name is generated for it.
 * <p>
 * Unless otherwise noted, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be
 * thrown.
 *
 * @see     Runnable
 * @see     Runtime#exit(int)
 * @see     #run()
 * @see     #stop()
 * @since   1.0
 */
public class Thread implements Runnable {
    // Android-removed: registerNatives() not used on Android.
    /*
    /* Make sure registerNatives is the first thing <clinit> does. *
    private static native void registerNatives();
    static {
        registerNatives();
    }
    */

    // BEGIN Android-added: Android specific fields lock, nativePeer.
    /**
     * The synchronization object responsible for this thread's join/sleep/park operations.
     */
    private final Object lock = new Object();

    /**
     * Reference to the native thread object.
     *
     * <p>Is 0 if the native thread has not yet been created/started, or has been destroyed.
     */
    private volatile long nativePeer;
    // END Android-added: Android specific fields lock, nativePeer.

    private volatile String name;

    // Android-changed: Cache Posix niceness in addition to Java thread priority.
    // The setpriority() call sets both. We have Android-specific means to set the latter.
    // They differ in two ways:
    //  1. They use a different scale: 19 .. -20 vs 1 .. 10. Not all Linux niceness values can
    //    be set via setPriority().
    //  2. Priority values are inherited by child threads. Niceness values are reset as implied
    //    by the current priority value in Java-created children.
    // Whenever we set the actual OS-level priority. it is set to the current niceness value.
    // The thread itself can restore the OS priority without synchronization by
    //  1. reading niceness.
    //  2. Calling Posix setpriority on the result
    //  3. re-reading niceness and repeating if it changed in the interim.
    //  If niceness was changed by another thread before the last step, we restore that value.
    //  If it is changed after that, the Java.setPriority() call will do the right thing.
    // For this to work, we need to ensure, among other things, that a Posix setpriority call and
    // a subsequent read of niceness are not reordered. Such guarantees are generally unclear; we
    // assume that consistently treating niceness as Java `volatile` / C++ `seq_cst` suffices.
    private volatile int niceness;

    private int priority;  // Only for reading via reflection and for unstarted threads. Avoid.
                           // Once there is no need for reflective access, and we no longer need
                           // S compatibility, remove.

    // cachedPriorityForNiceness[n + PFN_INDEX_OFFSET] = 0 or Java priority for niceness n.
    private static final byte[] cachedPriorityForNiceness = new byte[40];
    static final int PFN_INDEX_OFFSET = 20;

    /* Whether or not to single_step this thread. */
    private boolean     single_step;

    /* Whether or not the thread is a daemon thread. */
    private boolean daemon = false;

    /* Interrupt state of the thread - read/written directly by JVM */
    // Android-removed: Remove unused field.
    // private volatile boolean interrupted;

    /* Fields reserved for exclusive use by the JVM */
    private boolean stillborn = false;

    /*
     * Reserved for exclusive use by the JVM. The historically named
     * `eetop` holds the address of the underlying VM JavaThread, and is set to
     * non-zero when the thread is started, and reset to zero when the thread terminates.
     * A non-zero value indicates this thread isAlive().
     */
    private volatile long eetop;

    /* What will be run. */
    private Runnable target;

    /* The group of this thread */
    private ThreadGroup group;

    /* The context ClassLoader for this thread */
    private ClassLoader contextClassLoader;

    /* The inherited AccessControlContext of this thread */
    @SuppressWarnings("removal")
    private AccessControlContext inheritedAccessControlContext;

    /* For autonumbering anonymous threads. */
    private static int threadInitNumber;
    private static synchronized int nextThreadNum() {
        return threadInitNumber++;
    }

    /* ThreadLocal values pertaining to this thread. This map is maintained
     * by the ThreadLocal class. */
    ThreadLocal.ThreadLocalMap threadLocals = null;

    /*
     * InheritableThreadLocal values pertaining to this thread. This map is
     * maintained by the InheritableThreadLocal class.
     */
    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;

    /*
     * The requested stack size for this thread, or 0 if the creator did
     * not specify a stack size.  It is up to the VM to do whatever it
     * likes with this number; some VMs will ignore it.
     */
    private final long stackSize;

    // BEGIN Android-changed: Keep track of whether this thread was unparked while not alive.
    /*
    /*
     * JVM-private state that persists after native thread termination.
     *
    private long nativeParkEventPointer;
    */
    /**
     * Indicates whether this thread was unpark()ed while not alive, in which case start()ing
     * it should leave it in unparked state. This field is read and written by native code in
     * the runtime, guarded by thread_list_lock. See http://b/28845097#comment49
     */
    private boolean unparkedBeforeStart;
    // END Android-changed: Keep track of whether this thread was unparked while not alive.

    /*
     * Thread ID
     */
    private final long tid;

    /* For generating thread ID */
    private static long threadSeqNumber;

    private static synchronized long nextThreadID() {
        return ++threadSeqNumber;
    }

    // Android-added: The concept of "system-daemon" threads. See java.lang.Daemons.
    /** True if this thread is managed by {@link Daemons}. */
    private boolean systemDaemon = false;

    /* Java thread status for tools,
     * initialized to indicate thread 'not yet started'
     */

    // BEGIN Android-changed: Replace unused threadStatus field with started field.
    // Upstream this is modified by the native code and read in the start() and getState() methods
    // but in Android it is unused. The threadStatus is essentially an internal representation of
    // the Thread.State enum. Android uses two sources for that information, the native thread
    // state and the started field. The reason two sources are needed is because the native thread
    // is created when the thread is started and destroyed when the thread is stopped. That means
    // that the native thread state does not exist before the Thread has started (in State.NEW) or
    // after it has been stopped (in State.TERMINATED). In that case (i.e. when the nativePeer = 0)
    // the started field differentiates between the two states, i.e. if started = false then the
    // thread is in State.NEW and if started = true then the thread is in State.TERMINATED.
    // private volatile int threadStatus = 0;
    /**
     * True if the the Thread has been started, even it has since been stopped.
     */
    boolean started = false;
    // END Android-changed: Replace unused threadStatus field with started field.

    /**
     * The argument supplied to the current call to
     * java.util.concurrent.locks.LockSupport.park.
     * Set by (private) java.util.concurrent.locks.LockSupport.setBlocker
     * Accessed using java.util.concurrent.locks.LockSupport.getBlocker
     */
    volatile Object parkBlocker;

    /* The object in which this thread is blocked in an interruptible I/O
     * operation, if any.  The blocker's interrupt method should be invoked
     * after setting this thread's interrupt status.
     */
    volatile Interruptible blocker;
    private final Object blockerLock = new Object();

    /**
     * Starts a virtual thread. Compared to {@linkplain #startVirtualThread(Runnable)}, it returns
     * the carrier thread. This is useful for internal testing and verifying the states of a
     * carrier thread.
     *
     * @hide
     */
    public static Thread startVirtual(Runnable task) {
        Objects.requireNonNull(task);
        VirtualThreadContext vContext = new VirtualThreadContext(task, nextThreadID());
        return Thread.startVirtual(vContext);
    }

    /**
     * Unpark a virtual thread if it's parked.
     * Start a new carrier thread if the virtual thread is pinned.
     *
     * @throws IllegalStateException if the virtual thread isn't parked.
     * @hide
     */
    public static Thread unparkVirtual(VirtualThreadContext context) {
        if (!context.isParked()) {
            throw new IllegalStateException("This virtual thread isn't parked.");
        }
        if (context.isPinned()) {
            return context.unparkOnCarrierThread();
        } else {
            return Thread.startVirtual(context);
        }
    }

    private static Thread startVirtual(VirtualThreadContext context) {
        if (!com.android.art.flags.Flags.virtualThreadImplV1()) {
            throw new UnsupportedOperationException("Virtual Thread isn't supported.");
        }
        Thread carrier = new Thread(context, context.carrierName);
        carrier.start();
        return carrier;
    }

    /**
     * @hide
     */
    public static void parkVirtual() {
        // The bytecode generated for this method may keep a reference to the carrier
        // java.lang.Thread object for the lifetime of this method. To avoid this, we invoke
        // getCurrentVirtualThreadContext() to get the context, and mark this method as
        // @NeverInline, which ensures this method is not inlined by dexers / JIT / AOT.
        VirtualThreadContext context = getCurrentVirtualThreadContext();
        VirtualThreadParkedStates parkedStates = new VirtualThreadParkedStates();
        // TODO: Consider avoiding passing from java, but getting the static instance from ART.
        VirtualThreadParkingError error = VirtualThreadParkingError.INSTANCE;
        parkVirtualInternal(context, parkedStates, error);
        // If the virtual thread is pinned, ART sets the state in VirtualThreadContext.
        // Now, we call the regular java code to park the carrier thread.
        context.parkOnCarrierThreadIfPinned();
    }

    private static native void parkVirtualInternal(VirtualThreadContext context,
            VirtualThreadParkedStates parkedStates, VirtualThreadParkingError error);

    /**
     *
     * Annotated with @NeverInline to avoid the reference to carrier java.lang.thread object.
     * @hide
     */
    @NeverInline
    public static VirtualThreadContext getCurrentVirtualThreadContext() {
        Thread t = Thread.currentThread();
        return t.getVirtualThreadContext();
    }

    /** @hide */
    @FastNative
    public static native int acquireThinLockId();

    /** @hide */
    @FastNative
    public static native void releaseThinLockId(int id);

    /**
     * @hide
     */
    public VirtualThreadContext getVirtualThreadContext() {
        if (!(target instanceof VirtualThreadContext context)) {
            throw new IllegalThreadStateException(
                    "getVirtualThreadContext() can't be called on a regular thread.");
        }
        return context;
    }

    // Android-changed: Make blockedOn() @hide public, for internal use.
    // Changed comment to reflect usage on Android
    /* Set the blocker field; invoked via jdk.internal.access.SharedSecrets
     * from java.nio code
     */
    /** @hide */
    public void blockedOn(Interruptible b) {
        synchronized (blockerLock) {
            blocker = b;
        }
    }

    /**
     * The minimum priority that a thread can have.
     */
    public static final int MIN_PRIORITY = 1;

    /**
     * The default priority that is assigned to a thread.
     */
    public static final int NORM_PRIORITY = 5;

    /**
     * The maximum priority that a thread can have.
     */
    public static final int MAX_PRIORITY = 10;

    /*
     * Current inner-most continuation.
     */
    private Continuation cont;

    /**
     * Returns the current continuation.
     * @hide
     */
    public Continuation getContinuation() {
        return cont;
    }

    /**
     * Sets the current continuation.
     * @hide
     */
    public void setContinuation(Continuation cont) {
        this.cont = cont;
    }

    /**
     * Returns the Thread object for the current platform thread. If the
     * current thread is a virtual thread then this method returns the carrier.
     * @hide
     */
    @IntrinsicCandidate
    @FastNative
    static native Thread currentCarrierThread();

    /**
     * Returns a reference to the currently executing thread object.
     *
     * @return  the currently executing thread.
     */
    @IntrinsicCandidate
    @FastNative
    public static native Thread currentThread();


    /**
     * Sets the Thread object to be returned by Thread.currentThread().
     */
    @IntrinsicCandidate
    final void setCurrentThread(Thread thread) {
        // Perform sanity check for the upstream usage of this instance method.
        // TODO: Avoid this check by replacing all call sites with the static method.
        if (this != currentCarrierThread()) {
            throw new WrongThreadException("setCurrentThread(Thread) can only be called on the "
                    + "current carrier thread.");
        }

        if (thread != this && !(thread instanceof VirtualThread)) {
            throw new IllegalArgumentException("Must be a VirtualThread or "
                    + "the current carrier thread.");
        }

        setCurrentThreadNative(thread);
    }

    /**
     * Set the current thread in the thread-local storage.
     */
    @FastNative
    private native static void setCurrentThreadNative(Thread thread);

    /**
     * A hint to the scheduler that the current thread is willing to yield
     * its current use of a processor. The scheduler is free to ignore this
     * hint.
     *
     * <p> Yield is a heuristic attempt to improve relative progression
     * between threads that would otherwise over-utilise a CPU. Its use
     * should be combined with detailed profiling and benchmarking to
     * ensure that it actually has the desired effect.
     *
     * <p> It is rarely appropriate to use this method. It may be useful
     * for debugging or testing purposes, where it may help to reproduce
     * bugs due to race conditions. It may also be useful when designing
     * concurrency control constructs such as the ones in the
     * {@link java.util.concurrent.locks} package.
     */
    public static void yield() {
        if (currentThread() instanceof VirtualThread vthread) {
            vthread.tryYield();
        } else {
            yield0();
        }
    }

    private static native void yield0();

    /**
     * Causes the currently executing thread to sleep (temporarily cease
     * execution) for the specified number of milliseconds, subject to
     * the precision and accuracy of system timers and schedulers. The thread
     * does not lose ownership of any monitors.
     *
     * @param  millis
     *         the length of time to sleep in milliseconds
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    // BEGIN Android-changed: Implement sleep() methods using a shared native implementation.
    public static void sleep(long millis) throws InterruptedException {
        sleep(millis, 0);
    }

    @FastNative
    private static native void sleep(Object lock, long millis, int nanos)
        throws InterruptedException;
    // END Android-changed: Implement sleep() methods using a shared native implementation.

    /**
     * Causes the currently executing thread to sleep (temporarily cease
     * execution) for the specified number of milliseconds plus the specified
     * number of nanoseconds, subject to the precision and accuracy of system
     * timers and schedulers. The thread does not lose ownership of any
     * monitors.
     *
     * @param  millis
     *         the length of time to sleep in milliseconds
     *
     * @param  nanos
     *         {@code 0-999999} additional nanoseconds to sleep
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative, or the value of
     *          {@code nanos} is not in the range {@code 0-999999}
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public static void sleep(long millis, int nanos)
    throws InterruptedException {
        // BEGIN Android-changed: Improve exception messages.
        /*
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException(
                                "nanosecond timeout value out of range");
        }
        */
        if (millis < 0) {
            throw new IllegalArgumentException("millis < 0: " + millis);
        }
        if (nanos < 0) {
            throw new IllegalArgumentException("nanos < 0: " + nanos);
        }
        if (nanos > 999999) {
            throw new IllegalArgumentException("nanos > 999999: " + nanos);
        }
        // END Android-changed: Improve exception messages.

        // BEGIN Android-changed: Implement sleep() methods using a shared native implementation.
        // Attempt nanosecond rather than millisecond accuracy for sleep();
        // RI code rounds up by 1 millisecond.
        /*
        if (nanos > 0 && millis < Long.MAX_VALUE) {
            millis++;
        }

        sleep(millis);
        */

        // The JLS 3rd edition, section 17.9 says: "...sleep for zero
        // time...need not have observable effects."
        if (millis == 0 && nanos == 0) {
            // ...but we still have to handle being interrupted.
            if (Thread.interrupted()) {
              throw new InterruptedException();
            }
            return;
        }

        final long totalNanos;
        if (millis >= Long.MAX_VALUE / NANOS_PER_MILLI - 1L) {
            // > 292 years. Avoid overflow by capping it at roughly 292 years.
            totalNanos = Long.MAX_VALUE;
        } else {
            totalNanos = (millis * NANOS_PER_MILLI) + nanos;
        }
        // END Android-changed: Implement sleep() methods using a shared native implementation.

        if (currentThread() instanceof VirtualThread vthread) {
            vthread.sleepNanos(totalNanos);
        } else {
            sleep0(totalNanos);
        }
    }

    private static final int NANOS_PER_MILLI = 1000000;

    // Android-changed: Implement sleep() methods using a shared native implementation.
    // private static native void sleep0(long nanos) throws InterruptedException;
    private static void sleep0(long durationNanos) throws InterruptedException {
        long startNanos = System.nanoTime();

        Object lock = currentThread().lock;

        // The native sleep(...) method actually does a monitor wait, which may return
        // early, so loop until sleep duration passes. The monitor is only notified when
        // we exit, which can't happen while we're sleeping.
        synchronized (lock) {
            for (long elapsed = 0L; elapsed < durationNanos;
                    elapsed = System.nanoTime() - startNanos) {
                final long remaining = durationNanos - elapsed;
                long millis = remaining / NANOS_PER_MILLI;
                int nanos = (int) (remaining % NANOS_PER_MILLI);
                sleep(lock, millis, nanos);
            }
        }
    }

    /**
     * Causes the currently executing thread to sleep (temporarily cease
     * execution) for the specified duration, subject to the precision and
     * accuracy of system timers and schedulers. This method is a no-op if
     * the duration is {@linkplain Duration#isNegative() negative}.
     *
     * @param  duration
     *         the duration to sleep
     *
     * @throws  InterruptedException
     *          if the current thread is interrupted while sleeping. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     *
     * @since 19
     */
    public static void sleep(Duration duration) throws InterruptedException {
        long nanos = NANOSECONDS.convert(duration);  // MAX_VALUE if > 292 years
        if (nanos < 0) {
            return;
        }

        // Android-added: Handle nanos == 0 case.
        // The JLS 3rd edition, section 17.9 says: "...sleep for zero
        // time...need not have observable effects."
        if (nanos == 0) {
            // ...but we still have to handle being interrupted.
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            return;
        }

        ThreadSleepEvent event = beforeSleep(nanos);
        try {
            if (currentThread() instanceof VirtualThread vthread) {
                vthread.sleepNanos(nanos);
            } else {
                sleep0(nanos);
            }
        } finally {
            afterSleep(event);
        }
    }


    /**
     * Called before sleeping to create a jdk.ThreadSleep event.
     */
    private static ThreadSleepEvent beforeSleep(long nanos) {
        ThreadSleepEvent event = null;
        if (ThreadSleepEvent.isTurnedOn()) {
            try {
                event = new ThreadSleepEvent();
                event.time = nanos;
                event.begin();
            } catch (OutOfMemoryError e) {
                event = null;
            }
        }
        return event;
    }

    /**
     * Called after sleeping to commit the jdk.ThreadSleep event.
     */
    private static void afterSleep(ThreadSleepEvent event) {
        if (event != null) {
            try {
                event.commit();
            } catch (OutOfMemoryError e) {
                // ignore
            }
        }
    }

    /**
     * Indicates that the caller is momentarily unable to progress, until the
     * occurrence of one or more actions on the part of other activities. By
     * invoking this method within each iteration of a spin-wait loop construct,
     * the calling thread indicates to the runtime that it is busy-waiting.
     * The runtime may take action to improve the performance of invoking
     * spin-wait loop constructions.
     *
     * @apiNote
     * As an example consider a method in a class that spins in a loop until
     * some flag is set outside of that method. A call to the {@code onSpinWait}
     * method should be placed inside the spin loop.
     * <pre>{@code
     *     class EventHandler {
     *         volatile boolean eventNotificationNotReceived;
     *         void waitForEventAndHandleIt() {
     *             while ( eventNotificationNotReceived ) {
     *                 java.lang.Thread.onSpinWait();
     *             }
     *             readAndProcessEvent();
     *         }
     *
     *         void readAndProcessEvent() {
     *             // Read event from some source and process it
     *              . . .
     *         }
     *     }
     * }</pre>
     * <p>
     * The code above would remain correct even if the {@code onSpinWait}
     * method was not called at all. However on some architectures the Java
     * Virtual Machine may issue the processor instructions to address such
     * code patterns in a more beneficial way.
     *
     * @since 9
     */
    @IntrinsicCandidate
    public static void onSpinWait() {}

    /**
     * Initializes a Thread.
     *
     * @param g the Thread group
     * @param target the object whose run() method gets called
     * @param name the name of the new Thread
     * @param stackSize the desired stack size for the new thread, or
     *        zero to indicate that this parameter is to be ignored.
     * @param acc the AccessControlContext to inherit, or
     *            AccessController.getContext() if null
     * @param inheritThreadLocals if {@code true}, inherit initial values for
     *            inheritable thread-locals from the constructing thread
     */
    @SuppressWarnings("removal")
    private Thread(ThreadGroup g, Runnable target, String name,
                   long stackSize, AccessControlContext acc,
                   boolean inheritThreadLocals) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }

        this.name = name;

        Thread parent = currentThread();
        // Android-removed: SecurityManager stubbed out on Android.
        // SecurityManager security = System.getSecurityManager();
        if (g == null) {
            // Android-changed: SecurityManager stubbed out on Android.
            /*
            /* Determine if it's an applet or not *

            /* If there is a security manager, ask the security manager
               what to do. *
            if (security != null) {
                g = security.getThreadGroup();
            }

            /* If the security manager doesn't have a strong opinion
               on the matter, use the parent thread group. *
            if (g == null) {
            */
                g = parent.getThreadGroup();
            // }
        }

        // Android-removed: SecurityManager stubbed out on Android.
        /*
        /* checkAccess regardless of whether or not threadgroup is
           explicitly passed in. *
        g.checkAccess();

        /*
         * Do we have the required permissions?
         *
        if (security != null) {
            if (isCCLOverridden(getClass())) {
                security.checkPermission(
                        SecurityConstants.SUBCLASS_IMPLEMENTATION_PERMISSION);
            }
        }
        */

        g.addUnstarted();

        this.group = g;
        this.daemon = parent.isDaemon();
        this.priority = parent.priority;
        // niceness is not inherited from the parent, but is used to set the actual OS priority.
        // Reset it to correspond to priority.
        this.niceness = nicenessForPriority(this.priority);

        // Android-changed: Moved into init2(Thread, boolean) helper method.
        /*
        if (security == null || isCCLOverridden(parent.getClass()))
            this.contextClassLoader = parent.getContextClassLoader();
        else
            this.contextClassLoader = parent.contextClassLoader;
        this.inheritedAccessControlContext =
                acc != null ? acc : AccessController.getContext();
        */
        this.target = target;
        // Android-removed: The priority parameter is unchecked on Android.
        // It is unclear why this is not being done (b/80180276).
        // setPriority(priority);
        // Android-changed: Moved into init2(Thread, boolean) helper method.
        // if (inheritThreadLocals && parent.inheritableThreadLocals != null)
        //     this.inheritableThreadLocals =
        //         ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
        init2(parent, inheritThreadLocals);

        /* Stash the specified stack size in case the VM cares */
        this.stackSize = stackSize;

        /* Set thread ID */
        this.tid = nextThreadID();
    }



    /**
     * Initializes a platform Thread.
     *
     * @param g the Thread group, can be null
     * @param name the name of the new Thread
     * @param characteristics thread characteristics
     * @param task the object whose run() method gets called
     * @param stackSize the desired stack size for the new thread, or
     *        zero to indicate that this parameter is to be ignored.
     * @param acc the AccessControlContext to inherit, or
     *        AccessController.getContext() if null
     */
    Thread(ThreadGroup g, String name, int characteristics, Runnable task,
            long stackSize, AccessControlContext acc) {
        this(g, task, name, stackSize, acc,
                (characteristics & NO_INHERIT_THREAD_LOCALS) == 0);
    }

    /**
     * Characteristic value signifying that initial values for {@link
     * InheritableThreadLocal inheritable-thread-locals} are not inherited from
     * the constructing thread.
     * See Thread initialization.
     */
    static final int NO_INHERIT_THREAD_LOCALS = 1 << 2;

    /**
     * Returns the context class loader to inherit from the parent thread.
     * See Thread initialization.
     */
    private static ClassLoader contextClassLoader(Thread parent) {
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || isCCLOverridden(parent.getClass())) {
            return parent.getContextClassLoader();
        } else {
            // skip call to getContextClassLoader
            return parent.contextClassLoader;
        }
    }

    /**
     * Initializes a virtual Thread.
     *
     * @param name thread name, can be null
     * @param characteristics thread characteristics
     * @param bound true when bound to an OS thread
     */
    Thread(String name, int characteristics, boolean bound) {
        // Android-changed: Use nextThreadID() for simplicity until we need a off-heap counter.
        // this.tid = ThreadIdentifiers.next();
        this.tid = nextThreadID();
        this.name = (name != null) ? name : "";
        // Android-changed: Android has no SecurityManager.
        // this.inheritedAccessControlContext = Constants.NO_PERMISSIONS_ACC;
        this.inheritedAccessControlContext = AccessController.getContext();

        // thread locals
        if ((characteristics & NO_INHERIT_THREAD_LOCALS) == 0) {
            Thread parent = currentThread();
            ThreadLocal.ThreadLocalMap parentMap = parent.inheritableThreadLocals;
            if (parentMap != null && parentMap.size() > 0) {
                this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parentMap);
            }
            this.contextClassLoader = contextClassLoader(parent);
        } else {
            // default CCL to the system class loader when not inheriting
            this.contextClassLoader = ClassLoader.getSystemClassLoader();
        }

        // Android-changed: TODO(b/346542404): bound to an OS thread.
        /*
        // special value to indicate this is a newly-created Thread
        this.scopedValueBindings = NEW_THREAD_BINDINGS;

        // create a FieldHolder object, needed when bound to an OS thread
        if (bound) {
            ThreadGroup g = Constants.VTHREAD_GROUP;
            int pri = NORM_PRIORITY;
            this.holder = new FieldHolder(g, null, -1, pri, true);
        } else {
            this.holder = null;
        }
        */
        this.stackSize = -1;
    }

    /**
     * Throws CloneNotSupportedException as a Thread can not be meaningfully
     * cloned. Construct a new Thread instead.
     *
     * @throws  CloneNotSupportedException
     *          always
     */
    @Override
    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    /**
     * Generates a thread name of the form {@code Thread-<n>}.
     */
    static String genThreadName() {
        // Android-changed: TODO: Replace nextThreadNum with ThreadNumbering from OpenJDK 21.
        // return "Thread-" + ThreadNumbering.next();
        return "Thread-" + nextThreadNum();
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, null, gname)}, where {@code gname} is a newly generated
     * name. Automatically generated names are of the form
     * {@code "Thread-"+}<i>n</i>, where <i>n</i> is an integer.
     */
    public Thread() {
        this(null, null, "Thread-" + nextThreadNum(), 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, target, gname)}, where {@code gname} is a newly generated
     * name. Automatically generated names are of the form
     * {@code "Thread-"+}<i>n</i>, where <i>n</i> is an integer.
     *
     * @param  target
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this classes {@code run} method does
     *         nothing.
     */
    public Thread(Runnable target) {
        this(null, target, "Thread-" + nextThreadNum(), 0);
    }

    /**
     * Creates a new Thread that inherits the given AccessControlContext
     * but thread-local variables are not inherited.
     * This is not a public constructor.
     */
    Thread(Runnable target, @SuppressWarnings("removal") AccessControlContext acc) {
        this(null, target, "Thread-" + nextThreadNum(), 0, acc, false);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (group, target, gname)} ,where {@code gname} is a newly generated
     * name. Automatically generated names are of the form
     * {@code "Thread-"+}<i>n</i>, where <i>n</i> is an integer.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  target
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     */
    public Thread(ThreadGroup group, Runnable target) {
        this(group, target, "Thread-" + nextThreadNum(), 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, null, name)}.
     *
     * @param   name
     *          the name of the new thread
     */
    public Thread(String name) {
        this(null, null, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (group, null, name)}.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  name
     *         the name of the new thread
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     */
    public Thread(ThreadGroup group, String name) {
        this(group, null, name, 0);
    }

    // BEGIN Android-added: Private constructor - used by the runtime.
    /** @hide */
    Thread(ThreadGroup group, String name, int priority, boolean daemon) {
        this.group = group;
        this.group.addUnstarted();
        // Must be tolerant of threads without a name.
        if (name == null) {
            name = "Thread-" + nextThreadNum();
        }

        // NOTE: Resist the temptation to call setName() here. This constructor is only called
        // by the runtime to construct peers for threads that have attached via JNI and it's
        // undesirable to clobber their natively set name.
        this.name = name;

        this.priority = priority;
        this.niceness = nicenessForPriority(priority);
        this.daemon = daemon;
        init2(currentThread(), true);
        this.stackSize = 0;
        this.tid = nextThreadID();
    }

    // Android-added: Helper method for previous constructor and init(...) method.
    private void init2(Thread parent, boolean inheritThreadLocals) {
        this.contextClassLoader = parent.getContextClassLoader();
        this.inheritedAccessControlContext = AccessController.getContext();
        if (inheritThreadLocals && parent.inheritableThreadLocals != null) {
            this.inheritableThreadLocals =
                    ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
        }
    }
    // END Android-added: Private constructor - used by the runtime.


    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, target, name)}.
     *
     * @param  target
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     */
    public Thread(Runnable target, String name) {
        this(null, target, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object so that it has {@code target}
     * as its run object, has the specified {@code name} as its name,
     * and belongs to the thread group referred to by {@code group}.
     *
     * <p>If there is a security manager, its
     * {@link SecurityManager#checkAccess(ThreadGroup) checkAccess}
     * method is invoked with the ThreadGroup as its argument.
     *
     * <p>In addition, its {@code checkPermission} method is invoked with
     * the {@code RuntimePermission("enableContextClassLoaderOverride")}
     * permission when invoked directly or indirectly by the constructor
     * of a subclass which overrides the {@code getContextClassLoader}
     * or {@code setContextClassLoader} methods.
     *
     * <p>The priority of the newly created thread is set equal to the
     * priority of the thread creating it, that is, the currently running
     * thread. The method {@linkplain #setPriority setPriority} may be
     * used to change the priority to a new value.
     *
     * <p>The newly created thread is initially marked as being a daemon
     * thread if and only if the thread creating it is currently marked
     * as a daemon thread. The method {@linkplain #setDaemon setDaemon}
     * may be used to change whether or not a thread is a daemon.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  target
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group or cannot override the context class loader methods.
     */
    public Thread(ThreadGroup group, Runnable target, String name) {
        this(group, target, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object so that it has {@code target}
     * as its run object, has the specified {@code name} as its name,
     * and belongs to the thread group referred to by {@code group}, and has
     * the specified <i>stack size</i>.
     *
     * <p>This constructor is identical to {@link
     * #Thread(ThreadGroup,Runnable,String)} with the exception of the fact
     * that it allows the thread stack size to be specified.  The stack size
     * is the approximate number of bytes of address space that the virtual
     * machine is to allocate for this thread's stack.  <b>The effect of the
     * {@code stackSize} parameter, if any, is highly platform dependent.</b>
     *
     * <p>On some platforms, specifying a higher value for the
     * {@code stackSize} parameter may allow a thread to achieve greater
     * recursion depth before throwing a {@link StackOverflowError}.
     * Similarly, specifying a lower value may allow a greater number of
     * threads to exist concurrently without throwing an {@link
     * OutOfMemoryError} (or other internal error).  The details of
     * the relationship between the value of the {@code stackSize} parameter
     * and the maximum recursion depth and concurrency level are
     * platform-dependent.  <b>On some platforms, the value of the
     * {@code stackSize} parameter may have no effect whatsoever.</b>
     *
     * <p>The virtual machine is free to treat the {@code stackSize}
     * parameter as a suggestion.  If the specified value is unreasonably low
     * for the platform, the virtual machine may instead use some
     * platform-specific minimum value; if the specified value is unreasonably
     * high, the virtual machine may instead use some platform-specific
     * maximum.  Likewise, the virtual machine is free to round the specified
     * value up or down as it sees fit (or to ignore it completely).
     *
     * <p>Specifying a value of zero for the {@code stackSize} parameter will
     * cause this constructor to behave exactly like the
     * {@code Thread(ThreadGroup, Runnable, String)} constructor.
     *
     * <p><i>Due to the platform-dependent nature of the behavior of this
     * constructor, extreme care should be exercised in its use.
     * The thread stack size necessary to perform a given computation will
     * likely vary from one JRE implementation to another.  In light of this
     * variation, careful tuning of the stack size parameter may be required,
     * and the tuning may need to be repeated for each JRE implementation on
     * which an application is to run.</i>
     *
     * <p>Implementation note: Java platform implementers are encouraged to
     * document their implementation's behavior with respect to the
     * {@code stackSize} parameter.
     *
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  target
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     *
     * @param  stackSize
     *         the desired stack size for the new thread, or zero to indicate
     *         that this parameter is to be ignored.
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     *
     * @since 1.4
     */
    public Thread(ThreadGroup group, Runnable target, String name,
                  long stackSize) {
        this(group, target, name, stackSize, null, true);
    }

    /**
     * Allocates a new {@code Thread} object so that it has {@code target}
     * as its run object, has the specified {@code name} as its name,
     * belongs to the thread group referred to by {@code group}, has
     * the specified {@code stackSize}, and inherits initial values for
     * {@linkplain InheritableThreadLocal inheritable thread-local} variables
     * if {@code inheritThreadLocals} is {@code true}.
     *
     * <p> This constructor is identical to {@link
     * #Thread(ThreadGroup,Runnable,String,long)} with the added ability to
     * suppress, or not, the inheriting of initial values for inheritable
     * thread-local variables from the constructing thread. This allows for
     * finer grain control over inheritable thread-locals. Care must be taken
     * when passing a value of {@code false} for {@code inheritThreadLocals},
     * as it may lead to unexpected behavior if the new thread executes code
     * that expects a specific thread-local value to be inherited.
     *
     * <p> Specifying a value of {@code true} for the {@code inheritThreadLocals}
     * parameter will cause this constructor to behave exactly like the
     * {@code Thread(ThreadGroup, Runnable, String, long)} constructor.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  target
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     *
     * @param  stackSize
     *         the desired stack size for the new thread, or zero to indicate
     *         that this parameter is to be ignored
     *
     * @param  inheritThreadLocals
     *         if {@code true}, inherit initial values for inheritable
     *         thread-locals from the constructing thread, otherwise no initial
     *         values are inherited
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     *
     * @since 9
     */
    public Thread(ThreadGroup group, Runnable target, String name,
                  long stackSize, boolean inheritThreadLocals) {
        this(group, target, name, stackSize, null, inheritThreadLocals);
    }

    /**
     * Creates a virtual thread to execute a task and schedules it to execute.
     *
     * <p> This method is equivalent to:
     * <pre>{@code Thread.ofVirtual().start(task); }</pre>
     *
     * @param task the object to run when the thread executes
     * @return a new, and started, virtual thread
     * @see <a href="#inheritance">Inheritance when creating threads</a>
     * @since 21
     */
    public static Thread startVirtualThread(Runnable task) {
        Objects.requireNonNull(task);
        var thread = ThreadBuilders.newVirtualThread(null, null, 0, task);
        thread.start();
        return thread;
    }

    /**
     * Returns {@code true} if this thread is a virtual thread. A virtual thread
     * is scheduled by the Java virtual machine rather than the operating system.
     *
     * @return {@code true} if this thread is a virtual thread
     *
     * This method always returns false because virtual thread isn't implemented on Android yet.
     * This method is only useful for cross-platform libraries.
     *
     * @since 21
     */
    public final boolean isVirtual() {
        // Android-changed: Android has its own implementation.
        // return (this instanceof BaseVirtualThread);
        return target instanceof VirtualThreadContext ||
                this instanceof BaseVirtualThread;
    }

    /**
     * Causes this thread to begin execution; the Java Virtual Machine
     * calls the {@code run} method of this thread.
     * <p>
     * The result is that two threads are running concurrently: the
     * current thread (which returns from the call to the
     * {@code start} method) and the other thread (which executes its
     * {@code run} method).
     * <p>
     * It is never legal to start a thread more than once.
     * In particular, a thread may not be restarted once it has completed
     * execution.
     *
     * <p><strong>WARNING:</strong> Do not override this method, because the runtime may
     * treat this method {@code final} in the future, because it works closely with the runtime.</p>
     *
     * @throws     IllegalThreadStateException  if the thread was already started.
     * @see        #run()
     * @see        #stop()
     */
    public synchronized void start() {
        /**
         * This method is not invoked for the main method thread or "system"
         * group threads created/set up by the VM. Any new functionality added
         * to this method in the future may have to also be added to the VM.
         *
         * A zero status value corresponds to state "NEW".
         */
        // Android-changed: Replace unused threadStatus field with started field.
        // The threadStatus field is unused on Android.
        // if (threadStatus != 0)
        if (started)
            throw new IllegalThreadStateException();

        /* Notify the group that this thread is about to be started
         * so that it can be added to the group's list of threads
         * and the group's unstarted count can be decremented. */
        group.add(this);

        // Android-changed: Use field instead of local variable.
        // It is necessary to remember the state of this across calls to this method so that it
        // can throw an IllegalThreadStateException if this method is called on an already
        // started thread.
        // boolean started = false;
        started = false;
        try {
            // Android-changed: Use Android specific nativeCreate() method to create/start thread.
            // start0();
            nativeCreate(this, stackSize, daemon);
            started = true;
        } finally {
            try {
                if (!started) {
                    group.threadStartFailed(this);
                }
            } catch (Throwable ignore) {
                /* do nothing. If start0 threw a Throwable then
                  it will be passed up the call stack */
            }
        }
    }

    // Android-added: Thread.start() is overridden in ThreadPoolExecutor. http://b/418924588
    /**
     * {@link Thread#start()} is no longer invoked for threads used by
     * {@link java.util.concurrent.ThreadPoolExecutor} and
     * {@link java.util.concurrent.ForkJoinPool}.
     *
     * Don't override {@link Thread#start()} in this case when the app targets SDK level
     * corresponding to 26Q2, or the overridden method won't be invoked by the
     * runtime.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = VersionCodes.CINNAMON_BUN)
    public static final long OVERRIDDEN_THREAD_START_METHOD = 418924588L;

    /**
     * Schedules this thread to begin execution in the given thread container.
     * @throws IllegalStateException if the container is shutdown or closed
     * @throws IllegalThreadStateException if the thread has already been started
     */
    void start(ThreadContainer container) {
        // Android-changed: App compat with overridden Thread.start() method. http://b/418924588
        if (!isVirtual() && !(VMRuntime.getSdkVersion() >= VersionCodes.CINNAMON_BUN
                && Compatibility.isChangeEnabled(Thread.OVERRIDDEN_THREAD_START_METHOD))) {
            synchronized (this) {
                // Disable this check due to app compat http://b/455404369
                // if (started) {
                //    throw new IllegalThreadStateException();
                // }

                // bind thread to container
                if (this.container != null)
                    throw new IllegalThreadStateException();

                setThreadContainer(container);
                container.onStart(this);
                // Release the monitor for better app compatibility, because an app may not assume
                // the monitor being held.
            }
            try {
                this.start();
            } finally {
                synchronized (this) {
                    if (!started) {
                        container.onExit(this);
                    }
                }
            }
            return;
        }

        // TODO: Minimize code duplication in start().
        synchronized (this) {
            // zero status corresponds to state "NEW".
            // Android-changed: Replace unused threadStatus field with started field.
            // if (holder.threadStatus != 0)
            //     throw new IllegalThreadStateException();
            if (started)
                throw new IllegalThreadStateException();

            // bind thread to container
            if (this.container != null)
                throw new IllegalThreadStateException();

            // Android-added: Add this to the thread group until ART does so in the native side.
            /* Notify the group that this thread is about to be started
             * so that it can be added to the group's list of threads
             * and the group's unstarted count can be decremented. */
            group.add(this);

            setThreadContainer(container);

            // start thread
            // Android-changed: Use field instead of local variable.
            started = false;
            container.onStart(this);  // may throw
            try {
                // scoped values may be inherited
                // Android-removed: ScopedValue isn't supported yet.
                // inheritScopedValueBindings(container);

                // Android-changed: Use Android specific nativeCreate() method to start thread.
                // start0();
                nativeCreate(this, stackSize, daemon);
                started = true;
            } finally {
                if (!started) {
                    container.onExit(this);
                }
                // Android-added: Report start failure until ART does so in the native side.
                try {
                    if (!started) {
                        group.threadStartFailed(this);
                    }
                } catch (Throwable ignore) {
                    /* do nothing. If start0 threw a Throwable then
                      it will be passed up the call stack */
                }
            }
        }
    }

    // Android-changed: Use Android specific nativeCreate() method to create/start thread.
    // The upstream native method start0() only takes a reference to this object and so must obtain
    // the stack size and daemon status directly from the field whereas Android supplies the values
    // explicitly on the method call.
    // private native void start0();
    private native static void nativeCreate(Thread t, long stackSize, boolean daemon);

    /**
     * If this thread was constructed using a separate
     * {@code Runnable} run object, then that
     * {@code Runnable} object's {@code run} method is called;
     * otherwise, this method does nothing and returns.
     * <p>
     * Subclasses of {@code Thread} should override this method.
     *
     * @see     #start()
     * @see     #stop()
     * @see     #Thread(ThreadGroup, Runnable, String)
     */
    @Override
    public void run() {
        if (target != null) {
            target.run();
        }
    }

    @Hidden
    @ForceInline
    final void runWith(Object bindings, Runnable op) {
        // Android-removed: Don't support ScopedValue yet.
        // ensureMaterializedForStackWalk(bindings);
        op.run();
        // Reference.reachabilityFence(bindings);
    }

    /**
     * Null out reference after Thread termination.
     */
    void clearReferences() {
        threadLocals = null;
        inheritableThreadLocals = null;
        inheritedAccessControlContext = null;
        blocker = null;
        uncaughtExceptionHandler = null;
    }


    /**
     * This method is called by the system to give a Thread
     * a chance to clean up before it actually exits.
     */
    private void exit() {
        if (group != null) {
            group.threadTerminated(this);
            group = null;
        }
        /* Aggressively null out all reference fields: see bug 4006245 */
        target = null;
        /* Speed the release of some of these resources */
        clearReferences();
    }

    // Android-changed: Throws UnsupportedOperationException.
    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @deprecated This method was originally designed to force a thread to stop
     *       and throw a {@code ThreadDeath} as an exception. It was inherently unsafe.
     *       Stopping a thread with
     *       Thread.stop causes it to unlock all of the monitors that it
     *       has locked (as a natural consequence of the unchecked
     *       {@code ThreadDeath} exception propagating up the stack).  If
     *       any of the objects previously protected by these monitors were in
     *       an inconsistent state, the damaged objects become visible to
     *       other threads, potentially resulting in arbitrary behavior.  Many
     *       uses of {@code stop} should be replaced by code that simply
     *       modifies some variable to indicate that the target thread should
     *       stop running.  The target thread should check this variable
     *       regularly, and return from its run method in an orderly fashion
     *       if the variable indicates that it is to stop running.  If the
     *       target thread waits for long periods (on a condition variable,
     *       for example), the {@code interrupt} method should be used to
     *       interrupt the wait.
     *       For more information, see
     *       <a href="{@docRoot}/../technotes/guides/concurrency/threadPrimitiveDeprecation.html">Why
     *       are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     */
    @Deprecated(since="1.2")
    public final void stop() {
        /*
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            checkAccess();
            if (this != Thread.currentThread()) {
                security.checkPermission(SecurityConstants.STOP_THREAD_PERMISSION);
            }
        }
        // A zero status value corresponds to "NEW", it can't change to
        // not-NEW because we hold the lock.
        if (threadStatus != 0) {
            resume(); // Wake up thread if it was suspended; no-op otherwise
        }

        // The VM can handle all thread states
        stop0(new ThreadDeath());
        */
        throw new UnsupportedOperationException();
    }

    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @param obj ignored
     *
     * @deprecated This method was originally designed to force a thread to stop
     *        and throw a given {@code Throwable} as an exception. It was
     *        inherently unsafe (see {@link #stop()} for details), and furthermore
     *        could be used to generate exceptions that the target thread was
     *        not prepared to handle.
     *        For more information, see
     *        <a href="{@docRoot}/../technotes/guides/concurrency/threadPrimitiveDeprecation.html">Why
     *        are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     * @removed
     */
    @Deprecated
    public final synchronized void stop(Throwable obj) {
        throw new UnsupportedOperationException();
    }

    /**
     * Interrupts this thread.
     *
     * <p> Unless the current thread is interrupting itself, which is
     * always permitted, the {@link #checkAccess() checkAccess} method
     * of this thread is invoked, which may cause a {@link
     * SecurityException} to be thrown.
     *
     * <p> If this thread is blocked in an invocation of the {@link
     * Object#wait() wait()}, {@link Object#wait(long) wait(long)}, or {@link
     * Object#wait(long, int) wait(long, int)} methods of the {@link Object}
     * class, or of the {@link #join()}, {@link #join(long)}, {@link
     * #join(long, int)}, {@link #sleep(long)}, or {@link #sleep(long, int)}
     * methods of this class, then its interrupt status will be cleared and it
     * will receive an {@link InterruptedException}.
     *
     * <p> If this thread is blocked in an I/O operation upon an {@link
     * java.nio.channels.InterruptibleChannel InterruptibleChannel}
     * then the channel will be closed, the thread's interrupt
     * status will be set, and the thread will receive a {@link
     * java.nio.channels.ClosedByInterruptException}.
     *
     * <p> If this thread is blocked in a {@link java.nio.channels.Selector}
     * then the thread's interrupt status will be set and it will return
     * immediately from the selection operation, possibly with a non-zero
     * value, just as if the selector's {@link
     * java.nio.channels.Selector#wakeup wakeup} method were invoked.
     *
     * <p> If none of the previous conditions hold then this thread's interrupt
     * status will be set. </p>
     *
     * <p> Interrupting a thread that is not alive need not have any effect.
     *
     * @implNote In the JDK Reference Implementation, interruption of a thread
     * that is not alive still records that the interrupt request was made and
     * will report it via {@link #interrupted} and {@link #isInterrupted()}.
     *
     * @throws  SecurityException
     *          if the current thread cannot modify this thread
     *
     * @revised 6.0, 14
     */
    public void interrupt() {
        if (this != Thread.currentThread()) {
            checkAccess();

            // thread may be blocked in an I/O operation
            synchronized (blockerLock) {
                Interruptible b = blocker;
                if (b != null) {
                    // Android-removed: Remove unused the interrupted field.
                    // interrupted = true;
                    interrupt0();  // inform VM of interrupt
                    b.interrupt(this);
                    return;
                }
            }
        }
        // Android-removed: Remove unused the interrupted field.
        // interrupted = true;
        // inform VM of interrupt
        interrupt0();
    }

    /**
     * Tests whether the current thread has been interrupted.  The
     * <i>interrupted status</i> of the thread is cleared by this method.  In
     * other words, if this method were to be called twice in succession, the
     * second call would return false (unless the current thread were
     * interrupted again, after the first call had cleared its interrupted
     * status and before the second call had examined it).
     *
     * @return  {@code true} if the current thread has been interrupted;
     *          {@code false} otherwise.
     * @see #isInterrupted()
     * @revised 6.0, 14
     */
    // Android-changed: Use native interrupted()/isInterrupted() methods.
    // Upstream has one native method for both these methods that takes a boolean parameter that
    // determines whether the interrupted status of the thread should be cleared after reading
    // it. While that approach does allow code reuse it is less efficient/more complex than having
    // a native implementation of each method because:
    // * The pure Java interrupted() method requires two native calls, one to get the current
    //   thread and one to get its interrupted status.
    // * Updating the interrupted flag is more complex than simply reading it. Knowing that only
    //   the current thread can clear the interrupted status makes the code simpler as it does not
    //   need to be concerned about multiple threads trying to clear the status simultaneously.
    /*
    public static boolean interrupted() {
        Thread t = currentThread();
        boolean interrupted = t.interrupted;
        // We may have been interrupted the moment after we read the field,
        // so only clear the field if we saw that it was set and will return
        // true; otherwise we could lose an interrupt.
        if (interrupted) {
            t.interrupted = false;
            clearInterruptEvent();
        }
        return interrupted;
    }
    */
    @CriticalNative
    public static native boolean interrupted();

    // Android-changed: Avoid instance method to clear interrupted status. See #interrupted()
    /*
        final void clearInterrupt() {
        // assert Thread.currentCarrierThread() == this;
        if (interrupted) {
            interrupted = false;
            clearInterruptEvent();
        }
    }
    */
    @SuppressWarnings("CheckReturnValue")
    static void clearCarrierInterrupt() {
        interrupted();
    }

    /**
     * Tests whether this thread has been interrupted.  The <i>interrupted
     * status</i> of the thread is unaffected by this method.
     *
     * @return  {@code true} if this thread has been interrupted;
     *          {@code false} otherwise.
     * @see     #interrupted()
     * @revised 6.0, 14
     */
    // Android-changed: Use native interrupted()/isInterrupted() methods.
    // public boolean isInterrupted() {
    //     return interrupted;
    // }
    @FastNative
    public native boolean isInterrupted();

    // Android-removed: Use native interrupted()/isInterrupted() methods.
    /*
    /**
     * Tests if some Thread has been interrupted.  The interrupted state
     * is reset or not based on the value of ClearInterrupted that is
     * passed.
     *
    @IntrinsicCandidate
    private native boolean isInterrupted(boolean ClearInterrupted);
    */

    // BEGIN Android-changed: Throw UnsupportedOperationException instead of NoSuchMethodError.
    /**
     * Throws {@link UnsupportedOperationException}.
     *
     * @deprecated This method was originally designed to destroy this
     *     thread without any cleanup. Any monitors it held would have
     *     remained locked. However, the method was never implemented.
     *     If if were to be implemented, it would be deadlock-prone in
     *     much the manner of {@link #suspend}. If the target thread held
     *     a lock protecting a critical system resource when it was
     *     destroyed, no thread could ever access this resource again.
     *     If another thread ever attempted to lock this resource, deadlock
     *     would result. Such deadlocks typically manifest themselves as
     *     "frozen" processes. For more information, see
     *     <a href="{@docRoot}/../technotes/guides/concurrency/threadPrimitiveDeprecation.html">
     *     Why are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     * @removed
     * @throws UnsupportedOperationException always
     */
    @Deprecated(since="16", forRemoval=true)
    public void destroy() {
        throw new UnsupportedOperationException();
    }
    // END Android-changed: Throw UnsupportedOperationException instead of NoSuchMethodError.

    final void setInterrupt() {
        assert Thread.currentCarrierThread() == this;

        // Android-removed: Remove unused the interrupted field.
        // if (!interrupted) {
        //     interrupted = true;
        //     interrupt0();  // inform VM of interrupt
        // }
        interrupt0();  // inform VM of interrupt
    }

    /**
     * Tests if this thread is alive. A thread is alive if it has
     * been started and has not yet terminated.
     *
     * @return  {@code true} if this thread is alive;
     *          {@code false} otherwise.
     */
    public final boolean isAlive() {
        return alive();
    }

    /**
     * Returns true if this thread is alive.
     * This method is non-final so it can be overridden.
     */
    boolean alive() {
        // Android-changed: Provide pure Java implementation of isAlive().
        // return eetop != 0;
        return nativePeer != 0;
    }

    // Android-changed: Updated JavaDoc as it always throws an UnsupportedOperationException.
    /**
     * Throws {@link UnsupportedOperationException}.
     *
     * @deprecated   This method has been deprecated, as it is
     *   inherently deadlock-prone.  If the target thread holds a lock on the
     *   monitor protecting a critical system resource when it is suspended, no
     *   thread can access this resource until the target thread is resumed. If
     *   the thread that would resume the target thread attempts to lock this
     *   monitor prior to calling {@code resume}, deadlock results.  Such
     *   deadlocks typically manifest themselves as "frozen" processes.
     *   For more information, see
     *   <a href="{@docRoot}/../technotes/guides/concurrency/threadPrimitiveDeprecation.html">Why
     *   are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     * @removed
     * @throws UnsupportedOperationException always
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void suspend() {
        // Android-changed: Unsupported on Android.
        // checkAccess();
        // suspend0();

        throw new UnsupportedOperationException();
    }

    // Android-changed: Updated JavaDoc as it always throws an UnsupportedOperationException.
    /**
     * Throws {@link UnsupportedOperationException}.
     *
     * @deprecated This method exists solely for use with {@link #suspend},
     *     which has been deprecated because it is deadlock-prone.
     *     For more information, see
     *     <a href="{@docRoot}/../technotes/guides/concurrency/threadPrimitiveDeprecation.html">Why
     *     are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     * @removed
     * @throws UnsupportedOperationException always
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void resume() {
        // Android-changed: Unsupported on Android.
        // checkAccess();
        // resume0();
        throw new UnsupportedOperationException();
    }

    /**
     * Changes the priority of this thread.
     * <p>
     * First the {@code checkAccess} method of this thread is called
     * with no arguments. This may result in throwing a {@code SecurityException}.
     * <p>
     * Otherwise, the priority of this thread is set to the smaller of
     * the specified {@code newPriority} and the maximum permitted
     * priority of the thread's thread group.
     *
     * @param newPriority priority to set this thread to
     * @throws     IllegalArgumentException  If the priority is not in the
     *               range {@code MIN_PRIORITY} to
     *               {@code MAX_PRIORITY}.
     * @throws     SecurityException  if the current thread cannot modify
     *               this thread.
     * @see        #getPriority
     * @see        #checkAccess()
     * @see        #getThreadGroup()
     * @see        #MAX_PRIORITY
     * @see        #MIN_PRIORITY
     * @see        ThreadGroup#getMaxPriority()
     */
    public final void setPriority(int newPriority) {
        checkAccess();
        if (newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY) {
            // Android-changed: Improve exception message when the new priority is out of bounds.
            throw new IllegalArgumentException("Priority out of range: " + newPriority);
        }
        if (!isVirtual()) {
            priority(newPriority);
        }
    }

    void priority(int newPriority) {
        // Android-changed:  Use getThreadGroup() to workaround exit() not being called.
        ThreadGroup g = getThreadGroup();
        if (g != null) {
            int maxPriority = g.getMaxPriority();
            if (newPriority > maxPriority) {
                newPriority = maxPriority;
            }
            // Android-changed: Avoid native call if Thread is not yet started.
            // Pass both priority and niceness, since S workaround requires priority, otherwise we
            // need niceness.
            // setPriority0(holder.priority = newPriority);
            synchronized(this) {
                priority = newPriority;  // Ignored by us if already started.
                niceness = nicenessForPriority(newPriority);
                if (isAlive()) {
                    setPriority0(newPriority, niceness);
                }
            }
        }
    }

    /**
     * Android-added: An internal version of setPriority that takes niceness rather than priority.
     * We do not bounds check. This does affect getPriority() calls. The results of such
     * getPriority() will be limited to [MIN_PRIORITY, MAX_PRIORITY] even if the actual niceness
     * value is outside that range. Such a value may be outside the ThreadGroup limit.
     *
     * @return  Linux errno, 0 on success or if thread has not yet been started.
     *
     * @hide
     */
    public final int setPosixNicenessInternal(int newNiceness) {
        synchronized(this) {
            this.niceness = newNiceness;
            // We do not set the priority field here, since the effect should not be inherited
            // by children.
            if (isAlive()) {
                return setNiceness0(newNiceness);
            }
        }
        return 0;
    }

    /**
     * Android-added: Fast niceness to priority conversion.
     * Return Java priority for Posix niceness `n`, caching previously computed results.
     */
    private int cachingPriorityForNiceness(int n) {
        byte[] pfn = cachedPriorityForNiceness;
        int p = cachedPriorityForNiceness[n + PFN_INDEX_OFFSET];
        if (p == 0) {
            p = priorityForNiceness(n);
            // Data race here is OK by Java rules.
            cachedPriorityForNiceness[n + PFN_INDEX_OFFSET] = (byte) p;
        }
        return p;
    }

    /**
     * Returns this thread's priority.
     *
     * @return  this thread's priority.
     * @see     #setPriority
     */
    public final int getPriority() {
        if (isVirtual()) {
            return Thread.NORM_PRIORITY;
        } else {
            // We return the inheritable priority, even if it does not match niceness.
            // Either option can be confusing, and this matches historical behavior.
            return priority;
        }
    }

    /**
     * Android-added: Access to cached niceness value.
     * Returns this thread's cached niceness value.
     *
     * @hide
     */
    public final int getPosixNicenessInternal() {
      return niceness;
    }

    /**
     * Changes the name of this thread to be equal to the argument {@code name}.
     * <p>
     * First the {@code checkAccess} method of this thread is called
     * with no arguments. This may result in throwing a
     * {@code SecurityException}.
     *
     * @param      name   the new name for this thread.
     * @throws     SecurityException  if the current thread cannot modify this
     *             thread.
     * @see        #getName
     * @see        #checkAccess()
     */
    public final synchronized void setName(String name) {
        checkAccess();
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }

        this.name = name;
        // Android-changed: Use isAlive() not threadStatus to check whether Thread has started.
        // The threadStatus field is not used in Android.
        // if (threadStatus != 0) {
        if (isAlive()) {
            setNativeName(name);
        }
    }

    /**
     * Returns this thread's name.
     *
     * @return  this thread's name.
     * @see     #setName(String)
     */
    public final String getName() {
        return name;
    }

    /**
     * Returns the thread group to which this thread belongs.
     * This method returns null if this thread has died
     * (been stopped).
     *
     * @return  this thread's thread group.
     */
    public final ThreadGroup getThreadGroup() {
        // BEGIN Android-added: Work around exit() not being called.
        // Android runtime does not call exit() when a Thread exits so the group field is not
        // set to null so it needs to pretend as if it did. If we are not going to call exit()
        // then this should probably just check isAlive() here rather than getState() as the
        // latter requires a native call.
        if (getState() == Thread.State.TERMINATED) {
            return null;
        }
        // END Android-added: Work around exit() not being called.
        return group;
    }

    /**
     * Returns an estimate of the number of active threads in the current
     * thread's {@linkplain java.lang.ThreadGroup thread group} and its
     * subgroups. Recursively iterates over all subgroups in the current
     * thread's thread group.
     *
     * <p> The value returned is only an estimate because the number of
     * threads may change dynamically while this method traverses internal
     * data structures, and might be affected by the presence of certain
     * system threads. This method is intended primarily for debugging
     * and monitoring purposes.
     *
     * @return  an estimate of the number of active threads in the current
     *          thread's thread group and in any other thread group that
     *          has the current thread's thread group as an ancestor
     */
    public static int activeCount() {
        return currentThread().getThreadGroup().activeCount();
    }

    /**
     * Copies into the specified array every active thread in the current
     * thread's thread group and its subgroups. This method simply
     * invokes the {@link java.lang.ThreadGroup#enumerate(Thread[])}
     * method of the current thread's thread group.
     *
     * <p> An application might use the {@linkplain #activeCount activeCount}
     * method to get an estimate of how big the array should be, however
     * <i>if the array is too short to hold all the threads, the extra threads
     * are silently ignored.</i>  If it is critical to obtain every active
     * thread in the current thread's thread group and its subgroups, the
     * invoker should verify that the returned int value is strictly less
     * than the length of {@code tarray}.
     *
     * <p> Due to the inherent race condition in this method, it is recommended
     * that the method only be used for debugging and monitoring purposes.
     *
     * @param  tarray
     *         an array into which to put the list of threads
     *
     * @return  the number of threads put into the array
     *
     * @throws  SecurityException
     *          if {@link java.lang.ThreadGroup#checkAccess} determines that
     *          the current thread cannot access its thread group
     */
    public static int enumerate(Thread tarray[]) {
        return currentThread().getThreadGroup().enumerate(tarray);
    }

    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @return     nothing
     *
     * @deprecated This method was originally designed to count the number of
     *             stack frames but the results were never well-defined and it
     *             depended on thread-suspension.
     *             This method is subject to removal in a future version of Java SE.
     * @removed
     */
    @Deprecated(since="1.2", forRemoval=true)
    // Android-changed: Provide non-native implementation of countStackFrames().
    // public int countStackFrames() {
    //     throw new UnsupportedOperationException();
    // }
    public int countStackFrames() {
        return getStackTrace().length;
    }

    /**
     * Waits at most {@code millis} milliseconds for this thread to
     * die. A timeout of {@code 0} means to wait forever.
     *
     * <p> This implementation uses a loop of {@code this.wait} calls
     * conditioned on {@code this.isAlive}. As a thread terminates the
     * {@code this.notifyAll} method is invoked. It is recommended that
     * applications not use {@code wait}, {@code notify}, or
     * {@code notifyAll} on {@code Thread} instances.
     *
     * @param  millis
     *         the time to wait in milliseconds
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public final void join(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (this instanceof VirtualThread vthread) {
            if (isAlive()) {
                long nanos = MILLISECONDS.toNanos(millis);
                vthread.joinNanos(nanos);
            }
            return;
        }

        // BEGIN Android-changed: Synchronize on separate lock object not this Thread.
        // nativePeer and hence isAlive() can change asynchronously, but Thread::Destroy
        // will always acquire and notify lock after isAlive() changes to false.
        // synchronized (this) {
        synchronized(lock) {
            if (millis > 0) {
                if (isAlive()) {
                    final long startTime = System.nanoTime();
                    long delay = millis;
                    do {
                        // wait(delay);
                        lock.wait(delay);
                    } while (isAlive() && (delay = millis -
                            NANOSECONDS.toMillis(System.nanoTime() - startTime)) > 0);
                }
            } else {
                while (isAlive()) {
                    // wait(0);
                    lock.wait(0);
                }
            }
        }
        // END Android-changed: Synchronize on separate lock object not this Thread.
    }

    /**
     * Waits at most {@code millis} milliseconds plus
     * {@code nanos} nanoseconds for this thread to die.
     * If both arguments are {@code 0}, it means to wait forever.
     *
     * <p> This implementation uses a loop of {@code this.wait} calls
     * conditioned on {@code this.isAlive}. As a thread terminates the
     * {@code this.notifyAll} method is invoked. It is recommended that
     * applications not use {@code wait}, {@code notify}, or
     * {@code notifyAll} on {@code Thread} instances.
     *
     * @param  millis
     *         the time to wait in milliseconds
     *
     * @param  nanos
     *         {@code 0-999999} additional nanoseconds to wait
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative, or the value
     *          of {@code nanos} is not in the range {@code 0-999999}
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    // BEGIN Android-changed: Synchronize on separate lock object not this Thread.
    // public final synchronized void join(long millis, int nanos)
    public final void join(long millis, int nanos)
    throws InterruptedException {

        synchronized(lock) {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException(
                                "nanosecond timeout value out of range");
        }

        if (nanos > 0 && millis < Long.MAX_VALUE) {
            millis++;
        }

        join(millis);
        }
    }
    // END Android-changed: Synchronize on separate lock object not this Thread.

    /**
     * Waits for this thread to die.
     *
     * <p> An invocation of this method behaves in exactly the same
     * way as the invocation
     *
     * <blockquote>
     * {@linkplain #join(long) join}{@code (0)}
     * </blockquote>
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public final void join() throws InterruptedException {
        join(0);
    }

    /**
     * Waits for this thread to terminate for up to the given waiting duration.
     *
     * <p> This method does not wait if the duration to wait is less than or
     * equal to zero. In this case, the method just tests if the thread has
     * terminated.
     *
     * @param   duration
     *          the maximum duration to wait
     *
     * @return  {@code true} if the thread has terminated, {@code false} if the
     *          thread has not terminated
     *
     * @throws  InterruptedException
     *          if the current thread is interrupted while waiting.
     *          The <i>interrupted status</i> of the current thread is cleared
     *          when this exception is thrown.
     *
     * @throws  IllegalThreadStateException
     *          if this thread has not been started.
     *
     * @since 19
     */
    public final boolean join(Duration duration) throws InterruptedException {
        long nanos = NANOSECONDS.convert(duration); // MAX_VALUE if > 292 years

        Thread.State state = threadState();
        if (state == State.NEW)
            throw new IllegalThreadStateException("Thread not started");
        if (state == State.TERMINATED)
            return true;
        if (nanos <= 0)
            return false;

        if (this instanceof VirtualThread vthread) {
            return vthread.joinNanos(nanos);
        }

        // convert to milliseconds
        long millis = MILLISECONDS.convert(nanos, NANOSECONDS);
        if (nanos > NANOSECONDS.convert(millis, MILLISECONDS)) {
            millis += 1L;
        }
        join(millis);
        return isTerminated();
    }

    /**
     * Prints a stack trace of the current thread to the standard error stream.
     * This method is used only for debugging.
     *
     * @see     Throwable#printStackTrace()
     */
    public static void dumpStack() {
        new Exception("Stack trace").printStackTrace();
    }

    /**
     * Marks this thread as either a {@linkplain #isDaemon daemon} thread
     * or a user thread. The Java Virtual Machine exits when the only
     * threads running are all daemon threads.
     *
     * <p> This method must be invoked before the thread is started.
     *
     * @param  on
     *         if {@code true}, marks this thread as a daemon thread
     *
     * @throws  IllegalThreadStateException
     *          if this thread is {@linkplain #isAlive alive}
     *
     * @throws  SecurityException
     *          if {@link #checkAccess} determines that the current
     *          thread cannot modify this thread
     */
    public final void setDaemon(boolean on) {
        checkAccess();
        if (isAlive()) {
            throw new IllegalThreadStateException();
        }
        daemon = on;
    }

    void daemon(boolean on) {
        this.daemon = on;
    }

    /**
     * Tests if this thread is a daemon thread.
     *
     * @return  {@code true} if this thread is a daemon thread;
     *          {@code false} otherwise.
     * @see     #setDaemon(boolean)
     */
    public final boolean isDaemon() {
        return daemon;
    }

    /**
     * Determines if the currently running thread has permission to
     * modify this thread.
     * <p>
     * If there is a security manager, its {@code checkAccess} method
     * is called with this thread as its argument. This may result in
     * throwing a {@code SecurityException}.
     *
     * @throws  SecurityException  if the current thread is not allowed to
     *          access this thread.
     * @see        SecurityManager#checkAccess(Thread)
     * @deprecated This method is only useful in conjunction with
     *       {@linkplain SecurityManager the Security Manager}, which is
     *       deprecated and subject to removal in a future release.
     *       Consequently, this method is also deprecated and subject to
     *       removal. There is no replacement for the Security Manager or this
     *       method.
     */
    @Deprecated(since="17", forRemoval=true)
    public final void checkAccess() {
        // Android-removed: SecurityManager stubbed out on Android.
        // @SuppressWarnings("removal")
        // SecurityManager security = System.getSecurityManager();
        // if (security != null) {
        //     security.checkAccess(this);
        // }
    }

    /**
     * Returns a string representation of this thread, including the
     * thread's name, priority, and thread group.
     *
     * @return  a string representation of this thread.
     */
    public String toString() {
        ThreadGroup group = getThreadGroup();
        if (group != null) {
            return "Thread[" + getName() + "," + getPriority() + "," +
                           group.getName() + "]";
        } else {
            return "Thread[" + getName() + "," + getPriority() + "," +
                            "" + "]";
        }
    }

    /**
     * Returns a builder for creating a platform {@code Thread} or {@code ThreadFactory}
     * that creates platform threads.
     *
     * <p> <a id="ofplatform-security"><b>Interaction with security manager when
     * creating platform threads</b></a>
     * <p> Creating a platform thread when there is a security manager set will
     * invoke the security manager's {@link SecurityManager#checkAccess(ThreadGroup)
     * checkAccess(ThreadGroup)} method with the thread's thread group.
     * If the thread group has not been set with the {@link
     * Builder.OfPlatform#group(ThreadGroup) OfPlatform.group} method then the
     * security manager's {@link SecurityManager#getThreadGroup() getThreadGroup}
     * method will be invoked first to select the thread group. If the security
     * manager {@code getThreadGroup} method returns {@code null} then the thread
     * group of the constructing thread is used.
     *
     * @apiNote The following are examples using the builder:
     * {@snippet :
     *   // Start a daemon thread to run a task
     *   Thread thread = Thread.ofPlatform().daemon().start(runnable);
     *
     *   // Create an unstarted thread with name "duke", its start() method
     *   // must be invoked to schedule it to execute.
     *   Thread thread = Thread.ofPlatform().name("duke").unstarted(runnable);
     *
     *   // A ThreadFactory that creates daemon threads named "worker-0", "worker-1", ...
     *   ThreadFactory factory = Thread.ofPlatform().daemon().name("worker-", 0).factory();
     * }
     *
     * @return A builder for creating {@code Thread} or {@code ThreadFactory} objects.
     * @since 21
     */
    public static Builder.OfPlatform ofPlatform() {
        return new ThreadBuilders.PlatformThreadBuilder();
    }

    /**
     * Returns a builder for creating a virtual {@code Thread} or {@code ThreadFactory}
     * that creates virtual threads.
     *
     * @apiNote The following are examples using the builder:
     * {@snippet :
     *   // Start a virtual thread to run a task.
     *   Thread thread = Thread.ofVirtual().start(runnable);
     *
     *   // A ThreadFactory that creates virtual threads
     *   ThreadFactory factory = Thread.ofVirtual().factory();
     * }
     *
     * @return A builder for creating {@code Thread} or {@code ThreadFactory} objects.
     * @since 21
     */
    public static Builder.OfVirtual ofVirtual() {
        return new ThreadBuilders.VirtualThreadBuilder();
    }

    /**
     * A builder for {@link Thread} and {@link ThreadFactory} objects.
     *
     * <p> {@code Builder} defines methods to set {@code Thread} properties such
     * as the thread {@link #name(String) name}. This includes properties that would
     * otherwise be <a href="Thread.html#inheritance">inherited</a>. Once set, a
     * {@code Thread} or {@code ThreadFactory} is created with the following methods:
     *
     * <ul>
     *     <li> The {@linkplain #unstarted(Runnable) unstarted} method creates a new
     *          <em>unstarted</em> {@code Thread} to run a task. The {@code Thread}'s
     *          {@link Thread#start() start} method must be invoked to schedule the
     *          thread to execute.
     *     <li> The {@linkplain #start(Runnable) start} method creates a new {@code
     *          Thread} to run a task and schedules the thread to execute.
     *     <li> The {@linkplain #factory() factory} method creates a {@code ThreadFactory}.
     * </ul>
     *
     * <p> A {@code Thread.Builder} is not thread safe. The {@code ThreadFactory}
     * returned by the builder's {@code factory()} method is thread safe.
     *
     * <p> Unless otherwise specified, passing a null argument to a method in
     * this interface causes a {@code NullPointerException} to be thrown.
     *
     * @see Thread#ofPlatform()
     * @see Thread#ofVirtual()
     * @since 21
     */
    // Android-changed: Remove sealed keyword to make Metalava happy.
    public interface Builder {
        /**
         * Sets the thread name.
         * @param name thread name
         * @return this builder
         */
        Builder name(String name);
        /**
         * Sets the thread name to be the concatenation of a string prefix and
         * the string representation of a counter value. The counter's initial
         * value is {@code start}. It is incremented after a {@code Thread} is
         * created with this builder so that the next thread is named with
         * the new counter value. A {@code ThreadFactory} created with this
         * builder is seeded with the current value of the counter. The {@code
         * ThreadFactory} increments its copy of the counter after {@link
         * ThreadFactory#newThread(Runnable) newThread} is used to create a
         * {@code Thread}.
         *
         * @apiNote
         * The following example creates a builder that is invoked twice to start
         * two threads named "{@code worker-0}" and "{@code worker-1}".
         * {@snippet :
         *   Thread.Builder builder = Thread.ofPlatform().name("worker-", 0);
         *   Thread t1 = builder.start(task1);   // name "worker-0"
         *   Thread t2 = builder.start(task2);   // name "worker-1"
         * }
         *
         * @param prefix thread name prefix
         * @param start the starting value of the counter
         * @return this builder
         * @throws IllegalArgumentException if start is negative
         */
        Builder name(String prefix, long start);
        /**
         * Sets whether the thread inherits the initial values of {@linkplain
         * InheritableThreadLocal inheritable-thread-local} variables from the
         * constructing thread. The default is to inherit.
         *
         * @param inherit {@code true} to inherit, {@code false} to not inherit
         * @return this builder
         */
        Builder inheritInheritableThreadLocals(boolean inherit);
        /**
         * Sets the uncaught exception handler.
         * @param ueh uncaught exception handler
         * @return this builder
         */
        Builder uncaughtExceptionHandler(UncaughtExceptionHandler ueh);
        /**
         * Creates a new {@code Thread} from the current state of the builder to
         * run the given task. The {@code Thread}'s {@link Thread#start() start}
         * method must be invoked to schedule the thread to execute.
         *
         * @param task the object to run when the thread executes
         * @return a new unstarted Thread
         * @throws SecurityException if denied by the security manager
         *         (See <a href="Thread.html#ofplatform-security">Interaction with
         *         security manager when creating platform threads</a>)
         *
         * @see <a href="Thread.html#inheritance">Inheritance when creating threads</a>
         */
        Thread unstarted(Runnable task);
        /**
         * Creates a new {@code Thread} from the current state of the builder and
         * schedules it to execute.
         *
         * @param task the object to run when the thread executes
         * @return a new started Thread
         * @throws SecurityException if denied by the security manager
         *         (See <a href="Thread.html#ofplatform-security">Interaction with
         *         security manager when creating platform threads</a>)
         *
         * @see <a href="Thread.html#inheritance">Inheritance when creating threads</a>
         */
        Thread start(Runnable task);
        /**
         * Returns a {@code ThreadFactory} to create threads from the current
         * state of the builder. The returned thread factory is safe for use by
         * multiple concurrent threads.
         *
         * @return a thread factory to create threads
         */
        ThreadFactory factory();
        /**
         * A builder for creating a platform {@link Thread} or {@link ThreadFactory}
         * that creates platform threads.
         *
         * <p> Unless otherwise specified, passing a null argument to a method in
         * this interface causes a {@code NullPointerException} to be thrown.
         *
         * @see Thread#ofPlatform()
         * @since 21
         */
        interface OfPlatform extends Builder {
            @Override OfPlatform name(String name);
            /**
             * @throws IllegalArgumentException {@inheritDoc}
             */
            @Override OfPlatform name(String prefix, long start);
            @Override OfPlatform inheritInheritableThreadLocals(boolean inherit);
            @Override OfPlatform uncaughtExceptionHandler(UncaughtExceptionHandler ueh);
            /**
             * Sets the thread group.
             * @param group the thread group
             * @return this builder
             */
            OfPlatform group(ThreadGroup group);
            /**
             * Sets the daemon status.
             * @param on {@code true} to create daemon threads
             * @return this builder
             */
            OfPlatform daemon(boolean on);
            /**
             * Sets the daemon status to {@code true}.
             * @implSpec The default implementation invokes {@linkplain #daemon(boolean)} with
             * a value of {@code true}.
             * @return this builder
             */
            default OfPlatform daemon() {
                return daemon(true);
            }
            /**
             * Sets the thread priority.
             * @param priority priority
             * @return this builder
             * @throws IllegalArgumentException if the priority is less than
             *        {@link Thread#MIN_PRIORITY} or greater than {@link Thread#MAX_PRIORITY}
             */
            OfPlatform priority(int priority);
            /**
             * Sets the desired stack size.
             *
             * <p> The stack size is the approximate number of bytes of address space
             * that the Java virtual machine is to allocate for the thread's stack. The
             * effect is highly platform dependent and the Java virtual machine is free
             * to treat the {@code stackSize} parameter as a "suggestion". If the value
             * is unreasonably low for the platform then a platform specific minimum
             * may be used. If the value is unreasonably high then a platform specific
             * maximum may be used. A value of zero is always ignored.
             *
             * @param stackSize the desired stack size
             * @return this builder
             * @throws IllegalArgumentException if the stack size is negative
             */
            OfPlatform stackSize(long stackSize);
        }
        /**
         * A builder for creating a virtual {@link Thread} or {@link ThreadFactory}
         * that creates virtual threads.
         *
         * <p> Unless otherwise specified, passing a null argument to a method in
         * this interface causes a {@code NullPointerException} to be thrown.
         *
         * @see Thread#ofVirtual()
         * @since 21
         */
        interface OfVirtual extends Builder {
            @Override OfVirtual name(String name);
            /**
             * @throws IllegalArgumentException {@inheritDoc}
             */
            @Override OfVirtual name(String prefix, long start);
            @Override OfVirtual inheritInheritableThreadLocals(boolean inherit);
            @Override OfVirtual uncaughtExceptionHandler(UncaughtExceptionHandler ueh);
        }
    }

    /**
     * Returns the context {@code ClassLoader} for this thread. The context
     * {@code ClassLoader} is provided by the creator of the thread for use
     * by code running in this thread when loading classes and resources.
     * If not {@linkplain #setContextClassLoader set}, the default is the
     * {@code ClassLoader} context of the parent thread. The context
     * {@code ClassLoader} of the
     * primordial thread is typically set to the class loader used to load the
     * application.
     *
     *
     * @return  the context {@code ClassLoader} for this thread, or {@code null}
     *          indicating the system class loader (or, failing that, the
     *          bootstrap class loader)
     *
     * @throws  SecurityException
     *          if the current thread cannot get the context ClassLoader
     *
     * @since 1.2
     */
    @CallerSensitive
    public ClassLoader getContextClassLoader() {
        // Android-removed: SecurityManager stubbed out on Android.
        /*
        if (contextClassLoader == null)
            return null;
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            ClassLoader.checkClassLoaderPermission(contextClassLoader,
                                                   Reflection.getCallerClass());
        }
        */
        return contextClassLoader;
    }

    /**
     * Sets the context ClassLoader for this Thread. The context
     * ClassLoader can be set when a thread is created, and allows
     * the creator of the thread to provide the appropriate class loader,
     * through {@code getContextClassLoader}, to code running in the thread
     * when loading classes and resources.
     *
     * <p>If a security manager is present, its {@link
     * SecurityManager#checkPermission(java.security.Permission) checkPermission}
     * method is invoked with a {@link RuntimePermission RuntimePermission}{@code
     * ("setContextClassLoader")} permission to see if setting the context
     * ClassLoader is permitted.
     *
     * @param  cl
     *         the context ClassLoader for this Thread, or null  indicating the
     *         system class loader (or, failing that, the bootstrap class loader)
     *
     * @throws  SecurityException
     *          if the current thread cannot set the context ClassLoader
     *
     * @since 1.2
     */
    public void setContextClassLoader(ClassLoader cl) {
        // Android-removed: SecurityManager stubbed out on Android.
        // @SuppressWarnings("removal")
        // SecurityManager sm = System.getSecurityManager();
        // if (sm != null) {
        //     sm.checkPermission(new RuntimePermission("setContextClassLoader"));
        // }
        contextClassLoader = cl;
    }

    /**
     * Returns {@code true} if and only if the current thread holds the
     * monitor lock on the specified object.
     *
     * <p>This method is designed to allow a program to assert that
     * the current thread already holds a specified lock:
     * <pre>
     *     assert Thread.holdsLock(obj);
     * </pre>
     *
     * @param  obj the object on which to test lock ownership
     * @throws NullPointerException if obj is {@code null}
     * @return {@code true} if the current thread holds the monitor lock on
     *         the specified object.
     * @since 1.4
     */
    @FastNative
    public static native boolean holdsLock(Object obj);

    private static final StackTraceElement[] EMPTY_STACK_TRACE
        = new StackTraceElement[0];

    /**
     * Returns an array of stack trace elements representing the stack dump
     * of this thread.  This method will return a zero-length array if
     * this thread has not started, has started but has not yet been
     * scheduled to run by the system, or has terminated.
     * If the returned array is of non-zero length then the first element of
     * the array represents the top of the stack, which is the most recent
     * method invocation in the sequence.  The last element of the array
     * represents the bottom of the stack, which is the least recent method
     * invocation in the sequence.
     *
     * <p>If there is a security manager, and this thread is not
     * the current thread, then the security manager's
     * {@code checkPermission} method is called with a
     * {@code RuntimePermission("getStackTrace")} permission
     * to see if it's ok to get the stack trace.
     *
     * <p>Some virtual machines may, under some circumstances, omit one
     * or more stack frames from the stack trace.  In the extreme case,
     * a virtual machine that has no stack trace information concerning
     * this thread is permitted to return a zero-length array from this
     * method.
     *
     * @return an array of {@code StackTraceElement},
     * each represents one stack frame.
     *
     * @throws SecurityException
     *        if a security manager exists and its
     *        {@code checkPermission} method doesn't allow
     *        getting the stack trace of thread.
     * @see SecurityManager#checkPermission
     * @see RuntimePermission
     * @see Throwable#getStackTrace
     *
     * @since 1.5
     */
    public StackTraceElement[] getStackTrace() {
        // BEGIN Android-changed: Use native VMStack to get stack trace.
        /*
        if (this != Thread.currentThread()) {
            // check for getStackTrace permission
            @SuppressWarnings("removal")
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(
                    SecurityConstants.GET_STACK_TRACE_PERMISSION);
            }
            // optimization so we do not call into the vm for threads that
            // have not yet started or have terminated
            if (!isAlive()) {
                return EMPTY_STACK_TRACE;
            }
            StackTraceElement[][] stackTraceArray = dumpThreads(new Thread[] {this});
            StackTraceElement[] stackTrace = stackTraceArray[0];
            // a thread that was alive during the previous isAlive call may have
            // since terminated, therefore not having a stacktrace.
            if (stackTrace == null) {
                stackTrace = EMPTY_STACK_TRACE;
            }
            return stackTrace;
        } else {
            return (new Exception()).getStackTrace();
        }
        */
        StackTraceElement ste[] = VMStack.getThreadStackTrace(this);
        return ste != null ? ste : EmptyArray.STACK_TRACE_ELEMENT;
        // END Android-changed: Use native VMStack to get stack trace.
    }

    // Android-removed: SecurityManager paragraph.
    /**
     * Returns a map of stack traces for all live threads.
     * The map keys are threads and each map value is an array of
     * {@code StackTraceElement} that represents the stack dump
     * of the corresponding {@code Thread}.
     * The returned stack traces are in the format specified for
     * the {@link #getStackTrace getStackTrace} method.
     *
     * <p>The threads may be executing while this method is called.
     * The stack trace of each thread only represents a snapshot and
     * each stack trace may be obtained at different time.  A zero-length
     * array will be returned in the map value if the virtual machine has
     * no stack trace information about a thread.
     *
     * @return a {@code Map} from {@code Thread} to an array of
     * {@code StackTraceElement} that represents the stack trace of
     * the corresponding thread.
     *
     * @see #getStackTrace
     * @see SecurityManager#checkPermission
     * @see RuntimePermission
     * @see Throwable#getStackTrace
     *
     * @since 1.5
     */
    public static Map<Thread, StackTraceElement[]> getAllStackTraces() {
        // Android-removed: SecurityManager stubbed out on Android.
        /*
        // check for getStackTrace permission
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(
                SecurityConstants.GET_STACK_TRACE_PERMISSION);
            security.checkPermission(
                SecurityConstants.MODIFY_THREADGROUP_PERMISSION);
        }
        */

        // Get a snapshot of the list of all threads
        // BEGIN Android-changed: Use ThreadGroup and getStackTrace() instead of native methods.
        // Allocate a bit more space than needed, in case new ones are just being created.
        /*
        Thread[] threads = getThreads();
        StackTraceElement[][] traces = dumpThreads(threads);
        Map<Thread, StackTraceElement[]> m = new HashMap<>(threads.length);
        for (int i = 0; i < threads.length; i++) {
            StackTraceElement[] stackTrace = traces[i];
            if (stackTrace != null) {
                m.put(threads[i], stackTrace);
            }
            // else terminated so we don't put it in the map
        }
        */
        AllThreadsRecord r = getAllThreadsInternal();
        int count = r.count;
        Thread[] threads = r.threads;

        // Collect the stacktraces
        Map<Thread, StackTraceElement[]> m = new HashMap<Thread, StackTraceElement[]>();
        for (int i = 0; i < count; i++) {
            StackTraceElement[] stackTrace = threads[i].getStackTrace();
            m.put(threads[i], stackTrace);
        }
        // END Android-changed: Use ThreadGroup and getStackTrace() instead of native methods.
        return m;
    }


    private static final RuntimePermission SUBCLASS_IMPLEMENTATION_PERMISSION =
                    new RuntimePermission("enableContextClassLoaderOverride");

    /** cache of subclass security audit results */
    /* Replace with ConcurrentReferenceHashMap when/if it appears in a future
     * release */
    private static class Caches {
        /** cache of subclass security audit results */
        static final ConcurrentMap<WeakClassKey,Boolean> subclassAudits =
            new ConcurrentHashMap<>();

        /** queue for WeakReferences to audited subclasses */
        static final ReferenceQueue<Class<?>> subclassAuditsQueue =
            new ReferenceQueue<>();
    }

    /**
     * Verifies that this (possibly subclass) instance can be constructed
     * without violating security constraints: the subclass must not override
     * security-sensitive non-final methods, or else the
     * "enableContextClassLoaderOverride" RuntimePermission is checked.
     */
    private static boolean isCCLOverridden(Class<?> cl) {
        if (cl == Thread.class)
            return false;

        processQueue(Caches.subclassAuditsQueue, Caches.subclassAudits);
        WeakClassKey key = new WeakClassKey(cl, Caches.subclassAuditsQueue);
        Boolean result = Caches.subclassAudits.get(key);
        if (result == null) {
            result = Boolean.valueOf(auditSubclass(cl));
            Caches.subclassAudits.putIfAbsent(key, result);
        }

        return result.booleanValue();
    }

    /**
     * Performs reflective checks on given subclass to verify that it doesn't
     * override security-sensitive non-final methods.  Returns true if the
     * subclass overrides any of the methods, false otherwise.
     */
    private static boolean auditSubclass(final Class<?> subcl) {
        @SuppressWarnings("removal")
        Boolean result = AccessController.doPrivileged(
            new PrivilegedAction<>() {
                public Boolean run() {
                    for (Class<?> cl = subcl;
                         cl != Thread.class;
                         cl = cl.getSuperclass())
                    {
                        try {
                            cl.getDeclaredMethod("getContextClassLoader", new Class<?>[0]);
                            return Boolean.TRUE;
                        } catch (NoSuchMethodException ex) {
                        }
                        try {
                            Class<?>[] params = {ClassLoader.class};
                            cl.getDeclaredMethod("setContextClassLoader", params);
                            return Boolean.TRUE;
                        } catch (NoSuchMethodException ex) {
                        }
                    }
                    return Boolean.FALSE;
                }
            }
        );
        return result.booleanValue();
    }

    /**
     * Return an array of all live threads.
     */
    static Thread[] getAllThreads() {
        // Android-changed: Use ThreadGroup and getStackTrace() instead of native methods.
        // return getThreads();
        AllThreadsRecord r = getAllThreadsInternal();
        int count = r.count;
        Thread[] threads = new Thread[count];
        System.arraycopy(r.threads, 0, threads, 0, count);
        return threads;
    }

    // BEGIN Android-added: Use ThreadGroup and getStackTrace() instead of native methods.
    /**
     * @return an AllThreadsRecord object that has some unused space at the tail of the array, and
     *         an actual count of threads.
     */
    private static AllThreadsRecord getAllThreadsInternal() {
        // Allocate a bit more space than needed, in case new ones are just being created.
        int count = ThreadGroup.systemThreadGroup.activeCount();
        Thread[] threads = new Thread[count + count / 2];

        // Enumerate the threads.
        count = ThreadGroup.systemThreadGroup.enumerate(threads);
        return new AllThreadsRecord(threads, count);
    }

    @SuppressWarnings("ArrayRecordComponent")
    private record AllThreadsRecord(Thread[] threads, int count) {}
    // END Android-added: Use ThreadGroup and getStackTrace() instead of native methods.

    // Android-removed: Native methods that are unused on Android.
    // private static native StackTraceElement[][] dumpThreads(Thread[] threads);
    // private static native Thread[] getThreads();

    /**
     * Returns the identifier of this Thread.  The thread ID is a positive
     * {@code long} number generated when this thread was created.
     * The thread ID is unique and remains unchanged during its lifetime.
     * When a thread is terminated, this thread ID may be reused.
     *
     * @return this thread's ID.
     *
     * @deprecated This method is not final and may be overridden to return a
     * value that is not the thread ID. Use {@link #threadId()} instead.
     *
     * @since 1.5
     */
    @Deprecated(since="19")
    public long getId() {
        return tid;
    }

    /**
     * Returns the identifier of this Thread.  The thread ID is a positive
     * {@code long} number generated when this thread was created.
     * The thread ID is unique and remains unchanged during its lifetime.
     *
     * @return this thread's ID
     * @since 19
     */
    public final long threadId() {
        return tid;
    }

    /**
     * A thread state.  A thread can be in one of the following states:
     * <ul>
     * <li>{@link #NEW}<br>
     *     A thread that has not yet started is in this state.
     *     </li>
     * <li>{@link #RUNNABLE}<br>
     *     A thread executing in the Java virtual machine is in this state.
     *     </li>
     * <li>{@link #BLOCKED}<br>
     *     A thread that is blocked waiting for a monitor lock
     *     is in this state.
     *     </li>
     * <li>{@link #WAITING}<br>
     *     A thread that is waiting indefinitely for another thread to
     *     perform a particular action is in this state.
     *     </li>
     * <li>{@link #TIMED_WAITING}<br>
     *     A thread that is waiting for another thread to perform an action
     *     for up to a specified waiting time is in this state.
     *     </li>
     * <li>{@link #TERMINATED}<br>
     *     A thread that has exited is in this state.
     *     </li>
     * </ul>
     *
     * <p>
     * A thread can be in only one state at a given point in time.
     * These states are virtual machine states which do not reflect
     * any operating system thread states.
     *
     * @since   1.5
     * @see #getState
     */
    public enum State {
        /**
         * Thread state for a thread which has not yet started.
         */
        NEW,

        /**
         * Thread state for a runnable thread.  A thread in the runnable
         * state is executing in the Java virtual machine but it may
         * be waiting for other resources from the operating system
         * such as processor.
         */
        RUNNABLE,

        /**
         * Thread state for a thread blocked waiting for a monitor lock.
         * A thread in the blocked state is waiting for a monitor lock
         * to enter a synchronized block/method or
         * reenter a synchronized block/method after calling
         * {@link Object#wait() Object.wait}.
         */
        BLOCKED,

        /**
         * Thread state for a waiting thread.
         * A thread is in the waiting state due to calling one of the
         * following methods:
         * <ul>
         *   <li>{@link Object#wait() Object.wait} with no timeout</li>
         *   <li>{@link #join() Thread.join} with no timeout</li>
         *   <li>{@link LockSupport#park() LockSupport.park}</li>
         * </ul>
         *
         * <p>A thread in the waiting state is waiting for another thread to
         * perform a particular action.
         *
         * For example, a thread that has called {@code Object.wait()}
         * on an object is waiting for another thread to call
         * {@code Object.notify()} or {@code Object.notifyAll()} on
         * that object. A thread that has called {@code Thread.join()}
         * is waiting for a specified thread to terminate.
         */
        WAITING,

        /**
         * Thread state for a waiting thread with a specified waiting time.
         * A thread is in the timed waiting state due to calling one of
         * the following methods with a specified positive waiting time:
         * <ul>
         *   <li>{@link #sleep Thread.sleep}</li>
         *   <li>{@link Object#wait(long) Object.wait} with timeout</li>
         *   <li>{@link #join(long) Thread.join} with timeout</li>
         *   <li>{@link LockSupport#parkNanos LockSupport.parkNanos}</li>
         *   <li>{@link LockSupport#parkUntil LockSupport.parkUntil}</li>
         * </ul>
         */
        TIMED_WAITING,

        /**
         * Thread state for a terminated thread.
         * The thread has completed execution.
         */
        TERMINATED;
    }

    /**
     * Returns the state of this thread.
     * This method is designed for use in monitoring of the system state,
     * not for synchronization control.
     *
     * @return this thread's state.
     * @since 1.5
     */
    public State getState() {
        // get current thread state
        return threadState();
    }

    /**
     * Returns the state of this thread.
     * This method can be used instead of getState as getState is not final and
     * so can be overridden to run arbitrary code.
     */
    State threadState() {
        // Android-changed: Replace unused threadStatus field with started field.
        // Use Android specific nativeGetStatus() method. See comment on started field for more
        // information.
        // return jdk.internal.misc.VM.toThreadState(holder.threadStatus);
        return State.values()[nativeGetStatus(started)];
    }

    /**
     * Returns true if the thread has terminated.
     */
    boolean isTerminated() {
        return threadState() == State.TERMINATED;
    }

    // Added in JSR-166

    /**
     * Interface for handlers invoked when a {@code Thread} abruptly
     * terminates due to an uncaught exception.
     * <p>When a thread is about to terminate due to an uncaught exception
     * the Java Virtual Machine will query the thread for its
     * {@code UncaughtExceptionHandler} using
     * {@link #getUncaughtExceptionHandler} and will invoke the handler's
     * {@code uncaughtException} method, passing the thread and the
     * exception as arguments.
     * If a thread has not had its {@code UncaughtExceptionHandler}
     * explicitly set, then its {@code ThreadGroup} object acts as its
     * {@code UncaughtExceptionHandler}. If the {@code ThreadGroup} object
     * has no
     * special requirements for dealing with the exception, it can forward
     * the invocation to the {@linkplain #getDefaultUncaughtExceptionHandler
     * default uncaught exception handler}.
     *
     * @see #setDefaultUncaughtExceptionHandler
     * @see #setUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    @FunctionalInterface
    public interface UncaughtExceptionHandler {
        /**
         * Method invoked when the given thread terminates due to the
         * given uncaught exception.
         * <p>Any exception thrown by this method will be ignored by the
         * Java Virtual Machine.
         * @param t the thread
         * @param e the exception
         */
        void uncaughtException(Thread t, Throwable e);
    }

    // null unless explicitly set
    private volatile UncaughtExceptionHandler uncaughtExceptionHandler;

    // null unless explicitly set
    private static volatile UncaughtExceptionHandler defaultUncaughtExceptionHandler;

    // Android-removed: SecurityManager throws clause.
    /**
     * Set the default handler invoked when a thread abruptly terminates
     * due to an uncaught exception, and no other handler has been defined
     * for that thread.
     *
     * <p>Uncaught exception handling is controlled first by the thread, then
     * by the thread's {@link ThreadGroup} object and finally by the default
     * uncaught exception handler. If the thread does not have an explicit
     * uncaught exception handler set, and the thread's thread group
     * (including parent thread groups)  does not specialize its
     * {@code uncaughtException} method, then the default handler's
     * {@code uncaughtException} method will be invoked.
     * <p>By setting the default uncaught exception handler, an application
     * can change the way in which uncaught exceptions are handled (such as
     * logging to a specific device, or file) for those threads that would
     * already accept whatever &quot;default&quot; behavior the system
     * provided.
     *
     * <p>Note that the default uncaught exception handler should not usually
     * defer to the thread's {@code ThreadGroup} object, as that could cause
     * infinite recursion.
     *
     * @param eh the object to use as the default uncaught exception handler.
     * If {@code null} then there is no default handler.
     *
     * @see #setUncaughtExceptionHandler
     * @see #getUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        // Android-removed: SecurityManager stubbed out on Android.
        /*
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(
                new RuntimePermission("setDefaultUncaughtExceptionHandler")
                    );
        }
        */

         defaultUncaughtExceptionHandler = eh;
     }

    /**
     * Returns the default handler invoked when a thread abruptly terminates
     * due to an uncaught exception. If the returned value is {@code null},
     * there is no default.
     * @since 1.5
     * @see #setDefaultUncaughtExceptionHandler
     * @return the default uncaught exception handler for all threads
     */
    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler(){
        return defaultUncaughtExceptionHandler;
    }

    // BEGIN Android-added: The concept of an uncaughtExceptionPreHandler for use by platform.
    // See http://b/29624607 for background information.
    // null unless explicitly set
    private static volatile UncaughtExceptionHandler uncaughtExceptionPreHandler;

    /**
     * Sets an {@link UncaughtExceptionHandler} that will be called before any
     * returned by {@link #getUncaughtExceptionHandler()}. To allow the standard
     * handlers to run, this handler should never terminate this process. Any
     * throwables thrown by the handler will be ignored by
     * {@link #dispatchUncaughtException(Throwable)}.
     *
     * @hide used when configuring the runtime for exception logging; see
     *     {@link dalvik.system.RuntimeHooks} b/29624607
     */
    public static void setUncaughtExceptionPreHandler(UncaughtExceptionHandler eh) {
        uncaughtExceptionPreHandler = eh;
    }

    /**
     * Gets an {@link UncaughtExceptionHandler} that will be called before any
     * returned by {@link #getUncaughtExceptionHandler()}. Can be {@code null} if
     * was not explicitly set with
     * {@link #setUncaughtExceptionPreHandler(UncaughtExceptionHandler)}.
     *
     * @return the uncaught exception prehandler for this thread
     *
     * @hide
     */
    public static UncaughtExceptionHandler getUncaughtExceptionPreHandler() {
        return uncaughtExceptionPreHandler;
    }
    // END Android-added: The concept of an uncaughtExceptionPreHandler for use by platform.

    /**
     * Returns the handler invoked when this thread abruptly terminates
     * due to an uncaught exception. If this thread has not had an
     * uncaught exception handler explicitly set then this thread's
     * {@code ThreadGroup} object is returned, unless this thread
     * has terminated, in which case {@code null} is returned.
     * @since 1.5
     * @return the uncaught exception handler for this thread
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler != null ?
            uncaughtExceptionHandler : group;
    }

    /**
     * Set the handler invoked when this thread abruptly terminates
     * due to an uncaught exception.
     * <p>A thread can take full control of how it responds to uncaught
     * exceptions by having its uncaught exception handler explicitly set.
     * If no such handler is set then the thread's {@code ThreadGroup}
     * object acts as its handler.
     * @param eh the object to use as this thread's uncaught exception
     * handler. If {@code null} then this thread has no explicit handler.
     * @throws  SecurityException  if the current thread is not allowed to
     *          modify this thread.
     * @see #setDefaultUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        checkAccess();
        uncaughtExceptionHandler = eh;
    }

    void uncaughtExceptionHandler(UncaughtExceptionHandler ueh) {
        uncaughtExceptionHandler = ueh;
    }

    /**
     * Dispatch an uncaught exception to the handler. This method is
     * intended to be called only by the runtime and by tests.
     *
     * @hide
     */
    // Android-changed: Make dispatchUncaughtException() public, for use by tests.
    public final void dispatchUncaughtException(Throwable e) {
        // BEGIN Android-added: uncaughtExceptionPreHandler for use by platform.
        Thread.UncaughtExceptionHandler initialUeh =
                Thread.getUncaughtExceptionPreHandler();
        if (initialUeh != null) {
            try {
                initialUeh.uncaughtException(this, e);
            } catch (RuntimeException | Error ignored) {
                // Throwables thrown by the initial handler are ignored
            }
        }
        // END Android-added: uncaughtExceptionPreHandler for use by platform.
        getUncaughtExceptionHandler().uncaughtException(this, e);
    }

    // BEGIN Android-added: The concept of "system-daemon" threads. See java.lang.Daemons.
    /**
     * Marks this thread as either a special runtime-managed ("system daemon")
     * thread or a normal (i.e. app code created) daemon thread.)
     *
     * <p>System daemon threads get special handling when starting up in some
     * cases.
     *
     * <p>This method must be invoked before the thread is started.
     *
     * <p>This method must only be invoked on Thread instances that have already
     * had {@code setDaemon(true)} called on them.
     *
     * <p>Package-private since only {@link java.lang.Daemons} needs to call
     * this.
     *
     * @param  on if {@code true}, marks this thread as a system daemon thread
     *
     * @throws  IllegalThreadStateException
     *          if this thread is {@linkplain #isAlive alive} or not a
     *          {@linkplain #isDaemon daemon}
     *
     * @throws  SecurityException
     *          if {@link #checkAccess} determines that the current
     *          thread cannot modify this thread
     *
     * @hide For use by Daemons.java only.
     */
    final void setSystemDaemon(boolean on) {
        checkAccess();
        if (isAlive() || !isDaemon()) {
            throw new IllegalThreadStateException();
        }
        systemDaemon = on;
    }
    // END Android-added: The concept of "system-daemon" threads. See java.lang.Daemons.

    /**
     * Removes from the specified map any keys that have been enqueued
     * on the specified reference queue.
     */
    static void processQueue(ReferenceQueue<Class<?>> queue,
                             ConcurrentMap<? extends
                             WeakReference<Class<?>>, ?> map)
    {
        Reference<? extends Class<?>> ref;
        while((ref = queue.poll()) != null) {
            map.remove(ref);
        }
    }

    /**
     *  Weak key for Class objects.
     **/
    static class WeakClassKey extends WeakReference<Class<?>> {
        /**
         * saved value of the referent's identity hash code, to maintain
         * a consistent hash code after the referent has been cleared
         */
        private final int hash;

        /**
         * Create a new WeakClassKey to the given object, registered
         * with a queue.
         */
        WeakClassKey(Class<?> cl, ReferenceQueue<Class<?>> refQueue) {
            super(cl, refQueue);
            hash = System.identityHashCode(cl);
        }

        /**
         * Returns the identity hash code of the original referent.
         */
        @Override
        public int hashCode() {
            return hash;
        }

        /**
         * Returns true if the given object is this identical
         * WeakClassKey instance, or, if this object's referent has not
         * been cleared, if the given object is another WeakClassKey
         * instance with the identical non-null referent as this one.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;

            if (obj instanceof WeakClassKey) {
                Class<?> referent = get();
                return (referent != null) &&
                        (((WeakClassKey) obj).refersTo(referent));
            } else {
                return false;
            }
        }
    }


    // The following three initially uninitialized fields are exclusively
    // managed by class java.util.concurrent.ThreadLocalRandom. These
    // fields are used to build the high-performance PRNGs in the
    // concurrent code, and we can not risk accidental false sharing.
    // Hence, the fields are isolated with @Contended.

    /** The current seed for a ThreadLocalRandom */
    @jdk.internal.vm.annotation.Contended("tlr")
    long threadLocalRandomSeed;

    /** Probe hash value; nonzero if threadLocalRandomSeed initialized */
    @jdk.internal.vm.annotation.Contended("tlr")
    int threadLocalRandomProbe;

    /** Secondary seed isolated from public ThreadLocalRandom sequence */
    @jdk.internal.vm.annotation.Contended("tlr")
    int threadLocalRandomSecondarySeed;

    /* Some private helper methods */
    /**
     * Android-changed: Add niceness argument to avoid recomputation.
     *
     * Equivalent to
     *
     *   int n = nicenessForPriority(newPriority);
     *   setNiceness0(n);
     *
     * But it allows us to implement Thread.setPriority() with a single native call.
     * (On Android S, this equivalence is approximate. See implementation.)
     */
    @FastNative
    private native void setPriority0(int newPriority, int newNiceness);

    /**
     * Android-added: Helper methods allowing us to understand the priority to niceness mapping,
     * so that we can process franeworks requests trafficking in niceness as well.
     */

    /**
     * Set the Posix niceness value associated with this thread. Returns an errno value.
     */
    @FastNative
    private native int setNiceness0(int niceness);

    /**
     * A somewhat inefficient way to map Linux niceness to Java priority. We cache the results here.
     * @hide
     */
    // VisibleForTesting
    @CriticalNative
    public static native int priorityForNiceness(int niceness);

    /**
     * A more efficient way to map Java priority to Posix niceness.
     * @hide
     */
    // VisibleForTesting
    @CriticalNative
    public static native int nicenessForPriority(int priority);

    // BEGIN Android-removed: Native methods that are unused on Android.
    /*
    private native void stop0(Object o);
    private native void suspend0();
    private native void resume0();
    */
    // END Android-removed: Native methods that are unused on Android.


    /** The thread container that this thread is in */
    private @Stable ThreadContainer container;
    ThreadContainer threadContainer() {
        return container;
    }
    void setThreadContainer(ThreadContainer container) {
        // assert this.container == null;
        this.container = container;
    }

    /** The top of this stack of stackable scopes owned by this thread */
    private volatile StackableScope headStackableScopes;
    StackableScope headStackableScopes() {
        return headStackableScopes;
    }
    static void setHeadStackableScope(StackableScope scope) {
        currentThread().headStackableScopes = scope;
    }

    @FastNative
    private native void interrupt0();
    // Android-removed: This native method is replaced by the native interrupted() method.
    // private static native void clearInterruptEvent();
    private native void setNativeName(String name);

    // Android-added: Android specific nativeGetStatus() method.
    private native int nativeGetStatus(boolean hasBeenStarted);
}
