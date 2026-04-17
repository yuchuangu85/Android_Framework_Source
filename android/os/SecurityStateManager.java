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

package android.os;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;

/**
 * SecurityStateManager provides the functionality to query the security status of the system and
 * platform components. For example, this includes the system and vendor security patch level.
 */
@FlaggedApi(Flags.FLAG_SECURITY_STATE_SERVICE)
@SystemService(Context.SECURITY_STATE_SERVICE)
public class SecurityStateManager {

    /**
     * The system SPL key returned as part of the {@code Bundle} from
     * {@code getGlobalSecurityState}.
     */
    public static final String KEY_SYSTEM_SPL = "system_spl";

    /**
     * The vendor SPL key returned as part of the {@code Bundle} from
     * {@code getGlobalSecurityState}.
     */
    public static final String KEY_VENDOR_SPL = "vendor_spl";

    /**
     * The kernel version key returned as part of the {@code Bundle} from
     * {@code getGlobalSecurityState}.
     */
    public static final String KEY_KERNEL_VERSION = "kernel_version";

    /**
     * The system supplemental patches key returned as part of the {@code Bundle} from {@code
     * getGlobalSecurityState}.
     *
     * <p>The value is a {@code String[]} of CVE IDs (e.g., "CVE-2026-12345") that are affirmed to
     * be fully mitigated in the system image, supplemental to the declared {@link #KEY_SYSTEM_SPL}.
     */
    @FlaggedApi(Flags.FLAG_SUPPLEMENTAL_SECURITY_PATCHES)
    public static final String KEY_SYSTEM_SUPPLEMENTAL_PATCHES =
            "system_supplemental_security_patches";

    /**
     * The vendor supplemental patches key returned as part of the {@code Bundle} from {@code
     * getGlobalSecurityState}.
     *
     * <p>The value is a {@code String[]} listing CVEs (e.g., "CVE-2026-12345") that are affirmed to
     * be fully mitigated in the vendor image, supplemental to the declared {@link #KEY_VENDOR_SPL}.
     */
    @FlaggedApi(Flags.FLAG_SUPPLEMENTAL_SECURITY_PATCHES)
    public static final String KEY_VENDOR_SUPPLEMENTAL_PATCHES =
            "vendor_supplemental_security_patches";

    private final ISecurityStateManager mService;

    /**
     * @hide
     */
    public SecurityStateManager(ISecurityStateManager service) {
        mService = requireNonNull(service, "missing ISecurityStateManager");
    }

    /**
     * Returns the current global security state. Each key-value pair is a mapping of a component
     * of the global security state to its current version/SPL (security patch level). For example,
     * the {@code KEY_SYSTEM_SPL} key will map to the SPL of the system as defined in
     * {@link android.os.Build.VERSION}. The bundle will also include mappings from WebView packages
     * and packages listed under config {@code config_securityStatePackages} to their respective
     * versions as defined in {@link android.content.pm.PackageInfo#versionName}.
     *
     * <p>The bundle will also include lists of CVEs that are affirmed to be patched in the source
     * code, supplemental to the declared Security Patch Level (SPL). These lists are associated
     * with {@link #KEY_SYSTEM_SUPPLEMENTAL_PATCHES} and {@link #KEY_VENDOR_SUPPLEMENTAL_PATCHES}.
     *
     * <p>The presence of a CVE in these lists indicates that the device implementation has fully
     * mitigated the vulnerability. The accuracy of this information is critical, as it is used to
     * determine the device's security posture.
     *
     * @return A {@code Bundle} that contains the global security state information as
     * string-to-string key-value pairs.
     */
    @FlaggedApi(Flags.FLAG_SECURITY_STATE_SERVICE)
    @NonNull
    public Bundle getGlobalSecurityState() {
        try {
            return mService.getGlobalSecurityState();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }
}
