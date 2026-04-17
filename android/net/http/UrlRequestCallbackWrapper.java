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

@SuppressWarnings("Override")
class UrlRequestCallbackWrapper extends org.chromium.net.UrlRequest.Callback {

    private final android.net.http.UrlRequest.Callback backend;
    private UrlRequestWrapper mOriginalWrapper;

    public UrlRequestCallbackWrapper(android.net.http.UrlRequest.Callback backend) {
        this.backend = backend;
    }

    public void setOriginalRequestWrapper(UrlRequestWrapper wrapper) {
        mOriginalWrapper = wrapper;
    }

    // Note: one could argue that this is unnecessary, and we could simply pass
    // mOriginalStreamWrapper to the user callbacks - this would make the code simpler and more
    // efficient. However, that would also create a subtle change in behavior: indeed it would mean
    // that the user callback always receives the same object on every call,
    // whereas currently it is a different object on every call. This introduces the risk that a
    // user may rely on the object always being the same (e.g. using it as a key in a map),
    // which may prevent their code from running correctly against older versions of HttpEngine.
    private UrlRequestWrapper createWrapperFromRequest(org.chromium.net.UrlRequest request) {
        return new UrlRequestWrapper(
                request,
                mOriginalWrapper.getHttpMethod(),
                mOriginalWrapper.hasTrafficStatsTag(),
                mOriginalWrapper.hasTrafficStatsTag() ? mOriginalWrapper.getTrafficStatsTag() : 0,
                mOriginalWrapper.hasTrafficStatsUid(),
                mOriginalWrapper.hasTrafficStatsUid() ? mOriginalWrapper.getTrafficStatsUid() : 0,
                mOriginalWrapper.getPriority(),
                mOriginalWrapper.getHeaders(),
                mOriginalWrapper.isCacheDisabled(),
                mOriginalWrapper.isDirectExecutorAllowed());
    }

    @Override
    public void onRedirectReceived(
            org.chromium.net.UrlRequest request,
            org.chromium.net.UrlResponseInfo info,
            String newLocationUrl)
            throws Exception {
        UrlRequestWrapper wrappedRequest = createWrapperFromRequest(request);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onRedirectReceived(wrappedRequest, wrappedInfo, newLocationUrl);
    }

    @Override
    public void onResponseStarted(
            org.chromium.net.UrlRequest request, org.chromium.net.UrlResponseInfo info)
            throws Exception {
        UrlRequestWrapper wrappedRequest = createWrapperFromRequest(request);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onResponseStarted(wrappedRequest, wrappedInfo);
    }

    @Override
    public void onReadCompleted(
            org.chromium.net.UrlRequest request,
            org.chromium.net.UrlResponseInfo info,
            ByteBuffer buffer)
            throws Exception {
        UrlRequestWrapper wrappedRequest = createWrapperFromRequest(request);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onReadCompleted(wrappedRequest, wrappedInfo, buffer);
    }

    @Override
    public void onSucceeded(
            org.chromium.net.UrlRequest request, org.chromium.net.UrlResponseInfo info) {
        UrlRequestWrapper wrappedRequest = createWrapperFromRequest(request);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onSucceeded(wrappedRequest, wrappedInfo);
    }

    @Override
    public void onFailed(
            org.chromium.net.UrlRequest request,
            org.chromium.net.UrlResponseInfo info,
            CronetException e) {
        UrlRequestWrapper wrappedRequest = createWrapperFromRequest(request);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        HttpException translatedException = CronetExceptionTranslationUtils.translateException(e);
        backend.onFailed(wrappedRequest, wrappedInfo, translatedException);
    }

    @Override
    public void onCanceled(
            org.chromium.net.UrlRequest request, org.chromium.net.UrlResponseInfo info) {
        UrlRequestWrapper wrappedRequest = createWrapperFromRequest(request);
        UrlResponseInfoWrapper wrappedInfo = new UrlResponseInfoWrapper(info);
        backend.onCanceled(wrappedRequest, wrappedInfo);
    }
}
