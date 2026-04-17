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
package android.app.modes;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.Flags;

import java.util.ArrayList;
import java.util.List;

/**
 * A data object representing a mutation that can be applied to the mode system for updating
 * contextual mode states.
 *
 * <p>This class is used with {@link ContextualModeManager#mutateModes(ContextualModesMutation)} to
 * specify desired changes to one or more modes. It contains a list of {@link ContextualMode}
 * objects, where each {@link ContextualMode} object specifies the target mode and its desired new
 * state.
 *
 * @see ContextualModeManager
 * @see ContextualMode
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_ENABLE_DND_SYNC)
public final class ContextualModesMutation implements Parcelable {

    /** A {@link Parcelable.Creator} that can unparcel {@link ContextualModesMutation} objects. */
    @NonNull
    public static final Creator<ContextualModesMutation> CREATOR =
            new Creator<>() {
                @Override
                public ContextualModesMutation createFromParcel(Parcel in) {
                    return new ContextualModesMutation(in);
                }

                @Override
                public ContextualModesMutation[] newArray(int size) {
                    return new ContextualModesMutation[size];
                }
            };

    private final List<ContextualMode> mUpdatedModes;

    /**
     * Creates a new {@link ContextualModesMutation} instance.
     *
     * @param updatedModes a non-null list of {@link ContextualMode} objects, each specifying a mode
     *     and its desired new state
     */
    private ContextualModesMutation(@NonNull List<ContextualMode> updatedModes) {
        mUpdatedModes = List.copyOf(updatedModes);
    }

    private ContextualModesMutation(Parcel in) {
        List<ContextualMode> updatedModes = new ArrayList<>();
        in.readTypedList(updatedModes, ContextualMode.CREATOR);
        mUpdatedModes = List.copyOf(updatedModes);
    }

    /**
     * Returns an unmodifiable list of {@link ContextualMode} objects contained in this mutation.
     * Each {@link ContextualMode} object specifies a mode and its desired new state.
     *
     * @return a non-null, unmodifiable list of {@link ContextualMode}
     */
    @NonNull
    public List<ContextualMode> getUpdatedModes() {
        return mUpdatedModes;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mUpdatedModes);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ContextualModesMutation mutation)) {
            return false;
        }
        return mUpdatedModes.equals(mutation.mUpdatedModes);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return mUpdatedModes.hashCode();
    }

    /** A builder to construct a {@link ContextualModesMutation} instance. */
    public static final class Builder {
        private final List<ContextualMode> mUpdates = new ArrayList<>();

        /**
         * Add an updated {@link ContextualMode} to this mutation.
         *
         * @param mode the updated {@link ContextualMode}
         * @return same instance for chaining
         */
        @NonNull
        public Builder addUpdatedMode(@NonNull ContextualMode mode) {
            mUpdates.add(mode);
            return this;
        }

        /**
         * Build a {@link ContextualModesMutation} instance.
         *
         * @return the {@link ContextualModesMutation} instance
         */
        @NonNull
        public ContextualModesMutation build() {
            return new ContextualModesMutation(mUpdates);
        }
    }
}
