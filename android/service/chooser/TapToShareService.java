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

package android.service.chooser;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.annotation.TestApi;
import android.service.chooser.Flags;

/**
 * An abstract service class for implementing a tap-to-share feature.
 *
 * <p>This service is designed to be implemented by a single, privileged component designated by
 * system configuration for integration with the system sharesheet. It is not intended for use by
 * general third-party applications, as the system sharesheet will only bind to the one
 * configured service.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_TO_TAP_TO_SHARE_SERVICE} permission
 * and include an intent filter with the {@link #TAP_TO_SHARE_SERVICE_INTERFACE} action. For
 * example:</p>
 * <pre>
 *     &lt;service android:name=".MyTapToShareService"
 *             android:label="&#64;string/service_name"
 *             android:permission="android.permission.BIND_TO_TAP_TO_SHARE_SERVICE">
 *         &lt;intent-filter>
 *             &lt;action android:name="android.service.chooser.TapToShareService" />
 *         &lt;/intent-filter>
 *     &lt;/service>
 * </pre>
 *
 * <p><b>Overview</b></p>
 * This service provides a bridge for Tap-To-Share implementations to integrate with the system
 * sharesheet. The primary use case is for proximity-based sharing technologies like NFC.
 * Implementations are responsible for device detection when a share session is active.
 *
 * <p><b>Implementation Guide</b></p>
 * Subclasses must implement the abstract methods {@link #onSessionStart()} and
 * {@link #onSessionEnd()} to manage the device detection lifecycle.
 *
 * <ul>
 *   <li>{@link #onSessionStart()}: This method is called when the system sharesheet binds to this
 *       service. Implementations should start device detection. For example, this is
 *       the place to enable NFC reader mode and configure it for detecting compatible peers.</li>
 *   <li>{@link #onSessionEnd()}: This method is called when the system sharesheet unbinds
 *       from this service (in {@link #onUnbind()}). Implementations should stop any active device
 *       detection and release resources. For example, this is where NFC reader mode should be
 *       disabled.</li>
 * </ul>
 *
 * <p>When a compatible device is detected via an NFC tap, the subclass must call
 * {@link #performTapToShare()} to notify the system sharesheet that a device is ready to
 * receive the shared content.
 *
 * <p>The Tap-To-Share feature is enabled by a configuration that defines two key components:
 * <ul>
 *   <li><b>Service Binder</b>: The sharesheet is configured with the package name of a specific
 *       {@code TapToShareService} implementation to bind to. This service is responsible for
 *       managing the device detection lifecycle while the sharesheet is active.</li>
 *   <li><b>Fulfillment Activity</b>: The sharesheet is also configured with the package name of a
 *       fulfillment activity. After a successful tap, the sharesheet sends the {@code ACTION_SEND}
 *       intent to this activity. The activity is responsible for handling this intent, which
 *       includes extracting the shared data from the intent's extras and transferring it to the
 *       peer device discovered during the tap.</li>
 * </ul>
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TAP_TO_SHARE)
@SystemApi
public abstract class TapToShareService extends Service {

    private static final String TAG = TapToShareService.class.getSimpleName();

    /** @hide */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String TAP_TO_SHARE_SERVICE_INTERFACE =
            "android.service.chooser.TapToShareService";

    private ITapToShareCallback mActiveCallback = null;
    private boolean mIsSessionStarted = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied(IBinder who) {
            // This is called on a binder thread, post to handler.
            mHandler.post(() -> handleClientDied(who));
        }

        @Override
        public void binderDied() {
            // binderDied(IBinder) is preferred.
        }
    };

    private final ITapToShareService.Stub mBinder = new ITapToShareService.Stub() {
        @Override
        public void registerCallback(ITapToShareCallback callback) {
            mHandler.post(() -> handleSetActiveCallback(callback));
        }

        @Override
        public void unregisterCallback(ITapToShareCallback callback) {
            mHandler.post(() -> handleClearActiveCallback(callback));
        }
    };

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        if (intent == null || !TAP_TO_SHARE_SERVICE_INTERFACE.equals(intent.getAction())) {
            return null;
        }
        return mBinder;
    }

    @Override
    public final boolean onUnbind(@Nullable Intent intent) {
        mHandler.post(this::handleUnbind);
        return false;
    }

    /**
     * Called when the Tap-To-Share session begins. This is triggered when the system sharesheet
     * binds to this service. Implementations should start device detection.
     */
    public abstract void onSessionStart();

    /**
     * Called when the Tap-To-Share session ends. This is triggered when the system sharesheet
     * unbinds from the service (in {@link #onUnbind()}). Implementations should stop device
     * detection.
     */
    public abstract void onSessionEnd();

    /**
     * Notifies the system sharesheet that a compatible device has been detected via an NFC tap and
     * is ready to receive the shared content. After this method is called, the system sharesheet
     * will launch the fulfillment activity with the original share to perform the data transfer.
     * This method is primarily responsible for informing the sharesheet that device discovery has
     * occurred, and does not handle the data transfer directly.
     */
    public final void performTapToShare() {
        mHandler.post(this::handlePerformTapToShare);
    }

    private void handleSetActiveCallback(ITapToShareCallback callback) {
        if (callback == null) {
            return;
        }

        handleClearActiveCallback(mActiveCallback);

        try {
            callback.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to link to death, client already died", e);
            return;
        }

        mActiveCallback = callback;

        if (!mIsSessionStarted) {
            mIsSessionStarted = true;
            onSessionStart();
        }
    }

    private void handleClearActiveCallback(ITapToShareCallback callback) {
        if (callback != null && mActiveCallback != null
                && mActiveCallback.asBinder() == callback.asBinder()) {
            mActiveCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mActiveCallback = null;
        }
    }

    private void handleClientDied(IBinder who) {
        if (mActiveCallback != null && mActiveCallback.asBinder() == who) {
            handleUnbind();
        }
    }

    private void handleUnbind() {
        handleClearActiveCallback(mActiveCallback);
        // Ensures onSessionEnd() is called only once. When a client process dies,
        // cleanup can be triggered from two places: handleClientDied() and the system's onUnbind().
        if (mIsSessionStarted) {
            mIsSessionStarted = false;
            onSessionEnd();
        }
    }

    private void handlePerformTapToShare() {
        if (mActiveCallback != null) {
            try {
                mActiveCallback.performTapToShare();
            } catch (RemoteException e) {
                Log.e(TAG, "Error invoking ITapToShareCallback, client may have died.", e);
                handleClearActiveCallback(mActiveCallback);
            }
        } else {
            Log.d(TAG, "performTapToShare called but no callback registered.");
        }
    }
}