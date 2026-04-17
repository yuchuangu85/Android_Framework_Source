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

package android.app.appfunctions;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.appfunctions.flags.Flags;
import android.app.appsearch.GenericDocument;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The response to an app function execution, returned by {@link
 * AppFunctionManager#executeAppFunction}.
 *
 * <p>Use {@link AppFunctionMetadata} to determine the expected structure of the response.
 */
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER)
public final class ExecuteAppFunctionResponse implements Parcelable {
    @NonNull
    public static final Creator<ExecuteAppFunctionResponse> CREATOR =
            new Creator<ExecuteAppFunctionResponse>() {
                @Override
                public ExecuteAppFunctionResponse createFromParcel(Parcel parcel) {
                    GenericDocumentWrapper resultWrapper =
                            Objects.requireNonNull(
                                    GenericDocumentWrapper.CREATOR.createFromParcel(parcel));
                    Bundle extras =
                            Objects.requireNonNull(
                                    parcel.readBundle(Bundle.class.getClassLoader()));
                    if (Flags.enableAppFunctionPermissionV2()) {
                        List<AppFunctionUriGrant> uriGrants =
                                Objects.requireNonNull(
                                        parcel.createTypedArrayList(AppFunctionUriGrant.CREATOR));
                        return new ExecuteAppFunctionResponse(
                                resultWrapper.getValue(), extras, uriGrants);
                    } else {
                        return new ExecuteAppFunctionResponse(resultWrapper.getValue(), extras);
                    }
                }

                @Override
                public ExecuteAppFunctionResponse[] newArray(int size) {
                    return new ExecuteAppFunctionResponse[size];
                }
            };

    /**
     * The name of the property that stores the function return value within the {@link
     * #getResultDocument}.
     *
     * <p>If the function returns {@code void} or throws an error, this property will not be set.
     */
    public static final String PROPERTY_RETURN_VALUE = "androidAppfunctionsReturnValue";

    /**
     * Returns the return value of the executed function.
     *
     * <p>The return value is stored in a {@link GenericDocument} with the key {@link
     * #PROPERTY_RETURN_VALUE}.
     *
     * <p>See {@link #getResultDocument} for more information on extracting the return value.
     */
    @NonNull private final GenericDocumentWrapper mResultDocumentWrapper;

    /** Returns the additional metadata data relevant to this function execution response. */
    @NonNull private final Bundle mExtras;

    /**
     * The list of {@link AppFunctionUriGrant} to which the caller of this app function execution
     * should have temporary access granted.
     */
    @NonNull private final List<AppFunctionUriGrant> mUriGrants;

    /**
     * Constructs an {@link ExecuteAppFunctionResponse}.
     *
     * @param resultDocument The return value of the executed function, see {@link
     *     #getResultDocument}.
     */
    public ExecuteAppFunctionResponse(@NonNull GenericDocument resultDocument) {
        this(resultDocument, Bundle.EMPTY);
    }

    /**
     * Constructs an {@link ExecuteAppFunctionResponse} with an additional {@link Bundle}.
     *
     * @param resultDocument The return value of the executed function, see {@link
     *     #getResultDocument}.
     * @param extras The additional metadata for this function execution response, see {@link
     *     #getExtras}.
     */
    public ExecuteAppFunctionResponse(
            @NonNull GenericDocument resultDocument, @NonNull Bundle extras) {
        mResultDocumentWrapper = new GenericDocumentWrapper(Objects.requireNonNull(resultDocument));
        mExtras = Objects.requireNonNull(extras);
        mUriGrants = Collections.emptyList();
    }

    /**
     * Constructs an {@link ExecuteAppFunctionResponse} with an additional {@link Bundle} and {@link
     * AppFunctionUriGrant}s.
     *
     * @param resultDocument The return value of the executed function, see {@link
     *     #getResultDocument}.
     * @param extras The additional metadata for this function execution response, see {@link
     *     #getExtras}.
     * @param uriGrants The list of {@link AppFunctionUriGrant} to which the caller of this app
     *     function execution should have temporary access granted.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
    public ExecuteAppFunctionResponse(
            @NonNull GenericDocument resultDocument,
            @NonNull Bundle extras,
            @NonNull List<AppFunctionUriGrant> uriGrants) {
        mResultDocumentWrapper = new GenericDocumentWrapper(Objects.requireNonNull(resultDocument));
        mExtras = Objects.requireNonNull(extras);
        mUriGrants = Objects.requireNonNull(uriGrants);
    }

    /**
     * Returns a {@link GenericDocument} containing the return value of the executed function.
     *
     * <p>The {@link #PROPERTY_RETURN_VALUE} key can be used to obtain the return value.
     *
     * <p>Example of extracting the return value:
     *
     * <pre>{@code
     * GenericDocument resultDocument = response.getResultDocument();
     * Object returnValue = resultDocument.getProperty(PROPERTY_RETURN_VALUE);
     * if (returnValue != null) {
     *   // Cast returnValue to expected type, or use GenericDocument#getPropertyString,
     *   // GenericDocument#getPropertyLong, etc.
     * }
     * }</pre>
     *
     * <p>Use {@link AppFunctionMetadata} to determine the expected structure of this document.
     */
    @NonNull
    public GenericDocument getResultDocument() {
        return mResultDocumentWrapper.getValue();
    }

    /** Returns the additional metadata for this function execution response. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * The list of {@link AppFunctionUriGrant} to which the caller of this app function execution
     * should have temporary access granted.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
    @NonNull
    public List<AppFunctionUriGrant> getUriGrants() {
        return mUriGrants;
    }

    /**
     * Returns the size of the response in bytes.
     *
     * @hide
     */
    public int getResponseDataSize() {
        return mResultDocumentWrapper.getDataSize() + mExtras.getSize();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mResultDocumentWrapper.writeToParcel(dest, flags);
        dest.writeBundle(mExtras);
        if (Flags.enableAppFunctionPermissionV2()) {
            dest.writeTypedList(mUriGrants, flags);
        }
    }
}
