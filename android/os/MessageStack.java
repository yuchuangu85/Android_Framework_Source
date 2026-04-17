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

import android.annotation.Nullable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Printer;
import android.util.proto.ProtoOutputStream;

import dalvik.annotation.optimization.NeverCompile;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Treiber stack of Message objects, used in NewNewMessageQueue.
 * @hide
 */
@RavenwoodKeepWholeClass
public final class MessageStack {
    private static final String TAG = "MessageStack";

    private static final VarHandle sTop;
    private volatile Message mTopValue = null;

    private static final VarHandle sFreelistHead;
    private volatile Message mFreelistHeadValue = null;

    // The underlying min-heaps that are used for ordering Messages.
    private final MessageHeap mSyncHeap = new MessageHeap();
    private final MessageHeap mAsyncHeap = new MessageHeap();

    // This points to the most-recently processed message. Comparison with mTopValue will indicate
    // whether some messages still need to be processed. This value excludes the quitting sentinel.
    private Message mLooperProcessed = null;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            sTop = l.findVarHandle(MessageStack.class, "mTopValue",
                    Message.class);
            sFreelistHead = l.findVarHandle(MessageStack.class, "mFreelistHeadValue",
                    Message.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Object QUITTING_NODE_OBJ = new Object();

    private boolean isQuittingMessage(Message m) {
        return m != null && m.obj == QUITTING_NODE_OBJ;
    }

    /**
     * Pushes a message onto the top of the stack with a CAS.
     * @return true if successfully pushed; false if the stack is quitting.
     */
    public boolean pushMessage(Message m) {
        Message current;
        do {
            current = mTopValue;
            if (isQuittingMessage(current)) {
                return false;
            }
            m.next = current;
        } while (!sTop.weakCompareAndSetRelease(this, current, m));
        return true;
    }

    /**
     * Pushes a quitting message onto the top of the stack.
     * After this call no more messages can be pushed onto the stack.
     * @return true if pushed, false if there was already a quitting message
     */
    public boolean pushQuitting(long when) {
        Message quittingMsg = Message.obtain();
        quittingMsg.obj = QUITTING_NODE_OBJ;
        quittingMsg.when = when;
        final boolean ret = pushMessage(quittingMsg);
        if (!ret) {
            quittingMsg.recycleUnchecked();
        }

        return ret;
    }

    /**
     * Query if we are in a quitting state.
     * @return true if we have a quitting message on top of the stack.
     */
    public boolean isQuitting() {
        return isQuittingMessage((Message) sTop.getAcquire(this));
    }

    /**
     * Gets timestamp of quitting message.
     * @return timestamp, or throws an exception if no quitting message exists.
     */
    public long getQuittingTimestamp() throws IllegalStateException {
        Message m = (Message) sTop.getAcquire(this);
        if (!isQuittingMessage(m)) {
            throw new IllegalStateException();
        }
        return m.when;
    }

    /**
     * Check that the message hasn't already been removed or processed elsewhere.
     */
    private boolean messageMatches(Message m, Message.MessageCompare compare, Handler h,
            int what, Object object, Runnable r, long when) {
        return !isQuittingMessage(m)
                && !m.isRemoved()
                && compare.compareMessage(m, h, what, object, r, when);
    }

    /**
     * Iterates through messages and creates a reverse-ordered chain of messages to remove.
     * @return true if any messages were removed, false otherwise
     */
    public int moveMatchingToFreelist(Message.MessageCompare compare, Handler h, int what,
            Object object, Runnable r, long when) {
        Message current = (Message) sTop.getAcquire(this);
        Message prev = null;
        Message firstRemoved = null;
        int numRemoved = 0;

        while (current != null) {
            if (messageMatches(current, compare, h, what, object, r, when)
                    && current.markRemoved()) {
                if (firstRemoved == null) {
                    firstRemoved = current;
                }
                current.clearReferenceFields();
                // nextFree links each to-be-removed message to the one processed before.
                current.nextFree = prev;
                prev = current;
                numRemoved++;
            }
            current = current.next;
        }

        if (firstRemoved != null) {
            Message freelist;
            do {
                freelist = mFreelistHeadValue;
                firstRemoved.nextFree = freelist;
            // prev points to the last to-be-removed message that was processed.
            } while (!sFreelistHead.compareAndSet(this, freelist, prev));
        }

        return numRemoved;
    }

    /**
     * Moves the sync barrier with the matching token to the freelist.
     * This is a special case of {@link #moveMatchingToFreelist()} that only removes a single sync
     * barrier with a matching token.
     */
    public boolean moveSyncBarrierToFreelist(int token) {
        Message current = (Message) sTop.getAcquire(this);
        while (current != null) {
            if (current.target == null && current.arg1 == token && current.markRemoved()) {
                break;
            }
            current = current.next;
        }

        // We didn't find a sync barrier with a matching token.
        if (current == null) {
            return false;
        }

        Message freelist;
        do {
            freelist = mFreelistHeadValue;
            current.nextFree = freelist;
        } while (!sFreelistHead.compareAndSet(this, freelist, current));
        return true;
    }

    /**
     * Search our stack for a given set of messages.
     * @return true if matching messages are found, false otherwise.
     */
    public boolean hasMessages(Message.MessageCompare compare, Handler h, int what,
            Object object, Runnable r, long when) {
        Message current = (Message) sTop.getAcquire(this);

        while (current != null) {
            // Check that the message hasn't already been removed or processed elsewhere.
            if (messageMatches(current, compare, h, what, object, r, when)) {
                return true;
            }
            current = current.next;
        }
        return false;
    }

    private MessageHeap getHeap(boolean async) {
        return async ? mAsyncHeap : mSyncHeap;
    }

    private MessageHeap getHeap(Message m) {
        return getHeap(m.isAsynchronous());
    }

    /**
     * Adds not-yet-processed messages into the MessageHeap and creates backlinks.
     */
    public void heapSweep() {
        Message current = (Message) sTop.getAcquire(this);

        if (current != null && isQuittingMessage(current)) {
            if (current.next != null) {
                current.next.prev = current;
            }
            current = current.next;
        }

        Message prevLooperProcessed = mLooperProcessed;
        mLooperProcessed = current;

        while (current != null && current != prevLooperProcessed) {
            if (current.next != null) {
                current.next.prev = current;
            }
            // MessageHeap will maintain its own ordering of Messages, so it doesn't matter that we
            // insert these Messages in a different order than submitted to the stack.
            if (!current.isRemoved()) {
                getHeap(current).add(current);
            }
            current = current.next;
        }

        // TODO: Investigate inserting in-submitted-order with a second traversal using backlinks.
    }

    /**
     * Iterate through the freelist and unlink Messages.
     */
    public void drainFreelist() {
        boolean shrinkSyncHeap = false;
        boolean shrinkAsyncHeap = false;
        Message current = (Message) sFreelistHead.getAndSetAcquire(this, null);
        while (current != null) {
            Message nextFree = current.nextFree;
            current.nextFree = null;
            if (maybeRemoveFromHeap(current)) {
                if (current.isAsynchronous()) {
                    shrinkAsyncHeap = true;
                } else {
                    shrinkSyncHeap = true;
                }
            }
            removeFromStack(current);
            current = nextFree;
        }

        if (shrinkSyncHeap) {
            mSyncHeap.maybeShrink();
        }
        if (shrinkAsyncHeap) {
            mAsyncHeap.maybeShrink();
        }
    }

    /**
     * Get a message from the MessageHeap, remove its links within this stack, then return it.
     *
     * This will return null if there are no more items in the heap, or if there was a race and the
     * polled message was removed.
     */
    public Message pop(boolean async) {
        final Message m = getHeap(async).poll();
        if (m != null) {
            // We CAS this so that a remover doesn't attempt to add it to the freelist. If this CAS
            // fails, it has already been removed, and links will be cleared in a drainFreelist()
            // pass.
            if (!m.markRemoved()) {
                return null;
            }
            removeFromStack(m);
        }
        return m;
    }

    private boolean maybeRemoveFromHeap(Message m) {
        // An out of range heapIndex means that we've already removed this message from the heap, or
        // it was never added to the heap in the first place.
        if (m.heapIndex >= 0) {
            getHeap(m).removeMessage(m);
            return true;
        }
        return false;
    }

    /**
     * Remove a message from the stack.
     */
    private void removeFromStack(Message m) {
        // mLooperProcessed must be updated to the next message.
        if (m == mLooperProcessed) {
            mLooperProcessed = m.next;
        }

        if (m.prev == null) {
            // If prev is null, m was the top or had not yet been added at the time the previous
            // heapSweep was called.
            // Check whether m is the top and try to pop it. If so, m has no predecessor node and we
            // can just unlink from its successor. Since only the looper thread can pop or drain
            // the freelist, if this CAS fails, it can only be due to pushes or quits, either of
            // which would mean that the current node is no longer the top of the stack.
            if (!sTop.compareAndSet(this, m, m.next)) {
                // New messages were pushed to the stack between the previous backlink creation pass
                // (heapSweep or removeFromStack) and now. We must find m's predecessor to unlink m.
                // To ensure amortized O(1) runtime, create backlinks for all nodes between the
                // current top and the last-created backlink. After that we can remove m the normal
                // way.
                Message current = (Message) sTop.getAcquire(this);
                // Note that current can't start as null, since at least the element we're removing
                // must be in the stack.
                while (current.next != null && current.next.prev == null) {
                    current.next.prev = current;
                    current = current.next;
                }
            }
        }

        // Unlink from next and previous
        Message next = m.next;
        Message prev = m.prev;
        m.prev = null;
        if (next != null) {
            next.prev = prev;
        }


        if (prev != null) {
            prev.next = next;
        }
    }

    /**
     * Return the next non-removed Message.
     *
     * A null return value indicates that the underlying heap was either empty or only contained
     * removed messages.
     */
    public @Nullable Message peek(boolean async) {
        MessageHeap heap = getHeap(async);
        while (true) {
            final Message m = heap.peek();
            if (m == null) {
                return null;
            }
            if (!m.isRemoved()) {
                return m;
            }
            heap.removeMessage(m);
        }
    }

    /**
     * Remove the input Message.
     *
     * This is suitable to use with the output of peek().
     */
    public void remove(Message m) {
        if (maybeRemoveFromHeap(m)) {
            MessageHeap heap = m.isAsynchronous() ? mAsyncHeap : mSyncHeap;
            heap.maybeShrink();
        }
        removeFromStack(m);
    }

    Message peekLastMessageForTest() {
        Message lastMsg = null;
        Message current = (Message) sTop.getAcquire(this);
        while (current != null) {
            if (!current.isRemoved() && (lastMsg == null || current.when > lastMsg.when)) {
                lastMsg = current;
            }
            current = current.next;
        }
        return lastMsg;
    }

    /**
     * Returns the number of non-removed messages in this stack.
     */
    public int sizeForTest() {
        int size = 0;
        Message current = (Message) sTop.getAcquire(this);
        while (current != null) {
            if (!current.isRemoved()) {
                size++;
            }
            current = current.next;
        }
        return size;
    }

    /**
     * Returns the number of messages in the freelist.
     */
    public int freelistSizeForTest() {
        int size = 0;
        Message current = (Message) sFreelistHead.getAcquire(this);
        while (current != null) {
            size++;
            current = current.nextFree;
        }
        return size;
    }

    /**
     * Returns the number of messages in the underlying MessageHeaps.
     */
    public int combinedHeapSizesForTest() {
        return mSyncHeap.size() + mAsyncHeap.size();
    }

    /**
     * Returns the total capacity in the underlying MessageHeaps.
     */
    public int combinedHeapCapacitiesForTest() {
        return mSyncHeap.capacity() + mAsyncHeap.capacity();
    }

    @NeverCompile
    int dump(Printer pw, String prefix, Handler h) {
        final long now = SystemClock.uptimeMillis();
        int n = 0;
        Message msg = (Message) sTop.getAcquire(this);
        while (msg != null) {
            if (h == null || h == msg.target) {
                pw.println(prefix + "Message " + n + ": " + msg.toString(now));
            }
            msg = msg.next;
            n++;
        }
        return n;
    }

    @NeverCompile
    void dumpDebug(ProtoOutputStream proto) {
        Message msg = (Message) sTop.getAcquire(this);
        while (msg != null) {
            msg.dumpDebug(proto, MessageQueueProto.MESSAGES);
            msg = msg.next;
        }
    }
}
