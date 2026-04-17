/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.inputmethod;

import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.ViewProtoLogGroups.IME_TRACKER;

import static com.android.internal.inputmethod.InputMethodDebug.softInputDisplayReasonToString;
import static com.android.internal.jank.Cuj.CUJ_IME_INSETS_HIDE_ANIMATION;
import static com.android.internal.jank.Cuj.CUJ_IME_INSETS_SHOW_ANIMATION;
import static com.android.internal.util.LatencyTracker.ACTION_REQUEST_IME_HIDDEN;
import static com.android.internal.util.LatencyTracker.ACTION_REQUEST_IME_SHOWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.InsetsController.AnimationType;
import android.view.SurfaceControl;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.LatencyTracker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** @hide */
public interface ImeTracker {

    String TAG = "ImeTracker";

    /** The debug flag for IME visibility event log. */
    boolean DEBUG_IME_VISIBILITY = SystemProperties.getBoolean("persist.debug.imf_event", false);

    /** The message to indicate if there is no valid {@link Token}. */
    String TOKEN_NONE = "TOKEN_NONE";

    /** The type of the IME request. */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_NOT_SET,
            TYPE_SHOW,
            TYPE_HIDE,
            TYPE_USER,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Type {}

    int TYPE_NOT_SET = ImeProtoEnums.TYPE_NOT_SET;

    /**
     * IME show request type.
     *
     * @see android.view.InsetsController#ANIMATION_TYPE_SHOW
     */
    int TYPE_SHOW = ImeProtoEnums.TYPE_SHOW;

    /**
     * IME hide request type.
     *
     * @see android.view.InsetsController#ANIMATION_TYPE_HIDE
     */
    int TYPE_HIDE = ImeProtoEnums.TYPE_HIDE;

    /**
     * IME user-controlled animation request type.
     *
     * @see android.view.InsetsController#ANIMATION_TYPE_USER
     */
    int TYPE_USER = ImeProtoEnums.TYPE_USER;

    /** The status of the IME request. */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_RUN,
            STATUS_CANCEL,
            STATUS_FAIL,
            STATUS_SUCCESS,
            STATUS_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Status {}

    /** The IME request is running. */
    int STATUS_RUN = ImeProtoEnums.STATUS_RUN;

    /** The IME request is cancelled. */
    int STATUS_CANCEL = ImeProtoEnums.STATUS_CANCEL;

    /** The IME request failed. */
    int STATUS_FAIL = ImeProtoEnums.STATUS_FAIL;

    /** The IME request succeeded. */
    int STATUS_SUCCESS = ImeProtoEnums.STATUS_SUCCESS;

    /** The IME request timed out. */
    int STATUS_TIMEOUT = ImeProtoEnums.STATUS_TIMEOUT;

