/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.media.soundtrigger_middleware.Status;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This is a decorator of an {@link ISoundTriggerMiddlewareService}, which enforces correct usage by
 * the client, as well as makes sure that exceptions representing a server malfunction get sent to
 * the client in a consistent manner, which cannot be confused with a client fault.
 * <p>
 * This is intended to extract the non-business logic out of the underlying implementation and thus
 * make it easier to maintain each one of those separate aspects. A design trade-off is being made
 * here, in that this class would need to essentially eavesdrop on all the client-server
 * communication and retain all state known to the client, while the client doesn't necessarily care
 * about all of it, and while the server has its own representation of this information. However,
 * in this case, this is a small amount of data, and the benefits in code elegance seem worth it.
 * There is also some additional cost in employing a simplistic locking mechanism here, but
 * following the same line of reasoning, the benefits in code simplicity outweigh it.
 * <p>
 * Every public method in this class, overriding an interface method, must follow the following
 * pattern:
 * <code><pre>
 * @Override public T method(S arg) {
 *     // Input validation.
 *     ValidationUtil.validateS(arg);
 *     synchronized (this) {
 *         // State validation.
 *         if (...state is not valid for this call...) {
 *             throw new IllegalStateException("State is invalid because...");
 *         }
 *         // From here on, every exception isn't client's fault.
 *         try {
 *             T result = mDelegate.method(arg);
 *             // Update state.;
 *             ...
 *             return result;
 *         } catch (Exception e) {
 *             throw handleException(e);
 *         }
 *     }
 * }
 * </pre></code>
 * Following this patterns ensures a consistent and rigorous handling of all aspects associated
 * with client-server separation.
 * <p>
 * <b>Exception handling approach:</b><br>
 * We make sure all client faults (argument and state validation) happen first, and
 * would throw {@link IllegalArgumentException}/{@link NullPointerException} or {@link
 * IllegalStateException}, respectively. All those exceptions are treated specially by Binder and
 * will get sent back to the client.<br>
 * Once this is done, any subsequent fault is considered either a recoverable (expected) or
 * unexpected server fault. Those will be delivered to the client as a
 * {@link ServiceSpecificException}. {@link RecoverableException}s thrown by the implementation are
 * considered recoverable and will include a specific error code to indicate the problem. Any other
 * exceptions will use the INTERNAL_ERROR code. They may also cause the module to become invalid
 * asynchronously, and the client would be notified via the moduleDied() callback.
 *
 * {@hide}
 */
public class SoundTriggerMiddlewareValidation implements ISoundTriggerMiddlewareInternal, Dumpable {
    private static final String TAG = "SoundTriggerMiddlewareValidation";

    private enum ModuleStatus {
        ALIVE,
        DETACHED,
        DEAD
    }

    private class ModuleState {
        final @NonNull SoundTriggerModuleProperties properties;
        Set<Session> sessions = new HashSet<>();

        private ModuleState(@NonNull SoundTriggerModuleProperties properties) {
            this.properties = properties;
        }
    }

    private AtomicReference<Boolean> mCaptureState = new AtomicReference<>();

    private final @NonNull ISoundTriggerMiddlewareInternal mDelegate;
    private Map<Integer, ModuleState> mModules;

    public SoundTriggerMiddlewareValidation(
            @NonNull ISoundTriggerMiddlewareInternal delegate) {
        mDelegate = delegate;
    }

