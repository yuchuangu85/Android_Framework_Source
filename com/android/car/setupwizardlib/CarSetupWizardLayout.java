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
package com.android.car.setupwizardlib;

import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;


import java.util.Locale;

/**
 * Custom layout for the Car Setup Wizard. Provides accessors for modifying elements such as buttons
 * and progress bars. Any modifications to elements built by
 * the CarSetupWizardLayout should be done through methods provided by this class unless that is
 * not possible so as to keep the state internally consistent.
 */
public class CarSetupWizardLayout extends LinearLayout {
    private static final int ANIMATION_DURATION_MS = 100;

    private View mBackButton;
    private View mTitleBar;
    private Float mTitleBarElevation;
    private TextView mToolbarTitle;

    /* <p>The Primary Toolbar Button should always be used when there is only a single action that
     * moves the wizard to the next screen (e.g. Only need a 'Skip' button).
     *
     * When there are two actions that can move the wizard to the next screen (e.g. either 'Skip'
     * or 'Let's Go' are the two options), then the Primary is used for the positive action
     * while the Secondary is used for the negative action.</p>
     */
    private Button mPrimaryToolbarButton;

    /*
     * Flag to track the primary toolbar button flat state.
     */
    private boolean mPrimaryToolbarButtonFlat;
    private View.OnClickListener mPrimaryToolbarButtonOnClick;
    private Button mSecondaryToolbarButton;
    private ProgressBar mProgressBar;

    public CarSetupWizardLayout(Context context) {
        this(context, null);
    }

