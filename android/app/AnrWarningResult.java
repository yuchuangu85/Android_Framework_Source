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

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.app.AnrTypes.AnrType;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.Objects;

/** Describes the information about a potential ANR before the timeout is reached. */
@FlaggedApi(Flags.FLAG_ENABLE_ANR_WARNING_CALLBACK)
public final class AnrWarningResult implements Parcelable {

    /** Implement the parcelable interface. */
    @NonNull
    public static final Creator<AnrWarningResult> CREATOR =
            new Creator<>() {
                @Override
                public AnrWarningResult createFromParcel(Parcel in) {
                    return new AnrWarningResult(in);
                }

                @Override
                public AnrWarningResult[] newArray(int size) {
                    return new AnrWarningResult[size];
                }
            };

    private final int mAnrId;

    @AnrType private final int mAnrType;

    @DurationMillisLong private final long mConsumedMillis;

    @DurationMillisLong private final long mTimeoutMillis;

    private final String mDescription;

    /** @hide */
    public AnrWarningResult(
            int anrId,
            @AnrType int anrType,
            @DurationMillisLong long consumedMillis,
            @DurationMillisLong long timeoutMillis,
            String description) {
        mAnrId = anrId;
        mAnrType = anrType;
        mConsumedMillis = consumedMillis;
        mTimeoutMillis = timeoutMillis;
        mDescription = description;
    }

    /**
     * The id for the ANR event.
     *
     * <p>This id will link to the anr id in {@link ApplicationExitInfo} if it becomes an ANR.
     *
     * <p>For each {@code mAnrType}, {@code mAnrId} is unique.
     */
    public int getAnrId() {
        return mAnrId;
    }

    /** The type of the ANR event. */
    @AnrType
    public int getAnrType() {
        return mAnrType;
    }

    /**
     * The duration in milliseconds that the ANR process has been unresponsive or blocked at the
     * time the warning is generated.
     *
     * <p> This duration is measured in the {@link android.os.SystemClock#uptimeMillis} timebase.
     */
    @DurationMillisLong
    public long getConsumedMillis() {
        return mConsumedMillis;
    }

    /** The total duration in milliseconds the system will wait before ANR is declared. */
    @DurationMillisLong
    public long getTimeoutMillis() {
        return mTimeoutMillis;
    }

    /**
     * A short description string providing context for the ANR warning.
     *
     * <p>This string contains debugging information for ANR and is not guaranteed to be stable and
     * may change in future Android versions.
     *
     * <p>While the format is not stable, the description can be useful for clustering reports of
     * similar ANRs. For eg, for an ANR related to be a broadcast receiver, the description might
     * look like: {@code "Broadcast of Intent { act=com.example.MY_ACTION flg=0x10 }"}
     */
    @NonNull
    public String getDescription() {
        return mDescription;
    }

    private AnrWarningResult(Parcel in) {
        this(in.readInt(), in.readInt(), in.readLong(), in.readLong(), in.readString8());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAnrId);
        dest.writeInt(mAnrType);
        dest.writeLong(mConsumedMillis);
        dest.writeLong(mTimeoutMillis);
        dest.writeString8(mDescription);
    }

    @Override
    public String toString() {
        return TextUtils.formatSimple(
                "AnrWarningResult(anrId=%d anrType=%d consumedMillis=%d timeoutMillis=%d"
                        + " description=%s)",
                mAnrId, mAnrType, mConsumedMillis, mTimeoutMillis, mDescription);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof AnrWarningResult o)) {
            return false;
        }

        return mAnrId == o.mAnrId
                && mAnrType == o.mAnrType
                && mConsumedMillis == o.mConsumedMillis
                && mTimeoutMillis == o.mTimeoutMillis
                && mDescription.equals(o.mDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAnrId, mAnrType, mConsumedMillis, mTimeoutMillis, mDescription);
    }
}
