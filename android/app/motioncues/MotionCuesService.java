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

package android.app.motioncues;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Flags;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Abstract base class for a service that provides motion cues data to SystemUI.
 *
 * <p>A system component should extend this service to send bubble rendering information to the
 * SystemUI drawing layer. SystemUI binds to the implementation of this service and provides an
 * {@link IMotionCuesCallback} instance via the intent extras.
 *
 * <p>To extend this class, the implementing component must declare the service in its
 * manifest file. This declaration must require the
 * {@link android.Manifest.permission#BIND_MOTION_CUES_SERVICE} permission and include an
 * intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>{@code
 * <service android:name=".YourMotionCuesServiceSubclass"
 *          android:label="@string/motion_cues_service_label"
 *          android:exported="true"
 *          android:permission="android.permission.BIND_MOTION_CUES_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.app.motioncues.MotionCuesService" />
 *     </intent-filter>
 * </service>
 * }</pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
public abstract class MotionCuesService extends Service {
    private static final String TAG = "MotionCuesService";

    /**
     * Intent extra key for the {@link IBinder} of the {@link IMotionCuesCallback} interface. The
     * SystemUI component binding to this service must include this extra in the Intent's extras.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public static final String EXTRA_API_CALLBACK = "android.app.motioncues.extra.API_CALLBACK";

    /**
     * The Intent action must be provided when binding to this service.
     * This is used by the system to properly bind to the service and to
     * verify that the service is a valid MotionCuesService.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.app.motioncues.MotionCuesService";

    private IMotionCuesCallback mCallback;
    private final IBinder mBinder = new Binder();

    private final IBinder.DeathRecipient mDeathRecipient =
            new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.w(TAG, "MotionCuesCallback died");
                    if (mCallback != null) {
                        mCallback.asBinder().unlinkToDeath(this, 0);
                    }
                    mCallback = null;
                    onCallbackDied();
                }
            };

    /**
     * Called by the system when SystemUI binds to this service.
     *
     * @hide
     */
    @Override
    @RequiresPermission(Manifest.permission.BIND_MOTION_CUES_SERVICE)
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        Log.i(TAG, "onBind called");
        if (intent == null) {
            Log.e(TAG, "Binding intent is null");
            return null;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            Log.e(TAG, "Binding intent missing extras");
            return null;
        }

        IBinder callbackBinder = extras.getBinder(EXTRA_API_CALLBACK);
        if (callbackBinder == null) {
            Log.e(TAG, "Binding intent missing EXTRA_API_CALLBACK");
            return null;
        }

        mCallback = IMotionCuesCallback.Stub.asInterface(callbackBinder);
        try {
            mCallback.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Callback binder is already dead", e);
            mCallback = null;
            return null;
        }

        Log.i(TAG, "Callback registered successfully.");
        onClientConnected();
        return mBinder;
    }

    /**
     * Called when the SystemUI client unbinds from this service.
     *
     * @hide
    */
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    @Override
    public final boolean onUnbind(@Nullable Intent intent) {
        Log.i(TAG, "Client unbound");
        if (mCallback != null) {
            mCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }
        mCallback = null;
        onClientDisconnected();
        return false;
    }

    /**
     * Called when SystemUI has successfully bound and provided a callback. The service
     * implementation should start any necessary operations here.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public abstract void onClientConnected();

    /**
     * Called when the SystemUI client has disconnected (unbound). The service implementation should
     * stop any ongoing operations here.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public abstract void onClientDisconnected();

    /**
     * Called when the SystemUI callback binder has died unexpectedly.
     */
    private void onCallbackDied() {
        onClientDisconnected();
    }

    /**
     * Sends bubble position updates to SystemUI.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public final void updateBubblePixelPos(float dx, float dy) {
        if (mCallback != null) {
            try {
                mCallback.updateBubblePixelPos(dx, dy);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call updateBubblePixelPos", e);
            }
        }
    }

    /**
     * Sends updates for bubble visual properties (e.g., color, shape) to SystemUI.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public final void updateMotionCuesVisualStyle(@NonNull MotionCuesVisualStyle style) {
        if (mCallback != null) {
            try {
                mCallback.updateMotionCuesVisualStyle(style);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call updateMotionCuesVisualStyle", e);
            }
        }
    }
}
