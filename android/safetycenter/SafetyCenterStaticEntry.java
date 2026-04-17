/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.safetycenter;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.permission.flags.Flags;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import java.util.Objects;

/**
 * A static, stateless entry in the Safety Center.
 *
 * <p>Static entries have no changing severity level or associated issues. They provide simple links
 * or actions for safety-related features via {@link #getPendingIntent()}.
 *
 * @hide
 */
@SystemApi
@RequiresApi(TIRAMISU)
public final class SafetyCenterStaticEntry implements Parcelable {

    @NonNull
    public static final Creator<SafetyCenterStaticEntry> CREATOR =
            new Creator<SafetyCenterStaticEntry>() {
                @Override
                public SafetyCenterStaticEntry createFromParcel(Parcel in) {
                    SafetyCenterStaticEntry.Builder builder;
                    CharSequence title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
                    if (Flags.openSafetyCenterApis()) {
                        String safetySourceId = in.readString();
                        UserHandle user = in.readTypedObject(UserHandle.CREATOR);
                        if (safetySourceId == null || user == null) {
                            builder =
                                    new SafetyCenterStaticEntry.Builder(
                                            title, /* checkTargetSdk= */ false);
                        } else {
                            builder =
                                    new SafetyCenterStaticEntry.Builder(
                                            title, user, safetySourceId);
                        }
                    } else {
                        builder = new SafetyCenterStaticEntry.Builder(title);
                    }
                    return builder.setSummary(TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in))
                            .setPendingIntent(in.readTypedObject(PendingIntent.CREATOR))
                            .build();
                }

