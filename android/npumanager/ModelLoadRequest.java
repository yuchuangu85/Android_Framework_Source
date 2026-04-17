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
package android.npumanager;

import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_BACKGROUND;
import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_NORMAL;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.npumanager.NpuManager.NpuModelPriority;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * An object representing a request to load or unload a model.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
public class ModelLoadRequest implements Parcelable {
    private final ModelLoadRequestParcelable mParcelable;
    private final int mUid;

    ModelLoadRequest(ModelLoadRequestParcelable parcelable) {
        mParcelable = parcelable;
        mUid = Binder.getCallingUid();
    }

    ModelLoadRequestParcelable getParcelable() {
        ModelLoadRequestParcelable parcelable = new ModelLoadRequestParcelable();
        parcelable.id = mParcelable.id;
        parcelable.size = mParcelable.size;
        parcelable.priority = mParcelable.priority;
        return parcelable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ModelLoadRequest that = (ModelLoadRequest) o;
        return mParcelable.equals(that.mParcelable) && mUid == that.mUid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mParcelable, mUid);
    }

    @Override
    public String toString() {
        return "ModelLoadRequest{"
                + "mId="
                + mParcelable.id
                + ", mSize="
                + mParcelable.size
                + ", mPriority="
                + mParcelable.priority
                + ", mUid="
                + mUid
                + '}';
    }

    /**
     * Returns the id of the model load request.
     *
     * @return The id of the model load request.
     */
    @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE)
    public int getId() {
        return mParcelable.id;
    }

    @Override
    public int describeContents() {
        return mParcelable.describeContents();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mParcelable.writeToParcel(dest, flags);
    }

    public static final @NonNull Creator<ModelLoadRequest> CREATOR =
            new Creator<ModelLoadRequest>() {
                @Override
                public ModelLoadRequest createFromParcel(Parcel in) {
                    return new ModelLoadRequest(
                            ModelLoadRequestParcelable.CREATOR.createFromParcel(in));
                }

                @Override
                public ModelLoadRequest[] newArray(int size) {
                    return new ModelLoadRequest[size];
                }
            };

    /** Builder for {@link ModelLoadRequest}. */
    public static class Builder {
        private final ModelLoadRequestParcelable mParcelable = new ModelLoadRequestParcelable();

        /**
         * Sets the size of the model to load.
         *
         * @param size The size of the model to load. Must be one of {@link NpuModelSize}.
         * @return The builder.
         */
        public Builder setSize(@NpuModelSize int size) {
            mParcelable.size = size;
            return this;
        }

        /**
         * Sets the priority of the model to load.
         *
         * @param priority The priority of the model to load. Must be between {@link
         *     NpuManager#NPU_MODEL_PRIORITY_NORMAL} and {@link
         *     NpuManager#NPU_MODEL_PRIORITY_BACKGROUND}.
         * @return The builder.
         */
        public Builder setPriority(int priority) {
            mParcelable.priority = priority;
            return this;
        }

        /**
         * Constructor for the builder which sets the id for the request. Ids should be unique for
         * each request.
         *
         * @param id The id of the model to load.
         */
        public Builder(int id) {
            mParcelable.id = id;
        }

        /**
         * Builds the {@link ModelLoadRequest}.
         *
         * @return The {@link ModelLoadRequest}.
         */
        public ModelLoadRequest build() {
            return new ModelLoadRequest(mParcelable);
        }
    }

    /**
     * Returns the size of the model to load.
     *
     * @return The size of the model to load.
     */
    @IntRange(from = NPU_MODEL_SIZE_LESS_THAN_1GB, to = NPU_MODEL_SIZE_GREATER_THAN_2G)
    public @NpuModelSize int getSize() {
        return mParcelable.size;
    }

    /**
     * Returns the priority of the model to load.
     *
     * @return The priority of the model to load.
     */
    @IntRange(from = NPU_MODEL_PRIORITY_NORMAL, to = NPU_MODEL_PRIORITY_BACKGROUND)
    public @NpuModelPriority int getPriority() {
        return mParcelable.priority;
    }
}
