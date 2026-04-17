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

import androidx.annotation.NonNull;

import org.chromium.net.ExperimentalCronetEngine;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

class BidirectionalStreamBuilderWrapper
        extends android.net.http.BidirectionalStream.Builder {

    private Consumer<org.chromium.net.BidirectionalStream.Builder> mBuilderMutator = builder -> {};
    private Consumer<android.net.http.BidirectionalStreamWrapper> mWrapperMutator = wrapper -> {};
    private android.net.http.BidirectionalStreamCallbackWrapper mCallbackWrapper;
    private org.chromium.net.BidirectionalStream.Builder mBackend;
    private final ExperimentalCronetEngine mCronetEngine;
    private final String mUrl;
    private final Executor mExecutor;
    private final android.net.http.BidirectionalStream.Callback mCallback;

    public BidirectionalStreamBuilderWrapper(
            ExperimentalCronetEngine cronetEngine,
            String url,
            Executor executor,
            android.net.http.BidirectionalStream.Callback callback) {
        this.mCronetEngine = cronetEngine;
        this.mUrl = url;
        this.mExecutor = executor;
        this.mCallback = callback;
        this.mCallbackWrapper = new BidirectionalStreamCallbackWrapper(mCallback);
        this.mBackend =
                mCronetEngine.newBidirectionalStreamBuilder(mUrl, mCallbackWrapper, mExecutor);
    }

    private void mutate(
            Consumer<org.chromium.net.BidirectionalStream.Builder> builderMutator,
            Consumer<BidirectionalStreamWrapper> wrapperMutator) {
        maybeCreateBackendAndApplyMutator();
        mBuilderMutator = mBuilderMutator.andThen(builderMutator);
        builderMutator.accept(mBackend);
        mWrapperMutator = mWrapperMutator.andThen(wrapperMutator);
    }

    @Override
    public android.net.http.BidirectionalStream.Builder setHttpMethod(String method) {
        mutate(builder -> builder.setHttpMethod(method), wrapper -> wrapper.setHttpMethod(method));
        return this;
    }

    @Override
    public android.net.http.BidirectionalStream.Builder addHeader(String header, String value) {
        mutate(
                builder -> builder.addHeader(header, value),
                wrapper -> wrapper.addHeader(header, value));
        return this;
    }

    @Override
    public android.net.http.BidirectionalStream.Builder setPriority(int priority) {
        mutate(builder -> builder.setPriority(priority), wrapper -> wrapper.setPriority(priority));
        return this;
    }

    @Override
    public android.net.http.BidirectionalStream.Builder
            setDelayRequestHeadersUntilFirstFlushEnabled(
                    boolean delayRequestHeadersUntilFirstFlush) {
        mutate(
                builder ->
                        builder.delayRequestHeadersUntilFirstFlush(
                                delayRequestHeadersUntilFirstFlush),
                wrapper ->
                        wrapper.setDelayRequestHeadersUntilFirstFlushEnabled(
                                delayRequestHeadersUntilFirstFlush));
        return this;
    }

    private void maybeCreateBackendAndApplyMutator() {
        if (mBackend != null) {
            return;
        }
        mCallbackWrapper = new BidirectionalStreamCallbackWrapper(mCallback);
        mBackend = mCronetEngine.newBidirectionalStreamBuilder(mUrl, mCallbackWrapper, mExecutor);
        mBuilderMutator.accept(mBackend);
    }

    @Override
    public android.net.http.BidirectionalStream build() {
        // We're doing late initialization here because we need to maintain a 1:1 mapping between
        // UrlRequestWrapper and UrlRequestCallbackWrapper, even if build() is called multiple times
        // on the same builder. This is so we can maintain the information that we can't fetch
        // from CronetUrlRequest inside the UrlRequestWrapper and passing that wrapper to the
        // callback. For this reason there's a 1:1 relationship between each UrlRequestWrapper and
        // UrlRequestCallbackWrapper. The drawback here is the user does not save any performance
        // when they re-use the builders as we have to store the data and copy it over during
        // build().
        maybeCreateBackendAndApplyMutator();
        var wrapper = new BidirectionalStreamWrapper(mBackend.build());
        mWrapperMutator.accept(wrapper);
        mCallbackWrapper.setOriginalStreamWrapper(wrapper);
        // Reset those two wrappers as we will re-create them once the user call any setter method
        // on this builder.
        mBackend = null;
        return wrapper;
    }

    @NonNull
    @Override
    public android.net.http.BidirectionalStream.Builder setTrafficStatsTag(int tag) {
        mutate(
                builder -> builder.setTrafficStatsTag(tag),
                wrapper -> wrapper.setTrafficStatsTag(tag));
        return this;
    }

    @NonNull
    @Override
    public android.net.http.BidirectionalStream.Builder setTrafficStatsUid(int uid) {
        mutate(
                builder -> builder.setTrafficStatsUid(uid),
                wrapper -> wrapper.setTrafficStatsUid(uid));
        return this;
    }
}
