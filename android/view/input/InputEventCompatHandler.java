/*
 * Copyright 2025 The Android Open Source Project
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

package android.view.input;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputEvent;
import android.view.InputEventCompatProcessor;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller of {@link InputEventCompatProcessor}s. One handler instance holds one feature,
 * and handlers can be chained.
 *
 * @hide
 */
public class InputEventCompatHandler {
    private static final String TAG = InputEventCompatHandler.class.getSimpleName();

    private final InputEventCompatProcessor mProcessor;
    private final InputEventCompatHandler mNext;

    public InputEventCompatHandler(InputEventCompatProcessor processor,
            @Nullable InputEventCompatHandler next) {
        mProcessor = processor;
        mNext = next;
    }

    /**
     * Process the InputEvent for compatibility before it is sent to the app, allowing for the
     * generation of more than one event if necessary.
     *
     * @param inputEvent The InputEvent to process.
     * @return The list of adjusted events, or null if no adjustments are needed. The list is empty
     * if the event should be ignored. Do not keep a reference to the output as the list is reused.
     */
    public List<InputEvent> processInputEvent(InputEvent inputEvent) {
        final List<InputEvent> events = mProcessor.processInputEventForCompatibility(inputEvent);
        if (mNext == null) {
            // This is the end of the chain. Returns the result.
            return events;
        } else if (events == null) {
            // The processor doesn't modified event.
            return mNext.processInputEvent(inputEvent);
        } else if (events.isEmpty()) {
            // The processor consumed the event.
            return events;
        } else if (events.size() == 1) {
            // The processor rewrote the event to another event.
            final List<InputEvent> res = mNext.processInputEvent(events.get(0));
            return res == null ? events : res;
        } else {
            // The processor synthesizes multiple events for a given event.
            final List<InputEvent> tmpEvents = new ArrayList<>(events.size());
            for (InputEvent ev : events) {
                final List<InputEvent> res = mNext.processInputEvent(ev);
                if (res != null) {
                    tmpEvents.addAll(res);
                } else {
                    tmpEvents.add(ev);
                }
            }
            return tmpEvents;
        }
    }

    /**
     * Process the InputEvent for compatibility before it is finished by calling
     * InputEventReceiver#finishInputEvent().
     *
     * @param inputEvent The InputEvent to process.
     * @return The InputEvent to finish, or null if it should not be finished.
     */
    @Nullable
    public InputEvent processInputEventBeforeFinish(@NonNull InputEvent inputEvent) {
        if (mNext != null) {
            inputEvent = mNext.processInputEventBeforeFinish(inputEvent);
            if (inputEvent == null) {
                return null;
            }
        }
        return mProcessor.processInputEventBeforeFinish(inputEvent);
    }

    /**
     * Create a list of {@link InputEventCompatProcessor} to be used based on a given context.
     * Returns the head processor of the chain, or null if no compatibility feature is needed.
     */
    public static InputEventCompatHandler buildChain(Context context, Handler handler) {
        // Build features from the tail.
        InputEventCompatHandler chainHead = null;

        final String processorOverrideName = context.getResources().getString(
                R.string.config_inputEventCompatProcessorOverrideClassName);
        if (!processorOverrideName.isEmpty()) {
            try {
                final Class<? extends InputEventCompatProcessor> klass =
                        (Class<? extends InputEventCompatProcessor>) Class.forName(
                                processorOverrideName);
                final InputEventCompatProcessor processor = klass
                        .getConstructor(Context.class, Handler.class)
                        .newInstance(context, handler);
                chainHead = new InputEventCompatHandler(processor, chainHead);
            } catch (Exception e) {
                Log.e(TAG, "Unable to create the InputEventCompatProcessor. ", e);
                chainHead = null;
            }
        }

        if (MouseToTouchProcessor.isCompatibilityNeeded(context)) {
            chainHead = new InputEventCompatHandler(
                    new MouseToTouchProcessor(context, handler), chainHead);
        }

        if (LetterboxScrollProcessor.isCompatibilityNeeded(context)) {
            chainHead = new InputEventCompatHandler(
                    new LetterboxScrollProcessor(context, handler), chainHead);
        }

        if (StylusButtonCompatibility.isCompatibilityNeeded(context)) {
            chainHead = new InputEventCompatHandler(
                    new StylusButtonCompatibility(context, handler), chainHead);
        }

        return chainHead;
    }

    /**
     * Return whether the compatibility of given change ID is required for the given context.
     * If the application declares {@link PackageManager.FEATURE_PC} in the manifest, the
     * compatibility is not required.
     */
    static boolean isPcInputCompatibilityNeeded(Context context, long changeId) {
        if (ActivityThread.isSystem() || !CompatChanges.isChangeEnabled(changeId)) {
            return false;
        }

        // Enabled by the device manufacturer. Check if the app opts out.
        try {
            final PackageInfo pkgInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_CONFIGURATIONS);
            if (pkgInfo.reqFeatures != null) {
                for (FeatureInfo feature : pkgInfo.reqFeatures) {
                    if (TextUtils.equals(feature.name, PackageManager.FEATURE_PC)) {
                        return false;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot obtain package info.", e);
            // pass through.
        }

        Log.i(TAG,
                "Input compatibility " + changeId + " is enabled for " + context.getPackageName());
        return true;
    }
}
