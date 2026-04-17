/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.appfunctions;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.appfunctions.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.util.Objects;

/**
 * The state of an activity from the perspective of app functions, retrieved using {@link
 * AppFunctionManager#getAppFunctionActivityStates}.
 *
 * <p>This holds which app functions are registered for a given activity, a property that can change
 * at runtime during the app's operation.
 *
 * @see AppFunctionState
 */
@FlaggedApi(Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionActivityState implements Parcelable {

    @NonNull
    public static final Creator<AppFunctionActivityState> CREATOR =
            new Creator<AppFunctionActivityState>() {
                @Override
                public AppFunctionActivityState createFromParcel(Parcel in) {
                    return new AppFunctionActivityState(in);
                }

                @Override
                public AppFunctionActivityState[] newArray(int size) {
                    return new AppFunctionActivityState[size];
                }
            };

    @NonNull private final AppFunctionActivityId mActivityId;
    @NonNull private final ArraySet<AppFunctionName> mFunctionNames;

    /**
     * Constructs an {@link AppFunctionActivityState} object.
     *
     * @param activityId The identifier for the activity.
     * @param functionNames The set of function names registered for this activity.
     * @hide
     */
    public AppFunctionActivityState(
            @NonNull AppFunctionActivityId activityId,
            @NonNull ArraySet<AppFunctionName> functionNames) {
        mActivityId = Objects.requireNonNull(activityId);
        mFunctionNames = Objects.requireNonNull(functionNames);
    }

    private AppFunctionActivityState(Parcel in) {
        mActivityId = Objects.requireNonNull(in.readTypedObject(AppFunctionActivityId.CREATOR));
        mFunctionNames =
                (ArraySet<AppFunctionName>) in.readArraySet(AppFunctionName.class.getClassLoader());
    }

    /** Returns the {@link AppFunctionActivityId} associated with this state. */
    @NonNull
    public AppFunctionActivityId getActivityId() {
        return mActivityId;
    }

    /**
     * Returns the set of {@link AppFunctionName}s that are registered for the {@link
     * android.app.Activity} referenced by {@link #getActivityId}.
     */
    @SuppressLint({"ConcreteCollection"})
    @NonNull
    public ArraySet<AppFunctionName> getFunctionNames() {
        return mFunctionNames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppFunctionActivityState that)) return false;
        return mActivityId.equals(that.mActivityId) && mFunctionNames.equals(that.mFunctionNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mActivityId, mFunctionNames);
    }

    @Override
    public String toString() {
        return "AppFunctionActivityState("
                + "activityId="
                + mActivityId
                + ", "
                + "functionNames="
                + mFunctionNames
                + ")";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mActivityId, flags);
        dest.writeArraySet(mFunctionNames);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
