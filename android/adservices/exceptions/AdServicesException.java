/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.adservices.exceptions;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;

import com.android.adservices.flags.Flags;

/**
 * Exception thrown by AdServices.
 *
 * @deprecated The Rubidium (Rb) Measurement APIs, including those in
 *     android.adservices.measurement, are being deprecated. There are no direct replacement APIs
 *     for the Measurement APIs. Developers currently using these APIs should cease integration, as
 *     calls to these APIs will be rejected in upcoming Android releases as part of a soft removal
 *     process. Please refer to the official Privacy Sandbox developer documentation and
 *     announcements for more details on this deprecation and the future roadmap of Privacy Sandbox
 *     on Android: https://privacysandbox.com/news/update-on-plans-for-privacy-sandbox-technologies/
 */
@Deprecated
@FlaggedApi(Flags.FLAG_ADSERVICES_DEPRECATED)
public class AdServicesException extends Exception {
    public AdServicesException(@Nullable String message, @Nullable Throwable e) {
        super(message, e);
    }
    public AdServicesException(@Nullable String message) {
        super(message);
    }

    /** @hide */
    public AdServicesException() {
        super();
    }
}