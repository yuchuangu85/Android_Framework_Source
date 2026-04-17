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
package android.companion;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A result reported by a companion app in response to an {@link ActionRequest}.
 *
 * @see CompanionDeviceManager#notifyActionResult(int, ActionResult)
 */
@FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
public final class ActionResult implements Parcelable {
    /** @hide */
    @IntDef(prefix = {"RESULT_"}, value = {
            RESULT_ACTIVATED,
            RESULT_FAILED_TO_ACTIVATE,
            RESULT_DEACTIVATED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    /**
     * A result code indicating that the requested activation was completed successfully.
     * This is sent in response to an {@link ActionRequest#OP_ACTIVATE} request.
     */
    public static final int RESULT_ACTIVATED = 0;

    /**
     * A result code indicating that the requested activation failed.
     * This is sent in response to an {@link ActionRequest#OP_ACTIVATE} request.
     */
    public static final int RESULT_FAILED_TO_ACTIVATE = 1;

    /**
     * A result code indicating that a previously active action is now inactive.
     * This can be sent for two reasons:
     * 1. As a successful response to an {@link ActionRequest#OP_DEACTIVATE} request.
     * 2. Action was failed after initially succeed.
     */
    public static final int RESULT_DEACTIVATED = 2;

    private final @ResultCode int mResultCode;
    private final @ActionRequest.RequestAction int mAction;

    private ActionResult(Builder builder) {
        mResultCode = builder.mResultCode;
        mAction = builder.mAction;
    }

    /**
     * @return the result code, e.g., {@link #RESULT_ACTIVATED}.
     */
    public @ResultCode int getResultCode() {
        return mResultCode;
    }

    /**
     * @return the action this result refers to, e.g. {@link ActionRequest#REQUEST_NEARBY_SCANNING}.
     */
    public @ActionRequest.RequestAction int getAction() {
        return mAction;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
        dest.writeInt(mAction);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActionResult that = (ActionResult) o;
        return  mResultCode == that.mResultCode
                && mAction == that.mAction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mResultCode, mAction);
    }

    @Override
    public String toString() {
        return "ActionResult{"
                + ", mAction=" + mAction
                + ", mResultCode=" + mResultCode
                + '}';
    }

    @NonNull
    public static final Creator<ActionResult> CREATOR = new Creator<>() {
        @Override
        @NonNull
        public ActionResult createFromParcel(Parcel in) {
            return new ActionResult(in);
        }

        @Override
        @NonNull
        public ActionResult[] newArray(int size) {
            return new ActionResult[size];
        }
    };

    private ActionResult(Parcel in) {
        mResultCode = in.readInt();
        mAction = in.readInt();
    }

    /**
     * Builder for creating an {@link ActionResult}.
     */
    public static final class Builder {

        private final int mResultCode;
        private final @ActionRequest.RequestAction int mAction;

        /**
         * @param action The action this result is for,
         *               e.g. {@link ActionRequest#REQUEST_NEARBY_SCANNING}.
         *
         * @param resultCode The result code for this result.
         */
        public Builder(@ActionRequest.RequestAction int action, @ResultCode int resultCode) {
            mAction = action;
            mResultCode = resultCode;
        }

        /**
         * Builds the {@link ActionResult} object.
         */
        @NonNull
        public ActionResult build() {
            return new ActionResult(this);
        }
    }
}
