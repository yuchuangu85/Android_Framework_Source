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
package android.nfc;

import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.nfc.NfcAdapter.ReaderCallback;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** @hide */
public final class NfcGestureExchangeCallbackListener extends IReaderCallback.Stub {
    private static final String TAG = "Nfc.NfcGestureExchangeCallback";
    private boolean mIsRegistered = false;
    private final Map<ReaderCallback, Executor> mCallbackMap = new HashMap<>();
    private IBinder.DeathRecipient mDeathRecipient;

    public NfcGestureExchangeCallbackListener() {}

    private void linkToNfcDeath() {
        try {
            mDeathRecipient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    synchronized (this) {
                        mDeathRecipient = null;
                    }
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                synchronized (this) {
                                    if (mCallbackMap.size() > 0) {
                                        NfcAdapter.callService(() -> NfcAdapter.getService()
                                                .registerGestureExchangeCallback(
                                                        NfcGestureExchangeCallbackListener.this));
                                        linkToNfcDeath();
                                    }
                                }
                            } catch (Throwable t) {
                                handler.postDelayed(this, 50);
                            }
                        }
                    }, 50);
                }
            };
            NfcAdapter.getService().asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException re) {
            Log.e(TAG, "Couldn't link to death");
        }
    }

    /**
     * Register GestureExchangeCallback
     */
    public void register(@NonNull Executor executor, @NonNull ReaderCallback callback) {
        synchronized (this) {
            if (mCallbackMap.containsKey(callback)) {
                return;
            }
            mCallbackMap.put(callback, executor);
            if (!mIsRegistered) {
                final  NfcGestureExchangeCallbackListener listener = this;
                NfcAdapter.callService(() -> {
                    NfcAdapter.getService().registerGestureExchangeCallback(listener);
                    linkToNfcDeath();
                    mIsRegistered = true;
                });
            }
        }
    }

    /**
     * Unregister GestureExchangeCallback
     */
    public void unregister(@NonNull ReaderCallback callback) {
        synchronized (this) {
            if (!mCallbackMap.containsKey(callback) || !mIsRegistered) {
                return;
            }
            if (mCallbackMap.size() == 1) {
                final NfcGestureExchangeCallbackListener listener = this;
                NfcAdapter.callService(() -> {
                    NfcAdapter.getService().unregisterGestureExchangeCallback(listener);
                    if (mDeathRecipient != null) {
                        NfcAdapter.getService().asBinder().unlinkToDeath(mDeathRecipient, 0);
                        mDeathRecipient = null;
                    }
                    mIsRegistered = false;
                });
            }
            mCallbackMap.remove(callback);
        }
    }

    @Override
    @RequiresNoPermission
    public void onTagDiscovered(Tag tag) throws RemoteException {
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                for (ReaderCallback callback : mCallbackMap.keySet()) {
                    Executor executor = mCallbackMap.get(callback);
                    executor.execute(() -> callback.onTagDiscovered(tag));
                }
            } catch (RuntimeException ex) {
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    @RequiresNoPermission
    public void onTagLost(Tag tag) throws RemoteException {
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                for (ReaderCallback callback : mCallbackMap.keySet()) {
                    Executor executor = mCallbackMap.get(callback);
                    executor.execute(() -> callback.onTagLost(tag));
                }
            } catch (RuntimeException ex) {
                throw ex;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
