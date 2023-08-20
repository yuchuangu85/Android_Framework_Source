/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.dreams;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import java.util.concurrent.Executor;


/**
 * Basic implementation of for {@link IDreamOverlay} for testing.
 * @hide
 */
@TestApi
public abstract class DreamOverlayService extends Service {
    private static final String TAG = "DreamOverlayService";
    private static final boolean DEBUG = false;

    // The last client that started dreaming and hasn't ended
    private OverlayClient mCurrentClient;

    /**
     * Executor used to run callbacks that subclasses will implement. Any calls coming over Binder
     * from {@link OverlayClient} should perform the work they need to do on this executor.
     */
    private Executor mExecutor;

    // An {@link IDreamOverlayClient} implementation that identifies itself when forwarding
    // requests to the {@link DreamOverlayService}
    private static class OverlayClient extends IDreamOverlayClient.Stub {
        private final DreamOverlayService mService;
        private boolean mShowComplications;
        private ComponentName mDreamComponent;
        IDreamOverlayCallback mDreamOverlayCallback;

        OverlayClient(DreamOverlayService service) {
            mService = service;
        }

        @Override
        public void startDream(WindowManager.LayoutParams params, IDreamOverlayCallback callback,
                String dreamComponent, boolean shouldShowComplications) throws RemoteException {
            mDreamComponent = ComponentName.unflattenFromString(dreamComponent);
            mShowComplications = shouldShowComplications;
            mDreamOverlayCallback = callback;
            mService.startDream(this, params);
        }

        @Override
        public void wakeUp() {
            mService.wakeUp(this, () -> {
                try {
                    mDreamOverlayCallback.onWakeUpComplete();
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not notify dream of wakeUp", e);
                }
            });
        }

        @Override
        public void endDream() {
            mService.endDream(this);
        }

        private void onExitRequested() {
            try {
                mDreamOverlayCallback.onExitRequested();
            } catch (RemoteException e) {
                Log.e(TAG, "Could not request exit:" + e);
            }
        }

        private boolean shouldShowComplications() {
            return mShowComplications;
        }

        private ComponentName getComponent() {
            return mDreamComponent;
        }
    }

    private void startDream(OverlayClient client, WindowManager.LayoutParams params) {
        // Run on executor as this is a binder call from OverlayClient.
        mExecutor.execute(() -> {
            endDreamInternal(mCurrentClient);
            mCurrentClient = client;
            onStartDream(params);
        });
    }

    private void endDream(OverlayClient client) {
        // Run on executor as this is a binder call from OverlayClient.
        mExecutor.execute(() -> endDreamInternal(client));
    }

    private void endDreamInternal(OverlayClient client) {
        if (client == null || client != mCurrentClient) {
            return;
        }

        onEndDream();
        mCurrentClient = null;
    }

    private void wakeUp(OverlayClient client, Runnable callback) {
        // Run on executor as this is a binder call from OverlayClient.
        mExecutor.execute(() -> {
            if (mCurrentClient != client) {
                return;
            }

            onWakeUp(callback);
        });
    }

    private IDreamOverlay mDreamOverlay = new IDreamOverlay.Stub() {
        @Override
        public void getClient(IDreamOverlayClientCallback callback) {
            try {
                callback.onDreamOverlayClient(
                        new OverlayClient(DreamOverlayService.this));
            } catch (RemoteException e) {
                Log.e(TAG, "could not send client to callback", e);
            }
        }
    };

    public DreamOverlayService() {
    }

    /**
     * This constructor allows providing an executor to run callbacks on.
     *
     * @hide
     */
    public DreamOverlayService(@NonNull Executor executor) {
        mExecutor = executor;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (mExecutor == null) {
            // If no executor was provided, use the main executor. onCreate is the earliest time
            // getMainExecutor is available.
            mExecutor = getMainExecutor();
        }
    }

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        return mDreamOverlay.asBinder();
    }

    /**
     * This method is overridden by implementations to handle when the dream has started and the
     * window is ready to be interacted with.
     *
     * This callback will be run on the {@link Executor} provided in the constructor if provided, or
     * on the main executor if none was provided.
     *
     * @param layoutParams The {@link android.view.WindowManager.LayoutParams} associated with the
     *                     dream window.
     */
    public abstract void onStartDream(@NonNull WindowManager.LayoutParams layoutParams);

    /**
     * This method is overridden by implementations to handle when the dream has been requested
     * to wakeup. This allows any overlay animations to run. By default, the method will invoke
     * the callback immediately.
     *
     * This callback will be run on the {@link Executor} provided in the constructor if provided, or
     * on the main executor if none was provided.
     *
     * @param onCompleteCallback The callback to trigger to notify the dream service that the
     *                           overlay has completed waking up.
     * @hide
     */
    public void onWakeUp(@NonNull Runnable onCompleteCallback) {
        onCompleteCallback.run();
    }

    /**
     * This method is overridden by implementations to handle when the dream has ended. There may
     * be earlier signals leading up to this step, such as @{@link #onWakeUp(Runnable)}.
     *
     * This callback will be run on the {@link Executor} provided in the constructor if provided, or
     * on the main executor if none was provided.
     */
    public void onEndDream() {
    }

    /**
     * This method is invoked to request the dream exit.
     */
    public final void requestExit() {
        if (mCurrentClient == null) {
            throw new IllegalStateException("requested exit with no dream present");
        }

        mCurrentClient.onExitRequested();
    }

    /**
     * Returns whether to show complications on the dream overlay.
     */
    public final boolean shouldShowComplications() {
        if (mCurrentClient == null) {
            throw new IllegalStateException(
                    "requested if should show complication when no dream active");
        }

        return mCurrentClient.shouldShowComplications();
    }

    /**
     * Returns the active dream component.
     * @hide
     */
    public final ComponentName getDreamComponent() {
        if (mCurrentClient == null) {
            throw new IllegalStateException("requested dream component when no dream active");
        }

        return mCurrentClient.getComponent();
    }
}
