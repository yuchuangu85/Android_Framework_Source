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

package android.health.connect.backuprestore;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.healthfitness.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A request to change the backup and restore User settings in Health Connect.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
public final class UpdateBackupAndRestoreSettingsRequest implements Parcelable {

    /**
     * Indicates that an enabled state is not provided. This is used in 2 ways: As an initial state
     * in settings before any value was provided and to indicate in api calls that no new value is
     * provided and previous values should be kept. Calling
     * updateHealthConnectBackupAndRestoreSettings with this value does therefore not reset the
     * enablement setting.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupAndRestoreSettings
     */
    @FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUPS_ENABLED_UNSET = 0;

    /**
     * Indicates that the user enabled Health Connect cloud backups.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupAndRestoreSettings
     */
    @FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUPS_ENABLED_TRUE = 1;

    /**
     * Indicates that the user did not enable Health Connect cloud backups.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupAndRestoreSettings
     */
    @FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUPS_ENABLED_FALSE = 2;

    /**
     * Enum for the enablement states for Cloud backups.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupAndRestoreSettings
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "BACKUPS_ENABLED_SETTING_",
            value = {BACKUPS_ENABLED_UNSET, BACKUPS_ENABLED_TRUE, BACKUPS_ENABLED_FALSE})
    public @interface BackupsEnabledSetting {}

    @Nullable private final String mBackupSettingsLabel;
    @Nullable private final String mTurnOnBackupsInvitationText;
    @Nullable private final String mAccountName;
    @BackupsEnabledSetting private final int mEnabledWithUserConsent;

    private UpdateBackupAndRestoreSettingsRequest(
            @Nullable String backupSettingsLabel,
            @Nullable String turnOnBackupsInvitationText,
            @Nullable String accountName,
            @BackupsEnabledSetting int enabledWithUserConsent) {
        mBackupSettingsLabel = backupSettingsLabel;
        mTurnOnBackupsInvitationText = turnOnBackupsInvitationText;
        mAccountName = accountName;
        mEnabledWithUserConsent = enabledWithUserConsent;
    }

    /** Builder for {@link UpdateBackupAndRestoreSettingsRequest} objects. */
    public static final class Builder {
        private @Nullable String mBackupSettingsLabel;
        private @Nullable String mTurnOnBackupsInvitationText;
        private @Nullable String mAccountName;
        private @BackupsEnabledSetting int mEnabledWithUserConsent = BACKUPS_ENABLED_UNSET;

        /**
         * Sets the label for the UI item in Health Connect settings that the user can click to
         * change their backup settings. Set this value to override system default strings. If the
         * label is not provided or it is set to {@code null} in the request, the label will be left
         * unchanged. Strings need to be translated to the system language before being passed in.
         */
        public Builder setBackupSettingsLabel(@Nullable String backupSettingsLabel) {
            mBackupSettingsLabel = backupSettingsLabel;
            return this;
        }

        /**
         * Sets the text used in Health Connect Settings to encourage users who don't have backups
         * enabled to enable cloud backups. Set this value to override system default strings, e.g.
         * for branding. If the invitation text is not provided or it is set to {@code null} in the
         * request, the invitation text will be left unchanged. Strings need to be translated to the
         * system language before being passed in.
         */
        public Builder setTurnOnBackupsInvitationText(
                @Nullable String turnOnBackupsInvitationText) {
            mTurnOnBackupsInvitationText = turnOnBackupsInvitationText;
            return this;
        }

        /**
         * Sets the account name currently used for backups. If the account name is not provided or
         * set to {@code null} in the request, the account name will be left unchanged. The account
         * name will be shown in the Health Connect backup settings.
         */
        public Builder setAccountName(@Nullable String accountName) {
            mAccountName = accountName;
            return this;
        }

        /**
         * Sets the user consent status for cloud backups. Use {@link BackupsEnabledSetting} values.
         * If the enabled setting is not provided or it is set to {@link #BACKUPS_ENABLED_UNSET} in
         * the request the value will stay unchanged in the system. The caller is responsible for
         * ensuring that the enabled state reflects user choices and consent.
         */
        public Builder setBackupsEnabledState(@BackupsEnabledSetting int backupsEnabledState) {
            mEnabledWithUserConsent = backupsEnabledState;
            return this;
        }

        /** Builds the {@link UpdateBackupAndRestoreSettingsRequest}. */
        public UpdateBackupAndRestoreSettingsRequest build() {
            return new UpdateBackupAndRestoreSettingsRequest(
                    mBackupSettingsLabel,
                    mTurnOnBackupsInvitationText,
                    mAccountName,
                    mEnabledWithUserConsent);
        }
    }

    private UpdateBackupAndRestoreSettingsRequest(Parcel in) {
        mBackupSettingsLabel = in.readString();
        mTurnOnBackupsInvitationText = in.readString();
        mAccountName = in.readString();
        mEnabledWithUserConsent = in.readInt();
    }

    public static final Creator<UpdateBackupAndRestoreSettingsRequest> CREATOR =
            new Creator<UpdateBackupAndRestoreSettingsRequest>() {
                @Override
                public UpdateBackupAndRestoreSettingsRequest createFromParcel(Parcel in) {
                    return new UpdateBackupAndRestoreSettingsRequest(in);
                }

                @Override
                public UpdateBackupAndRestoreSettingsRequest[] newArray(int size) {
                    return new UpdateBackupAndRestoreSettingsRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mBackupSettingsLabel);
        dest.writeString(mTurnOnBackupsInvitationText);
        dest.writeString(mAccountName);
        dest.writeInt(mEnabledWithUserConsent);
    }

    /**
     * Returns the label for the UI item in Health Connect settings that the user can click to
     * change their backup settings.
     */
    public @Nullable String getBackupSettingsLabel() {
        return mBackupSettingsLabel;
    }

    /**
     * Returns the text used in Health Connect Settings to encourage users who don't have backups
     * enabled to enable cloud backups.
     */
    public @Nullable String getTurnOnBackupsInvitationText() {
        return mTurnOnBackupsInvitationText;
    }

    /** Returns the account name currently used for backups. */
    public @Nullable String getAccountName() {
        return mAccountName;
    }

    /** Returns user consent status for cloud backups. */
    public @BackupsEnabledSetting int getEnabledWithUserConsent() {
        return mEnabledWithUserConsent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UpdateBackupAndRestoreSettingsRequest)) return false;
        UpdateBackupAndRestoreSettingsRequest that = (UpdateBackupAndRestoreSettingsRequest) o;
        return mEnabledWithUserConsent == that.mEnabledWithUserConsent
                && Objects.equals(mBackupSettingsLabel, that.mBackupSettingsLabel)
                && Objects.equals(mTurnOnBackupsInvitationText, that.mTurnOnBackupsInvitationText)
                && Objects.equals(mAccountName, that.mAccountName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mBackupSettingsLabel,
                mTurnOnBackupsInvitationText,
                mAccountName,
                mEnabledWithUserConsent);
    }
}
