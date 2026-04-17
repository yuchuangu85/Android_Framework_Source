/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidMessageQueue.MESSAGE_CODE;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidMessageQueue.MESSAGE_DELAY_MS;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidMessageQueue.RECEIVING_THREAD_NAME;
import static android.internal.perfetto.protos.AndroidTrackEventOuterClass.AndroidTrackEvent.MESSAGE_QUEUE;
import static android.os.Message.*;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodRedirect;
import android.ravenwood.annotation.RavenwoodRedirectionClass;
import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Low-level class holding the list of messages to be dispatched by a
 * {@link Looper}.  Messages are not added directly to a MessageQueue,
 * but rather through {@link Handler} objects associated with the Looper.
 *
 * <p>You can retrieve the MessageQueue for the current thread with
 * {@link Looper#myQueue() Looper.myQueue()}.
 */
@RavenwoodKeepWholeClass
@RavenwoodRedirectionClass("MessageQueue_ravenwood")
public final class MessageQueue {
    private static final String TAG_L = "LegacyMessageQueue";
    private static final String TAG_D = "DeliQueue";
    private static final boolean DEBUG = false;

    /**
     * Enables concurrent message queue implementation in all applications.
     *
     * @hide
     */
    // Make sure MessageQueue_ravenwood's check matches this definition.
    // LINT.IfChange
    @ChangeId
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.BAKLAVA)
    public static final long USE_NEW_MESSAGEQUEUE = 421623328L;
    // LINT.ThenChange(//frameworks/base/core/java/android/os/MessageQueue_ravenwood.java)

    // True if the message queue can be quit.
    @UnsupportedAppUsage
    private final boolean mQuitAllowed;

    @UnsupportedAppUsage
    @SuppressWarnings("unused")
    private long mPtr; // used by native code

    /* ------------------------------------------------------------------------------------------ */
    /* These fields are only used in legacy message queue. */
    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.BAKLAVA,
            publicAlternatives =
                    "To manipulate the queue in Instrumentation tests, use {@link"
                        + " android.os.TestLooperManager}")
    Message mMessages;
    private Message mLast;
    private boolean mQuitting;

    // Indicates whether next() is blocked waiting in pollOnce() with a non-zero timeout.
    private boolean mBlocked;

    // Tracks the number of async message. We use this in enqueueMessage() to avoid searching the
    // queue for async messages when inserting a message at the tail.
    private int mAsyncMessageCount;

    /* ------------------------------------------------------------------------------------------ */
    /* These fields are only used in DeliQueue. */

    private final Object mIdleHandlersLock = new Object();
    private final Object mFileDescriptorRecordsLock = new Object();

    MessageStack mStack = new MessageStack();

    /*
     * This helps us ensure that messages with the same timestamp are inserted in FIFO order.
     * Increments on each insert, starting at 0. MessageNode.compareTo() will compare sequences
     * when delivery timestamps are identical.
     */
    private static final VarHandle sNextInsertSeq;
    private volatile long mNextInsertSeqValue = 0;
    /*
     * The exception to the FIFO order rule is sendMessageAtFrontOfQueue().
     * Those messages must be in LIFO order.
     * Decrements on each front of queue insert.
     */
    private static final VarHandle sNextFrontInsertSeq;
    private volatile long mNextFrontInsertSeqValue = -1;

    private static final VarHandle sWaitState;
    private volatile long mWaitState;

    /*
     * Ref count our access to mPtr.
     * next() doesn't want to dispose of mPtr until after quit() is called.
     * isPolling() also needs to ensure safe access to mPtr.
     * So keep a ref count of access to mPtr. If quitting is set, we disallow new refs.
     * next() will only proceed with disposing of the pointer once all refs are dropped.
     */
    private static final VarHandle sMptrRefCount;
    private volatile long mMptrRefCountValue = 0;

    private volatile Message mSyncBarrier = null;

    /* ------------------------------------------------------------------------------------------ */
    /* These fields/methods are used to determine which of the two MQ impls is used. */

    /**
     * Select between two implementations of message queue. The legacy implementation is used
     * by default as it provides maximum compatibility with applications and tests that
     * reach into MessageQueue via the mMessages field. The DeliQueue implementation is used for
     * system processes and provides a higher level of concurrency and higher enqueue throughput
     * than the legacy implementation.
     */
    private static boolean sUseDeliQueueInitialized = false;
    private static boolean sUseDeliQueue;

    // This isn't named "getUseDeliQueue"; it's referenced from Message.java.
    static boolean getUseConcurrent() {
        // setUseDeliQueue() is always called when starting apps or system_server, but some tests
        // create Loopers directly--in these cases, we still need to initialize sUseDeliQueue.
        setUseDeliQueue(true);
        return sUseDeliQueue;
    }

    /** @hide */
    public static void setUseDeliQueue(boolean enable) {
        if (!sUseDeliQueueInitialized) {
            if (!enable) {
                sUseDeliQueue = false;
            } else {
                final boolean useDeliQueue = computeUseDeliQueue(enable);
                sUseDeliQueue = useDeliQueue;
            }
        }
        sUseDeliQueueInitialized = true;
    }

    @RavenwoodRedirect(bug = 454028089, reason = "change IDs are not initialized when we call it")
    private static boolean computeUseDeliQueue(boolean enable) {
        if (CompatChanges.isChangeEnabled(USE_NEW_MESSAGEQUEUE)
                || Flags.useConcurrentMessageQueueInApps()) {
            return true;
        }

        final String processName = Process.myProcessName();
        if (processName == null) {
            // Assume that this is a host-side test and avoid DeliQueue mode for now.
            return false;
        }

        // We can lift these restrictions in the future after we've made it possible for test
        // authors to test Looper and MessageQueue without resorting to reflection.
        return enable;
    }

    /**
     * @return human-readable string that identifies the implementation.
     * @hide
     */
    public static String getImplName() {
        return "deli:" + getUseConcurrent();
    }

    /* ------------------------------------------------------------------------------------------ */

    /**
     * Skip epoll_wait syscalls if nativePollOnce is called with a timeout of 0, which indicates
     * that there are already pending messages.
     */
    static void setSkipEpollWaitForZeroTimeout(long ptr) {
        if (Flags.nativeLooperSkipEpollWaitForZeroTimeout()) {
            nativeSetSkipEpollWaitForZeroTimeout(ptr);
        }
    }

    private native static long nativeInit();
    private native static void nativeDestroy(long ptr);
    @UnsupportedAppUsage
    private native void nativePollOnce(long ptr, int timeoutMillis); /*non-static for callbacks*/

    private native static void nativeWake(long ptr);
    private native static boolean nativeIsPolling(long ptr);
    private native static void nativeSetFileDescriptorEvents(long ptr, int fd, int events);
    private native static void nativeSetSkipEpollWaitForZeroTimeout(long ptr);

    @UnsupportedAppUsage
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<IdleHandler>();
    private SparseArray<FileDescriptorRecord> mFileDescriptorRecords;
    private IdleHandler[] mPendingIdleHandlers;

    private final AtomicLong mMessageCount = new AtomicLong();
    private final Thread mLooperThread;
    private final String mThreadName;
    private final long mTid;

    static {
        try {
            // We need to use VarHandle rather than java.util.concurrent.atomic.*
            // for performance reasons. See: b/421437036
            MethodHandles.Lookup l = MethodHandles.lookup();
            sNextInsertSeq = l.findVarHandle(MessageQueue.class, "mNextInsertSeqValue",
                    long.class);
            sNextFrontInsertSeq = l.findVarHandle(MessageQueue.class, "mNextFrontInsertSeqValue",
                    long.class);
            sWaitState = l.findVarHandle(MessageQueue.class, "mWaitState",
                    long.class);
            sMptrRefCount = l.findVarHandle(MessageQueue.class, "mMptrRefCountValue",
                    long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Use MSB to indicate mPtr teardown state. Lower 63 bits hold ref count.
    private static final long MPTR_TEARDOWN_MASK = 1L << 63;

    /**
     * Increment the mPtr ref count.
     *
     * If this method returns true then the caller may use mPtr until they call
     * {@link #decrementMptrRefs()}.
     * If this method returns false then the caller must not use mPtr, and must
     * instead assume that the MessageQueue is quitting or has already quit and
     * act accordingly.
     */
    private boolean incrementMptrRefs() {
        while (true) {
            final long oldVal = mMptrRefCountValue;
            if ((oldVal & MPTR_TEARDOWN_MASK) != 0) {
                // If we're quitting then we're not allowed to increment the ref count.
                return false;
            }
            if (sMptrRefCount.compareAndSet(this, oldVal, oldVal + 1L)) {
                // Successfully incremented the ref count without quitting.
                return true;
            }
        }
    }

    /**
     * Decrement the mPtr ref count.
     *
     * Call after {@link #incrementMptrRefs()} to release the ref on mPtr.
     */
    private void decrementMptrRefs() {
        long oldVal = (long) sMptrRefCount.getAndAdd(this, -1L);
        // If quitting and we were the last ref, wake up looper thread
        if (oldVal - 1 == MPTR_TEARDOWN_MASK) {
            LockSupport.unpark(mLooperThread);
        }
    }

    /**
     * Wake the looper thread.
     *
     * {@link #nativeWake(long)} may be called directly only by the looper thread.
     * Otherwise, call this method to ensure safe access to mPtr.
     */
    private void concurrentWake() {
        if (incrementMptrRefs()) {
            try {
                nativeWake(mPtr);
            } finally {
                decrementMptrRefs();
            }
        }
    }

    // Must only be called from looper thread
    private void setMptrTeardownAndWaitForRefsToDrop() {
        if (DEBUG && Thread.currentThread() != mLooperThread) {
            throw new IllegalStateException(
                    "setMptrTeardownAndWaitForRefsToDrop must only be called from looper thread");
        }
        while (true) {
            final long oldVal = mMptrRefCountValue;
            if (sMptrRefCount.compareAndSet(this, oldVal, oldVal | MPTR_TEARDOWN_MASK)) {
                // Successfully set teardown state.
                break;
            }
        }

        boolean wasInterrupted = false;
        try {
            while ((mMptrRefCountValue & ~MPTR_TEARDOWN_MASK) != 0) {
                LockSupport.park();
                wasInterrupted |= Thread.interrupted();
            }
        } finally {
            if (wasInterrupted) {
                mLooperThread.interrupt();
            }
        }
    }

    private static boolean isBarrier(Message msg) {
        return msg != null && msg.target == null;
    }

    MessageQueue(boolean quitAllowed) {
        getUseConcurrent();
        mQuitAllowed = quitAllowed;
        mPtr = nativeInit();
        mLooperThread = Thread.currentThread();
        mThreadName = mLooperThread.getName();
        mTid = Process.myTid();
        if (sUseDeliQueue) {
            long now = SystemClock.uptimeMillis();
            mWaitState = WaitState.composeDeadline(now + INDEFINITE_TIMEOUT_MS, false);
        }
        setSkipEpollWaitForZeroTimeout(mPtr);
    }

    Thread getLooperThread() {
        return mLooperThread;
    }

    // Disposes of the underlying message queue.
    // Must only be called on the looper thread or the finalizer.
    private void dispose() {
        if (mPtr != 0) {
            nativeDestroy(mPtr);
            mPtr = 0;
        }
    }

    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }

    @NeverCompile
    private static void logDeadThread(Message msg) {
        IllegalStateException e = new IllegalStateException(
                msg.target + " sending message to a Handler on a dead thread");
        Log.w(sUseDeliQueue ? TAG_D : TAG_L, e.getMessage(), e);
        msg.recycleUnchecked();
    }

    boolean enqueueMessage(Message msg, long when) {
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }

        if (sUseDeliQueue) {
            return enqueueMessageDeliQueue(msg, when);
        } else {
            return enqueueMessageLegacy(msg, when);
        }
    }

    private boolean enqueueMessageUnchecked(@NonNull Message msg, long when) {
        long seq = when != 0 ? ((long) sNextInsertSeq.getAndAdd(this, 1L) + 1L)
                : ((long) sNextFrontInsertSeq.getAndAdd(this, -1L) - 1L);
        msg.when = when;
        msg.insertSeq = seq;
        msg.markInUse();
        incAndTraceMessageCount(msg, when);

        if (!mStack.pushMessage(msg)) {
            logDeadThread(msg);
            decAndTraceMessageCount();
            return false;
        }

        if (DEBUG) {
            Log.d(TAG_D, "Insert message"
                    + " what: " + msg.what
                    + " when: " + msg.when
                    + " seq: " + msg.insertSeq
                    + " barrier: " + isBarrier(msg)
                    + " async: " + msg.isAsynchronous()
                    + " now: " + SystemClock.uptimeMillis());
        }

        while (true) {
            final long waitState = mWaitState;
            final long newWaitState;
            final boolean needWake;
            final Message checkBarrier;

            if (WaitState.isCounter(waitState)) {
                // Looper is already awake
                newWaitState = WaitState.incrementCounter(waitState);
                checkBarrier = null;
                needWake = false;
            } else if (msg.when >= WaitState.getTSMillis(waitState)) {
                // The enqueued message is not earlier than the current wake
                // deadline, so we don't need to wake.
                newWaitState = WaitState.incrementDeadline(waitState);
                checkBarrier = null;
                needWake = false;
            } else if (msg.isAsynchronous()) {
                // The enqueued message has an earlier deadline.
                // It is async, so it can bypass barriers.
                newWaitState = WaitState.initCounter();
                checkBarrier = null;
                needWake = true;
            } else {
                // We may need to wake up, depending on the state of the sync barrier.
                Message barrier = WaitState.hasSyncBarrier(waitState) ? mSyncBarrier : null;
                boolean blockedByBarrier =
                        barrier != null && Message.compareMessages(barrier, msg) < 0;
                if (blockedByBarrier) {
                    newWaitState = WaitState.incrementDeadline(waitState);
                    checkBarrier = barrier;
                    needWake = false;
                } else {
                    newWaitState = WaitState.initCounter();
                    checkBarrier = null;
                    needWake = true;
                }
            }

            if (sWaitState.compareAndSet(this, waitState, newWaitState)) {
                if (checkBarrier != null && checkBarrier != mSyncBarrier) {
                    /*
                    * If barrier state changed underneath us and we chose not to wake the
                    * looper thread, we have to recheck to ensure that the barrier we saw was
                    * actually in place while we did the CAS.
                    */
                    continue;
                }
                if (needWake) {
                    concurrentWake();
                }
                return true;
            }
            // Failed to update wait state, loop and retry
        }
    }

    private boolean enqueueMessageDeliQueue(Message msg, long when) {
        if (msg.isInUse()) {
            throw new IllegalStateException(msg + " This message is already in use.");
        }
        return enqueueMessageUnchecked(msg, when);
    }

    private boolean enqueueMessageLegacy(Message msg, long when) {
        synchronized (this) {
            if (msg.isInUse()) {
                throw new IllegalStateException(msg + " This message is already in use.");
            }

            if (mQuitting) {
                logDeadThread(msg);
                return false;
            }

            msg.markInUse();
            msg.when = when;
            incAndTraceMessageCount(msg, when);

            Message p = mMessages;
            boolean needWake;
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
                if (p == null) {
                    mLast = mMessages;
                }
            } else {
                // Message is to be inserted at tail or middle of queue. Usually we don't have to
                // wake up the event queue unless there is a barrier at the head of the queue and
                // the message is the earliest asynchronous message in the queue.
                needWake = mBlocked && p.target == null && msg.isAsynchronous();

                // For readability, we split this portion of the function into two blocks based on
                // whether tail tracking is enabled. This has a minor implication for the case
                // where tail tracking is disabled. See the comment below.
                if (when >= mLast.when) {
                    needWake = needWake && mAsyncMessageCount == 0;
                    msg.next = null;
                    mLast.next = msg;
                    mLast = msg;
                } else {
                    // Inserted within the middle of the queue.
                    Message prev;
                    for (;;) {
                        prev = p;
                        p = p.next;
                        if (p == null || when < p.when) {
                            break;
                        }
                        if (needWake && p.isAsynchronous()) {
                            needWake = false;
                        }
                    }
                    if (p == null) {
                        /* Inserting at tail of queue */
                        mLast = msg;
                    }
                    msg.next = p; // invariant: p == prev.next
                    prev.next = msg;
                }
            }

            if (msg.isAsynchronous()) {
                mAsyncMessageCount++;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }

    /* These are only read/written from the looper thread */
    private int mNextPollTimeoutMillis;
    private static int INDEFINITE_TIMEOUT_MS = 600_000_000;
    private boolean mWorkerShouldQuit;
    Message nextMessage(boolean peek, boolean returnEarliest) {
        while (true) {
            /*
             * Once we are converted to a counter only the looper thread can change waitstate back
             * to a timestamp
             */
            long oldWaitState = mWaitState;
            final long zeroCounter = WaitState.initCounter();
            while (!WaitState.isCounter(oldWaitState)) {
                if (sWaitState.compareAndSet(this, oldWaitState, zeroCounter)) {
                    oldWaitState = zeroCounter;
                    break;
                }
                oldWaitState = mWaitState;
            }

            mStack.heapSweep();
            mStack.drainFreelist();

            boolean shouldRemoveMessages = false;
            if (mStack.isQuitting()) {
                if (!mWorkerShouldQuit) {
                    mWorkerShouldQuit = true;
                    long TS = mStack.getQuittingTimestamp();
                    if (TS == 0) {
                        removeAllMessages();
                    } else {
                        removeAllFutureMessages(TS);
                    }
                }
            }

            Message msg = mStack.peek(false);
            Message asyncMsg = mStack.peek(true);
            final long now = SystemClock.uptimeMillis();

            if (DEBUG) {
                if (msg != null) {
                    Log.d(TAG_D, "Next found node"
                            + " what: " + msg.what
                            + " when: " + msg.when
                            + " seq: " + msg.insertSeq
                            + " barrier: " + isBarrier(msg)
                            + " now: " + now);
                }
                if (asyncMsg != null) {
                    Log.d(TAG_D, "Next found async node"
                            + " what: " + asyncMsg.what
                            + " when: " + asyncMsg.when
                            + " seq: " + asyncMsg.insertSeq
                            + " barrier: " + isBarrier(asyncMsg)
                            + " now: " + now);
                }
            }

            /*
            * the node which we will return, null if none are ready
            */
            Message found = null;
            /*
            * The node from which we will determine our next wakeup time.
            * Null indicates there is no next message ready. If we found a node,
            * we can leave this null as Looper will call us again after delivering
            * the message.
            */
            Message next = null;
            Message syncBarrier = null;
            /*
            * If we have a barrier we should return the async node (if it exists and is ready)
            */
            if (isBarrier(msg)) {
                if (asyncMsg != null && (returnEarliest || now >= asyncMsg.when)) {
                    found = asyncMsg;
                } else {
                    syncBarrier = msg;
                    next = asyncMsg;
                }
            } else { /* No barrier. */
                // Pick the earliest of the next sync and async messages, if any.
                Message earliest = msg;
                if (msg == null) {
                    earliest = asyncMsg;
                } else if (asyncMsg != null) {
                    if (Message.compareMessages(msg, asyncMsg) > 0) {
                        earliest = asyncMsg;
                    }
                }

                if (earliest != null) {
                    if (returnEarliest || now >= earliest.when) {
                        found = earliest;
                    } else {
                        next = earliest;
                    }
                }
            }

            if (DEBUG) {
                if (found != null) {
                    Log.d(TAG_D, "Will deliver node"
                            + " what: " + found.what
                            + " when: " + found.when
                            + " seq: " + found.insertSeq
                            + " barrier: " + isBarrier(found)
                            + " async: " + found.isAsynchronous()
                            + " now: " + now);
                } else {
                    Log.d(TAG_D, "No node to deliver");
                }
                if (next != null) {
                    Log.d(TAG_D, "Next node"
                            + " what: " + next.what
                            + " when: " + next.when
                            + " seq: " + next.insertSeq
                            + " barrier: " + isBarrier(next)
                            + " async: " + next.isAsynchronous()
                            + " now: " + now);
                } else {
                    Log.d(TAG_D, "No next node");
                }
            }

            /*
             * If we have a found message, we will get called again so there's no need to set
             * state.
             *
             * Otherwise we should determine how to park the thread.
             * mNextPollTimeoutMillis has some special meanings which don't translate directly to
             * the deadline required in our WaitState. Use a separate variable to track waitstate
             * deadline.
             */
            long nextDeadline = 0;
            if (found == null) {
                if (mWorkerShouldQuit) {
                    // Set to zero so we don't block the looper and can quit immediately.
                    mNextPollTimeoutMillis = 0;
                } else if (next == null) {
                    /* No message to deliver, sleep indefinitely */
                    mNextPollTimeoutMillis = INDEFINITE_TIMEOUT_MS;
                    nextDeadline = now + INDEFINITE_TIMEOUT_MS;
                    if (DEBUG) {
                        Log.d(TAG_D, "nextMessage next wait state is INDEFINITE_TIMEOUT_MS");
                    }
                } else {
                    /* Message not ready, or we found one to deliver already, set a timeout */
                    long nextMessageWhen = next.when;
                    if (nextMessageWhen > now) {
                        mNextPollTimeoutMillis = (int) Math.min(nextMessageWhen - now,
                                Integer.MAX_VALUE);
                        nextDeadline = now + mNextPollTimeoutMillis;
                    } else {
                        mNextPollTimeoutMillis = 0;
                    }

                    if (DEBUG) {
                        Log.d(TAG_D, "nextMessage next wait "
                                + " timeout ms " + mNextPollTimeoutMillis
                                + " now: " + now);
                    }
                }
            }

            mSyncBarrier = syncBarrier;
            /*
             * Try to swap waitstate back from a counter to a deadline. If we can't then that means
             * the counter was incremented and we need to loop back to pick up any new items.
             */
            if (!sWaitState.compareAndSet(this, oldWaitState,
                    WaitState.composeDeadline(nextDeadline, syncBarrier != null))) {
                continue;
            }
            if (found != null || nextDeadline != 0) {
                if (found != null && !peek) {
                    if (!found.markRemoved()) {
                        continue;
                    }
                    mStack.remove(found);
                }
                return found;
            }
            return null;
        }
    }

    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.BAKLAVA,
            publicAlternatives =
                    "To manipulate the queue in Instrumentation tests, use {@link"
                        + " android.os.TestLooperManager}")
    Message next() {
        if (sUseDeliQueue) {
            return nextDeliQueue();
        } else {
            return nextLegacy();
        }
    }

    private Message nextDeliQueue() {
        final long ptr = mPtr;
        if (ptr == 0) {
            return null;
        }

        mNextPollTimeoutMillis = 0;
        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        while (true) {
            if (mNextPollTimeoutMillis != 0) {
                Binder.flushPendingCommands();
            }

            // TODO: mMessageDirectlyQueued = false; when implementing direct enqueue
            nativePollOnce(ptr, mNextPollTimeoutMillis);

            Message msg = nextMessage(false, false);
            if (msg != null) {
                msg.markInUse();
                decAndTraceMessageCount();
                return msg;
            }

            // Prevent any race between quit()/nativeWake(), dispose() and other users of mPtr
            if (mWorkerShouldQuit) {
                setMptrTeardownAndWaitForRefsToDrop();
                dispose();
                return null;
            }

            synchronized (mIdleHandlersLock) {
                // If first time idle, then get the number of idlers to run.
                // Idle handles only run if the queue is empty or if the first message
                // in the queue (possibly a barrier) is due to be handled in the future.
                if (pendingIdleHandlerCount < 0
                        && looperCheckIsIdle()) {
                    pendingIdleHandlerCount = mIdleHandlers.size();
                }
                if (pendingIdleHandlerCount <= 0) {
                    // No idle handlers to run.  Loop and wait some more.
                    continue;
                }

                if (mPendingIdleHandlers == null) {
                    mPendingIdleHandlers = new IdleHandler[Math.max(pendingIdleHandlerCount, 4)];
                }
                mPendingIdleHandlers = mIdleHandlers.toArray(mPendingIdleHandlers);
            }

            // Run the idle handlers.
            // We only ever reach this code block during the first iteration.
            for (int i = 0; i < pendingIdleHandlerCount; i++) {
                final IdleHandler idler = mPendingIdleHandlers[i];
                mPendingIdleHandlers[i] = null; // release the reference to the handler

                boolean keep = false;
                try {
                    keep = idler.queueIdle();
                } catch (Throwable t) {
                    Log.wtf(TAG_D, "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (mIdleHandlersLock) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            // Reset the idle handler count to 0 so we do not run them again.
            pendingIdleHandlerCount = 0;

            // While calling an idle handler, a new message could have been delivered
            // so go back and look again for a pending message without waiting.
            mNextPollTimeoutMillis = 0;
        }
    }

    private Message nextLegacy() {
        // Return here if the message loop has already quit and been disposed.
        // This can happen if the application tries to restart a looper after quit
        // which is not supported.
        final long ptr = mPtr;
        if (ptr == 0) {
            return null;
        }

        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        int nextPollTimeoutMillis = 0;
        for (;;) {
            if (nextPollTimeoutMillis != 0) {
                Binder.flushPendingCommands();
            }

            nativePollOnce(ptr, nextPollTimeoutMillis);

            synchronized (this) {
                // Try to retrieve the next message.  Return if found.
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = mMessages;
                if (msg != null && msg.target == null) {
                    // Stalled by a barrier.  Find the next asynchronous message in the queue.
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                    } while (msg != null && !msg.isAsynchronous());
                }
                if (msg != null) {
                    if (now < msg.when) {
                        // Next message is not ready.  Set a timeout to wake up when it is ready.
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        // Got a message.
                        mBlocked = false;
                        if (prevMsg != null) {
                            prevMsg.next = msg.next;
                            if (prevMsg.next == null) {
                                mLast = prevMsg;
                            }
                        } else {
                            mMessages = msg.next;
                            if (msg.next == null) {
                                mLast = null;
                            }
                        }
                        msg.next = null;
                        if (DEBUG) Log.v(TAG_L, "Returning message: " + msg);
                        msg.markInUse();
                        if (msg.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        decAndTraceMessageCount();
                        return msg;
                    }
                } else {
                    // No more messages.
                    nextPollTimeoutMillis = -1;
                }

                // Process the quit message now that all pending messages have been handled.
                if (mQuitting) {
                    dispose();
                    return null;
                }

                // If first time idle, then get the number of idlers to run.
                // Idle handles only run if the queue is empty or if the first message
                // in the queue (possibly a barrier) is due to be handled in the future.
                if (pendingIdleHandlerCount < 0
                        && (mMessages == null || now < mMessages.when)) {
                    pendingIdleHandlerCount = mIdleHandlers.size();
                }
                if (pendingIdleHandlerCount <= 0) {
                    // No idle handlers to run.  Loop and wait some more.
                    mBlocked = true;
                    continue;
                }

                if (mPendingIdleHandlers == null) {
                    mPendingIdleHandlers = new IdleHandler[Math.max(pendingIdleHandlerCount, 4)];
                }
                mPendingIdleHandlers = mIdleHandlers.toArray(mPendingIdleHandlers);
            }

            // Run the idle handlers.
            // We only ever reach this code block during the first iteration.
            for (int i = 0; i < pendingIdleHandlerCount; i++) {
                final IdleHandler idler = mPendingIdleHandlers[i];
                mPendingIdleHandlers[i] = null; // release the reference to the handler

                boolean keep = false;
                try {
                    keep = idler.queueIdle();
                } catch (Throwable t) {
                    Log.wtf(TAG_L, "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (this) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            // Reset the idle handler count to 0 so we do not run them again.
            pendingIdleHandlerCount = 0;

            // While calling an idle handler, a new message could have been delivered
            // so go back and look again for a pending message without waiting.
            nextPollTimeoutMillis = 0;
        }
    }

    /**
     * Returns true if the looper has no pending messages which are due to be processed
     * and is not blocked on a sync barrier.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is idle.
     */
    public boolean isIdle() {
        if (sUseDeliQueue) {
            return isIdleDeliQueue();
        } else {
            return isIdleLegacy();
        }
    }

    private boolean isIdleDeliQueue() {
        final long now = SystemClock.uptimeMillis();
        return !mStack.hasMessages(sMatchDeliverableMessages, null, -1, null, null, now);
    }

    /**
     * isIdle() variant for DeliQueue looper thread.
     * We avoid the stack search and go directly to our heaps.
     * This method is only to be called from the looper thread.
     */
    private boolean looperCheckIsIdle() {
        mStack.heapSweep();

        final long now = SystemClock.uptimeMillis();
        Message msg = mStack.peek(false);
        if (msg != null && msg.when <= now) {
            return false;
        }

        Message asyncMsg = mStack.peek(true);
        if (asyncMsg != null && asyncMsg.when <= now) {
            return false;
        }

        return true;
    }

    private boolean isIdleLegacy() {
        synchronized (this) {
            final long now = SystemClock.uptimeMillis();
            return mMessages == null || now < mMessages.when;
        }
    }

    /**
     * Returns whether this looper's thread is currently polling for more work to do.
     * This is a good signal that the loop is still alive rather than being stuck
     * handling a callback.  Note that this method is intrinsically racy, since the
     * state of the loop can change before you get the result back.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is currently polling for events.
     * @hide
     */
    public boolean isPolling() {
        if (sUseDeliQueue) {
            return isPollingDeliQueue();
        } else {
            return isPollingLegacy();
        }
    }

    private boolean isPollingDeliQueue() {
        // If the loop is quitting then it must not be idling.
        if (!mStack.isQuitting() && incrementMptrRefs()) {
            try {
                return nativeIsPolling(mPtr);
            } finally {
                decrementMptrRefs();
            }
        }
        return false;
    }

    private boolean isPollingLegacy() {
        synchronized (this) {
            return isPollingLocked();
        }
    }

    private boolean isPollingLocked() {
        // If the loop is quitting then it must not be idling.
        // We can assume mPtr != 0 when mQuitting is false.
        return !mQuitting && nativeIsPolling(mPtr);
    }

    /**
     * Returns the message with the latest scheduled execution time.
     *
     *
     * Caller must ensure that this doesn't race 'next' from the Looper thread.
     * @hide
     */
    public @Nullable Message peekLastMessageForTest() {
        ActivityThread.throwIfNotInstrumenting();
        if (sUseDeliQueue) {
            return peekLastMessageDeliQueue();
        } else {
            return peekLastMessageLegacy();
        }
    }

    private Message peekLastMessageDeliQueue() {
        return mStack.peekLastMessageForTest();
    }

    private Message peekLastMessageLegacy() {
        synchronized (this) {
            Message lastMsg = null;

            Message current = mMessages;
            while (current != null) {
                if (current.target != null && (lastMsg == null || lastMsg.when <= current.when)) {
                    lastMsg = current;
                }
                current = current.next;
            }

            return lastMsg;
        }
    }

    /**
     * Resets this queue's state.
     *
     * @hide
     */
    public void resetForTest() {
        ActivityThread.throwIfNotInstrumenting();
        onResetForTestCalled();
        if (sUseDeliQueue) {
            resetDeliQueue();
        } else {
            resetLegacy();
        }
    }

    @RavenwoodRedirect
    private static void onResetForTestCalled() {
    }

    private void resetDeliQueue() {
        // This queue is already quitting, so we can't reset its state and continue using it.
        if (mWorkerShouldQuit) {
            return;
        }
        synchronized (mIdleHandlersLock) {
            mIdleHandlers.clear();
        }
        synchronized (mFileDescriptorRecordsLock) {
            removeAllFdRecords();
        }
        removeAllMessages();

        // We reset the sync barrier tokens to reflect the queue's state reset. This helps ensure
        // that the queue's behavior is deterministic in both individual tests and in a test suite.
        resetSyncBarrierTokens();
    }

    private void resetLegacy() {
        synchronized (this) {
            // This queue is already quitting, so we can't reset its state and continue using it.
            if (mQuitting) {
                return;
            }
            mIdleHandlers.clear();
            removeAllFdRecords();
            removeAllMessagesLocked();
            // We reset the sync barrier tokens to reflect the queue's state reset. This helps
            // ensure that the queue's behavior is deterministic in both individual tests and in a
            // test suite.
            resetSyncBarrierTokens();
            nativeWake(mPtr);
        }
    }

    private void removeAllFdRecords() {
        if (mFileDescriptorRecords != null) {
            while (mFileDescriptorRecords.size() > 0) {
                removeOnFileDescriptorEventListener(mFileDescriptorRecords.valueAt(0).mDescriptor);
            }
        }
    }

    private void resetSyncBarrierTokens() {
        mNextBarrierTokenAtomic.set(1);
        mNextBarrierToken = 0;
    }

    void quit(boolean safe) {
        if (!mQuitAllowed) {
            throw new IllegalStateException("Main thread not allowed to quit.");
        }

        if (sUseDeliQueue) {
            /*
             * TODO: FIX - It's possible to get an ordering where the handler's post succeeds (returns
             * true) but does not run. That's because you could read different timestamps as "now" in
             * each one, and the quit observes a sooner timestamp despite losing the first CAS.
             */
            long ts = safe ? SystemClock.uptimeMillis() : 0;
            if (mStack.pushQuitting(ts)) {
                if (incrementMptrRefs()) {
                    try {
                        nativeWake(mPtr);
                    } finally {
                        decrementMptrRefs();
                    }
                }
            }
        } else {
            synchronized (this) {
                if (mQuitting) {
                    return;
                }
                mQuitting = true;

                if (safe) {
                    removeAllFutureMessagesLocked();
                } else {
                    removeAllMessagesLocked();
                }

                // We can assume mPtr != 0 because mQuitting was previously false.
                nativeWake(mPtr);
            }
        }
    }

    // The next barrier token.
    // Barriers are indicated by messages with a null target whose arg1 field carries the token.
    private final AtomicInteger mNextBarrierTokenAtomic = new AtomicInteger(1);

    // Must retain this for compatibility reasons.
    @UnsupportedAppUsage
    private int mNextBarrierToken;

    /**
     * Posts a synchronization barrier to the Looper's message queue.
     *
     * Message processing occurs as usual until the message queue encounters the
     * synchronization barrier that has been posted.  When the barrier is encountered,
     * later synchronous messages in the queue are stalled (prevented from being executed)
     * until the barrier is released by calling {@link #removeSyncBarrier} and specifying
     * the token that identifies the synchronization barrier.
     *
     * This method is used to immediately postpone execution of all subsequently posted
     * synchronous messages until a condition is met that releases the barrier.
     * Asynchronous messages (see {@link Message#isAsynchronous} are exempt from the barrier
     * and continue to be processed as usual.
     *
     * This call must be always matched by a call to {@link #removeSyncBarrier} with
     * the same token to ensure that the message queue resumes normal operation.
     * Otherwise the application will probably hang!
     *
     * @return A token that uniquely identifies the barrier.  This token must be
     * passed to {@link #removeSyncBarrier} to release the barrier.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public int postSyncBarrier() {
        if (sUseDeliQueue) {
            return onSyncBarrierPosted(postSyncBarrierDeliQueue());
        } else {
            return onSyncBarrierPosted(postSyncBarrierLegacy());
        }
    }

    @RavenwoodRedirect
    private int onSyncBarrierPosted(int token) {
        return token;
    }

    private int postSyncBarrierDeliQueue() {
        long when = SystemClock.uptimeMillis();
        final int token = mNextBarrierTokenAtomic.getAndIncrement();

        // b/376573804: apps and tests may expect to be able to use reflection
        // to read this value. Make some effort to support this legacy use case.
        mNextBarrierToken = token + 1;

        final Message msg = Message.obtain();

        msg.markInUse();
        msg.arg1 = token;

        if (!enqueueMessageUnchecked(msg, when)) {
            Log.wtf(TAG_D, "Unexpected error while adding sync barrier!");
            return -1;
        }

        return token;
    }

    private int postSyncBarrierLegacy() {
        long when = SystemClock.uptimeMillis();
        synchronized (this) {
            final int token = mNextBarrierToken++;
            final Message msg = Message.obtain();
            msg.markInUse();
            msg.when = when;
            msg.arg1 = token;
            incAndTraceMessageCount(msg, when);

            if (mLast != null && mLast.when <= when) {
                /* Message goes to tail of list */
                mLast.next = msg;
                mLast = msg;
                msg.next = null;
                return token;
            }

            Message prev = null;
            Message p = mMessages;
            if (when != 0) {
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
            }

            if (p == null) {
                /* We reached the tail of the list, or list is empty. */
                mLast = msg;
            }

            if (prev != null) { // invariant: p == prev.next
                msg.next = p;
                prev.next = msg;
            } else {
                msg.next = p;
                mMessages = msg;
            }
            return token;
        }
    }

    /**
     * Removes a synchronization barrier.
     *
     * @param token The synchronization barrier token that was returned by
     * {@link #postSyncBarrier}.
     *
     * @throws IllegalStateException if the barrier was not found.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public void removeSyncBarrier(int token) {
        if (sUseDeliQueue) {
            removeSyncBarrierDeliQueue(token);
        } else {
            removeSyncBarrierLegacy(token);
        }
        onSyncBarrierRemoved(token);
    }

    @RavenwoodRedirect
    private void onSyncBarrierRemoved(int token) {
    }

    private void removeSyncBarrierDeliQueue(int token) {
        final boolean removed = mStack.moveSyncBarrierToFreelist(token);
        if (!removed) {
            throw new IllegalStateException("The specified message queue synchronization "
                    + " barrier token has not been posted or has already been removed.");
        }
        maybeDrainFreelist();
        decAndTraceMessageCount();

        boolean needWake;
        while (true) {
            long waitState = mWaitState;
            long newWaitState;

            if (WaitState.isCounter(waitState)) {
                // Thread is already awake and processing messages
                newWaitState = WaitState.incrementCounter(waitState);
                needWake = false;
            } else if (!WaitState.hasSyncBarrier(waitState)) {
                // Thread is asleep but not waiting on sync barrier
                newWaitState = WaitState.incrementDeadline(waitState);
                needWake = false;
            } else {
                // Thread is asleep, wake up
                newWaitState = WaitState.initCounter();
                needWake = true;
            }
            if (sWaitState.compareAndSet(this, waitState, newWaitState)) {
                break;
            }
        }
        if (needWake) {
            // Wake up next() in case it was sleeping on this barrier.
            concurrentWake();
        }
    }

    private void removeSyncBarrierLegacy(int token) {
        synchronized (this) {
            Message prev = null;
            Message p = mMessages;
            while (p != null && (p.target != null || p.arg1 != token)) {
                prev = p;
                p = p.next;
            }
            if (p == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            final boolean needWake;
            if (prev != null) {
                prev.next = p.next;
                if (prev.next == null) {
                    mLast = prev;
                }
                needWake = false;
            } else {
                mMessages = p.next;
                if (mMessages == null) {
                    mLast = null;
                }
                needWake = mMessages == null || mMessages.target != null;
            }
            p.recycleUnchecked();
            decAndTraceMessageCount();

            // If the loop is quitting then it is already awake.
            // We can assume mPtr != 0 when mQuitting is false.
            if (needWake && !mQuitting) {
                nativeWake(mPtr);
            }
        }
    }

    /**
     * Get the timestamp of the next executable message in our priority queue.
     * Returns null if there are no messages ready for delivery.
     *
     * Caller must ensure that this doesn't race 'next' from the Looper thread.
     */
    @SuppressLint("VisiblySynchronized") // Legacy MessageQueue synchronizes on this
    Long peekWhenForTest() {
        ActivityThread.throwIfNotInstrumenting();
        Message ret;
        if (sUseDeliQueue) {
            ret = nextMessage(true, true);
        } else {
            ret = legacyPeekOrPoll(true);
        }
        return ret != null ? ret.when : null;
    }

    /**
     * Return the next executable message in our priority queue.
     * Returns null if there are no messages ready for delivery
     *
     * Caller must ensure that this doesn't race 'next' from the Looper thread.
     */
    @SuppressLint("VisiblySynchronized") // Legacy MessageQueue synchronizes on this
    @Nullable
    Message pollForTest() {
        ActivityThread.throwIfNotInstrumenting();
        if (sUseDeliQueue) {
            return nextMessage(false, true);
        } else {
            return legacyPeekOrPoll(false);
        }
    }

    private Message legacyPeekOrPoll(boolean peek) {
        synchronized (this) {
            // Try to retrieve the next message.  Return if found.
            final long now = SystemClock.uptimeMillis();
            Message prevMsg = null;
            Message msg = mMessages;
            if (msg != null && msg.target == null) {
                // Stalled by a barrier.  Find the next asynchronous message in the queue.
                do {
                    prevMsg = msg;
                    msg = msg.next;
                } while (msg != null && !msg.isAsynchronous());
            }
            if (msg != null) {
                if (peek) {
                    return msg;
                }
                if (now >= msg.when) {
                    // Got a message.
                    mBlocked = false;
                }
                if (prevMsg != null) {
                    prevMsg.next = msg.next;
                    if (prevMsg.next == null) {
                        mLast = prevMsg;
                    }
                } else {
                    mMessages = msg.next;
                    if (msg.next == null) {
                        mLast = null;
                    }
                }
                msg.next = null;
                msg.markInUse();
                if (msg.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                decAndTraceMessageCount();
                return msg;
            }
        }
        return null;
    }


    /**
     * @return true if we are blocked on a sync barrier
     *
     * Calls to this method must not be allowed to race with `next`.
     * Specifically, the Looper thread must be paused before calling this method,
     * and may not be resumed until after returning from this method.
     */
    boolean isBlockedOnSyncBarrier() {
        ActivityThread.throwIfNotInstrumenting();
        if (sUseDeliQueue) {
            // Call nextMessage to process any pending barriers
            nextMessage(true, false);
            Message asyncMsg = mStack.peek(true);

            return mSyncBarrier != null &&
                    (asyncMsg == null || asyncMsg.when <= mSyncBarrier.when);
        } else {
            Message msg = mMessages;
            return msg != null && msg.target == null;
        }
    }

    void maybeDrainFreelist() {
        if (Thread.currentThread() == mLooperThread) {
            mStack.drainFreelist();
        }
    }

    boolean hasMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }
        if (sUseDeliQueue) {
            return hasMessagesDeliQueue(h, what, object);
        } else {
            return hasMessagesLegacy(h, what, object);
        }
    }

    private boolean hasMessagesDeliQueue(Handler h, int what, Object object) {
        return mStack.hasMessages(sMatchHandlerWhatAndObject, h, what, object, null, 0);
    }

    private boolean hasMessagesLegacy(Handler h, int what, Object object) {
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.what == what && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    boolean hasEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }
        if (sUseDeliQueue) {
            return hasEqualMessagesDeliQueue(h, what, object);
        } else {
            return hasEqualMessagesLegacy(h, what, object);
        }
    }

    private boolean hasEqualMessagesDeliQueue(Handler h, int what, Object object) {
        return mStack.hasMessages(sMatchHandlerWhatAndObjectEquals, h, what, object, null, 0);
    }

    private boolean hasEqualMessagesLegacy(Handler h, int what, Object object) {
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.what == what && (object == null || object.equals(p.obj))) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    boolean hasMessages(Handler h, Runnable r, Object object) {
        if (h == null) {
            return false;
        }
        if (sUseDeliQueue) {
            return hasMessagesDeliQueue(h, r, object);
        } else {
            return hasMessagesLegacy(h, r, object);
        }
    }

    private boolean hasMessagesDeliQueue(Handler h, Runnable r, Object object) {
        return mStack.hasMessages(sMatchHandlerRunnableAndObject, h, -1, object, r, 0);
    }

    private boolean hasMessagesLegacy(Handler h, Runnable r, Object object) {
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.callback == r && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    boolean hasMessages(Handler h) {
        if (h == null) {
            return false;
        }
        if (sUseDeliQueue) {
            return hasMessagesDeliQueue(h);
        } else {
            return hasMessagesLegacy(h);
        }
    }

    private boolean hasMessagesDeliQueue(Handler h) {
        return mStack.hasMessages(sMatchHandler, h, -1, null, null, 0);
    }

    private boolean hasMessagesLegacy(Handler h) {
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }
        if (sUseDeliQueue) {
            removeMessagesDeliQueue(h, what, object);
        } else {
            removeMessagesLegacy(h, what, object);
        }
    }

    private void removeMessagesDeliQueue(Handler h, int what, Object object) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchHandlerWhatAndObject, h, what,
                object, null, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeMessagesLegacy(Handler h, int what, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                decAndTraceMessageCount();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                            && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        decAndTraceMessageCount();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }
        if (sUseDeliQueue) {
            removeEqualMessagesDeliQueue(h, what, object);
        } else {
            removeEqualMessagesLegacy(h, what, object);
        }
    }

    private void removeEqualMessagesDeliQueue(Handler h, int what, Object object) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchHandlerWhatAndObjectEquals, h,
                what, object, null, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeEqualMessagesLegacy(Handler h, int what, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || object.equals(p.obj))) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                decAndTraceMessageCount();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                            && (object == null || object.equals(n.obj))) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        decAndTraceMessageCount();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }
        if (sUseDeliQueue) {
            removeMessagesDeliQueue(h, r, object);
        } else {
            removeMessagesLegacy(h, r, object);
        }
    }

    private void removeMessagesDeliQueue(Handler h, Runnable r, Object object) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchHandlerRunnableAndObject, h,
                -1, object, r, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeMessagesLegacy(Handler h, Runnable r, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                decAndTraceMessageCount();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                            && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        decAndTraceMessageCount();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeEqualMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }
        if (sUseDeliQueue) {
            removeEqualMessagesDeliQueue(h, r, object);
        } else {
            removeEqualMessagesLegacy(h, r, object);
        }
    }

    private void removeEqualMessagesDeliQueue(Handler h, Runnable r, Object object) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchHandlerRunnableAndObjectEquals,
                h, -1, object, r, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeEqualMessagesLegacy(Handler h, Runnable r, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || object.equals(p.obj))) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                decAndTraceMessageCount();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                            && (object == null || object.equals(n.obj))) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        decAndTraceMessageCount();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeCallbacksAndMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }
        if (sUseDeliQueue) {
            removeCallbacksAndMessagesDeliQueue(h, object);
        } else {
            removeCallbacksAndMessagesLegacy(h, object);
        }
    }

    private void removeCallbacksAndMessagesDeliQueue(Handler h, Object object) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchHandlerAndObject, h, -1, object,
                null, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeCallbacksAndMessagesLegacy(Handler h, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                decAndTraceMessageCount();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        decAndTraceMessageCount();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeCallbacksAndEqualMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }
        if (sUseDeliQueue) {
            removeCallbacksAndEqualMessagesDeliQueue(h, object);
        } else {
            removeCallbacksAndEqualMessagesLegacy(h, object);
        }
    }

    private void removeCallbacksAndEqualMessagesDeliQueue(Handler h, Object object) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchHandlerAndObjectEquals, h, -1,
                object, null, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeCallbacksAndEqualMessagesLegacy(Handler h, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || object.equals(p.obj))) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                decAndTraceMessageCount();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || object.equals(n.obj))) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        decAndTraceMessageCount();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private void removeAllMessagesLocked() {
        Message p = mMessages;
        while (p != null) {
            Message n = p.next;
            p.recycleUnchecked();
            p = n;
        }
        mMessages = null;
        mLast = null;
        mAsyncMessageCount = 0;
        mMessageCount.set(0);
        traceMessageCount();
    }

    private void removeAllFutureMessagesLocked() {
        final long now = SystemClock.uptimeMillis();
        Message p = mMessages;
        if (p != null) {
            if (p.when > now) {
                removeAllMessagesLocked();
            } else {
                Message n;
                for (;;) {
                    n = p.next;
                    if (n == null) {
                        return;
                    }
                    if (n.when > now) {
                        break;
                    }
                    p = n;
                }
                p.next = null;
                mLast = p;

                do {
                    p = n;
                    n = p.next;
                    if (p.isAsynchronous()) {
                        mAsyncMessageCount--;
                    }
                    p.recycleUnchecked();
                    decAndTraceMessageCount();
                } while (n != null);
            }
        }
    }

    private void removeAllMessages() {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchAllMessages, null, -1, null,
                null, 0);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    private void removeAllFutureMessages(long when) {
        final int numRemoved = mStack.moveMatchingToFreelist(sMatchAllFutureMessages, null, -1,
                null, null, when);
        decAndTraceMessageCount(numRemoved);
        maybeDrainFreelist();
    }

    /**
     * Callback interface for discovering when a thread is going to block
     * waiting for more messages.
     */
    public static interface IdleHandler {
        /**
         * Called when the message queue has run out of messages and will now
         * wait for more.  Return true to keep your idle handler active, false
         * to have it removed.  This may be called if there are still messages
         * pending in the queue, but they are all scheduled to be dispatched
         * after the current time.
         */
        boolean queueIdle();
    }

    /**
     * Add a new {@link IdleHandler} to this message queue.  This may be
     * removed automatically for you by returning false from
     * {@link IdleHandler#queueIdle IdleHandler.queueIdle()} when it is
     * invoked, or explicitly removing it with {@link #removeIdleHandler}.
     *
     * <p>This method is safe to call from any thread.
     *
     * @param handler The IdleHandler to be added.
     */
    public void addIdleHandler(@NonNull IdleHandler handler) {
        if (handler == null) {
            throw new NullPointerException("Can't add a null IdleHandler");
        }
        if (sUseDeliQueue) {
            addIdleHandlerDeliQueue(handler);
        } else {
            addIdleHandlerLegacy(handler);
        }
    }

    private void addIdleHandlerDeliQueue(@NonNull IdleHandler handler) {
        synchronized (mIdleHandlersLock) {
            mIdleHandlers.add(handler);
        }
    }

    private void addIdleHandlerLegacy(@NonNull IdleHandler handler) {
        synchronized (this) {
            mIdleHandlers.add(handler);
        }
    }

    /**
     * Remove an {@link IdleHandler} from the queue that was previously added
     * with {@link #addIdleHandler}.  If the given object is not currently
     * in the idle list, nothing is done.
     *
     * <p>This method is safe to call from any thread.
     *
     * @param handler The IdleHandler to be removed.
     */
    public void removeIdleHandler(@NonNull IdleHandler handler) {
        if (sUseDeliQueue) {
            removeIdleHandlerDeliQueue(handler);
        } else {
            removeIdleHandlerLegacy(handler);
        }
    }

    private void removeIdleHandlerDeliQueue(@NonNull IdleHandler handler) {
        synchronized (mIdleHandlersLock) {
            mIdleHandlers.remove(handler);
        }
    }

    private void removeIdleHandlerLegacy(@NonNull IdleHandler handler) {
        synchronized (this) {
            mIdleHandlers.remove(handler);
        }
    }

    /**
     * A listener which is invoked when file descriptor related events occur.
     */
    public interface OnFileDescriptorEventListener {
        /**
         * File descriptor event: Indicates that the file descriptor is ready for input
         * operations, such as reading.
         * <p>
         * The listener should read all available data from the file descriptor
         * then return <code>true</code> to keep the listener active or <code>false</code>
         * to remove the listener.
         * </p><p>
         * In the case of a socket, this event may be generated to indicate
         * that there is at least one incoming connection that the listener
         * should accept.
         * </p><p>
         * This event will only be generated if the {@link #EVENT_INPUT} event mask was
         * specified when the listener was added.
         * </p>
         */
        public static final int EVENT_INPUT = 1 << 0;

        /**
         * File descriptor event: Indicates that the file descriptor is ready for output
         * operations, such as writing.
         * <p>
         * The listener should write as much data as it needs.  If it could not
         * write everything at once, then it should return <code>true</code> to
         * keep the listener active.  Otherwise, it should return <code>false</code>
         * to remove the listener then re-register it later when it needs to write
         * something else.
         * </p><p>
         * This event will only be generated if the {@link #EVENT_OUTPUT} event mask was
         * specified when the listener was added.
         * </p>
         */
        public static final int EVENT_OUTPUT = 1 << 1;

        /**
         * File descriptor event: Indicates that the file descriptor encountered a
         * fatal error.
         * <p>
         * File descriptor errors can occur for various reasons.  One common error
         * is when the remote peer of a socket or pipe closes its end of the connection.
         * </p><p>
         * This event may be generated at any time regardless of whether the
         * {@link #EVENT_ERROR} event mask was specified when the listener was added.
         * </p>
         */
        public static final int EVENT_ERROR = 1 << 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "EVENT_" }, value = {
                EVENT_INPUT,
                EVENT_OUTPUT,
                EVENT_ERROR
        })
        public @interface Events {}

        /**
         * Called when a file descriptor receives events.
         *
         * @param fd The file descriptor.
         * @param events The set of events that occurred: a combination of the
         * {@link #EVENT_INPUT}, {@link #EVENT_OUTPUT}, and {@link #EVENT_ERROR} event masks.
         * @return The new set of events to watch, or 0 to unregister the listener.
         *
         * @see #EVENT_INPUT
         * @see #EVENT_OUTPUT
         * @see #EVENT_ERROR
         */
        @Events int onFileDescriptorEvents(@NonNull FileDescriptor fd, @Events int events);
    }

    static final class FileDescriptorRecord {
        public final FileDescriptor mDescriptor;
        public int mEvents;
        public OnFileDescriptorEventListener mListener;
        public int mSeq;

        public FileDescriptorRecord(FileDescriptor descriptor,
                int events, OnFileDescriptorEventListener listener) {
            mDescriptor = descriptor;
            mEvents = events;
            mListener = listener;
        }
    }

    /**
     * Adds a file descriptor listener to receive notification when file descriptor
     * related events occur.
     * <p>
     * If the file descriptor has already been registered, the specified events
     * and listener will replace any that were previously associated with it.
     * It is not possible to set more than one listener per file descriptor.
     * </p><p>
     * It is important to always unregister the listener when the file descriptor
     * is no longer of use.
     * </p>
     *
     * @param fd The file descriptor for which a listener will be registered.
     * @param events The set of events to receive: a combination of the
     * {@link OnFileDescriptorEventListener#EVENT_INPUT},
     * {@link OnFileDescriptorEventListener#EVENT_OUTPUT}, and
     * {@link OnFileDescriptorEventListener#EVENT_ERROR} event masks.  If the requested
     * set of events is zero, then the listener is unregistered.
     * @param listener The listener to invoke when file descriptor events occur.
     *
     * @see OnFileDescriptorEventListener
     * @see #removeOnFileDescriptorEventListener
     */
    public void addOnFileDescriptorEventListener(@NonNull FileDescriptor fd,
            @OnFileDescriptorEventListener.Events int events,
            @NonNull OnFileDescriptorEventListener listener) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (sUseDeliQueue) {
            addOnFileDescriptorEventListenerDeliQueue(fd, events, listener);
        } else {
            addOnFileDescriptorEventListenerLegacy(fd, events, listener);
        }
    }

    private void addOnFileDescriptorEventListenerDeliQueue(@NonNull FileDescriptor fd,
            @OnFileDescriptorEventListener.Events int events,
            @NonNull OnFileDescriptorEventListener listener) {
        synchronized (mFileDescriptorRecordsLock) {
            updateOnFileDescriptorEventListenerLocked(fd, events, listener);
        }
    }

    private void addOnFileDescriptorEventListenerLegacy(@NonNull FileDescriptor fd,
            @OnFileDescriptorEventListener.Events int events,
            @NonNull OnFileDescriptorEventListener listener) {
        synchronized (this) {
            updateOnFileDescriptorEventListenerLocked(fd, events, listener);
        }
    }

    /**
     * Removes a file descriptor listener.
     * <p>
     * This method does nothing if no listener has been registered for the
     * specified file descriptor.
     * </p>
     *
     * @param fd The file descriptor whose listener will be unregistered.
     *
     * @see OnFileDescriptorEventListener
     * @see #addOnFileDescriptorEventListener
     */
    public void removeOnFileDescriptorEventListener(@NonNull FileDescriptor fd) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }

        if (sUseDeliQueue) {
            removeOnFileDescriptorEventListenerDeliQueue(fd);
        } else {
            removeOnFileDescriptorEventListenerLegacy(fd);
        }
    }

    private void removeOnFileDescriptorEventListenerDeliQueue(@NonNull FileDescriptor fd) {
        synchronized (mFileDescriptorRecordsLock) {
            updateOnFileDescriptorEventListenerLocked(fd, 0, null);
        }
    }

    private void removeOnFileDescriptorEventListenerLegacy(@NonNull FileDescriptor fd) {
        synchronized (this) {
            updateOnFileDescriptorEventListenerLocked(fd, 0, null);
        }
    }


    private void setFileDescriptorEvents(int fdNum, int events) {
        if (sUseDeliQueue) {
            if (incrementMptrRefs()) {
                try {
                    nativeSetFileDescriptorEvents(mPtr, fdNum, events);
                } finally {
                    decrementMptrRefs();
                }
            }
        } else {
            nativeSetFileDescriptorEvents(mPtr, fdNum, events);
        }
    }

    private void updateOnFileDescriptorEventListenerLocked(FileDescriptor fd, int events,
            OnFileDescriptorEventListener listener) {
        final int fdNum = fd.getInt$();

        int index = -1;
        FileDescriptorRecord record = null;
        if (mFileDescriptorRecords != null) {
            index = mFileDescriptorRecords.indexOfKey(fdNum);
            if (index >= 0) {
                record = mFileDescriptorRecords.valueAt(index);
                if (record != null && record.mEvents == events) {
                    return;
                }
            }
        }

        if (events != 0) {
            events |= OnFileDescriptorEventListener.EVENT_ERROR;
            if (record == null) {
                if (mFileDescriptorRecords == null) {
                    mFileDescriptorRecords = new SparseArray<FileDescriptorRecord>();
                }
                record = new FileDescriptorRecord(fd, events, listener);
                mFileDescriptorRecords.put(fdNum, record);
            } else {
                record.mListener = listener;
                record.mEvents = events;
                record.mSeq += 1;
            }
            setFileDescriptorEvents(fdNum, events);
        } else if (record != null) {
            record.mEvents = 0;
            mFileDescriptorRecords.removeAt(index);

            setFileDescriptorEvents(fdNum, 0);
        }
    }

    // Called from native code.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int dispatchEvents(int fd, int events) {
        // Get the file descriptor record and any state that might change.
        final FileDescriptorRecord record;
        final int oldWatchedEvents;
        final OnFileDescriptorEventListener listener;
        final int seq;
        if (sUseDeliQueue) {
            synchronized (mFileDescriptorRecordsLock) {
                record = mFileDescriptorRecords.get(fd);
                if (record == null) {
                    return 0; // spurious, no listener registered
                }

                oldWatchedEvents = record.mEvents;
                events &= oldWatchedEvents; // filter events based on current watched set
                if (events == 0) {
                    return oldWatchedEvents; // spurious, watched events changed
                }

                listener = record.mListener;
                seq = record.mSeq;
            }
        } else {
            synchronized (this) {
                record = mFileDescriptorRecords.get(fd);
                if (record == null) {
                    return 0; // spurious, no listener registered
                }

                oldWatchedEvents = record.mEvents;
                events &= oldWatchedEvents; // filter events based on current watched set
                if (events == 0) {
                    return oldWatchedEvents; // spurious, watched events changed
                }

                listener = record.mListener;
                seq = record.mSeq;
            }
        }
        // Invoke the listener outside of the lock.
        int newWatchedEvents = listener.onFileDescriptorEvents(
                record.mDescriptor, events);
        if (newWatchedEvents != 0) {
            newWatchedEvents |= OnFileDescriptorEventListener.EVENT_ERROR;
        }

        // Update the file descriptor record if the listener changed the set of
        // events to watch and the listener itself hasn't been updated since.
        if (newWatchedEvents != oldWatchedEvents) {
            if (sUseDeliQueue) {
                synchronized (mFileDescriptorRecordsLock) {
                    int index = mFileDescriptorRecords.indexOfKey(fd);
                    if (index >= 0 && mFileDescriptorRecords.valueAt(index) == record
                            && record.mSeq == seq) {
                        record.mEvents = newWatchedEvents;
                        if (newWatchedEvents == 0) {
                            mFileDescriptorRecords.removeAt(index);
                        }
                    }
                }
            } else {
                synchronized (this) {
                    int index = mFileDescriptorRecords.indexOfKey(fd);
                    if (index >= 0 && mFileDescriptorRecords.valueAt(index) == record
                            && record.mSeq == seq) {
                        record.mEvents = newWatchedEvents;
                        if (newWatchedEvents == 0) {
                            mFileDescriptorRecords.removeAt(index);
                        }
                    }
                }
            }
        }

        // Return the new set of events to watch for native code to take care of.
        return newWatchedEvents;
    }

    @NeverCompile
    void dump(Printer pw, String prefix, Handler h) {
        if (sUseDeliQueue) {
            pw.println(prefix + "(MessageQueue is using DeliQueue implementation)");
            final int n = mStack.dump(pw, prefix, h);
            pw.println(prefix + "(Total messages: " + n + ", polling=" + isPollingDeliQueue()
                    + ", quitting=" + mStack.isQuitting() + ")");
        } else {
            synchronized (this) {
                pw.println(prefix + "(MessageQueue is using Legacy implementation)");
                long now = SystemClock.uptimeMillis();
                int n = 0;
                for (Message msg = mMessages; msg != null; msg = msg.next) {
                    if (h == null || h == msg.target) {
                        pw.println(prefix + "Message " + n + ": " + msg.toString(now));
                    }
                    n++;
                }
                pw.println(prefix + "(Total messages: " + n + ", polling=" + isPollingLocked()
                        + ", quitting=" + mQuitting + ")");
            }
        }
    }

    @NeverCompile
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long messageQueueToken = proto.start(fieldId);
        if (sUseDeliQueue) {
            mStack.dumpDebug(proto);
            proto.write(MessageQueueProto.IS_POLLING_LOCKED, isPollingDeliQueue());
            proto.write(MessageQueueProto.IS_QUITTING, mStack.isQuitting());
        } else {
            synchronized (this) {
                for (Message msg = mMessages; msg != null; msg = msg.next) {
                    msg.dumpDebug(proto, MessageQueueProto.MESSAGES);
                }
                proto.write(MessageQueueProto.IS_POLLING_LOCKED, isPollingLocked());
                proto.write(MessageQueueProto.IS_QUITTING, mQuitting);
            }
        }
        proto.end(messageQueueToken);
    }

    private void decAndTraceMessageCount(int n) {
        if (n != 0) {
            mMessageCount.addAndGet(-1 * n);
            if (PerfettoTrace.isMQCategoryEnabled()) {
                traceMessageCount();
            }
        }
    }

    private void decAndTraceMessageCount() {
        decAndTraceMessageCount(1);
    }

    private void incAndTraceMessageCount(Message msg, long when) {
        mMessageCount.incrementAndGet();
        if (PerfettoTrace.isMQCategoryEnabled()) {
            msg.sendingThreadName = Thread.currentThread().getName();
            final long eventId = msg.eventId = PerfettoTrace.getFlowId();

            traceMessageCount();
            final long messageDelayMs = Math.max(0L, when - SystemClock.uptimeMillis());
            if (PerfettoTrace.IS_USE_SDK_TRACING_API_V3) {
                com.android.internal.dev.perfetto.sdk.PerfettoTrace
                        .instant(PerfettoCategories.MQ_CATEGORY, "message_queue_send")
                        .setFlow(eventId)
                        .beginProto()
                        .beginNested(MESSAGE_QUEUE)
                        .addField(RECEIVING_THREAD_NAME, mThreadName)
                        .addField(MESSAGE_CODE, msg.what)
                        .addField(MESSAGE_DELAY_MS, messageDelayMs)
                        .endNested()
                        .endProto()
                        .emit();
            }
        }
    }

    private void traceMessageCount() {
        if (PerfettoTrace.IS_USE_SDK_TRACING_API_V3) {
            com.android.internal.dev.perfetto.sdk.PerfettoTrace
                    .counter(PerfettoCategories.MQ_CATEGORY, mMessageCount.get())
                    .usingThreadCounterTrack(mTid, mThreadName)
                    .emit();
        }
    }
}
