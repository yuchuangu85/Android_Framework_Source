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

package android.service.selectiontoolbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;
import android.view.selectiontoolbar.WidgetInfo;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * Service for rendering selection toolbar.
 *
 * @hide
 */
public abstract class SelectionToolbarRenderService extends Service {

    private static final String TAG = "SelectionToolbarRenderService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_SELECTION_TOOLBAR_RENDER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.selectiontoolbar.SelectionToolbarRenderService";

    private volatile ISelectionToolbarRenderServiceCallback mServiceCallback;

    private final HandlerThread mThread =
            new HandlerThread(SelectionToolbarRenderService.class.getSimpleName());
    private final Executor mThreadExecutor;

    public SelectionToolbarRenderService() {
        mThread.start();
        mThreadExecutor = mThread.getThreadExecutor();
    }

    /**
     * Binder to receive calls from system server.
     */
    private final ISelectionToolbarRenderService mInterface =
            new ISelectionToolbarRenderService.Stub() {

                /**
                 * Maps the uid of the calling app who the toolbar is for to the callback for
                 * toolbar events.
                 */
                private final SparseArray<RemoteCallbackWrapper> mCache =
                        new SparseArray<>();

                @Override
                public void onConnected(IBinder callback) {
                    mServiceCallback = ISelectionToolbarRenderServiceCallback.Stub.asInterface(
                            callback);
                }

                @Override
                public void onShow(int uid, ShowInfo showInfo,
                        ISelectionToolbarCallback callback) {
                    mThreadExecutor.execute(() -> {
                        RemoteCallbackWrapper remoteCallbackWrapper = mCache.get(uid);
                        if (remoteCallbackWrapper == null) {
                            try {
                                DeathRecipient deathRecipient = () -> {
                                    mThreadExecutor.execute(() -> {
                                        mCache.remove(uid);
                                        onUidDied(uid);
                                    });
                                };
                                callback.asBinder().linkToDeath(deathRecipient, 0);
                                remoteCallbackWrapper = new RemoteCallbackWrapper(callback,
                                        deathRecipient);
                                mCache.put(uid, remoteCallbackWrapper);
                            } catch (RemoteException e) {
                                Log.e(TAG, "ISelectionToolbarCallback has already died");
                                return;
                            }
                        }
                        SelectionToolbarRenderService.this.onShow(uid, showInfo,
                                remoteCallbackWrapper);
                    });
                }

                @Override
                public void onHide(int uid) {
                    mThreadExecutor.execute(() -> SelectionToolbarRenderService.this.onHide(uid));
                }

                @Override
                public void onDismiss(int uid) {
                    mThreadExecutor.execute(() -> {
                        SelectionToolbarRenderService.this.onDismiss(uid);
                        RemoteCallbackWrapper remoteCallbackWrapper =
                                mCache.removeReturnOld(uid);
                        if (remoteCallbackWrapper != null) {
                            remoteCallbackWrapper.unlinkToDeath();
                        }
                    });
                }

                @Override
                public void onUidDied(int uid) {
                    mThreadExecutor.execute(
                            () -> SelectionToolbarRenderService.this.onUidDied(uid));
                }
            };

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    protected void transferTouch(@NonNull IBinder source, @NonNull IBinder target) {
        final ISelectionToolbarRenderServiceCallback callback = mServiceCallback;
        if (callback == null) {
            Log.e(TAG, "transferTouch(): no server callback");
            return;
        }
        try {
            callback.transferTouch(source, target);
        } catch (RemoteException e) {
            // no-op
        }
    }

    protected void onPasteAction(int uid) {
        final ISelectionToolbarRenderServiceCallback callback = mServiceCallback;
        if (callback == null) {
            Log.e(TAG, "onPasteAction(): no server callback");
            return;
        }
        try {
            callback.onPasteAction(uid);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to notify onPasteAction", e);
        }
    }

    /**
     * Called when showing the selection toolbar.
     */
    public abstract void onShow(int uid, ShowInfo showInfo,
            RemoteCallbackWrapper callbackWrapper);

    /**
     * Called when hiding the selection toolbar.
     */
    public abstract void onHide(int uid);

    /**
     * Called when dismissing the selection toolbar.
     */
    public abstract void onDismiss(int uid);

    /**
     * Called when the client process dies.
     */
    public abstract void onUidDied(int uid);

    /**
     * Callback to notify the client toolbar events.
     */
    public static final class RemoteCallbackWrapper implements SelectionToolbarRenderCallback {

        private final ISelectionToolbarCallback mRemoteCallback;

        private final IBinder.DeathRecipient mDeathRecipient;

        RemoteCallbackWrapper(ISelectionToolbarCallback remoteCallback,
                IBinder.DeathRecipient deathRecipient) {
            // TODO(b/215497659): handle if the binder dies.
            mRemoteCallback = remoteCallback;
            mDeathRecipient = deathRecipient;
        }

        private void unlinkToDeath() {
            mRemoteCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }

        @Override
        public void onShown(WidgetInfo widgetInfo) {
            try {
                mRemoteCallback.onShown(widgetInfo);
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onInvisible() {
            try {
                mRemoteCallback.onInvisible();
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onWidgetUpdated(WidgetInfo widgetInfo) {
            try {
                mRemoteCallback.onWidgetUpdated(widgetInfo);
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onMenuItemClicked(int itemIndex) {
            try {
                mRemoteCallback.onMenuItemClicked(itemIndex);
            } catch (RemoteException e) {
                // no-op
            }
        }
    }

    /**
     * A listener to notify the service to the transfer touch focus.
     */
    public interface TransferTouchListener {
        /**
         * Notify the service to transfer the touch focus.
         */
        void onTransferTouch(IBinder source, IBinder target);
    }

    /**
     * A listener to notify the service to the paste action.
     */
    public interface OnPasteActionCallback {
        /**
         * Notify the service to the paste action.
         */
        void onPasteAction(int uid);
    }
}
