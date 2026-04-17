/*
 * Copyright 2025 The Android Open Source Project
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

package android.media.projection;

import android.annotation.EnforcePermission;
import android.annotation.FlaggedApi;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PermissionEnforcer;
import android.os.RemoteCallback;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.media.projection.flags.Flags;

import java.util.List;

/**
 * Service to be implemented by the application providing the {@link MediaProjectionAppContent}.
 *
 * <p>
 * To receive media projection callbacks related to app content sharing, this service must:
 * <ol>
 *     <li> be declared with an intent-filter action
 *     {@value AppContentProjectionService#SERVICE_INTERFACE}
 *     <li> set {@code android:exported="true"}
 *     <li> be protected by {@value android.Manifest.permission#MANAGE_MEDIA_PROJECTION}
 *     <li> set
 *     {@link MediaProjectionConfig.Builder#setOwnAppContentProvided(boolean)} to
 *     {@code true}
 * </ol>
 * <p>
 * <pre>
 *  &lt;/application>
 *   ...
 *    &lt;service
 *         android:exported="true"
 *         android:name="com.example.AppContentSharingService"
 *         android:permission="android.permission.MANAGE_MEDIA_PROJECTION">
 *       &lt;intent-filter>
 *         &lt;action android:name="android.media.projection.AppContentProjectionService"/>
 *       &lt;/intent-filter>
 *     &lt;/service>
 *   &lt;/application>
 * </pre>
 * <p>
 * Only holders of {@value android.Manifest.permission#MANAGE_MEDIA_PROJECTION} are allowed to call
 * these callbacks.
 */
@FlaggedApi(Flags.FLAG_APP_CONTENT_SHARING)
public abstract class AppContentProjectionService extends Service {

    /**
     * The {@link Intent} action that must be declared as handled by the service.
     * Put this in your manifest to reply to requests for app content projection.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.media.projection.AppContentProjectionService";
    /** @hide **/
    public static final String EXTRA_APP_CONTENT = "extra_app_content";

    private AppContentProjectionCallbackInternal mService;

    private class AppContentProjectionCallbackInternal extends
            IAppContentProjectionCallback.Stub {

        private AppContentProjectionSession mSession;

        private AppContentProjectionCallbackInternal(PermissionEnforcer permissionEnforcer) {
            super(permissionEnforcer);
        }

        @Override
        @EnforcePermission(allOf = {"MANAGE_MEDIA_PROJECTION"})
        public void onContentRequest(RemoteCallback newContentConsumer, int thumbnailWidth,
                int thumbnailHeight, int iconWidth, int iconHeight) {
            onContentRequest_enforcePermission();
            Size thumbnailSize = new Size(thumbnailWidth, thumbnailHeight);
            Size iconSize = new Size(iconWidth, iconHeight);
            AppContentProjectionService.this.onContentRequest(
                    new AppContentRequest(thumbnailSize,
                            iconSize, mediaProjectionAppContents -> {
                        for (int i = 0; i < mediaProjectionAppContents.length; i++) {
                            mediaProjectionAppContents[i].optimizeResources(thumbnailSize,
                                    iconSize);
                        }
                        Bundle bundle = new Bundle();
                        bundle.putParcelableArray(EXTRA_APP_CONTENT,
                                mediaProjectionAppContents);
                        newContentConsumer.sendResult(bundle);
                    }));
        }

        @Override
        @EnforcePermission(allOf = {"MANAGE_MEDIA_PROJECTION"})
        public void onLoopbackProjectionStarted(IAppContentProjectionSession session,
                int contentId, boolean isAudioRequested) {
            onLoopbackProjectionStarted_enforcePermission();
            if (mSession != null) {
                throw new IllegalStateException(
                        "Only one single AppContentProjectionSession is supported");
            }
            mSession = new AppContentProjectionSession(session, isAudioRequested);
            AppContentProjectionService.this.onLoopbackProjectionStarted(
                    mSession, contentId
            );
        }

        @Override
        @EnforcePermission(allOf = {"MANAGE_MEDIA_PROJECTION"})
        public void onSessionStopped() {
            onSessionStopped_enforcePermission();
            AppContentProjectionService.this.onSessionStopped(mSession);
            mSession = null;
        }

        @Override
        @EnforcePermission(allOf = {"MANAGE_MEDIA_PROJECTION"})
        public void onContentRequestCanceled() {
            onContentRequestCanceled_enforcePermission();
            AppContentProjectionService.this.onContentRequestCanceled();
        }
    }

    @NonNull
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        if (mService == null) {
            mService = new AppContentProjectionCallbackInternal(new PermissionEnforcer(this));
        }
        return mService;
    }

    /**
     * Called when the picker UI has been shown to the user.
     * <p>
     * App content should be returned by calling {@link AppContentRequest#provideContent(List)}
     * <p>
     * If the user picks one of the offered app content,
     * {@link #onLoopbackProjectionStarted(AppContentProjectionSession, int)} will be called with
     * the id corresponding to the chosen content. See {@link MediaProjectionAppContent} for more
     * information about the id.
     * @param request the request instance containing the characteristics of the content requested
     */
    public abstract void onContentRequest(@NonNull AppContentRequest request);

    /**
     * Called when the user picked a content to be shared within the requesting app.
     *
     * <p>This can be called multiple times if the user picks a different content to
     * be shared at a later point.
     *
     * <p> {@code contentId} is the id that has been provided by this application during the
     * {@link #onContentRequest(AppContentRequest)} call. See
     * {@link MediaProjectionAppContent} for more information about the id.
     *
     * @param session the session started for sharing the selected {@link MediaProjectionAppContent}
     * @param contentId the id of the selected {@link MediaProjectionAppContent}
     *
     * @return {@code true} if the request has been fulfilled, {@code false} otherwise
     */
    public abstract boolean onLoopbackProjectionStarted(
            @NonNull AppContentProjectionSession session, int contentId);

    /**
     * Called when the sharing session has been ended by the user or the system. The shared
     * resources can be discarded.
     *
     * <p> The provided session object is the same as the one provided when
     * {@link #onLoopbackProjectionStarted(AppContentProjectionSession, int)} was called. It can
     * be used to identify the session that is being terminated.
     *
     * <p> Note that if the session is terminated by this service by calling
     * {@link AppContentProjectionSession#notifySessionStop()}, this callback won't be called.
     *
     * @param session the same session object that was passed in
     * {@link #onLoopbackProjectionStarted(AppContentProjectionSession, int)}
     */
    public abstract void onSessionStopped(@NonNull AppContentProjectionSession session);

    /**
     * Called when the user didn't pick some app content to be shared. This can happen if the
     * projection request was canceled, or the user picked another source (e.g. display, whole app).
     * <p>
     * Any resources created for sharing app content, such as thumbnails, can be discarded at this
     * point.
     */
    public abstract void onContentRequestCanceled();

}