    /**
     * The origin of the IME request.
     *
     * <p> The name follows the format {@code ORIGIN_x_...} where {@code x} denotes
     * where the origin is (i.e. {@code ORIGIN_SERVER} occurs in the server).
     */
    @IntDef(prefix = { "ORIGIN_" }, value = {
            ORIGIN_NOT_SET,
            ORIGIN_CLIENT,
            ORIGIN_SERVER,
            ORIGIN_IME,
            ORIGIN_SHELL,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Origin {}

    int ORIGIN_NOT_SET = ImeProtoEnums.ORIGIN_NOT_SET;

    /** The IME request originated in the client. */
    int ORIGIN_CLIENT = ImeProtoEnums.ORIGIN_CLIENT;

    /** The IME request originated in the server. */
    int ORIGIN_SERVER = ImeProtoEnums.ORIGIN_SERVER;

    /** The IME request originated in the IME. */
    int ORIGIN_IME = ImeProtoEnums.ORIGIN_IME;
    /** The IME request originated in the WindowManager Shell. */
    int ORIGIN_SHELL = ImeProtoEnums.ORIGIN_SHELL;

    /**
     * The current phase of the IME request.
     *
     * <p> The name follows the format {@code PHASE_x_...} where {@code x} denotes
     * where the phase is (i.e. {@code PHASE_SERVER_...} occurs in the server).
     */
    @IntDef(prefix = { "PHASE_" }, value = {
            PHASE_NOT_SET,
            PHASE_CLIENT_VIEW_SERVED,
            PHASE_SERVER_CLIENT_KNOWN,
            PHASE_SERVER_CLIENT_FOCUSED,
            PHASE_SERVER_ACCESSIBILITY,
            PHASE_SERVER_SYSTEM_READY,
            PHASE_SERVER_HIDE_IMPLICIT,
            PHASE_SERVER_HIDE_NOT_ALWAYS,
            PHASE_SERVER_WAIT_IME,
            PHASE_SERVER_HAS_IME,
            PHASE_SERVER_SHOULD_HIDE,
            PHASE_IME_WRAPPER,
            PHASE_IME_WRAPPER_DISPATCH,
            PHASE_IME_SHOW_SOFT_INPUT,
            PHASE_IME_HIDE_SOFT_INPUT,
            PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE,
            PHASE_SERVER_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS,
            PHASE_SERVER_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS,
            PHASE_SERVER_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS,
            PHASE_SHELL_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS,
            PHASE_SHELL_REMOTE_INSETS_CONTROLLER,
            PHASE_SHELL_ANIMATION_CREATE,
            PHASE_SHELL_ANIMATION_RUNNING,
            PHASE_CLIENT_SHOW_INSETS,
            PHASE_CLIENT_HIDE_INSETS,
            PHASE_CLIENT_HANDLE_SHOW_INSETS,
            PHASE_CLIENT_HANDLE_HIDE_INSETS,
            PHASE_CLIENT_APPLY_ANIMATION,
            PHASE_CLIENT_CONTROL_ANIMATION,
            PHASE_CLIENT_ANIMATION_RUNNING,
            PHASE_CLIENT_ANIMATION_CANCEL,
            PHASE_CLIENT_ANIMATION_FINISHED_SHOW,
            PHASE_SERVER_ABORT_SHOW_IME_POST_LAYOUT,
            PHASE_IME_SHOW_WINDOW,
            PHASE_IME_HIDE_WINDOW,
            PHASE_IME_PRIVILEGED_OPERATIONS,
            PHASE_SERVER_CURRENT_ACTIVE_IME,
            PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES,
            PHASE_SERVER_SET_REMOTE_TARGET_IME_VISIBILITY,
            PHASE_SERVER_POST_LAYOUT_NOTIFY_CONTROLS_CHANGED,
            PHASE_IME_NOTIFY_IME_VISIBILITY_CHANGED,
            PHASE_CLIENT_UPDATE_REQUESTED_VISIBLE_TYPES,
            PHASE_SERVER_GET_CONTROL_WITH_LEASH,
            PHASE_SERVER_UPDATE_REQUESTED_VISIBLE_TYPES,
            PHASE_SERVER_SET_VISIBILITY_ON_FOCUSED_WINDOW,
            PHASE_CLIENT_HANDLE_SET_IME_VISIBILITY,
            PHASE_CLIENT_SET_IME_VISIBILITY,
            PHASE_SERVER_DISPATCH_IME_REQUESTED_CHANGED,
            PHASE_CLIENT_NO_ONGOING_USER_ANIMATION,
            PHASE_SERVER_NOTIFY_IME_VISIBILITY_CHANGED_FROM_CLIENT,
            PHASE_SERVER_POSTING_CHANGED_IME_VISIBILITY,
            PHASE_SERVER_INVOKING_IME_REQUESTED_LISTENER,
            PHASE_CLIENT_ALREADY_HIDDEN,
            PHASE_CLIENT_VIEW_HANDLER_AVAILABLE,
            PHASE_SERVER_UPDATE_CLIENT_VISIBILITY,
            PHASE_SHELL_DISPLAY_IME_CONTROLLER_SET_IME_REQUESTED_VISIBLE,
            PHASE_SERVER_UPDATE_DISPLAY_WINDOW_REQUESTED_VISIBLE_TYPES,
            PHASE_CLIENT_UPDATE_ANIMATING_TYPES,
            PHASE_SERVER_UPDATE_ANIMATING_TYPES,
            PHASE_SERVER_WINDOW_ANIMATING_TYPES_CHANGED,
            PHASE_SERVER_NOTIFY_HIDE_ANIMATION_FINISHED,
            PHASE_SERVER_UPDATE_DISPLAY_WINDOW_ANIMATING_TYPES,
            PHASE_CLIENT_ON_CONTROLS_CHANGED,
            PHASE_SERVER_IME_INVOKER,
            PHASE_SERVER_CLIENT_INVOKER,
            PHASE_SERVER_ALREADY_VISIBLE,
            PHASE_CLIENT_INSETS_CONTROLLER_DISPATCH,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Phase {}

    int PHASE_NOT_SET = ImeProtoEnums.PHASE_NOT_SET;

    /** The view that requested the IME has been served by the IMM. */
    int PHASE_CLIENT_VIEW_SERVED = ImeProtoEnums.PHASE_CLIENT_VIEW_SERVED;

    /** The IME client that requested the IME has window manager focus. */
    int PHASE_SERVER_CLIENT_KNOWN = ImeProtoEnums.PHASE_SERVER_CLIENT_KNOWN;

    /** The IME client that requested the IME has IME focus. */
    int PHASE_SERVER_CLIENT_FOCUSED = ImeProtoEnums.PHASE_SERVER_CLIENT_FOCUSED;

    /** The IME request complies with the current accessibility settings. */
    int PHASE_SERVER_ACCESSIBILITY = ImeProtoEnums.PHASE_SERVER_ACCESSIBILITY;

    /** The server is ready to run third party code. */
    int PHASE_SERVER_SYSTEM_READY = ImeProtoEnums.PHASE_SERVER_SYSTEM_READY;

    /** Checked the implicit hide request against any explicit show requests. */
    int PHASE_SERVER_HIDE_IMPLICIT = ImeProtoEnums.PHASE_SERVER_HIDE_IMPLICIT;

    /** Checked the not-always hide request against any forced show requests. */
    int PHASE_SERVER_HIDE_NOT_ALWAYS = ImeProtoEnums.PHASE_SERVER_HIDE_NOT_ALWAYS;

    /** The server is waiting for a connection to the IME. */
    int PHASE_SERVER_WAIT_IME = ImeProtoEnums.PHASE_SERVER_WAIT_IME;

    /** The server has a connection to the IME. */
    int PHASE_SERVER_HAS_IME = ImeProtoEnums.PHASE_SERVER_HAS_IME;

    /** The server decided the IME should be hidden. */
    int PHASE_SERVER_SHOULD_HIDE = ImeProtoEnums.PHASE_SERVER_SHOULD_HIDE;

    /** Reached the IME wrapper. */
    int PHASE_IME_WRAPPER = ImeProtoEnums.PHASE_IME_WRAPPER;

    /** Dispatched from the IME wrapper to the IME. */
    int PHASE_IME_WRAPPER_DISPATCH = ImeProtoEnums.PHASE_IME_WRAPPER_DISPATCH;

    /** Reached the IME's showSoftInput method. */
    int PHASE_IME_SHOW_SOFT_INPUT = ImeProtoEnums.PHASE_IME_SHOW_SOFT_INPUT;

    /** Reached the IME's hideSoftInput method. */
    int PHASE_IME_HIDE_SOFT_INPUT = ImeProtoEnums.PHASE_IME_HIDE_SOFT_INPUT;

    /** The server decided the IME should be shown. */
    int PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE = ImeProtoEnums.PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE;

    /** Reached the window insets control target's show insets method. */
    int PHASE_SERVER_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS =
            ImeProtoEnums.PHASE_SERVER_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS;

    /** Reached the window insets control target's hide insets method. */
    int PHASE_SERVER_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS =
            ImeProtoEnums.PHASE_SERVER_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS;

    /** Reached the remote insets control target's show insets method. */
    int PHASE_SERVER_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS =
            ImeProtoEnums.PHASE_SERVER_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS;

    /** Reached the remote insets control target's hide insets method. */
    int PHASE_SHELL_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS =
            ImeProtoEnums.PHASE_SHELL_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS;

    /** Reached the remote insets controller. */
    int PHASE_SHELL_REMOTE_INSETS_CONTROLLER = ImeProtoEnums.PHASE_SHELL_REMOTE_INSETS_CONTROLLER;

    /** Created the IME window insets show animation. */
    int PHASE_SHELL_ANIMATION_CREATE = ImeProtoEnums.PHASE_SHELL_ANIMATION_CREATE;

    /** Started the IME window insets show animation. */
    int PHASE_SHELL_ANIMATION_RUNNING = ImeProtoEnums.PHASE_SHELL_ANIMATION_RUNNING;

    /** Reached the client's show insets method. */
    int PHASE_CLIENT_SHOW_INSETS = ImeProtoEnums.PHASE_CLIENT_SHOW_INSETS;

    /** Reached the client's hide insets method. */
    int PHASE_CLIENT_HIDE_INSETS = ImeProtoEnums.PHASE_CLIENT_HIDE_INSETS;

    /** Handling the IME window insets show request. */
    int PHASE_CLIENT_HANDLE_SHOW_INSETS = ImeProtoEnums.PHASE_CLIENT_HANDLE_SHOW_INSETS;

    /** Handling the IME window insets hide request. */
    int PHASE_CLIENT_HANDLE_HIDE_INSETS = ImeProtoEnums.PHASE_CLIENT_HANDLE_HIDE_INSETS;

    /** Applied the IME window insets show animation. */
    int PHASE_CLIENT_APPLY_ANIMATION = ImeProtoEnums.PHASE_CLIENT_APPLY_ANIMATION;

    /** Started the IME window insets show animation. */
    int PHASE_CLIENT_CONTROL_ANIMATION = ImeProtoEnums.PHASE_CLIENT_CONTROL_ANIMATION;

    /** Queued the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_RUNNING = ImeProtoEnums.PHASE_CLIENT_ANIMATION_RUNNING;

    /** Cancelled the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_CANCEL = ImeProtoEnums.PHASE_CLIENT_ANIMATION_CANCEL;

    /** Finished the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_FINISHED_SHOW = ImeProtoEnums.PHASE_CLIENT_ANIMATION_FINISHED_SHOW;

    /** Aborted the request to show the IME post layout. */
    int PHASE_SERVER_ABORT_SHOW_IME_POST_LAYOUT =
            ImeProtoEnums.PHASE_SERVER_ABORT_SHOW_IME_POST_LAYOUT;

    /** Reached the IME's showWindow method. */
    int PHASE_IME_SHOW_WINDOW = ImeProtoEnums.PHASE_IME_SHOW_WINDOW;

    /** Reached the IME's hideWindow method. */
    int PHASE_IME_HIDE_WINDOW = ImeProtoEnums.PHASE_IME_HIDE_WINDOW;

    /** Reached the InputMethodPrivilegedOperations handler. */
    int PHASE_IME_PRIVILEGED_OPERATIONS = ImeProtoEnums.PHASE_IME_PRIVILEGED_OPERATIONS;

    /** Checked that the calling IME is the currently active IME. */
    int PHASE_SERVER_CURRENT_ACTIVE_IME = ImeProtoEnums.PHASE_SERVER_CURRENT_ACTIVE_IME;

    /** Reporting the new requested visible types. */
    int PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES =
            ImeProtoEnums.PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES;
    /** Setting the IME visibility for the RemoteInsetsControlTarget. */
    int PHASE_SERVER_SET_REMOTE_TARGET_IME_VISIBILITY =
            ImeProtoEnums.PHASE_SERVER_SET_REMOTE_TARGET_IME_VISIBILITY;
    /** IME has no insets pending and is server visible. Notify about changed controls. */
    int PHASE_SERVER_POST_LAYOUT_NOTIFY_CONTROLS_CHANGED =
            ImeProtoEnums.PHASE_SERVER_POST_LAYOUT_NOTIFY_CONTROLS_CHANGED;
    /** Dispatching the IME visibility change. */
    int PHASE_IME_NOTIFY_IME_VISIBILITY_CHANGED =
            ImeProtoEnums.PHASE_IME_NOTIFY_IME_VISIBILITY_CHANGED;
    /** Updating the requested visible types. */
    int PHASE_CLIENT_UPDATE_REQUESTED_VISIBLE_TYPES =
            ImeProtoEnums.PHASE_CLIENT_UPDATE_REQUESTED_VISIBLE_TYPES;
    /** Received a new insets source control with a leash. */
    int PHASE_SERVER_GET_CONTROL_WITH_LEASH =
            ImeProtoEnums.PHASE_SERVER_GET_CONTROL_WITH_LEASH;
    /**
     * Updating the requested visible types in the WindowState and sending them to state
     * controller.
     */
    int PHASE_SERVER_UPDATE_REQUESTED_VISIBLE_TYPES =
            ImeProtoEnums.PHASE_SERVER_UPDATE_REQUESTED_VISIBLE_TYPES;
    /** Setting the requested IME visibility of a window. */
    int PHASE_SERVER_SET_VISIBILITY_ON_FOCUSED_WINDOW =
            ImeProtoEnums.PHASE_SERVER_SET_VISIBILITY_ON_FOCUSED_WINDOW;
    /** Reached the redirect of InputMethodManager to InsetsController show/hide. */
    int PHASE_CLIENT_HANDLE_SET_IME_VISIBILITY =
            ImeProtoEnums.PHASE_CLIENT_HANDLE_SET_IME_VISIBILITY;
    /** Reached the InputMethodManager Handler call to send the visibility. */
    int PHASE_CLIENT_SET_IME_VISIBILITY = ImeProtoEnums.PHASE_CLIENT_SET_IME_VISIBILITY;
    /** Calling into the listener to show/hide the IME from the ImeInsetsSourceProvider. */
    int PHASE_SERVER_DISPATCH_IME_REQUESTED_CHANGED =
            ImeProtoEnums.PHASE_SERVER_DISPATCH_IME_REQUESTED_CHANGED;
    /** An ongoing user animation will not be interrupted by a IMM#showSoftInput. */
    int PHASE_CLIENT_NO_ONGOING_USER_ANIMATION =
            ImeProtoEnums.PHASE_CLIENT_NO_ONGOING_USER_ANIMATION;
    /** Dispatching the token to the ImeInsetsSourceProvider. */
    int PHASE_SERVER_NOTIFY_IME_VISIBILITY_CHANGED_FROM_CLIENT =
            ImeProtoEnums.PHASE_SERVER_NOTIFY_IME_VISIBILITY_CHANGED_FROM_CLIENT;
    /** Now posting the IME visibility to the WMS handler. */
    int PHASE_SERVER_POSTING_CHANGED_IME_VISIBILITY =
            ImeProtoEnums.PHASE_SERVER_POSTING_CHANGED_IME_VISIBILITY;
    /** Inside the WMS handler calling into the listener that calls into IMMS show/hide. */
    int PHASE_SERVER_INVOKING_IME_REQUESTED_LISTENER =
            ImeProtoEnums.PHASE_SERVER_INVOKING_IME_REQUESTED_LISTENER;
    /** IME is requested to be hidden, but already hidden. Don't hide to avoid another animation. */
    int PHASE_CLIENT_ALREADY_HIDDEN = ImeProtoEnums.PHASE_CLIENT_ALREADY_HIDDEN;
    /**
     * The view's handler is needed to check if we're running on a different thread. We can't
     * continue without.
     */
    int PHASE_CLIENT_VIEW_HANDLER_AVAILABLE = ImeProtoEnums.PHASE_CLIENT_VIEW_HANDLER_AVAILABLE;
    /**
     * ImeInsetsSourceProvider sets the reported visibility of the caller/client window (either the
     * app or the RemoteInsetsControlTarget).
     */
    int PHASE_SERVER_UPDATE_CLIENT_VISIBILITY = ImeProtoEnums.PHASE_SERVER_UPDATE_CLIENT_VISIBILITY;
    /** DisplayImeController received the requested visibility for the IME and stored it. */
    int PHASE_SHELL_DISPLAY_IME_CONTROLLER_SET_IME_REQUESTED_VISIBLE =
            ImeProtoEnums.PHASE_SHELL_DISPLAY_IME_CONTROLLER_SET_IME_REQUESTED_VISIBLE;
    /** The control target reported its requestedVisibleTypes back to WindowManagerService. */
    int PHASE_SERVER_UPDATE_DISPLAY_WINDOW_REQUESTED_VISIBLE_TYPES =
            ImeProtoEnums.PHASE_SERVER_UPDATE_DISPLAY_WINDOW_REQUESTED_VISIBLE_TYPES;
    /** Updating the currently animating types on the client side. */
    int PHASE_CLIENT_UPDATE_ANIMATING_TYPES =
            ImeProtoEnums.PHASE_CLIENT_UPDATE_ANIMATING_TYPES;
    /** Updating the animating types in the WindowState on the WindowManager side. */
    int PHASE_SERVER_UPDATE_ANIMATING_TYPES =
            ImeProtoEnums.PHASE_SERVER_UPDATE_ANIMATING_TYPES;
    /** Animating types of the WindowState have changed, now sending them to state controller. */
    int PHASE_SERVER_WINDOW_ANIMATING_TYPES_CHANGED =
            ImeProtoEnums.PHASE_SERVER_WINDOW_ANIMATING_TYPES_CHANGED;
    /** ImeInsetsSourceProvider got notified that the hide animation is finished. */
    int PHASE_SERVER_NOTIFY_HIDE_ANIMATION_FINISHED =
            ImeProtoEnums.PHASE_SERVER_NOTIFY_HIDE_ANIMATION_FINISHED;
    /** The control target reported its animatingTypes back to WindowManagerService. */
    int PHASE_SERVER_UPDATE_DISPLAY_WINDOW_ANIMATING_TYPES =
            ImeProtoEnums.PHASE_SERVER_UPDATE_DISPLAY_WINDOW_ANIMATING_TYPES;
    /** InsetsController received a control for the IME. */
    int PHASE_CLIENT_ON_CONTROLS_CHANGED =
            ImeProtoEnums.PHASE_CLIENT_ON_CONTROLS_CHANGED;
    /** Reached the IME invoker on the server. */
    int PHASE_SERVER_IME_INVOKER = ImeProtoEnums.PHASE_SERVER_IME_INVOKER;
    /** Reached the IME client invoker on the server. */
    int PHASE_SERVER_CLIENT_INVOKER = ImeProtoEnums.PHASE_SERVER_CLIENT_INVOKER;
    /** The server will dispatch the show request to the IME, but this is already visible. */
    int PHASE_SERVER_ALREADY_VISIBLE = ImeProtoEnums.PHASE_SERVER_ALREADY_VISIBLE;
    /** The show/hide request will be dispatched to the InsetsController of the ViewRootImpl. */
    int PHASE_CLIENT_INSETS_CONTROLLER_DISPATCH =
            ImeProtoEnums.PHASE_CLIENT_INSETS_CONTROLLER_DISPATCH;

    /**
     * Called when an IME request is started.
     *
     * @param component the name of the component that started the request.
     * @param uid the uid of the client that started the request.
     * @param type the type of the request.
     * @param origin the origin of the request.
     * @param reason the reason for starting the request.
     * @param fromUser whether this request was created directly from user interaction.
     * @param userId the ID of the user that started the request.
     * @param displayId the ID of the display where the IME would show.
     * @return An IME request tracking token.
     */
    @NonNull
    Token onStart(@NonNull String component, int uid, @Type int type, @Origin int origin,
            @SoftInputShowHideReason int reason, boolean fromUser, @UserIdInt int userId,
            int displayId);

    /**
     * Called when an IME request is started for the current process.
     *
     * @param type the type of the request.
     * @param origin the origin of the request.
     * @param reason the reason for starting the request.
     * @param fromUser whether this request was created directly from user interaction.
     * @param userId the ID of the user that started the request.
     * @param displayId the ID of the display where the IME would show.
     * @return An IME request tracking token.
     */
    @NonNull
    default Token onStart(@Type int type, @Origin int origin, @SoftInputShowHideReason int reason,
            boolean fromUser, @UserIdInt int userId, int displayId) {
        return onStart(Process.myProcessName(), Process.myUid(), type, origin, reason, fromUser,
                userId, displayId);
    }

    /**
     * Called when an IME request progresses to a further phase.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     * @param phase the new phase the request reached.
     */
    void onProgress(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME request fails.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     * @param phase the phase the request failed at.
     */
    void onFailed(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME request reached a flow that is not yet implemented.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     * @param phase the phase the request was currently at.
     */
    void onTodo(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME request is cancelled.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     * @param phase the phase the request was cancelled at.
     */
    void onCancelled(@Nullable Token token, @Phase int phase);

    /**
     * Called when the show IME request is successful.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     */
    void onShown(@Nullable Token token);

    /**
     * Called when the hide IME request is successful.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     */
    void onHidden(@Nullable Token token);

    /**
     * Called when the user-controlled IME request was dispatched to the requesting app. The
     * user animation can take an undetermined amount of time, so it shouldn't be tracked.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     */
    void onDispatched(@Nullable Token token);

    /**
     * Called when the animation of the user-controlled IME request finished.
     *
     * @param token the token tracking the request or {@code null} otherwise.
     * @param shown whether the end state of the animation was shown or hidden.
     */
    void onUserFinished(@Nullable Token token, boolean shown);

    /**
     * Returns whether the current IME request was created due to a user interaction. This can
     * only be {@code true} when running on the view's UI thread.
     *
     * @param view the view for which the IME was requested.
     * @return {@code true} if this request is coming from a user interaction,
     * {@code false} otherwise.
     */
    static boolean isFromUser(@Nullable View view) {
        if (view == null) {
            return false;
        }
        final var handler = view.getHandler();
        // Early return if not on the UI thread, to ensure safe access to getViewRootImpl() below.
        if (handler == null || handler.getLooper() == null
                || !handler.getLooper().isCurrentThread()) {
            return false;
        }
        final var viewRootImpl = view.getViewRootImpl();
        return viewRootImpl != null && viewRootImpl.isHandlingPointerEvent();
    }

    /**
     * Get the singleton request tracker instance.
     *
     * @return the singleton request tracker instance
     */
    @NonNull
    static ImeTracker forLogging() {
        return LOGGER;
    }

    /**
     * Get the singleton jank tracker instance.
     *
     * @return the singleton jank tracker instance
     */
    @NonNull
    static ImeJankTracker forJank() {
        return JANK_TRACKER;
    }

    /**
     * Get the singleton latency tracker instance.
     *
     * @return the singleton latency tracker instance
     */
    @NonNull
    static ImeLatencyTracker forLatency() {
        return LATENCY_TRACKER;
    }

    /** The singleton IME tracker instance. */
    @NonNull
    ImeTracker LOGGER = new ImeTracker() {

        {
            // Read initial system properties.
            reloadSystemProperties();
            // Update when system properties change.
            SystemProperties.addChangeCallback(this::reloadSystemProperties);

            if (android.tracing.Flags.imetrackerProtolog()) {
                ProtoLog.registerLogGroupInProcess(IME_TRACKER);
            }
        }

        /** Whether {@link #onProgress} calls should be logged. */
        private boolean mLogProgress;

        /** Whether the stack trace at the request call site should be logged. */
        private boolean mLogStackTrace;

        @NonNull
        @Override
        public Token onStart(@NonNull String component, int uid, @Type int type, @Origin int origin,
                @SoftInputShowHideReason int reason, boolean fromUser, @UserIdInt int userId,
                int displayId) {
            final var token = Token.createToken(component);
            final long startWallTimeMs = System.currentTimeMillis();
            final long startTimestampMs = SystemClock.elapsedRealtime();
            IInputMethodManagerGlobalInvoker.onStart(token, uid, type, origin, reason, fromUser,
                    userId, displayId, startWallTimeMs, startTimestampMs);

            log("%s: %s at %s reason %s fromUser %b%s displayId %d%s", token.mTag,
                    getOnStartPrefix(type), Debug.originToString(origin),
                    InputMethodDebug.softInputDisplayReasonToString(reason), fromUser,
                    userId != UserHandle.USER_NULL ? " userId " + userId : "",
                    displayId,
                    mLogStackTrace ? " Stack trace=" + Log.getStackTraceString(new Throwable()) : ""
            );
            return token;
        }

        @Override
        public void onProgress(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onProgress(token, phase);

            if (mLogProgress) {
                log("%s: onProgress at %s", token.mTag, Debug.phaseToString(phase));
            }
        }

        @Override
        public void onFailed(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onFailed(token, phase);

            log("%s: onFailed at %s", token.mTag, Debug.phaseToString(phase));
        }

        @Override
        public void onTodo(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            log("%s: onTodo at %s", token.mTag, Debug.phaseToString(phase));
        }

        @Override
        public void onCancelled(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onCancelled(token, phase);

            log("%s: onCancelled at %s", token.mTag, Debug.phaseToString(phase));
        }

        @Override
        public void onShown(@Nullable Token token) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onShown(token);

            log("%s: onShown", token.mTag);
        }

        @Override
        public void onHidden(@Nullable Token token) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onHidden(token);

            log("%s: onHidden", token.mTag);
        }

        @Override
        public void onDispatched(@Nullable Token token) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onDispatched(token);

            log("%s: onDispatched", token.mTag);
        }

        @Override
        public void onUserFinished(@Nullable Token token, boolean shown) {
            if (token == null) return;
            // This is already sent to ImeTrackerService to mark it finished during onDispatched.

            log("%s: onUserFinished %s", token.mTag, shown ? "shown" : "hidden");
        }

        /**
         * Gets the prefix string for {@link #onStart} based on the given request type.
         *
         * @param type request type for which to create the prefix string with.
         */
        @NonNull
        private static String getOnStartPrefix(@Type int type) {
            return switch (type) {
                case TYPE_SHOW -> "onRequestShow";
                case TYPE_HIDE -> "onRequestHide";
                case TYPE_USER -> "onRequestUser";
                default -> "onRequestUnknown";
            };
        }

        /** Reloads the system properties related to this class. */
        private void reloadSystemProperties() {
            mLogProgress = SystemProperties.getBoolean(
                    "persist.debug.imetracker", false);
            mLogStackTrace = SystemProperties.getBoolean(
                    "persist.debug.imerequest.logstacktrace", false);
        }
    };

    /** The singleton IME tracker instance for instrumenting jank metrics. */
    ImeJankTracker JANK_TRACKER = new ImeJankTracker();

    /** The singleton IME tracker instance for instrumenting latency metrics. */
    ImeLatencyTracker LATENCY_TRACKER = new ImeLatencyTracker();

    /** A token that tracks the progress of an IME request. */
    final class Token implements Parcelable {

        private static final AtomicInteger sCounter = new AtomicInteger();

        /** The id used to identify this token. */
        private final long mId;

        /** Logging tag, of the shape "component:random_hexadecimal". */
        @NonNull
        private final String mTag;

        @VisibleForTesting
        public Token(long id, @NonNull String tag) {
            mId = id;
            mTag = tag;
        }

        private Token(@NonNull Parcel in) {
            mId = in.readLong();
            mTag = in.readString8();
        }

        /** Returns the id used to identify this token. */
        public long getId() {
            return mId;
        }

        /** Returns the logging tag of this token. */
        @NonNull
        public String getTag() {
            return mTag;
        }

        @NonNull
        private static Token createToken(@NonNull String component) {
            // Unique 64 bit ID, composed of process ID and process local counter. When a process
            // dies, its ID is generally re-used only after the other possible values are exhausted.
            // Thus the collision chance is very low, (virtually) towards zero.
            final long id = ((long) Process.myPid()) << 32 | sCounter.getAndIncrement();
            return new Token(id, createTag(component));
        }

        /**
         * Creates a logging tag.
         *
         * @param component the name of the component that created the request.
         */
        @NonNull
        private static String createTag(@NonNull String component) {
            return component + ":" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
        }

        /** Returns a new token with an empty id. */
        @NonNull
        @VisibleForTesting
        public static Token empty() {
            final var tag = createTag(Process.myProcessName());
            return empty(tag);
        }

        /** Returns a new token with an empty id and the given logging tag. */
        @NonNull
        static Token empty(@NonNull String tag) {
            return new Token(0 /* id */, tag);
        }

        @Override
        public String toString() {
            return super.toString() + "(tag: " + mTag + ")";
        }

        /** For Parcelable, no special marshalled objects. */
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeLong(mId);
            dest.writeString8(mTag);
        }

        @NonNull
        public static final Creator<Token> CREATOR = new Creator<>() {
            @NonNull
            @Override
            public Token createFromParcel(@NonNull Parcel in) {
                return new Token(in);
            }

            @NonNull
            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Utilities for mapping IntDef values to their names.
     *
     * Note: This is held in a separate class so that it only gets initialized when actually needed.
     */
    final class Debug {

        @NonNull
        private static final Map<Integer, String> sTypes =
                getFieldMapping(ImeTracker.class, "TYPE_");
        @NonNull
        private static final Map<Integer, String> sStatus =
                getFieldMapping(ImeTracker.class, "STATUS_");
        @NonNull
        private static final Map<Integer, String> sOrigins =
                getFieldMapping(ImeTracker.class, "ORIGIN_");
        @NonNull
        private static final Map<Integer, String> sPhases =
                getFieldMapping(ImeTracker.class, "PHASE_");

        @NonNull
        public static String typeToString(@Type int type) {
            return sTypes.getOrDefault(type, "TYPE_" + type);
        }

        @NonNull
        public static String statusToString(@Status int status) {
            return sStatus.getOrDefault(status, "STATUS_" + status);
        }

        @NonNull
        public static String originToString(@Origin int origin) {
            return sOrigins.getOrDefault(origin, "ORIGIN_" + origin);
        }

        @NonNull
        public static String phaseToString(@Phase int phase) {
            return sPhases.getOrDefault(phase, "PHASE_" + phase);
        }

        @NonNull
        private static Map<Integer, String> getFieldMapping(Class<?> cls,
                @NonNull String fieldPrefix) {
            return Arrays.stream(cls.getDeclaredFields())
                    .filter(field -> field.getName().startsWith(fieldPrefix))
                    .collect(Collectors.toMap(Debug::getFieldValue, Field::getName));
        }

        private static int getFieldValue(@NonNull Field field) {
            try {
                return field.getInt(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Context related to {@link InteractionJankMonitor}.
     */
    interface InputMethodJankContext {
        /**
         * @return a context associated with a display
         */
        Context getDisplayContext();

        /**
         * @return a SurfaceControl that is going to be monitored
         */
        SurfaceControl getTargetSurfaceControl();

        /**
         * @return the package name of the host
         */
        String getHostPackageName();
    }

    /**
     * Context related to {@link LatencyTracker}.
     */
    interface InputMethodLatencyContext {
        /**
         * @return a context associated with current application
         */
        Context getAppContext();
    }

    @SuppressWarnings("ProtoLogNonConstantFormat")
    private static void log(@NonNull String messageString, @NonNull Object... args) {
        if (android.tracing.Flags.imetrackerProtolog()) {
            ProtoLog.i(IME_TRACKER, messageString, args);
        } else {
            // Log only to logcat
            final var message = TextUtils.formatSimple(messageString, args);
            Log.i(TAG, message);
        }
    }

    /**
     * A tracker instance which is in charge of communicating with {@link InteractionJankMonitor}.
     * This class disallows instantiating from outside, use {@link #forJank()} to get the singleton.
     */
    final class ImeJankTracker {

        /**
         * This class disallows instantiating from outside.
         */
        private ImeJankTracker() {
        }

        /**
         * Called when the animation, which is going to be monitored, starts.
         *
         * @param jankContext context which is needed by {@link InteractionJankMonitor}.
         * @param animType the animation type.
         * @param useSeparatedThread {@code true} if the animation is handled by the app,
         *                           {@code false} if the animation will be scheduled on the
         *                           {@link android.view.InsetsAnimationThread}.
         * @param handler the handler for the thread used for running inset animations.
         */
        public void onRequestAnimation(@NonNull InputMethodJankContext jankContext,
                @AnimationType int animType, boolean useSeparatedThread,
                @Nullable Handler handler) {
            final int cujType = getImeInsetsCujFromAnimation(animType);
            if (jankContext.getDisplayContext() == null
                    || jankContext.getTargetSurfaceControl() == null
                    || handler == null
                    || cujType == -1) {
                return;
            }
            final Configuration.Builder builder = Configuration.Builder.withSurface(
                            cujType,
                            jankContext.getDisplayContext(),
                            jankContext.getTargetSurfaceControl(),
                            handler)
                    .setTag(String.format(Locale.US, "%d@%d@%s", animType,
                            useSeparatedThread ? 0 : 1, jankContext.getHostPackageName()));
            InteractionJankMonitor.getInstance().begin(builder);
        }

        /**
         * Called when the animation, which is going to be monitored, cancels.
         *
         * @param animType the animation type.
         */
        public void onCancelAnimation(@AnimationType int animType) {
            final int cujType = getImeInsetsCujFromAnimation(animType);
            if (cujType != -1) {
                InteractionJankMonitor.getInstance().cancel(cujType);
            }
        }

        /**
         * Called when the animation, which is going to be monitored, ends.
         *
         * @param animType the animation type.
         */
        public void onFinishAnimation(@AnimationType int animType) {
            final int cujType = getImeInsetsCujFromAnimation(animType);
            if (cujType != -1) {
                InteractionJankMonitor.getInstance().end(cujType);
            }
        }

        /**
         * A helper method to translate animation type to CUJ type for IME animations.
         *
         * @param animType the animation type.
         * @return the integer in {@link com.android.internal.jank.Cuj.CujType},
         * or {@code -1} if the animation type is not supported for tracking yet.
         */
        private static int getImeInsetsCujFromAnimation(@AnimationType int animType) {
            switch (animType) {
                case ANIMATION_TYPE_SHOW:
                    return CUJ_IME_INSETS_SHOW_ANIMATION;
                case ANIMATION_TYPE_HIDE:
                    return CUJ_IME_INSETS_HIDE_ANIMATION;
                default:
                    return -1;
            }
        }
    }

    /**
     * A tracker instance which is in charge of communicating with {@link LatencyTracker}.
     * This class disallows instantiating from outside, use {@link #forLatency()}
     * to get the singleton.
     */
    final class ImeLatencyTracker {

        /**
         * This class disallows instantiating from outside.
         */
        private ImeLatencyTracker() {
        }

        private boolean shouldMonitorLatency(@SoftInputShowHideReason int reason) {
            return reason == SoftInputShowHideReason.SHOW_SOFT_INPUT
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_VIEW
                    || reason == SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API
                    || reason == SoftInputShowHideReason.SHOW_SOFT_INPUT_FROM_IME
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_IME;
        }

        public void onRequestShow(@Nullable Token token, @Origin int origin,
                @SoftInputShowHideReason int reason,
                @NonNull InputMethodLatencyContext latencyContext) {
            if (!shouldMonitorLatency(reason)) return;
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionStart(
                            ACTION_REQUEST_IME_SHOWN,
                            softInputDisplayReasonToString(reason));
        }

        public void onRequestHide(@Nullable Token token, @Origin int origin,
                @SoftInputShowHideReason int reason,
                @NonNull InputMethodLatencyContext latencyContext) {
            if (!shouldMonitorLatency(reason)) return;
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionStart(
                            ACTION_REQUEST_IME_HIDDEN,
                            softInputDisplayReasonToString(reason));
        }

        public void onShowFailed(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            onShowCancelled(token, phase, latencyContext);
        }

        public void onHideFailed(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            onHideCancelled(token, phase, latencyContext);
        }

        public void onShowCancelled(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionCancel(ACTION_REQUEST_IME_SHOWN);
        }

        public void onHideCancelled(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionCancel(ACTION_REQUEST_IME_HIDDEN);
        }

        public void onShown(@Nullable Token token,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionEnd(ACTION_REQUEST_IME_SHOWN);
        }

        public void onHidden(@Nullable Token token,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionEnd(ACTION_REQUEST_IME_HIDDEN);
        }
    }
}
