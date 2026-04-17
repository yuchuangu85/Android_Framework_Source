/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.os.allowlist;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.appfunctions.flags.Flags;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;

/**
 * The specifications for a service that will handle allowlist requests, and respond to them. This
 * service exposes its data via the {@link AllowlistManager}.
 *
 * <p> Must be declared in manifest in the following form:
 * <pre>
 *     {@literal
 *       <service android:name=".MyAllowlistProviderService"
 *                android:permission="android.permission.BIND_ALLOWLIST_PROVIDER_SERVICE"
 *              <intent-filter>
 *                  <action android:name="android.allowlist.action.ALLOWLIST_PROVIDER"/>
 *              </intent-filter>
 *       </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
public abstract class AllowlistProviderService extends Service {

    private static final String LOG_TAG = AllowlistProviderService.class.getSimpleName();

    /**
     * The intent action that the AllowlistProviderService must have a filter for in its manifest.
     */
    public static final String ACTION_ALLOWLIST_PROVIDER =
            "android.os.allowlist.action.ALLOWLIST_PROVIDER";

    private IAllowlistProviderService.Stub mStub;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArraySet<AllowlistRequest> mAllowlistListenerRequests = new ArraySet<>();

    @GuardedBy("mLock")
    private ProviderOnAllowlistChangedListener mProviderOnAllowlistChangedListener;

    @Override
    public void onCreate() {
        mStub = new Stub();
    }

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        return mStub;
    }

    /**
     * Need to be implemented by each provider service to handle allowlist query requests.
     *
     * @param request The request that contains query criteria for the allowlist
     * @return A response to the request
     * @see AllowlistRequest
     * @see AllowlistResponse
     */
    public abstract @NonNull AllowlistResponse onQueryAllowlist(@NonNull AllowlistRequest request);

    /**
     * A method used to manually notify a change listener event. Used only in tests.
     *
     * @hide
     */
    public void onNotifyAllowlistChangedListenersForTestProvider(
            @NonNull List<AllowlistRequest> requests) {
        // No op by default
    }

    /**
     * When allowlist changes match certain registered AllowlistRequests, call this method to
     * notify the system service of the change.
     *
     * @param changedRequests The allowlist requests that are relevant to allowlist changes
     */
    public final void notifyAllowlistChanged(@NonNull List<AllowlistRequest> changedRequests) {
        synchronized (mLock) {
            if (mProviderOnAllowlistChangedListener != null) {
                mProviderOnAllowlistChangedListener.onAllowlistChanged(changedRequests);
            } else {
                Log.w(LOG_TAG, "No listener registered for allowlist changes, cannot notify");
            }
        }
    }

    /**
     * Return a list of {@link AllowlistRequest} the provider service has received for registering
     * allowlist change listeners.
     */
    @NonNull
    public final List<AllowlistRequest> getAllowlistListenerRequests() {
        synchronized (mLock) {
            return new ArrayList<>(mAllowlistListenerRequests);
        }
    }

    private class ProviderOnAllowlistChangedListener implements IBinder.DeathRecipient {
        private final IProviderOnAllowlistChangedListener mRemoteListener;

        ProviderOnAllowlistChangedListener(IProviderOnAllowlistChangedListener remoteListener) {
            mRemoteListener = remoteListener;
            try {
                mRemoteListener.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error linking death recipient", e);
            }
        }

        void onAllowlistChanged(List<AllowlistRequest> requests) {
            try {
                mRemoteListener.onAllowlistChanged(requests);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Failed to notify AllowlistService about changes", e);
            }
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                // This happens when the system service dies.
                mProviderOnAllowlistChangedListener = null;
                mAllowlistListenerRequests.clear();
            }
        }
    }

    private class Stub extends IAllowlistProviderService.Stub {

        @Override
        public void addRequestForAllowlistChange(@NonNull AllowlistRequest request,
                @NonNull IProviderOnAllowlistChangedListener listener) {
            synchronized (mLock) {
                if (mProviderOnAllowlistChangedListener == null) {
                    mProviderOnAllowlistChangedListener = new ProviderOnAllowlistChangedListener(
                            listener);
                }
                mAllowlistListenerRequests.add(request);
            }
        }

        @Override
        public void removeRequestForAllowlistChange(@NonNull AllowlistRequest request) {
            synchronized (mLock) {
                mAllowlistListenerRequests.remove(request);
            }
        }

        @Override
        public void queryAllowlist(AllowlistRequest request,
                RemoteCallback callback) throws RemoteException {
            AllowlistResponse response = onQueryAllowlist(request);
            Bundle bundle = new Bundle();
            bundle.putParcelable(AllowlistManager.KEY_ALLOWLIST_RESPONSE, response);
            callback.sendResult(bundle);
        }

        @Override
        public void notifyAllowlistChangedListenersForTestProvider(
                @NonNull List<AllowlistRequest> requests)
                throws RemoteException {
            onNotifyAllowlistChangedListenersForTestProvider(requests);
        }
    }
}
