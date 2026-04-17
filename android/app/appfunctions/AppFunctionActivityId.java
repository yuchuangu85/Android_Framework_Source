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
package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * An identifier of an {@link android.app.Activity} an app function can be associated with.
 *
 * <p>This is only relevant for app functions with {@link AppFunctionMetadata#SCOPE_ACTIVITY}.
 *
 * <p>Two instances of {@link AppFunctionActivityId} are always equal if the {@link
 * android.app.Activity} instance they are referencing are the same, even across multiple app
 * functions.
 *
 * <p>Use {@link android.service.voice.VoiceInteractionSession#getAppFunctionActivityId} to match an
 * {@link AppFunctionActivityId} with an {@link
 * android.service.voice.VoiceInteractionSession.ActivityId}.
 *
 * @see AppFunctionMetadata#SCOPE_ACTIVITY
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionActivityId implements Parcelable {
    @NonNull private final IBinder mAssistToken;

    /** @hide */
    public AppFunctionActivityId(@NonNull IBinder assistToken) {
        mAssistToken = assistToken;
    }

    private AppFunctionActivityId(@NonNull Parcel in) {
        mAssistToken = in.readStrongBinder();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mAssistToken);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof AppFunctionActivityId that
                        && Objects.equals(mAssistToken, that.mAssistToken));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAssistToken);
    }

    public static final @NonNull Creator<AppFunctionActivityId> CREATOR =
            new Creator<AppFunctionActivityId>() {
                @Override
                public AppFunctionActivityId createFromParcel(@NonNull Parcel in) {
                    return new AppFunctionActivityId(in);
                }

                @Override
                public AppFunctionActivityId[] newArray(int size) {
                    return new AppFunctionActivityId[size];
                }
            };
}
