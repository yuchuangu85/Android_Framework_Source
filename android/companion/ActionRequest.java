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
 * Represents a request for a companion app to perform a specific action.
 *
 * @see CompanionDeviceService#onActionRequested(AssociationInfo, ActionRequest)
 */
@FlaggedApi(Flags.FLAG_ENABLE_DATA_SYNC)
public final class ActionRequest implements Parcelable {
    /** @hide */
    @IntDef(prefix = {"OP_"}, value = {
            OP_ACTIVATE,
            OP_DEACTIVATE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Operation {}

    /**
     * An operation to request that the action be activated.
     */
    public static final int OP_ACTIVATE = 0;

    /**
     * An operation to request that the action be deactivated.
     */
    public static final int OP_DEACTIVATE = 1;

    /** @hide */
    @IntDef(prefix = {"REQUEST_"}, value = {
            REQUEST_NEARBY_SCANNING,
            REQUEST_NEARBY_ADVERTISING,
            REQUEST_TRANSPORT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestAction {}

    /**
     * An action that signals a request for the companion app to change its nearby scanning state.
     * <p>
     * When the system sends this request, the app receives a callback to
     * {@link CompanionDeviceService#onActionRequested(AssociationInfo, ActionRequest)}
     * with this constant.
     */
    public static final int REQUEST_NEARBY_SCANNING = 0;

    /**
     * An action that signals a request for the companion app to change its nearby
     * advertising state.
     * <p>
     * When the system sends this request, the app receives a callback to
     * {@link CompanionDeviceService#onActionRequested(AssociationInfo, ActionRequest)}
     * with this constant.
     */
    public static final int REQUEST_NEARBY_ADVERTISING = 1;

    /**
     * An action that signals a request for the companion app to attach or detach its system data
     * transport.
     * <p>
     * When the system sends this request, the app receives a callback to
     * {@link CompanionDeviceService#onActionRequested(AssociationInfo, ActionRequest)}
     * with this constant.
     */
    public static final int REQUEST_TRANSPORT = 2;

    private final @RequestAction int mAction;
    private final @Operation int mOperation;

    private ActionRequest(Builder builder) {
        mAction = builder.mAction;
        mOperation = builder.mOperation;
    }

    /**
     * @return The action being requested, such as
     *         {@link #REQUEST_NEARBY_SCANNING}.
     */
    public @RequestAction int getAction() {
        return mAction;
    }

    /**
     * @return The operation to perform, either {@link #OP_ACTIVATE} or {@link #OP_DEACTIVATE}.
     */
    public @Operation int getOperation() {
        return mOperation;
    }

    @Override
    public String toString() {
        return "ActionRequest{"
                + "mAction=" + mAction
                + ", mOperation="
                + mOperation + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActionRequest that = (ActionRequest) o;
        return mAction == that.mAction && mOperation == that.mOperation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAction, mOperation);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAction);
        dest.writeInt(mOperation);
    }

    public static final @NonNull Creator<ActionRequest> CREATOR = new Creator<>() {
        @Override
        public ActionRequest createFromParcel(Parcel in) {
            return new ActionRequest(in);
        }

        @Override
        public ActionRequest[] newArray(int size) {
            return new ActionRequest[size];
        }
    };

    private ActionRequest(Parcel in) {
        mAction = in.readInt();
        mOperation = in.readInt();
    }

    /**
     * @param action The action to convert to a string.
     * @hide
     */
    public static String actionToString(int action) {
        return switch (action) {
            case ActionRequest.REQUEST_NEARBY_SCANNING -> "REQUEST_NEARBY_SCANNING";
            case REQUEST_NEARBY_ADVERTISING -> "REQUEST_NEARBY_ADVERTISING";
            case ActionRequest.REQUEST_TRANSPORT -> "REQUEST_TRANSPORT";
            default -> "UNKNOWN_ACTION: " + action;
        };
    }

    /**
     * A builder for creating {@link ActionRequest}.
     */
    public static final class Builder {
        private final @RequestAction int mAction;
        private final @Operation int mOperation;

        /**
         * @param action The action to request.
         * @param operation The operation to perform.
         */
        public Builder(@RequestAction int action, @Operation int operation) {
            this.mAction = action;
            this.mOperation = operation;
        }
        /**
         * Builds the {@link ActionRequest} object.
         */
        @NonNull
        public ActionRequest build() {
            return new ActionRequest(this);
        }
    }
}
