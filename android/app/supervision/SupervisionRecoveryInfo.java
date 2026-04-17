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

package android.app.supervision;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.supervision.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import androidx.annotation.Keep;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Contains the information needed for recovering the device-wide supervision credentials.
 *
 * <p>This is typically returned as an {@link android.content.Intent} extra from the supervision
 * credentials recovery activity. This activity is hosted by the holder of the {@code
 * android.app.role.RoleManager#ROLE_SYSTEM_SUPERVISION} role and is generally launched to set up a
 * recovery method or to reset the supervision credentials.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SUPERVISION_MANAGER_APIS)
public final class SupervisionRecoveryInfo implements Parcelable {
    /**
     * Extra key used to pass supervision recovery information within an intent.
     *
     * <p>The associated value should be a {@link android.app.supervision.SupervisionRecoveryInfo}
     * object.
     *
     * <p>This extra is intended to be used by the supervision PIN recovery activity hosted by the
     * {@code android.app.role.RoleManager#ROLE_SYSTEM_SUPERVISION} role holder.
     */
    public static final String EXTRA_SUPERVISION_RECOVERY_INFO =
            "android.app.supervision.extra.SUPERVISION_RECOVERY_INFO";

    @NonNull
    public static final Creator<SupervisionRecoveryInfo> CREATOR =
            new Creator<SupervisionRecoveryInfo>() {
                @Override
                public SupervisionRecoveryInfo createFromParcel(@NonNull Parcel source) {
                    String accountName = source.readString();
                    String accountType = source.readString();
                    PersistableBundle accountData =
                            source.readPersistableBundle(getClass().getClassLoader());
                    int state = source.readInt();
                    if (accountName != null && accountType != null) {
                        return new SupervisionRecoveryInfo(
                                accountName, accountType, state, accountData);
                    }
                    return null;
                }

                @Override
                public SupervisionRecoveryInfo[] newArray(int size) {
                    return new SupervisionRecoveryInfo[size];
                }
            };

    /**
     * An IntDef which describes the various states of the recovery information.
     *
     * @hide
     */
    @Keep
    @IntDef({STATE_PENDING, STATE_VERIFIED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /** Indicates that the recovery information is pending verification. */
    public static final int STATE_PENDING = 0;

    /** Indicates that the recovery information has been verified. */
    public static final int STATE_VERIFIED = 1;

    @NonNull private String mAccountName;
    @NonNull private String mAccountType;
    @Nullable private PersistableBundle mAccountData;
    @State private int mState;

    /**
     * Constructor for SupervisionRecoveryInfo.
     *
     * @param accountName The name of the account. See {@link android.accounts.Account#name}.
     * @param accountType The type of the account. See {@link android.accounts.Account#type}.
     * @param state The state of the recovery information.
     * @param accountData Authenticator-specific data for recovery. This contains additional
     *     information that the recovery method needs to recover the device supervision PIN.
     */
    public SupervisionRecoveryInfo(
            @NonNull String accountName,
            @NonNull String accountType,
            @State int state,
            @Nullable PersistableBundle accountData) {
        this.mAccountName = accountName;
        this.mAccountType = accountType;
        this.mAccountData = accountData;
        this.mState = state;
    }

    /** Gets the recovery account name. */
    @NonNull
    public String getAccountName() {
        return mAccountName;
    }

    /** Gets the recovery account type. */
    @NonNull
    public String getAccountType() {
        return mAccountType;
    }

    /** Gets the recovery account data. */
    @NonNull
    public PersistableBundle getAccountData() {
        return mAccountData == null ? new PersistableBundle() : mAccountData;
    }

    /**
     * Gets the state of the recovery information.
     *
     * @return One of {@link #STATE_PENDING}, {@link #STATE_VERIFIED}.
     */
    @State
    public int getState() {
        return mState;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flag) {
        parcel.writeString(mAccountName);
        parcel.writeString(mAccountType);
        parcel.writePersistableBundle(mAccountData);
        parcel.writeInt(mState);
    }

    @Override
    public String toString() {
        java.util.StringJoiner joiner = new java.util.StringJoiner(", ", "{", "}");
        joiner.add("accountName: " + mAccountName);
        joiner.add("accountType: " + mAccountType);
        joiner.add("accountData: " + mAccountData);
        joiner.add("state: " + mState);
        return "SupervisionRecoveryInfo" + joiner;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SupervisionRecoveryInfo)) return false;
        SupervisionRecoveryInfo that = (SupervisionRecoveryInfo) other;
        return Objects.equals(mAccountName, that.mAccountName)
                && Objects.equals(mAccountType, that.mAccountType)
                && Objects.equals(mAccountData, that.mAccountData)
                && mState == that.mState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAccountName, mAccountType, mAccountData, mState);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
