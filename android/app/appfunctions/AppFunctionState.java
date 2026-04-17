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

package android.app.appfunctions;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.appfunctions.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import android.util.ArraySet;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Runtime state of an app function, retrieved using {@link
 * AppFunctionManager#getAppFunctionStates}.
 *
 * <p>This holds properties of an app function that can change at runtime during the app's
 * operation, such as whether the function is enabled.
 *
 * <p>This is distinct from {@link AppFunctionMetadata}, which represents the metadata that remains
 * constant until the providing package is updated. While {@link AppFunctionMetadata} defines
 * <em>what</em> a function is, the {@link AppFunctionState} defines its current operational status.
 */
@FlaggedApi(Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionState implements Parcelable {

    @NonNull
    public static final Creator<AppFunctionState> CREATOR =
            new Creator<AppFunctionState>() {
                @Override
                public AppFunctionState createFromParcel(Parcel in) {
                    return new AppFunctionState(in);
                }

                @Override
                public AppFunctionState[] newArray(int size) {
                    return new AppFunctionState[size];
                }
            };

    @NonNull private final AppFunctionName mFunctionName;
    private final boolean mIsEnabled;
    @Nullable private final ArraySet<AppFunctionActivityId> mActivityIds;

    /**
     * Constructs an {@link AppFunctionState} object.
     *
     * @param functionName The identifier for the app function.
     * @param isEnabled Whether this app function can be executed.
     * @param activityIds The {@link AppFunctionActivityId}s this app function is associated with.
     * @hide
     */
    public AppFunctionState(
            @NonNull AppFunctionName functionName,
            boolean isEnabled,
            @Nullable ArraySet<AppFunctionActivityId> activityIds) {
        mFunctionName = Objects.requireNonNull(functionName);
        mIsEnabled = isEnabled;
        mActivityIds = activityIds;
    }

    private AppFunctionState(Parcel in) {
        mFunctionName = Objects.requireNonNull(in.readTypedObject(AppFunctionName.CREATOR));
        mIsEnabled = in.readBoolean();
        mActivityIds =
                (ArraySet<AppFunctionActivityId>)
                        in.readArraySet(AppFunctionActivityId.class.getClassLoader());
    }

    /** Returns the {@link AppFunctionName} associated with this state. */
    @NonNull
    public AppFunctionName getFunctionName() {
        return mFunctionName;
    }

    /**
     * Returns whether this app function can be executed.
     *
     * <p>This can be false if:
     *
     * <ul>
     *   <li>The app disabled the function with {@link AppFunctionManager#setAppFunctionEnabled}.
     *   <li>The function is disabled by default using {@link
     *       AppFunctionMetadata#PROPERTY_ENABLED_BY_DEFAULT} and was never enabled.
     *   <li>The associated {@link AppFunctionService} is disabled.
     *   <li>A function without an associated {@link AppFunctionService} has not been registered
     *       using {@link AppFunctionManager#registerAppFunction}, or has been unregistered.
     *   <li>The process registering the function using {@link
     *       AppFunctionManager#registerAppFunction} is frozen, or the {@link
     *       android.content.Context} used to register it has been destroyed.
     * </ul>
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Returns {@link AppFunctionActivityId}s this app function is associated with, or null if none.
     *
     * <p>This will only be non-null when {@link AppFunctionMetadata#getScope} is {@link
     * AppFunctionMetadata#SCOPE_ACTIVITY}. See {@link AppFunctionMetadata#SCOPE_ACTIVITY} for more
     * details.
     */
    // Performance optimization to avoid creating empty collections for an unbound list of
    // AppFunctionStates (using null instead), and to allow indexed for-loop (using ArraySet).
    @SuppressLint({"NullableCollection", "ConcreteCollection"})
    @Nullable
    public ArraySet<AppFunctionActivityId> getActivityIds() {
        return mActivityIds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppFunctionState that)) return false;
        return mIsEnabled == that.mIsEnabled
                && mFunctionName.equals(that.mFunctionName)
                && Objects.equals(mActivityIds, that.mActivityIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFunctionName, mIsEnabled, mActivityIds);
    }

    @Override
    public String toString() {
        return "AppFunctionState("
                + "functionName="
                + mFunctionName
                + ", "
                + "isEnabled="
                + mIsEnabled
                + ", "
                + "activityIds="
                + mActivityIds
                + ")";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mFunctionName, flags);
        dest.writeBoolean(mIsEnabled);
        dest.writeArraySet(mActivityIds);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
