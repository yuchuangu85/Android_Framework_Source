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

package android.service.personalcontext.refiner;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.service.personalcontext.Flags;
import android.service.personalcontext.IOpCallback;
import android.service.personalcontext.PersonalContextManager;
import android.service.personalcontext.hint.ContextHint;
import android.service.personalcontext.hint.ContextHintWrapper;
import android.service.personalcontext.hint.HintFilter;
import android.service.personalcontext.hint.PublishedContextHint;
import android.service.personalcontext.hint.PublishedContextHintWrapper;
import android.service.personalcontext.insight.PublishedContextInsightWrapper;
import android.service.personalcontext.insight.interaction.InsightEvent;
import android.service.personalcontext.util.BinderRequestProcessor;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * This is the base class for refiner services to implement, handling service details.
 *
 * <p>A refiner provides more information to the Personal Context system by adding {@link
 * ContextHint}s. These hints can be created with no basis on existing hints (e.g. a device
 * screenshot), or they can be derived from other hints (e.g. OCR text from a screenshot). Generated
 * hints will be forwarded to understanding components to be analyzed, and to other refiners that
 * may be interested in them.
 *
 * <p>A refiner receives incoming hints based on the filter specified in {@link
 * #onInitializeFilter()}. While a refiner only processes a particular {@link ContextHint} once, it
 * may encounter {@link ContextHint} derived by another refiner from a previously seen {@link
 * ContextHint} if it matches the filter as well.
 *
 * <p>The Personal Context service will manage the lifetime of this service, and this service may be
 * stopped if not utilized for some time. Services should start as rapidly as possible to minimize
 * latency in the Personal Context workflow.
 *
 * <p>You must declare the service in the AndroidManifest of the app hosting the service with the
 * {@link android.Manifest.permission#BIND_CONTEXT_COMPONENT_SERVICE} permission, and include an
 * intent filter with the necessary action indicating that it is a {@link HintRefinerService}
 * (android.service.personalcontext.RefinerService). The application must have the {@link
 * android.Manifest.permission#PERSONAL_CONTEXT_RECEIVE_HINTS} and {@link
 * android.Manifest.permission#PERSONAL_CONTEXT_PUBLISH_HINTS} permissions.
 *
 * <p>For example:
 *
 * <pre>
 *     &lt;uses-permission
 *         android:name="android.permission.PERSONAL_CONTEXT_RECEIVE_HINTS"/&gt;
 *     &lt;uses-permission
 *         android:name="android.permission.PERSONAL_CONTEXT_PUBLISH_HINTS"/&gt;
 *
 *     &lt;service android:name=".ExampleHintRefinerService"
 *             android:exported="true"
 *             android:permission="android.permission.BIND_CONTEXT_COMPONENT_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action
 *             android:name="android.service.personalcontext.RefinerService"
 *             /&gt;
 *     &lt;/service&gt;
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_PERSONAL_CONTEXT_SERVICE)
public abstract class HintRefinerService extends Service {
    private static final String TAG = "HintRefinerService";

    private UUID mComponentId = null;
    private Executor mBinderExecutor = null;

    /** @hide */
    @NonNull
    public final UUID getComponentId() {
        if (mComponentId == null) {
            throw new IllegalStateException("Service has not been configured yet");
        }
        return mComponentId;
    }

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        final Executor executor = mBinderExecutor != null
                ? mBinderExecutor
                : new HandlerExecutor(new Handler(Looper.getMainLooper()));
        return new Binder(this, executor);
    }

    private void configure(UUID componentId) {
        if (mComponentId == null) {
            mComponentId = componentId;
            onConnected();
        }
    }

    /**
     * Called when the refiner has been configured and is ready to receive insights.
     * Any actions related to this method should complete before returning.
     */
    public void onConnected() {
        // Default implementation does nothing.
    }

    /**
     * Sets the executor to be used when methods are invoked on this service. By default, an
     * Executor running on the main looper is used. This method should be called within
     * {@link #onCreate()}.
     *
     * @param executor The {@link Executor} to run calls on.
     */
    public final void setExecutor(@NonNull Executor executor) {
        mBinderExecutor = executor;
    }

    /**
     * The refiner must return a {@link HintFilter} that will be used to filter the hints
     * that this refiner's {@link #onRefine} method will be called with.
     *
     * The result of this method will be cached and re-used between service bindings. If the filter
     * returned by this method changes, the changes will be ignored.
     *
     * @return a filter that restricts the {@link ContextHint}s this refiner will receive
     */
    @NonNull
    public abstract HintFilter onInitializeFilter();

    /**
     * Called when there are hints to be generated or refined.
     *
     * <p>Return a list of new {@link ContextHint}s generated from the input hints, or an empty list
     * if there are no new hints.
     * <p>Refiners should not cache hints between calls. Hints that have been delivered before will
     * be ignored.
     * <p>Do *not* use {@link PersonalContextManager#publishTriggeringHint} to provide new hints
     * from a refiner; providing hints this way will cause hints to not be attributed properly and
     * will break some rendering flows.
     *
     * @param inputHints set of hints that this refiner may want to use to derive new hints
     * @return a list of hints produced from this {@link HintRefinerService} processing the input
     *         hints.
     */
    @NonNull
    public abstract List<ContextHint> onRefine(@NonNull List<ContextHint> inputHints);

    private static final class Binder extends IRefiner.Stub {

        private final BinderRequestProcessor<HintRefinerService> mRequestProcessor;

        private Binder(HintRefinerService service, Executor executor) {
            mRequestProcessor = new BinderRequestProcessor.Builder<>(service, executor)
                    .setInitializer(HintRefinerService::configure)
                    .build();
        }

        @Override
        public void refine(
                ParcelUuid componentId,
                List<PublishedContextHintWrapper> inputHints, IRefineCallback callback,
                IOpCallback opCallback) {
            mRequestProcessor.execute(
                    new BinderRequestProcessor.ExecutionParams.Builder<HintRefinerService>(
                            opCallback, serviceInstance -> {
                        callback.onHintsRefined(ContextHintWrapper.wrapList(
                                serviceInstance.onRefine(PublishedContextHint.unwrapList(
                                        PublishedContextHintWrapper.unwrapList(inputHints)))));
                    }).setComponentId(componentId)
                            .build());

        }

        @Override
        public void getFilter(ParcelUuid componentId, IGetFilterCallback callback,
                IOpCallback opCallback) {
            mRequestProcessor.execute(
                    new BinderRequestProcessor.ExecutionParams.Builder<HintRefinerService>(
                            opCallback,
                            serviceInstance -> callback.updateFilter(
                                    serviceInstance.onInitializeFilter())
                    ).setComponentId(componentId)
                            .build());
        }

        @Override
        public void handleEvent(ParcelUuid componentId, String packageName, InsightEvent event,
                IOpCallback opCallback) throws RemoteException {
            try {
                throw new UnsupportedOperationException(
                        "Can not handle insight events in a refiner");
            } finally {
                opCallback.signalCompletion();
            }
        }

        @Override
        public void handleFeedback(ParcelUuid componentId, PublishedContextInsightWrapper insight,
                Bundle feedback, IOpCallback opCallback) throws RemoteException {
            try {
                throw new UnsupportedOperationException(
                        "Can not handle user feedback in a refiner");
            } finally {
                opCallback.signalCompletion();
            }
        }
    }
}
