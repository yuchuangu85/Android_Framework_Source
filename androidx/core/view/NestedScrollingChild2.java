/*
 * Copyright 2018 The Android Open Source Project
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


package androidx.core.view;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat.NestedScrollType;
import androidx.core.view.ViewCompat.ScrollAxis;

/**
 * This interface should be implemented by {@link View View} subclasses that wish
 * to support dispatching nested scrolling operations to a cooperating parent
 * {@link android.view.ViewGroup ViewGroup}.
 *
 * <p>Classes implementing this interface should create a final instance of a
 * {@link NestedScrollingChildHelper} as a field and delegate any View methods to the
 * <code>NestedScrollingChildHelper</code> methods of the same signature.</p>
 *
 * <p>Views invoking nested scrolling functionality should always do so from the relevant
 * {@link ViewCompat}, {@link ViewGroupCompat} or {@link ViewParentCompat} compatibility
 * shim static methods. This ensures interoperability with nested scrolling views on all versions
 * of Android.</p>
 * <p>
 * 参考：
 * https://blog.csdn.net/qq_42944793/article/details/88417127
 */
public interface NestedScrollingChild2 extends NestedScrollingChild {

    /**
     * Begin a nestable scroll operation along the given axes, for the given input type.
     *
     * <p>A view starting a nested scroll promises to abide by the following contract:</p>
     *
     * <p>The view will call startNestedScroll upon initiating a scroll operation. In the case
     * of a touch scroll type this corresponds to the initial {@link MotionEvent#ACTION_DOWN}.
     * In the case of touch scrolling the nested scroll will be terminated automatically in
     * the same manner as {@link ViewParent#requestDisallowInterceptTouchEvent(boolean)}.
     * In the event of programmatic scrolling the caller must explicitly call
     * {@link #stopNestedScroll(int)} to indicate the end of the nested scroll.</p>
     *
     * <p>If <code>startNestedScroll</code> returns true, a cooperative parent was found.
     * If it returns false the caller may ignore the rest of this contract until the next scroll.
     * Calling startNestedScroll while a nested scroll is already in progress will return true.</p>
     *
     * <p>At each incremental step of the scroll the caller should invoke
     * {@link #dispatchNestedPreScroll(int, int, int[], int[], int) dispatchNestedPreScroll}
     * once it has calculated the requested scrolling delta. If it returns true the nested scrolling
     * parent at least partially consumed the scroll and the caller should adjust the amount it
     * scrolls by.</p>
     *
     * <p>After applying the remainder of the scroll delta the caller should invoke
     * {@link #dispatchNestedScroll(int, int, int, int, int[], int) dispatchNestedScroll}, passing
     * both the delta consumed and the delta unconsumed. A nested scrolling parent may treat
     * these values differently. See
     * {@link NestedScrollingParent2#onNestedScroll(View, int, int, int, int, int)}.
     * </p>
     * <p>
     * 是否开始嵌套滑动，如果开启嵌套滑动，要通知嵌套滑动父View，调用父View的startNestedScroll方法，
     * 告诉父View需要父View配合子View处理onTouchEvent事件
     *
     * @param axes Flags consisting of a combination of {@link ViewCompat#SCROLL_AXIS_HORIZONTAL}
     *             and/or {@link ViewCompat#SCROLL_AXIS_VERTICAL}.
     * @param type the type of input which cause this scroll event
     *
     * @return true if a cooperative parent was found and nested scrolling has been enabled for
     * the current gesture.
     *
     * @see #stopNestedScroll(int)
     * @see #dispatchNestedPreScroll(int, int, int[], int[], int)
     * @see #dispatchNestedScroll(int, int, int, int, int[], int)
     */
    boolean startNestedScroll(@ScrollAxis int axes, @NestedScrollType int type);

    /**
     * Stop a nested scroll in progress for the given input type.
     *
     * <p>Calling this method when a nested scroll is not currently in progress is harmless.</p>
     *
     * @param type the type of input which cause this scroll event
     *
     * @see #startNestedScroll(int, int)
     */
    void stopNestedScroll(@NestedScrollType int type);

