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

package android.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.OutcomeReceiver;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Singleton;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * @hide
 */
@RavenwoodKeepWholeClass
public class FullscreenRequestHandler {
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_APPROVED,
            RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY,
            RESULT_FAILED_NOT_TOP_FOCUSED,
            RESULT_FAILED_ALREADY_FULLY_EXPANDED,
            RESULT_FAILED_NOT_SUPPORTED
    })
    public @interface RequestResult {}

    public static final int RESULT_APPROVED = 0;
    public static final int RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY = 1;
    public static final int RESULT_FAILED_NOT_TOP_FOCUSED = 2;
    public static final int RESULT_FAILED_ALREADY_FULLY_EXPANDED = 3;
    public static final int RESULT_FAILED_NOT_SUPPORTED = 4;

    /** @hide */
    @IntDef(prefix = { "REQUEST_ALLOW_MODE_" }, value = {
            REQUEST_ALLOW_MODE_INHERIT,
            REQUEST_ALLOW_MODE_NONE,
            REQUEST_ALLOW_MODE_ENTER,
            REQUEST_ALLOW_MODE_EXIT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestAllowMode {}

    /**
     * Inherit the request allow mode of its parent window container.
     *
     * @hide
     */
    public static final int REQUEST_ALLOW_MODE_INHERIT = 0;

    /**
     * No request is allowed on the container.
     *
     * @hide
     */
    public static final int REQUEST_ALLOW_MODE_NONE = 1;

    /**
     * Enter fullscreen requests are allowed.
     *
     * @hide
     */
    public static final int REQUEST_ALLOW_MODE_ENTER = 2;

    /**
     * Exit fullscreen requests are allowed.
     *
     * @hide
     */
    public static final int REQUEST_ALLOW_MODE_EXIT = 3;

    public static final String REMOTE_CALLBACK_RESULT_KEY = "result";

    private static final Singleton<FullscreenRequestHandler> sInstance =
            new Singleton<FullscreenRequestHandler>() {
                @Override
                protected FullscreenRequestHandler create() {
                    return new FullscreenRequestHandler(ActivityClient.getInstance());
                }
            };

    private final ActivityClient mActivityClient;

    /** @return the singleton instance of {@link FullscreenRequestHandler} */
    public static FullscreenRequestHandler getInstance() {
        return sInstance.get();
    }

    @VisibleForTesting
    public FullscreenRequestHandler(ActivityClient activityClient) {
        mActivityClient = activityClient;
    }

    /** Handles the fullscreen mode request. */
    public void requestFullscreenMode(@Activity.FullscreenModeRequest int request,
            @Nullable OutcomeReceiver<Void, Throwable> approvalCallback, IBinder token,
            @NonNull Executor executor) {
        try {
            if (approvalCallback != null) {
                mActivityClient.requestMultiwindowFullscreen(token, request,
                        new IRemoteCallback.Stub() {
                            @Override
                            public void sendResult(Bundle res) {
                                executor.execute(() -> notifyFullscreenRequestResult(
                                        approvalCallback,
                                        res.getInt(REMOTE_CALLBACK_RESULT_KEY)));
                            }
                        });
            } else {
                mActivityClient.requestMultiwindowFullscreen(token, request, null);
            }
        } catch (Throwable e) {
            if (approvalCallback != null) {
                executor.execute(() -> approvalCallback.onError(e));
            }
        }
    }

    private void notifyFullscreenRequestResult(
            OutcomeReceiver<Void, Throwable> callback, int result) {
        Throwable e = null;
        switch (result) {
            case RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY:
                e = new IllegalStateException("The window is not in fullscreen by calling the "
                        + "requestFullscreenMode API before, such that cannot be restored.");
                break;
            case RESULT_FAILED_NOT_TOP_FOCUSED:
                e = new IllegalStateException("The window is not the top focused window.");
                break;
            case RESULT_FAILED_ALREADY_FULLY_EXPANDED:
                e = new IllegalStateException("The window is already fully expanded.");
                break;
            case RESULT_FAILED_NOT_SUPPORTED:
                e = new UnsupportedOperationException("Fullscreen request denied by system "
                        + "policy.");
                break;
            default:
                callback.onResult(null);
                break;
        }
        if (e != null) {
            callback.onError(e);
        }
    }

    /** @hide */
    @NonNull
    public static String requestResultToString(@RequestResult int result) {
        switch (result) {
            case RESULT_APPROVED: return "RESULT_APPROVED";
            case RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY:
                return "RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY";
            case RESULT_FAILED_NOT_TOP_FOCUSED: return "RESULT_FAILED_NOT_TOP_FOCUSED";
            case RESULT_FAILED_ALREADY_FULLY_EXPANDED:
                return "RESULT_FAILED_ALREADY_FULLY_EXPANDED";
            case RESULT_FAILED_NOT_SUPPORTED: return "RESULT_FAILED_NOT_SUPPORTED";
            default: return String.valueOf(result);
        }
    }
}
