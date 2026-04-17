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

package android.telecom;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.telecom.ILocalVoicemailService;
import com.android.internal.telecom.ILocalVoicemailServiceAdapter;
import com.android.modules.utils.HandlerExecutor;
import com.android.server.telecom.flags.Flags;

import java.util.concurrent.Executor;

/**
 * A pre-loaded application can provide an implementation of {@link LocalVoicemailService} in order
 * to provide on-device voicemail capabilities.
 * <p>
 * Local voicemail is triggered when:
 * <ul>
 *      <ol>When a call has been in a {@link android.telecom.Call#STATE_RINGING} state and the user
 *      does not answer it within a period of time.</ol>
 *      <ol>When the user rejects the call via {@link android.telecom.Call#reject(int)}.</ol>
 * </ul>
 * <p>
 * When local voicemail is triggered, Telecom calls
 * {@link #onVoicemailRequested(AudioTrack, AudioRecord, Call.Details)} so that the voicemail app
 * can perform voicemail operations.
 * <p>
 * The voicemail app can call {@link #disconnectCall()} to terminate the call.  The voicemail
 * service may want to do this to limit the length of incoming messages.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(android.telecom.flags.Flags.FLAG_LOCAL_VOICEMAIL)
public abstract class LocalVoicemailService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.LocalVoicemailService";

    private ILocalVoicemailServiceAdapter mAdapter;
    private String mCallId;
    private Executor mExecutor;

    /**
     * Handles incoming requests from Telecom to the {@link LocalVoicemailService}.
     */
    private final class LocalVoicemailServiceBinder extends ILocalVoicemailService.Stub {
        @Override
        public void setAdapter(ILocalVoicemailServiceAdapter adapter) throws RemoteException {
            Log.i(LocalVoicemailService.this, "setAdapter");
            final long token = Binder.clearCallingIdentity();
            try {
                getExecutor().execute(() -> {
                    mAdapter = adapter;
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void startLocalVoicemail(ParcelableCall call) throws RemoteException {
            Log.i(LocalVoicemailService.this, "startLocalVoicemail: " + call.getId());
            final long token = Binder.clearCallingIdentity();
            try {
                getExecutor().execute(() -> {
                    handleLocalVoicemailRequest(call);
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void stopLocalVoicemail(ParcelableCall call) throws RemoteException {
            Log.i(LocalVoicemailService.this, "stopLocalVoicemail: " + call.getId());
            final long token = Binder.clearCallingIdentity();
            try {
                getExecutor().execute(() -> {
                    onVoicemailStopped(Call.Details.createFromParcelableCall(call));
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    public LocalVoicemailService() {
    }

    @Override
    public @Nullable IBinder onBind(@Nullable Intent intent) {
        Log.i(this, "onBind");
        return new LocalVoicemailServiceBinder();
    }

    /**
     * Override this method so that your service can provide its own {@link Executor} on which the
     * incoming request from Telecom take place.  If not overridden, a main looper executor is used.
     * @return the {@link Executor} to handle incoming requests on.
     */
    @SuppressLint("OnNameExpected")
    @NonNull public Executor getExecutor() {
        if (mExecutor == null) {
            mExecutor = new HandlerExecutor(Handler.createAsync(getMainLooper()));
        }
        return mExecutor;
    }

    /**
     * Disconnects the current call and stops local voicemail processing.  The
     * {@link LocalVoicemailService} is unbound after this method is called.
     */
    public final void disconnectCall() {
        try {
            mAdapter.disconnectCall(mCallId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Implement this method to handle a request from Telecom to perform local voicemail for a call.
     * <p>
     * There can ONLY be a single local voicemail session taking place at a time.  Telecom will call
     * this method when either:
     * <ul>
     *     <li>The user did not answer the call before the voicemail timeout.</li>
     *     <li>The user rejected the call in the Dialer app.</li>
     * </ul>
     * <p>
     * Local voicemail usually involves playing a greeting to the caller; this is done by playing
     * the greeting onto the {@code uplinkInjectionTrack}.  Once the greeting is played, you should
     * record the message from the {@code downlinkExtractionTrack}.
     * <p>
     * Note: Your service must have the required permissions or the platform will not bind to it.
     * <p>
     * Use {@link AudioManager#getCallDownlinkExtractionAudioRecord(AudioFormat)} to get an
     * {@link AudioRecord} which you can use to access the incoming call audio.  Use
     * {@link AudioManager#getCallUplinkInjectionAudioTrack(AudioFormat)} to get an
     * {@link AudioTrack} which you can use to send audio out to the call.
     * <p>
     * Below is an example; be aware that {@link AudioManager} places restrictions on the formats
     * which can be used and will throw {@link UnsupportedOperationException} if you provide an
     * invalid format.
     * <pre>
     * {@code
     *     AudioManager audioManager = getApplicationContext().getSystemService(
     *             AudioManager.class);
     *     AudioFormat formatOut = new AudioFormat.Builder().setSampleRate(16000)
     *             .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
     *             .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
     *     AudioTrack uplinkInjectionTrack = audioManager.getCallUplinkInjectionAudioTrack(
     *             formatOut);
     *     AudioFormat formatIn = new AudioFormat.Builder().setSampleRate(16000)
     *             .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
     *             .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
     *     AudioRecord downlinkExtractionTrack = audioManager.getCallDownlinkExtractionAudioRecord(
     *             formatIn);
     * }
     * </pre>
     * <p>
     * Note: there can ONLY be one local voicemail call at any one time; you can expect that this
     * method will be called only once per binding to your {@link LocalVoicemailService}.
     *
     * @param call information about the incoming call including its phone number.
     */
    @RequiresPermission(allOf = {Manifest.permission.CALL_AUDIO_INTERCEPTION,
            Manifest.permission.MODIFY_AUDIO_ROUTING, Manifest.permission.RECORD_AUDIO})
    public abstract void onVoicemailRequested(@NonNull Call.Details call);

    /**
     * Implement this method in the {@link LocalVoicemailService} to be notified by Telecom when
     * local voicemail processing has stopped for the call undergoing local voicemail.
     * The {@link AudioManager#getMode()} will change from {@link AudioManager#MODE_CALL_REDIRECT}
     * and you will no longer have access to the call uplink and downlink audio tracks.
     * <p>
     * Note: This will only be called if {@link #onVoicemailRequested(Call.Details)} was called, and
     * the {@code call} will be the same.
     *
     * @param call the call which is no longer undergoing local voicemail processing.
     */
    public abstract void onVoicemailStopped(@NonNull Call.Details call);

    /**
     * Relays a request from Telecom to start local voicemail for a call to the app's
     * {@link #onVoicemailRequested(Call.Details)} implementation.
     * @param call Information about the call.
     */
    private void handleLocalVoicemailRequest(ParcelableCall call) {
        mCallId = call.getId();
        onVoicemailRequested(Call.Details.createFromParcelableCall(call));
    }
}
