/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player;

import static com.android.internal.widget.remotecompose.core.CoreDocument.MAJOR_VERSION;
import static com.android.internal.widget.remotecompose.core.CoreDocument.MINOR_VERSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.CoreDocument.ShaderControl;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.RemoteContextActions;
import com.android.internal.widget.remotecompose.core.operations.NamedVariable;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.semantics.ScrollableComponent;
import com.android.internal.widget.remotecompose.player.accessibility.platform.RemoteComposeTouchHelper;
import com.android.internal.widget.remotecompose.player.platform.AndroidRemoteContext;
import com.android.internal.widget.remotecompose.player.platform.BitmapLoader;
import com.android.internal.widget.remotecompose.player.platform.HapticSupport;
import com.android.internal.widget.remotecompose.player.platform.RemoteComposeView;
import com.android.internal.widget.remotecompose.player.platform.RemotePreparedDocument;
import com.android.internal.widget.remotecompose.player.platform.SensorSupport;
import com.android.internal.widget.remotecompose.player.platform.SettingsRetriever;
import com.android.internal.widget.remotecompose.player.platform.ThemeSupport;
import com.android.internal.widget.remotecompose.player.state.StateUpdater;
import com.android.internal.widget.remotecompose.player.state.StateUpdaterImpl;

import java.io.InputStream;

/**
 * This is a player for a RemoteComposeDocument.
 *
 * <p>It displays the document as well as providing the integration with the Android system (e.g.
 * passing sensor values, etc.). It also exposes player APIs that allows to control how the document
 * is displayed as well as reacting to document events.
 */
public class RemoteComposePlayer extends FrameLayout implements RemoteContextActions {

    private static final int MAX_SUPPORTED_MAJOR_VERSION = MAJOR_VERSION;
    private static final int MAX_SUPPORTED_MINOR_VERSION = MINOR_VERSION;

    // Theme constants
    public static final int THEME_UNSPECIFIED = Theme.UNSPECIFIED;
    public static final int THEME_LIGHT = Theme.LIGHT;
    public static final int THEME_DARK = Theme.DARK;

    private RemoteComposeView mInner;
    private StateUpdater mStateUpdater;

    private final @NonNull ThemeSupport mThemeSupport = new ThemeSupport();
    private final @NonNull SensorSupport mSensorsSupport = new SensorSupport();
    private final @NonNull HapticSupport mHapticSupport = new HapticSupport();

    private @NonNull ShaderControl mShaderControl = (shader) -> false;

    public RemoteComposePlayer(@NonNull Context context) {
        super(context);
        init(context, null, 0);
    }

