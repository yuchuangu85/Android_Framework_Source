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

package android.window;

import static android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT;
import static android.window.OnBackInvokedDispatcher.PRIORITY_SYSTEM;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.util.Pair;
import android.util.Printer;
import android.view.ViewRootImpl;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * A handler (on the app process side) for IME back callbacks.
 *
 * See {@link ImeBackCallbackSender} for the counterpart of this class on the IME process side.
 *
 * This class lives in the app process and handles back callback registrations from the IME process.
 * Whenever it receives a callback registration through its ResultReceiver, it registers the
 * callback at the correct receiving dispatcher inside the app process.
 * <p>
 * The app process creates an instance of {@link ImeBackCallbackProxy} in
 * {@link android.view.inputmethod.InputMethodManager}. This class` {@link ResultReceiver} is
 * sent to the IME process. The IME process then uses the provided ResultReceiver to send callback
 * registrations and unregistrations to the app process.
 *
 * @see ImeBackCallbackSender
 * @hide
 */
public class ImeBackCallbackProxy {

    private static final String TAG = "ImeBackCallbackProxy";
    static final String RESULT_KEY_ID = "id";
    static final String RESULT_KEY_CALLBACK = "callback";
    static final String RESULT_KEY_PRIORITY = "priority";
    static final int RESULT_CODE_REGISTER = 0;
    static final int RESULT_CODE_UNREGISTER = 1;
    @NonNull
    private final ResultReceiver mResultReceiver;
    private final ArrayDeque<Pair<Integer, Bundle>> mQueuedReceive = new ArrayDeque<>();

    public ImeBackCallbackProxy(Handler handler) {
        mResultReceiver = new ResultReceiver(handler) {
            @Override
            public void onReceiveResult(int resultCode, Bundle resultData) {
                WindowOnBackInvokedDispatcher dispatcher = getReceivingDispatcher();
                if (dispatcher != null) {
                    receive(resultCode, resultData, dispatcher);
                } else {
                    mQueuedReceive.add(new Pair<>(resultCode, resultData));
                }
            }
        };
    }

    /** Set receiving dispatcher to consume queued receiving events. */
    public void updateReceivingDispatcher(@NonNull WindowOnBackInvokedDispatcher dispatcher) {
        while (!mQueuedReceive.isEmpty()) {
            final Pair<Integer, Bundle> queuedMessage = mQueuedReceive.poll();
            receive(queuedMessage.first, queuedMessage.second, dispatcher);
        }
    }

    /**
     * Override this method to return the {@link WindowOnBackInvokedDispatcher} of the window
     * that should receive the forwarded callback.
     */
    @Nullable
    protected WindowOnBackInvokedDispatcher getReceivingDispatcher() {
        return null;
    }

    @NonNull
    public ResultReceiver getResultReceiver() {
        return mResultReceiver;
    }

    private final ArrayList<ImeOnBackInvokedCallback> mImeCallbacks = new ArrayList<>();

    private void receive(
            int resultCode, Bundle resultData,
            @NonNull WindowOnBackInvokedDispatcher receivingDispatcher) {
        if (resultCode == RESULT_CODE_REGISTER) {
            final int callbackId = resultData.getInt(RESULT_KEY_ID);
            int priority = resultData.getInt(RESULT_KEY_PRIORITY);
            final IOnBackInvokedCallback callback = IOnBackInvokedCallback.Stub.asInterface(
                    resultData.getBinder(RESULT_KEY_CALLBACK));
            registerReceivedCallback(callback, priority, callbackId, receivingDispatcher);
        } else if (resultCode == RESULT_CODE_UNREGISTER) {
            final int callbackId = resultData.getInt(RESULT_KEY_ID);
            unregisterReceivedCallback(callbackId, receivingDispatcher);
        }
    }

    private void registerReceivedCallback(
            @NonNull IOnBackInvokedCallback iCallback,
            @OnBackInvokedDispatcher.Priority int priority,
            int callbackId,
            @NonNull WindowOnBackInvokedDispatcher receivingDispatcher) {
        final ImeOnBackInvokedCallback imeCallback;
        if (priority == PRIORITY_SYSTEM) {
            // A callback registration with PRIORITY_SYSTEM indicates that a predictive back
            // animation can be played on the IME. Therefore register the
            // DefaultImeOnBackInvokedCallback with the receiving dispatcher and override the
            // priority to PRIORITY_DEFAULT.
            priority = PRIORITY_DEFAULT;
            imeCallback = new DefaultImeOnBackAnimationCallback(iCallback, callbackId, priority);
        } else {
            imeCallback = new ImeOnBackInvokedCallback(iCallback, callbackId, priority);
        }
        if (unregisterCallback(callbackId, receivingDispatcher)) {
            Log.w(TAG, "Received IME callback that's already registered. Unregistering and "
                    + "reregistering. (callbackId: " + callbackId
                    + " current callbacks: " + mImeCallbacks.size() + ")");
        }
        mImeCallbacks.add(imeCallback);
        receivingDispatcher.registerOnBackInvokedCallbackUnchecked(imeCallback, priority);
    }

    private void unregisterReceivedCallback(
            int callbackId, @NonNull OnBackInvokedDispatcher receivingDispatcher) {
        if (!unregisterCallback(callbackId, receivingDispatcher)) {
            Log.w(TAG, "Ime callback not found. Ignoring unregisterReceivedCallback. "
                    + "callbackId: " + callbackId
                    + " remaining callbacks: " + mImeCallbacks.size());
        }
    }

    private boolean unregisterCallback(int callbackId,
            @NonNull OnBackInvokedDispatcher receivingDispatcher) {
        ImeOnBackInvokedCallback callback = null;
        for (ImeOnBackInvokedCallback imeCallback : mImeCallbacks) {
            if (imeCallback.getId() == callbackId) {
                callback = imeCallback;
                break;
            }
        }
        if (callback == null) {
            return false;
        }
        receivingDispatcher.unregisterOnBackInvokedCallback(callback);
        mImeCallbacks.remove(callback);
        return true;
    }

    /**
     * Unregisters all callbacks on the receiving dispatcher but keeps a reference of the callbacks
     * in case the clearance is reverted in
     * {@link ImeOnBackInvokedDispatcher#undoPreliminaryClear()}.
     */
    public void preliminaryClear() {
        // Unregister previously registered callbacks if there's any.
        if (getReceivingDispatcher() != null) {
            for (ImeOnBackInvokedCallback callback : mImeCallbacks) {
                getReceivingDispatcher().unregisterOnBackInvokedCallback(callback);
            }
        }
    }

    /**
     * Reregisters all callbacks on the receiving dispatcher that have previously been cleared by
     * calling {@link ImeOnBackInvokedDispatcher#preliminaryClear()}. This can happen if an IME hide
     * animation is interrupted causing the IME to reappear.
     */
    public void undoPreliminaryClear() {
        if (getReceivingDispatcher() != null) {
            for (ImeOnBackInvokedCallback callback : mImeCallbacks) {
                getReceivingDispatcher().registerOnBackInvokedCallbackUnchecked(callback,
                        callback.mPriority);
            }
        }
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        // Unregister previously registered callbacks if there's any.
        if (getReceivingDispatcher() != null) {
            for (ImeOnBackInvokedCallback callback : mImeCallbacks) {
                getReceivingDispatcher().unregisterOnBackInvokedCallback(callback);
            }
        }
        mImeCallbacks.clear();
        mQueuedReceive.clear();
    }

    /**
     * Dumps the registered IME callbacks.
     *
     * @param prefix prefix to be prepended to each line
     * @param p      printer to write the dump to
     */
    public void dump(@NonNull Printer p, @NonNull String prefix) {
        p.println(prefix + TAG + ":");
        String innerPrefix = prefix + "  ";
        if (mImeCallbacks.isEmpty()) {
            p.println(innerPrefix + "mImeCallbacks: []");
        } else {
            p.println(innerPrefix + "mImeCallbacks:");
            for (ImeOnBackInvokedCallback callback : mImeCallbacks) {
                p.println(innerPrefix + "  " + callback);
            }
        }
    }

    @VisibleForTesting(visibility = PACKAGE)
    public static class ImeOnBackInvokedCallback implements OnBackAnimationCallback {
        @NonNull
        protected final IOnBackInvokedCallback mIOnBackInvokedCallback;
        /**
         * The hashcode of the callback instance in the IME process, used as a unique id to
         * identify the callback when it's passed between processes.
         */
        protected final int mId;
        private final int mPriority;

        ImeOnBackInvokedCallback(@NonNull IOnBackInvokedCallback iCallback, int id,
                @OnBackInvokedDispatcher.Priority int priority) {
            mIOnBackInvokedCallback = iCallback;
            mId = id;
            mPriority = priority;
        }

        @Override
        public void onBackStarted(@NonNull BackEvent backEvent) {
            try {
                mIOnBackInvokedCallback.onBackStarted(
                        new BackMotionEvent(backEvent.getTouchX(), backEvent.getTouchY(),
                                backEvent.getFrameTimeMillis(), backEvent.getProgress(), false,
                                backEvent.getSwipeEdge()));
            } catch (RemoteException e) {
                Log.e(TAG, "Exception when invoking forwarded callback. e: ", e);
            }
        }

        @Override
        public void onBackProgressed(@NonNull BackEvent backEvent) {
            try {
                mIOnBackInvokedCallback.onBackProgressed(
                        new BackMotionEvent(backEvent.getTouchX(), backEvent.getTouchY(),
                                backEvent.getFrameTimeMillis(), backEvent.getProgress(), false,
                                backEvent.getSwipeEdge()));
            } catch (RemoteException e) {
                Log.e(TAG, "Exception when invoking forwarded callback. e: ", e);
            }
        }

        @Override
        public void onBackInvoked() {
            try {
                mIOnBackInvokedCallback.onBackInvoked();
            } catch (RemoteException e) {
                Log.e(TAG, "Exception when invoking forwarded callback. e: ", e);
            }
        }

        @Override
        public void onBackCancelled() {
            try {
                mIOnBackInvokedCallback.onBackCancelled();
            } catch (RemoteException e) {
                Log.e(TAG, "Exception when invoking forwarded callback. e: ", e);
            }
        }

        private int getId() {
            return mId;
        }

        @Override
        public String toString() {
            return "ImeOnBackInvokedCallback@" + mId
                    + " Callback=" + mIOnBackInvokedCallback;
        }
    }

    /**
     * Subclass of ImeOnBackInvokedCallback indicating that a predictive IME back animation may be
     * played instead of invoking the callback.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public static class DefaultImeOnBackAnimationCallback extends ImeOnBackInvokedCallback {
        DefaultImeOnBackAnimationCallback(@NonNull IOnBackInvokedCallback iCallback, int id,
                int priority) {
            super(iCallback, id, priority);
        }

        @Override
        public String toString() {
            return "DefaultImeOnBackAnimationCallback@" + mId
                    + " Callback=" + mIOnBackInvokedCallback;
        }
    }

    /**
     * Transfers {@link ImeOnBackInvokedCallback}s registered on one {@link ViewRootImpl} to
     * another {@link ViewRootImpl} on focus change.
     *
     * @param previous the previously focused {@link ViewRootImpl}.
     * @param current  the currently focused {@link ViewRootImpl}.
     */
    public void switchRootView(ViewRootImpl previous, ViewRootImpl current) {
        for (ImeOnBackInvokedCallback imeCallback : mImeCallbacks) {
            if (previous != null) {
                previous.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(imeCallback);
            }
            if (current != null) {
                current.getOnBackInvokedDispatcher().registerOnBackInvokedCallbackUnchecked(
                        imeCallback, imeCallback.mPriority);
            }
        }
    }
}
