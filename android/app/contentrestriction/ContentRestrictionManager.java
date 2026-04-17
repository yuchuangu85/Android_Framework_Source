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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.contentrestriction.flags.Flags;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.os.Binder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Service for handling content restrictions.
 */
@SystemService(Context.CONTENT_RESTRICTION_SERVICE)
@FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
public class ContentRestrictionManager {
    private final Context mContext;
    private final IContentRestrictionManager mService;

    /**
     * Activity Action: Launch an activity showing information about restricted content.
     * <p>
     * Apps holding the {@link android.app.role.RoleManager#ROLE_CONTENT_RESTRICTION} must
     * declare an activity that handles this action to show specific content restriction details.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_RESTRICTED_CONTENT_DETAILS =
            "android.app.contentrestriction.action.SHOW_RESTRICTED_CONTENT_DETAILS";

    /**
     * Extra for {@link #ACTION_SHOW_RESTRICTED_CONTENT_DETAILS} containing the locus ID of the
     * restricted content.
     */
    public static final String EXTRA_CONTENT_LOCUS_ID =
            "android.app.contentrestriction.extra.CONTENT_LOCUS_ID";

    /** @hide */
    public ContentRestrictionManager(
            @NonNull Context context,
            @NonNull IContentRestrictionManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Whether the content is allowed or should be blocked.
     *
     * <p>When content restrictions are enabled, this method determines if the specified content
     * is appropriate based on the user's settings.
     *
     * <p>If the content is allowed or the content restrictions settings are disabled, the result
     * provide to the callback will be {@code true}. If the content is blocked, the value will be
     * {@code false}.
     *
     * @param content the content to be classified
     * @param executor the executor on which to run the callback
     * @param callback the callback for whether the content is allowed
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public void requestClassification(
            @NonNull ClassifiableContent content,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {

        if (!isContentRestrictionEnabled()) {
            executor.execute(() -> callback.onResult(true));
            return;
        }

        try {
            mService.requestClassification(mContext.getUserId(), content,
                    new IContentRestrictionCallback.Stub() {
                @Override
                public void onResult(boolean isContentAllowed) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onResult(isContentAllowed));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }

                @Override
                public void onError(int error, String message) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        executor.execute(()
                            -> callback.onError(new IllegalStateException(message)));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether content restriction is currently enabled for the user.
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public boolean isContentRestrictionEnabled() {
        if (mService != null) {
            try {
                return mService.isContentRestrictionEnabledForUser(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns whether content restriction device policy bypassing is allowed.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    @UserHandleAware(requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public boolean isDevicePolicyBypassingEnabledForUser(@UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.isDevicePolicyBypassingEnabledForUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets whether content restriction device policy bypassing is allowed.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    @UserHandleAware(requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public void setDevicePolicyBypassingEnabledForUser(@UserIdInt int userId, boolean enabled) {
        if (mService != null) {
            try {
                mService.setDevicePolicyBypassingEnabledForUser(userId, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Creates an {@link Intent} that can be used with {@link Context#startActivity(Intent)} to
     * display a dialog about the restricted content.
     *
     * @param locusId the {@link LocusId} of the content to be restricted
     * @return the intent to display the restricted content dialog
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    @NonNull
    public Intent createContentRestrictedIntent(@NonNull LocusId locusId) {
        if (mService != null) {
            try {
                return mService.createContentRestrictedIntent(locusId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }
}
