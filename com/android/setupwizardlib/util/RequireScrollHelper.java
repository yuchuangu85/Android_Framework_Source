/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.setupwizardlib.util;

import android.view.View;
import android.widget.ScrollView;

import com.android.setupwizardlib.view.BottomScrollView;
import com.android.setupwizardlib.view.NavigationBar;

/**
 * Add this helper to require the scroll view to be scrolled to the bottom, making sure that the
 * user sees all content on the screen. This will change the navigation bar to show the more button
 * instead of the next button when there is more content to be seen. When the more button is
 * clicked, the scroll view will be scrolled one page down.
 */
public class RequireScrollHelper implements BottomScrollView.BottomScrollListener,
        View.OnClickListener {

    /**
     * Require scrolling on the scrollView, so that if the scrollView has content hidden beneath the
     * fold, the next button will be hidden and the more button will be shown instead. The more
     * button will scroll the scrollView downwards when clicked until the bottom is reached.
     *
     * @param navigationBar The navigation bar in which the next button's label will be changed.
     * @param scrollView The {@link BottomScrollView} to be scrolled.
     */
    public static RequireScrollHelper requireScroll(NavigationBar navigationBar,
            BottomScrollView scrollView) {
        final RequireScrollHelper helper = new RequireScrollHelper(navigationBar, scrollView);
        helper.requireScroll();
        return helper;
    }

    private final BottomScrollView mScrollView;
    private final NavigationBar mNavigationBar;

    private boolean mScrollNeeded;

    private RequireScrollHelper(NavigationBar navigationBar, BottomScrollView scrollView) {
        mNavigationBar = navigationBar;
        mScrollView = scrollView;
    }

    private void requireScroll() {
        mNavigationBar.getMoreButton().setOnClickListener(this);
        mScrollView.setBottomScrollListener(this);
    }

    @Override
    public void onScrolledToBottom() {
        if (mScrollNeeded) {
            mNavigationBar.getNextButton().setVisibility(View.VISIBLE);
            mNavigationBar.getMoreButton().setVisibility(View.GONE);
            mScrollNeeded = false;
        }
    }

    @Override
    public void onRequiresScroll() {
        if (!mScrollNeeded) {
            mNavigationBar.getNextButton().setVisibility(View.GONE);
            mNavigationBar.getMoreButton().setVisibility(View.VISIBLE);
            mScrollNeeded = true;
        }
    }

    @Override
    public void onClick(View view) {
        mScrollView.pageScroll(ScrollView.FOCUS_DOWN);
    }
}
