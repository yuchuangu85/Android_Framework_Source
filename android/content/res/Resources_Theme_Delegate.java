/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.res;

import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.RenderSessionImpl;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.Nullable;
import android.content.res.Resources.NotFoundException;
import android.content.res.Resources.Theme;
import android.content.res.Resources.ThemeKey;
import android.util.AttributeSet;
import android.util.TypedValue;

/**
 * Delegate used to provide new implementation of a select few methods of {@link Resources.Theme}
 *
 * Through the layoutlib_create tool, the original  methods of Theme have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class Resources_Theme_Delegate {

    // ---- delegate manager ----

    private static final DelegateManager<Resources_Theme_Delegate> sManager =
            new DelegateManager<Resources_Theme_Delegate>(Resources_Theme_Delegate.class);

    public static DelegateManager<Resources_Theme_Delegate> getDelegateManager() {
        return sManager;
    }

    // ---- delegate methods. ----

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int[] attrs) {
        boolean changed = setupResources(thisTheme);
        BridgeTypedArray ta = RenderSessionImpl.getCurrentContext().internalObtainStyledAttributes(
                0, attrs);
        ta.setTheme(thisTheme);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            int resid, int[] attrs)
            throws NotFoundException {
        boolean changed = setupResources(thisTheme);
        BridgeTypedArray ta = RenderSessionImpl.getCurrentContext().internalObtainStyledAttributes(
                resid, attrs);
        ta.setTheme(thisTheme);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray obtainStyledAttributes(
            Resources thisResources, Theme thisTheme,
            AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
        boolean changed = setupResources(thisTheme);
        BridgeTypedArray ta = RenderSessionImpl.getCurrentContext().internalObtainStyledAttributes(set,
                attrs, defStyleAttr, defStyleRes);
        ta.setTheme(thisTheme);
        restoreResources(changed);
        return ta;
    }

    @LayoutlibDelegate
    /*package*/ static boolean resolveAttribute(
            Resources thisResources, Theme thisTheme,
            int resid, TypedValue outValue,
            boolean resolveRefs) {
        boolean changed = setupResources(thisTheme);
        boolean found =  RenderSessionImpl.getCurrentContext().resolveThemeAttribute(resid,
                outValue, resolveRefs);
        restoreResources(changed);
        return found;
    }

    @LayoutlibDelegate
    /*package*/ static TypedArray resolveAttributes(Resources thisResources, Theme thisTheme,
            int[] values, int[] attrs) {
        // FIXME
        return null;
    }

    // ---- private helper methods ----

    private static boolean setupResources(Theme thisTheme) {
        // Key is a space-separated list of theme ids applied that have been merged into the
        // BridgeContext's theme to make thisTheme.
        final ThemeKey key = thisTheme.getKey();
        final int[] resId = key.mResId;
        final boolean[] force = key.mForce;

        boolean changed = false;
        for (int i = 0, N = key.mCount; i < N; i++) {
            StyleResourceValue style = resolveStyle(resId[i]);
            if (style != null) {
                RenderSessionImpl.getCurrentContext().getRenderResources().applyStyle(
                        style, force[i]);
                changed = true;
            }

        }
        return changed;
    }

    private static void restoreResources(boolean changed) {
        if (changed) {
            RenderSessionImpl.getCurrentContext().getRenderResources().clearStyles();
        }
    }

    @Nullable
    private static StyleResourceValue resolveStyle(int nativeResid) {
        if (nativeResid == 0) {
            return null;
        }
        BridgeContext context = RenderSessionImpl.getCurrentContext();
        ResourceReference theme = context.resolveId(nativeResid);
        return (StyleResourceValue) context.getRenderResources().getResolvedResource(theme);
    }
}
