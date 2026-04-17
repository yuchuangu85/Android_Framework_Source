/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.view;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;

/**
 * Similar to {@link InputEventReceiver}, but batches events to vsync boundaries when possible.
 * @hide
 */
public class BatchedInputEventReceiver extends InputEventReceiver {
    private final Choreographer mChoreographer;
    private boolean mBatchingEnabled;
    private boolean mBatchedInputScheduled;
    private final String mTag;
    private final Handler mHandler;
    private final Runnable mConsumeBatchedInputEvents = new Runnable() {
        @Override
        public void run() {
            consumeBatchedInputEvents(-1);
        }
    };

    @UnsupportedAppUsage
    public BatchedInputEventReceiver(
            InputChannel inputChannel, Looper looper, Choreographer choreographer) {
        super(inputChannel, looper);
        mChoreographer = choreographer;
        mBatchingEnabled = true;
        mTag = getClass().getName();
        traceBoolVariable("mBatchingEnabled", mBatchingEnabled);
        traceBoolVariable("mBatchedInputScheduled", mBatchedInputScheduled);
        mHandler = new Handler(looper);
    }

    @Override
    public void onBatchedInputEventPending(int source) {
        if (mBatchingEnabled) {
            scheduleBatchedInput();
        } else {
            consumeBatchedInputEvents(-1);
        }
    }

    @Override
    public void dispose() {
        unscheduleBatchedInput();
        consumeBatchedInputEvents(-1);
        super.dispose();
    }

    /**
     * Sets whether to enable batching on this input event receiver.
     * @hide
     */
    public void setBatchingEnabled(boolean batchingEnabled) {
        if (mBatchingEnabled == batchingEnabled) {
            return;
        }

        mBatchingEnabled = batchingEnabled;
        traceBoolVariable("mBatchingEnabled", mBatchingEnabled);
        if (mHandler.hasCallbacks(mConsumeBatchedInputEvents)) {
            mHandler.removeCallbacks(mConsumeBatchedInputEvents);
            // Existence of `mConsumeBatchedInputEvents` implies that there are pending batched
            // input events from the last time batching was enabled that need to be consumed -
            // consume them so the receiver keeps getting `onBatchedInputEventPending()`
            // notifications.
            if (batchingEnabled) {
                scheduleBatchedInput();
            }
        }
        if (!batchingEnabled) {
            unscheduleBatchedInput();
            mHandler.post(mConsumeBatchedInputEvents);
        }
    }

    protected void doConsumeBatchedInput(long frameTimeNanos) {
        if (mBatchedInputScheduled) {
            mBatchedInputScheduled = false;
            traceBoolVariable("mBatchedInputScheduled", mBatchedInputScheduled);
            if (consumeBatchedInputEvents(frameTimeNanos) && frameTimeNanos != -1) {
                // If we consumed a batch here, we want to go ahead and schedule the
                // consumption of batched input events on the next frame. Otherwise, we would
                // wait until we have more input events pending and might get starved by other
                // things occurring in the process. If the frame time is -1, however, then
                // we're in a non-batching mode, so there's no need to schedule this.
                scheduleBatchedInput();
            }
        }
    }

    private void scheduleBatchedInput() {
        if (!mBatchedInputScheduled) {
            mBatchedInputScheduled = true;
            traceBoolVariable("mBatchedInputScheduled", mBatchedInputScheduled);
            mChoreographer.postVsyncCallback(Choreographer.CALLBACK_INPUT, mBatchedInputCallback);
        }
    }

    private void unscheduleBatchedInput() {
        if (mBatchedInputScheduled) {
            mBatchedInputScheduled = false;
            traceBoolVariable("mBatchedInputScheduled", mBatchedInputScheduled);
            mChoreographer.removeVsyncCallback(
                    Choreographer.CALLBACK_INPUT, mBatchedInputCallback);
        }
    }

    // @TODO(b/311142655): Delete this temporary tracing. It's only used here to debug a very
    // specific issue.
    private void traceBoolVariable(String name, boolean value) {
        Trace.traceCounter(Trace.TRACE_TAG_INPUT, name, value ? 1 : 0);
    }

    private final class BatchedInputCallback implements Choreographer.VsyncCallback {
        @Override
        public void onVsync(Choreographer.FrameData frameData) {
            try {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, mTag);
                doConsumeBatchedInput(frameData.getFrameTimeNanos());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_INPUT);
            }
        }
    }
    private final BatchedInputCallback mBatchedInputCallback = new BatchedInputCallback();

    /**
     * A {@link BatchedInputEventReceiver} that reports events to an {@link InputEventListener}.
     * @hide
     */
    public static class SimpleBatchedInputEventReceiver extends BatchedInputEventReceiver {

        /** @hide */
        public interface InputEventListener {
            /**
             * Process the input event.
             * @return handled
             */
            boolean onInputEvent(InputEvent event);
        }

        protected InputEventListener mListener;

        public SimpleBatchedInputEventReceiver(InputChannel inputChannel, Looper looper,
                Choreographer choreographer, InputEventListener listener) {
            super(inputChannel, looper, choreographer);
            mListener = listener;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                handled = mListener.onInputEvent(event);
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }
}