    public CarSetupWizardLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarSetupWizardLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    /**
     * On initialization, the layout gets all of the custom attributes and initializes
     * the custom views that can be set by the user (e.g. back button, continue button).
     */
    public CarSetupWizardLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        TypedArray attrArray = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.CarSetupWizardLayout,
                0, 0);

        init(attrArray);
    }

    /**
     * Inflates the layout and sets the custom views (e.g. back button, continue button).
     */
    private void init(TypedArray attrArray) {
        boolean showBackButton;

        boolean showToolbarTitle;
        String toolbarTitleText;

        boolean showPrimaryToolbarButton;
        String primaryToolbarButtonText;
        boolean primaryToolbarButtonEnabled;

        boolean showSecondaryToolbarButton;
        String secondaryToolbarButtonText;
        boolean secondaryToolbarButtonEnabled;

        boolean showProgressBar;
        boolean indeterminateProgressBar;

        try {
            showBackButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showBackButton, true);
            showToolbarTitle = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showToolbarTitle, false);
            toolbarTitleText = attrArray.getString(
                    R.styleable.CarSetupWizardLayout_toolbarTitleText);
            showPrimaryToolbarButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showPrimaryToolbarButton, true);
            primaryToolbarButtonText = attrArray.getString(
                    R.styleable.CarSetupWizardLayout_primaryToolbarButtonText);
            primaryToolbarButtonEnabled = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_primaryToolbarButtonEnabled, true);
            mPrimaryToolbarButtonFlat = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_primaryToolbarButtonFlat, false);
            showSecondaryToolbarButton = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showSecondaryToolbarButton, false);
            secondaryToolbarButtonText = attrArray.getString(
                    R.styleable.CarSetupWizardLayout_secondaryToolbarButtonText);
            secondaryToolbarButtonEnabled = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_secondaryToolbarButtonEnabled, true);
            showProgressBar = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_showProgressBar, false);
            indeterminateProgressBar = attrArray.getBoolean(
                    R.styleable.CarSetupWizardLayout_indeterminateProgressBar, true);
        } finally {
            attrArray.recycle();
        }

        LayoutInflater inflater = LayoutInflater.from(getContext());
        inflater.inflate(R.layout.car_setup_wizard_layout, this);

        // Set the back button visibility based on the custom attribute.
        setBackButton(findViewById(R.id.back_button));
        setBackButtonVisible(showBackButton);

        // Se the title bar.
        setTitleBar(findViewById(R.id.application_bar));
        mTitleBarElevation =
                getContext().getResources().getDimension(R.dimen.title_bar_drop_shadow_elevation);

        // Set the toolbar title visibility and text based on the custom attributes.
        setToolbarTitle(findViewById(R.id.toolbar_title));
        if (showToolbarTitle) {
            setToolbarTitleText(toolbarTitleText);
        } else {
            setToolbarTitleVisible(false);
        }

        // Set the primary continue button visibility and text based on the custom attributes.
        ViewStub primaryToolbarButtonStub =
                (ViewStub) findViewById(R.id.primary_toolbar_button_stub);
        // Set the button layout to flat if that attribute was set.
        if (mPrimaryToolbarButtonFlat) {
            primaryToolbarButtonStub.setLayoutResource(R.layout.flat_button);
        }
        primaryToolbarButtonStub.inflate();
        setPrimaryToolbarButton(findViewById(R.id.primary_toolbar_button));
        if (showPrimaryToolbarButton) {
            setPrimaryToolbarButtonText(primaryToolbarButtonText);
            setPrimaryToolbarButtonEnabled(primaryToolbarButtonEnabled);
        } else {
            setPrimaryToolbarButtonVisible(false);
        }

        // Set the secondary continue button visibility and text based on the custom attributes.
        ViewStub secondaryToolbarButtonStub =
                (ViewStub) findViewById(R.id.secondary_toolbar_button_stub);
        if (showSecondaryToolbarButton || !TextUtils.isEmpty(secondaryToolbarButtonText)) {
            secondaryToolbarButtonStub.inflate();
            mSecondaryToolbarButton = findViewById(R.id.secondary_toolbar_button);
            setSecondaryToolbarButtonText(secondaryToolbarButtonText);
            setSecondaryToolbarButtonEnabled(secondaryToolbarButtonEnabled);
            setSecondaryToolbarButtonVisible(showSecondaryToolbarButton);
        }

        mProgressBar = findViewById(R.id.progress_bar);
        setProgressBarVisible(showProgressBar);
        setProgressBarIndeterminate(indeterminateProgressBar);

        // Set orientation programmatically since the inflated layout uses <merge>
        setOrientation(LinearLayout.VERTICAL);
    }

    /**
     * Set a given view's visibility.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    void setViewVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // Add or remove the back button touch delegate depending on whether it is visible.
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    void updateBackButtonTouchDelegate(boolean visible) {
        if (visible) {
            // Post this action in the parent's message queue to make sure the parent
            // lays out its children before getHitRect() is called
            this.post(() -> {
                Rect delegateArea = new Rect();

                mBackButton.getHitRect(delegateArea);

                /*
                 * Update the delegate area based on the difference between the current size and
                 * the touch target size
                 */
                float touchTargetSize = getResources().getDimension(
                        R.dimen.car_touch_target_size);
                float primaryIconSize = getResources().getDimension(
                        R.dimen.car_primary_icon_size);

                int sizeDifference = (int) ((touchTargetSize - primaryIconSize) / 2);

                delegateArea.right += sizeDifference;
                delegateArea.bottom += sizeDifference;
                delegateArea.left -= sizeDifference;
                delegateArea.top -= sizeDifference;

                // Set the TouchDelegate on the parent view
                TouchDelegate touchDelegate = new TouchDelegate(delegateArea,
                        mBackButton);

                if (View.class.isInstance(mBackButton.getParent())) {
                    ((View) mBackButton.getParent()).setTouchDelegate(touchDelegate);
                }
            });
        } else {
            // Set the TouchDelegate to null if the back button is not visible.
            if (View.class.isInstance(mBackButton.getParent())) {
                ((View) mBackButton.getParent()).setTouchDelegate(null);
            }
        }
    }

    /**
     * Gets the back button.
     */
    public View getBackButton() {
        return mBackButton;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    final void setBackButton(View backButton) {
        mBackButton = backButton;
    }

    /**
     * Set the back button onClickListener to given listener. Can be null if the listener should
     * be overridden so no callback is made.
     */
    public void setBackButtonListener(@Nullable View.OnClickListener listener) {
        mBackButton.setOnClickListener(listener);
    }

    /**
     * Set the back button visibility to the given visibility.
     */
    public void setBackButtonVisible(boolean visible) {
        setViewVisible(mBackButton, visible);
        updateBackButtonTouchDelegate(visible);
    }

    /**
     * Sets the title bar view.
     */
    private void setTitleBar(View titleBar) {
        mTitleBar = titleBar;
    }

    /**
     * Gets the toolbar title.
     */
    public TextView getToolbarTitle() {
        return mToolbarTitle;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    final void setToolbarTitle(TextView toolbarTitle) {
        mToolbarTitle = toolbarTitle;
    }

    /**
     * Sets the header title visibility to given value.
     */
    public void setToolbarTitleVisible(boolean visible) {
        setViewVisible(mToolbarTitle, visible);
    }

    /**
     * Sets the header title text to the provided text.
     */
    public void setToolbarTitleText(String text) {
        mToolbarTitle.setText(text);
    }

    /**
     * Gets the primary toolbar button.
     */
    public Button getPrimaryToolbarButton() {
        return mPrimaryToolbarButton;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    final void setPrimaryToolbarButton(Button primaryToolbarButton) {
        mPrimaryToolbarButton = primaryToolbarButton;
    }

    /**
     * Set the primary continue button visibility to the given visibility.
     */
    public void setPrimaryToolbarButtonVisible(boolean visible) {
        setViewVisible(mPrimaryToolbarButton, visible);
    }

    /**
     * Set whether the primary continue button is enabled.
     */
    public void setPrimaryToolbarButtonEnabled(boolean enabled) {
        mPrimaryToolbarButton.setEnabled(enabled);
    }

    /**
     * Set the primary continue button text to the given text.
     */
    public void setPrimaryToolbarButtonText(String text) {
        mPrimaryToolbarButton.setText(text);
    }

    /**
     * Set the primary continue button onClickListener to the given listener. Can be null if the
     * listener should be overridden so no callback is made. All changes to primary toolbar
     * button's onClickListener should be made here so they can be stored through changes to the
     * button.
     */
    public void setPrimaryToolbarButtonListener(@Nullable View.OnClickListener listener) {
        mPrimaryToolbarButtonOnClick = listener;
        mPrimaryToolbarButton.setOnClickListener(listener);
    }

    /**
     * Getter for the flatness of the primary toolbar button.
     */
    public boolean getPrimaryToolbarButtonFlat() {
        return mPrimaryToolbarButtonFlat;
    }

    /**
     * Changes the button in the primary slot to a flat theme, maintaining the text, visibility,
     * whether it is enabled, and id.
     * <p>NOTE: that other attributes set manually on the primaryToolbarButton will be lost on calls
     * to this method as the button will be replaced.</p>
     */
    public void setPrimaryToolbarButtonFlat(boolean isFlat) {
        // Do nothing if the state isn't changing.
        if (isFlat == mPrimaryToolbarButtonFlat) {
            return;
        }
        Button newPrimaryButton = createPrimaryToolbarButton(isFlat);

        ViewGroup parent = (ViewGroup) findViewById(R.id.button_container);
        int buttonIndex = parent.indexOfChild(mPrimaryToolbarButton);
        parent.removeViewAt(buttonIndex);
        parent.addView(newPrimaryButton, buttonIndex);

        // Update state of layout
        setPrimaryToolbarButton(newPrimaryButton);
        mPrimaryToolbarButtonFlat = isFlat;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    Button createPrimaryToolbarButton(boolean isFlat) {
        int layoutId = isFlat ? R.layout.flat_button : R.layout.primary_button;
        Button newPrimaryButton = (Button) inflate(getContext(), layoutId, null);
        newPrimaryButton.setId(mPrimaryToolbarButton.getId());
        newPrimaryButton.setVisibility(mPrimaryToolbarButton.getVisibility());
        newPrimaryButton.setEnabled(mPrimaryToolbarButton.isEnabled());
        newPrimaryButton.setText(mPrimaryToolbarButton.getText());
        newPrimaryButton.setOnClickListener(mPrimaryToolbarButtonOnClick);
        newPrimaryButton.setLayoutParams(mPrimaryToolbarButton.getLayoutParams());

        return newPrimaryButton;
    }

    /**
     * Gets the secondary toolbar button.
     */
    public Button getSecondaryToolbarButton() {
        return mSecondaryToolbarButton;
    }

    /**
     * Set the secondary continue button visibility to the given visibility.
     */
    public void setSecondaryToolbarButtonVisible(boolean visible) {
        // If not setting it visible and it hasn't been inflated yet then don't inflate.
        if (!visible && mSecondaryToolbarButton == null) {
            return;
        }
        maybeInflateSecondaryToolbarButton();
        setViewVisible(mSecondaryToolbarButton, visible);
    }

    /**
     * Sets whether the secondary continue button is enabled.
     */
    public void setSecondaryToolbarButtonEnabled(boolean enabled) {
        maybeInflateSecondaryToolbarButton();
        mSecondaryToolbarButton.setEnabled(enabled);
    }

    /**
     * Sets the secondary continue button text to the given text.
     */
    public void setSecondaryToolbarButtonText(String text) {
        maybeInflateSecondaryToolbarButton();
        mSecondaryToolbarButton.setText(text);
    }

    /**
     * Sets the secondary continue button onClickListener to the given listener. Can be null if the
     * listener should be overridden so no callback is made.
     */
    public void setSecondaryToolbarButtonListener(@Nullable View.OnClickListener listener) {
        maybeInflateSecondaryToolbarButton();
        mSecondaryToolbarButton.setOnClickListener(listener);
    }

    /**
     * A method that will inflate the SecondaryToolbarButton if it is has not already been
     * inflated. If it has been inflated already this method will do nothing.
     */
    private void maybeInflateSecondaryToolbarButton() {
        ViewStub secondaryToolbarButtonStub = findViewById(R.id.secondary_toolbar_button_stub);
        // If the secondaryToolbarButtonStub is null then the stub has been inflated so there is
        // nothing to do.
        if (secondaryToolbarButtonStub != null) {
            secondaryToolbarButtonStub.inflate();
            mSecondaryToolbarButton = findViewById(R.id.secondary_toolbar_button);
            setSecondaryToolbarButtonVisible(false);
        }

    }

    /**
     * Gets the progress bar.
     */
    public ProgressBar getProgressBar() {
        return mProgressBar;
    }

    /**
     * Sets the progress bar visibility to the given visibility.
     */
    public void setProgressBarVisible(boolean visible) {
        setViewVisible(mProgressBar, visible);
    }

    /**
     * Sets the progress bar indeterminate/determinate state.
     */
    public void setProgressBarIndeterminate(boolean indeterminate) {
        mProgressBar.setIndeterminate(indeterminate);
    }

    /**
     * Sets the progress bar's progress.
     */
    public void setProgressBarProgress(int progress) {
        setProgressBarIndeterminate(false);
        mProgressBar.setProgress(progress);
    }

    /**
     * Sets the locale to be used for rendering.
     */
    public void applyLocale(Locale locale) {
        if (locale == null) {
            return;
        }
        int direction = TextUtils.getLayoutDirectionFromLocale(locale);
        setLayoutDirection(direction);

        mToolbarTitle.setTextLocale(locale);
        mToolbarTitle.setLayoutDirection(direction);

        mPrimaryToolbarButton.setTextLocale(locale);
        mPrimaryToolbarButton.setLayoutDirection(direction);

        mSecondaryToolbarButton.setTextLocale(locale);
        mSecondaryToolbarButton.setLayoutDirection(direction);
    }

    /**
     * Adds elevation to the title bar in order to produce a drop shadow. An animation can be used
     * in cases where a direct elevation changes would be too jarring.
     *
     * @param animate True when a smooth animation is wanted for the adding of the elevation.
     */
    public void addElevationToTitleBar(boolean animate) {
        if (animate) {
            ValueAnimator elevationAnimator =
                    ValueAnimator.ofFloat(mTitleBar.getElevation(), mTitleBarElevation);
            elevationAnimator
                    .setDuration(ANIMATION_DURATION_MS)
                    .addUpdateListener(
                            animation -> mTitleBar.setElevation(
                                    (float) animation.getAnimatedValue()));
            elevationAnimator.start();
        } else {
            mTitleBar.setElevation(mTitleBarElevation);
        }
    }

    /**
     * Removes the elevation from the title bar, an animation can be used in cases where a direct
     * elevation changes would be too jarring.
     *
     * @param animate True when a smooth animation is wanted for the removal of the elevation.
     */
    public void removeElevationFromTitleBar(boolean animate) {
        if (animate) {
            ValueAnimator elevationAnimator =
                    ValueAnimator.ofFloat(mTitleBar.getElevation(), 0f);
            elevationAnimator
                    .setDuration(ANIMATION_DURATION_MS)
                    .addUpdateListener(
                            animation -> mTitleBar.setElevation(
                                    (float) animation.getAnimatedValue()));
            elevationAnimator.start();
        } else {
            mTitleBar.setElevation(0f);
        }
    }
}
