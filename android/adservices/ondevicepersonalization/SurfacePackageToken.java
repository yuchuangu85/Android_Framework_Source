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

package android.adservices.ondevicepersonalization;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;

import com.android.adservices.ondevicepersonalization.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

/**
 * An opaque reference to content that can be displayed in a {@link android.view.SurfaceView}. This
 * maps to a {@link RenderingConfig} returned by an {@link IsolatedService}.
 *
 *  @deprecated The ODP APIs are deprecated and will not be supported in future Android releases.
 *  There is no direct replacement API available. Developers currently integrated with these APIs
 *  must cease further integration efforts. For comprehensive details regarding this deprecation and
 *  the future roadmap of Privacy Sandbox on Android, please consult the official Privacy Sandbox
 *  developer documentation and announcements:
 *  <a href="https://privacysandbox.google.com">https://privacysandbox.google.com</a>
 */
@Deprecated
@FlaggedApi(Flags.FLAG_ODP_DEPRECIATION_ENABLED)
public class SurfacePackageToken {
    @NonNull private final String mTokenString;

    /** @hide */
    public SurfacePackageToken(@NonNull String tokenString) {
        mTokenString = tokenString;
    }

    /** @hide */
    @VisibleForTesting
    @NonNull
    public String getTokenString() {
        return mTokenString;
    }
}
