/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.voice;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.media.voice.KeyphraseModelManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SharedMemory;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Top-level service of the current global voice interactor, which is providing
 * support for hotwording, the back-end of a {@link android.app.VoiceInteractor}, etc.
 * The current VoiceInteractionService that has been selected by the user is kept
 * always running by the system, to allow it to do things like listen for hotwords
 * in the background to instigate voice interactions.
 *
 * <p>Because this service is always running, it should be kept as lightweight as
 * possible.  Heavy-weight operations (including showing UI) should be implemented
 * in the associated {@link android.service.voice.VoiceInteractionSessionService} when
 * an actual voice interaction is taking place, and that service should run in a
 * separate process from this one.
 */
public class VoiceInteractionService extends Service {
    static final String TAG = VoiceInteractionService.class.getSimpleName();

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_VOICE_INTERACTION} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.VoiceInteractionService";

    /**
     * Name under which a VoiceInteractionService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#VoiceInteractionService voice-interaction-service}&gt;</code> tag.
     */
    public static final String SERVICE_META_DATA = "android.voice_interaction";

    IVoiceInteractionService mInterface = new IVoiceInteractionService.Stub() {
        @Override
        public void ready() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onReady, VoiceInteractionService.this));
        }

        @Override
        public void shutdown() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onShutdownInternal, VoiceInteractionService.this));
        }

        @Override
        public void soundModelsChanged() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onSoundModelsChangedInternal,
                    VoiceInteractionService.this));
        }

        @Override
        public void launchVoiceAssistFromKeyguard() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    VoiceInteractionService::onLaunchVoiceAssistFromKeyguard,
                    VoiceInteractionService.this));
        }

        @Override
        public void getActiveServiceSupportedActions(List<String> voiceActions,
                IVoiceActionCheckCallback callback) {
            Handler.getMain().executeOrSendMessage(
                    PooledLambda.obtainMessage(VoiceInteractionService::onHandleVoiceActionCheck,
                            VoiceInteractionService.this,
                            voiceActions,
                            callback));
        }
    };

    IVoiceInteractionManagerService mSystemService;

    private final Object mLock = new Object();

    private KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;

    private AlwaysOnHotwordDetector mHotwordDetector;
    private SoftwareHotwordDetector mSoftwareHotwordDetector;

    /**
     * Called when a user has activated an affordance to launch voice assist from the Keyguard.
     *
     * <p>This method will only be called if the VoiceInteractionService has set
     * {@link android.R.attr#supportsLaunchVoiceAssistFromKeyguard} and the Keyguard is showing.</p>
     *
     * <p>A valid implementation must start a new activity that should use {@link
     * android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED} to display
     * on top of the lock screen.</p>
     */
    public void onLaunchVoiceAssistFromKeyguard() {
    }

    /**
     * Check whether the given service component is the currently active
     * VoiceInteractionService.
     */
    public static boolean isActiveService(Context context, ComponentName service) {
        String cur = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.VOICE_INTERACTION_SERVICE);
        if (cur == null || cur.isEmpty()) {
            return false;
        }
        ComponentName curComp = ComponentName.unflattenFromString(cur);
        if (curComp == null) {
            return false;
        }
        return curComp.equals(service);
    }

    /**
     * Set contextual options you would always like to have disabled when a session
     * is shown.  The flags may be any combination of
     * {@link VoiceInteractionSession#SHOW_WITH_ASSIST VoiceInteractionSession.SHOW_WITH_ASSIST} and
     * {@link VoiceInteractionSession#SHOW_WITH_SCREENSHOT
     * VoiceInteractionSession.SHOW_WITH_SCREENSHOT}.
     */
    public void setDisabledShowContext(int flags) {
        try {
            mSystemService.setDisabledShowContext(flags);
        } catch (RemoteException e) {
        }
    }

    /**
     * Return the value set by {@link #setDisabledShowContext}.
     */
    public int getDisabledShowContext() {
        try {
            return mSystemService.getDisabledShowContext();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * Request that the associated {@link android.service.voice.VoiceInteractionSession} be
     * shown to the user, starting it if necessary.
     * @param args Arbitrary arguments that will be propagated to the session.
     * @param flags Indicates additional optional behavior that should be performed.  May
     * be any combination of
     * {@link VoiceInteractionSession#SHOW_WITH_ASSIST VoiceInteractionSession.SHOW_WITH_ASSIST} and
     * {@link VoiceInteractionSession#SHOW_WITH_SCREENSHOT
     * VoiceInteractionSession.SHOW_WITH_SCREENSHOT}
     * to request that the system generate and deliver assist data on the current foreground
     * app as part of showing the session UI.
     */
    public void showSession(Bundle args, int flags) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        try {
            mSystemService.showSession(args, flags);
        } catch (RemoteException e) {
        }
    }

    /**
     * Request to query for what extended voice actions this service supports. This method will
     * be called when the system checks the supported actions of this
     * {@link VoiceInteractionService}. Supported actions may be delivered to
     * {@link VoiceInteractionSession} later to request a session to perform an action.
     *
     * <p>Voice actions are defined in support libraries and could vary based on platform context.
     * For example, car related voice actions will be defined in car support libraries.
     *
     * @param voiceActions A set of checked voice actions.
     * @return Returns a subset of checked voice actions. Additional voice actions in the
     * returned set will be ignored. Returns empty set if no actions are supported.
     */
    @NonNull
    public Set<String> onGetSupportedVoiceActions(@NonNull Set<String> voiceActions) {
        return Collections.emptySet();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        return null;
    }

    /**
     * Called during service initialization to tell you when the system is ready
     * to receive interaction from it. You should generally do initialization here
     * rather than in {@link #onCreate}. Methods such as {@link #showSession} will
     * not be operational until this point.
     */
    public void onReady() {
        mSystemService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
        Objects.requireNonNull(mSystemService);
        try {
            mSystemService.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.wtf(TAG, "unable to link to death with system service");
        }
        mKeyphraseEnrollmentInfo = new KeyphraseEnrollmentInfo(getPackageManager());
    }

    private IBinder.DeathRecipient mDeathRecipient = () -> {
        Log.e(TAG, "system service binder died shutting down");
        Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                VoiceInteractionService::onShutdownInternal, VoiceInteractionService.this));
    };


    private void onShutdownInternal() {
        onShutdown();
        // Stop any active recognitions when shutting down.
        // This ensures that if implementations forget to stop any active recognition,
        // It's still guaranteed to have been stopped.
        // This helps with cases where the voice interaction implementation is changed
        // by the user.
        safelyShutdownAllHotwordDetectors();
    }

    /**
     * Called during service de-initialization to tell you when the system is shutting the
     * service down.
     * At this point this service may no longer be the active {@link VoiceInteractionService}.
     */
    public void onShutdown() {
    }

    private void onSoundModelsChangedInternal() {
        synchronized (this) {
            if (mHotwordDetector != null) {
                // TODO: Stop recognition if a sound model that was being recognized gets deleted.
                mHotwordDetector.onSoundModelsChanged();
            }
        }
    }

    private void onHandleVoiceActionCheck(List<String> voiceActions,
            IVoiceActionCheckCallback callback) {
        if (callback != null) {
            try {
                Set<String> voiceActionsSet = new ArraySet<>(voiceActions);
                Set<String> resultSet = onGetSupportedVoiceActions(voiceActionsSet);
                callback.onComplete(new ArrayList<>(resultSet));
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Creates an {@link AlwaysOnHotwordDetector} for the given keyphrase and locale.
     * This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            @SuppressLint("MissingNullability") String keyphrase,  // TODO: nullability properly
            @SuppressLint({"MissingNullability", "UseIcu"}) Locale locale,
            @SuppressLint("MissingNullability") AlwaysOnHotwordDetector.Callback callback) {
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ false, /* options= */ null,
                /* sharedMemory= */ null, callback);
    }

    /**
     * Create an {@link AlwaysOnHotwordDetector} and trigger a {@link HotwordDetectionService}
     * service, then it will also pass the read-only data to hotword detection service.
     *
     * Like {@see #createAlwaysOnHotwordDetector(String, Locale, AlwaysOnHotwordDetector.Callback)
     * }. Before calling this function, you should set a valid hotword detection service with
     * android:hotwordDetectionService in an android.voice_interaction metadata file and set
     * android:isolatedProcess="true" in the AndroidManifest.xml of hotword detection service.
     * Otherwise it will throw IllegalStateException. After calling this function, the system will
     * also trigger a hotword detection service and pass the read-only data back to it.
     *
     * <p>Note: The system will trigger hotword detection service after calling this function when
     * all conditions meet the requirements.
     *
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     * @param options Application configuration data provided by the
     * {@link VoiceInteractionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob provided by the
     * {@link VoiceInteractionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(
            @SuppressLint("MissingNullability") String keyphrase,  // TODO: nullability properly
            @SuppressLint({"MissingNullability", "UseIcu"}) Locale locale,
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @SuppressLint("MissingNullability") AlwaysOnHotwordDetector.Callback callback) {
        return createAlwaysOnHotwordDetectorInternal(keyphrase, locale,
                /* supportHotwordDetectionService= */ true, options,
                sharedMemory, callback);
    }

    private AlwaysOnHotwordDetector createAlwaysOnHotwordDetectorInternal(
            @SuppressLint("MissingNullability") String keyphrase,  // TODO: nullability properly
            @SuppressLint({"MissingNullability", "UseIcu"}) Locale locale,
            boolean supportHotwordDetectionService,
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @SuppressLint("MissingNullability") AlwaysOnHotwordDetector.Callback callback) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            // Allow only one concurrent recognition via the APIs.
            safelyShutdownAllHotwordDetectors();
            mHotwordDetector = new AlwaysOnHotwordDetector(keyphrase, locale, callback,
                    mKeyphraseEnrollmentInfo, mSystemService,
                    getApplicationContext().getApplicationInfo().targetSdkVersion,
                    supportHotwordDetectionService, options, sharedMemory);
            mHotwordDetector.registerOnDestroyListener((detector) -> onDspHotwordDetectorDestroyed(
                    (AlwaysOnHotwordDetector) detector));
        }
        return mHotwordDetector;
    }

    /**
     * Creates a {@link HotwordDetector} and initializes the application's
     * {@link HotwordDetectionService} using {@code options} and {code sharedMemory}.
     *
     * <p>To be able to call this, you need to set android:hotwordDetectionService in the
     * android.voice_interaction metadata file to a valid hotword detection service, and set
     * android:isolatedProcess="true" in the hotword detection service's declaration. Otherwise,
     * this throws an {@link IllegalStateException}.
     *
     * <p>This instance must be retained and used by the client.
     * Calling this a second time invalidates the previously created hotword detector
     * which can no longer be used to manage recognition.
     *
     * <p>Using this has a noticeable impact on battery, since the microphone is kept open
     * for the lifetime of the recognition {@link HotwordDetector#startRecognition() session}. On
     * devices where hardware filtering is available (such as through a DSP), it's highly
     * recommended to use {@link #createAlwaysOnHotwordDetector} instead.
     *
     * @param options Application configuration data to be provided to the
     * {@link HotwordDetectionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to be provided to the
     * {@link HotwordDetectionService}. Use this to provide hotword models or other such data to the
     * sandboxed process.
     * @param callback The callback to notify of detection events.
     * @return A hotword detector for the given audio format.
     *
     * @see #createAlwaysOnHotwordDetector(String, Locale, PersistableBundle, SharedMemory,
     * AlwaysOnHotwordDetector.Callback)
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_HOTWORD_DETECTION)
    @NonNull
    public final HotwordDetector createHotwordDetector(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull HotwordDetector.Callback callback) {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            // Allow only one concurrent recognition via the APIs.
            safelyShutdownAllHotwordDetectors();
            mSoftwareHotwordDetector =
                    new SoftwareHotwordDetector(
                            mSystemService, null, options, sharedMemory, callback);
            mSoftwareHotwordDetector.registerOnDestroyListener(
                    (detector) -> onMicrophoneHotwordDetectorDestroyed(
                            (SoftwareHotwordDetector) detector));
        }
        return mSoftwareHotwordDetector;
    }

    /**
     * Creates an {@link KeyphraseModelManager} to use for enrolling voice models outside of the
     * pre-bundled system voice models.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
    @NonNull
    public final KeyphraseModelManager createKeyphraseModelManager() {
        if (mSystemService == null) {
            throw new IllegalStateException("Not available until onReady() is called");
        }
        synchronized (mLock) {
            return new KeyphraseModelManager(mSystemService);
        }
    }

    /**
     * @return Details of keyphrases available for enrollment.
     * @hide
     */
    @VisibleForTesting
    protected final KeyphraseEnrollmentInfo getKeyphraseEnrollmentInfo() {
        return mKeyphraseEnrollmentInfo;
    }

    /**
     * Checks if a given keyphrase and locale are supported to create an
     * {@link AlwaysOnHotwordDetector}.
     *
     * @return true if the keyphrase and locale combination is supported, false otherwise.
     * @hide
     */
    @UnsupportedAppUsage
    public final boolean isKeyphraseAndLocaleSupportedForHotword(String keyphrase, Locale locale) {
        if (mKeyphraseEnrollmentInfo == null) {
            return false;
        }
        return mKeyphraseEnrollmentInfo.getKeyphraseMetadata(keyphrase, locale) != null;
    }

    private void safelyShutdownAllHotwordDetectors() {
        synchronized (mLock) {
            if (mHotwordDetector != null) {
                try {
                    mHotwordDetector.destroy();
                } catch (Exception ex) {
                    Log.i(TAG, "exception destroying AlwaysOnHotwordDetector", ex);
                }
            }

            if (mSoftwareHotwordDetector != null) {
                try {
                    mSoftwareHotwordDetector.destroy();
                } catch (Exception ex) {
                    Log.i(TAG, "exception destroying SoftwareHotwordDetector", ex);
                }
            }
        }
    }

    private void onDspHotwordDetectorDestroyed(@NonNull AlwaysOnHotwordDetector detector) {
        synchronized (mLock) {
            mHotwordDetector = null;
        }
    }

    private void onMicrophoneHotwordDetectorDestroyed(@NonNull SoftwareHotwordDetector detector) {
        synchronized (mLock) {
            mSoftwareHotwordDetector = null;
        }
    }

    /**
     * Provide hints to be reflected in the system UI.
     *
     * @param hints Arguments used to show UI.
     */
    public final void setUiHints(@NonNull Bundle hints) {
        if (hints == null) {
            throw new IllegalArgumentException("Hints must be non-null");
        }

        try {
            mSystemService.setUiHints(hints);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("VOICE INTERACTION");
        synchronized (mLock) {
            pw.println("  AlwaysOnHotwordDetector");
            if (mHotwordDetector == null) {
                pw.println("    NULL");
            } else {
                mHotwordDetector.dump("    ", pw);
            }

            pw.println("  MicrophoneHotwordDetector");
            if (mSoftwareHotwordDetector == null) {
                pw.println("    NULL");
            } else {
                mSoftwareHotwordDetector.dump("    ", pw);
            }
        }
    }
}
