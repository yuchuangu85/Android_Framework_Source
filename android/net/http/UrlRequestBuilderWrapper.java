/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.http;

import android.net.Network;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

class UrlRequestBuilderWrapper extends android.net.http.UrlRequest.Builder {
    private boolean mCacheDisabled;
    private boolean mDirectExecutorAllowed;

    private Consumer<org.chromium.net.ExperimentalUrlRequest.Builder> mBuilderMutator =
            builder -> {};
    private Consumer<android.net.http.UrlRequestWrapper> mWrapperMutator = wrapper -> {};
    private UrlRequestCallbackWrapper mCallbackWrapper;
    private org.chromium.net.ExperimentalUrlRequest.Builder mBackend;
    private List<Map.Entry<String, String>> mHeaders;
    private boolean mHttpMethodSet;
    private final org.chromium.net.ExperimentalCronetEngine mCronetEngine;
    private final String mUrl;
    private final Executor mExecutor;
    private final android.net.http.UrlRequest.Callback mCallback;

    public UrlRequestBuilderWrapper(
            org.chromium.net.ExperimentalCronetEngine cronetEngine,
            String url,
            Executor executor,
            android.net.http.UrlRequest.Callback callback) {
        this.mCronetEngine = cronetEngine;
        this.mUrl = url;
        this.mExecutor = executor;
        this.mCallback = callback;
        this.mHeaders = new ArrayList<>();
        this.mCallbackWrapper = new UrlRequestCallbackWrapper(mCallback);
        this.mBackend = mCronetEngine.newUrlRequestBuilder(mUrl, mCallbackWrapper, mExecutor);
    }

    private void maybeCreateBackendAndApplyMutator() {
        if (mBackend != null) {
            return;
        }
        mCallbackWrapper = new UrlRequestCallbackWrapper(mCallback);
        mBackend = mCronetEngine.newUrlRequestBuilder(mUrl, mCallbackWrapper, mExecutor);
        mBuilderMutator.accept(mBackend);
    }

    private void mutate(
            Consumer<org.chromium.net.ExperimentalUrlRequest.Builder> builderMutator,
            Consumer<android.net.http.UrlRequestWrapper> wrapperMutator) {
        maybeCreateBackendAndApplyMutator();
        mBuilderMutator = mBuilderMutator.andThen(builderMutator);
        builderMutator.accept(mBackend);
        if (wrapperMutator != null) {
            mWrapperMutator = mWrapperMutator.andThen(wrapperMutator);
        }
    }

    @Override
    public android.net.http.UrlRequest.Builder setHttpMethod(String method) {
        mutate(builder -> builder.setHttpMethod(method), wrapper -> wrapper.setHttpMethod(method));
        mHttpMethodSet = true;
        return this;
    }

    @Override
    public android.net.http.UrlRequest.Builder addHeader(String header, String value) {
        mutate(
                builder -> builder.addHeader(header, value),
                wrapper -> wrapper.addHeader(header, value));
        return this;
    }

    @Override
    public android.net.http.UrlRequest.Builder setCacheDisabled(boolean disableCache) {
        mCacheDisabled = disableCache;
        return this;
    }

    @Override
    public android.net.http.UrlRequest.Builder setPriority(int priority) {
        mutate(builder -> builder.setPriority(priority), wrapper -> wrapper.setPriority(priority));
        return this;
    }

    @NonNull
    @Override
    public android.net.http.UrlRequest.Builder setUploadDataProvider(
            @NonNull android.net.http.UploadDataProvider provider, @NonNull Executor executor) {
        mutate(
                builder ->
                        builder.setUploadDataProvider(
                                new UploadDataProviderWrapper(provider), executor),
                null);
        if (!mHttpMethodSet) {
            setHttpMethod("POST");
        }
        return this;
    }

    @NonNull
    @Override
    public android.net.http.UrlRequest.Builder setDirectExecutorAllowed(
            boolean allowDirectExecutor) {
        mDirectExecutorAllowed = allowDirectExecutor;
        return this;
    }

    @NonNull
    @Override
    public android.net.http.UrlRequest.Builder bindToNetwork(Network network) {
        mutate(
                builder ->
                        builder.bindToNetwork(
                                network != null
                                        ? network.getNetworkHandle()
                                        : org.chromium.net.ExperimentalCronetEngine
                                                .UNBIND_NETWORK_HANDLE),
                null);
        return this;
    }

    @NonNull
    @Override
    public android.net.http.UrlRequest.Builder setTrafficStatsUid(int uid) {
        mutate(
                builder -> builder.setTrafficStatsUid(uid),
                wrapper -> wrapper.setTrafficStatsUid(uid));
        return this;
    }

    @NonNull
    @Override
    public android.net.http.UrlRequest.Builder setTrafficStatsTag(int tag) {
        mutate(
                builder -> builder.setTrafficStatsTag(tag),
                wrapper -> wrapper.setTrafficStatsTag(tag));
        return this;
    }

    @Override
    public android.net.http.UrlRequest build() {
        // We're doing late initialization here because it's required that each
        // UrlRequestWrapper maps to exactly one UrlRequestCallbackWrapper so we can
        // maintain the information that we can't fetch from CronetUrlRequest inside
        // the UrlRequestWrapper and passing that wrapper to the callback. So now there's
        // a 1:1 relationship between each UrlRequestWrapper and UrlRequestCallbackWrapper.
        // The drawback here is that the user does not save any performance when they re-use the
        // builders as we have to store the data and copy it over during build().
        maybeCreateBackendAndApplyMutator();
        if (mCacheDisabled) {
            mBackend.disableCache();
        }
        if (mDirectExecutorAllowed) {
            mBackend.allowDirectExecutor();
        }
        mBuilderMutator.accept(mBackend);
        var requestWrapper =
                new UrlRequestWrapper(mBackend.build(), mCacheDisabled, mDirectExecutorAllowed);
        mWrapperMutator.accept(requestWrapper);
        mCallbackWrapper.setOriginalRequestWrapper(requestWrapper);
        return requestWrapper;
    }
}
