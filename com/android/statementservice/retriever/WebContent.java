/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice.retriever;

/**
 * An immutable value type representing the response from a web server.
 *
 * Visible for testing.
 *
 * @hide
 */
public final class WebContent {

    private final String mContent;
    private final Long mExpireTimeMillis;
    private final int mResponseCode;

    public WebContent(String content, Long expireTimeMillis, int responseCode) {
        mContent = content;
        mExpireTimeMillis = expireTimeMillis;
        mResponseCode = responseCode;
    }

    /**
     * Returns the expiration time of the content as specified in the HTTP header.
     */
    public Long getExpireTimeMillis() {
        return mExpireTimeMillis;
    }

    /**
     * Returns content of the HTTP message body.
     */
    public String getContent() {
        return mContent;
    }

    public int getResponseCode() {
        return mResponseCode;
    }
}
