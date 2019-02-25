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

package android.support.v4.view;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.annotation.CallSuper;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.support.v4.view.accessibility.AccessibilityRecordCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Layout manager that allows the user to flip left and right
 * through pages of data.  You supply an implementation of a
 * {@link PagerAdapter} to generate the pages that the view shows.
 * <p>
 * <p>ViewPager is most often used in conjunction with {@link android.app.Fragment},
 * which is a convenient way to supply and manage the lifecycle of each page.
 * There are standard adapters implemented for using fragments with the ViewPager,
 * which cover the most common use cases.  These are
 * {@link android.support.v4.app.FragmentPagerAdapter} and
 * {@link android.support.v4.app.FragmentStatePagerAdapter}; each of these
 * classes have simple code showing how to build a full user interface
 * with them.
 * <p>
 * <p>Views which are annotated with the {@link DecorView} annotation are treated as
 * part of the view pagers 'decor'. Each decor view's position can be controlled via
 * its {@code android:layout_gravity} attribute. For example:
 * <p>
 * <pre>
 * &lt;android.support.v4.view.ViewPager
 *     android:layout_width=&quot;match_parent&quot;
 *     android:layout_height=&quot;match_parent&quot;&gt;
 *
 *     &lt;android.support.v4.view.PagerTitleStrip
 *         android:layout_width=&quot;match_parent&quot;
 *         android:layout_height=&quot;wrap_content&quot;
 *         android:layout_gravity=&quot;top&quot; /&gt;
 *
 * &lt;/android.support.v4.view.ViewPager&gt;
 * </pre>
 * <p>
 * <p>For more information about how to use ViewPager, read <a
 * href="{@docRoot}training/implementing-navigation/lateral.html">Creating Swipe Views with
 * Tabs</a>.</p>
 * <p>
 * <p>Below is a more complicated example of ViewPager, using it in conjunction
 * with {@link android.app.ActionBar} tabs.  You can find other examples of using
 * ViewPager in the API 4+ Support Demos and API 13+ Support Demos sample code.
 * <p>
 * {@sample frameworks/support/samples/Support13Demos/src/com/example/android/supportv13/app/ActionBarTabsPager.java
 * complete}
 */
public class ViewPager extends ViewGroup {
    private static final String TAG = "ViewPager";
    private static final boolean DEBUG = false;

    private static final boolean USE_CACHE = false;

    private static final int DEFAULT_OFFSCREEN_PAGES = 1;
    private static final int MAX_SETTLE_DURATION = 600; // ms
    private static final int MIN_DISTANCE_FOR_FLING = 25; // dips

    private static final int DEFAULT_GUTTER_SIZE = 16; // dips

    private static final int MIN_FLING_VELOCITY = 400; // dips

    static final int[] LAYOUT_ATTRS = new int[]{
            android.R.attr.layout_gravity
    };

    /**
     * Used to track what the expected number of items in the adapter should be.
     * If the app changes this when we don't expect it, we'll throw a big obnoxious exception.
     */
    private int mExpectedAdapterCount;

    static class ItemInfo {
        Object object;      // childView
        int position;       // childView在Adapter中的位置
        boolean scrolling;  // 是否在滚动
        // childView占的Viewpager宽度的比例[0-1]，默认是1，这个值来可以设置让一个Viewpager显示多少个childView
        float widthFactor;
        float offset;       // pager偏移量
    }

    // 页面排序
    private static final Comparator<ItemInfo> COMPARATOR = new Comparator<ItemInfo>() {
        @Override
        public int compare(ItemInfo lhs, ItemInfo rhs) {
            return lhs.position - rhs.position;
        }
    };

    // 插值器，就是根据不同的时间控制滑动速度
    private static final Interpolator sInterpolator = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    // 表示已经缓存的页面信息
    private final ArrayList<ItemInfo> mItems = new ArrayList<ItemInfo>();
    private final ItemInfo mTempItem = new ItemInfo();

    private final Rect mTempRect = new Rect();

    // 页面适配器
    PagerAdapter mAdapter;
    int mCurItem;   // Index of currently displayed page.
    private int mRestoredCurItem = -1;
    private Parcelable mRestoredAdapterState = null;
    private ClassLoader mRestoredClassLoader = null;

    private Scroller mScroller;
    private boolean mIsScrollStarted;

    private PagerObserver mObserver;

    private int mPageMargin;
    private Drawable mMarginDrawable;
    private int mTopPageBounds;
    private int mBottomPageBounds;

    // Offsets of the first and last items, if known.
    // Set during population, used to determine if we are at the beginning
    // or end of the pager data set during touch scrolling.
    // 第一个页卡的滑动偏移量
    private float mFirstOffset = -Float.MAX_VALUE;
    // 最后一个页卡的偏移量
    private float mLastOffset = Float.MAX_VALUE;

    private int mChildWidthMeasureSpec;
    private int mChildHeightMeasureSpec;
    private boolean mInLayout;

    private boolean mScrollingCacheEnabled;

    private boolean mPopulatePending;
    private int mOffscreenPageLimit = DEFAULT_OFFSCREEN_PAGES;

    private boolean mIsBeingDragged;
    private boolean mIsUnableToDrag;
    private int mDefaultGutterSize;
    private int mGutterSize;
    private int mTouchSlop;
    /**
     * Position of the last motion event.
     */
    private float mLastMotionX;
    private float mLastMotionY;
    private float mInitialMotionX;
    private float mInitialMotionY;
    /**
     * ID of the active pointer. This is used to retain consistency during
     * drags/flings if multiple pointers are used.
     * 触摸手指的id。多点触摸时使用
     */
    private int mActivePointerId = INVALID_POINTER;
    /**
     * Sentinel value for no current active pointer.
     * Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * Determines speed during touch scrolling
     * 滑动速度追踪器
     */
    private VelocityTracker mVelocityTracker;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mFlingDistance;
    private int mCloseEnough;

    // If the pager is at least this close to its final position, complete the scroll
    // on touch down and let the user interact with the content inside instead of
    // "catching" the flinging pager.
    private static final int CLOSE_ENOUGH = 2; // dp

    private boolean mFakeDragging;
    private long mFakeDragBeginTime;

    private EdgeEffectCompat mLeftEdge;
    private EdgeEffectCompat mRightEdge;

    private boolean mFirstLayout = true;
    private boolean mNeedCalculatePageOffsets = false;
    private boolean mCalledSuper;
    private int mDecorChildCount;

    private List<OnPageChangeListener> mOnPageChangeListeners;
    private OnPageChangeListener mOnPageChangeListener;
    private OnPageChangeListener mInternalPageChangeListener;
    private List<OnAdapterChangeListener> mAdapterChangeListeners;
    private PageTransformer mPageTransformer;
    private int mPageTransformerLayerType;
    private Method mSetChildrenDrawingOrderEnabled;

    private static final int DRAW_ORDER_DEFAULT = 0;
    private static final int DRAW_ORDER_FORWARD = 1;
    private static final int DRAW_ORDER_REVERSE = 2;
    private int mDrawingOrder;
    private ArrayList<View> mDrawingOrderedChildren;
    private static final ViewPositionComparator sPositionComparator = new ViewPositionComparator();

    /**
     * Indicates that the pager is in an idle, settled state. The current page
     * is fully in view and no animation is in progress.
     */
    public static final int SCROLL_STATE_IDLE = 0;// 空闲

    /**
     * Indicates that the pager is currently being dragged by the user.
     */
    public static final int SCROLL_STATE_DRAGGING = 1;// 拖动

    /**
     * Indicates that the pager is in the process of settling to a final position.
     */
    public static final int SCROLL_STATE_SETTLING = 2;// 滑动结束

    private final Runnable mEndScrollRunnable = new Runnable() {
        @Override
        public void run() {
            setScrollState(SCROLL_STATE_IDLE);
            populate();
        }
    };

    private int mScrollState = SCROLL_STATE_IDLE;

    /**
     * Callback interface for responding to changing state of the selected page.
     */
    public interface OnPageChangeListener {

        /**
         * This method will be invoked when the current page is scrolled, either as part
         * of a programmatically initiated smooth scroll or a user initiated touch scroll.
         *
         * @param position             Position index of the first page currently being displayed.
         *                             Page position+1 will be visible if positionOffset is nonzero.
         * @param positionOffset       Value from [0, 1) indicating the offset from the page at position.
         * @param positionOffsetPixels Value in pixels indicating the offset from position.
         */
        void onPageScrolled(int position, float positionOffset, int positionOffsetPixels);

        /**
         * This method will be invoked when a new page becomes selected. Animation is not
         * necessarily complete.
         *
         * @param position Position index of the new selected page.
         */
        void onPageSelected(int position);

        /**
         * Called when the scroll state changes. Useful for discovering when the user
         * begins dragging, when the pager is automatically settling to the current page,
         * or when it is fully stopped/idle.
         *
         * @param state The new scroll state.
         *
         * @see ViewPager#SCROLL_STATE_IDLE
         * @see ViewPager#SCROLL_STATE_DRAGGING
         * @see ViewPager#SCROLL_STATE_SETTLING
         */
        void onPageScrollStateChanged(int state);
    }

    /**
     * Simple implementation of the {@link OnPageChangeListener} interface with stub
     * implementations of each method. Extend this if you do not intend to override
     * every method of {@link OnPageChangeListener}.
     */
    public static class SimpleOnPageChangeListener implements OnPageChangeListener {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // This space for rent
        }

