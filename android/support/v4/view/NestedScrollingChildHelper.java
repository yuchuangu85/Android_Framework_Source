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


package android.support.v4.view;

import android.view.View;
import android.view.ViewParent;

/**
 * Google从逻辑上区分了滑动的两个角色：NestedScrollingParent简称ns parent，NestedScrollingChild简称ns child。对应了滑动布局中的外层滑动容器和内部滑动容器。
 * ns child在收到DOWN事件时，找到离自己最近的ns parent，与它进行绑定并关闭它的事件拦截机制。
 * ns child会在接下来的MOVE事件中判定出用户触发了滑动手势，并把事件拦截下来给自己消费。
 * 消费MOVE事件流时，对于每一个MOVE事件增加的滑动距离：
 * 4.1. ns child并不是直接自己消费，而是先将它交给ns parent，让ns parent可以在ns child滑动前进行消费。
 * 4.2. 如果ns parent没有消费或者滑动没消费完，ns child再消费剩下的滑动。
 * 4.3. 如果ns child消费后滑动还是有剩余，会把剩下的滑动距离再交给ns parent消费。
 * 4.4. 最后如果ns parent消费滑动后还有剩余，ns child可以做最终处理。
 * ns child在收到UP事件时，可以计算出需要滚动的速度，ns child对于速度的消费流程是：
 * 5.1 ns child在进行flying操作前，先询问ns parent是否需要消费该速度。如果ns parent消费该速度，后续就由ns parent带飞，自己就不消费该速度了。如果ns parent不消费，则ns child进行自己的flying操作。
 * 5.2 ns child在flying过程中，如果已经滚动到阈值速度仍没有消费完，会再次将速度分发给ns parent，将ns parent进行消费。
 *
 *
 * Helper class for implementing nested scrolling child views compatible with Android platform
 * versions earlier than Android 5.0 Lollipop (API 21).
 *
 * <p>{@link android.view.View View} subclasses should instantiate a final instance of this
 * class as a field at construction. For each <code>View</code> method that has a matching
 * method signature in this class, delegate the operation to the helper instance in an overridden
 * method implementation. This implements the standard framework policy for nested scrolling.</p>
 *
 * <p>Views invoking nested scrolling functionality should always do so from the relevant
 * {@link android.support.v4.view.ViewCompat}, {@link android.support.v4.view.ViewGroupCompat} or
 * {@link android.support.v4.view.ViewParentCompat} compatibility
 * shim static methods. This ensures interoperability with nested scrolling views on Android
 * 5.0 Lollipop and newer.</p>
 * <p>
 * 嵌套滑动子View的帮助类
 *
 * 参考：
 * https://segmentfault.com/a/1190000019272870
 * https://segmentfault.com/a/1190000019272870
 * https://blog.csdn.net/lmj623565791/article/details/52204039
 * https://juejin.im/entry/5ccf9c2451882541332f5a9c
 *
 */
public class NestedScrollingChildHelper {
    private final View mView;
    private ViewParent mNestedScrollingParent;
    private boolean mIsNestedScrollingEnabled;
    private int[] mTempNestedScrollConsumed;

    /**
     * Construct a new helper for a given view.
     */
    public NestedScrollingChildHelper(View view) {
        mView = view;
    }

    /**
     * Enable nested scrolling.
     * <p>
     * 自定义的嵌套滑动需要手动设置允许嵌套滑动
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @param enabled true to enable nested scrolling dispatch from this view, false otherwise
     */
    public void setNestedScrollingEnabled(boolean enabled) {
        if (mIsNestedScrollingEnabled) {
            ViewCompat.stopNestedScroll(mView);
        }
        mIsNestedScrollingEnabled = enabled;
    }

    /**
     * Check if nested scrolling is enabled for this view.
     * <p>
     * 是否允许嵌套滑动
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @return true if nested scrolling is enabled for this view
     */
    public boolean isNestedScrollingEnabled() {
        return mIsNestedScrollingEnabled;
    }

    /**
     * Check if this view has a nested scrolling parent view currently receiving events for
     * a nested scroll in progress.
     * <p>
     * 检测该View是否有一个嵌套滑动事件的嵌套滑动父布局
     *
     * <p>This is a delegate(代理) method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @return true if this view has a nested scrolling parent, false otherwise
     */
    public boolean hasNestedScrollingParent() {
        return mNestedScrollingParent != null;
    }

    /**
     * Start a new nested scroll for this view.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     * <p>
     * 开始嵌套滑动（down事件发起）
     *
     * @param axes Supported nested scroll axes.
     *             See {@link android.support.v4.view.NestedScrollingChild#startNestedScroll(int)}.
     *
     * @return true if a cooperating parent view was found and nested scrolling started successfully
     */
    public boolean startNestedScroll(int axes) {
        // 首次调用为false，该方法的后面部分开始初始化mNestedScrollingParent
        if (hasNestedScrollingParent()) {
            // Already in progress
            return true;
        }
        if (isNestedScrollingEnabled()) {
            ViewParent p = mView.getParent();
            View child = mView;
            while (p != null) {// 循环查找循环滑动的父布局
                // 重点在这------->
                // 在子View开始滑动前通知嵌套滑动父View，回调嵌套滑动父View的onStartNestedScroll()方法，
                // 嵌套滑动父View需要嵌套滑动则返回true：
                if (ViewParentCompat.onStartNestedScroll(p, child, mView, axes)) {
                    mNestedScrollingParent = p;
                    ViewParentCompat.onNestedScrollAccepted(p, child, mView, axes);
                    return true; // 已经找到了嵌套滑动的父View
                }
                if (p instanceof View) {
                    child = (View) p;
                }
                p = p.getParent();
            }
        }
        return false;
    }

