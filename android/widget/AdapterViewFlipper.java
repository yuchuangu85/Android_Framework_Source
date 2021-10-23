/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews.RemoteView;

/**
 * Simple {@link ViewAnimator} that will animate between two or more views
 * that have been added to it.  Only one child is shown at a time.  If
 * requested, can automatically flip between each child at a regular interval.
 *
 * @attr ref android.R.styleable#AdapterViewFlipper_flipInterval
 * @attr ref android.R.styleable#AdapterViewFlipper_autoStart
 */
@RemoteView
public class AdapterViewFlipper extends AdapterViewAnimator {
    private static final String TAG = "ViewFlipper";
    private static final boolean LOGD = false;

    private static final int DEFAULT_INTERVAL = 10000;

    private int mFlipInterval = DEFAULT_INTERVAL;
    private boolean mAutoStart = false;

    private boolean mRunning = false;
    private boolean mStarted = false;
    private boolean mVisible = false;
    private boolean mUserPresent = true;
    private boolean mAdvancedByHost = false;

    public AdapterViewFlipper(Context context) {
        super(context);
    }

    public AdapterViewFlipper(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AdapterViewFlipper(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AdapterViewFlipper(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.AdapterViewFlipper, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, com.android.internal.R.styleable.AdapterViewFlipper,
                attrs, a, defStyleAttr, defStyleRes);
        mFlipInterval = a.getInt(
                com.android.internal.R.styleable.AdapterViewFlipper_flipInterval, DEFAULT_INTERVAL);
        mAutoStart = a.getBoolean(
                com.android.internal.R.styleable.AdapterViewFlipper_autoStart, false);

        // A view flipper should cycle through the views
        mLoopViews = true;

        a.recycle();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mUserPresent = false;
                updateRunning();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                mUserPresent = true;
                updateRunning(false);
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Listen for broadcasts related to user-presence
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);

        // OK, this is gross but needed. This class is supported by the
        // remote views machanism and as a part of that the remote views
        // can be inflated by a context for another user without the app
        // having interact users permission - just for loading resources.
        // For exmaple, when adding widgets from a user profile to the
        // home screen. Therefore, we register the receiver as the current
        // user not the one the context is for.
        getContext().registerReceiverAsUser(mReceiver, android.os.Process.myUserHandle(),
                filter, null, getHandler());


        if (mAutoStart) {
            // Automatically start when requested
            startFlipping();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mVisible = false;

        getContext().unregisterReceiver(mReceiver);
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mVisible = (visibility == VISIBLE);
        updateRunning(false);
    }

    @Override
    public void setAdapter(Adapter adapter) {
        super.setAdapter(adapter);
        updateRunning();
    }

    /**
     * Returns the flip interval, in milliseconds.
     *
     * @return the flip interval in milliseconds
     *
     * @see #setFlipInterval(int)
     *
     * @attr ref android.R.styleable#AdapterViewFlipper_flipInterval
     */
    public int getFlipInterval() {
        return mFlipInterval;
    }

    /**
     * How long to wait before flipping to the next view.
     *
     * @param flipInterval flip interval in milliseconds
     *
     * @see #getFlipInterval()
     *
     * @attr ref android.R.styleable#AdapterViewFlipper_flipInterval
     */
    public void setFlipInterval(int flipInterval) {
        mFlipInterval = flipInterval;
    }

    /**
     * Start a timer to cycle through child views
     */
    public void startFlipping() {
        mStarted = true;
        updateRunning();
    }

    /**
     * No more flips
     */
    public void stopFlipping() {
        mStarted = false;
        updateRunning();
    }

    /**
    * {@inheritDoc}
    */
   @Override
   @RemotableViewMethod
   public void showNext() {
       // if the flipper is currently flipping automatically, and showNext() is called
       // we should we should make sure to reset the timer
       if (mRunning) {
           removeCallbacks(mFlipRunnable);
           postDelayed(mFlipRunnable, mFlipInterval);
       }
       super.showNext();
   }

   /**
    * {@inheritDoc}
    */
   @Override
   @RemotableViewMethod
   public void showPrevious() {
       // if the flipper is currently flipping automatically, and showPrevious() is called
       // we should we should make sure to reset the timer
       if (mRunning) {
           removeCallbacks(mFlipRunnable);
           postDelayed(mFlipRunnable, mFlipInterval);
       }
       super.showPrevious();
   }

    /**
     * Internal method to start or stop dispatching flip {@link Message} based
     * on {@link #mRunning} and {@link #mVisible} state.
     */
    private void updateRunning() {
        // by default when we update running, we want the
        // current view to animate in
        updateRunning(true);
    }

    /**
     * Internal method to start or stop dispatching flip {@link Message} based
     * on {@link #mRunning} and {@link #mVisible} state.
     *
     * @param flipNow Determines whether or not to execute the animation now, in
     *            addition to queuing future flips. If omitted, defaults to
     *            true.
     */
    private void updateRunning(boolean flipNow) {
        boolean running = !mAdvancedByHost && mVisible && mStarted && mUserPresent
                && mAdapter != null;
        if (running != mRunning) {
            if (running) {
                showOnly(mWhichChild, flipNow);
                postDelayed(mFlipRunnable, mFlipInterval);
            } else {
                removeCallbacks(mFlipRunnable);
            }
            mRunning = running;
        }
        if (LOGD) {
            Log.d(TAG, "updateRunning() mVisible=" + mVisible + ", mStarted=" + mStarted
                    + ", mUserPresent=" + mUserPresent + ", mRunning=" + mRunning);
        }
    }

    /**
     * Returns true if the child views are flipping.
     */
    public boolean isFlipping() {
        return mStarted;
    }

    /**
     * Set if this view automatically calls {@link #startFlipping()} when it
     * becomes attached to a window.
     */
    public void setAutoStart(boolean autoStart) {
        mAutoStart = autoStart;
    }

    /**
     * Returns true if this view automatically calls {@link #startFlipping()}
     * when it becomes attached to a window.
     */
    public boolean isAutoStart() {
        return mAutoStart;
    }

    private final Runnable mFlipRunnable = new Runnable() {
        @Override
        public void run() {
            if (mRunning) {
                showNext();
            }
        }
    };

    /**
     * Called by an {@link android.appwidget.AppWidgetHost} to indicate that it will be
     * automatically advancing the views of this {@link AdapterViewFlipper} by calling
     * {@link AdapterViewFlipper#advance()} at some point in the future. This allows
     * {@link AdapterViewFlipper} to prepare by no longer Advancing its children.
     */
    @Override
    public void fyiWillBeAdvancedByHostKThx() {
        mAdvancedByHost = true;
        updateRunning(false);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return AdapterViewFlipper.class.getName();
    }
}
