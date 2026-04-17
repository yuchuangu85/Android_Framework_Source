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
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.InputEvent;
import android.view.InputEventCompatProcessor;
import android.view.MotionEvent;

import java.util.List;

/**
 * This rewrites stylus button events for an application targeting older SDK.
 *
 * @hide
 */
public class StylusButtonCompatibility extends InputEventCompatProcessor {
    private static final int STYLUS_BUTTONS_MASK =
            MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY;

    /**
     * Returns {@code true} if this compatibility is required based on the given context.
     */
    public static boolean isCompatibilityNeeded(Context context) {
        return context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.M;
    }

    public StylusButtonCompatibility(Context context, Handler handler) {
        super(context, handler);
    }

    @Nullable
    @Override
    public List<InputEvent> processInputEventForCompatibility(@NonNull InputEvent inputEvent) {
        if (!(inputEvent instanceof MotionEvent motion)) {
            return null;
        }
        final int buttonState = motion.getButtonState();
        // BUTTON_STYLUS_PRIMARY and BUTTON_STYLUS_SECONDARY are mapped to
        // BUTTON_SECONDARY and BUTTON_TERTIARY respectively.
        final int compatButtonState = (buttonState & STYLUS_BUTTONS_MASK) >> 4;
        if (compatButtonState == 0) {
            // No need to rewrite.
            return null;
        }
        motion.setButtonState(buttonState | compatButtonState);
        return List.of(motion);
    }
}
