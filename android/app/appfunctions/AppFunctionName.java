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
package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Globally unique identifier for an app function.
 *
 * <p>See {@link AppFunctionMetadata#getName} for how to define and retrieve the identifier for a
 * given app function.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionName implements Parcelable {
    @NonNull
    public static final Creator<AppFunctionName> CREATOR =
            new Creator<AppFunctionName>() {
                @Override
                public AppFunctionName createFromParcel(Parcel in) {
                    return new AppFunctionName(in);
                }

                @Override
                public AppFunctionName[] newArray(int size) {
                    return new AppFunctionName[size];
                }
            };

    @NonNull private final String mPackageName;
    @NonNull private final String mFunctionId;

    /**
     * Constructs an {@link AppFunctionName}.
     *
     * @param packageName The package name of the Android app which contains the app function.
     * @param functionIdentifier The identifier of the app function within the {@code packageName}.
     */
    public AppFunctionName(@NonNull String packageName, @NonNull String functionIdentifier) {
        mPackageName = requireNonNull(packageName);
        mFunctionId = requireNonNull(functionIdentifier);
    }

    private AppFunctionName(Parcel in) {
        mPackageName = requireNonNull(in.readString8());
        mFunctionId = requireNonNull(in.readString8());
    }

    /**
     * Creates an {@link AppFunctionName} from the given qualified function id.
     *
     * @throws IllegalArgumentException if given qualified function id has an incorrect format.
     * @hide
     */
    @NonNull
    public static AppFunctionName fromQualifiedId(@NonNull String qualifiedFunctionId)
            throws IllegalArgumentException {
        requireNonNull(qualifiedFunctionId);
        int separatorIndex = qualifiedFunctionId.indexOf('/');
        if (separatorIndex == -1 || separatorIndex == qualifiedFunctionId.length() - 1) {
            throw new IllegalArgumentException("Incorrect app function id format.");
        }
        return new AppFunctionName(
                qualifiedFunctionId.substring(0, separatorIndex),
                qualifiedFunctionId.substring(separatorIndex + 1));
    }

    /**
     * Gets the qualified id of {@link AppFunctionName}.
     *
     * @hide
     */
    public String getQualifiedId() {
        return TextUtils.formatSimple("%s/%s", mPackageName, mFunctionId);
    }

    /** Returns the package name of the Android app which contains the app function. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns the identifier of the app function within the {@code #getPackageName}. */
    @NonNull
    public String getFunctionIdentifier() {
        return mFunctionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppFunctionName that)) return false;
        return mPackageName.equals(that.mPackageName) && mFunctionId.equals(that.mFunctionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mFunctionId);
    }

    @Override
    public String toString() {
        return "AppFunctionName("
                + "packageName="
                + mPackageName
                + ", "
                + "functionIdentifier="
                + mFunctionId
                + ")";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mPackageName);
        dest.writeString8(mFunctionId);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
