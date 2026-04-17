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

class BidirectionalStreamWrapper extends android.net.http.BidirectionalStream {

    private final org.chromium.net.BidirectionalStream backend;
    private String mHttpMethod = "POST";
    private int mPriority = android.net.http.BidirectionalStream.STREAM_PRIORITY_MEDIUM;
    private boolean mHasTrafficStatsTag;
    private int mTrafficStatsTag;
    private boolean mHasTrafficStatsUid;
    private int mTrafficStatsUid;
    private boolean mDelayRequestHeadersUntilFirstFlush;
    private final List<Map.Entry<String, String>> mHeadersList;
    private android.net.http.HeaderBlock mHeaders;

    BidirectionalStreamWrapper(org.chromium.net.BidirectionalStream backend) {
        this.backend = backend;
        this.mHeadersList = new ArrayList<>();
        this.mHeaders = new HeaderBlockImpl(mHeadersList);
    }

    BidirectionalStreamWrapper(
            org.chromium.net.BidirectionalStream backend,
            String httpMethod,
            boolean hasTrafficStatsTag,
            int trafficStatsTag,
            boolean hasTrafficStatsUid,
            int trafficStatsUid,
            int priority,
            android.net.http.HeaderBlock headers,
            boolean delayRequestHeadersUntilFirstFlush) {
        this(backend);
        this.mHasTrafficStatsTag = hasTrafficStatsTag;
        this.mHasTrafficStatsUid = hasTrafficStatsUid;
        this.mHttpMethod = httpMethod;
        this.mTrafficStatsTag = trafficStatsTag;
        this.mTrafficStatsUid = trafficStatsUid;
        this.mPriority = priority;
        this.mHeaders = headers;
        this.mDelayRequestHeadersUntilFirstFlush = delayRequestHeadersUntilFirstFlush;
    }

    @Override
    public void start() {
        backend.start();
    }

    @Override
    public void read(ByteBuffer buffer) {
        backend.read(buffer);
    }

    @Override
    public void write(ByteBuffer buffer, boolean endOfStream) {
        backend.write(buffer, endOfStream);
    }

    @Override
    public void flush() {
        backend.flush();
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
    public boolean isDelayRequestHeadersUntilFirstFlushEnabled() {
        return mDelayRequestHeadersUntilFirstFlush;
    }

    @Override
    public int getPriority() {
        return mPriority;
    }

    @Override
    public String getHttpMethod() {
        return mHttpMethod;
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

    @Override
    public android.net.http.HeaderBlock getHeaders() {
        return mHeaders;
    }

    void setHttpMethod(String httpMethod) {
        mHttpMethod = httpMethod;
    }

    void setTrafficStatsTag(int trafficStatsTag) {
        mHasTrafficStatsTag = true;
        mTrafficStatsTag = trafficStatsTag;
    }

    void setTrafficStatsUid(int trafficStatsUid) {
        mHasTrafficStatsUid = true;
        mTrafficStatsUid = trafficStatsUid;
    }

    void setPriority(int priority) {
        mPriority = priority;
    }

    void setDelayRequestHeadersUntilFirstFlushEnabled(
            boolean delayRequestHeadersUntilFirstFlushEnabled) {
        mDelayRequestHeadersUntilFirstFlush = delayRequestHeadersUntilFirstFlushEnabled;
    }

    void addHeader(String header, String value) {
        mHeadersList.add(new AbstractMap.SimpleImmutableEntry<>(header, value));
    }
}
