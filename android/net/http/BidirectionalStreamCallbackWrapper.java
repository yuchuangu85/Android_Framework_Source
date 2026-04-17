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

import org.chromium.net.CronetException;

import java.nio.ByteBuffer;

class BidirectionalStreamCallbackWrapper
        extends org.chromium.net.BidirectionalStream.Callback {

    private final android.net.http.BidirectionalStream.Callback backend;
    // Holds a reference to the original wrapper which wraps the bidirectionalStream. This is
    // needed because we have to support HttpEngine APIs which are not available in Cronet.
    // There's an assumption here that the values will not change during the lifetime of a request
    // which makes it safe to use the initial values stored in the originalWrapper.
    private BidirectionalStreamWrapper mOriginalStreamWrapper;

    public BidirectionalStreamCallbackWrapper(
            android.net.http.BidirectionalStream.Callback backend) {
        this.backend = backend;
    }

    public void setOriginalStreamWrapper(BidirectionalStreamWrapper wrapper) {
        this.mOriginalStreamWrapper = wrapper;
    }

    // Note: one could argue that this is unnecessary, and we could simply pass
    // mOriginalStreamWrapper to the user callbacks - this would make the code simpler and more
    // efficient. However, that would also create a subtle change in behavior: indeed it would mean
    // that the user callback always receives the same object on every call,
    // whereas currently it is a different object on every call. This introduces the risk that a
    // user may rely on the object always being the same (e.g. using it as a key in a map),
    // which may prevent their code from running correctly against older versions of HttpEngine.
    public BidirectionalStreamWrapper createWrapperFromStream(
            org.chromium.net.BidirectionalStream stream) {
        return new BidirectionalStreamWrapper(
                stream,
                mOriginalStreamWrapper.getHttpMethod(),
                mOriginalStreamWrapper.hasTrafficStatsTag(),
                mOriginalStreamWrapper.hasTrafficStatsTag()
                        ? mOriginalStreamWrapper.getTrafficStatsTag()
                        : 0,
                mOriginalStreamWrapper.hasTrafficStatsUid(),
                mOriginalStreamWrapper.hasTrafficStatsUid()
                        ? mOriginalStreamWrapper.getTrafficStatsUid()
                        : 0,
                mOriginalStreamWrapper.getPriority(),
                mOriginalStreamWrapper.getHeaders(),
                mOriginalStreamWrapper.isDelayRequestHeadersUntilFirstFlushEnabled());
    }

    @Override
    public void onStreamReady(org.chromium.net.BidirectionalStream stream) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        backend.onStreamReady(wrappedStream);
    }

    @Override
    public void onResponseHeadersReceived(
            org.chromium.net.BidirectionalStream stream, org.chromium.net.UrlResponseInfo info) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onResponseHeadersReceived(wrappedStream, wrappedInfo);
    }

    @Override
    public void onReadCompleted(
            org.chromium.net.BidirectionalStream stream,
            org.chromium.net.UrlResponseInfo info,
            ByteBuffer byteBuffer,
            boolean endOfStream) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onReadCompleted(wrappedStream, wrappedInfo, byteBuffer, endOfStream);
    }

    @Override
    public void onWriteCompleted(
            org.chromium.net.BidirectionalStream stream,
            org.chromium.net.UrlResponseInfo info,
            ByteBuffer byteBuffer,
            boolean endOfStream) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onWriteCompleted(wrappedStream, wrappedInfo, byteBuffer, endOfStream);
    }

    @Override
    public void onResponseTrailersReceived(
            org.chromium.net.BidirectionalStream stream,
            org.chromium.net.UrlResponseInfo info,
            org.chromium.net.UrlResponseInfo.HeaderBlock headers) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        HeaderBlockWrapper wrappedHeaders = new HeaderBlockWrapper(headers);
        backend.onResponseTrailersReceived(wrappedStream, wrappedInfo, wrappedHeaders);
    }

    @Override
    public void onSucceeded(
            org.chromium.net.BidirectionalStream stream, org.chromium.net.UrlResponseInfo info) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onSucceeded(wrappedStream, wrappedInfo);
    }

    @Override
    public void onFailed(
            org.chromium.net.BidirectionalStream stream,
            org.chromium.net.UrlResponseInfo info,
            CronetException e) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        HttpException wrappedException = CronetExceptionTranslationUtils.translateException(e);
        backend.onFailed(wrappedStream, wrappedInfo, wrappedException);
    }

    @Override
    public void onCanceled(
            org.chromium.net.BidirectionalStream stream, org.chromium.net.UrlResponseInfo info) {
        BidirectionalStreamWrapper wrappedStream = createWrapperFromStream(stream);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onCanceled(wrappedStream, wrappedInfo);
    }
}
