/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License athasEqualMessages
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

import android.os.Message;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dalvik.annotation.optimization.NeverCompile;

import java.util.Arrays;

/**
 * A min-heap of Message objects. Used by MessageQueue.
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class MessageHeap {
    static final int INITIAL_SIZE = 16;
    static final boolean DEBUG = false;
    private static final String TAG = "MessageHeap";

    Message[] mHeap = new Message[INITIAL_SIZE];
    int mNumElements = 0;

    private static int parentNodeIdx(int i) {
        return (i - 1) >>> 1;
    }

    private @Nullable Message getParentNode(int i) {
        return mHeap[parentNodeIdx(i)];
    }

    private static int rightNodeIdx(int i) {
        return 2 * i + 2;
    }

    private @Nullable Message getRightNode(int i) {
        return mHeap[rightNodeIdx(i)];
    }

    private static int leftNodeIdx(int i) {
        return 2 * i + 1;
    }

    private @Nullable Message getLeftNode(int i) {
        return mHeap[leftNodeIdx(i)];
    }

    public int capacity() {
        return mHeap.length;
    }

    public int size() {
        return mNumElements;
    }

    public boolean isEmpty() {
        return mNumElements == 0;
    }

    @Nullable Message getMessageAt(int index) {
        return mHeap[index];
    }

    /*
     * Returns:
     *    0 if x==y.
     *    A value less than 0 if x<y.
     *    A value greater than 0 if x>y.
     */
    private int compareMessagesByIdx(int x, int y) {
        return Message.compareMessages(mHeap[x], mHeap[y]);
    }

    private void siftDown(int i) {
        final Message m = mHeap[i];
        final int numElements = mNumElements;
        int half = numElements >>> 1;
        boolean sifted = false;

        while (i < half) {
            // Find the child to sift down to.
            int childIdx = leftNodeIdx(i);
            Message child = mHeap[childIdx];
            int rightIdx = childIdx + 1;

            if (rightIdx < numElements) {
                final Message right = mHeap[rightIdx];
                if (Message.compareMessages(right, child) < 0) {
                    childIdx = rightIdx;
                    child = right;
                }
            }

            if (Message.compareMessages(m, child) <= 0) {
                // We've found a child that is less than or equal to our message, so we can't
                // traverse down.
                break;
            }

            // Sift the child up and continue traversing down.
            mHeap[i] = child;
            child.heapIndex = i;
            i = childIdx;
            sifted = true;
        }

        // We've arrived at the final position. Store our message, if it moved.
        if (sifted) {
            mHeap[i] = m;
            m.heapIndex = i;
        }
    }

    private boolean siftUp(int i) {
        final Message m = mHeap[i];
        boolean sifted = false;

        // While we're not at the top of the heap, we can try to sift up.
        while (i > 0) {
            int p = parentNodeIdx(i);
            Message parent = mHeap[p];
            if (Message.compareMessages(parent, m) < 0) {
                // We've found a parent that is less than our message, so we can't sift up.
                break;
            }

            // Sift the parent down and continue traversing up.
            mHeap[i] = parent;
            parent.heapIndex = i;
            i = p;
            sifted = true;
        }

        // We've arrived at the final position. Store our message, if it moved.
        if (sifted) {
            mHeap[i] = m;
            m.heapIndex = i;
        }

        return sifted;
    }

    private void maybeGrow() {
        if (mNumElements == mHeap.length) {
            /*
             * Grow by 1.5x. We shrink by a factor of two below to avoid pinging between
             * grow/shrink.
             */
            int newSize = mHeap.length + (mHeap.length >>> 1);
            Message[] newHeap;
            if (DEBUG) {
                Log.d(TAG, "maybeGrow mNumElements " + mNumElements + " mHeap.length "
                        + mHeap.length + " newSize " + newSize);
            }

            newHeap = Arrays.copyOf(mHeap, newSize);
            mHeap = newHeap;
        }
    }

    public void add(@NonNull Message m) {
        maybeGrow();

        int i = mNumElements++;
        m.heapIndex = i;
        mHeap[i] = m;

        // We sift up to ensure that the heap invariant is maintained,
        // but we don't care whether the message was actually moved.
        boolean unused = siftUp(i);
    }

    /**
     * Shrinks the underlying array if the number of elements is significantly smaller than the
     * current capacity.
     *
     * @return {@code true} if the heap was shrunk, {@code false} otherwise.
     */
    public boolean maybeShrink() {
        int nextShrinkSize = mHeap.length;
        final int minElem = Math.max(mNumElements, INITIAL_SIZE);
        int newSize = INITIAL_SIZE;
        while (nextShrinkSize > minElem) {
            newSize = nextShrinkSize;
            nextShrinkSize = nextShrinkSize >>> 1;
        }
        if (DEBUG) {
            Log.d(TAG, "maybeShrink chosen new size " + newSize + " mNumElements "
                    + mNumElements + " mHeap.length " + mHeap.length);
        }

        if (newSize >= INITIAL_SIZE
                && mNumElements <= newSize
                && newSize < mHeap.length) {
            Message[] newHeap;

            newHeap = Arrays.copyOf(mHeap, newSize);
            mHeap = newHeap;

            if (DEBUG) {
                Log.d(TAG, "maybeShrink SHRUNK mNumElements " + mNumElements + " mHeap.length "
                        + mHeap.length + " newSize " + newSize);
            }
            return true;
        }
        return false;
    }

    public @Nullable Message poll() {
        if (mNumElements > 0) {
            Message ret = mHeap[0];
            mNumElements--;

            mHeap[0] = mHeap[mNumElements];
            mHeap[0].heapIndex = 0;
            mHeap[mNumElements] = null;
            ret.heapIndex = -1;

            siftDown(0);

            maybeShrink();
            return ret;
        }
        return null;
    }

    public @Nullable Message peek() {
        return mNumElements > 0 ? mHeap[0] : null;
    }

    public void remove(int i) throws IllegalArgumentException {
        if (i >= mNumElements || mNumElements == 0 || i < 0) {
            throw new IllegalArgumentException("Index " + i + " out of bounds: "
                    + mNumElements);
        }
        mNumElements--;
        mHeap[i].heapIndex = -1;
        if (i == mNumElements) {
            mHeap[i] = null;
        } else {
            mHeap[i] = mHeap[mNumElements];
            mHeap[i].heapIndex = i;
            mHeap[mNumElements] = null;
            if (!siftUp(i)) {
                siftDown(i);
            }
        }
        /* Don't shrink here, let the caller do this once it has removed all matching items. */
    }

    public void removeMessage(@NonNull Message m) throws IllegalArgumentException {
        remove(m.heapIndex);
    }

    public void removeAll() {
        for (int i = 0; i < mNumElements; i++) {
            mHeap[i].heapIndex = -1;
        }
        mHeap = new Message[INITIAL_SIZE];
        mNumElements = 0;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("MessageHeap size: ");
        b.append(mNumElements);
        b.append(" mHeap.length ");
        b.append(mHeap.length);
        for (int i = 0; i < mNumElements; i++) {
            b.append(" [");
            b.append(i);
            b.append("]\t");
            b.append(mHeap[i].when);
            b.append(" seq: ");
            b.append(mHeap[i].insertSeq);
            b.append(" async: ");
            b.append(mHeap[i].isAsynchronous());
        }
        return b.toString();
    }

    @NeverCompile
    private boolean verify(int root) {
        int right = rightNodeIdx(root);
        int left = leftNodeIdx(root);

        if (left >= mNumElements && right >= mNumElements) {
            return true;
        }

        if (left < mNumElements && compareMessagesByIdx(left, root) < 0) {
            Log.e(TAG, "Verify failure: root idx/when: " + root + "/" + mHeap[root].when
                    + " left node idx/when: " + left + "/" + mHeap[left].when);
            return false;
        }

        if (right < mNumElements && compareMessagesByIdx(right, root) < 0) {
            Log.e(TAG, "Verify failure: root idx/when: " + root + "/" + mHeap[root].when
                    + " right node idx/when: " + right + "/" + mHeap[right].when);
            return false;
        }

        if (!verify(right) || !verify(left)) {
            return false;
        }

        int localHeapCount = 0;
        for (int i = 0; i < mHeap.length; i++) {
            if (mHeap[i] != null) {
                if (mHeap[i].heapIndex != i) {
                    Log.e(TAG, "Verify failure: message at " + i + " has heapIndex "
                            + mHeap[i].heapIndex);
                    return false;
                }
                localHeapCount++;
            }
        }

        if (mNumElements != localHeapCount) {
            Log.e(TAG, "Verify failure mNumLElements is " + mNumElements +
                    " but I counted " + localHeapCount + " heap elements");
            return false;
        }
        return true;
    }

    @NeverCompile
    private boolean checkDanglingReferences(String where) {
        /* First, let's make sure we didn't leave any dangling references */
        for (int i = mNumElements; i < mHeap.length; i++) {
            if (mHeap[i] != null) {
                Log.e(TAG, "[" + where
                        + "] Verify failure: dangling reference found at index "
                        + i + ": " + mHeap[i] + " Async " + mHeap[i].isAsynchronous()
                        + " mNumElements " + mNumElements + " mHeap.length " + mHeap.length);
                return false;
            }
        }
        return true;
    }

    @NeverCompile
    public boolean verify() {
        if (!checkDanglingReferences("MessageHeap")) {
            return false;
        }
        return verify(0);
    }
}
