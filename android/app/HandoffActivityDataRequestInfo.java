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

package android.app;

import android.annotation.FlaggedApi;

/**
 * Represents a request made by the platform to hand off an activity. An instance
 * of this class is passed to {@link Activity#onHandoffActivityDataRequested}, and
 * it provides context about how the request was made - for instance, if the request
 * was initiated by a user action. This context is used by the activity to inform
 * the {@link HandoffActivityData} returned by {@link Activity#onHandoffActivityDataRequested}.
 */
@FlaggedApi(android.companion.Flags.FLAG_TASK_CONTINUITY)
public final class HandoffActivityDataRequestInfo {

    private boolean mIsActiveRequest;

    /*
     * Creates a new instance of {@link HandoffActivityDataRequestInfo}.
     *
     * @param isActiveRequest true if the request for {@link HandoffActivityData} was triggered by
     * a user action. Otherwise, this is a request to cache {@link HandoffActivityData} for future
     * use.
     */
    public HandoffActivityDataRequestInfo(boolean isActiveRequest) {
        mIsActiveRequest = isActiveRequest;
    }

    /**
     * @return true if the request for {@link HandoffActivityData} was triggered by a user action.
     * Otherwise, this is a request to cache {@link HandoffActivityData} for future use.
     */
    public boolean isActiveRequest() {
        return mIsActiveRequest;
    }
}