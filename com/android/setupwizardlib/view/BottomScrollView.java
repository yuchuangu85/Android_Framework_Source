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

package com.android.setupwizardlib.view;

import android.content.Context;
import androidx.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/**
 * An extension of ScrollView that will invoke a listener callback when the ScrollView needs
 * scrolling, and when the ScrollView is being scrolled to the bottom. This is often used in Setup
 * Wizard as a way to ensure that users see all the content before proceeding.
 */
public class BottomScrollView extends ScrollView {

  public interface BottomScrollListener {
    void onScrolledToBottom();

    void onRequiresScroll();
  }

  private BottomScrollListener listener;
  private int scrollThreshold;
  private boolean requiringScroll = false;

  private final Runnable checkScrollRunnable =
      new Runnable() {
        @Override
        public void run() {
          checkScroll();
        }
      };

  public BottomScrollView(Context context) {
    super(context);
  }

  public BottomScrollView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public BottomScrollView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
  }

  public void setBottomScrollListener(BottomScrollListener l) {
    listener = l;
  }

  @VisibleForTesting
  public BottomScrollListener getBottomScrollListener() {
    return listener;
  }

  @VisibleForTesting
  public int getScrollThreshold() {
    return scrollThreshold;
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    final View child = getChildAt(0);
    if (child != null) {
      scrollThreshold = Math.max(0, child.getMeasuredHeight() - b + t - getPaddingBottom());
    }
    if (b - t > 0) {
      // Post check scroll in the next run loop, so that the callback methods will be invoked
      // after the layout pass. This way a new layout pass will be scheduled if view
      // properties are changed in the callbacks.
      post(checkScrollRunnable);
    }
  }

  @Override
  protected void onScrollChanged(int l, int t, int oldl, int oldt) {
    super.onScrollChanged(l, t, oldl, oldt);
    if (oldt != t) {
      checkScroll();
    }
  }

  private void checkScroll() {
    if (listener != null) {
      if (getScrollY() >= scrollThreshold) {
        listener.onScrolledToBottom();
      } else if (!requiringScroll) {
        requiringScroll = true;
        listener.onRequiresScroll();
      }
    }
  }
}