    /**
     * Stop a nested scroll in progress.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     */
    public void stopNestedScroll() {
        if (mNestedScrollingParent != null) {
            ViewParentCompat.onStopNestedScroll(mNestedScrollingParent, mView);
            mNestedScrollingParent = null;
        }
    }

    /**
     * Dispatch one step of a nested scrolling operation to the current nested scrolling parent.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @return true if the parent consumed any of the nested scroll
     */
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
                                        int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow) {
        if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
            if (dxConsumed != 0 || dyConsumed != 0 || dxUnconsumed != 0 || dyUnconsumed != 0) {
                int startX = 0;
                int startY = 0;
                if (offsetInWindow != null) {
                    mView.getLocationInWindow(offsetInWindow);
                    startX = offsetInWindow[0];
                    startY = offsetInWindow[1];
                }

                // 然后调用父View的onNestedScroll
                ViewParentCompat.onNestedScroll(mNestedScrollingParent, mView, dxConsumed,
                        dyConsumed, dxUnconsumed, dyUnconsumed);

                if (offsetInWindow != null) {
                    mView.getLocationInWindow(offsetInWindow);
                    offsetInWindow[0] -= startX;
                    offsetInWindow[1] -= startY;
                }
                return true;
            } else if (offsetInWindow != null) {
                // No motion, no dispatch. Keep offsetInWindow up to date.
                offsetInWindow[0] = 0;
                offsetInWindow[1] = 0;
            }
        }
        return false;
    }

    /**
     * Dispatch one step of a nested pre-scrolling operation to the current nested scrolling parent.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @return true if the parent consumed any of the nested scroll
     * 嵌套滑动父布局是否消费滑动距离，如果父布局消费返回true，否则返回false
     */
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
        if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
            if (dx != 0 || dy != 0) {
                int startX = 0;
                int startY = 0;
                if (offsetInWindow != null) {
                    mView.getLocationInWindow(offsetInWindow);
                    startX = offsetInWindow[0];
                    startY = offsetInWindow[1];
                }

                if (consumed == null) {
                    if (mTempNestedScrollConsumed == null) {
                        mTempNestedScrollConsumed = new int[2];
                    }
                    consumed = mTempNestedScrollConsumed;
                }
                // 重点在这--------->，首先把consume封装好，consumed[0]表示X方向父View消耗的距离，
                // consumed[1]表示Y方向上父View消耗的距离，在父View处理前当然都是0
                consumed[0] = 0;
                consumed[1] = 0;
                // 然后调用父View的onNestedPreScroll并把当前的dx，dy以及用来保存父View需要消耗距离的consumed传递过去
                ViewParentCompat.onNestedPreScroll(mNestedScrollingParent, mView, dx, dy, consumed);

                if (offsetInWindow != null) {
                    mView.getLocationInWindow(offsetInWindow);
                    offsetInWindow[0] -= startX;
                    offsetInWindow[1] -= startY;
                }
                return consumed[0] != 0 || consumed[1] != 0;
            } else if (offsetInWindow != null) {
                offsetInWindow[0] = 0;
                offsetInWindow[1] = 0;
            }
        }
        return false;
    }

    /**
     * Dispatch a nested fling operation to the current nested scrolling parent.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @return true if the parent consumed the nested fling
     * 父布局消费滑动惯性事件，返回true，否则返回false
     */
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
            return ViewParentCompat.onNestedFling(mNestedScrollingParent, mView, velocityX,
                    velocityY, consumed);
        }
        return false;
    }

    /**
     * Dispatch a nested pre-fling operation to the current nested scrolling parent.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @return true if the parent consumed the nested fling
     */
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
            return ViewParentCompat.onNestedPreFling(mNestedScrollingParent, mView, velocityX,
                    velocityY);
        }
        return false;
    }

    /**
     * View subclasses should always call this method on their
     * <code>NestedScrollingChildHelper</code> when detached from a window.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     */
    public void onDetachedFromWindow() {
        ViewCompat.stopNestedScroll(mView);
    }

    /**
     * Called when a nested scrolling child stops its current nested scroll operation.
     *
     * <p>This is a delegate method. Call it from your {@link android.view.View View} subclass
     * method/{@link android.support.v4.view.NestedScrollingChild} interface method with the same
     * signature to implement the standard policy.</p>
     *
     * @param child Child view stopping its nested scroll. This may not be a direct child view.
     */
    public void onStopNestedScroll(View child) {
        ViewCompat.stopNestedScroll(mView);
    }
}
