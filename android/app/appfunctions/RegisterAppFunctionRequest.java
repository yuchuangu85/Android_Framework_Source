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
import android.annotation.NonNull;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A request to register an {@link AppFunction} implementation, provided to {@link
 * AppFunctionManager#registerAppFunctions}.
 *
 * <p>This encapsulates the information needed to register a single app function, equivalent to the
 * arguments to {@link AppFunctionManager#registerAppFunction}.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class RegisterAppFunctionRequest {
    private final String mFunctionIdentifier;
    private final Executor mExecutor;
    private final AppFunction mAppFunction;

    /**
     * Constructs a {@link RegisterAppFunctionRequest}.
     *
     * @param functionIdentifier The unique identifier for the function.
     * @param executor The {@link Executor} on which the function will be invoked.
     * @param appFunction The {@link AppFunction} implementation to be executed when the function is
     *     triggered.
     */
    public RegisterAppFunctionRequest(
            @NonNull String functionIdentifier,
            @NonNull Executor executor,
            @NonNull AppFunction appFunction) {
        mFunctionIdentifier = Objects.requireNonNull(functionIdentifier);
        mExecutor = Objects.requireNonNull(executor);
        mAppFunction = Objects.requireNonNull(appFunction);
    }

    /** Returns the unique identifier of the function to be registered. */
    @NonNull
    public String getFunctionIdentifier() {
        return mFunctionIdentifier;
    }

    /** Returns the {@link Executor} on which the function will be invoked. */
    @NonNull
    public Executor getExecutor() {
        return mExecutor;
    }

    /** Returns the {@link AppFunction} implementation to be registered. */
    @NonNull
    public AppFunction getAppFunction() {
        return mAppFunction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegisterAppFunctionRequest that)) return false;
        return mFunctionIdentifier.equals(that.mFunctionIdentifier)
                && mExecutor.equals(that.mExecutor)
                && mAppFunction.equals(that.mAppFunction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFunctionIdentifier, mAppFunction, mExecutor);
    }
}
