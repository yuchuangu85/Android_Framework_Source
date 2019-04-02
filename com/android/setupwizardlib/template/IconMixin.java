/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.setupwizardlib.template;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.setupwizardlib.R;
import com.android.setupwizardlib.TemplateLayout;

/**
 * A {@link Mixin} for setting an icon on the template layout.
 */
public class IconMixin implements Mixin {

    private TemplateLayout mTemplateLayout;

    /**
     * @param layout The template layout that this Mixin is a part of.
     * @param attrs XML attributes given to the layout.
     * @param defStyleAttr The default style attribute as given to the constructor of the layout.
     */
    public IconMixin(TemplateLayout layout, AttributeSet attrs, int defStyleAttr) {
        mTemplateLayout = layout;
        final Context context = layout.getContext();

        final TypedArray a =
                context.obtainStyledAttributes(attrs, R.styleable.SuwIconMixin, defStyleAttr, 0);

        final Drawable icon = a.getDrawable(R.styleable.SuwIconMixin_android_icon);
        if (icon != null) {
            setIcon(icon);
        }

        a.recycle();
    }

    /**
     * Sets the icon on this layout. The icon can also be set in XML using {@code android:icon}.
     *
     * @param icon A drawable icon.
     */
    public void setIcon(Drawable icon) {
        final ImageView iconView = getView();
        if (iconView != null) {
            iconView.setImageDrawable(icon);
        }
    }

    /**
     * @return The icon previously set in {@link #setIcon(Drawable)} or {@code android:icon}
     */
    public Drawable getIcon() {
        final ImageView iconView = getView();
        return iconView != null ? iconView.getDrawable() : null;
    }

    /**
     * @return The ImageView responsible for displaying the icon.
     */
    protected ImageView getView() {
        return (ImageView) mTemplateLayout.findManagedViewById(R.id.suw_layout_icon);
    }
}