    public RemoteComposePlayer(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public RemoteComposePlayer(
            @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    private @NonNull RemoteContext getRemoteContext() {
        return mInner.getRemoteContext();
    }

    public @NonNull StateUpdater getStateUpdater() {
        return mStateUpdater;
    }

    @Override
    public boolean showOnScreen(@NonNull Component component) {
        ScrollableComponent scrollable = findScrollable(component);

        if (scrollable != null) {
            return scrollable.showOnScreen(getRemoteContext(), component);
        }

        return false;
    }

    @Nullable
    private static ScrollableComponent findScrollable(Component component) {
        Component parent = component.getParent();

        while (parent != null) {
            ScrollableComponent scrollable = parent.selfOrModifier(ScrollableComponent.class);

            if (scrollable != null) {
                return scrollable;
            } else {
                parent = parent.getParent();
            }
        }

        return null;
    }

    @Override
    public int scrollByOffset(@NonNull Component component, int offset) {
        ScrollableComponent scrollable = component.selfOrModifier(ScrollableComponent.class);

        if (scrollable != null) {
            return scrollable.scrollByOffset(getRemoteContext(), offset);
        }

        return 0;
    }

    @Override
    public boolean scrollDirection(
            @NonNull Component component, @NonNull ScrollableComponent.ScrollDirection direction) {
        ScrollableComponent scrollable = component.selfOrModifier(ScrollableComponent.class);

        if (scrollable != null) {
            return scrollable.scrollDirection(getRemoteContext(), direction);
        }

        return false;
    }

    @Override
    public boolean performClick(
            @NonNull CoreDocument document,
            @NonNull Component component,
            @NonNull String metadata) {
        document.performClick(getRemoteContext(), component.getComponentId(), metadata);
        return true;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void requestLayout() {
        super.requestLayout();

        if (mInner != null) {
            mInner.requestLayout();
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public void invalidate() {
        super.invalidate();

        if (mInner != null) {
            mInner.invalidate();
        }
    }

    /**
     * Returns true if the document supports drag touch events. This is used in platform.
     *
     * @return true if draggable content, false otherwise
     */
    public boolean isDraggable() {
        return mInner.isDraggable();
    }

    /**
     * Turn on debug information
     *
     * @param debugFlags 1 to set debug on
     */
    public void setDebug(int debugFlags) {
        mInner.setDebug(debugFlags);
    }

    /** Returns the document */
    public @NonNull RemoteDocument getDocument() {
        return mInner.getDocument();
    }

    /**
     * This will update values in the already loaded document.
     *
     * @param value the document to update variables in the current document width
     */
    public void updateDocument(@NonNull RemoteDocument value) {
        AndroidRemoteContext tmpContext = new AndroidRemoteContext(value.getClock());
        tmpContext.setAccessibilityAnimationEnabled(
                SettingsRetriever.animationsEnabled(getContext()));
        value.initializeContext(tmpContext);
        float density = getContext().getResources().getDisplayMetrics().density;
        tmpContext.setAnimationEnabled(true);
        tmpContext.setDensity(density);
        tmpContext.setUseChoreographer(false);
        mInner.applyUpdate(value);
        mInner.invalidate();
    }

    /**
     * This will update values in the already loaded document.
     *
     * @param buffer the document to update variables in the current document width
     */
    public void updateDocument(@NonNull byte[] buffer) {
        RemoteDocument document = new RemoteDocument(buffer);
        updateDocument(document);
    }

    /** Set a document on the player */
    public void setDocument(@NonNull byte[] buffer) {
        RemoteDocument document = new RemoteDocument(buffer);
        setDocument(document);
    }

    /** Set a document on the player */
    public void setDocument(@NonNull InputStream inputStream) {
        RemoteDocument document = new RemoteDocument(inputStream);
        setDocument(document);
    }

    /** Set a document on the player */
    public void setDocument(@Nullable RemoteDocument value) {
        if (value != null) {
            if (value.canBeDisplayed(
                    MAX_SUPPORTED_MAJOR_VERSION, MAX_SUPPORTED_MINOR_VERSION, 0L)) {
                if (value.isUpdateDoc()) {
                    updateDocument(value);
                    return;
                }
                mInner.setDocument(value);
                int contentBehavior = value.getDocument().getContentScroll();
                applyContentBehavior(contentBehavior);
            } else {
                Log.e("RemoteComposePlayer", "Unsupported document ");
            }

            RemoteComposeTouchHelper.REGISTRAR.setAccessibilityDelegate(this, value.getDocument());
        } else {
            // TODO discuss with Nico
            //            mInner.setDocument(null);

            RemoteComposeTouchHelper.REGISTRAR.clearAccessibilityDelegate(this);
        }

        mThemeSupport.mapColors(getContext(), mInner);
        mSensorsSupport.setupSensors(getContext().getApplicationContext(), mInner);
        mHapticSupport.setupHaptics(mInner);
        mInner.checkShaders(mShaderControl);
    }

    /**
     * Apply the content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL) to the player, adding or
     * removing scrollviews as needed.
     *
     * @param contentBehavior document content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     */
    private void applyContentBehavior(int contentBehavior) {
        switch (contentBehavior) {
            case RootContentBehavior.SCROLL_HORIZONTAL:
                if (!(mInner.getParent() instanceof HorizontalScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner =
                            new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
                    HorizontalScrollView horizontalScrollView =
                            new HorizontalScrollView(getContext());
                    horizontalScrollView.setBackgroundColor(Color.TRANSPARENT);
                    horizontalScrollView.setFillViewport(true);
                    horizontalScrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                    addView(horizontalScrollView, layoutParams);
                }
                break;
            case RootContentBehavior.SCROLL_VERTICAL:
                if (!(mInner.getParent() instanceof ScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                    ScrollView scrollView = new ScrollView(getContext());
                    scrollView.setBackgroundColor(Color.TRANSPARENT);
                    scrollView.setFillViewport(true);
                    scrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                    addView(scrollView, layoutParams);
                }
                break;
            default:
                if (mInner.getParent() != this) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParams =
                            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                    addView(mInner, layoutParams);
                }
        }
    }

    private void init(@NonNull Context context, @NonNull AttributeSet attrs, int defStyleAttr) {
        LayoutParams layoutParams =
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        setBackgroundColor(Color.TRANSPARENT);
        mInner = new RemoteComposeView(context, attrs, defStyleAttr);
        if (mInner.getRemoteContext() instanceof AndroidRemoteContext) {
            ((AndroidRemoteContext) mInner.getRemoteContext())
                    .setAccessibilityAnimationEnabled(
                            SettingsRetriever.animationsEnabled(getContext()));
        }
        mInner.setBackgroundColor(Color.TRANSPARENT);
        addView(mInner, layoutParams);
        mStateUpdater = new StateUpdaterImpl(getRemoteContext());
    }

    /**
     * Sets a BitmapLoader on the RemoteContext.
     *
     * @param bitmapLoader new bitmap loader.
     */
    public void setBitmapLoader(@NonNull BitmapLoader bitmapLoader) {
        ((AndroidRemoteContext) mInner.getRemoteContext()).setBitmapLoader(bitmapLoader);
    }

    /**
     * Set an override for a string resource
     *
     * @param domain domain (SYSTEM or USER)
     * @param name name of the string
     * @param content content of the string
     */
    public void setLocalString(
            @NonNull String domain, @NonNull String name, @NonNull String content) {
        mInner.setLocalString(domain + ":" + name, content);
    }

    /**
     * Clear the override of the given string
     *
     * @param domain domain (SYSTEM or USER)
     * @param name name of the string
     */
    public void clearLocalString(@NonNull String domain, @NonNull String name) {
        mInner.clearLocalString(domain + ":" + name);
    }

    /**
     * Set an override for a user domain string resource
     *
     * @param name name of the string
     * @param content content of the string
     */
    public void setUserLocalString(@NonNull String name, @NonNull String content) {
        mInner.setLocalString("USER:" + name, content);
    }

    /**
     * Set an override for a user domain int resource
     *
     * @param name name of the int
     * @param value value of the int
     */
    public void setUserLocalInt(@NonNull String name, int value) {
        mInner.setLocalInt("USER:" + name, value);
    }

    /**
     * Set an override for a user domain int resource
     *
     * @param name name of the int
     * @param value value of the int
     */
    public void setUserLocalColor(@NonNull String name, int value) {
        mInner.setLocalColor("USER:" + name, value);
    }

    /**
     * Set an override for a user domain float resource
     *
     * @param name name of the float
     * @param value value of the float
     */
    public void setUserLocalFloat(@NonNull String name, float value) {
        mInner.setLocalFloat("USER:" + name, value);
    }

    /**
     * Set an override for a user domain int resource
     *
     * @param name name of the int
     * @param value value of the int
     */
    public void setUserLocalBitmap(@NonNull String name, @NonNull Bitmap value) {
        mInner.setLocalBitmap("USER:" + name, value);
    }

    /**
     * Clear the override of the given user bitmap
     *
     * @param name name of the bitmap
     */
    public void clearUserLocalBitmap(@NonNull String name) {
        mInner.clearLocalBitmap("USER:" + name);
    }

    /**
     * Clear the override of the given user string
     *
     * @param name name of the string
     */
    public void clearUserLocalString(@NonNull String name) {
        mInner.clearLocalString("USER:" + name);
    }

    /**
     * Clear the override of the given user int
     *
     * @param name name of the int
     */
    public void clearUserLocalInt(@NonNull String name) {
        mInner.clearLocalInt("USER:" + name);
    }

    /**
     * Clear the override of the given user color
     *
     * @param name name of the color
     */
    public void clearUserLocalColor(@NonNull String name) {
        mInner.clearLocalColor("USER:" + name);
    }

    /**
     * Clear the override of the given user int
     *
     * @param name name of the int
     */
    public void clearUserLocalFloat(@NonNull String name) {
        mInner.clearLocalFloat("USER:" + name);
    }

    /**
     * Set an override for a system domain string resource
     *
     * @param name name of the string
     * @param content content of the string
     */
    public void setSystemLocalString(@NonNull String name, @NonNull String content) {
        mInner.setLocalString("SYSTEM:" + name, content);
    }

    /**
     * Clear the override of the given system string
     *
     * @param name name of the string
     */
    public void clearSystemLocalString(@NonNull String name) {
        mInner.clearLocalString("SYSTEM:" + name);
    }

    /**
     * This is the number of ops used to calculate the last frame.
     *
     * @return number of ops
     */
    public int getOpsPerFrame() {
        return mInner.getDocument().getDocument().getOpsPerFrame();
    }

    /** Set to use the choreographer */
    @VisibleForTesting
    public void setUseChoreographer(boolean value) {
        mInner.setUseChoreographer(value);
    }

    /** Reload the palette colors */
    public void reloadPalette() {
        mThemeSupport.mapColors(getContext(), mInner);
        invalidate();
    }

    /** Id action callback interface */
    public interface IdActionCallbacks {
        /**
         * Callback for on action
         *
         * @param id the id of the action
         * @param metadata the metadata of the action
         */
        void onAction(int id, @Nullable String metadata);
    }

    /**
     * Add a callback for handling id actions events on the document. Can only be added after the
     * document has been loaded.
     *
     * @param callback the callback lambda that will be used when a action is executed
     *     <p>The parameter of the callback are:
     *     <ul>
     *       <li>id : the id of the action
     *       <li>metadata: a client provided unstructured string associated with that id action
     *     </ul>
     */
    public void addIdActionListener(@NonNull IdActionCallbacks callback) {
        mInner.addIdActionListener((id, metadata) -> callback.onAction(id, metadata));
    }

    /**
     * Set the playback theme for the document. This allows to filter operations in order to have
     * the document adapt to the given theme. This method is intended to be used to support
     * night/light themes (system or app level), not custom themes.
     *
     * @param theme the theme used for playing the document. Possible values for theme are: -
     *     Theme.UNSPECIFIED -- all instructions in the document will be executed - Theme.DARK --
     *     only executed NON Light theme instructions - Theme.LIGHT -- only executed NON Dark theme
     *     instructions
     */
    public void setTheme(int theme) {
        if (mInner.getTheme() != theme) {
            mInner.setTheme(theme);
            mInner.invalidate();
        }
    }

    /**
     * This returns a list of colors that have names in the Document.
     *
     * @return the names of named Strings or null
     */
    public @NonNull String[] getNamedColors() {
        return mInner.getNamedColors();
    }

    /**
     * This returns a list of floats that have names in the Document.
     *
     * @return return the names of named floats in the document
     */
    public @NonNull String[] getNamedFloats() {
        return mInner.getNamedVariables(NamedVariable.FLOAT_TYPE);
    }

    /**
     * This returns a list of string name that have names in the Document.
     *
     * @return the name of named string (not the string itself)
     */
    public @NonNull String[] getNamedStrings() {
        return mInner.getNamedVariables(NamedVariable.STRING_TYPE);
    }

    /**
     * This returns a list of images that have names in the Document.
     *
     * @return the name of named images in the document
     */
    public @NonNull String[] getNamedImages() {
        return mInner.getNamedVariables(NamedVariable.IMAGE_TYPE);
    }

    /**
     * This sets a color based on its name. Overriding the color set in the document.
     *
     * @param colorName Name of the color
     * @param colorValue The new color value
     */
    public void setColor(@NonNull String colorName, int colorValue) {
        mInner.setColor(colorName, colorValue);
    }

    /**
     * This sets long based on its name.
     *
     * @param name Name of the color
     * @param value The new long value
     */
    public void setLong(@NonNull String name, long value) {
        mInner.setLong(name, value);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mSensorsSupport.unregisterListener();
    }

    /**
     * This returns the amount of time in ms the player used to evaluate a pass it is averaged over
     * a number of evaluations.
     *
     * @return time in ms
     */
    public float getEvalTime() {
        return mInner.getEvalTime();
    }

    /**
     * Sets the controller for shaders. Note: set before loading the document. The default is to not
     * accept shaders.
     *
     * @param ctl the controller
     */
    public void setShaderControl(@NonNull ShaderControl ctl) {
        mShaderControl = ctl;
    }

    /** This is a prepared document. */
    public interface PreparedDocument {
        /**
         * Get the original document
         *
         * @return the original document
         */
        @NonNull
        RemoteDocument getOriginalDoc();
    }

    /**
     * determine whether it is worth it to prepare the document or not.
     *
     * @param doc the document to prepare
     * @return true if the document needs to be prepared
     */
    public boolean shouldPrepare(@NonNull RemoteDocument doc) {
        int size_small_enough_to_inline = 1_000;
        return doc.getDocument().getDocInfo().getSizeOfImages() > size_small_enough_to_inline;
    }

    private boolean isCompatible(@NonNull RemoteDocument doc) {
        if (doc.canBeDisplayed(MAX_SUPPORTED_MAJOR_VERSION, MAX_SUPPORTED_MINOR_VERSION, 0L)) {
            return true;
        } else {
            Log.e("RemoteComposePlayer", "Unsupported document ");
        }
        return false;
    }

    /**
     * Prepare the document.
     *
     * @param doc the document to prepare
     * @return the prepared document
     */
    public @Nullable PreparedDocument prepareDocument(@NonNull RemoteDocument doc) {
        if (isCompatible(doc)) {
            return new RemotePreparedDocument(doc);
        }
        return null;
    }

    /**
     * Set the document that was prepared.
     *
     * @param doc the document to prepare
     */
    public void setPreparedDocument(@NonNull PreparedDocument doc) {
        if (doc instanceof RemotePreparedDocument) {
            RemotePreparedDocument remoteDoc = (RemotePreparedDocument) doc;
            mInner.setResolvedData(remoteDoc.getResolvedData());
        }
        setDocument(doc.getOriginalDoc());
    }
}
