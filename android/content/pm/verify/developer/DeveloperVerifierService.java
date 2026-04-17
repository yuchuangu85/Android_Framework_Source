/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.verify.developer;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.Flags;
import android.content.pm.PackageManager;
import android.os.IBinder;

/**
 * A base service implementation for the developer verifier agent to implement.
 * <p></p>
 * The developer verifier agent app should register the implemented {@link DeveloperVerifierService}
 * in the manifest.
 * Example:
 * <pre>{@code
 *     <service android:name=".MyVerifierService"
 *         permission="android.permission.BIND_DEVELOPER_VERIFICATION_AGENT">
 *       <intent-filter>
 *         <action android:name="android.content.pm.action.VERIFY_DEVELOPER" />
 *       </intent-filter>
 *     </service>
 * }</pre>
 * <p></p>
 * Notice that the developer verifier agent app should also declare
 * {@code android:forceQueryable="true"} to make itself visible to the installers.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE)
public abstract class DeveloperVerifierService extends Service {
    /**
     * Called when a package name is available for a pending verification,
     * giving the verifier opportunity to pre-fetch any relevant information
     * that may be needed should a verification for the package be required.
     */
    public abstract void onPackageNameAvailable(@NonNull String packageName);

    /**
     * Called when a package recently provided via {@link #onPackageNameAvailable}
     * is no longer expected to be installed.
     * <p>This is a hint that any pre-fetch or
     * cache created as a result of the previous call may be be cleared.</p>
     * <p>This method will never be called after {@link #onVerificationRequired} is called for the
     * same package. Once a verification is officially requested by
     * {@link #onVerificationRequired}, it cannot be cancelled.
     * </p>
     */
    public abstract void onVerificationCancelled(@NonNull String packageName);

    /**
     * Called when an application needs to be verified.
     * <p>Details about the
     * verification and actions that can be taken on it will be encapsulated in
     * the provided {@link DeveloperVerificationSession} parameter.</p>
     */
    public abstract void onVerificationRequired(@NonNull DeveloperVerificationSession session);

    /**
     * Called when a verification needs to be retried.
     * <p>This can be encountered
     * when a prior verification was marked incomplete and the user has indicated
     * that they've resolved the issue, or when a timeout is reached, but the
     * the system is attempting to retry.
     * </p>
     * <p>Details about the
     * verification and actions that can be taken on it will be encapsulated in
     * the provided {@link DeveloperVerificationSession} parameter.</p>
     */
    public abstract void onVerificationRetry(@NonNull DeveloperVerificationSession session);

    /**
     * Called in the case that an active verification has failed because of the timeout.
     */
    public abstract void onVerificationTimeout(int verificationId);

    /**
     * Called when the verifier service is bound to the system.
     */
    public @Nullable IBinder onBind(@Nullable Intent intent) {
        if (intent == null || !PackageManager.ACTION_VERIFY_DEVELOPER.equals(intent.getAction())) {
            return null;
        }
        return new IDeveloperVerifierService.Stub() {
            @Override
            public void onPackageNameAvailable(@NonNull String packageName) {
                DeveloperVerifierService.this.onPackageNameAvailable(packageName);
            }

            @Override
            public void onVerificationCancelled(@NonNull String packageName) {
                DeveloperVerifierService.this.onVerificationCancelled(packageName);
            }

            @Override
            public void onVerificationRequired(@NonNull DeveloperVerificationSession session) {
                DeveloperVerifierService.this.onVerificationRequired(session);
            }

            @Override
            public void onVerificationRetry(@NonNull DeveloperVerificationSession session) {
                DeveloperVerifierService.this.onVerificationRetry(session);
            }

            @Override
            public void onVerificationTimeout(int verificationId) {
                DeveloperVerifierService.this.onVerificationTimeout(verificationId);
            }
        };
    }
}
