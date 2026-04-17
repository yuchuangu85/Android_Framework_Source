/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.attention;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.annotation.UptimeMillisLong;

import com.android.input.flags.Flags;

/**
 * Information about the interaction, used by InteractionListener.
 * @see android.attention.InteractionListener
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_ATTENTION_SERVICE_APIS)
public final class InteractionInfo {

    @AttentionManager.InteractionType
    private final int mInteractionTypes;
    private final @UptimeMillisLong long mInteractionTimeMillis;

    /** @hide */
    public InteractionInfo(@AttentionManager.InteractionType int interactionType,
            @UptimeMillisLong long interactionTimeMillis) {
        this.mInteractionTypes = interactionType;
        this.mInteractionTimeMillis = interactionTimeMillis;
    }

    /**
     * A bit set of the currently active INTERACTION_TYPE(s)
     * @see AttentionManager.InteractionType
     */
    public @AttentionManager.InteractionType int getInteractionTypes() {
        return mInteractionTypes;
    }

    /**
     * Time when most recent interaction occurred in the {@link android.os.SystemClock#uptimeMillis}
     * time base with millisecond precision.
     */
    public @UptimeMillisLong long getInteractionTimeMillis() {
        return mInteractionTimeMillis;
    }

}
