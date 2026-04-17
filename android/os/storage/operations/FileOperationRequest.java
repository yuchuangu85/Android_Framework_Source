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

package android.os.storage.operations;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.storage.FileManager;
import android.os.storage.operations.sources.OperationSource;
import android.os.storage.operations.sources.OperationSourceWrapper;
import android.os.storage.operations.targets.OperationTarget;
import android.os.storage.operations.targets.OperationTargetWrapper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Encapsulates a request to Move, or Copy files.
 *
 * @see android.os.storage.FileManager#enqueueOperation(FileOperationRequest)
 */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public final class FileOperationRequest implements Parcelable {

    /**
     * Operation mode indicating that files should be moved from the source to the target.
     *
     * <p>A move operation results in the files being transferred to the destination specified by
     * the {@link OperationTarget} and subsequently removed from the {@link OperationSource}.
     *
     * <p>Note: While a move will be attempted, it is possible that this still results in standard
     * Copy + Delete behavior, as the Source/Target may not be on the same underlying storage
     * volume.
     */
    public static final int OPERATION_MOVE = 1;

    /**
     * Operation mode indicating that files should be copied from the source to the target.
     *
     * <p>A copy operation creates duplicates of the files from the {@link OperationSource} at the
     * destination specified by the {@link OperationTarget}, while the original files remain
     * unchanged at the source.
     */
    public static final int OPERATION_COPY = 2;

    /** @hide */
    @IntDef(
            prefix = {"OPERATION_"},
            value = {OPERATION_MOVE, OPERATION_COPY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface OperationMode {}

    private final int mMode;
    private final OperationSource mSource;
    private final OperationTarget mTarget;
    private final boolean mShouldRegisterListener;

    private FileOperationRequest(Builder builder) {
        mMode = builder.mMode;
        mSource = builder.mSource;
        mTarget = builder.mTarget;
        mShouldRegisterListener = builder.mShouldRegisterListener;
    }

    private FileOperationRequest(Parcel in) {
        mMode = in.readInt();
        OperationSourceWrapper sourceWrapper =
                in.readParcelable(
                        OperationSourceWrapper.class.getClassLoader(),
                        OperationSourceWrapper.class);
        mSource =
                sourceWrapper != null
                        ? sourceWrapper.getWrappedSource()
                        : OperationSource.getInvalidSource();
        OperationTargetWrapper targetWrapper =
                in.readParcelable(
                        OperationTargetWrapper.class.getClassLoader(),
                        OperationTargetWrapper.class);
        mTarget =
                targetWrapper != null
                        ? targetWrapper.getWrappedTarget()
                        : OperationTarget.getInvalidTarget();
        mShouldRegisterListener = in.readBoolean();
    }

    /**
     * Returns the type of file operation to be performed for this request.
     *
     * @return The operation mode, which will be either {@link #OPERATION_MOVE} or {@link
     *     #OPERATION_COPY}.
     */
    @OperationMode
    public int getMode() {
        return mMode;
    }

    /** Returns the source of the file operation. */
    @NonNull
    public OperationSource getSource() {
        return mSource;
    }

    /** Returns the target of the file operation. */
    @Nullable
    public OperationTarget getTarget() {
        return mTarget;
    }

    /**
     * Returns true if a completion listener should be automatically registered for this request.
     */
    public boolean shouldRegisterCompletionListener() {
        return mShouldRegisterListener;
    }

    @NonNull
    public static final Creator<FileOperationRequest> CREATOR =
            new Creator<FileOperationRequest>() {
                @Override
                public FileOperationRequest createFromParcel(Parcel in) {
                    return new FileOperationRequest(in);
                }

                @Override
                public FileOperationRequest[] newArray(int size) {
                    return new FileOperationRequest[size];
                }
            };

    /** implemented for Parcelable */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implemented for Parcelable */
    @Override
    @SuppressWarnings("AndroidFrameworkEfficientParcelable")
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMode);
        dest.writeParcelable(new OperationSourceWrapper(mSource), flags);
        dest.writeParcelable(new OperationTargetWrapper(mTarget), flags);
        dest.writeBoolean(mShouldRegisterListener);
    }

    /**
     * Builder for constructing {@link FileOperationRequest} instances.
     *
     * <p>A builder must be initialized with an {@link OperationMode} (Move or Copy). Before calling
     * {@link #build()}, a source must be specified.
     *
     * <p><b>Example Usage:</b>
     *
     * <pre>{@code
     * // Initialize the builder with the desired operation mode
     * FileOperationRequest.Builder builder = new FileOperationRequest.Builder(
     *         FileOperationRequest.OPERATION_MOVE);
     *
     * // Define the source files (e.g., from the app's internal data)
     * OperationSource source = new AppDataFileSource(new File(getDataDir(), "logs.db"));
     *
     * // Define the destination (e.g., a subdirectory in PCC)
     * OperationTarget target = new PccTarget("archive/databases");
     *
     * // Configure and build the request
     * FileOperationRequest request = builder
     *         .setSource(source)
     *         .setTarget(target)
     *         .build();
     * }</pre>
     */
    public static final class Builder {
        private int mMode;
        private OperationSource mSource;
        private OperationTarget mTarget;
        private boolean mShouldRegisterListener;

        /**
         * Creates a new builder for a specific file operation mode.
         *
         * @param mode The operation to perform; must be {@link #OPERATION_MOVE} or {@link
         *     #OPERATION_COPY}.
         */
        public Builder(@OperationMode int mode) {
            mMode = mode;
        }

        /**
         * Sets the source of the file operation.
         *
         * @param source The {@link OperationSource} representing the files to be operated on.
         * @return This builder instance.
         */
        @NonNull
        public Builder setSource(@NonNull OperationSource source) {
            mSource = source;
            return this;
        }

        /**
         * Sets the target of the file operation.
         *
         * @param target The {@link OperationTarget} representing the destination of the operation.
         * @return This builder instance.
         */
        @NonNull
        public Builder setTarget(@NonNull OperationTarget target) {
            mTarget = target;
            return this;
        }

        /**
         * Sets whether a completion listener should be automatically registered for this request
         * when it is enqueued.
         *
         * <p>If set to {@code true}, the calling application will receive a {@link
         * FileManager#ACTION_FILE_OPERATION_COMPLETED} broadcast when the operation finishes.
         *
         * <p>IMPORTANT: If the operation is rejected or fails to be queued, the listener will not
         * be registered. Apps should ensure their requests are enqueued successfully via {@link
         * FileOperationEnqueueResult#isSuccessful}
         *
         * @param shouldRegister Whether to register a completion listener.
         * @return This builder instance.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setRegisterCompletionListener(boolean shouldRegister) {
            mShouldRegisterListener = shouldRegister;
            return this;
        }

        /**
         * Builds the {@link FileOperationRequest} instance.
         *
         * @return A new {@link FileOperationRequest} instance.
         * @throws IllegalStateException if the source or target (for Move/Copy) is not set.
         */
        @NonNull
        public FileOperationRequest build() {
            if (mSource == null) {
                throw new IllegalStateException("Source must be set");
            }
            if (mTarget == null) {
                throw new IllegalStateException("Target must be set for Move/Copy operations");
            }
            return new FileOperationRequest(this);
        }
    }
}
