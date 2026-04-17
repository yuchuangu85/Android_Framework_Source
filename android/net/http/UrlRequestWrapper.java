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

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class UrlRequestWrapper extends android.net.http.UrlRequest {

    private final org.chromium.net.UrlRequest backend;
    private String mHttpMethod = "GET";
    private int mPriority = android.net.http.UrlRequest.REQUEST_PRIORITY_MEDIUM;
    private boolean mHasTrafficStatsTag;
    private int mTrafficStatsTag;
    private boolean mHasTrafficStatsUid;
    private int mTrafficStatsUid;
    private final boolean mCacheDisabled;
    private final boolean mDirectExecutorAllowed;
    private final List<Map.Entry<String, String>> mHeadersList;
    private android.net.http.HeaderBlock mHeaders;

    public UrlRequestWrapper(
            org.chromium.net.UrlRequest backend,
            boolean cacheDisabled,
            boolean directExecutorAllowed) {
        this.backend = backend;
        this.mCacheDisabled = cacheDisabled;
        this.mDirectExecutorAllowed = directExecutorAllowed;
        this.mHeadersList = new ArrayList<>();
        this.mHeaders = new HeaderBlockImpl(mHeadersList);
    }

    public UrlRequestWrapper(
            org.chromium.net.UrlRequest backend,
            String httpMethod,
            boolean hasTrafficStatsTag,
            int trafficStatsTag,
            boolean hasTrafficStatsUid,
            int trafficStatsUid,
            int priority,
            HeaderBlock headerBlock,
            boolean cacheDisabled,
            boolean directExecutorAllowed) {
        this(backend, cacheDisabled, directExecutorAllowed);
        this.mHttpMethod = httpMethod;
        this.mHasTrafficStatsTag = hasTrafficStatsTag;
        this.mHasTrafficStatsUid = hasTrafficStatsUid;
        this.mTrafficStatsTag = trafficStatsTag;
        this.mTrafficStatsUid = trafficStatsUid;
        this.mHeaders = headerBlock;
        this.mPriority = priority;
    }

    @Override
    public void start() {
        backend.start();
    }

    @Override
    public void followRedirect() {
        backend.followRedirect();
    }

    @Override
    public void read(ByteBuffer buffer) {
        backend.read(buffer);
    }

    @Override
    public void cancel() {
        backend.cancel();
    }

    @Override
    public boolean isDone() {
        return backend.isDone();
    }

    @Override
    public void getStatus(android.net.http.UrlRequest.StatusListener listener) {
        backend.getStatus(new UrlRequestStatusListenerWrapper(listener));
    }

    @Override
    public String getHttpMethod() {
        return mHttpMethod;
    }

    @Override
    public android.net.http.HeaderBlock getHeaders() {
        return mHeaders;
    }

    @Override
    public boolean isCacheDisabled() {
        return mCacheDisabled;
    }

    @Override
    public boolean isDirectExecutorAllowed() {
        return mDirectExecutorAllowed;
    }

    @Override
    public int getPriority() {
        return mPriority;
    }

    @Override
    public boolean hasTrafficStatsTag() {
        return mHasTrafficStatsTag;
    }

    @Override
    public int getTrafficStatsTag() {
        if (!hasTrafficStatsTag()) {
            throw new IllegalStateException("TrafficStatsTag is not set");
        }
        return mTrafficStatsTag;
    }

    @Override
    public boolean hasTrafficStatsUid() {
        return mHasTrafficStatsUid;
    }

    @Override
    public int getTrafficStatsUid() {
        if (!hasTrafficStatsUid()) {
            throw new IllegalStateException("TrafficStatsUid is not set");
        }
        return mTrafficStatsUid;
    }

    void setHttpMethod(String httpMethod) {
        mHttpMethod = httpMethod;
    }

    void setPriority(int priority) {
        mPriority = priority;
    }

    void setTrafficStatsTag(int trafficStatsTag) {
        mHasTrafficStatsTag = true;
        mTrafficStatsTag = trafficStatsTag;
    }

    void setTrafficStatsUid(int trafficStatsUid) {
        mHasTrafficStatsUid = true;
        mTrafficStatsUid = trafficStatsUid;
    }

    void addHeader(String header, String value) {
        mHeadersList.add(new AbstractMap.SimpleImmutableEntry<>(header, value));
    }
}