    /**
     * Generic exception handling for exceptions thrown by the underlying implementation.
     *
     * Would throw any {@link RecoverableException} as a {@link ServiceSpecificException} (passed
     * by Binder to the caller) and <i>any other</i> exception as {@link InternalServerError}
     * (<b>not</b> passed by Binder to the caller).
     * <p>
     * Typical usage:
     * <code><pre>
     * try {
     *     ... Do server operations ...
     * } catch (Exception e) {
     *     throw handleException(e);
     * }
     * </pre></code>
     */
    static @NonNull
    RuntimeException handleException(@NonNull Exception e) {
        if (e instanceof RecoverableException) {
            throw new ServiceSpecificException(((RecoverableException) e).errorCode,
                    e.getMessage());
        }

        Log.wtf(TAG, "Unexpected exception", e);
        throw new ServiceSpecificException(Status.INTERNAL_ERROR, e.getMessage());
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() {
        // Input validation (always valid).

        synchronized (this) {
            // State validation (always valid).

            // From here on, every exception isn't client's fault.
            try {
                SoundTriggerModuleDescriptor[] result = mDelegate.listModules();
                if (mModules == null) {
                    mModules = new HashMap<>(result.length);
                    for (SoundTriggerModuleDescriptor desc : result) {
                        mModules.put(desc.handle, new ModuleState(desc.properties));
                    }
                } else {
                    if (result.length != mModules.size()) {
                        throw new RuntimeException(
                                "listModules must always return the same result.");
                    }
                    for (SoundTriggerModuleDescriptor desc : result) {
                        if (!mModules.containsKey(desc.handle)) {
                            throw new RuntimeException(
                                    "listModules must always return the same result.");
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public @NonNull ISoundTriggerModule attach(int handle,
            @NonNull ISoundTriggerCallback callback) {
        // Input validation.
        Objects.requireNonNull(callback);
        Objects.requireNonNull(callback.asBinder());

        synchronized (this) {
            // State validation.
            if (mModules == null) {
                throw new IllegalStateException(
                        "Client must call listModules() prior to attaching.");
            }
            if (!mModules.containsKey(handle)) {
                throw new IllegalArgumentException("Invalid handle: " + handle);
            }

            // From here on, every exception isn't client's fault.
            try {
                Session session = new Session(handle, callback);
                session.attach(mDelegate.attach(handle, session.getCallbackWrapper()));
                return session;
            } catch (Exception e) {
                throw handleException(e);
            }
        }
    }

    @Override
    public void setCaptureState(boolean active) {
        // This is an internal call. No permissions needed.
        //
        // Normally, we would acquire a lock here. However, we do not access any state here so it
        // is safe to not lock. This call is typically done from a different context than all the
        // other calls and may result in a deadlock if we lock here (between the audio server and
        // the system server).
        try {
            mDelegate.setCaptureState(active);
        } catch (Exception e) {
            throw handleException(e);
        } finally {
            mCaptureState.set(active);
        }
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return mDelegate.toString();
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException(
                "This implementation is not inteded to be used directly with Binder.");
    }

    @Override
    public void dump(PrintWriter pw) {
        synchronized (this) {
            Boolean captureState = mCaptureState.get();
            pw.printf("Capture state is %s\n\n", captureState == null ? "uninitialized"
                    : (captureState ? "active" : "inactive"));
            if (mModules != null) {
                for (int handle : mModules.keySet()) {
                    final ModuleState module = mModules.get(handle);
                    pw.println("=========================================");
                    pw.printf("Module %d\n%s\n", handle,
                            ObjectPrinter.print(module.properties, true, 16));
                    pw.println("=========================================");
                    for (Session session : module.sessions) {
                        session.dump(pw);
                    }
                }
            } else {
                pw.println("Modules have not yet been enumerated.");
            }
        }
        pw.println();

        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(pw);
        }
    }

    /** State of a sound model. */
    static class ModelState {
        ModelState(SoundModel model) {
            this.description = ObjectPrinter.print(model, true, 16);
        }

        ModelState(PhraseSoundModel model) {
            this.description = ObjectPrinter.print(model, true, 16);
        }

        /** Activity state of a sound model. */
        enum Activity {
            /** Model is loaded, recognition is inactive. */
            LOADED,
            /** Model is loaded, recognition is active. */
            ACTIVE,
            /**
             * Model is active as far as the client is concerned, but loaded as far as the
             * layers are concerned. This condition occurs when a recognition event that indicates
             * the recognition for this model arrived from the underlying layer, but had not been
             * delivered to the caller (most commonly, for permission reasons).
             */
            INTERCEPTED,
        }

        /** Activity state. */
        private AtomicInteger mActivityState = new AtomicInteger(Activity.LOADED.ordinal());

        /** Human-readable description of the model. */
        final String description;

        /**
         * A map of known parameter support. A missing key means we don't know yet whether the
         * parameter is supported. A null value means it is known to not be supported. A non-null
         * value indicates the valid value range.
         */
        private Map<Integer, ModelParameterRange> parameterSupport = new HashMap<>();

        private RecognitionConfig mConfig;

        /**
         * Check that the given parameter is known to be supported for this model.
         *
         * @param modelParam The parameter key.
         */
        void checkSupported(int modelParam) {
            if (!parameterSupport.containsKey(modelParam)) {
                throw new IllegalStateException("Parameter has not been checked for support.");
            }
            ModelParameterRange range = parameterSupport.get(modelParam);
            if (range == null) {
                throw new IllegalArgumentException("Paramater is not supported.");
            }
        }

        /**
         * Check that the given parameter is known to be supported for this model and that the given
         * value is a valid value for it.
         *
         * @param modelParam The parameter key.
         * @param value      The value.
         */
        void checkSupported(int modelParam, int value) {
            if (!parameterSupport.containsKey(modelParam)) {
                throw new IllegalStateException("Parameter has not been checked for support.");
            }
            ModelParameterRange range = parameterSupport.get(modelParam);
            if (range == null) {
                throw new IllegalArgumentException("Paramater is not supported.");
            }
            Preconditions.checkArgumentInRange(value, range.minInclusive, range.maxInclusive,
                    "value");
        }

        /**
         * Update support state for the given parameter for this model.
         *
         * @param modelParam The parameter key.
         * @param range      The parameter value range, or null if not supported.
         */
        void updateParameterSupport(int modelParam, @Nullable ModelParameterRange range) {
            parameterSupport.put(modelParam, range);
        }

        Activity getActivityState() {
            return Activity.values()[mActivityState.get()];
        }

        void setActivityState(Activity activity) {
            mActivityState.set(activity.ordinal());
        }

        void setRecognitionConfig(@NonNull RecognitionConfig config) {
            mConfig = config;
        }

        RecognitionConfig getRecognitionConfig() {
            return mConfig;
        }
    }

    /**
     * A wrapper around an {@link ISoundTriggerModule} implementation, to address the same aspects
     * mentioned in {@link SoundTriggerModule} above. This class follows the same conventions.
     */
    private class Session extends ISoundTriggerModule.Stub {
        private ISoundTriggerModule mDelegate;
        // While generally all the fields of this class must be changed under a lock, an exception
        // is made for the specific case of changing a model state from ACTIVE to LOADED, which
        // may happen as result of a recognition callback. This would happen atomically and is
        // necessary in order to avoid deadlocks associated with locking from within callbacks
        // possibly originating from the audio server.
        private @NonNull
        ConcurrentMap<Integer, ModelState> mLoadedModels = new ConcurrentHashMap<>();
        private final int mHandle;
        private ModuleStatus mState = ModuleStatus.ALIVE;
        private final CallbackWrapper mCallbackWrapper;
        private final Identity mOriginatorIdentity;

        Session(int handle, @NonNull ISoundTriggerCallback callback) {
            mCallbackWrapper = new CallbackWrapper(callback);
            mHandle = handle;
            mOriginatorIdentity = IdentityContext.get();
        }

        ISoundTriggerCallback getCallbackWrapper() {
            return mCallbackWrapper;
        }

        void attach(@NonNull ISoundTriggerModule delegate) {
            mDelegate = delegate;
            mModules.get(mHandle).sessions.add(this);
        }

        @Override
        public int loadModel(@NonNull SoundModel model) {
            // Input validation.
            ValidationUtil.validateGenericModel(model);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadModel(model);
                    mLoadedModels.put(handle, new ModelState(model));
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int loadPhraseModel(@NonNull PhraseSoundModel model) {
            // Input validation.
            ValidationUtil.validatePhraseModel(model);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    int handle = mDelegate.loadPhraseModel(model);
                    mLoadedModels.put(handle, new ModelState(model));
                    return handle;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void unloadModel(int modelHandle) {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                if (modelState.getActivityState() != ModelState.Activity.LOADED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for unloading");
                }

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.unloadModel(modelHandle);
                    mLoadedModels.remove(modelHandle);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void startRecognition(int modelHandle, @NonNull RecognitionConfig config) {
            // Input validation.
            ValidationUtil.validateRecognitionConfig(config);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                if (modelState.getActivityState() != ModelState.Activity.LOADED) {
                    throw new IllegalStateException("Model with handle: " + modelHandle
                            + " has invalid state for starting recognition");
                }

                // From here on, every exception isn't client's fault.
                try {
                    // Normally, we would set the state after the operation succeeds. However, since
                    // the activity state may be reset outside of the lock, we set it here first,
                    // and reset it in case of exception.
                    modelState.setRecognitionConfig(config);
                    modelState.setActivityState(ModelState.Activity.ACTIVE);
                    mDelegate.startRecognition(modelHandle, config);
                } catch (Exception e) {
                    modelState.setActivityState(ModelState.Activity.LOADED);
                    throw handleException(e);
                }
            }
        }

        @Override
        public void stopRecognition(int modelHandle) {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // stopRecognition is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    // If the activity state is LOADED or INTERCEPTED, we skip delegating the
                    // command, but still consider the call valid. In either case, the resulting
                    // state is LOADED.
                    if (modelState.getActivityState() == ModelState.Activity.ACTIVE) {
                        mDelegate.stopRecognition(modelHandle);
                    }
                    modelState.setActivityState(ModelState.Activity.LOADED);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        private void restartIfIntercepted(int modelHandle) {
            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    return;
                }
                ModelState modelState = mLoadedModels.get(modelHandle);
                if (modelState == null
                        || modelState.getActivityState() != ModelState.Activity.INTERCEPTED) {
                    return;
                }
                try {
                    mDelegate.startRecognition(modelHandle, modelState.getRecognitionConfig());
                    modelState.setActivityState(ModelState.Activity.ACTIVE);
                    Log.i(TAG, "Restarted intercepted model " + modelHandle);
                } catch (Exception e) {
                    Log.i(TAG, "Failed to restart intercepted model " + modelHandle, e);
                }
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                // forceRecognitionEvent is idempotent - no need to check model state.

                // From here on, every exception isn't client's fault.
                try {
                    // If the activity state is LOADED or INTERCEPTED, we skip delegating the
                    // command, but still consider the call valid.
                    if (modelState.getActivityState() == ModelState.Activity.ACTIVE) {
                        mDelegate.forceRecognitionEvent(modelHandle);
                    }
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value) {
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                modelState.checkSupported(modelParam, value);

                // From here on, every exception isn't client's fault.
                try {
                    mDelegate.setModelParameter(modelHandle, modelParam, value);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) {
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }
                modelState.checkSupported(modelParam);

                // From here on, every exception isn't client's fault.
                try {
                    return mDelegate.getModelParameter(modelHandle, modelParam);
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        @Nullable
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam) {
            // Input validation.
            ValidationUtil.validateModelParameter(modelParam);

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has been detached.");
                }
                ModelState modelState = mLoadedModels.get(
                        modelHandle);
                if (modelState == null) {
                    throw new IllegalStateException("Invalid handle: " + modelHandle);
                }

                // From here on, every exception isn't client's fault.
                try {
                    ModelParameterRange result = mDelegate.queryModelParameterSupport(modelHandle,
                            modelParam);
                    modelState.updateParameterSupport(modelParam, result);
                    return result;
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        @Override
        public void detach() {
            // Input validation (always valid).

            synchronized (SoundTriggerMiddlewareValidation.this) {
                // State validation.
                if (mState == ModuleStatus.DETACHED) {
                    throw new IllegalStateException("Module has already been detached.");
                }
                if (mState == ModuleStatus.ALIVE && !mLoadedModels.isEmpty()) {
                    throw new IllegalStateException("Cannot detach while models are loaded.");
                }

                // From here on, every exception isn't client's fault.
                try {
                    detachInternal();
                } catch (Exception e) {
                    throw handleException(e);
                }
            }
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }

        private void detachInternal() {
            try {
                mDelegate.detach();
                mState = ModuleStatus.DETACHED;
                mCallbackWrapper.detached();
                mModules.get(mHandle).sessions.remove(this);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        void dump(PrintWriter pw) {
            if (mState == ModuleStatus.ALIVE) {
                pw.println("-------------------------------");
                pw.printf("Session %s, client: %s\n", toString(),
                        ObjectPrinter.print(mOriginatorIdentity, true, 16));
                pw.printf("Loaded models (handle, active, description):", toString());
                pw.println();
                pw.println("-------------------------------");
                for (Map.Entry<Integer, ModelState> entry : mLoadedModels.entrySet()) {
                    pw.print(entry.getKey());
                    pw.print('\t');
                    pw.print(entry.getValue().getActivityState().name());
                    pw.print('\t');
                    pw.print(entry.getValue().description);
                    pw.println();
                }
                pw.println();
            } else {
                pw.printf("Session %s is dead", toString());
                pw.println();
            }
        }

        class CallbackWrapper implements ISoundTriggerCallback,
                IBinder.DeathRecipient {
            private final ISoundTriggerCallback mCallback;

            CallbackWrapper(ISoundTriggerCallback callback) {
                mCallback = callback;
                try {
                    mCallback.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
            }

            void detached() {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }

            @Override
        public void onRecognition(int modelHandle, @NonNull RecognitionEvent event) {
            // We cannot obtain a lock on SoundTriggerMiddlewareValidation.this, since this call
            // might be coming from the audio server (via setCaptureState()) while it is holding
            // a lock that is also acquired while loading / unloading models. Thus, we require a
            // strict locking order here, where obtaining our lock must always come first.
            // To avoid this problem, we use an atomic model activity state. There is a risk of the
            // model not being in the mLoadedModels map here, since it might have been stopped /
            // unloaded while the event was in flight.
            ModelState modelState = mLoadedModels.get(modelHandle);
            if (modelState != null) {
                if (event.status != RecognitionStatus.FORCED) {
                    modelState.setActivityState(ModelState.Activity.LOADED);
                }
                try {
                    mCallback.onRecognition(modelHandle, event);
                } catch (Exception e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                    if (event.status != RecognitionStatus.FORCED) {
                        modelState.setActivityState(ModelState.Activity.INTERCEPTED);
                        // If we failed to deliver an actual event to the client, they would never
                        // know to restart it whenever circumstances change. Thus, we restart it
                        // here. We do this from a separate thread to avoid any race conditions.
                        new Thread(() -> restartIfIntercepted(modelHandle)).start();
                    }
                }
            }
        }

        @Override
        public void onPhraseRecognition(int modelHandle, @NonNull PhraseRecognitionEvent event) {
            // We cannot obtain a lock on SoundTriggerMiddlewareValidation.this, since this call
            // might be coming from the audio server (via setCaptureState()) while it is holding
            // a lock that is also acquired while loading / unloading models. Thus, we require a
            // strict locking order here, where obtaining our lock must always come first.
            // To avoid this problem, we use an atomic model activity state. There is a risk of the
            // model not being in the mLoadedModels map here, since it might have been stopped /
            // unloaded while the event was in flight.
            ModelState modelState = mLoadedModels.get(modelHandle);
            if (modelState != null) {
                if (event.common.status != RecognitionStatus.FORCED) {
                        modelState.setActivityState(ModelState.Activity.LOADED);
                }
                try {
                    mCallback.onPhraseRecognition(modelHandle, event);
                } catch (Exception e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                    if (event.common.status != RecognitionStatus.FORCED) {
                        modelState.setActivityState(ModelState.Activity.INTERCEPTED);
                        // If we failed to deliver an actual event to the client, they would never
                        // know to restart it whenever circumstances change. Thus, we restart it
                        // here. We do this from a separate thread to avoid any race conditions.
                        new Thread(() -> restartIfIntercepted(modelHandle)).start();
                    }
                }
            }
        }

        @Override
        public void onRecognitionAvailabilityChange(boolean available) {
            // Not locking to avoid deadlocks (not affecting any state).
            try {
                mCallback.onRecognitionAvailabilityChange(available);
            } catch (RemoteException e) {
                // Dead client will be handled by binderDied() - no need to handle here.
                // In any case, client callbacks are considered best effort.
                Log.e(TAG, "Client callback exception.", e);
            }
        }

            @Override
            public void onModuleDied() {
                synchronized (SoundTriggerMiddlewareValidation.this) {
                    mState = ModuleStatus.DEAD;
                }
                // Trigger the callback outside of the lock to avoid deadlocks.
                try {
                    mCallback.onModuleDied();
                } catch (RemoteException e) {
                    // Dead client will be handled by binderDied() - no need to handle here.
                    // In any case, client callbacks are considered best effort.
                    Log.e(TAG, "Client callback exception.", e);
                }
            }

            @Override
            public void binderDied() {
                // This is called whenever our client process dies.
                synchronized (SoundTriggerMiddlewareValidation.this) {
                    try {
                        // Gracefully stop all active recognitions and unload the models.
                        for (Map.Entry<Integer, ModelState> entry :
                                mLoadedModels.entrySet()) {
                            if (entry.getValue().getActivityState()
                                    == ModelState.Activity.ACTIVE) {
                                mDelegate.stopRecognition(entry.getKey());
                            }
                            mDelegate.unloadModel(entry.getKey());
                        }
                        // Detach.
                        detachInternal();
                    } catch (Exception e) {
                        throw handleException(e);
                    }
                }
            }

            @Override
            public IBinder asBinder() {
                return mCallback.asBinder();
            }

            // Override toString() in order to have the delegate's ID in it.
            @Override
            public String toString() {
                return Objects.toString(mDelegate);
            }
        }
    }
}
