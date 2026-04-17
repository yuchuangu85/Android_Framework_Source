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

package android.app.contentrestriction;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.app.Service;
import android.app.contentrestriction.flags.Flags;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Base class for a service that the
 * {@code android.app.role.RoleManager.ROLE_CONTENT_RESTRICTION} role holder must implement.
 */
@FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
public class ContentRestrictionAppService extends Service {
    private static final String TAG = "ContentRestrictionAppService";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Service action: Action for a service that the {@code
     * android.app.role.RoleManager.ROLE_CONTENT_RESTRICTION} role holder must implement.
     */
    @SuppressLint("ActionValue")
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_CONTENT_RESTRICTION_APP_SERVICE =
            "android.app.action.CONTENT_RESTRICTION_SERVICE";

    private final IContentRestrictionAppService mBinder = new IContentRestrictionAppService.Stub() {
        @Override
        public void onContentRestrictionEnabled(boolean enabled) {
            mHandler.post(() -> {
                if (enabled) {
                    ContentRestrictionAppService.this.onContentRestrictionEnabled();
                } else {
                    ContentRestrictionAppService.this.onContentRestrictionDisabled();
                }
            });
        }

        @Override
        public void onClassifyContent(ClassifiableContent content,
                IContentRestrictionCallback callback) {
            try {
                ContentClassificationResult result =
                        ContentRestrictionAppService.this.onClassifyContent(content);
                if (result == null) {
                    callback.onResult(false);
                } else {
                    callback.onResult(result.isAllowed());
                }
            } catch (Exception e) {
                Slog.e(TAG, "Implementation of onClassifyContent crashed.", e);
                try {
                    callback.onResult(false);
                } catch (RemoteException re) {
                    Slog.e(TAG, "System service died", re);
                }
            }
        }
    };

    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return mBinder.asBinder();
    }

    /**
     * Called when content restriction is enabled.
     *
     * <p>This is called on the main thread.
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public void onContentRestrictionEnabled() {}

    /**
     * Called when content restriction is disabled.
     *
     * <p>This is called on the main thread.
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public void onContentRestrictionDisabled() {}

    /**
     * Called when content needs to be classified.
     *
     * <p>This is called on a background thread.
     *
     * @param content the content to be classified
     * @return the content classification result
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    @Nullable
    public ContentClassificationResult onClassifyContent(@NonNull ClassifiableContent content) {
        return null;
    }
}
