/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;

import java.util.List;

/**
 * Compatibility processor for InputEvents that allows events to be adjusted before and
 * after it is sent to the application.
 *
 * @hide
 */
public abstract class InputEventCompatProcessor {

    protected Context mContext;
    protected int mTargetSdkVersion;

    public InputEventCompatProcessor(Context context) {
        this(context, null);
    }

    public InputEventCompatProcessor(Context context, Handler handler) {
        mContext = context;
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
    }

    /**
     * Process the InputEvent for compatibility before it is sent to the app, allowing for the
     * generation of more than one event if necessary.
     *
     * @param inputEvent The InputEvent to process.
     * @return The list of adjusted events, or null if no adjustments are needed. The list is empty
     * if the event should be ignored. Do not keep a reference to the output as the list is reused.
     */
    @Nullable
    public List<InputEvent> processInputEventForCompatibility(@NonNull InputEvent inputEvent) {
        return null;
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
        return inputEvent;
    }
}
