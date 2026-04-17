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

package android.service.personalcontext.understander;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.service.personalcontext.Flags;
import android.service.personalcontext.IOpCallback;
import android.service.personalcontext.hint.ContextHint;
import android.service.personalcontext.hint.HintFilter;
import android.service.personalcontext.hint.PublishedContextHint;
import android.service.personalcontext.hint.PublishedContextHintWrapper;
import android.service.personalcontext.insight.ContextInsight;
import android.service.personalcontext.insight.ContextInsightWrapper;
import android.service.personalcontext.insight.PublishedContextInsight;
import android.service.personalcontext.insight.PublishedContextInsightWrapper;
import android.service.personalcontext.insight.interaction.InsightEvent;
import android.service.personalcontext.refiner.IGetFilterCallback;
import android.service.personalcontext.refiner.IRefineCallback;
import android.service.personalcontext.refiner.IRefiner;
import android.service.personalcontext.util.BinderRequestProcessor;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * This is the base class for understander services to implement, handling service details.
 *
 * <p>An understander takes hints provided to the Personal Context system and interprets them. This
 * usually means taking one or more hints, running them through models, and generating zero or more
 * {@link ContextInsight}s based on the hints.
 *
 * <p>The Personal Context service will manage the lifetime of this service, and this service may be
 * stopped if not utilized for some time. Services should start as rapidly as possible to minimize
 * latency in the Personal Context workflow.
 *
 * <p>You must declare the service in the AndroidManifest of the app hosting the service with the
 * {@link Manifest.permission#BIND_CONTEXT_COMPONENT_SERVICE} permission, and include an intent
 * filter with the necessary action indicating that it is a {@link ContextUnderstanderService}
 * (android.service.personalcontext.UnderstanderService). The application must have the {@link
 * Manifest.permission#PERSONAL_CONTEXT_RECEIVE_HINTS} and {@link
 * Manifest.permission#PERSONAL_CONTEXT_PUBLISH_INSIGHTS} permissions.
 *
 * <p>For example:
 *
 * <pre>
 *     &lt;uses-permission
 *         android:name="android.permission.PERSONAL_CONTEXT_RECEIVE_HINTS"/&gt;
 *     &lt;uses-permission
 *         android:name="android.permission.PERSONAL_CONTEXT_PUBLISH_INSIGHTS"/&gt;
 *
 *     &lt;service android:name=".ExampleContextUnderstanderService"
 *             android:exported="true"
 *             android:permission="android.permission.BIND_CONTEXT_COMPONENT_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action
 *             android:name="android.service.personalcontext.UnderstanderService"
 *             /&gt;
 *     &lt;/service&gt;
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_PERSONAL_CONTEXT_SERVICE)
public abstract class ContextUnderstanderService extends Service {
    private static final String TAG = "ContextUnderstanderSvc";

    private UUID mComponentId = null;

    private Executor mBinderExecutor = null;

    private void configure(UUID componentId) {
        if (mComponentId == null) {
            mComponentId = componentId;
            onConnected();
        }
    }

    /**
     * Sets the executor to be used when methods are invoked on this service. By default, an
     * Executor running on the main looper is used. This method should be called within
     * {@link #onCreate()}.
     *
     * @param executor The {@link Executor} to run calls on.
     */
    public final void setExecutor(@androidx.annotation.NonNull Executor executor) {
        mBinderExecutor = executor;
    }

    @NonNull
    private UUID getComponentId() {
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

    /**
     * Called when the understander has been configured and is ready to receive insights.
     * Any actions related to this method should complete before returning.
     */
    public void onConnected() {
        // Default implementation does nothing.
    }

    /**
     * The understander should return a {@link HintFilter} that will be used to filter the
     * hints that this understander's {@link #onUnderstand} method will be called with.
     *
     * The result of this method will be cached and re-used between service bindings. If the filter
     * returned by this method changes, the changes will be ignored.
     *
     * @return a filter that restricts the {@link ContextHint}s this understander will receive
     */
    @NonNull
    public abstract HintFilter onInitializeFilter();

    /**
     * Called when a new hint is available.
     *
     * <p>As each hint is provided to the Personal Context system it will be forwarded on to
     * understanding components. The understander can take these hints, cache them between calls,
     * and use one or more hints together to generate {@link ContextInsight}s.
     *
     * @param hints new hints that this understander has not seen before
     * @return a list of {@link ContextInsight} that this {@link ContextUnderstanderService}
     *         generated by processing the incoming hints.
     */
    @NonNull
    @RequiresPermission(Manifest.permission.PERSONAL_CONTEXT_PUBLISH_INSIGHTS)
    public abstract List<ContextInsight> onUnderstand(@NonNull List<PublishedContextHint>
            hints);

    /**
     * Override this method to receive logging events for actions taken on the insight.
     *
     * <p>Invoked when an event has been reported on a {@link ContextInsight} originally
     * published by this {@link ContextUnderstanderService}.
     *
     * @param packageName the package of the application reporting the event.
     * @param event       The reported {@link InsightEvent}.
     */
    public void onHandleEvent(@NonNull String packageName, @NonNull InsightEvent event) {
        // Do nothing by default.
    }

    /**
     * Override this method to receive user feedback on an insight.
     *
     * @param insight  {@link ContextInsight} that the user feedback is related to
     * @param feedback information about the requested feedback and user responses
     */
    public void onHandleUserFeedback(@NonNull PublishedContextInsight insight,
            @NonNull Bundle feedback) {
        // Do nothing by default.
    }

    private static final class Binder extends IRefiner.Stub {
        private final BinderRequestProcessor<ContextUnderstanderService> mRequestProcessor;

        private Binder(ContextUnderstanderService service, Executor executor) {
            mRequestProcessor = new BinderRequestProcessor.Builder<>(service, executor)
                    .setInitializer(ContextUnderstanderService::configure)
                    .build();
        }

        @Override
        public void refine(
                ParcelUuid componentId,
                List<PublishedContextHintWrapper> inputHints, IRefineCallback callback,
                IOpCallback opCallback) {
            mRequestProcessor.execute(
                    new BinderRequestProcessor.ExecutionParams.Builder<ContextUnderstanderService>(
                            opCallback, serviceInstance -> {
                        callback.onHintsRefined(Collections.emptyList());
                        final List<ContextInsight> insights = serviceInstance.onUnderstand(
                                PublishedContextHintWrapper.unwrapList(inputHints));
                        callback.onUnderstood(ContextInsightWrapper.wrapList(insights));
                    }).setComponentId(componentId).build());
        }

        @Override
        public void getFilter(
                ParcelUuid componentId, IGetFilterCallback callback, IOpCallback opCallback) {
            mRequestProcessor.execute(
                    new BinderRequestProcessor.ExecutionParams.Builder<ContextUnderstanderService>(
                            opCallback, serviceInstance -> {
                        callback.updateFilter(serviceInstance.onInitializeFilter());
                    }).setComponentId(componentId).build());
        }

        @Override
        public void handleEvent(ParcelUuid componentId, String packageName, InsightEvent event,
                IOpCallback opCallback) {
            mRequestProcessor.execute(
                    new BinderRequestProcessor.ExecutionParams.Builder<ContextUnderstanderService>(
                            opCallback, serviceInstance -> serviceInstance.onHandleEvent(
                            packageName, event)
                    ).setComponentId(componentId).build());
        }

        @Override
        public void handleFeedback(ParcelUuid componentId, PublishedContextInsightWrapper insight,
                Bundle feedback, IOpCallback opCallback) {
            mRequestProcessor.execute(
                    new BinderRequestProcessor.ExecutionParams.Builder<ContextUnderstanderService>(
                            opCallback, serviceInstance ->
                            serviceInstance.onHandleUserFeedback(
                                    insight.getPublishedContextInsight(),
                                    feedback)
                    ).setComponentId(componentId).build());
        }
    }
}
