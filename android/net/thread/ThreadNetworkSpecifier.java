/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.thread;

import static android.Manifest.permission.THREAD_NETWORK_PRIVILEGED;
import static android.net.NetworkCapabilities.REDACT_FOR_ACCESS_FINE_LOCATION;
import static android.net.thread.ActiveOperationalDataset.LENGTH_EXTENDED_PAN_ID;
import static android.net.thread.ActiveOperationalDataset.LENGTH_NETWORK_KEY;
import static android.net.thread.ActiveOperationalDataset.LENGTH_PSKC;

import static com.android.net.thread.flags.Flags.FLAG_THREAD_MOBILE_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import java.util.Arrays;
import java.util.Objects;

/**
 * Specifies and identifies a Thread network.
 *
 * <p>Using this class with {@link NetworkRequest} requires the app holding {@link
 * THREAD_NETWORK_PRIVILEGED} permission.
 *
 * @hide
 */
@FlaggedApi(FLAG_THREAD_MOBILE_ENABLED)
@SystemApi
public final class ThreadNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    // TODO: b/427955643 - use NetworkCapabilities#REDACT_FOR_THREAD_NETWORK_PRIVILEGED
    private static final long REDACT_FOR_THREAD_NETWORK_PRIVILEGED = 1 << 3;
    private static final String UNKNOWN_NETWORK_NAME = "UNKNOWN";

    /** The Active Operational Dataset of a Thread network. */
    @Nullable private final ActiveOperationalDataset mActiveDataset;

    private final boolean mShouldCreatePartitionIfNotFound;

    private ThreadNetworkSpecifier(@NonNull Builder builder) {
        mActiveDataset = builder.mActiveDataset;
        mShouldCreatePartitionIfNotFound = builder.mShouldCreatePartitionIfNotFound;
    }

    /** Returns the Active Operational Dataset of the Thread network this specifier refers to. */
    @Nullable
    public ActiveOperationalDataset getActiveOperationalDataset() {
        return mActiveDataset;
    }

    /**
     * Returns {@code true} if this device should create a Thread network partition with the given
     * {@code activeDataset} and become the Leader of the network partition if the target network
     * can't be found nearby.
     *
     * <p>Setting this option may cause significant battery drain because this device will need to
     * be a router and setting its radio to a high power state when creating a partition. This
     * should typically be used for a short period of time. For example, when provisioning new
     * Thread accessory devices.
     *
     * <p>This value is not used for matching in {@link #canBeSatisfiedBy}.
     */
    public boolean shouldCreatePartitionIfNotFound() {
        return mShouldCreatePartitionIfNotFound;
    }

    /**
     * Returns {@code true} if the Network Key of the Active Operational Dataset in the two {@link
     * ThreadNetworkSpecifier} objects are equal.
     *
     * <p>The full Active Operational Dataset rather than only the Network Key is needed in this
     * specifier because creating a partition requires full dataset.
     *
     * @hide
     */
    @Override
    public boolean canBeSatisfiedBy(@Nullable NetworkSpecifier other) {
        if (!(other instanceof ThreadNetworkSpecifier)) {
            return false;
        }

        final ThreadNetworkSpecifier otherSpecifier = (ThreadNetworkSpecifier) other;
        if (mActiveDataset == null) {
            return true;
        } else if (otherSpecifier.mActiveDataset == null) {
            return false;
        }
        return Arrays.equals(
                mActiveDataset.getNetworkKey(), otherSpecifier.mActiveDataset.getNetworkKey());
    }

    /** @hide */
    @Override
    public NetworkSpecifier redact(long redactions) {
        if (mActiveDataset == null) {
            return new ThreadNetworkSpecifier.Builder(this).build();
        }

        var redactedDatasetBuilder = new ActiveOperationalDataset.Builder(mActiveDataset);
        if ((redactions & REDACT_FOR_THREAD_NETWORK_PRIVILEGED) != 0
                || (redactions & REDACT_FOR_ACCESS_FINE_LOCATION) != 0) {
            redactedDatasetBuilder.setNetworkKey(new byte[LENGTH_NETWORK_KEY]);
            redactedDatasetBuilder.setPskc(new byte[LENGTH_PSKC]);
            redactedDatasetBuilder.setUnknownTlvs(new SparseArray<byte[]>());
        }

        if ((redactions & REDACT_FOR_ACCESS_FINE_LOCATION) != 0) {
            redactedDatasetBuilder.setExtendedPanId(new byte[LENGTH_EXTENDED_PAN_ID]);
            redactedDatasetBuilder.setPanId(0);
            redactedDatasetBuilder.setNetworkName(UNKNOWN_NETWORK_NAME);
            redactedDatasetBuilder.setMeshLocalPrefix(
                    new byte[] {(byte) 0xfd, 0, 0, 0, 0, 0, 0, 0});
        }

        return new ThreadNetworkSpecifier.Builder()
                .setActiveOperationalDataset(redactedDatasetBuilder.build())
                .setShouldCreatePartitionIfNotFound(mShouldCreatePartitionIfNotFound)
                .build();
    }

    /** @hide */
    @Override
    public long getApplicableRedactions() {
        return REDACT_FOR_ACCESS_FINE_LOCATION | REDACT_FOR_THREAD_NETWORK_PRIVILEGED;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof ThreadNetworkSpecifier)) {
            return false;
        } else if (this == other) {
            return true;
        }

        final ThreadNetworkSpecifier otherSpecifier = (ThreadNetworkSpecifier) other;

        return Objects.equals(mActiveDataset, otherSpecifier.mActiveDataset)
                && (mShouldCreatePartitionIfNotFound
                        == otherSpecifier.mShouldCreatePartitionIfNotFound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mActiveDataset, mShouldCreatePartitionIfNotFound);
    }

    @Override
    public String toString() {
        return "ThreadNetworkSpecifier{activeDataset="
                + mActiveDataset
                + ", shouldCreatePartitionIfNotFound="
                + mShouldCreatePartitionIfNotFound
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mActiveDataset, 0 /* parcelableFlags */);
        dest.writeBoolean(mShouldCreatePartitionIfNotFound);
    }

    public static final @NonNull Parcelable.Creator<ThreadNetworkSpecifier> CREATOR =
            new Parcelable.Creator<ThreadNetworkSpecifier>() {
                @Override
                public ThreadNetworkSpecifier createFromParcel(Parcel in) {
                    final ActiveOperationalDataset activeDataset =
                            in.readParcelable(
                                    ActiveOperationalDataset.class.getClassLoader(),
                                    ActiveOperationalDataset.class);
                    final boolean createPartitionIfNotFound = in.readBoolean();
                    return new Builder()
                            .setActiveOperationalDataset(activeDataset)
                            .setShouldCreatePartitionIfNotFound(createPartitionIfNotFound)
                            .build();
                }

                @Override
                public ThreadNetworkSpecifier[] newArray(int size) {
                    return new ThreadNetworkSpecifier[size];
                }
            };

    /** The builder for creating {@link ThreadNetworkSpecifier} objects. */
    public static final class Builder {
        @Nullable private ActiveOperationalDataset mActiveDataset;
        private boolean mShouldCreatePartitionIfNotFound;

        /** Creates an empty builder. */
        public Builder() {}

        /**
         * Creates a new {@link Builder} object by copying the data in the given {@code specifier}
         * object.
         *
         * @hide
         */
        public Builder(@NonNull ThreadNetworkSpecifier specifier) {
            mActiveDataset = specifier.mActiveDataset;
            mShouldCreatePartitionIfNotFound = specifier.mShouldCreatePartitionIfNotFound;
        }

        /**
         * Sets the Active Operational Dataset.
         *
         * <p>The dataset is for matching a satisfied network (see {@link
         * ThreadNetworkSpecifier#canBeSatisfiedBy}) or creating a new network partition when {@link
         * #setShouldCreatePartitionIfNotFound} is called with {@code true}.
         */
        @NonNull
        public Builder setActiveOperationalDataset(
                @Nullable ActiveOperationalDataset activeDataset) {
            mActiveDataset = activeDataset;
            return this;
        }

        /**
         * Sets whether this device should create a new Thread network partition if no existing
         * network with the given Active Operational Dataset can be found nearby.
         *
         * @see ThreadNetworkSpecifier#shouldCreatePartitionIfNotFound()
         */
        @NonNull
        public Builder setShouldCreatePartitionIfNotFound(boolean create) {
            mShouldCreatePartitionIfNotFound = create;
            return this;
        }

        /** Creates a new {@link ThreadNetworkSpecifier} object from values set so far. */
        @NonNull
        public ThreadNetworkSpecifier build() {
            return new ThreadNetworkSpecifier(this);
        }
    }
}
