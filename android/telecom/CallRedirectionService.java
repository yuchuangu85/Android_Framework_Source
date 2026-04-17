/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.ICallRedirectionAdapter;
import com.android.internal.telecom.ICallRedirectionService;

import com.android.server.telecom.flags.Flags;

/**
 * This service can be implemented to interact between Telecom and its implementor
 * for making outgoing call with optional redirection/cancellation purposes.
 *
 * <p>
 * Below is an example manifest registration for a {@code CallRedirectionService}.
 * {@code
 * <service android:name="your.package.YourCallRedirectionServiceImplementation"
 *          android:permission="android.permission.BIND_CALL_REDIRECTION_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telecom.CallRedirectionService"/>
 *      </intent-filter>
 * </service>
 * }
 */
public abstract class CallRedirectionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.CallRedirectionService";

    /**
     * An adapter to inform Telecom the response from the implementor of the Call
     * Redirection service
     */
    private ICallRedirectionAdapter mCallRedirectionAdapter;

    /**
     * Telecom calls this method once upon binding to a {@link CallRedirectionService} to inform
     * it of a new outgoing call which is being placed. Telecom does not request to redirect
     * emergency calls and does not request to redirect calls with gateway information.
     *
     * <p>Telecom will cancel the call if Telecom does not receive a response in 5 seconds from
     * the implemented {@link CallRedirectionService} set by users.
     *
     * <p>The implemented {@link CallRedirectionService} can call {@link #placeCallUnmodified()},
     * {@link #redirectCall(Uri, PhoneAccountHandle, boolean)}, and {@link #cancelCall()} only
     * from here. Calls to these methods are assumed by the Telecom framework to be the response
     * for the phone call for which {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} was
     * invoked by Telecom. The Telecom framework will only invoke
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} once each time it binds to a
     * {@link CallRedirectionService}.
     *
     * @param handle the phone number dialed by the user, represented in E.164 format if possible
     * @param initialPhoneAccount the {@link PhoneAccountHandle} on which the call will be placed.
     * @param allowInteractiveResponse a boolean to tell if the implemented
     *                                 {@link CallRedirectionService} should allow interactive
     *                                 responses with users. Will be {@code false} if, for example
     *                                 the device is in car mode and the user would not be able to
     *                                 interact with their device.
     */
    public abstract void onPlaceCall(@NonNull Uri handle,
                                     @NonNull PhoneAccountHandle initialPhoneAccount,
                                     boolean allowInteractiveResponse);
    /**
     * Telecom calls this method once upon binding to a {@link CallRedirectionService} to inform
     * it of a new outgoing call which is being placed. Telecom does not request to redirect
     * emergency calls and does not request to redirect calls with gateway information.
     *
     * <p>Telecom will cancel the call if Telecom does not receive a response in 5 seconds from
     * the implemented {@link CallRedirectionService} set by users.
     *
     * <p>The implemented {@link CallRedirectionService} can call {@link #placeCallUnmodified()},
     * {@link #redirectCall(Uri, PhoneAccountHandle, boolean)}, and {@link #cancelCall()} only
     * from here. Calls to these methods are assumed by the Telecom framework to be the response
     * for the phone call for which {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} was
     * invoked by Telecom. The Telecom framework will only invoke
     * {@link #onPlaceCall(Uri, Uri, PhoneAccountHandle, boolean)} once each time it binds to a
     * {@link CallRedirectionService}.
     *
     * <p> This method is same as {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} above,
     * but includes the original dial string.
     *
     * @param handle the phone number dialed by the user, represented in E.164 format
     * @param originalHandle the phone number dialed by the user in the original format it was
     * dialed (i.e. not E.164 format).  This is helpful for numbers in some regions where
     * formatting to E.164 can cause the loss of suffix digits that the service needs to know
     * about.
     * @param initialPhoneAccount the {@link PhoneAccountHandle} on which the call will be placed.
     * @param allowInteractiveResponse a boolean to tell if the implemented
     *                                 {@link CallRedirectionService} should allow interactive
     *                                 responses with users. Will be {@code false} if, for example
     *                                 the device is in car mode and the user would not be able to
     *                                 interact with their device.
     */

    @FlaggedApi(android.telecom.flags.Flags.FLAG_SEND_ORIGINAL_NUMBER_ON_PLACE_CALL)
    public void onPlaceCall(@NonNull Uri handle, @NonNull Uri originalHandle,
                            @NonNull PhoneAccountHandle initialPhoneAccount,
                            boolean allowInteractiveResponse) {

        onPlaceCall(handle, initialPhoneAccount, allowInteractiveResponse);

    }

    /**
     * Telecom calls this method when times out waiting for the {@link CallRedirectionService} to
     * call {@link #placeCallUnmodified()}, {@link #redirectCall(Uri, PhoneAccountHandle, boolean)},
     * or {@link #cancelCall()}
     */
    public void onRedirectionTimeout() {}

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * no changes are required to the outgoing call, and that the call should be placed as-is.
     *
     * <p>This can only be called from implemented
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}. The response corresponds to the
     * latest request via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}.
     *
     */
    public final void placeCallUnmodified() {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }
            mCallRedirectionAdapter.placeCallUnmodified();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * changes are required to the phone number or/and {@link PhoneAccountHandle} for the outgoing
     * call. Telecom will cancel the call if the implemented {@link CallRedirectionService}
     * replies Telecom a handle for an emergency number.
     * <p>
     * Note: The {@code targetPhoneAccount} can only be used to place a call via a
     * {@link PhoneAccount} with {@link PhoneAccount#CAPABILITY_SIM_SUBSCRIPTION}.
     *
     * <p>This can only be called from implemented
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}. The response corresponds to the
     * latest request via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}.
     *
     * @param gatewayUri the gateway uri for call redirection.
     * @param targetPhoneAccount the {@link PhoneAccountHandle} to use when placing the call.
     * @param confirmFirst Telecom will ask users to confirm the redirection via a yes/no dialog
     *                     if the confirmFirst is true, and if the redirection request of this
     *                     response was sent with a true flag of allowInteractiveResponse via
     *                     {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}
     */
    public final void redirectCall(@NonNull Uri gatewayUri,
                                   @NonNull PhoneAccountHandle targetPhoneAccount,
                                   boolean confirmFirst) {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }
            mCallRedirectionAdapter.redirectCall(gatewayUri, targetPhoneAccount, confirmFirst);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to respond to a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * the call should be placed to a different number entirely.
     * <p>
     * This is in contrast to {@link #redirectCall(Uri, PhoneAccountHandle, boolean)} which places
     * the call on the mobile network via a gateway number but still shows the original dialed
     * number to the user in the Dialer app. This method places the call to the specified number and
     * also shows that number to the user.
     * <p>
     * This is useful for apps which perform number rewriting to add dialing prefixes and the like.
     * <p>
     * Note: The {@code targetPhoneAccount} can only be used to place a call via a
     * {@link PhoneAccount} with {@link PhoneAccount#CAPABILITY_SIM_SUBSCRIPTION}.
     *
     * @param alternateUri the alternate number to place the call to and to show to the user.
     * @param targetPhoneAccount the {@link PhoneAccountHandle} to use when placing the call.
     * @param confirmFirst Telecom will ask users to confirm the redirection via a yes/no dialog
     *                     if the confirmFirst is true, and if the redirection request of this
     *                     response was sent with a true flag of allowInteractiveResponse via
     *                     {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}
     */
    @FlaggedApi(android.telecom.flags.Flags.FLAG_PLACE_CALL_TO_ALTERNATE_NUMBER)
    public final void placeCallToAlternateNumber(@NonNull Uri alternateUri,
                                                 @NonNull PhoneAccountHandle targetPhoneAccount,
                                                 boolean confirmFirst) {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }

            if (alternateUri == null) {
                throw new IllegalArgumentException("alternateUri must be non-null");
            }

            mCallRedirectionAdapter.placeCallToAlternateNumber(alternateUri,
                                                               targetPhoneAccount,
                                                               confirmFirst);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * an outgoing call should be canceled entirely.
     *
     * <p>This can only be called from implemented
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}. The response corresponds to the
     * latest request via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}.
     *
     */
    public final void cancelCall() {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }
            mCallRedirectionAdapter.cancelCall();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * A handler message to process the attempt to place call with redirection service from Telecom
     */
    private static final int MSG_PLACE_CALL = 1;

    /**
     * A handler message to process the attempt to notify the operation of redirection service timed
     * out from Telecom
     */
    private static final int MSG_TIMEOUT = 2;

    /**
     * A handler to process the attempt to place call with redirection service from Telecom
     */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PLACE_CALL:
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mCallRedirectionAdapter = (ICallRedirectionAdapter) args.arg1;
                        onPlaceCall((Uri) args.arg2, (Uri) args.arg3,
                        (PhoneAccountHandle) args.arg4, (boolean) args.arg5);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_TIMEOUT:
                    onRedirectionTimeout();
                    break;
            }
        }
    };

    private final class CallRedirectionBinder extends ICallRedirectionService.Stub {

        /**
         * Telecom calls this method to inform the CallRedirectionService of a new outgoing call
         * which is about to be placed.
         * @param handle the phone number dialed by the user
         * @param initialPhoneAccount the URI of the number the user dialed
         * @param allowInteractiveResponse a boolean to tell if the implemented
         *                                 {@link CallRedirectionService} should allow interactive
         *                                 responses with users.
         */
        @Override
        public void placeCall(@NonNull ICallRedirectionAdapter adapter, @NonNull Uri handle,
                              @NonNull Uri originalHandle,
                              @NonNull PhoneAccountHandle initialPhoneAccount,
                              boolean allowInteractiveResponse) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = adapter;
            args.arg2 = handle;
            args.arg3 = originalHandle;
            args.arg4 = initialPhoneAccount;
            args.arg5 = allowInteractiveResponse;
            mHandler.obtainMessage(MSG_PLACE_CALL, args).sendToTarget();
        }

        /**
         * Telecom calls this method to inform the CallRedirectionService of the timeout waiting for
         * it to complete its operation.
         */
        @Override
        public void notifyTimeout() {
            mHandler.obtainMessage(MSG_TIMEOUT).sendToTarget();
        }
    }

    @Override
    public final @Nullable IBinder onBind(@NonNull Intent intent) {
        return new CallRedirectionBinder();
    }

    @Override
    public final boolean onUnbind(@NonNull Intent intent) {
        return false;
    }
}