        @Override
        public void onPageSelected(int position) {
            // This space for rent
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            // This space for rent
        }
    }

    /**
     * A PageTransformer is invoked whenever a visible/attached page is scrolled.
     * This offers an opportunity for the application to apply a custom transformation
     * to the page views using animation properties.
     * <p>
     * <p>As property animation is only supported as of Android 3.0 and forward,
     * setting a PageTransformer on a ViewPager on earlier platform versions will
     * be ignored.</p>
     */
    public interface PageTransformer {
        /**
         * Apply a property transformation to the given page.
         *
         * @param page     Apply the transformation to this page
         * @param position Position of page relative to the current front-and-center
         *                 position of the pager. 0 is front and center. 1 is one full
         *                 page position to the right, and -1 is one page position to the left.
         */
        void transformPage(View page, float position);
    }

    /**
     * Callback interface for responding to adapter changes.
     */
    public interface OnAdapterChangeListener {
        /**
         * Called when the adapter for the given view pager has changed.
         *
         * @param viewPager  ViewPager where the adapter change has happened
         * @param oldAdapter the previously set adapter
         * @param newAdapter the newly set adapter
         */
        void onAdapterChanged(@NonNull ViewPager viewPager,
                              @Nullable PagerAdapter oldAdapter, @Nullable PagerAdapter newAdapter);
    }

    /**
     * Annotation which allows marking of views to be decoration views when added to a view
     * pager.
     * <p>
     * <p>Views marked with this annotation can be added to the view pager with a layout resource.
     * An example being {@link PagerTitleStrip}.</p>
     * <p>
     * <p>You can also control whether a view is a decor view but setting
     * {@link LayoutParams#isDecor} on the child's layout params.</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    public @interface DecorView {
    }

    public ViewPager(Context context) {
        super(context);
        initViewPager();
    }

    public ViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        initViewPager();
    }

    void initViewPager() {
        setWillNotDraw(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setFocusable(true);
        final Context context = getContext();
        mScroller = new Scroller(context, sInterpolator);
        final ViewConfiguration configuration = ViewConfiguration.get(context);// 标准常量
        final float density = context.getResources().getDisplayMetrics().density;// 屏幕密度

        mTouchSlop = configuration.getScaledPagingTouchSlop();// 最小滑动阈值
        mMinimumVelocity = (int) (MIN_FLING_VELOCITY * density);// 允许执行一个fling手势的最大速度
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mLeftEdge = new EdgeEffectCompat(context);
        mRightEdge = new EdgeEffectCompat(context);

        mFlingDistance = (int) (MIN_DISTANCE_FOR_FLING * density);
        mCloseEnough = (int) (CLOSE_ENOUGH * density);
        mDefaultGutterSize = (int) (DEFAULT_GUTTER_SIZE * density);

        ViewCompat.setAccessibilityDelegate(this, new MyAccessibilityDelegate());

        if (ViewCompat.getImportantForAccessibility(this)
                == ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            ViewCompat.setImportantForAccessibility(this,
                    ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        // 兼容帮助类
        ViewCompat.setOnApplyWindowInsetsListener(this,
                new android.support.v4.view.OnApplyWindowInsetsListener() {
                    private final Rect mTempRect = new Rect();

                    @Override
                    public WindowInsetsCompat onApplyWindowInsets(final View v,
                                                                  final WindowInsetsCompat originalInsets) {
                        // First let the ViewPager itself try and consume them...
                        final WindowInsetsCompat applied =
                                ViewCompat.onApplyWindowInsets(v, originalInsets);
                        if (applied.isConsumed()) {
                            // If the ViewPager consumed all insets, return now
                            return applied;
                        }

                        // Now we'll manually dispatch the insets to our children. Since ViewPager
                        // children are always full-height, we do not want to use the standard
                        // ViewGroup dispatchApplyWindowInsets since if child 0 consumes them,
                        // the rest of the children will not receive any insets. To workaround this
                        // we manually dispatch the applied insets, not allowing children to
                        // consume them from each other. We do however keep track of any insets
                        // which are consumed, returning the union of our children's consumption
                        final Rect res = mTempRect;
                        res.left = applied.getSystemWindowInsetLeft();
                        res.top = applied.getSystemWindowInsetTop();
                        res.right = applied.getSystemWindowInsetRight();
                        res.bottom = applied.getSystemWindowInsetBottom();

                        for (int i = 0, count = getChildCount(); i < count; i++) {
                            final WindowInsetsCompat childInsets = ViewCompat
                                    .dispatchApplyWindowInsets(getChildAt(i), applied);
                            // Now keep track of any consumed by tracking each dimension's min
                            // value
                            res.left = Math.min(childInsets.getSystemWindowInsetLeft(),
                                    res.left);
                            res.top = Math.min(childInsets.getSystemWindowInsetTop(),
                                    res.top);
                            res.right = Math.min(childInsets.getSystemWindowInsetRight(),
                                    res.right);
                            res.bottom = Math.min(childInsets.getSystemWindowInsetBottom(),
                                    res.bottom);
                        }

                        // Now return a new WindowInsets, using the consumed window insets
                        return applied.replaceSystemWindowInsets(
                                res.left, res.top, res.right, res.bottom);
                    }
                });
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mEndScrollRunnable);
        // To be on the safe side, abort the scroller
        if ((mScroller != null) && !mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
        super.onDetachedFromWindow();
    }

    void setScrollState(int newState) {
        if (mScrollState == newState) {
            return;
        }

        mScrollState = newState;
        if (mPageTransformer != null) {
            // PageTransformers can do complex things that benefit from hardware layers.
            enableLayers(newState != SCROLL_STATE_IDLE);
        }
        dispatchOnScrollStateChanged(newState);
    }

    /**
     * Set a PagerAdapter that will supply views for this pager as needed.
     *
     * @param adapter Adapter to use
     */
    public void setAdapter(PagerAdapter adapter) {
        // 如果已经设置过PagerAdapter，即mAdapter != null，做一些清理工作
        if (mAdapter != null) {
            // 清除观察者
            mAdapter.setViewPagerObserver(null);
            // 回调startUpdate函数，告诉PagerAdapter开始更新要显示的页面
            mAdapter.startUpdate(this);
            // 如果之前保存有页面，则将之前所有的页面destroy掉
            for (int i = 0; i < mItems.size(); i++) {
                final ItemInfo ii = mItems.get(i);
                mAdapter.destroyItem(this, ii.position, ii.object);
            }
            // 回调finishUpdate，告诉PagerAdapter结束更新
            mAdapter.finishUpdate(this);
            // 将所有的页面清除
            mItems.clear();
            // 将所有的非Decor View移除，即将页面移除
            removeNonDecorViews();
            // 当前的显示页面重置到第一个
            mCurItem = 0;
            // 滑动重置到(0,0)位置
            scrollTo(0, 0);
        }

        // 保存上一次的PagerAdapter
        final PagerAdapter oldAdapter = mAdapter;
        // 设置mAdapter为新的PagerAdapter
        mAdapter = adapter;
        // 设置期望的适配器中的页面数量为0个
        mExpectedAdapterCount = 0;

        // 如果设置的PagerAdapter不为null
        if (mAdapter != null) {
            // 确保观察者不为null，观察者主要是用于监视数据源的内容发生变化
            if (mObserver == null) {
                mObserver = new PagerObserver();
            }
            // 将观察者设置到PagerAdapter中
            mAdapter.setViewPagerObserver(mObserver);
            mPopulatePending = false;
            // 保存上一次是否是第一次Layout
            final boolean wasFirstLayout = mFirstLayout;
            // 设定当前为第一次Layout
            mFirstLayout = true;
            // 更新期望的数据源中页面个数
            mExpectedAdapterCount = mAdapter.getCount();
            // 如果有数据需要恢复
            if (mRestoredCurItem >= 0) {
                // 回调PagerAdapter的restoreState函数
                mAdapter.restoreState(mRestoredAdapterState, mRestoredClassLoader);
                setCurrentItemInternal(mRestoredCurItem, false, true);
                // 标记无需再恢复
                mRestoredCurItem = -1;
                mRestoredAdapterState = null;
                mRestoredClassLoader = null;
            } else if (!wasFirstLayout) {// 如果在此之前不是第一次Layout
                // 由于ViewPager并不是将所有页面作为页卡，
                // 而是最多缓存用户指定缓存个数*2（左右两边，可能左边或右边没有那么多页面）
                // 因此需要创建和销毁页面，populate主要工作就是这些
                populate();
            } else {
                // 重新布局（Layout）
                requestLayout();
            }
        }

        // Dispatch the change to any listeners
        // 如果PagerAdapter发生变化，并且设置了OnAdapterChangeListener监听器
        // 则回调OnAdapterChangeListener的onAdapterChanged函数
        if (mAdapterChangeListeners != null && !mAdapterChangeListeners.isEmpty()) {
            for (int i = 0, count = mAdapterChangeListeners.size(); i < count; i++) {
                mAdapterChangeListeners.get(i).onAdapterChanged(this, oldAdapter, adapter);
            }
        }
    }

    private void removeNonDecorViews() {
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.isDecor) {
                removeViewAt(i);
                i--;
            }
        }
    }

    /**
     * Retrieve the current adapter supplying pages.
     *
     * @return The currently registered PagerAdapter
     */
    public PagerAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Add a listener that will be invoked whenever the adapter for this ViewPager changes.
     *
     * @param listener listener to add
     */
    public void addOnAdapterChangeListener(@NonNull OnAdapterChangeListener listener) {
        if (mAdapterChangeListeners == null) {
            mAdapterChangeListeners = new ArrayList<>();
        }
        mAdapterChangeListeners.add(listener);
    }

    /**
     * Remove a listener that was previously added via
     * {@link #addOnAdapterChangeListener(OnAdapterChangeListener)}.
     *
     * @param listener listener to remove
     */
    public void removeOnAdapterChangeListener(@NonNull OnAdapterChangeListener listener) {
        if (mAdapterChangeListeners != null) {
            mAdapterChangeListeners.remove(listener);
        }
    }

    private int getClientWidth() {
        return getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
    }

    /**
     * Set the currently selected page. If the ViewPager has already been through its first
     * layout with its current adapter there will be a smooth animated transition between
     * the current item and the specified item.
     *
     * @param item Item index to select
     */
    public void setCurrentItem(int item) {
        mPopulatePending = false;
        setCurrentItemInternal(item, !mFirstLayout, false);
    }

    /**
     * Set the currently selected page.
     *
     * @param item         Item index to select
     * @param smoothScroll True to smoothly scroll to the new item, false to transition immediately
     */
    public void setCurrentItem(int item, boolean smoothScroll) {
        mPopulatePending = false;
        setCurrentItemInternal(item, smoothScroll, false);
    }

    public int getCurrentItem() {
        return mCurItem;
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always) {
        setCurrentItemInternal(item, smoothScroll, always, 0);
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always, int velocity) {
        if (mAdapter == null || mAdapter.getCount() <= 0) {
            setScrollingCacheEnabled(false);
            return;
        }
        if (!always && mCurItem == item && mItems.size() != 0) {
            setScrollingCacheEnabled(false);
            return;
        }

        if (item < 0) {
            item = 0;
        } else if (item >= mAdapter.getCount()) {
            item = mAdapter.getCount() - 1;
        }
        final int pageLimit = mOffscreenPageLimit;
        if (item > (mCurItem + pageLimit) || item < (mCurItem - pageLimit)) {
            // We are doing a jump by more than one page.  To avoid
            // glitches, we want to keep all current pages in the view
            // until the scroll ends.
            for (int i = 0; i < mItems.size(); i++) {
                mItems.get(i).scrolling = true;
            }
        }
        final boolean dispatchSelected = mCurItem != item;

        if (mFirstLayout) {
            // We don't have any idea how big we are yet and shouldn't have any pages either.
            // Just set things up and let the pending layout handle things.
            mCurItem = item;
            if (dispatchSelected) {
                dispatchOnPageSelected(item);
            }
            requestLayout();
        } else {
            populate(item);
            scrollToItem(item, smoothScroll, velocity, dispatchSelected);
        }
    }

    private void scrollToItem(int item, boolean smoothScroll, int velocity,
                              boolean dispatchSelected) {
        final ItemInfo curInfo = infoForPosition(item);
        int destX = 0;
        if (curInfo != null) {
            final int width = getClientWidth();
            destX = (int) (width * Math.max(mFirstOffset,
                    Math.min(curInfo.offset, mLastOffset)));
        }
        if (smoothScroll) {
            smoothScrollTo(destX, 0, velocity);
            if (dispatchSelected) {
                dispatchOnPageSelected(item);
            }
        } else {
            //是否需要分发OnPageSelected回调
            if (dispatchSelected) {
                dispatchOnPageSelected(item);
            }
            completeScroll(false);
            scrollTo(destX, 0);
            pageScrolled(destX);
        }
    }

    /**
     * Set a listener that will be invoked whenever the page changes or is incrementally
     * scrolled. See {@link OnPageChangeListener}.
     *
     * @param listener Listener to set
     *
     * @deprecated Use {@link #addOnPageChangeListener(OnPageChangeListener)}
     * and {@link #removeOnPageChangeListener(OnPageChangeListener)} instead.
     */
    @Deprecated
    public void setOnPageChangeListener(OnPageChangeListener listener) {
        mOnPageChangeListener = listener;
    }

    /**
     * Add a listener that will be invoked whenever the page changes or is incrementally
     * scrolled. See {@link OnPageChangeListener}.
     * <p>
     * <p>Components that add a listener should take care to remove it when finished.
     * Other components that take ownership of a view may call {@link #clearOnPageChangeListeners()}
     * to remove all attached listeners.</p>
     *
     * @param listener listener to add
     */
    public void addOnPageChangeListener(OnPageChangeListener listener) {
        if (mOnPageChangeListeners == null) {
            mOnPageChangeListeners = new ArrayList<>();
        }
        mOnPageChangeListeners.add(listener);
    }

    /**
     * Remove a listener that was previously added via
     * {@link #addOnPageChangeListener(OnPageChangeListener)}.
     *
     * @param listener listener to remove
     */
    public void removeOnPageChangeListener(OnPageChangeListener listener) {
        if (mOnPageChangeListeners != null) {
            mOnPageChangeListeners.remove(listener);
        }
    }

    /**
     * Remove all listeners that are notified of any changes in scroll state or position.
     */
    public void clearOnPageChangeListeners() {
        if (mOnPageChangeListeners != null) {
            mOnPageChangeListeners.clear();
        }
    }

    /**
     * Sets a {@link PageTransformer} that will be called for each attached page whenever
     * the scroll position is changed. This allows the application to apply custom property
     * transformations to each page, overriding the default sliding behavior.
     * <p>
     * <p><em>Note:</em> Prior to Android 3.0 the property animation APIs did not exist.
     * As a result, setting a PageTransformer prior to Android 3.0 (API 11) will have no effect.
     * By default, calling this method will cause contained pages to use
     * {@link ViewCompat#LAYER_TYPE_HARDWARE}. This layer type allows custom alpha transformations,
     * but it will cause issues if any of your pages contain a {@link android.view.SurfaceView}
     * and you have not called {@link android.view.SurfaceView#setZOrderOnTop(boolean)} to put that
     * {@link android.view.SurfaceView} above your app content. To disable this behavior, call
     * {@link #setPageTransformer(boolean, PageTransformer, int)} and pass
     * {@link ViewCompat#LAYER_TYPE_NONE} for {@code pageLayerType}.</p>
     *
     * @param reverseDrawingOrder true if the supplied PageTransformer requires page views
     *                            to be drawn from last to first instead of first to last.
     * @param transformer         PageTransformer that will modify each page's animation properties
     */
    public void setPageTransformer(boolean reverseDrawingOrder, PageTransformer transformer) {
        setPageTransformer(reverseDrawingOrder, transformer, ViewCompat.LAYER_TYPE_HARDWARE);
    }

    /**
     * Sets a {@link PageTransformer} that will be called for each attached page whenever
     * the scroll position is changed. This allows the application to apply custom property
     * transformations to each page, overriding the default sliding behavior.
     * <p>
     * <p><em>Note:</em> Prior to Android 3.0 ({@link Build.VERSION_CODES#HONEYCOMB API 11}),
     * the property animation APIs did not exist. As a result, setting a PageTransformer prior
     * to API 11 will have no effect.</p>
     *
     * @param reverseDrawingOrder true if the supplied PageTransformer requires page views
     *                            to be drawn from last to first instead of first to last.
     * @param transformer         PageTransformer that will modify each page's animation properties
     * @param pageLayerType       View layer type that should be used for ViewPager pages. It should be
     *                            either {@link ViewCompat#LAYER_TYPE_HARDWARE},
     *                            {@link ViewCompat#LAYER_TYPE_SOFTWARE}, or
     *                            {@link ViewCompat#LAYER_TYPE_NONE}.
     */
    public void setPageTransformer(boolean reverseDrawingOrder, PageTransformer transformer,
                                   int pageLayerType) {
        if (Build.VERSION.SDK_INT >= 11) {
            final boolean hasTransformer = transformer != null;
            final boolean needsPopulate = hasTransformer != (mPageTransformer != null);
            mPageTransformer = transformer;
            setChildrenDrawingOrderEnabledCompat(hasTransformer);
            if (hasTransformer) {
                mDrawingOrder = reverseDrawingOrder ? DRAW_ORDER_REVERSE : DRAW_ORDER_FORWARD;
                mPageTransformerLayerType = pageLayerType;
            } else {
                mDrawingOrder = DRAW_ORDER_DEFAULT;
            }
            if (needsPopulate) populate();
        }
    }

    void setChildrenDrawingOrderEnabledCompat(boolean enable) {
        if (Build.VERSION.SDK_INT >= 7) {
            if (mSetChildrenDrawingOrderEnabled == null) {
                try {
                    mSetChildrenDrawingOrderEnabled = ViewGroup.class.getDeclaredMethod(
                            "setChildrenDrawingOrderEnabled", new Class[]{Boolean.TYPE});
                } catch (NoSuchMethodException e) {
                    Log.e(TAG, "Can't find setChildrenDrawingOrderEnabled", e);
                }
            }
            try {
                mSetChildrenDrawingOrderEnabled.invoke(this, enable);
            } catch (Exception e) {
                Log.e(TAG, "Error changing children drawing order", e);
            }
        }
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        final int index = mDrawingOrder == DRAW_ORDER_REVERSE ? childCount - 1 - i : i;
        final int result =
                ((LayoutParams) mDrawingOrderedChildren.get(index).getLayoutParams()).childIndex;
        return result;
    }

    /**
     * Set a separate OnPageChangeListener for internal use by the support library.
     *
     * @param listener Listener to set
     *
     * @return The old listener that was set, if any.
     */
    OnPageChangeListener setInternalPageChangeListener(OnPageChangeListener listener) {
        OnPageChangeListener oldListener = mInternalPageChangeListener;
        mInternalPageChangeListener = listener;
        return oldListener;
    }

    /**
     * Returns the number of pages that will be retained to either side of the
     * current page in the view hierarchy in an idle state. Defaults to 1.
     *
     * @return How many pages will be kept offscreen on either side
     *
     * @see #setOffscreenPageLimit(int)
     */
    public int getOffscreenPageLimit() {
        return mOffscreenPageLimit;
    }

    /**
     * Set the number of pages that should be retained to either side of the
     * current page in the view hierarchy in an idle state. Pages beyond this
     * limit will be recreated from the adapter when needed.
     * <p>
     * <p>This is offered as an optimization. If you know in advance the number
     * of pages you will need to support or have lazy-loading mechanisms in place
     * on your pages, tweaking this setting can have benefits in perceived smoothness
     * of paging animations and interaction. If you have a small number of pages (3-4)
     * that you can keep active all at once, less time will be spent in layout for
     * newly created view subtrees as the user pages back and forth.</p>
     * <p>
     * <p>You should keep this limit low, especially if your pages have complex layouts.
     * This setting defaults to 1.</p>
     *
     * @param limit How many pages will be kept offscreen in an idle state.
     */
    public void setOffscreenPageLimit(int limit) {
        if (limit < DEFAULT_OFFSCREEN_PAGES) {
            Log.w(TAG, "Requested offscreen page limit " + limit + " too small; defaulting to "
                    + DEFAULT_OFFSCREEN_PAGES);
            limit = DEFAULT_OFFSCREEN_PAGES;
        }
        if (limit != mOffscreenPageLimit) {
            mOffscreenPageLimit = limit;
            populate();
        }
    }

    /**
     * Set the margin between pages.
     *
     * @param marginPixels Distance between adjacent pages in pixels
     *
     * @see #getPageMargin()
     * @see #setPageMarginDrawable(Drawable)
     * @see #setPageMarginDrawable(int)
     */
    public void setPageMargin(int marginPixels) {
        final int oldMargin = mPageMargin;
        mPageMargin = marginPixels;

        final int width = getWidth();
        recomputeScrollPosition(width, width, marginPixels, oldMargin);

        requestLayout();
    }

    /**
     * Return the margin between pages.
     *
     * @return The size of the margin in pixels
     */
    public int getPageMargin() {
        return mPageMargin;
    }

    /**
     * Set a drawable that will be used to fill the margin between pages.
     *
     * @param d Drawable to display between pages
     */
    public void setPageMarginDrawable(Drawable d) {
        mMarginDrawable = d;
        if (d != null) refreshDrawableState();
        setWillNotDraw(d == null);
        invalidate();
    }

    /**
     * Set a drawable that will be used to fill the margin between pages.
     *
     * @param resId Resource ID of a drawable to display between pages
     */
    public void setPageMarginDrawable(@DrawableRes int resId) {
        setPageMarginDrawable(ContextCompat.getDrawable(getContext(), resId));
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mMarginDrawable;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        final Drawable d = mMarginDrawable;
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    // We want the duration of the page snap animation to be influenced by the distance that
    // the screen has to travel, however, we don't want this duration to be effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect that the distance
    // of travel has on the overall snap duration.
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     *
     * @param x the number of pixels to scroll by on the X axis
     * @param y the number of pixels to scroll by on the Y axis
     */
    void smoothScrollTo(int x, int y) {
        smoothScrollTo(x, y, 0);
    }

    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     *
     * @param x        the number of pixels to scroll by on the X axis
     * @param y        the number of pixels to scroll by on the Y axis
     * @param velocity the velocity associated with a fling, if applicable. (0 otherwise)
     */
    void smoothScrollTo(int x, int y, int velocity) {
        if (getChildCount() == 0) {
            // Nothing to do.
            setScrollingCacheEnabled(false);
            return;
        }

        int sx;
        boolean wasScrolling = (mScroller != null) && !mScroller.isFinished();
        if (wasScrolling) {
            // We're in the middle of a previously initiated scrolling. Check to see
            // whether that scrolling has actually started (if we always call getStartX
            // we can get a stale value from the scroller if it hadn't yet had its first
            // computeScrollOffset call) to decide what is the current scrolling position.
            sx = mIsScrollStarted ? mScroller.getCurrX() : mScroller.getStartX();
            // And abort the current scrolling.
            mScroller.abortAnimation();
            setScrollingCacheEnabled(false);
        } else {
            sx = getScrollX();
        }
        int sy = getScrollY();
        int dx = x - sx;
        int dy = y - sy;
        if (dx == 0 && dy == 0) {
            completeScroll(false);
            populate();
            setScrollState(SCROLL_STATE_IDLE);
            return;
        }

        setScrollingCacheEnabled(true);
        setScrollState(SCROLL_STATE_SETTLING);

        final int width = getClientWidth();
        final int halfWidth = width / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dx) / width);
        final float distance = halfWidth + halfWidth
                * distanceInfluenceForSnapDuration(distanceRatio);

        int duration;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float pageWidth = width * mAdapter.getPageWidth(mCurItem);
            final float pageDelta = (float) Math.abs(dx) / (pageWidth + mPageMargin);
            duration = (int) ((pageDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);

        // Reset the "scroll started" flag. It will be flipped to true in all places
        // where we call computeScrollOffset().
        mIsScrollStarted = false;
        mScroller.startScroll(sx, sy, dx, dy, duration);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    ItemInfo addNewItem(int position, int index) {
        ItemInfo ii = new ItemInfo();
        ii.position = position;
        ii.object = mAdapter.instantiateItem(this, position);// 通过Adapter创建一个childView
        ii.widthFactor = mAdapter.getPageWidth(position);// 默认返回1.0f
        if (index < 0 || index >= mItems.size()) {
            mItems.add(ii);
        } else {
            mItems.add(index, ii);
        }
        return ii;
    }

    void dataSetChanged() {
        // This method only gets called if our observer is attached, so mAdapter is non-null.

        final int adapterCount = mAdapter.getCount();
        mExpectedAdapterCount = adapterCount;
        boolean needPopulate = mItems.size() < mOffscreenPageLimit * 2 + 1
                && mItems.size() < adapterCount;
        int newCurrItem = mCurItem;

        boolean isUpdating = false;
        for (int i = 0; i < mItems.size(); i++) {
            final ItemInfo ii = mItems.get(i);
            final int newPos = mAdapter.getItemPosition(ii.object);

            if (newPos == PagerAdapter.POSITION_UNCHANGED) {
                continue;
            }

            if (newPos == PagerAdapter.POSITION_NONE) {
                mItems.remove(i);
                i--;

                if (!isUpdating) {
                    mAdapter.startUpdate(this);
                    isUpdating = true;
                }

                mAdapter.destroyItem(this, ii.position, ii.object);
                needPopulate = true;

                if (mCurItem == ii.position) {
                    // Keep the current item in the valid range
                    newCurrItem = Math.max(0, Math.min(mCurItem, adapterCount - 1));
                    needPopulate = true;
                }
                continue;
            }

            if (ii.position != newPos) {
                if (ii.position == mCurItem) {
                    // Our current item changed position. Follow it.
                    newCurrItem = newPos;
                }

                ii.position = newPos;
                needPopulate = true;
            }
        }

        if (isUpdating) {
            mAdapter.finishUpdate(this);
        }

        Collections.sort(mItems, COMPARATOR);

        if (needPopulate) {
            // Reset our known page widths; populate will recompute them.
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (!lp.isDecor) {
                    lp.widthFactor = 0.f;
                }
            }

            setCurrentItemInternal(newCurrItem, false, true);
            requestLayout();
        }
    }

    // 填充
    void populate() {
        populate(mCurItem);
    }

    void populate(int newCurrentItem) {
        ItemInfo oldCurInfo = null;
        if (mCurItem != newCurrentItem) {
            oldCurInfo = infoForPosition(mCurItem);// 获取旧的ItemInfo
            mCurItem = newCurrentItem;// 更新新的当前位置
        }

        if (mAdapter == null) {
            // 对页卡的绘制顺序进行排序，优先绘制DecorView
            // 再按照position从小到大排序
            sortChildDrawingOrder();// 排序
            return;
        }

        // Bail now if we are waiting to populate.  This is to hold off
        // on creating views from the time the user releases their finger to
        // fling to a new position until we have finished the scroll to
        // that position, avoiding glitches from happening at that point.
        // 如果我们正在等待populate,那么在用户手指抬起切换到新的位置期间应该推迟创建页卡，
        // 直到滚动到最终位置再去创建，以免在这个期间出现差错
        if (mPopulatePending) {
            if (DEBUG) Log.i(TAG, "populate is pending, skipping for now...");
            // 对页卡的绘制顺序进行排序，优先绘制Decor View
            // 再按照position从小到大排序
            sortChildDrawingOrder();
            return;
        }

        // Also, don't populate until we are attached to a window.  This is to
        // avoid trying to populate before we have restored our view hierarchy
        // state and conflicting with what is restored.
        // 同样，在ViewPager没有attached到window之前，不要populate.
        // 这是因为如果我们在恢复View的层次结构之前进行populate，可能会与要恢复的内容有冲突
        if (getWindowToken() == null) {
            return;
        }

        // 回调PagerAdapter的startUpdate函数，
        // 告诉PagerAdapter开始更新要显示的页面
        mAdapter.startUpdate(this);

        // mOffscreenPageLimit就是setOffscreenPageLimit方法设置的值
        // 也就是单侧缓存数量（整个ViewPager含有的Item是（2 * mOffscreenPageLimit + 1）），
        // 其他要滑动过程中加载，超过缓存数量的需要销毁
        final int pageLimit = mOffscreenPageLimit;
        // mItems的长度就是 2 * pageLimit + 1
        final int startPos = Math.max(0, mCurItem - pageLimit);
        final int N = mAdapter.getCount();
        // 确保最后的位置小于等于数据源中数据个数-1，
        // 如果用户设置了缓存页面数量，第一个页面为当前页面加缓存页面数量
        final int endPos = Math.min(N - 1, mCurItem + pageLimit);

        // 判断用户是否增减了数据源的元素，如果增减了且没有调用notifyDataSetChanged，则抛出异常
        if (N != mExpectedAdapterCount) {
            String resName;
            try {
                resName = getResources().getResourceName(getId());
            } catch (Resources.NotFoundException e) {
                resName = Integer.toHexString(getId());
            }
            throw new IllegalStateException("The application's PagerAdapter changed the adapter's"
                    + " contents without calling PagerAdapter#notifyDataSetChanged!"
                    + " Expected adapter item count: " + mExpectedAdapterCount + ", found: " + N
                    + " Pager id: " + resName
                    + " Pager class: " + getClass()
                    + " Problematic adapter: " + mAdapter.getClass());
        }

        // Locate the currently focused item or add it if needed.
        // 遍历列表查找是否有获取焦点的页面ItemInfo
        int curIndex = -1;
        ItemInfo curItem = null;
        for (curIndex = 0; curIndex < mItems.size(); curIndex++) {
            final ItemInfo ii = mItems.get(curIndex);
            if (ii.position >= mCurItem) {
                if (ii.position == mCurItem) curItem = ii;
                break;
            }
        }

        // 如果没有获取焦点页面则创建一个ItemInfo加入
        if (curItem == null && N > 0) {
            curItem = addNewItem(mCurItem, curIndex);
        }

        // Fill 3x the available width or up to the number of offscreen
        // pages requested to either side, whichever is larger.
        // If we have no current item we have no work to do.
        if (curItem != null) {
            float extraWidthLeft = 0.f;
            int itemIndex = curIndex - 1;// 当前ItemInfo的左侧页面的索引
            // 如果当前页面左边有页面，则将左边页面对应的ItemInfo取出，否则左边页面的ItemInfo为null
            ItemInfo ii = itemIndex >= 0 ? mItems.get(itemIndex) : null;
            // 保存显示区域的宽度
            final int clientWidth = getClientWidth();
            // 算出左边页面需要的宽度，注意，这里的宽度是指实际宽度与可视区域宽度比例，
            // 即实际宽度 = leftWidthNeeded * clientWidth
            final float leftWidthNeeded = clientWidth <= 0 ? 0 :
                    2.f - curItem.widthFactor + (float) getPaddingLeft() / (float) clientWidth;
            // 从当前页面左边第一个页面开始往左进行遍历
            for (int pos = mCurItem - 1; pos >= 0; pos--) {
                // 如果左边的宽度超过了所需的宽度，并且当前当前页面位置比第一个缓存页面位置小
                // 这说明这个页面需要Destroy掉
                if (extraWidthLeft >= leftWidthNeeded && pos < startPos) {
                    if (ii == null) {// 如果左侧已经没有页面了则停止循环
                        break;
                    }
                    // 将当前页面destroy掉
                    if (pos == ii.position && !ii.scrolling) {
                        mItems.remove(itemIndex);
                        // 回调PagerAdapter的destroyItem
                        mAdapter.destroyItem(this, pos, ii.object);
                        if (DEBUG) {
                            Log.i(TAG, "populate() - destroyItem() with pos: " + pos
                                    + " view: " + ((View) ii.object));
                        }
                        // 由于mItems删除了一个元素
                        // 需要将索引减一
                        itemIndex--;
                        curIndex--;
                        ii = itemIndex >= 0 ? mItems.get(itemIndex) : null;
                    }
                } else if (ii != null && pos == ii.position) {
                    // 如果当前位置是需要缓存的位置，并且这个位置上的页面已经存在
                    // 则将左边宽度加上当前位置的页面
                    extraWidthLeft += ii.widthFactor;
                    itemIndex--;// 继续左移一位
                    // ii设置为当前遍历的页面的左边一个页面
                    ii = itemIndex >= 0 ? mItems.get(itemIndex) : null;
                } else {
                    // 如果当前位置是需要缓存，并且这个位置没有页面
                    // 需要添加一个ItemInfo,而addNewItem是通过PagerAdapter的instantiateItem获取对象
                    ii = addNewItem(pos, itemIndex + 1);
                    // 将左边宽度加上当前位置的页面
                    extraWidthLeft += ii.widthFactor;
                    // 由于新加了一个元素，当前的索引号需要加1
                    curIndex++;
                    // ii设置为当前遍历的页面的左边一个页面
                    ii = itemIndex >= 0 ? mItems.get(itemIndex) : null;
                }
            }

            // 调整右侧位置
            float extraWidthRight = curItem.widthFactor;
            itemIndex = curIndex + 1;
            if (extraWidthRight < 2.f) {
                ii = itemIndex < mItems.size() ? mItems.get(itemIndex) : null;
                final float rightWidthNeeded = clientWidth <= 0 ? 0 :
                        (float) getPaddingRight() / (float) clientWidth + 2.f;
                for (int pos = mCurItem + 1; pos < N; pos++) {
                    if (extraWidthRight >= rightWidthNeeded && pos > endPos) {
                        if (ii == null) {
                            break;
                        }
                        if (pos == ii.position && !ii.scrolling) {
                            mItems.remove(itemIndex);
                            mAdapter.destroyItem(this, pos, ii.object);
                            if (DEBUG) {
                                Log.i(TAG, "populate() - destroyItem() with pos: " + pos
                                        + " view: " + ((View) ii.object));
                            }
                            ii = itemIndex < mItems.size() ? mItems.get(itemIndex) : null;
                        }
                    } else if (ii != null && pos == ii.position) {
                        extraWidthRight += ii.widthFactor;
                        itemIndex++;
                        ii = itemIndex < mItems.size() ? mItems.get(itemIndex) : null;
                    } else {
                        ii = addNewItem(pos, itemIndex);
                        itemIndex++;
                        extraWidthRight += ii.widthFactor;
                        ii = itemIndex < mItems.size() ? mItems.get(itemIndex) : null;
                    }
                }
            }

            // 计算mItems中的偏移参数
            calculatePageOffsets(curItem, curIndex, oldCurInfo);
        }

        if (DEBUG) {
            Log.i(TAG, "Current page list:");
            for (int i = 0; i < mItems.size(); i++) {
                Log.i(TAG, "#" + i + ": page " + mItems.get(i).position);
            }
        }

        // 回调PagerAdapter的setPrimaryItem，告诉PagerAdapter当前显示的页面
        mAdapter.setPrimaryItem(this, mCurItem, curItem != null ? curItem.object : null);
        // 回调PagerAdapter的finishUpdate，告诉PagerAdapter页面更新结束
        mAdapter.finishUpdate(this);

        // Check width measurement of current pages and drawing sort order.
        // Update LayoutParams as needed.
        // 检查页面的宽度是否测量，如果页面的LayoutParams数据没有设定，则去重新设定好
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.childIndex = i;
            if (!lp.isDecor && lp.widthFactor == 0.f) {
                // 0 means requery the adapter for this, it doesn't have a valid width.
                final ItemInfo ii = infoForChild(child);
                if (ii != null) {
                    lp.widthFactor = ii.widthFactor;
                    lp.position = ii.position;
                }
            }
        }
        // 重新对页面排序
        sortChildDrawingOrder();

        // 如果ViewPager被设定为可获焦的，则将当前显示的页面设定为获焦
        if (hasFocus()) {
            View currentFocused = findFocus();
            ItemInfo ii = currentFocused != null ? infoForAnyChild(currentFocused) : null;
            if (ii == null || ii.position != mCurItem) {
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    ii = infoForChild(child);
                    if (ii != null && ii.position == mCurItem) {
                        if (child.requestFocus(View.FOCUS_FORWARD)) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private void sortChildDrawingOrder() {
        if (mDrawingOrder != DRAW_ORDER_DEFAULT) {
            if (mDrawingOrderedChildren == null) {
                mDrawingOrderedChildren = new ArrayList<View>();
            } else {
                mDrawingOrderedChildren.clear();
            }
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                mDrawingOrderedChildren.add(child);
            }
            Collections.sort(mDrawingOrderedChildren, sPositionComparator);
        }
    }

    private void calculatePageOffsets(ItemInfo curItem, int curIndex, ItemInfo oldCurInfo) {
        final int N = mAdapter.getCount();
        final int width = getClientWidth();
        final float marginOffset = width > 0 ? (float) mPageMargin / width : 0;
        // Fix up offsets for later layout.
        if (oldCurInfo != null) {
            final int oldCurPosition = oldCurInfo.position;
            // Base offsets off of oldCurInfo.
            if (oldCurPosition < curItem.position) {
                int itemIndex = 0;
                ItemInfo ii = null;
                float offset = oldCurInfo.offset + oldCurInfo.widthFactor + marginOffset;
                for (int pos = oldCurPosition + 1;
                     pos <= curItem.position && itemIndex < mItems.size(); pos++) {
                    ii = mItems.get(itemIndex);
                    while (pos > ii.position && itemIndex < mItems.size() - 1) {
                        itemIndex++;
                        ii = mItems.get(itemIndex);
                    }
                    while (pos < ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset += mAdapter.getPageWidth(pos) + marginOffset;
                        pos++;
                    }
                    ii.offset = offset;
                    offset += ii.widthFactor + marginOffset;
                }
            } else if (oldCurPosition > curItem.position) {
                int itemIndex = mItems.size() - 1;
                ItemInfo ii = null;
                float offset = oldCurInfo.offset;
                for (int pos = oldCurPosition - 1;
                     pos >= curItem.position && itemIndex >= 0; pos--) {
                    ii = mItems.get(itemIndex);
                    while (pos < ii.position && itemIndex > 0) {
                        itemIndex--;
                        ii = mItems.get(itemIndex);
                    }
                    while (pos > ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset -= mAdapter.getPageWidth(pos) + marginOffset;
                        pos--;
                    }
                    offset -= ii.widthFactor + marginOffset;
                    ii.offset = offset;
                }
            }
        }

        // Base all offsets off of curItem.
        final int itemCount = mItems.size();
        float offset = curItem.offset;
        int pos = curItem.position - 1;
        mFirstOffset = curItem.position == 0 ? curItem.offset : -Float.MAX_VALUE;
        mLastOffset = curItem.position == N - 1
                ? curItem.offset + curItem.widthFactor - 1 : Float.MAX_VALUE;
        // Previous pages
        for (int i = curIndex - 1; i >= 0; i--, pos--) {
            final ItemInfo ii = mItems.get(i);
            while (pos > ii.position) {
                offset -= mAdapter.getPageWidth(pos--) + marginOffset;
            }
            offset -= ii.widthFactor + marginOffset;
            ii.offset = offset;
            if (ii.position == 0) mFirstOffset = offset;
        }
        offset = curItem.offset + curItem.widthFactor + marginOffset;
        pos = curItem.position + 1;
        // Next pages
        for (int i = curIndex + 1; i < itemCount; i++, pos++) {
            final ItemInfo ii = mItems.get(i);
            while (pos < ii.position) {
                offset += mAdapter.getPageWidth(pos++) + marginOffset;
            }
            if (ii.position == N - 1) {
                mLastOffset = offset + ii.widthFactor - 1;
            }
            ii.offset = offset;
            offset += ii.widthFactor + marginOffset;
        }

        mNeedCalculatePageOffsets = false;
    }

    /**
     * This is the persistent state that is saved by ViewPager.  Only needed
     * if you are creating a sublass of ViewPager that must save its own
     * state, in which case it should implement a subclass of this which
     * contains that state.
     */
    public static class SavedState extends AbsSavedState {
        int position;
        Parcelable adapterState;
        ClassLoader loader;

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(position);
            out.writeParcelable(adapterState, flags);
        }

        @Override
        public String toString() {
            return "FragmentPager.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " position=" + position + "}";
        }

        public static final Parcelable.Creator<SavedState> CREATOR = ParcelableCompat.newCreator(
                new ParcelableCompatCreatorCallbacks<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                        return new SavedState(in, loader);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                });

        SavedState(Parcel in, ClassLoader loader) {
            super(in, loader);
            if (loader == null) {
                loader = getClass().getClassLoader();
            }
            position = in.readInt();
            adapterState = in.readParcelable(loader);
            this.loader = loader;
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.position = mCurItem;
        if (mAdapter != null) {
            ss.adapterState = mAdapter.saveState();
        }
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        if (mAdapter != null) {
            mAdapter.restoreState(ss.adapterState, ss.loader);
            setCurrentItemInternal(ss.position, false, true);
        } else {
            mRestoredCurItem = ss.position;
            mRestoredAdapterState = ss.adapterState;
            mRestoredClassLoader = ss.loader;
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (!checkLayoutParams(params)) {
            params = generateLayoutParams(params);
        }
        final LayoutParams lp = (LayoutParams) params;
        // Any views added via inflation should be classed as part of the decor
        lp.isDecor |= isDecorView(child);
        if (mInLayout) {
            if (lp != null && lp.isDecor) {
                throw new IllegalStateException("Cannot add pager decor view during layout");
            }
            lp.needsMeasure = true;
            addViewInLayout(child, index, params);
        } else {
            super.addView(child, index, params);
        }

        if (USE_CACHE) {
            if (child.getVisibility() != GONE) {
                child.setDrawingCacheEnabled(mScrollingCacheEnabled);
            } else {
                child.setDrawingCacheEnabled(false);
            }
        }
    }

    private static boolean isDecorView(@NonNull View view) {
        Class<?> clazz = view.getClass();
        return clazz.getAnnotation(DecorView.class) != null;
    }

    @Override
    public void removeView(View view) {
        if (mInLayout) {
            removeViewInLayout(view);
        } else {
            super.removeView(view);
        }
    }

    ItemInfo infoForChild(View child) {
        for (int i = 0; i < mItems.size(); i++) {
            ItemInfo ii = mItems.get(i);
            if (mAdapter.isViewFromObject(child, ii.object)) {
                return ii;
            }
        }
        return null;
    }

    ItemInfo infoForAnyChild(View child) {
        ViewParent parent;
        while ((parent = child.getParent()) != this) {
            if (parent == null || !(parent instanceof View)) {
                return null;
            }
            child = (View) parent;
        }
        return infoForChild(child);
    }

    ItemInfo infoForPosition(int position) {
        for (int i = 0; i < mItems.size(); i++) {
            ItemInfo ii = mItems.get(i);
            if (ii.position == position) {
                return ii;
            }
        }
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mFirstLayout = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // For simple implementation, our internal size is always 0.
        // We depend on the container to specify the layout size of
        // our view.  We can't really know what it is since we will be
        // adding and removing different arbitrary(任意的) views and do not
        // want the layout to change as this happens.
        // 设置尺寸信息，默认为0
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec),
                getDefaultSize(0, heightMeasureSpec));

        final int measuredWidth = getMeasuredWidth();
        final int maxGutterSize = measuredWidth / 10;
        mGutterSize = Math.min(maxGutterSize, mDefaultGutterSize);

        // Children are just made to fill our space.
        // Viewpager默认只显示一个childView
        int childWidthSize = measuredWidth - getPaddingLeft() - getPaddingRight();
        int childHeightSize = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();

        /*
         * Make sure all children have been properly(正确的) measured. Decor views first.
         * Right now we cheat and make this less complicated(复杂的) by assuming(假设) decor
         * views won't intersect(相交). We will pin to edges based on gravity.
         * 下面循环针对DecorView的，即用于装饰ViewPager的View
         * 获取ViewPager中pager的数量不是适配器中的view数量（实际是一样的）
         */
        int size = getChildCount();
        for (int i = 0; i < size; ++i) {
            // 获取Pager视图（不是适配器中view视图）
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp != null && lp.isDecor) {
                    // 获取水平方向和竖直方向的gravity
                    final int hgrav = lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
                    final int vgrav = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
                    // 默认宽高的模式是wrap_content
                    int widthMode = MeasureSpec.AT_MOST;
                    int heightMode = MeasureSpec.AT_MOST;
                    // 判断是在竖直方向还是水平方向上占用空间
                    boolean consumeVertical = vgrav == Gravity.TOP || vgrav == Gravity.BOTTOM;
                    boolean consumeHorizontal = hgrav == Gravity.LEFT || hgrav == Gravity.RIGHT;

                    // 如果竖直方向占用，那么宽度模式就是match_parent，竖直方向有DecorView决定
                    // 水平方向同理
                    if (consumeVertical) {
                        widthMode = MeasureSpec.EXACTLY;
                    } else if (consumeHorizontal) {
                        heightMode = MeasureSpec.EXACTLY;
                    }

                    // 默认宽高与pager的宽高一样，也就是只显示一个子view
                    int widthSize = childWidthSize;
                    int heightSize = childHeightSize;
                    // 如果pager的宽度不是wrap_content，那么宽度模式就是EXACTLY(match_parent)，
                    // 如果也不是match_parent，宽度就是pager的宽度
                    if (lp.width != LayoutParams.WRAP_CONTENT) {
                        widthMode = MeasureSpec.EXACTLY;
                        if (lp.width != LayoutParams.MATCH_PARENT) {
                            widthSize = lp.width;
                        }
                    }
                    // 高度同上
                    if (lp.height != LayoutParams.WRAP_CONTENT) {
                        heightMode = MeasureSpec.EXACTLY;
                        if (lp.height != LayoutParams.MATCH_PARENT) {
                            heightSize = lp.height;
                        }
                    }
                    // 有了宽度和高度的size和模型，就可以获取viewpager对应的规格（包含值和模型）
                    final int widthSpec = MeasureSpec.makeMeasureSpec(widthSize, widthMode);
                    final int heightSpec = MeasureSpec.makeMeasureSpec(heightSize, heightMode);
                    // 测量pager
                    child.measure(widthSpec, heightSpec);

                    // 如果DecorView占用了ViewPager的垂直方向的空间需要将页卡的竖直方向可用的空间
                    // 减去DecorView的高度，同理，水平方向上也做同样的处理
                    if (consumeVertical) {
                        childHeightSize -= child.getMeasuredHeight();
                    } else if (consumeHorizontal) {
                        childWidthSize -= child.getMeasuredWidth();
                    }
                }
            }
        }

        // 获取页卡对应的宽高规格MeasureSpec（包括值和模型）
        mChildWidthMeasureSpec = MeasureSpec.makeMeasureSpec(childWidthSize, MeasureSpec.EXACTLY);
        mChildHeightMeasureSpec = MeasureSpec.makeMeasureSpec(childHeightSize, MeasureSpec.EXACTLY);

        // Make sure we have created all fragments that we need to have shown.
        mInLayout = true;
        populate();
        mInLayout = false;

        // Page views next.再对页卡进行测量
        size = getChildCount();
        for (int i = 0; i < size; ++i) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                if (DEBUG) {
                    Log.v(TAG, "Measuring #" + i + " " + child + ": " + mChildWidthMeasureSpec);
                }

                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                // 只针对页卡而不是DecorView进行测量
                if (lp == null || !lp.isDecor) {
                    // LayoutParams的widthFactor是取值为[0,1]的浮点数，
                    // 用于表示页卡占ViewPager显示区域中页卡可用宽度的比例，
                    // 即(childWidthSize * lp.widthFactor)表示当前页卡的实际宽度
                    final int widthSpec = MeasureSpec.makeMeasureSpec(
                            (int) (childWidthSize * lp.widthFactor), MeasureSpec.EXACTLY);
                    child.measure(widthSpec, mChildHeightMeasureSpec);
                }
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Make sure scroll position is set correctly.
        if (w != oldw) {
            recomputeScrollPosition(w, oldw, mPageMargin, mPageMargin);
        }
    }

    private void recomputeScrollPosition(int width, int oldWidth, int margin, int oldMargin) {
        if (oldWidth > 0 && !mItems.isEmpty()) {
            if (!mScroller.isFinished()) {
                mScroller.setFinalX(getCurrentItem() * getClientWidth());
            } else {
                final int widthWithMargin = width - getPaddingLeft() - getPaddingRight() + margin;
                final int oldWidthWithMargin = oldWidth - getPaddingLeft() - getPaddingRight()
                        + oldMargin;
                final int xpos = getScrollX();
                final float pageOffset = (float) xpos / oldWidthWithMargin;
                final int newOffsetPixels = (int) (pageOffset * widthWithMargin);

                scrollTo(newOffsetPixels, getScrollY());
            }
        } else {
            final ItemInfo ii = infoForPosition(mCurItem);
            final float scrollOffset = ii != null ? Math.min(ii.offset, mLastOffset) : 0;
            final int scrollPos =
                    (int) (scrollOffset * (width - getPaddingLeft() - getPaddingRight()));
            if (scrollPos != getScrollX()) {
                completeScroll(false);
                scrollTo(scrollPos, getScrollY());
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int count = getChildCount();
        int width = r - l;
        int height = b - t;
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();
        final int scrollX = getScrollX();

        int decorCount = 0;

        // First pass - decor views. We need to do this in two passes so that
        // we have the proper offsets for non-decor views later.
        // 首先对DecorView进行layout，再对普通页卡进行layout，之所以先对DecorView布局，
        // 是为了让普通页卡（页卡）能有合适的偏移
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int childLeft = 0;
                int childTop = 0;
                if (lp.isDecor) {// 只神队DecorView
                    final int hgrav = lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
                    final int vgrav = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
                    // 根据水平方向上的Gravity来确定childLeft和paddingLeft
                    switch (hgrav) {
                        default:// 水平方向没有设置Gravity的时候默认为Parent的paddingRight
                            childLeft = paddingLeft;
                            break;
                        case Gravity.LEFT:// 在最左边
                            childLeft = paddingLeft;
                            paddingLeft += child.getMeasuredWidth();
                            break;
                        case Gravity.CENTER_HORIZONTAL:// 水平居中
                            childLeft = Math.max((width - child.getMeasuredWidth()) / 2,
                                    paddingLeft);
                            break;
                        case Gravity.RIGHT:// 最右侧
                            childLeft = width - paddingRight - child.getMeasuredWidth();
                            paddingRight += child.getMeasuredWidth();
                            break;
                    }
                    // 根据竖直方向上的Gravity来确定childTop和paddingTop
                    switch (vgrav) {
                        default:// 未设置
                            childTop = paddingTop;
                            break;
                        case Gravity.TOP:// 顶部
                            childTop = paddingTop;
                            paddingTop += child.getMeasuredHeight();
                            break;
                        case Gravity.CENTER_VERTICAL:// 垂直居中
                            childTop = Math.max((height - child.getMeasuredHeight()) / 2,
                                    paddingTop);
                            break;
                        case Gravity.BOTTOM:// 最底侧
                            childTop = height - paddingBottom - child.getMeasuredHeight();
                            paddingBottom += child.getMeasuredHeight();
                            break;
                    }
                    // 上面计算的childLeft是相对ViewPager的左边计算的，
                    // 还需要加上x方向已经滑动的距离scrollX
                    childLeft += scrollX;
                    // 对DecorView
                    child.layout(childLeft, childTop,
                            childLeft + child.getMeasuredWidth(),
                            childTop + child.getMeasuredHeight());
                    //累加DecorView的数量
                    decorCount++;
                }
            }
        }

        // 普通page的宽度
        final int childWidth = width - paddingLeft - paddingRight;
        // Page views. Do this once we have the right padding offsets from above.
        // 下面针对普通页卡布局，在此之前我们已经得到正确的偏移量了
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                ItemInfo ii;
                // 此处将DecorView过滤掉，并且根据view从mItems中查找ItemInfo对象
                // 如果ViewPager布局中添加了未实现Decor接口的控件，将不会被布局
                // 因为无法从mItems中查找到ItemInfo对象
                if (!lp.isDecor && (ii = infoForChild(child)) != null) {
                    // 计算当前page的左边界偏移值，此处的offset会随着页面增加而增加
                    int loff = (int) (childWidth * ii.offset);
                    int childLeft = paddingLeft + loff;
                    int childTop = paddingTop;
                    // 当找个页面卡是在layout期间添加的需要测量
                    if (lp.needsMeasure) {//如果需要重新测量，则重新测量之
                        // This was added during layout and needs measurement.
                        // Do it now that we know what we're working with.
                        lp.needsMeasure = false;
                        final int widthSpec = MeasureSpec.makeMeasureSpec(
                                (int) (childWidth * lp.widthFactor),
                                MeasureSpec.EXACTLY);
                        final int heightSpec = MeasureSpec.makeMeasureSpec(
                                (int) (height - paddingTop - paddingBottom),
                                MeasureSpec.EXACTLY);
                        child.measure(widthSpec, heightSpec);
                    }
                    if (DEBUG) {
                        Log.v(TAG, "Positioning #" + i + " " + child + " f=" + ii.object
                                + ":" + childLeft + "," + childTop + " " + child.getMeasuredWidth()
                                + "x" + child.getMeasuredHeight());
                    }
                    // 对普通页面卡进行布局
                    child.layout(childLeft, childTop,
                            childLeft + child.getMeasuredWidth(),
                            childTop + child.getMeasuredHeight());
                }
            }
        }
        // 保存顶部底部padding和数量
        mTopPageBounds = paddingTop;
        mBottomPageBounds = height - paddingBottom;
        mDecorChildCount = decorCount;

        //如果是首次布局，则会调用scrollToItem方法滑动到第一个页面位置
        if (mFirstLayout) {
            scrollToItem(mCurItem, false, 0, false);
        }
        // 标记第一次布局完成
        mFirstLayout = false;
    }

    @Override
    public void computeScroll() {
        mIsScrollStarted = true;
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            int oldX = getScrollX();
            int oldY = getScrollY();
            int x = mScroller.getCurrX();
            int y = mScroller.getCurrY();

            if (oldX != x || oldY != y) {
                scrollTo(x, y);
                if (!pageScrolled(x)) {
                    mScroller.abortAnimation();
                    scrollTo(0, y);
                }
            }

            // Keep on drawing until the animation has finished.
            ViewCompat.postInvalidateOnAnimation(this);
            return;
        }

        // Done with scroll, clean up state.
        completeScroll(true);
    }

    private boolean pageScrolled(int xpos) {
        if (mItems.size() == 0) {
            if (mFirstLayout) {
                // If we haven't been laid out yet, we probably just haven't been populated yet.
                // Let's skip this call since it doesn't make sense in this state
                return false;
            }
            mCalledSuper = false;
            onPageScrolled(0, 0, 0);
            if (!mCalledSuper) {
                throw new IllegalStateException(
                        "onPageScrolled did not call superclass implementation");
            }
            return false;
        }
        final ItemInfo ii = infoForCurrentScrollPosition();
        final int width = getClientWidth();
        final int widthWithMargin = width + mPageMargin;
        final float marginOffset = (float) mPageMargin / width;
        final int currentPage = ii.position;
        final float pageOffset = (((float) xpos / width) - ii.offset)
                / (ii.widthFactor + marginOffset);
        final int offsetPixels = (int) (pageOffset * widthWithMargin);

        mCalledSuper = false;
        onPageScrolled(currentPage, pageOffset, offsetPixels);
        if (!mCalledSuper) {
            throw new IllegalStateException(
                    "onPageScrolled did not call superclass implementation");
        }
        return true;
    }

    /**
     * This method will be invoked when the current page is scrolled, either as part
     * of a programmatically initiated smooth scroll or a user initiated touch scroll.
     * If you override this method you must call through to the superclass implementation
     * (e.g. super.onPageScrolled(position, offset, offsetPixels)) before onPageScrolled
     * returns.
     *
     * @param position     Position index of the first page currently being displayed.
     *                     Page position+1 will be visible if positionOffset is nonzero.
     *                     表示当前是第几个页面
     * @param offset       Value from [0, 1) indicating the offset from the page at position.
     *                     表示当前页面移动的距离，其实就是个相对实际宽度比例值，取值为[0,1)。
     *                     0表示整个页面在显示区域，1表示整个页面已经完全左移出显示区域。
     * @param offsetPixels Value in pixels indicating the offset from position.
     *                     表示当前页面左移的像素个数。
     */
    @CallSuper
    protected void onPageScrolled(int position, float offset, int offsetPixels) {
        // Offset any decor views if needed - keep them on-screen at all times.
        // 如果有DecorView，则需要使得它们时刻显示在屏幕中，不移出屏幕
        if (mDecorChildCount > 0) {
            // 根据Gravity将DecorView摆放到指定位置。
            // 这部分代码与onMeasure()方法中的原理一样，这里就不做解释了
            final int scrollX = getScrollX();
            int paddingLeft = getPaddingLeft();
            int paddingRight = getPaddingRight();
            final int width = getWidth();
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (!lp.isDecor) continue;

                final int hgrav = lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
                int childLeft = 0;
                switch (hgrav) {
                    default:
                        childLeft = paddingLeft;
                        break;
                    case Gravity.LEFT:
                        childLeft = paddingLeft;
                        paddingLeft += child.getWidth();
                        break;
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft = Math.max((width - child.getMeasuredWidth()) / 2,
                                paddingLeft);
                        break;
                    case Gravity.RIGHT:
                        childLeft = width - paddingRight - child.getMeasuredWidth();
                        paddingRight += child.getMeasuredWidth();
                        break;
                }
                childLeft += scrollX;

                final int childOffset = childLeft - child.getLeft();
                if (childOffset != 0) {
                    child.offsetLeftAndRight(childOffset);
                }
            }
        }

        // 分发页面滚动事件,类似于事件的分发
        dispatchOnPageScrolled(position, offset, offsetPixels);

        // 如果mPageTransformer不为null，则不断去调用mPageTransformer的transformPage函数
        if (mPageTransformer != null) {
            final int scrollX = getScrollX();
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                // 只针对页面进行处理
                if (lp.isDecor) continue;
                // 计算child位置
                final float transformPos = (float) (child.getLeft() - scrollX) / getClientWidth();
                // 调用transformPage
                mPageTransformer.transformPage(child, transformPos);
            }
        }

        // 标记ViewPager的onPageScrolled函数执行过
        mCalledSuper = true;
    }

    private void dispatchOnPageScrolled(int position, float offset, int offsetPixels) {
        if (mOnPageChangeListener != null) {
            mOnPageChangeListener.onPageScrolled(position, offset, offsetPixels);
        }
        if (mOnPageChangeListeners != null) {
            for (int i = 0, z = mOnPageChangeListeners.size(); i < z; i++) {
                OnPageChangeListener listener = mOnPageChangeListeners.get(i);
                if (listener != null) {
                    listener.onPageScrolled(position, offset, offsetPixels);
                }
            }
        }
        if (mInternalPageChangeListener != null) {
            mInternalPageChangeListener.onPageScrolled(position, offset, offsetPixels);
        }
    }

    private void dispatchOnPageSelected(int position) {
        if (mOnPageChangeListener != null) {
            mOnPageChangeListener.onPageSelected(position);
        }
        if (mOnPageChangeListeners != null) {
            for (int i = 0, z = mOnPageChangeListeners.size(); i < z; i++) {
                OnPageChangeListener listener = mOnPageChangeListeners.get(i);
                if (listener != null) {
                    listener.onPageSelected(position);
                }
            }
        }
        if (mInternalPageChangeListener != null) {
            mInternalPageChangeListener.onPageSelected(position);
        }
    }

    private void dispatchOnScrollStateChanged(int state) {
        if (mOnPageChangeListener != null) {
            mOnPageChangeListener.onPageScrollStateChanged(state);
        }
        if (mOnPageChangeListeners != null) {
            for (int i = 0, z = mOnPageChangeListeners.size(); i < z; i++) {
                OnPageChangeListener listener = mOnPageChangeListeners.get(i);
                if (listener != null) {
                    listener.onPageScrollStateChanged(state);
                }
            }
        }
        if (mInternalPageChangeListener != null) {
            mInternalPageChangeListener.onPageScrollStateChanged(state);
        }
    }

    private void completeScroll(boolean postEvents) {
        boolean needPopulate = mScrollState == SCROLL_STATE_SETTLING;
        if (needPopulate) {
            // Done with scroll, no longer want to cache view drawing.
            setScrollingCacheEnabled(false);
            boolean wasScrolling = !mScroller.isFinished();
            if (wasScrolling) {
                mScroller.abortAnimation();
                int oldX = getScrollX();
                int oldY = getScrollY();
                int x = mScroller.getCurrX();
                int y = mScroller.getCurrY();
                if (oldX != x || oldY != y) {
                    scrollTo(x, y);
                    if (x != oldX) {
                        pageScrolled(x);
                    }
                }
            }
        }
        mPopulatePending = false;
        for (int i = 0; i < mItems.size(); i++) {
            ItemInfo ii = mItems.get(i);
            if (ii.scrolling) {
                needPopulate = true;
                ii.scrolling = false;
            }
        }
        if (needPopulate) {
            if (postEvents) {
                ViewCompat.postOnAnimation(this, mEndScrollRunnable);
            } else {
                mEndScrollRunnable.run();
            }
        }
    }

    private boolean isGutterDrag(float x, float dx) {
        return (x < mGutterSize && dx > 0) || (x > getWidth() - mGutterSize && dx < 0);
    }

    private void enableLayers(boolean enable) {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final int layerType = enable
                    ? mPageTransformerLayerType : ViewCompat.LAYER_TYPE_NONE;
            ViewCompat.setLayerType(getChildAt(i), layerType, null);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onMotionEvent will be called and we do the actual
         * scrolling there.
         */

        // 触摸动作
        final int action = ev.getAction() & MotionEventCompat.ACTION_MASK;

        // Always take care of the touch gesture being complete.
        // 时刻要注意触摸是否已经结束
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            // Release the drag.
            if (DEBUG) Log.v(TAG, "Intercept done!");
            // 重置一些跟判断是否拦截触摸相关变量
            resetTouch();
            // 触摸结束，无需拦截
            return false;
        }

        // Nothing more to do here if we have decided whether or not we
        // are dragging.
        // 如果当前不是按下事件，我们就判断一下，是否是在拖拽切换页面
        if (action != MotionEvent.ACTION_DOWN) {
            // 如果当前是正在拽切换页面，直接拦截掉事件，后面无需再做拦截判断
            if (mIsBeingDragged) {
                if (DEBUG) Log.v(TAG, "Intercept returning true!");
                return true;
            }
            // 如果标记为不允许拖拽切换页面，我们就不处理一切触摸事件
            if (mIsUnableToDrag) {
                if (DEBUG) Log.v(TAG, "Intercept returning false!");
                return false;
            }
        }

        // 根据不同的动作进行处理
        switch (action) {
            case MotionEvent.ACTION_MOVE: {// 如果是手指移动操作
                /*
                 * mIsBeingDragged == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from his original down touch.
                 */

                /*
                * Locally do absolute value. mLastMotionY is set to the y value
                * of the down event.
                */
                // 代码能执行到这里，就说明mIsBeingDragged==false，否则的话就已经执行结束了
                // 使用触摸点Id，主要是为了处理多点触摸
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    // If we don't have a valid id, the touch down wasn't on content.
                    // 如果当前的触摸点id不是一个有效的Id，无需再做处理
                    break;
                }

                // 根据触摸点的id来区分不同的手指，我们只需关注一个手指就好
                final int pointerIndex = ev.findPointerIndex(activePointerId);
                // 根据这个手指的序号，来获取这个手指对应的x坐标
                final float x = ev.getX(pointerIndex);
                // 在x轴方向上移动的距离
                final float dx = x - mLastMotionX;
                // x轴方向的移动距离绝对值
                final float xDiff = Math.abs(dx);
                final float y = ev.getY(pointerIndex);
                final float yDiff = Math.abs(y - mInitialMotionY);
                if (DEBUG) Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);

                // 判断当前显示的页面是否可以滑动，如果可以滑动，则将该事件丢给当前显示的页面处理
                // isGutterDrag是判断是否在两个页面之间的缝隙内移动
                // canScroll是判断页面是否可以滑动
                if (dx != 0 && !isGutterDrag(mLastMotionX, dx)
                        && canScroll(this, false, (int) dx, (int) x, (int) y)) {
                    // Nested view has scrollable area under this point. Let it be handled there.
                    mLastMotionX = x;
                    mLastMotionY = y;
                    // 标记ViewPager不去拦截事件
                    mIsUnableToDrag = true;
                    return false;
                }
                // 如果x移动距离大于最小距离，并且斜率小于0.5，表示在水平方向上的拖动
                if (xDiff > mTouchSlop && xDiff * 0.5f > yDiff) {
                    if (DEBUG) Log.v(TAG, "Starting drag!");
                    // 水平方向的移动，需要ViewPager去拦截
                    mIsBeingDragged = true;
                    // 如果ViewPager还有父View，则还要向父View申请将触摸事件传递给ViewPager
                    requestParentDisallowInterceptTouchEvent(true);
                    // 设置滚动状态
                    setScrollState(SCROLL_STATE_DRAGGING);
                    // 保存当前位置
                    mLastMotionX = dx > 0
                            ? mInitialMotionX + mTouchSlop : mInitialMotionX - mTouchSlop;
                    mLastMotionY = y;
                    // 启用缓存
                    setScrollingCacheEnabled(true);
                } else if (yDiff > mTouchSlop) {// 否则的话，表示是竖直方向上的移动
                    // The finger has moved enough in the vertical
                    // direction to be counted as a drag...  abort
                    // any attempt to drag horizontally, to work correctly
                    // with children that have scrolling containers.
                    if (DEBUG) Log.v(TAG, "Starting unable to drag!");
                    // 竖直方向上的移动则不去拦截触摸事件
                    mIsUnableToDrag = true;
                }
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    // 跟随手指一起滑动
                    if (performDrag(x)) {
                        ViewCompat.postInvalidateOnAnimation(this);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {// 如果手指是按下操作
                /*
                 * Remember location of down touch.
                 * ACTION_DOWN always refers to pointer index 0.
                 */
                // 记录按下的点位置
                mLastMotionX = mInitialMotionX = ev.getX();
                mLastMotionY = mInitialMotionY = ev.getY();
                // 第一个ACTION_DOWN事件对应的手指序号为0
                mActivePointerId = ev.getPointerId(0);
                // 重置允许拖拽切换页面
                mIsUnableToDrag = false;
                // 标记开始滚动
                mIsScrollStarted = true;
                // 手动调用计算滑动的偏移量
                mScroller.computeScrollOffset();
                // 如果当前滚动状态为正在将页面放置到最终位置，
                // 且当前位置距离最终位置足够远
                if (mScrollState == SCROLL_STATE_SETTLING
                        && Math.abs(mScroller.getFinalX() - mScroller.getCurrX()) > mCloseEnough) {
                    // Let the user 'catch' the pager as it animates.
                    // 如果此时用户手指按下，则立马暂停滑动
                    mScroller.abortAnimation();
                    mPopulatePending = false;
                    populate();
                    mIsBeingDragged = true;
                    // 如果ViewPager还有父View，则还要向父View申请将触摸事件传递给ViewPager
                    requestParentDisallowInterceptTouchEvent(true);
                    // 设置当前状态为正在拖拽
                    setScrollState(SCROLL_STATE_DRAGGING);
                } else {
                    // 结束滚动
                    completeScroll(false);
                    mIsBeingDragged = false;
                }

                if (DEBUG) {
                    Log.v(TAG, "Down at " + mLastMotionX + "," + mLastMotionY
                            + " mIsBeingDragged=" + mIsBeingDragged
                            + "mIsUnableToDrag=" + mIsUnableToDrag);
                }
                break;
            }

            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        // 添加速度追踪
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         * 只有在当前是拖拽切换页面时我们才会去拦截事件
         */
        return mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mFakeDragging) {
            // A fake drag is in progress already, ignore this real one
            // but still eat the touch events.
            // (It is likely that the user is multi-touching the screen.)
            return true;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getEdgeFlags() != 0) {
            // Don't handle edge touches immediately -- they may actually belong to one of our
            // descendants.
            return false;
        }

        if (mAdapter == null || mAdapter.getCount() == 0) {
            // Nothing to present or scroll; nothing to touch.
            return false;
        }

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        final int action = ev.getAction();
        boolean needsInvalidate = false;

        switch (action & MotionEventCompat.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                mScroller.abortAnimation();
                mPopulatePending = false;
                populate();

                // Remember where the motion event started
                mLastMotionX = mInitialMotionX = ev.getX();
                mLastMotionY = mInitialMotionY = ev.getY();
                mActivePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (!mIsBeingDragged) {
                    final int pointerIndex = ev.findPointerIndex(mActivePointerId);
                    if (pointerIndex == -1) {
                        // A child has consumed some touch events and put us into an inconsistent
                        // state.
                        needsInvalidate = resetTouch();
                        break;
                    }
                    final float x = ev.getX(pointerIndex);
                    final float xDiff = Math.abs(x - mLastMotionX);
                    final float y = ev.getY(pointerIndex);
                    final float yDiff = Math.abs(y - mLastMotionY);
                    if (DEBUG) {
                        Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);
                    }
                    if (xDiff > mTouchSlop && xDiff > yDiff) {
                        if (DEBUG) Log.v(TAG, "Starting drag!");
                        mIsBeingDragged = true;
                        requestParentDisallowInterceptTouchEvent(true);
                        mLastMotionX = x - mInitialMotionX > 0 ? mInitialMotionX + mTouchSlop :
                                mInitialMotionX - mTouchSlop;
                        mLastMotionY = y;
                        setScrollState(SCROLL_STATE_DRAGGING);
                        setScrollingCacheEnabled(true);

                        // Disallow Parent Intercept, just in case
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                // Not else! Note that mIsBeingDragged can be set above.
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                    final float x = ev.getX(activePointerIndex);
                    needsInvalidate |= performDrag(x);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mIsBeingDragged) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    int initialVelocity = (int) VelocityTrackerCompat.getXVelocity(
                            velocityTracker, mActivePointerId);
                    mPopulatePending = true;
                    final int width = getClientWidth();
                    final int scrollX = getScrollX();
                    final ItemInfo ii = infoForCurrentScrollPosition();
                    final float marginOffset = (float) mPageMargin / width;
                    final int currentPage = ii.position;
                    final float pageOffset = (((float) scrollX / width) - ii.offset)
                            / (ii.widthFactor + marginOffset);
                    final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                    final float x = ev.getX(activePointerIndex);
                    final int totalDelta = (int) (x - mInitialMotionX);
                    int nextPage = determineTargetPage(currentPage, pageOffset, initialVelocity,
                            totalDelta);
                    setCurrentItemInternal(nextPage, true, true, initialVelocity);

                    needsInvalidate = resetTouch();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mIsBeingDragged) {
                    scrollToItem(mCurItem, true, 0, false);
                    needsInvalidate = resetTouch();
                }
                break;
            case MotionEventCompat.ACTION_POINTER_DOWN: {
                final int index = MotionEventCompat.getActionIndex(ev);
                final float x = ev.getX(index);
                mLastMotionX = x;
                mActivePointerId = ev.getPointerId(index);
                break;
            }
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                mLastMotionX = ev.getX(ev.findPointerIndex(mActivePointerId));
                break;
        }
        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
        return true;
    }

    private boolean resetTouch() {
        boolean needsInvalidate;
        mActivePointerId = INVALID_POINTER;
        endDrag();
        needsInvalidate = mLeftEdge.onRelease() | mRightEdge.onRelease();
        return needsInvalidate;
    }

    private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private boolean performDrag(float x) {
        boolean needsInvalidate = false;

        final float deltaX = mLastMotionX - x;
        mLastMotionX = x;

        float oldScrollX = getScrollX();
        float scrollX = oldScrollX + deltaX;
        final int width = getClientWidth();

        float leftBound = width * mFirstOffset;
        float rightBound = width * mLastOffset;
        boolean leftAbsolute = true;
        boolean rightAbsolute = true;

        final ItemInfo firstItem = mItems.get(0);
        final ItemInfo lastItem = mItems.get(mItems.size() - 1);
        if (firstItem.position != 0) {
            leftAbsolute = false;
            leftBound = firstItem.offset * width;
        }
        if (lastItem.position != mAdapter.getCount() - 1) {
            rightAbsolute = false;
            rightBound = lastItem.offset * width;
        }

        if (scrollX < leftBound) {
            if (leftAbsolute) {
                float over = leftBound - scrollX;
                needsInvalidate = mLeftEdge.onPull(Math.abs(over) / width);
            }
            scrollX = leftBound;
        } else if (scrollX > rightBound) {
            if (rightAbsolute) {
                float over = scrollX - rightBound;
                needsInvalidate = mRightEdge.onPull(Math.abs(over) / width);
            }
            scrollX = rightBound;
        }
        // Don't lose the rounded component
        mLastMotionX += scrollX - (int) scrollX;
        scrollTo((int) scrollX, getScrollY());
        pageScrolled((int) scrollX);

        return needsInvalidate;
    }

    /**
     * @return Info about the page at the current scroll position.
     * This can be synthetic for a missing middle page; the 'object' field can be null.
     */
    private ItemInfo infoForCurrentScrollPosition() {
        final int width = getClientWidth();
        final float scrollOffset = width > 0 ? (float) getScrollX() / width : 0;
        final float marginOffset = width > 0 ? (float) mPageMargin / width : 0;
        int lastPos = -1;
        float lastOffset = 0.f;
        float lastWidth = 0.f;
        boolean first = true;

        ItemInfo lastItem = null;
        for (int i = 0; i < mItems.size(); i++) {
            ItemInfo ii = mItems.get(i);
            float offset;
            if (!first && ii.position != lastPos + 1) {
                // Create a synthetic item for a missing page.
                ii = mTempItem;
                ii.offset = lastOffset + lastWidth + marginOffset;
                ii.position = lastPos + 1;
                ii.widthFactor = mAdapter.getPageWidth(ii.position);
                i--;
            }
            offset = ii.offset;

            final float leftBound = offset;
            final float rightBound = offset + ii.widthFactor + marginOffset;
            if (first || scrollOffset >= leftBound) {
                if (scrollOffset < rightBound || i == mItems.size() - 1) {
                    return ii;
                }
            } else {
                return lastItem;
            }
            first = false;
            lastPos = ii.position;
            lastOffset = offset;
            lastWidth = ii.widthFactor;
            lastItem = ii;
        }

        return lastItem;
    }

    private int determineTargetPage(int currentPage, float pageOffset, int velocity, int deltaX) {
        int targetPage;
        if (Math.abs(deltaX) > mFlingDistance && Math.abs(velocity) > mMinimumVelocity) {
            targetPage = velocity > 0 ? currentPage : currentPage + 1;
        } else {
            final float truncator = currentPage >= mCurItem ? 0.4f : 0.6f;
            targetPage = currentPage + (int) (pageOffset + truncator);
        }

        if (mItems.size() > 0) {
            final ItemInfo firstItem = mItems.get(0);
            final ItemInfo lastItem = mItems.get(mItems.size() - 1);

            // Only let the user target pages we have items for
            targetPage = Math.max(firstItem.position, Math.min(targetPage, lastItem.position));
        }

        return targetPage;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        boolean needsInvalidate = false;

        final int overScrollMode = getOverScrollMode();
        if (overScrollMode == View.OVER_SCROLL_ALWAYS
                || (overScrollMode == View.OVER_SCROLL_IF_CONTENT_SCROLLS
                && mAdapter != null && mAdapter.getCount() > 1)) {
            if (!mLeftEdge.isFinished()) {
                final int restoreCount = canvas.save();
                final int height = getHeight() - getPaddingTop() - getPaddingBottom();
                final int width = getWidth();

                canvas.rotate(270);
                canvas.translate(-height + getPaddingTop(), mFirstOffset * width);
                mLeftEdge.setSize(height, width);
                needsInvalidate |= mLeftEdge.draw(canvas);
                canvas.restoreToCount(restoreCount);
            }
            if (!mRightEdge.isFinished()) {
                final int restoreCount = canvas.save();
                final int width = getWidth();
                final int height = getHeight() - getPaddingTop() - getPaddingBottom();

                canvas.rotate(90);
                canvas.translate(-getPaddingTop(), -(mLastOffset + 1) * width);
                mRightEdge.setSize(height, width);
                needsInvalidate |= mRightEdge.draw(canvas);
                canvas.restoreToCount(restoreCount);
            }
        } else {
            mLeftEdge.finish();
            mRightEdge.finish();
        }

        if (needsInvalidate) {
            // Keep animating
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw the margin drawable between pages if needed.
        if (mPageMargin > 0 && mMarginDrawable != null && mItems.size() > 0 && mAdapter != null) {
            final int scrollX = getScrollX();
            final int width = getWidth();

            final float marginOffset = (float) mPageMargin / width;
            int itemIndex = 0;
            ItemInfo ii = mItems.get(0);
            float offset = ii.offset;
            final int itemCount = mItems.size();
            final int firstPos = ii.position;
            final int lastPos = mItems.get(itemCount - 1).position;
            for (int pos = firstPos; pos < lastPos; pos++) {
                while (pos > ii.position && itemIndex < itemCount) {
                    ii = mItems.get(++itemIndex);
                }

                float drawAt;
                if (pos == ii.position) {
                    drawAt = (ii.offset + ii.widthFactor) * width;
                    offset = ii.offset + ii.widthFactor + marginOffset;
                } else {
                    float widthFactor = mAdapter.getPageWidth(pos);
                    drawAt = (offset + widthFactor) * width;
                    offset += widthFactor + marginOffset;
                }

                if (drawAt + mPageMargin > scrollX) {
                    mMarginDrawable.setBounds(Math.round(drawAt), mTopPageBounds,
                            Math.round(drawAt + mPageMargin), mBottomPageBounds);
                    mMarginDrawable.draw(canvas);
                }

                if (drawAt > scrollX + width) {
                    break; // No more visible, no sense in continuing
                }
            }
        }
    }

    /**
     * Start a fake drag of the pager.
     * <p>
     * <p>A fake drag can be useful if you want to synchronize the motion of the ViewPager
     * with the touch scrolling of another view, while still letting the ViewPager
     * control the snapping motion and fling behavior. (e.g. parallax-scrolling tabs.)
     * Call {@link #fakeDragBy(float)} to simulate the actual drag motion. Call
     * {@link #endFakeDrag()} to complete the fake drag and fling as necessary.
     * <p>
     * <p>During a fake drag the ViewPager will ignore all touch events. If a real drag
     * is already in progress, this method will return false.
     *
     * @return true if the fake drag began successfully, false if it could not be started.
     *
     * @see #fakeDragBy(float)
     * @see #endFakeDrag()
     */
    public boolean beginFakeDrag() {
        if (mIsBeingDragged) {
            return false;
        }
        mFakeDragging = true;
        setScrollState(SCROLL_STATE_DRAGGING);
        mInitialMotionX = mLastMotionX = 0;
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
        final long time = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mVelocityTracker.addMovement(ev);
        ev.recycle();
        mFakeDragBeginTime = time;
        return true;
    }

    /**
     * End a fake drag of the pager.
     *
     * @see #beginFakeDrag()
     * @see #fakeDragBy(float)
     */
    public void endFakeDrag() {
        if (!mFakeDragging) {
            throw new IllegalStateException("No fake drag in progress. Call beginFakeDrag first.");
        }

        if (mAdapter != null) {
            final VelocityTracker velocityTracker = mVelocityTracker;
            velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
            int initialVelocity = (int) VelocityTrackerCompat.getXVelocity(
                    velocityTracker, mActivePointerId);
            mPopulatePending = true;
            final int width = getClientWidth();
            final int scrollX = getScrollX();
            final ItemInfo ii = infoForCurrentScrollPosition();
            final int currentPage = ii.position;
            final float pageOffset = (((float) scrollX / width) - ii.offset) / ii.widthFactor;
            final int totalDelta = (int) (mLastMotionX - mInitialMotionX);
            int nextPage = determineTargetPage(currentPage, pageOffset, initialVelocity,
                    totalDelta);
            setCurrentItemInternal(nextPage, true, true, initialVelocity);
        }
        endDrag();

        mFakeDragging = false;
    }

    /**
     * Fake drag by an offset in pixels. You must have called {@link #beginFakeDrag()} first.
     *
     * @param xOffset Offset in pixels to drag by.
     *
     * @see #beginFakeDrag()
     * @see #endFakeDrag()
     */
    public void fakeDragBy(float xOffset) {
        if (!mFakeDragging) {
            throw new IllegalStateException("No fake drag in progress. Call beginFakeDrag first.");
        }

        if (mAdapter == null) {
            return;
        }

        mLastMotionX += xOffset;

        float oldScrollX = getScrollX();
        float scrollX = oldScrollX - xOffset;
        final int width = getClientWidth();

        float leftBound = width * mFirstOffset;
        float rightBound = width * mLastOffset;

        final ItemInfo firstItem = mItems.get(0);
        final ItemInfo lastItem = mItems.get(mItems.size() - 1);
        if (firstItem.position != 0) {
            leftBound = firstItem.offset * width;
        }
        if (lastItem.position != mAdapter.getCount() - 1) {
            rightBound = lastItem.offset * width;
        }

        if (scrollX < leftBound) {
            scrollX = leftBound;
        } else if (scrollX > rightBound) {
            scrollX = rightBound;
        }
        // Don't lose the rounded component
        mLastMotionX += scrollX - (int) scrollX;
        scrollTo((int) scrollX, getScrollY());
        pageScrolled((int) scrollX);

        // Synthesize an event for the VelocityTracker.
        final long time = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(mFakeDragBeginTime, time, MotionEvent.ACTION_MOVE,
                mLastMotionX, 0, 0);
        mVelocityTracker.addMovement(ev);
        ev.recycle();
    }

    /**
     * Returns true if a fake drag is in progress.
     *
     * @return true if currently in a fake drag, false otherwise.
     *
     * @see #beginFakeDrag()
     * @see #fakeDragBy(float)
     * @see #endFakeDrag()
     */
    public boolean isFakeDragging() {
        return mFakeDragging;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionX = ev.getX(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    private void endDrag() {
        mIsBeingDragged = false;
        mIsUnableToDrag = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void setScrollingCacheEnabled(boolean enabled) {
        if (mScrollingCacheEnabled != enabled) {
            mScrollingCacheEnabled = enabled;
            if (USE_CACHE) {
                final int size = getChildCount();
                for (int i = 0; i < size; ++i) {
                    final View child = getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        child.setDrawingCacheEnabled(enabled);
                    }
                }
            }
        }
    }

    /**
     * Check if this ViewPager can be scrolled horizontally in a certain direction.
     *
     * @param direction Negative to check scrolling left, positive to check scrolling right.
     *
     * @return Whether this ViewPager can be scrolled in the specified direction. It will always
     * return false if the specified direction is 0.
     */
    public boolean canScrollHorizontally(int direction) {
        if (mAdapter == null) {
            return false;
        }

        final int width = getClientWidth();
        final int scrollX = getScrollX();
        if (direction < 0) {
            return (scrollX > (int) (width * mFirstOffset));
        } else if (direction > 0) {
            return (scrollX < (int) (width * mLastOffset));
        } else {
            return false;
        }
    }

    /**
     * Tests scrollability within child views of v given a delta of dx.
     *
     * @param v      View to test for horizontal scrollability
     * @param checkV Whether the view v passed should itself be checked for scrollability (true),
     *               or just its children (false).
     * @param dx     Delta scrolled in pixels
     * @param x      X coordinate of the active touch point
     * @param y      Y coordinate of the active touch point
     *
     * @return true if child views of v can be scrolled by delta of dx.
     */
    protected boolean canScroll(View v, boolean checkV, int dx, int x, int y) {
        if (v instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) v;
            final int scrollX = v.getScrollX();
            final int scrollY = v.getScrollY();
            final int count = group.getChildCount();
            // Count backwards - let topmost views consume scroll distance first.
            for (int i = count - 1; i >= 0; i--) {
                // TODO: Add versioned support here for transformed views.
                // This will not work for transformed views in Honeycomb+
                final View child = group.getChildAt(i);
                if (x + scrollX >= child.getLeft() && x + scrollX < child.getRight()
                        && y + scrollY >= child.getTop() && y + scrollY < child.getBottom()
                        && canScroll(child, true, dx, x + scrollX - child.getLeft(),
                        y + scrollY - child.getTop())) {
                    return true;
                }
            }
        }

        return checkV && ViewCompat.canScrollHorizontally(v, -dx);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Let the focused view and/or our descendants get the key first
        return super.dispatchKeyEvent(event) || executeKeyEvent(event);
    }

    /**
     * You can call this function yourself to have the scroll view perform
     * scrolling from a key event, just as if the event had been dispatched to
     * it by the view hierarchy.
     *
     * @param event The key event to execute.
     *
     * @return Return true if the event was handled, else false.
     */
    public boolean executeKeyEvent(KeyEvent event) {
        boolean handled = false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    handled = arrowScroll(FOCUS_LEFT);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    handled = arrowScroll(FOCUS_RIGHT);
                    break;
                case KeyEvent.KEYCODE_TAB:
                    if (Build.VERSION.SDK_INT >= 11) {
                        // The focus finder had a bug handling FOCUS_FORWARD and FOCUS_BACKWARD
                        // before Android 3.0. Ignore the tab key on those devices.
                        if (KeyEventCompat.hasNoModifiers(event)) {
                            handled = arrowScroll(FOCUS_FORWARD);
                        } else if (KeyEventCompat.hasModifiers(event, KeyEvent.META_SHIFT_ON)) {
                            handled = arrowScroll(FOCUS_BACKWARD);
                        }
                    }
                    break;
            }
        }
        return handled;
    }

    /**
     * Handle scrolling in response to a left or right arrow click.
     *
     * @param direction The direction corresponding to the arrow key that was pressed. It should be
     *                  either {@link View#FOCUS_LEFT} or {@link View#FOCUS_RIGHT}.
     *
     * @return Whether the scrolling was handled successfully.
     */
    public boolean arrowScroll(int direction) {
        View currentFocused = findFocus();
        if (currentFocused == this) {
            currentFocused = null;
        } else if (currentFocused != null) {
            boolean isChild = false;
            for (ViewParent parent = currentFocused.getParent(); parent instanceof ViewGroup;
                 parent = parent.getParent()) {
                if (parent == this) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild) {
                // This would cause the focus search down below to fail in fun ways.
                final StringBuilder sb = new StringBuilder();
                sb.append(currentFocused.getClass().getSimpleName());
                for (ViewParent parent = currentFocused.getParent(); parent instanceof ViewGroup;
                     parent = parent.getParent()) {
                    sb.append(" => ").append(parent.getClass().getSimpleName());
                }
                Log.e(TAG, "arrowScroll tried to find focus based on non-child "
                        + "current focused view " + sb.toString());
                currentFocused = null;
            }
        }

        boolean handled = false;

        View nextFocused = FocusFinder.getInstance().findNextFocus(this, currentFocused,
                direction);
        if (nextFocused != null && nextFocused != currentFocused) {
            if (direction == View.FOCUS_LEFT) {
                // If there is nothing to the left, or this is causing us to
                // jump to the right, then what we really want to do is page left.
                final int nextLeft = getChildRectInPagerCoordinates(mTempRect, nextFocused).left;
                final int currLeft = getChildRectInPagerCoordinates(mTempRect, currentFocused).left;
                if (currentFocused != null && nextLeft >= currLeft) {
                    handled = pageLeft();
                } else {
                    handled = nextFocused.requestFocus();
                }
            } else if (direction == View.FOCUS_RIGHT) {
                // If there is nothing to the right, or this is causing us to
                // jump to the left, then what we really want to do is page right.
                final int nextLeft = getChildRectInPagerCoordinates(mTempRect, nextFocused).left;
                final int currLeft = getChildRectInPagerCoordinates(mTempRect, currentFocused).left;
                if (currentFocused != null && nextLeft <= currLeft) {
                    handled = pageRight();
                } else {
                    handled = nextFocused.requestFocus();
                }
            }
        } else if (direction == FOCUS_LEFT || direction == FOCUS_BACKWARD) {
            // Trying to move left and nothing there; try to page.
            handled = pageLeft();
        } else if (direction == FOCUS_RIGHT || direction == FOCUS_FORWARD) {
            // Trying to move right and nothing there; try to page.
            handled = pageRight();
        }
        if (handled) {
            playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
        }
        return handled;
    }

    private Rect getChildRectInPagerCoordinates(Rect outRect, View child) {
        if (outRect == null) {
            outRect = new Rect();
        }
        if (child == null) {
            outRect.set(0, 0, 0, 0);
            return outRect;
        }
        outRect.left = child.getLeft();
        outRect.right = child.getRight();
        outRect.top = child.getTop();
        outRect.bottom = child.getBottom();

        ViewParent parent = child.getParent();
        while (parent instanceof ViewGroup && parent != this) {
            final ViewGroup group = (ViewGroup) parent;
            outRect.left += group.getLeft();
            outRect.right += group.getRight();
            outRect.top += group.getTop();
            outRect.bottom += group.getBottom();

            parent = group.getParent();
        }
        return outRect;
    }

    boolean pageLeft() {
        if (mCurItem > 0) {
            setCurrentItem(mCurItem - 1, true);
            return true;
        }
        return false;
    }

    boolean pageRight() {
        if (mAdapter != null && mCurItem < (mAdapter.getCount() - 1)) {
            setCurrentItem(mCurItem + 1, true);
            return true;
        }
        return false;
    }

    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        final int focusableCount = views.size();

        final int descendantFocusability = getDescendantFocusability();

        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == VISIBLE) {
                    ItemInfo ii = infoForChild(child);
                    if (ii != null && ii.position == mCurItem) {
                        child.addFocusables(views, direction, focusableMode);
                    }
                }
            }
        }

        // we add ourselves (if focusable) in all cases except for when we are
        // FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.  this is
        // to avoid the focus search finding layouts when a more precise search
        // among the focusable children would be more interesting.
        if (descendantFocusability != FOCUS_AFTER_DESCENDANTS
                || (focusableCount == views.size())) { // No focusable descendants
            // Note that we can't call the superclass here, because it will
            // add all views in.  So we need to do the same thing View does.
            if (!isFocusable()) {
                return;
            }
            if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE
                    && isInTouchMode() && !isFocusableInTouchMode()) {
                return;
            }
            if (views != null) {
                views.add(this);
            }
        }
    }

    /**
     * We only want the current page that is being shown to be touchable.
     */
    @Override
    public void addTouchables(ArrayList<View> views) {
        // Note that we don't call super.addTouchables(), which means that
        // we don't call View.addTouchables().  This is okay because a ViewPager
        // is itself not touchable.
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == mCurItem) {
                    child.addTouchables(views);
                }
            }
        }
    }

    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    protected boolean onRequestFocusInDescendants(int direction,
                                                  Rect previouslyFocusedRect) {
        int index;
        int increment;
        int end;
        int count = getChildCount();
        if ((direction & FOCUS_FORWARD) != 0) {
            index = 0;
            increment = 1;
            end = count;
        } else {
            index = count - 1;
            increment = -1;
            end = -1;
        }
        for (int i = index; i != end; i += increment) {
            View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == mCurItem) {
                    if (child.requestFocus(direction, previouslyFocusedRect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        // Dispatch scroll events from this ViewPager.
        if (event.getEventType() == AccessibilityEventCompat.TYPE_VIEW_SCROLLED) {
            return super.dispatchPopulateAccessibilityEvent(event);
        }

        // Dispatch all other accessibility events from the current page.
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                final ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == mCurItem
                        && child.dispatchPopulateAccessibilityEvent(event)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return generateDefaultLayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams && super.checkLayoutParams(p);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    class MyAccessibilityDelegate extends AccessibilityDelegateCompat {

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName(ViewPager.class.getName());
            final AccessibilityRecordCompat recordCompat =
                    AccessibilityEventCompat.asRecord(event);
            recordCompat.setScrollable(canScroll());
            if (event.getEventType() == AccessibilityEventCompat.TYPE_VIEW_SCROLLED
                    && mAdapter != null) {
                recordCompat.setItemCount(mAdapter.getCount());
                recordCompat.setFromIndex(mCurItem);
                recordCompat.setToIndex(mCurItem);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(ViewPager.class.getName());
            info.setScrollable(canScroll());
            if (canScrollHorizontally(1)) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
            }
            if (canScrollHorizontally(-1)) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (super.performAccessibilityAction(host, action, args)) {
                return true;
            }
            switch (action) {
                case AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD: {
                    if (canScrollHorizontally(1)) {
                        setCurrentItem(mCurItem + 1);
                        return true;
                    }
                }
                return false;
                case AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD: {
                    if (canScrollHorizontally(-1)) {
                        setCurrentItem(mCurItem - 1);
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        private boolean canScroll() {
            return (mAdapter != null) && (mAdapter.getCount() > 1);
        }
    }

    private class PagerObserver extends DataSetObserver {
        PagerObserver() {
        }

        @Override
        public void onChanged() {
            dataSetChanged();
        }

        @Override
        public void onInvalidated() {
            dataSetChanged();
        }
    }

    /**
     * Layout parameters that should be supplied for views added to a
     * ViewPager.
     */
    public static class LayoutParams extends ViewGroup.LayoutParams {
        /**
         * true if this view is a decoration(装饰) on the pager itself and not
         * a view supplied by the adapter.
         * <p>
         * 该View是pager本身而不是用适配器添加的view
         */
        public boolean isDecor;

        /**
         * Gravity setting for use on decor views only:
         * Where to position the view page within the overall ViewPager
         * container; constants are defined in {@link android.view.Gravity}.
         */
        public int gravity;

        /**
         * Width as a 0-1 multiplier of the measured pager width
         */
        float widthFactor = 0.f;

        /**
         * true if this view was added during layout and needs to be measured
         * before being positioned.
         */
        boolean needsMeasure;

        /**
         * Adapter position this view is for if !isDecor
         */
        int position;

        /**
         * Current child index within the ViewPager that this view occupies
         */
        int childIndex;

        public LayoutParams() {
            super(MATCH_PARENT, MATCH_PARENT);
        }

        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);

            final TypedArray a = context.obtainStyledAttributes(attrs, LAYOUT_ATTRS);
            gravity = a.getInteger(0, Gravity.TOP);
            a.recycle();
        }
    }

    static class ViewPositionComparator implements Comparator<View> {
        @Override
        public int compare(View lhs, View rhs) {
            final LayoutParams llp = (LayoutParams) lhs.getLayoutParams();
            final LayoutParams rlp = (LayoutParams) rhs.getLayoutParams();
            if (llp.isDecor != rlp.isDecor) {
                return llp.isDecor ? 1 : -1;
            }
            return llp.position - rlp.position;
        }
    }
}
