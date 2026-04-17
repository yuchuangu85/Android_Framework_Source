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
package android.health.connect.datatypes;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines the sensitivity levels for Health Connect record types used by the Device Data Provider
 * (DDP) Capabilities API.
 *
 * <p>The Capabilities API allows developers to determine if a specific data type is being provided
 * to Health Connect by a device (e.g., a wearable or smart scale) before requesting permissions.
 * However, to protect user privacy, certain data types are classified as {@link #SENSITIVE}.
 *
 * <p>If a data type is deemed {@link #SENSITIVE}, it is not exposed via the Capabilities API. This
 * prevents applications from inferring health conditions based solely on the availability of
 * specific hardware (e.g., inferring diabetes from the presence of a Blood Glucose monitor) without
 * holding the necessary permissions.
 *
 * @hide
 */
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
public final class RecordTypeSensitivity {

    /**
     * Indicates that the record type is sensitive.
     *
     * <p>Data types with this classification are <strong>not</strong> exposed via the Capabilities
     * API. The developer must request permission blindly to discover if this data is available.
     *
     * <p>This classification is used when the presence of a data source could allow an app to infer
     * specific health conditions or highly personal goals.
     *
     * <p>Examples include:
     *
     * <ul>
     *   <li>Blood Glucose (indicative of ongoing conditions like diabetes)
     *   <li>Basal Body Temperature (indicative of fertility tracking/pregnancy)
     *   <li>Ovulation Test (indicative of pregnancy)
     * </ul>
     */
    public static final int SENSITIVE = 0;

    /**
     * Indicates that the record type is not sensitive (insensitive).
     *
     * <p>Data types with this classification <strong>are</strong> exposed via the Capabilities API,
     * allowing developers to make informed decisions before requesting permissions.
     *
     * <p>This classification applies to general fitness data, or health metrics where owning a
     * device does not strongly imply a specific diagnosis.
     *
     * <p>Examples include:
     *
     * <ul>
     *   <li>Steps, Distance, and Sleep (standard activity/wellness tracking)
     *   <li>Blood Pressure (general health monitoring)
     *   <li>Body Weight and Composition
     * </ul>
     */
    public static final int INSENSITIVE = 1;

    private RecordTypeSensitivity() {}

    /** @hide */
    @IntDef({SENSITIVE, INSENSITIVE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Sensitivity {}
}
