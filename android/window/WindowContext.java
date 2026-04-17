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
package android.window;

import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManagerImpl.createWindowContextWindowManager;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacksController;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.WindowManagerImpl;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.Reference;

/**
 * {@link WindowContext} is a context for non-activity windows such as
 * {@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} windows or system
 * windows. Its resources and configuration are adjusted to the area of the display that will be
 * used when a new window is added via {@link android.view.WindowManager#addView}.
 *
 * @see Context#createWindowContext(int, Bundle)
 * @hide
 */
@UiContext
public class WindowContext extends ContextWrapper implements WindowProvider,
        ConfigurationDispatcher {
    @WindowType
    private final int mType;
    @Nullable
    private final Bundle mOptions;
    @WindowType
    private int mFallbackWindowType = INVALID_WINDOW_TYPE;
    private final ComponentCallbacksController mCallbacksController =
            new ComponentCallbacksController();
    private final WindowContextController mController;

    private final WindowManager mWindowManager;

    private Window mWindow;

    /**
     * Default implementation of {@link WindowContext}
     * <p>
     * Note that the users should call {@link Context#createWindowContext(Display, int, Bundle)}
     * to create a {@link WindowContext} instead of using this constructor
     * </p><p>
     * Example usage:
     * <pre class="prettyprint">
     * Bundle options = new Bundle();
     * options.put(KEY_ROOT_DISPLAY_AREA_ID, displayAreaInfo.rootDisplayAreaId);
     * Context windowContext = context.createWindowContext(display, windowType, options);
     * </pre></p>
     *
     * @param base    Base {@link Context} for this new instance.
     * @param type    Window type to be used with this context.
     * @param options A bundle used to pass window-related options.
     * @see DisplayAreaInfo#rootDisplayAreaId
     */
    public WindowContext(@NonNull Context base,
                         @WindowType int type,
                         @Nullable Bundle options) {
        super(base);

        mType = type;
        mOptions = options;
        mWindowManager = createWindowContextWindowManager(this);
        WindowTokenClient token = (WindowTokenClient) requireNonNull(getWindowContextToken());
        mController = new WindowContextController(requireNonNull(token));

        Reference.reachabilityFence(this);
        if (android.view.accessibility.Flags.forceInvertColor()) {
            // Use the theme of the application as the default theme for this window context.
            base.setTheme(getApplicationInfo().theme);
        }
    }

    /**
     * Attaches this {@link WindowContext} to the {@link com.android.server.wm.DisplayArea}
     * specified by {@code mType}, {@link #getDisplayId() display ID} and {@code mOptions}
     * to receive configuration changes.
     */
    public void attachToDisplayArea() {
        mController.attachToDisplayArea(mType, getDisplayId(), mOptions);
    }

    /**
     * Moves this context to another display.
     * <p>
     * Note that this re-parents all the previously attached windows. Resources associated with this
     * context will have the correct value and configuration for the new display after this is
     * called.
     */
    public void reparentToDisplay(int displayId) {
        if (displayId == getDisplayId()) {
            return;
        }
        super.updateDisplay(displayId);
        mController.reparentToDisplayArea(mType, displayId, mOptions);
    }

    @Override
    public Object getSystemService(String name) {
        if (WINDOW_SERVICE.equals(name)) {
            return mWindowManager;
        }
        return super.getSystemService(name);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    /** Used for test to invoke because we can't invoke finalize directly. */
    @VisibleForTesting
    public void release() {
        mController.detachIfNeeded();
        destroy();
    }

    @Override
    public void destroy() {
        try {
            mCallbacksController.clearCallbacks();
            // Called to the base ContextImpl to do final clean-up.
            getBaseContext().destroy();
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public void registerComponentCallbacks(@NonNull ComponentCallbacks callback) {
        mCallbacksController.registerCallbacks(callback);
    }

    @Override
    public void unregisterComponentCallbacks(@NonNull ComponentCallbacks callback) {
        mCallbacksController.unregisterCallbacks(callback);
    }

    @WindowType
    @Override
    public int getFallbackWindowType() {
        return mFallbackWindowType;
    }

    /**
     * Associates {@code window} to this {@code WindowContext} and attach {@code window} to
     * associated {@link WindowManager}.
     * <p>
     * Note that this method must be called before {@link WindowManager#addView} and
     * a {@code WindowContext} only can attach one {@code window}.
     * <p>
     * If there's a use case to attach another window, please {@link Context#createWindowContext}
     * instead.
     *
     * @param window the window to attach.
     * @throws IllegalStateException if window has been attached.
     */
    public void attachWindow(@NonNull View window) {
        if (mWindow != null) {
            throw new IllegalStateException(
                    "This WindowContext has already attached a window. Window=" + mWindow
                    + " Please create another WindowContext if you want to attach another window."
            );
        }
        mWindow = new WindowWrapper(this, window);
        final boolean hardwareAccelerated =
                (getApplicationInfo().flags & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
        mWindow.setWindowManager(
                mWindowManager,
                getWindowContextToken(),
                null /* appName */,
                hardwareAccelerated,
                false /* createLocalWindowManager */
        );
    }

    /**
     * Checks if the WindowContext should be reparented to the default display when the currently
     * attached display is removed.
     */
    public static boolean shouldFallbackToDefaultDisplay(@Nullable Bundle options) {
        return options != null
                && options.getBoolean(KEY_REPARENT_TO_DEFAULT_DISPLAY_WITH_DISPLAY_REMOVAL, false);
    }

/* === WindowProvider APIs === */

    @Override
    public int getWindowType() {
        return mType;
    }

    @Nullable
    @Override
    public Bundle getWindowContextOptions() {
        return mOptions;
    }

    /**
     * If set, this {@code WindowContext} will override the window type when
     * {@link WindowManager#addView} or {@link WindowManager#updateViewLayout} if the window does
     * not use {@link #isSelfOrSubWindowType(int)}.
     * <p>
     * The default is {@link WindowManager.LayoutParams#INVALID_WINDOW_TYPE},
     * which won't override the type.
     * <p>
     * Note:
     * <ol>
     *   <li>If a view is attached, the window type won't be overridden to another window type.</li>
     *   <li>If a sub-window override is requested, a parent window must be prepared. It can
     *   be either by using {@link WindowManager} from a {@link Window} or calling
     *   {@link #attachWindow(View)} before adding any sub-windows, or
     *   {@link IllegalArgumentException} throws when {@link WindowManager#addView}.
     *   </li>
     * </ol>
     *
     * @throws IllegalArgumentException if the passed {@code fallbackWindowType} is not
     * {@link #isSelfOrSubWindowType(int)}.
     */
    public void setFallbackWindowType(@WindowType int fallbackWindowType) {
        if (!isSelfOrSubWindowType(fallbackWindowType)
                && fallbackWindowType != INVALID_WINDOW_TYPE) {
            throw new IllegalArgumentException(
                    "The window type override must be either "
                    + mType
                    + " or a sub window type, but it's "
                            + fallbackWindowType
            );
        }
        mFallbackWindowType = fallbackWindowType;
    }

/* === ConfigurationDispatcher APIs === */

    @Override
    public boolean shouldReportPrivateChanges() {
        // Always dispatch config changes to WindowContext.
        return true;
    }

    /** Dispatch {@link Configuration} to each {@link ComponentCallbacks}. */
    @Override
    public void dispatchConfigurationChanged(@NonNull Configuration newConfig) {
        mCallbacksController.dispatchConfigurationChanged(newConfig);
    }

    /**
     * A simple {@link Window} wrapper that is used to pass to
     * {@link WindowManagerImpl#createLocalWindowManager(Window)}.
     */
    private static class WindowWrapper extends WindowBase {

        @NonNull
        private final View mDecorView;

        /**
         * The {@link WindowWrapper} constructor.
         *
         * @param context   the associated {@link WindowContext}`
         * @param decorView the view to be wrapped as {@link Window}'s decor view.
         */
        WindowWrapper(@NonNull @UiContext Context context, @NonNull View decorView) {
            super(context);

            mDecorView = requireNonNull(decorView);
        }

        @NonNull
        @Override
        public LayoutInflater getLayoutInflater() {
            return LayoutInflater.from(mDecorView.getContext());
        }

        @NonNull
        @Override
        public View getDecorView() {
            return mDecorView;
        }

        @Override
        public View peekDecorView() {
            return mDecorView;
        }
    }
}