                @Override
                public SafetyCenterStaticEntry[] newArray(int size) {
                    return new SafetyCenterStaticEntry[size];
                }
            };

    @NonNull private final CharSequence mTitle;
    @Nullable private final CharSequence mSummary;
    @Nullable private final PendingIntent mPendingIntent;
    @Nullable private final String mSafetySourceId;
    @Nullable private final UserHandle mUser;

    private SafetyCenterStaticEntry(
            @NonNull CharSequence title,
            @Nullable CharSequence summary,
            @Nullable PendingIntent pendingIntent,
            @Nullable String safetySourceId,
            @Nullable UserHandle user) {
        mTitle = title;
        mSummary = summary;
        mPendingIntent = pendingIntent;
        mSafetySourceId = safetySourceId;
        mUser = user;
    }

    /** Returns the title that describes this entry. */
    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns the optional summary text that describes this entry if present, or {@code null}
     * otherwise.
     */
    @Nullable
    public CharSequence getSummary() {
        return mSummary;
    }

    /**
     * Returns the optional {@link PendingIntent} to execute when this entry is selected if present,
     * or {@code null} otherwise.
     */
    @Nullable
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /**
     * Returns the safety source ID related to this entry.
     *
     * <p>The field is only nullable for legacy reasons, it should always be present, and if it's
     * not present it's expected that the UI can't display the information related to this entry.
     */
    @FlaggedApi(Flags.FLAG_OPEN_SAFETY_CENTER_APIS)
    @Nullable
    public String getSafetySourceId() {
        return mSafetySourceId;
    }

    /**
     * Returns the user handle related to this entry.
     *
     * <p>The field is only nullable for legacy reasons, it should always be present, and if it's
     * not present it's expected that the UI can't display the information related to this entry.
     */
    @FlaggedApi(Flags.FLAG_OPEN_SAFETY_CENTER_APIS)
    @Nullable
    public UserHandle getUser() {
        return mUser;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SafetyCenterStaticEntry)) return false;
        SafetyCenterStaticEntry that = (SafetyCenterStaticEntry) o;
        return TextUtils.equals(mTitle, that.mTitle)
                && TextUtils.equals(mSummary, that.mSummary)
                && Objects.equals(mPendingIntent, that.mPendingIntent)
                && Objects.equals(mSafetySourceId, that.mSafetySourceId)
                && Objects.equals(mUser, that.mUser);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTitle, mSummary, mPendingIntent, mSafetySourceId, mUser);
    }

    @Override
    public String toString() {
        return "SafetyCenterStaticEntry{"
                + "mTitle="
                + mTitle
                + ", mSummary="
                + mSummary
                + ", mPendingIntent="
                + mPendingIntent
                + (Flags.openSafetyCenterApis() ? ", mSafetySourceId=" + mSafetySourceId : "")
                + (Flags.openSafetyCenterApis() ? ", mUser=" + mUser : "")
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        TextUtils.writeToParcel(mTitle, dest, flags);
        if (Flags.openSafetyCenterApis()) {
            dest.writeString(mSafetySourceId);
            dest.writeTypedObject(mUser, flags);
        }
        TextUtils.writeToParcel(mSummary, dest, flags);
        dest.writeTypedObject(mPendingIntent, flags);
    }

    /** Builder class for {@link SafetyCenterStaticEntry}. */
    public static final class Builder {

        @NonNull private CharSequence mTitle;
        @Nullable private CharSequence mSummary;
        @Nullable private PendingIntent mPendingIntent;
        @Nullable private String mSafetySourceId;
        @Nullable private UserHandle mUser;

        /**
         * Creates a {@link Builder} for a {@link SafetyCenterStaticEntry}.
         *
         * @param title a title that describes this static entry
         * @throws UnsupportedOperationException If the target SDK version of the app is above or
         *     equal to Android C
         * @deprecated Use the builder with the {@code safetySourceId} and {@code user} fields
         *     instead.
         */
        @FlaggedApi(Flags.FLAG_OPEN_SAFETY_CENTER_APIS)
        @Deprecated
        public Builder(@NonNull CharSequence title) {
            this(title, /* checkTargetSdk= */ true);
        }

        /**
         * Creates a {@link Builder} for a {@link SafetyCenterStaticEntry}.
         *
         * @param checkTargetSdk whether to check for the target SDK level
         * @throws UnsupportedOperationException If the target SDK version of the app is above or
         *     equal to Android C, when the {@code checkTargetSdk} argument is true
         */
        private Builder(@NonNull CharSequence title, boolean checkTargetSdk) {
            if (checkTargetSdk
                    && Flags.openSafetyCenterApis()
                    && CompatChanges.isChangeEnabled(
                            SafetyCenterManager.RESTRICT_DEPRECATED_DATA_BUILDER_CONSTRUCTORS)) {
                throw new UnsupportedOperationException(
                        "Deprecated constructor no longer accessible.");
            }
            mTitle = requireNonNull(title);
        }

        /**
         * Creates a {@link Builder} for a {@link SafetyCenterStaticEntry}.
         *
         * @param title a title that describes this static entry
         * @param user the user handle for this entry
         * @param safetySourceId the safety source ID for this entry
         */
        @FlaggedApi(Flags.FLAG_OPEN_SAFETY_CENTER_APIS)
        public Builder(
                @NonNull CharSequence title,
                @NonNull UserHandle user,
                @NonNull String safetySourceId) {
            mTitle = requireNonNull(title);
            mUser = requireNonNull(user);
            mSafetySourceId = requireNonNull(safetySourceId);
        }

        /**
         * Creates a {@link Builder} with the values from the given {@link SafetyCenterStaticEntry}.
         */
        public Builder(@NonNull SafetyCenterStaticEntry safetyCenterStaticEntry) {
            mTitle = safetyCenterStaticEntry.mTitle;
            mSummary = safetyCenterStaticEntry.mSummary;
            mPendingIntent = safetyCenterStaticEntry.mPendingIntent;
            if (Flags.openSafetyCenterApis()) {
                mSafetySourceId = safetyCenterStaticEntry.mSafetySourceId;
                mUser = safetyCenterStaticEntry.mUser;
            }
        }

        /** Sets the title for this entry. */
        @NonNull
        public Builder setTitle(@NonNull CharSequence title) {
            mTitle = requireNonNull(title);
            return this;
        }

        /** Sets the optional summary text for this entry. */
        @NonNull
        public Builder setSummary(@Nullable CharSequence summary) {
            mSummary = summary;
            return this;
        }

        /** Sets the optional {@link PendingIntent} to execute when this entry is selected. */
        @NonNull
        public Builder setPendingIntent(@Nullable PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Sets the safety source ID for this entry.
         *
         * @param safetySourceId the safety source ID for this entry
         */
        @FlaggedApi(Flags.FLAG_OPEN_SAFETY_CENTER_APIS)
        @NonNull
        public Builder setSafetySourceId(@NonNull String safetySourceId) {
            mSafetySourceId = requireNonNull(safetySourceId);
            return this;
        }

        /**
         * Sets the user handle for this entry.
         *
         * @param user the user handle for this entry
         */
        @FlaggedApi(Flags.FLAG_OPEN_SAFETY_CENTER_APIS)
        @NonNull
        public Builder setUser(@NonNull UserHandle user) {
            mUser = requireNonNull(user);
            return this;
        }

        /** Creates the {@link SafetyCenterStaticEntry} defined by this {@link Builder}. */
        @NonNull
        public SafetyCenterStaticEntry build() {
            return new SafetyCenterStaticEntry(
                    mTitle, mSummary, mPendingIntent, mSafetySourceId, mUser);
        }
    }
}
