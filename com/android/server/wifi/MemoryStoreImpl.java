/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.IpMemoryStore;
import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.Status;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.android.server.wifi.WifiScoreCard.BlobListener;

import java.util.Objects;

/**
 * Connects WifiScoreCard to IpMemoryStore.
 */
final class MemoryStoreImpl implements WifiScoreCard.MemoryStore {
    private static final String TAG = "WifiMemoryStoreImpl";
    private static final boolean DBG = true;

    // The id of the client that stored this data
    public static final String WIFI_FRAMEWORK_IP_MEMORY_STORE_CLIENT_ID = "com.android.server.wifi";

    // The name of the data
    public static final String WIFI_FRAMEWORK_IP_MEMORY_STORE_DATA_NAME = "scorecard.proto";

    @NonNull private final Context mContext;
    @NonNull private final WifiScoreCard mWifiScoreCard;
    @NonNull private final WifiInjector mWifiInjector;
    @Nullable private IpMemoryStore mIpMemoryStore;

    MemoryStoreImpl(Context context, WifiInjector wifiInjector, WifiScoreCard wifiScoreCard) {
        mContext = Preconditions.checkNotNull(context);
        mWifiScoreCard = Preconditions.checkNotNull(wifiScoreCard);
        mWifiInjector = Preconditions.checkNotNull(wifiInjector);
        mIpMemoryStore = null;
    }

    private boolean mBroken = false;
    private void handleException(Exception e) {
        Log.wtf(TAG, "Exception using IpMemoryStore - disabling WifiScoreReport persistence", e);
        mBroken = true;
    }


    @Override
    public void read(final String key, final BlobListener blobListener) {
        if (mBroken) return;
        try {
            mIpMemoryStore.retrieveBlob(
                    key,
                    WIFI_FRAMEWORK_IP_MEMORY_STORE_CLIENT_ID,
                    WIFI_FRAMEWORK_IP_MEMORY_STORE_DATA_NAME,
                    new CatchAFallingBlob(key, blobListener));
        } catch (RuntimeException e) {
            handleException(e);
        }
    }

    /**
     * Listens for a reply to a read request.
     *
     * Note that onBlobRetrieved() is called on a binder thread, so the
     * provided blobListener must be prepared to deal with this.
     *
     */
    private static class CatchAFallingBlob
            implements android.net.ipmemorystore.OnBlobRetrievedListener {
        private final String mL2Key;
        private final WifiScoreCard.BlobListener mBlobListener;

        CatchAFallingBlob(String l2Key, WifiScoreCard.BlobListener blobListener) {
            mL2Key = l2Key;
            mBlobListener = blobListener;
        }

        @Override
        public void onBlobRetrieved(Status status, String l2Key, String name, Blob data) {
            if (!Objects.equals(mL2Key, l2Key)) {
                throw new IllegalArgumentException("l2Key does not match request");
            }
            if (status.isSuccess()) {
                if (data == null) {
                    if (DBG) Log.i(TAG, "Blob is null");
                    mBlobListener.onBlobRetrieved(null);
                    return;
                }
                mBlobListener.onBlobRetrieved(data.data);
            } else {
                if (DBG) Log.e(TAG, "android.net.ipmemorystore.Status " + status);
            }
        }
    }

    @Override
    public void write(String key, byte[] value) {
        if (mBroken) return;
        final Blob blob = new Blob();
        blob.data = value;
        try {
            mIpMemoryStore.storeBlob(
                    key,
                    WIFI_FRAMEWORK_IP_MEMORY_STORE_CLIENT_ID,
                    WIFI_FRAMEWORK_IP_MEMORY_STORE_DATA_NAME,
                    blob,
                    null /* no listener for now, just fire and forget */);
        } catch (RuntimeException e) {
            handleException(e);
        }
    }

    /**
     * Starts using IpMemoryStore.
     */
    public void start() {
        if (mIpMemoryStore != null) {
            Log.w(TAG, "Reconnecting to IpMemoryStore service");
        }
        mIpMemoryStore = mWifiInjector.getIpMemoryStore();
        if (mIpMemoryStore == null) {
            Log.e(TAG, "No IpMemoryStore service!");
            return;
        }
        mWifiScoreCard.installMemoryStore(this);
    }

    /**
     * Stops using IpMemoryStore after performing any outstanding writes.
     */
    public void stop() {
        if (mIpMemoryStore == null) return;
        mWifiScoreCard.doWrites();
        // TODO - Should wait for writes to complete (or time out)
        Log.i(TAG, "Disconnecting from IpMemoryStore service");
        mIpMemoryStore = null;
    }

}
