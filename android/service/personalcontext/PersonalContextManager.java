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

package android.service.personalcontext;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.service.personalcontext.embedded.InsightSurfaceClientInfo;
import android.service.personalcontext.hint.ContextHint;
import android.service.personalcontext.hint.ContextHintWrapper;
import android.service.personalcontext.hint.PublishedContextHint;
import android.service.personalcontext.insight.ContextInsight;
import android.service.personalcontext.insight.ContextInsightWrapper;
import android.service.personalcontext.insight.PublishedContextInsight;
import android.service.personalcontext.insight.interaction.InsightEvent;
import android.service.personalcontext.insight.interaction.ReturnHintReport;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client facing access to the PersonalContext service.
 *
 * <p>The PersonalContext service is a framework for securely gathering device context (such as
 * screen context and device state changes) and delivering it to a set of system configured
 * components to combine and infer personalized information and actions. The personalized contextual
 * information used by the PersonalContext service is implementation specific to the participating
 * component. The resulting output is shown in contextually relevant environment, such as
 * notification replies or auto-fill suggestions.
 *
 * <p>The service accepts a flow of contextual details from activity on the device, such as
 * notifications and screen content. This data is used by a set of components to determine relevant
 * information and suggestions for the user. The incoming data takes the form of various
 * {@link ContextHint} subclasses, each tailored to a particular captured data type. Entities
 * both inside and outside the PersonalContext service through
 * {@link #publishTriggeringHint(List, List)} and its variants.
 *
 * <p>Often times, the publisher might know that the results should be delivered to a particular
 * surface to render. For example, {@link android.service.personalcontext.hint.NotificationHint} can
 * lead to actions or suggestions within the notification shade. In these cases, the surface can
 * be targeted by the publisher to receiving the results by specifying the
 * {@link RenderToken} associated with the surface's renderer.
 *
 * <p>Core PersonalContext entities and roles:
 * <ul>
 *     <li><strong>{@link ContextHint}</strong> implementations are the input into the
 *     PersonalContext service. Each subclass captures domain specific information about a
 *     particular device activity, such as {@link android.service.personalcontext.hint.CallHint}.
 *     </li>
 *     <li><strong>@link {@link ContextInsight}</strong> represents information and actions derived
 *     from {@link ContextHint}s. This information is domain agnostic, allowing for display in
 *     different environments.</li>
 *     <li><strong>{@link android.service.personalcontext.refiner.HintRefinerService}</strong>
 *     receive {@link ContextHint}s and have the opportunity to generate {@link ContextHint}s based
 *     on the input data.</li>
 *     <li><strong>{@link android.service.personalcontext.understander.ContextUnderstanderService}
 *     </strong>
 *     is downstream from {@link android.service.personalcontext.refiner.HintRefinerService}s
 *     receiving all generated {@link ContextHint}s based on its specified
 *     {@link android.service.personalcontext.hint.HintFilter}. An understander service is not
 *     required to produce {@link ContextInsight}s from the inbound {@link ContextHint}s and
 *     may produce {@link ContextInsight}s by calling {@link #publishInsight(List)} at any
 *     time.</li>
 *     <li><strong>{@link android.service.personalcontext.renderer.InsightRendererService}
 *     </strong>
 *     handle showing resulting {@link ContextInsight}s. Renderers integrate with their given
 *     surfaces, such as notifications and auto-fill suggestions.</li>
 *     <li><strong>{@link RenderToken}</strong> allow for {@link ContextHint} publishers to
 *     specify the {@link android.service.personalcontext.renderer.InsightRendererService} that
 *     should any {@link ContextInsight} generated from the hint.</li>
 *  </ul>
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_PERSONAL_CONTEXT_SERVICE)
@SystemService(Context.PERSONAL_CONTEXT_SERVICE)
@SystemApi
public final class PersonalContextManager {
    /** The name of the Personal Context service. */
    public static final String PERSONAL_CONTEXT_SERVICE = "personal_context";

    private static final String TAG = "PersonalContextManager";

    /**
     * Intent that is broadcast when the state of {@link #isEnabled()} changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PERSONAL_CONTEXT_ENABLED_CHANGED =
            "android.service.personalcontext.action.PERSONAL_CONTEXT_ENABLED_CHANGED";

    private final Context mContext;
    private final IPersonalContextManager mService;

    /** @hide */
    public PersonalContextManager(
            @NonNull Context context, @NonNull IPersonalContextManager service) {
        mContext = context;
        mService = service;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Set up service: " + mService);
        }
    }

    /**
     * Returns whether the Personal Context service is enabled.
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    @RequiresPermission(Manifest.permission.PERSONAL_CONTEXT_READ_SETTINGS)
    public boolean isEnabled() {
        try {
            return mService.isEnabled(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the Personal Context service is enabled.
     * @param enabled whether to enable or disable the service
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    @RequiresPermission(Manifest.permission.PERSONAL_CONTEXT_WRITE_SETTINGS)
    public void setEnabled(boolean enabled) {
        try {
            mService.setEnabled(mContext.getUserId(), enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if personal context data collection is enabled for the given package.
     *
     * <p>When disabled, this setting stops all data collection sources of personal context for a
     * particular application, such as from the Content Capture API and notifications content. As
     * a result, contextual information from this application will not participate in the data
     * capture and processing within the PersonalContext service, excluding this information from
     * being seen by participating components and thus restricting any PersonalContext experience
     * from including this application.
     *
     * @param packageName package name of the application to read the setting for
     */
    @RequiresPermission(Manifest.permission.QUERY_ALL_PACKAGES)
    public boolean isPersonalContextModeEnabled(@NonNull String packageName) {
        try {
            return mService.isPersonalContextModeEnabled(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets whether personal context data collection is enabled for the given application.
     *
     * @param packageName package name of the application to change the setting for
     * @param enabled value for whether data collection is enabled
     * @see #isPersonalContextModeEnabled(String)
     *
     */
    @RequiresPermission(Manifest.permission.CHANGE_PERSONAL_CONTEXT_MODE)
    public void setPersonalContextModeEnabled(@NonNull String packageName, boolean enabled) {
        try {
            mService.setPersonalContextModeEnabled(packageName, mContext.getUserId(), enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Triggers a Personal Context service flow with raw data in the form of hints.
     *
     * @param hints raw data to be injected into the context flow
     * @param renderTokens optional tokens indicating which renderers should be used to render
     *                     results of this flow to the user; if empty, then this flow can be
     *                     rendered by any Personal Context renderer
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void publishTriggeringHint(
            @NonNull List<ContextHint> hints, @Nullable List<RenderToken> renderTokens) {
        publishTriggeringHint(hints, renderTokens, null);
    }

    /**
     * Triggers a Personal Context service flow with raw data in the form of hints.
     *
     * <p>Publishing hints is a one-way action. The publisher does not receive any confirmation
     * about the arrival or usage of the hint once it's published. There is no guarantee that the
     * hint will be used at all.
     *
     * <p>Each hint in {@code hints} will be attributed to each hint in {@code attributionHints}.
     *
     * @param hints raw data to be injected into the context flow
     * @param renderTokens optional tokens indicating which renderers should be used to render
     *     results of this flow to the user; if {@code null} or empty, then this flow can be
     *     rendered by any Personal Context renderer
     * @param attributionHints optional hints to use as attribution for {@code hints}
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void publishTriggeringHint(
            @NonNull List<ContextHint> hints,
            @Nullable List<RenderToken> renderTokens,
            @Nullable List<ContextHint> attributionHints) {
        try {
            mService.publishTriggeringHint(
                    ContextHintWrapper.wrapList(hints),
                    renderTokens,
                    ContextHintWrapper.wrapList(attributionHints),
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Triggers a Personal Context service flow with raw data in the form of hints.
     *
     * @throws IllegalArgumentException if the hints collection is null or empty
     *
     * @param returnHintReport Routing information from an insight that these hints are related to
     * @param hints raw data to be injected into the context flow
     */
    @RequiresPermission(Manifest.permission.PERSONAL_CONTEXT_PUBLISH_HINTS)
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void publishTriggeringHint(
            @NonNull ReturnHintReport returnHintReport, @NonNull List<ContextHint> hints) {
        requireNonNull(returnHintReport, "returnHintReport must not be null");

        if (hints == null || hints.isEmpty()) {
            throw new IllegalArgumentException("hints collection must not be null or empty");
        }

        final List<ContextHint> allHints = new ArrayList<>();
        allHints.add(returnHintReport.getInsightReferenceHint());
        allHints.addAll(hints);

        publishTriggeringHint(allHints, returnHintReport.getRenderTokens(), null);
    }

    /**
     * Triggers a PersonalContext service flow with data that has been understood in the form of
     * insights.
     *
     * <p>{@link android.service.personalcontext.understander.ContextUnderstanderService} typically
     * calls this method to publish the derived information and actions, captured in
     * {@link ContextInsight} implementations. All published insights must only be attributed
     * to hints previously published to the PersonalContext service. Additionally, all specified
     * insights should be targeting the same rendering surfaces by having matching
     * {@link RenderToken}s or not specifying any particular renderer. Any non-conforming insights
     * will be ignored.
     *
     * @param insights new insights to be injected into the context flow
     * @param componentId the publisher's unique identifier as issued by the
     * {@link com.android.server.personalcontext.PersonalContextManagerService}. This value is used
     *                    to route any resulting events and information stemming from this
     *                    {@link ContextInsight} back to the publisher.
     * @hide
     */
    @RequiresPermission(Manifest.permission.PERSONAL_CONTEXT_PUBLISH_INSIGHTS)
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void publishInsight(@NonNull List<ContextInsight> insights, @NonNull UUID componentId) {
        try {
            mService.publishInsight(ContextInsightWrapper.wrapList(insights),
                    new ParcelUuid(componentId),
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Takes a {@link ContextHint} and converts it to a {@link PublishedContextHint} without
     * actually publishing it.
     *
     * <p>This can be used to attach a new hint to a {@link ContextInsight} without having to
     * trigger a workflow.
     *
     * @param hint raw data to be signed
     * @param attributionHints optional hints to use as attribution for {@code hint}
     * @return prepared version of hint annotated with caller's package
     */
    @NonNull
    public PublishedContextHint preparePublishedHint(
            @NonNull ContextHint hint, @Nullable List<ContextHint> attributionHints) {
        try {
            return mService.signHint(
                    new ContextHintWrapper(hint), ContextHintWrapper.wrapList(attributionHints))
                    .getPublishedContextHint();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a client to receive embedded surfaces.
     *
     * @param clientInfo the {@link InsightSurfaceClientInfo} object for the client
     * @param hints a list of {@link ContextHint}s from the client used to trigger a new refiner
     *              workflow
     *
     * @hide
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    @RequiresPermission(Manifest.permission.PERSONAL_CONTEXT_HOST_INSIGHT_SURFACE)
    @Nullable
    public void registerInsightSurfaceClient(@NonNull InsightSurfaceClientInfo clientInfo) {
        try {
            mService.registerInsightSurfaceClient(clientInfo, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters an embedded insight surface client.
     *
     * @param clientInfo of the client to be unregistered
     *
     * @hide
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void unregisterInsightSurfaceClient(@NonNull InsightSurfaceClientInfo clientInfo) {
        try {
            mService.unregisterInsightSurfaceClient(
                    new ParcelUuid(clientInfo.getId()), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Mints a {@link Token} that can be attached to {@link ContextHint}, {@link ContextInsight}, or
     * used in filters to filter for hints and insights.
     */
    @NonNull
    public Token mintToken() {
        try {
            return mService.mintToken();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Publish hints from an embedded insight surface.
     *
     * @param clientInfo of the client to be unregistered
     *
     * @hide
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void publishInsightSurfaceHints(
            @NonNull Set<ContextHint> hints, @NonNull InsightSurfaceClientInfo clientInfo) {
        try {
            mService.publishInsightSurfaceHints(
                    ContextHintWrapper.wrapList(hints), clientInfo, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Instructs the understander to show attribution for the provided insight. If the insight does
     * not have attribution, nothing will happen. This can be checked ahead of the call via
     * {@link ContextInsight#hasAttribution}.
     *
     * @param insight Insight with attribution information that should be shown to the user.
     */
    @RequiresNoPermission
    public void showAttribution(@NonNull ContextInsight insight) {
        try {
            mService.showAttribution(new ContextInsightWrapper(insight));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the client info of an embedded client.
     *
     * @param oldClientInfo the old {@link InsightSurfaceClientInfo} that needs to be updated
     * @param newClientInfo the new {@link InsightSurfaceClientInfo} that has the updated info
     *
     * @hide
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public void updateEmbeddedClientInfo(
            @NonNull InsightSurfaceClientInfo oldClientInfo,
            @NonNull InsightSurfaceClientInfo newClientInfo) {
        try {
            mService.updateEmbeddedClientInfo(oldClientInfo, newClientInfo, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports an event that occurred on this insight from a Renderer back to the Understander that
     * published it.
     *
     * @param insight Insight the event occurred on
     * @param eventType Type of event that occurred (see {@code InsightEvent.EVENT_*})
     * @param renderToken RenderToken supplied to the Renderer
     *
     * @hide
     */
    @SystemApi
    public void reportInsightEvent(
            @NonNull PublishedContextInsight insight,
            @InsightEvent.EventType int eventType,
            @NonNull RenderToken renderToken) {
        try {
            mService.reportEvent(new InsightEvent(
                            eventType,
                            insight,
                            System.currentTimeMillis(),
                            renderToken),
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
