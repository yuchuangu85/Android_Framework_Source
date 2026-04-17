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

package android.media.tv.watchdogmanager;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.media.tv.flags.Flags;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * Killable state for a package.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION)
public final class PackageKillableState implements Parcelable {
    /** A package is killable. */
    @KillableState public static final int KILLABLE_STATE_YES = 1;

    /** A package is not killable. */
    @KillableState public static final int KILLABLE_STATE_NO = 2;

    /** A package is never killable i.e. its setting cannot be updated. */
    @KillableState public static final int KILLABLE_STATE_NEVER = 3;

    /** Name of the package. */
    private @NonNull String mPackageName;

    /** Id of the user. */
    private @UserIdInt int mUserId;

    /** Killable state of the user's package. */
    private @KillableState int mKillableState;

    /** @hide */
    @android.annotation.IntDef(
            prefix = "KILLABLE_STATE_",
            value = {KILLABLE_STATE_YES, KILLABLE_STATE_NO, KILLABLE_STATE_NEVER})
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface KillableState {}

    /** @hide */
    public static String killableStateToString(@KillableState int value) {
        switch (value) {
            case KILLABLE_STATE_YES:
                return "KILLABLE_STATE_YES";
            case KILLABLE_STATE_NO:
                return "KILLABLE_STATE_NO";
            case KILLABLE_STATE_NEVER:
                return "KILLABLE_STATE_NEVER";
            default:
                return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new PackageKillableState.
     *
     * @param packageName Name of the package.
     * @param userId Id of the user.
     * @param killableState Killable state of the user's package.
     * @hide
     */
    public PackageKillableState(
            @NonNull String packageName, @UserIdInt int userId, @KillableState int killableState) {
        this.mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
        this.mUserId = userId;
        AnnotationValidations.validate(UserIdInt.class, null, mUserId);
        this.mKillableState = killableState;

        if (!(mKillableState == KILLABLE_STATE_YES)
                && !(mKillableState == KILLABLE_STATE_NO)
                && !(mKillableState == KILLABLE_STATE_NEVER)) {
            throw new java.lang.IllegalArgumentException(
                    "killableState was "
                            + mKillableState
                            + " but must be one of: "
                            + "KILLABLE_STATE_YES("
                            + KILLABLE_STATE_YES
                            + "), "
                            + "KILLABLE_STATE_NO("
                            + KILLABLE_STATE_NO
                            + "), "
                            + "KILLABLE_STATE_NEVER("
                            + KILLABLE_STATE_NEVER
                            + ")");
        }
    }

    /** Name of the package. */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /** Id of the user. */
    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /** Killable state of the user's package. */
    public @KillableState int getKillableState() {
        return mKillableState;
    }

    @Override
    public String toString() {
        return "PackageKillableState { "
                + "packageName = "
                + mPackageName
                + ", "
                + "userId = "
                + mUserId
                + ", "
                + "killableState = "
                + killableStateToString(mKillableState)
                + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeInt(mUserId);
        dest.writeInt(mKillableState);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ PackageKillableState(@NonNull android.os.Parcel in) {
        String packageName = in.readString();
        int userId = in.readInt();
        int killableState = in.readInt();

        this.mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
        this.mUserId = userId;
        AnnotationValidations.validate(UserIdInt.class, null, mUserId);
        this.mKillableState = killableState;

        if (!(mKillableState == KILLABLE_STATE_YES)
                && !(mKillableState == KILLABLE_STATE_NO)
                && !(mKillableState == KILLABLE_STATE_NEVER)) {
            throw new java.lang.IllegalArgumentException(
                    "killableState was "
                            + mKillableState
                            + " but must be one of: "
                            + "KILLABLE_STATE_YES("
                            + KILLABLE_STATE_YES
                            + "), "
                            + "KILLABLE_STATE_NO("
                            + KILLABLE_STATE_NO
                            + "), "
                            + "KILLABLE_STATE_NEVER("
                            + KILLABLE_STATE_NEVER
                            + ")");
        }
    }

    public static final @NonNull Parcelable.Creator<PackageKillableState> CREATOR =
            new Parcelable.Creator<PackageKillableState>() {
                @Override
                public PackageKillableState[] newArray(int size) {
                    return new PackageKillableState[size];
                }

                @Override
                public PackageKillableState createFromParcel(@NonNull android.os.Parcel in) {
                    return new PackageKillableState(in);
                }
            };
}
