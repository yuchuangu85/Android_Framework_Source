/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.service.contentsafety;

import static android.app.contentsafety.flags.Flags.FLAG_ENABLE_CONTENTSAFETY;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@FlaggedApi(FLAG_ENABLE_CONTENTSAFETY)
class BundleUtil {

    private static final String TAG = "UtilHelper";

    private BundleUtil() {}
    /**
     * Convert feature file packed into bundle to a well defined map.
     * The feature file is retrieved from {@link ContentSafetyService#onGetFeature}, and sent
     * to {@link ContentSafetySandboxedService#onLoadFeature}.
     * @param input feature file stored in Bundle.
     * @return a Map where keys are of type string, and values are ParcelFileDescriptor
     */
    @NonNull
    static Map<String, ParcelFileDescriptor> unpackMapFromFeatureBundle(
            @NonNull Bundle input) {
        Map<String, ParcelFileDescriptor> map = new HashMap<>();
        for (String key : input.keySet()) {
            try {
                ParcelFileDescriptor files =
                        input.getParcelable(key, ParcelFileDescriptor.class);
                if (files != null) {
                    map.putIfAbsent(key, files);
                } else {
                    Slog.w(TAG,
                            "Bundle retrieved from remote service contains invalid "
                                    + "ParcelFileDescriptor");
                }
            } catch (NumberFormatException e) {
                // ignore invalid key
                Slog.w(TAG, "Bundle retrieved from remote service contains invalid keys");
            }
        }
        return map;
    }

    /**
     * Pack the given Map into a bundle. this is for parsing feature file returned by
     * {@link ContentSafetyService#onGetFeature}.
     * @param input Map where keys are string and values are ParcelFileDescriptor.
     */
    @NonNull
    static  Bundle packMapIntoFeatureBundle(
            @NonNull Map<String, ParcelFileDescriptor> input) {
        Bundle bundle = new Bundle();
        for (String key: input.keySet()) {
            bundle.putParcelable(key, input.get(key));
        }
        return bundle;
    }

    /**
     * Converts Bundle to a map to convert the input retrieved from the API
     * {@link android.app.contentsafety.ContentSafetyManager#checkContent} to be passed to
     * {@link ContentSafetySandboxedService#onCheckContent}.
     * @param input a bundle contains the payload content that needs to be processed bu
     *              {@link ContentSafetySandboxedService#onCheckContent}.
     * @return a map matches to the expected payload input.
     */
    @NonNull
    static Map<Integer, List<ParcelFileDescriptor>> unpackMapFromPayloadBundle(
            @NonNull Bundle input) {
        Map<Integer, List<ParcelFileDescriptor>> map = new HashMap<>();
        for (String key : input.keySet()) {
            try {
                ArrayList<ParcelFileDescriptor> files =
                        input.getParcelableArrayList(key, ParcelFileDescriptor.class);
                if (files != null) {
                    map.putIfAbsent(Integer.parseInt(key),
                            new ArrayList<>(files));
                } else {
                    Slog.w(TAG,
                            "Bundle retrieved from remote service contains invalid "
                                    + "ParcelFileDescriptor ArrayList");
                }
            } catch (NumberFormatException e) {
                // ignore invalid key
                Slog.w(TAG, "Bundle retrieved from remote service contains invalid keys");
            }
        }
        return map;
    }
}
