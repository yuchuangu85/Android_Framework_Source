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

package android.uwb;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @hide
 */
public class ChannelUsageCallbackListener extends IChannelUsageCallback.Stub {
    private static final String TAG = "Uwb.ChannelUsageListener";
    private final IUwbAdapter mAdapter;
    private boolean mIsRegistered = false;
    private final Map<Integer, Boolean> mChannelUsageMap = new ConcurrentHashMap<>();
    private final Map<Consumer<Set<@UwbManager.UwbChannel Integer>>, Executor> mCallbackMap =
            new ConcurrentHashMap<>();

    public ChannelUsageCallbackListener(@NonNull IUwbAdapter adapter) {
        mAdapter = adapter;
    }

    public void register(@NonNull Executor executor,
            @NonNull Consumer<Set<@UwbManager.UwbChannel Integer>> callback) {
        synchronized (this) {
            if (mCallbackMap.containsKey(callback)) {
                return;
            }
            mCallbackMap.put(callback, executor);

            if (!mIsRegistered) {
                try {
                    mAdapter.registerChannelUsageCallback(this);
                    mIsRegistered = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to register adapter state callback");
                    throw e.rethrowFromSystemServer();
                }
            } else {
                sendCurrentUsage(callback);
            }
        }
    }

    public void unregister(@NonNull Consumer<Set<@UwbManager.UwbChannel Integer>> callback) {
        synchronized (this) {
            if (!mCallbackMap.containsKey(callback)) {
                return;
            }
            mCallbackMap.remove(callback);

            if (mCallbackMap.isEmpty() && mIsRegistered) {
                try {
                    mAdapter.unregisterChannelUsageCallback(this);
                    mIsRegistered = false;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to unregister adapter state callback");
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    private void sendCurrentUsage(@NonNull Consumer<Set<@UwbManager.UwbChannel Integer>> callback) {
        synchronized (this) {
            Executor executor = mCallbackMap.get(callback);
            final long identity = Binder.clearCallingIdentity();
            try {
                executor.execute(
                        () -> callback.accept(mChannelUsageMap.entrySet()
                                .stream()
                                .filter(Map.Entry::getValue)
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toUnmodifiableSet())));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void onChannelUsageUpdated(List<ChannelUsage> channelUsageList) {
        for (ChannelUsage c : channelUsageList) {
            mChannelUsageMap.put(c.mChannel, c.mUsage);
        }
        for (Consumer callback : mCallbackMap.keySet()) {
            sendCurrentUsage(callback);
        }
    }
}