    /**
     * Returns true if this view has a nested scrolling parent for the given input type.
     *
     * <p>The presence of a nested scrolling parent indicates that this view has initiated
     * a nested scroll and it was accepted by an ancestor view further up the view hierarchy.</p>
     * <p>
     * 是否有嵌套滑动的父View
     *
     * @param type the type of input which cause this scroll event
     *
     * @return whether this view has a nested scrolling parent
     */
    boolean hasNestedScrollingParent(@NestedScrollType int type);

    /**
     * Dispatch one step of a nested scroll in progress.
     * <p>
     * 调度嵌套滑动的操作:
     * 向父View汇报滚动情况，包括子view消费的部分和子view没有消费的部分。
     * <p>
     * 父View消费子View剩余滚动后是否还有剩余。return true代表还有剩余
     *
     * <p>Implementations of views that support nested scrolling should call this to report
     * info about a scroll in progress to the current nested scrolling parent. If a nested scroll
     * is not currently in progress or nested scrolling is not
     * {@link #isNestedScrollingEnabled() enabled} for this view this method does nothing.</p>
     *
     * <p>Compatible View implementations should also call
     * {@link #dispatchNestedPreScroll(int, int, int[], int[], int) dispatchNestedPreScroll} before
     * consuming a component of the scroll event themselves.</p>
     *
     * @param dxConsumed     Horizontal distance in pixels consumed by this view during this scroll step
     *                       水平方向上消耗(滑动)的距离
     * @param dyConsumed     Vertical distance in pixels consumed by this view during this scroll step
     *                       竖直方向上消耗(滑动)的距离
     * @param dxUnconsumed   Horizontal scroll distance in pixels not consumed by this view
     *                       水平方向上未消耗(未滑动)的距离
     * @param dyUnconsumed   Horizontal scroll distance in pixels not consumed by this view
     *                       竖直方向上未消耗(未滑动)的距离
     * @param offsetInWindow Optional. If not null, on return this will contain the offset
     *                       in local view coordinates of this view from before this operation
     *                       to after it completes. View implementations may use this to adjust
     *                       expected input coordinate tracking.
     *                       offsetInWindow 窗体偏移量
     * @param type           the type of input which cause this scroll event
     *
     * @return true if the event was dispatched, false if it could not be dispatched.
     * 如果父view接受了它的滚动参数，进行了部分消费，则这个函数返回true，否则为false。
     *
     * @see #dispatchNestedPreScroll(int, int, int[], int[], int)
     */
    boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
                                 int dxUnconsumed, int dyUnconsumed, @Nullable int[] offsetInWindow,
                                 @NestedScrollType int type);

    /**
     * Dispatch one step of a nested scroll in progress before this view consumes any portion of it.
     *
     * 处理滑动事件前的准备工作：
     * 该方法的第三（consumed）第四个参数（offsetInWindow）返回父view消费掉的scroll长度和子View的窗体偏移量。
     * 如果这个scroll没有被消费完，则子view进行处理剩下的一些距离，由于窗体进行了移动，如果你记录了手指最后的位置，
     * 需要根据第四个参数offsetInWindow计算偏移量，才能保证下一次的touch事件的计算是正确的。
     *
     * 消费滑动时间前，先让嵌套滑动父View消费
     *
     * <p>Nested pre-scroll events are to nested scroll events what touch intercept is to touch.
     * <code>dispatchNestedPreScroll</code> offers an opportunity for the parent view in a nested
     * scrolling operation to consume some or all of the scroll operation before the child view
     * consumes it.</p>
     *
     * @param dx             Horizontal scroll distance in pixels
     * @param dy             Vertical scroll distance in pixels
     * @param consumed       Output. If not null, consumed[0] will contain the consumed component of dx
     *                       and consumed[1] the consumed dy.
     *                        父view消耗的距离
     * @param offsetInWindow Optional. If not null, on return this will contain the offset
     *                       in local view coordinates of this view from before this operation
     *                       to after it completes. View implementations may use this to adjust
     *                       expected input coordinate tracking.
     * @param type           the type of input which cause this scroll event
     *
     * @return true if the parent consumed some or all of the scroll delta
     * 如果父view接受了它的滚动参数，进行了部分消费，则这个函数返回true，否则为false。
     *
     * @see #dispatchNestedScroll(int, int, int, int, int[], int)
     */
    boolean dispatchNestedPreScroll(int dx, int dy, @Nullable int[] consumed,
                                    @Nullable int[] offsetInWindow, @NestedScrollType int type);
}
