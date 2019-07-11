package com.android.clockwork.bluetooth;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;
import com.android.clockwork.bluetooth.proxy.ProxyServiceHelper;
import com.android.clockwork.common.Util;
import com.android.internal.util.IndentingPrintWriter;

/**
 * This class serves as a thin layer of separation between the lifecycle management of
 * the shards owned by WearBluetoothService and the underlying implementations of the
 * shards themselves.
 *
 */
public class BluetoothShardRunner {
    private static final String TAG = WearBluetoothConstants.LOG_TAG;

    private final Context mContext;
    private final CompanionTracker mCompanionTracker;
    private final ProxyServiceHelper mProxyServiceHelper;

    private int mCompanionShardStarts;
    private int mCompanionShardStops;

    private CompanionProxyShard mProxyShard;
    private HandsFreeClientShard mHfcShard;

    public BluetoothShardRunner(
            final Context context,
            final CompanionTracker companionTracker,
            final ProxyServiceHelper proxyServiceHelper) {
        mContext = context;
        mCompanionTracker = companionTracker;
        mProxyServiceHelper = proxyServiceHelper;
    }

    @MainThread
    void startProxyShard(
            final int proxyScore,
            final CompanionProxyShard.Listener listener,
            final String reason) {
        final BluetoothDevice companion = mCompanionTracker.getCompanion();
        if (companion == null || mCompanionTracker.isCompanionBle()) {
            Log.w(TAG, "BluetoothShardRunner Companion is unavailable for proxy: " + companion);
            return;
        }
        mCompanionShardStarts += 1;
        if (mProxyShard != null) {
            Log.w(TAG, "BluetoothShardRunner Tearing down orphan proxy shard before"
                    + " starting new shard.");
            stopProxyShard();
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "BluetoothShardRunner Starting CompanionProxyShard for companion ["
                    + companion + "]" + " with score (" + proxyScore + ")");
        }
        mProxyShard = new CompanionProxyShard(mContext, mProxyServiceHelper, companion,
                listener, proxyScore);
    }

    @MainThread
    void updateProxyShard(final int proxyScore) {
        if (mProxyShard != null) {
            mProxyShard.updateNetwork(proxyScore);
        }
    }

    @MainThread
    void stopProxyShard() {
        if (mProxyShard != null) {
             if (Log.isLoggable(TAG, Log.DEBUG)) {
                 Log.d(TAG, "BluetoothShardRunner Stopping CompanionProxyShard.");
             }
            Util.close(mProxyShard);
            mCompanionShardStops += 1;
        }
        mProxyShard = null;
    }

    @MainThread
    void startHfcShard() {
        BluetoothDevice companion = mCompanionTracker.getCompanion();
        if (companion == null) {
            return;
        }
        if (mHfcShard != null) {
            Log.w(TAG, "BluetoothShardRunner Tearing down orphan HfcShard before starting"
                    + " new shard.");
            stopHfcShard();
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "BluetoothShardRunner Starting HandsFreeClientShard for companion ["
                    + companion + "]");
        }
        mHfcShard = new HandsFreeClientShard(mContext, companion);
    }

    @MainThread
    void stopHfcShard() {
        if (mHfcShard != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "BluetoothShardRunner Stopping HandsFreeClientShard.");
            }
            Util.close(mHfcShard);
        }
        mHfcShard = null;
    }

    void dumpShards(@NonNull final IndentingPrintWriter ipw) {
        ipw.printf("Dumping shard(s).\n");
        if (mProxyShard != null) {
            mProxyShard.dump(ipw);
        }
        ipw.increaseIndent();
        ipw.printPair("companion shard starts", mCompanionShardStarts);
        ipw.printPair("companion shard stops", mCompanionShardStops);
        ipw.decreaseIndent();
        ipw.println();
        if (mHfcShard != null) {
            mHfcShard.dump(ipw);
        }
    }
}
