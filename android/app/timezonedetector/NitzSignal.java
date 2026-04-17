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
package android.app.timezonedetector;

import android.annotation.DurationMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;
import java.util.TimeZone;

/**
 * A data class that captures information about a Network Identity and Timezone (NITZ) signal. This
 * information can be used by the device's time zone detector.
 *
 * @hide
 */
public final class NitzSignal implements Parcelable {

    /**
     * The elapsed realtime ({@link android.os.SystemClock#elapsedRealtime()}) when this NITZ signal
     * was received by the device.
     */
    @ElapsedRealtimeLong private final long mReceiptElapsedMillis;

    /** The age of the NITZ signal in milliseconds, measured from its reception time. */
    @DurationMillisLong private final long mAgeMillis;

    /** The time zone offset from UTC in milliseconds, provided by the NITZ signal. */
    private final int mZoneOffset;

    /**
     * The daylight saving time (DST) offset from standard time in milliseconds, provided by the
     * NITZ signal. {@code null} if no DST information is available.
     */
    private final Integer mDstOffset;

    /** The System.currentTimeMillis() corresponding to the NITZ signal's time. */
    private final long mCurrentTimeMillis;

    /**
     * The TimeZone object representing the host time zone of the emulator, if applicable. This is
     * useful for testing purposes.
     */
    @Nullable private final TimeZone mEmulatorHostTimeZone;

    /**
     * Creates a new {@link NitzSignal} instance.
     *
     * @param receiptElapsedMillis The elapsed realtime when the NITZ signal was received.
     * @param ageMillis The age of the NITZ signal in milliseconds.
     * @param zoneOffset The time zone offset from UTC in milliseconds.
     * @param dstOffset The DST offset in milliseconds, or {@code null}.
     * @param currentTimeMillis The System.currentTimeMillis() from the signal.
     * @param emulatorHostTimeZone The emulator host time zone, or a default if not applicable.
     */
    public NitzSignal(
            long receiptElapsedMillis,
            long ageMillis,
            int zoneOffset,
            Integer dstOffset,
            long currentTimeMillis,
            TimeZone emulatorHostTimeZone) {
        this.mReceiptElapsedMillis = receiptElapsedMillis;
        this.mAgeMillis = ageMillis;
        this.mZoneOffset = zoneOffset;
        this.mDstOffset = dstOffset;
        this.mCurrentTimeMillis = currentTimeMillis;
        this.mEmulatorHostTimeZone = emulatorHostTimeZone;
    }

    /** Returns the elapsed realtime when the NITZ signal was received. */
    public long getReceiptElapsedMillis() {
        return mReceiptElapsedMillis;
    }

    /** Returns the age of the NITZ signal in milliseconds. */
    public long getAgeMillis() {
        return mAgeMillis;
    }

    /** Returns the time zone offset from UTC in milliseconds. */
    public int getZoneOffset() {
        return mZoneOffset;
    }

    /** Returns the DST offset in milliseconds, or {@code null}. */
    public Integer getDstOffset() {
        return mDstOffset;
    }

    /** Returns the System.currentTimeMillis() from the signal. */
    public long getCurrentTimeMillis() {
        return mCurrentTimeMillis;
    }

    /** Returns the emulator host time zone. */
    @Nullable
    public TimeZone getEmulatorHostTimeZone() {
        return mEmulatorHostTimeZone;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof NitzSignal that) {
            return mReceiptElapsedMillis == that.mReceiptElapsedMillis
                    && mAgeMillis == that.mAgeMillis
                    && mZoneOffset == that.mZoneOffset
                    && mCurrentTimeMillis == that.mCurrentTimeMillis
                    && Objects.equals(mDstOffset, that.mDstOffset)
                    && Objects.equals(mEmulatorHostTimeZone, that.mEmulatorHostTimeZone);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mReceiptElapsedMillis,
                mAgeMillis,
                mZoneOffset,
                mDstOffset,
                mCurrentTimeMillis,
                mEmulatorHostTimeZone);
    }

    @Override
    public String toString() {
        return "NitzSignal{"
                + "mReceiptElapsedMillis="
                + mReceiptElapsedMillis
                + ", mAgeMillis="
                + mAgeMillis
                + ", mZoneOffset="
                + mZoneOffset
                + ", mDstOffset="
                + mDstOffset
                + ", mCurrentTimeMillis="
                + mCurrentTimeMillis
                + ", mEmulatorHostTimeZone="
                + (mEmulatorHostTimeZone == null ? null : mEmulatorHostTimeZone.getID())
                + '}';
    }

    /** Implement the {@link Parcelable} interface. */
    @Override
    public int describeContents() {
        return 0; // No special object types
    }

    /**
     * Writes the object's data to the parcel.
     *
     * @param dest The parcel to which the object's data is written.
     * @param flags Additional flags about how the object should be written. May be 0 or {@link
     *     #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mReceiptElapsedMillis);
        dest.writeLong(mAgeMillis);
        dest.writeInt(mZoneOffset);
        dest.writeSerializable(mDstOffset);
        dest.writeLong(mCurrentTimeMillis);
        dest.writeSerializable(mEmulatorHostTimeZone);
    }

    /** Helper to create {@link NitzSignal} objects from a {@link Parcel}. */
    public static final Creator<NitzSignal> CREATOR =
            new Creator<>() {
                @Override
                public NitzSignal createFromParcel(Parcel in) {
                    long receiptElapsedMillis = in.readLong();
                    long ageMillis = in.readLong();
                    int zoneOffset = in.readInt();
                    Integer dstOffset =
                            in.readSerializable(Integer.class.getClassLoader(), Integer.class);
                    long currentTimeMillis = in.readLong();
                    TimeZone emulatorHostTimeZone =
                            in.readSerializable(TimeZone.class.getClassLoader(), TimeZone.class);
                    return new NitzSignal(
                            receiptElapsedMillis,
                            ageMillis,
                            zoneOffset,
                            dstOffset,
                            currentTimeMillis,
                            emulatorHostTimeZone);
                }

                @Override
                public NitzSignal[] newArray(int size) {
                    return new NitzSignal[size];
                }
            };
}
