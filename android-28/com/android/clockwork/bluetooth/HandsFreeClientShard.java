package com.android.clockwork.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.android.clockwork.common.DebugAssert;

import java.io.Closeable;
import java.io.PrintWriter;

/**
 * A Shard that insists on establishing a client connection to the HandsFree profile on the
 * companion.
 */
/* package private */ class HandsFreeClientShard implements Closeable {
  private static final String TAG = HandsFreeClientShard.class.getSimpleName();
  private static final int POLL_PERIOD_MS = 30_000;
  private static final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

  private final Context context;
  private final BluetoothDevice companionDevice;
  private BluetoothHeadsetClient handsFreeProfile;
  private boolean isClosed;

  public HandsFreeClientShard(final Context context, final BluetoothDevice device) {
    DebugAssert.isMainThread();

    this.context = context;
    companionDevice = device;

    adapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET_CLIENT);
    context.registerReceiver(stateChangeReceiver,
        new IntentFilter(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED));
  }

  @Override
  public void close() {
    DebugAssert.isMainThread();
    if (isClosed) {
      return;
    }
    // Remove the retry message since this shard is closed.
    handler.removeMessages(0);
    context.unregisterReceiver(stateChangeReceiver);
    if (handsFreeProfile != null) {
      handsFreeProfile.disconnect(companionDevice);
      adapter.closeProfileProxy(BluetoothProfile.HEADSET_CLIENT, handsFreeProfile);
    }

    isClosed = true;
  }

  private void onProfileAvailable() {
    // Nothing to do if the profile is already connected.
    if (handsFreeProfile.getConnectionState(companionDevice) == BluetoothProfile.STATE_CONNECTED) {
      Log.i(TAG, "HandsFree client profile is already connected.");
      return;
    }

    // Try to connect and set up a retry loop in case it fails.
    Log.i(TAG, "Connecting HandsFree client profile (startup).");
    handsFreeProfile.connect(companionDevice);

    handler.removeMessages(0);
    handler.sendEmptyMessageDelayed(0, POLL_PERIOD_MS);
  }

  private final Handler handler = new Handler() {
    @Override
    public void handleMessage(Message msg) {
      if (handsFreeProfile != null) {
        Log.d(TAG, "Connecting HandsFree client profile (retry).");
        handsFreeProfile.disconnect(companionDevice);
        handsFreeProfile.connect(companionDevice);
        handler.sendEmptyMessageDelayed(0, POLL_PERIOD_MS);
      }
    }
  };

  private final BroadcastReceiver stateChangeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (!BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
        throw new IllegalStateException(
            "Expected ACTION_CONNECTION_STATE_CHANGED, received " + intent.getAction());
      }

      final int newState =
          intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);

      if (handsFreeProfile != null && newState == BluetoothProfile.STATE_DISCONNECTED) {
        // If there's a retry scheduled already, we shouldn't preempt it here.
        if (!handler.hasMessages(0)) {
          Log.d(TAG, "Connecting HandsFree client profile (disconnect).");
          handsFreeProfile.connect(companionDevice);
          handler.sendEmptyMessageDelayed(0, POLL_PERIOD_MS);
        }
      } else if (newState == BluetoothProfile.STATE_CONNECTED) {
        // Stop polling since we just connected.
        handler.removeMessages(0);
      }
    }
  };

  private final BluetoothProfile.ServiceListener profileListener =
      new BluetoothProfile.ServiceListener() {
    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
      DebugAssert.isMainThread();

      handsFreeProfile = (BluetoothHeadsetClient) proxy;

      if (isClosed) {
        onServiceDisconnected(profile);
        return;
      }

      onProfileAvailable();
    }

    @Override
    public void onServiceDisconnected(int profile) {
      DebugAssert.isMainThread();
      handler.removeMessages(0);
      adapter.closeProfileProxy(BluetoothProfile.HEADSET_CLIENT, handsFreeProfile);
      handsFreeProfile = null;
    }
  };

  public void dump(final PrintWriter writer) {
    writer.printf("HandsFreeClient [%s]\n", companionDevice);
    if (handsFreeProfile != null) {
      final int state = handsFreeProfile.getConnectionState(companionDevice);
      writer.printf("  Profile state: %d\n", state);
      writer.printf("  Retry scheduled: %b\n", handler.hasMessages(0));
    } else {
      writer.println("  Profile unavailable.");
    }
  }
}
