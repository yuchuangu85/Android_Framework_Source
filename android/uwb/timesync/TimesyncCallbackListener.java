/*
 * Copyright 2026 The Android Open Source Project
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

package android.uwb.timesync;

import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.os.RemoteException;
import android.util.Log;
import android.uwb.IUwbAdapter;
import android.uwb.UwbManager;
import android.uwb.UwbManager.AddressType;

import java.util.concurrent.Executor;

/**
 * @hide
 */
public class TimesyncCallbackListener extends ITimesyncCallbackListener.Stub {
    private static final String TAG = TimesyncCallbackListener.class.getSimpleName();

    private Executor mExecutor;
    private final IUwbAdapter mAdapter;
    private boolean mIsRegistered = false;
    private UwbManager.TimesyncCallback mCallback;
    private String mMacAddress;
    private int mAddressType;

    public String getMacAddress() {
        return mMacAddress;
    }
    public int getAddressType() {
        return mAddressType;
    }

    public TimesyncCallbackListener(@NonNull IUwbAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Register an {@link UwbManager.TimesyncCallback} with this {@link TimesyncCallbackListener}
     *
     * @param executor an {@link Executor} to execute given callback
     * @param timesyncCallback user implementation of the {@link UwbManager.TimesyncCallback}
     * @param macAddress the mac address of the peer device for time synchronization
     * @param addressType the address type of the peer device
     */
    public void register(@NonNull Executor executor, UwbManager.TimesyncCallback timesyncCallback,
            String macAddress, @AddressType int addressType) {
        synchronized (this) {
            if (mIsRegistered) {
                throw new IllegalStateException(TAG
                        + "Timesync already registered!! "
                        + "Unregister before trying to register again");
            }
            mCallback = timesyncCallback;
            mMacAddress = macAddress;
            mAddressType = addressType;
            mExecutor = executor;
            try {
                mAdapter.registerTimesyncCallback(this, mMacAddress, mAddressType);
                mIsRegistered = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register timesync callback");
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregister the specified {@link UwbManager.TimesyncCallback}
     *
     * @param timesyncCallback user implementation of the {@link UwbManager.TimesyncCallback}
     */
    public void unregister(UwbManager.TimesyncCallback timesyncCallback) {
        synchronized (this) {
            if (!mIsRegistered || (timesyncCallback != mCallback)) {
                Log.w(TAG, "Timesync callback not yet registered");
                return;
            }
            try {
                mAdapter.unregisterTimesyncCallback(this, mMacAddress, mAddressType);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister timesync callback");
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @Override
    @RequiresNoPermission
    public void onTimesyncEvent(TimesyncEvent timesyncEvent) throws RemoteException {
        mExecutor.execute(() -> mCallback.onTimesyncEvent(timesyncEvent));
    }

    @Override
    @RequiresNoPermission
    public void onRegistered() throws RemoteException {
        mExecutor.execute(() -> mCallback.onRegistered());
    }

    @Override
    @RequiresNoPermission
    public void onRegisterFailed() throws RemoteException {
        mExecutor.execute(() -> mCallback.onRegisteredFailed());
    }
}
