/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media;

import static android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS;

import static com.android.systemui.media.SmartspaceMediaDataKt.NUM_REQUIRED_RECOMMENDATIONS;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.app.PendingIntent;
import android.app.WallpaperColors;
import android.app.smartspace.SmartspaceAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.TransitionDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Process;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.GhostedViewLaunchAnimatorController;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.monet.Style;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.animation.TransitionLayout;
import com.android.systemui.util.time.SystemClock;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.Lazy;
import kotlin.Unit;

/**
 * A view controller used for Media Playback.
 */
public class MediaControlPanel {
    protected static final String TAG = "MediaControlPanel";

    private static final float DISABLED_ALPHA = 0.38f;
    private static final String EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME = "com.google"
            + ".android.apps.gsa.staticplugins.opa.smartspace.ExportedSmartspaceTrampolineActivity";
    private static final String EXTRAS_SMARTSPACE_INTENT =
            "com.google.android.apps.gsa.smartspace.extra.SMARTSPACE_INTENT";
    private static final String KEY_SMARTSPACE_ARTIST_NAME = "artist_name";
    private static final String KEY_SMARTSPACE_OPEN_IN_FOREGROUND = "KEY_OPEN_IN_FOREGROUND";
    protected static final String KEY_SMARTSPACE_APP_NAME = "KEY_SMARTSPACE_APP_NAME";

    // Event types logged by smartspace
    private static final int SMARTSPACE_CARD_CLICK_EVENT = 760;
    protected static final int SMARTSPACE_CARD_DISMISS_EVENT = 761;

    private static final Intent SETTINGS_INTENT = new Intent(ACTION_MEDIA_CONTROLS_SETTINGS);

    // Buttons to show in small player when using semantic actions
    private static final List<Integer> SEMANTIC_ACTIONS_COMPACT = List.of(
            R.id.actionPlayPause,
            R.id.actionPrev,
            R.id.actionNext
    );

    // Buttons that should get hidden when we're scrubbing (they will be replaced with the views
    // showing scrubbing time)
    private static final List<Integer> SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING = List.of(
            R.id.actionPrev,
            R.id.actionNext
    );

    // Buttons to show in small player when using semantic actions
    private static final List<Integer> SEMANTIC_ACTIONS_ALL = List.of(
            R.id.actionPlayPause,
            R.id.actionPrev,
            R.id.actionNext,
            R.id.action0,
            R.id.action1
    );

    private final SeekBarViewModel mSeekBarViewModel;
    private SeekBarObserver mSeekBarObserver;
    protected final Executor mBackgroundExecutor;
    private final Executor mMainExecutor;
    private final ActivityStarter mActivityStarter;
    private final BroadcastSender mBroadcastSender;

    private Context mContext;
    private MediaViewHolder mMediaViewHolder;
    private RecommendationViewHolder mRecommendationViewHolder;
    private String mKey;
    private MediaData mMediaData;
    private SmartspaceMediaData mRecommendationData;
    private MediaViewController mMediaViewController;
    private MediaSession.Token mToken;
    private MediaController mController;
    private Lazy<MediaDataManager> mMediaDataManagerLazy;
    // Uid for the media app.
    protected int mUid = Process.INVALID_UID;
    private int mSmartspaceMediaItemsCount;
    private MediaCarouselController mMediaCarouselController;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;
    private final FalsingManager mFalsingManager;
    private MetadataAnimationHandler mMetadataAnimationHandler;
    private ColorSchemeTransition mColorSchemeTransition;
    private Drawable mPrevArtwork = null;
    private boolean mIsArtworkBound = false;
    private int mArtworkBoundId = 0;
    private int mArtworkNextBindRequestId = 0;

    private final KeyguardStateController mKeyguardStateController;
    private final ActivityIntentHelper mActivityIntentHelper;
    private final NotificationLockscreenUserManager mLockscreenUserManager;

    // Used for logging.
    protected boolean mIsImpressed = false;
    private SystemClock mSystemClock;
    private MediaUiEventLogger mLogger;
    private InstanceId mInstanceId;
    protected int mSmartspaceId = -1;
    private String mPackageName;

    private boolean mIsScrubbing = false;
    private boolean mIsSeekBarEnabled = false;

    private final SeekBarViewModel.ScrubbingChangeListener mScrubbingChangeListener =
            this::setIsScrubbing;
    private final SeekBarViewModel.EnabledChangeListener mEnabledChangeListener =
            this::setIsSeekBarEnabled;

    /**
     * Initialize a new control panel
     *
     * @param backgroundExecutor background executor, used for processing artwork
     * @param mainExecutor main thread executor, used if we receive callbacks on the background
     *                     thread that then trigger UI changes.
     * @param activityStarter    activity starter
     */
    @Inject
    public MediaControlPanel(
            Context context,
            @Background Executor backgroundExecutor,
            @Main Executor mainExecutor,
            ActivityStarter activityStarter,
            BroadcastSender broadcastSender,
            MediaViewController mediaViewController,
            SeekBarViewModel seekBarViewModel,
            Lazy<MediaDataManager> lazyMediaDataManager,
            MediaOutputDialogFactory mediaOutputDialogFactory,
            MediaCarouselController mediaCarouselController,
            FalsingManager falsingManager,
            SystemClock systemClock,
            MediaUiEventLogger logger,
            KeyguardStateController keyguardStateController,
            ActivityIntentHelper activityIntentHelper,
            NotificationLockscreenUserManager lockscreenUserManager) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
        mActivityStarter = activityStarter;
        mBroadcastSender = broadcastSender;
        mSeekBarViewModel = seekBarViewModel;
        mMediaViewController = mediaViewController;
        mMediaDataManagerLazy = lazyMediaDataManager;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        mMediaCarouselController = mediaCarouselController;
        mFalsingManager = falsingManager;
        mSystemClock = systemClock;
        mLogger = logger;
        mKeyguardStateController = keyguardStateController;
        mActivityIntentHelper = activityIntentHelper;
        mLockscreenUserManager = lockscreenUserManager;

        mSeekBarViewModel.setLogSeek(() -> {
            if (mPackageName != null && mInstanceId != null) {
                mLogger.logSeek(mUid, mPackageName, mInstanceId);
            }
            logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT);
            return Unit.INSTANCE;
        });
    }

    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        }
        mSeekBarViewModel.removeScrubbingChangeListener(mScrubbingChangeListener);
        mSeekBarViewModel.removeEnabledChangeListener(mEnabledChangeListener);
        mSeekBarViewModel.onDestroy();
        mMediaViewController.onDestroy();
    }

    /**
     * Get the view holder used to display media controls.
     *
     * @return the media view holder
     */
    @Nullable
    public MediaViewHolder getMediaViewHolder() {
        return mMediaViewHolder;
    }

    /**
     * Get the recommendation view holder used to display Smartspace media recs.
     * @return the recommendation view holder
     */
    @Nullable
    public RecommendationViewHolder getRecommendationViewHolder() {
        return mRecommendationViewHolder;
    }

    /**
     * Get the view controller used to display media controls
     *
     * @return the media view controller
     */
    @NonNull
    public MediaViewController getMediaViewController() {
        return mMediaViewController;
    }

    /**
     * Sets the listening state of the player.
     * <p>
     * Should be set to true when the QS panel is open. Otherwise, false. This is a signal to avoid
     * unnecessary work when the QS panel is closed.
     *
     * @param listening True when player should be active. Otherwise, false.
     */
    public void setListening(boolean listening) {
        mSeekBarViewModel.setListening(listening);
    }

    /** Sets whether the user is touching the seek bar to change the track position. */
    private void setIsScrubbing(boolean isScrubbing) {
        if (mMediaData == null || mMediaData.getSemanticActions() == null) {
            return;
        }
        if (isScrubbing == this.mIsScrubbing) {
            return;
        }
        this.mIsScrubbing = isScrubbing;
        mMainExecutor.execute(() ->
                updateDisplayForScrubbingChange(mMediaData.getSemanticActions()));
    }

    private void setIsSeekBarEnabled(boolean isSeekBarEnabled) {
        if (isSeekBarEnabled == this.mIsSeekBarEnabled) {
            return;
        }
        this.mIsSeekBarEnabled = isSeekBarEnabled;
        updateSeekBarVisibility();
    }

    /**
     * Get the context
     *
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /** Attaches the player to the player view holder. */
    public void attachPlayer(MediaViewHolder vh) {
        mMediaViewHolder = vh;
        TransitionLayout player = vh.getPlayer();

        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        mSeekBarViewModel.attachTouchHandlers(vh.getSeekBar());
        mSeekBarViewModel.setScrubbingChangeListener(mScrubbingChangeListener);
        mSeekBarViewModel.setEnabledChangeListener(mEnabledChangeListener);
        mMediaViewController.attach(player, MediaViewController.TYPE.PLAYER);

        vh.getPlayer().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                openGuts();
                return true;
            } else {
                closeGuts();
                return true;
            }
        });

        // AlbumView uses a hardware layer so that clipping of the foreground is handled
        // with clipping the album art. Otherwise album art shows through at the edges.
        mMediaViewHolder.getAlbumView().setLayerType(View.LAYER_TYPE_HARDWARE, null);

        TextView titleText = mMediaViewHolder.getTitleText();
        TextView artistText = mMediaViewHolder.getArtistText();
        AnimatorSet enter = loadAnimator(R.anim.media_metadata_enter,
                Interpolators.EMPHASIZED_DECELERATE, titleText, artistText);
        AnimatorSet exit = loadAnimator(R.anim.media_metadata_exit,
                Interpolators.EMPHASIZED_ACCELERATE, titleText, artistText);

        mColorSchemeTransition = new ColorSchemeTransition(mContext, mMediaViewHolder);
        mMetadataAnimationHandler = new MetadataAnimationHandler(exit, enter);
    }

    @VisibleForTesting
    protected AnimatorSet loadAnimator(int animId, Interpolator motionInterpolator,
            View... targets) {
        ArrayList<Animator> animators = new ArrayList<>();
        for (View target : targets) {
            AnimatorSet animator = (AnimatorSet) AnimatorInflater.loadAnimator(mContext, animId);
            animator.getChildAnimations().get(0).setInterpolator(motionInterpolator);
            animator.setTarget(target);
            animators.add(animator);
        }

        AnimatorSet result = new AnimatorSet();
        result.playTogether(animators);
        return result;
    }

    /** Attaches the recommendations to the recommendation view holder. */
    public void attachRecommendation(RecommendationViewHolder vh) {
        mRecommendationViewHolder = vh;
        TransitionLayout recommendations = vh.getRecommendations();

        mMediaViewController.attach(recommendations, MediaViewController.TYPE.RECOMMENDATION);

        mRecommendationViewHolder.getRecommendations().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                openGuts();
                return true;
            } else {
                closeGuts();
                return true;
            }
        });
    }

    /** Bind this player view based on the data given. */
    public void bindPlayer(@NonNull MediaData data, String key) {
        if (mMediaViewHolder == null) {
            return;
        }
        Trace.beginSection("MediaControlPanel#bindPlayer<" + key + ">");
        mKey = key;
        mMediaData = data;
        MediaSession.Token token = data.getToken();
        mPackageName = data.getPackageName();
        mUid = data.getAppUid();
        // Only assigns instance id if it's unassigned.
        if (mSmartspaceId == -1) {
            mSmartspaceId = SmallHash.hash(mUid + (int) mSystemClock.currentTimeMillis());
        }
        mInstanceId = data.getInstanceId();

        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
        }

        if (mToken != null) {
            mController = new MediaController(mContext, mToken);
        } else {
            mController = null;
        }

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mMediaViewHolder.getPlayer().setOnClickListener(v -> {
                if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;
                if (mMediaViewController.isGutsVisible()) return;
                mLogger.logTapContentView(mUid, mPackageName, mInstanceId);
                logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT);

                // See StatusBarNotificationActivityStarter#onNotificationClicked
                boolean showOverLockscreen = mKeyguardStateController.isShowing()
                        && mActivityIntentHelper.wouldShowOverLockscreen(clickIntent.getIntent(),
                        mLockscreenUserManager.getCurrentUserId());

                if (showOverLockscreen) {
                    mActivityStarter.startActivity(clickIntent.getIntent(),
                            /* dismissShade */ true,
                            /* animationController */ null,
                            /* showOverLockscreenWhenLocked */ true);
                } else {
                    mActivityStarter.postStartActivityDismissingKeyguard(clickIntent,
                            buildLaunchAnimatorController(mMediaViewHolder.getPlayer()));
                }
            });
        }

        // Seek Bar
        final MediaController controller = getController();
        mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));

        bindOutputSwitcherChip(data);
        bindGutsMenuForPlayer(data);
        bindPlayerContentDescription(data);
        bindScrubbingTime(data);
        bindActionButtons(data);

        boolean isSongUpdated = bindSongMetadata(data);
        bindArtworkAndColors(data, key, isSongUpdated);

        // TODO: We don't need to refresh this state constantly, only if the state actually changed
        // to something which might impact the measurement
        // State refresh interferes with the translation animation, only run it if it's not running.
        if (!mMetadataAnimationHandler.isRunning()) {
            mMediaViewController.refreshState();
        }
        Trace.endSection();
    }

    private void bindOutputSwitcherChip(MediaData data) {
        // Output switcher chip
        ViewGroup seamlessView = mMediaViewHolder.getSeamless();
        seamlessView.setVisibility(View.VISIBLE);
        ImageView iconView = mMediaViewHolder.getSeamlessIcon();
        TextView deviceName = mMediaViewHolder.getSeamlessText();
        final MediaDeviceData device = data.getDevice();

        // Disable clicking on output switcher for invalid devices and resumption controls
        final boolean seamlessDisabled = (device != null && !device.getEnabled())
                || data.getResumption();
        final float seamlessAlpha = seamlessDisabled ? DISABLED_ALPHA : 1.0f;
        mMediaViewHolder.getSeamlessButton().setAlpha(seamlessAlpha);
        seamlessView.setEnabled(!seamlessDisabled);
        CharSequence deviceString = mContext.getString(R.string.media_seamless_other_device);
        if (device != null) {
            Drawable icon = device.getIcon();
            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mColorSchemeTransition.getBgColor());
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceString = device.getName();
        } else {
            // Set to default icon
            iconView.setImageResource(R.drawable.ic_media_home_devices);
        }
        deviceName.setText(deviceString);
        seamlessView.setContentDescription(deviceString);
        seamlessView.setOnClickListener(
                v -> {
                    if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        return;
                    }
                    mLogger.logOpenOutputSwitcher(mUid, mPackageName, mInstanceId);
                    if (device.getIntent() != null) {
                        if (device.getIntent().isActivity()) {
                            mActivityStarter.startActivity(
                                    device.getIntent().getIntent(), true);
                        } else {
                            try {
                                device.getIntent().send();
                            } catch (PendingIntent.CanceledException e) {
                                Log.e(TAG, "Device pending intent was canceled");
                            }
                        }
                    } else {
                        mMediaOutputDialogFactory.create(mPackageName, true,
                                mMediaViewHolder.getSeamlessButton());
                    }
                });
    }

    private void bindGutsMenuForPlayer(MediaData data) {
        Runnable onDismissClickedRunnable = () -> {
            if (mKey != null) {
                closeGuts();
                if (!mMediaDataManagerLazy.get().dismissMediaData(mKey,
                        MediaViewController.GUTS_ANIMATION_DURATION + 100)) {
                    Log.w(TAG, "Manager failed to dismiss media " + mKey);
                    // Remove directly from carousel so user isn't stuck with defunct controls
                    mMediaCarouselController.removePlayer(mKey, false, false);
                }
            } else {
                Log.w(TAG, "Dismiss media with null notification. Token uid="
                        + data.getToken().getUid());
            }
        };

        bindGutsMenuCommon(
                /* isDismissible= */ data.isClearable(),
                data.getApp(),
                mMediaViewHolder.getGutsViewHolder(),
                onDismissClickedRunnable);
    }

    private boolean bindSongMetadata(MediaData data) {
        TextView titleText = mMediaViewHolder.getTitleText();
        TextView artistText = mMediaViewHolder.getArtistText();
        return mMetadataAnimationHandler.setNext(
            Pair.create(data.getSong(), data.getArtist()),
            () -> {
                titleText.setText(data.getSong());
                artistText.setText(data.getArtist());

                // refreshState is required here to resize the text views (and prevent ellipsis)
                mMediaViewController.refreshState();
                return Unit.INSTANCE;
            },
            () -> {
                // After finishing the enter animation, we refresh state. This could pop if
                // something is incorrectly bound, but needs to be run if other elements were
                // updated while the enter animation was running
                mMediaViewController.refreshState();
                return Unit.INSTANCE;
            });
    }

    // We may want to look into unifying this with bindRecommendationContentDescription if/when we
    // do a refactor of this class.
    private void bindPlayerContentDescription(MediaData data) {
        if (mMediaViewHolder == null) {
            return;
        }

        CharSequence contentDescription;
        if (mMediaViewController.isGutsVisible()) {
            contentDescription = mMediaViewHolder.getGutsViewHolder().getGutsText().getText();
        } else if (data != null) {
            contentDescription = mContext.getString(
                    R.string.controls_media_playing_item_description,
                    data.getSong(),
                    data.getArtist(),
                    data.getApp());
        } else {
            contentDescription = null;
        }
        mMediaViewHolder.getPlayer().setContentDescription(contentDescription);
    }

    private void bindRecommendationContentDescription(SmartspaceMediaData data) {
        if (mRecommendationViewHolder == null) {
            return;
        }

       CharSequence contentDescription;
        if (mMediaViewController.isGutsVisible()) {
            contentDescription =
                    mRecommendationViewHolder.getGutsViewHolder().getGutsText().getText();
        } else if (data != null) {
            contentDescription = mContext.getString(
                    R.string.controls_media_smartspace_rec_description,
                    data.getAppName(mContext));
        } else {
            contentDescription = null;
        }

        mRecommendationViewHolder.getRecommendations().setContentDescription(contentDescription);
    }

    private void bindArtworkAndColors(MediaData data, String key, boolean updateBackground) {
        final int traceCookie = data.hashCode();
        final String traceName = "MediaControlPanel#bindArtworkAndColors<" + key + ">";
        Trace.beginAsyncSection(traceName, traceCookie);

        final int reqId = mArtworkNextBindRequestId++;
        if (updateBackground) {
            mIsArtworkBound = false;
        }

        // Capture width & height from views in foreground for artwork scaling in background
        int width = mMediaViewHolder.getAlbumView().getMeasuredWidth();
        int height = mMediaViewHolder.getAlbumView().getMeasuredHeight();

        // WallpaperColors.fromBitmap takes a good amount of time. We do that work
        // on the background executor to avoid stalling animations on the UI Thread.
        mBackgroundExecutor.execute(() -> {
            // Album art
            ColorScheme mutableColorScheme = null;
            Drawable artwork;
            boolean isArtworkBound;
            Icon artworkIcon = data.getArtwork();
            if (artworkIcon != null) {
                WallpaperColors wallpaperColors = WallpaperColors
                        .fromBitmap(artworkIcon.getBitmap());
                mutableColorScheme = new ColorScheme(wallpaperColors, true, Style.CONTENT);
                artwork = getScaledBackground(artworkIcon, width, height);
                isArtworkBound = true;
            } else {
                // If there's no artwork, use colors from the app icon
                artwork = new ColorDrawable(Color.TRANSPARENT);
                isArtworkBound = false;
                try {
                    Drawable icon = mContext.getPackageManager()
                            .getApplicationIcon(data.getPackageName());
                    mutableColorScheme = new ColorScheme(WallpaperColors.fromDrawable(icon), true,
                            Style.CONTENT);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Cannot find icon for package " + data.getPackageName(), e);
                }
            }

            final ColorScheme colorScheme = mutableColorScheme;
            mMainExecutor.execute(() -> {
                // Cancel the request if a later one arrived first
                if (reqId < mArtworkBoundId) {
                    Trace.endAsyncSection(traceName, traceCookie);
                    return;
                }
                mArtworkBoundId = reqId;

                // Bind the album view to the artwork or a transition drawable
                ImageView albumView = mMediaViewHolder.getAlbumView();
                albumView.setPadding(0, 0, 0, 0);
                if (updateBackground || (!mIsArtworkBound && isArtworkBound)) {
                    if (mPrevArtwork == null) {
                        albumView.setImageDrawable(artwork);
                    } else {
                        // Since we throw away the last transition, this'll pop if you backgrounds
                        // are cycled too fast (or the correct background arrives very soon after
                        // the metadata changes).
                        TransitionDrawable transitionDrawable = new TransitionDrawable(
                                new Drawable[]{mPrevArtwork, artwork});

                        scaleTransitionDrawableLayer(transitionDrawable, 0, width, height);
                        scaleTransitionDrawableLayer(transitionDrawable, 1, width, height);
                        transitionDrawable.setLayerGravity(0, Gravity.CENTER);
                        transitionDrawable.setLayerGravity(1, Gravity.CENTER);
                        transitionDrawable.setCrossFadeEnabled(!isArtworkBound);

                        albumView.setImageDrawable(transitionDrawable);
                        transitionDrawable.startTransition(isArtworkBound ? 333 : 80);
                    }
                    mPrevArtwork = artwork;
                    mIsArtworkBound = isArtworkBound;
                }

                // Transition Colors to current color scheme
                mColorSchemeTransition.updateColorScheme(colorScheme, mIsArtworkBound);

                // App icon - use notification icon
                ImageView appIconView = mMediaViewHolder.getAppIcon();
                appIconView.clearColorFilter();
                if (data.getAppIcon() != null && !data.getResumption()) {
                    appIconView.setImageIcon(data.getAppIcon());
                    appIconView.setColorFilter(
                            mColorSchemeTransition.getAccentPrimary().getTargetColor());
                } else {
                    // Resume players use launcher icon
                    appIconView.setColorFilter(getGrayscaleFilter());
                    try {
                        Drawable icon = mContext.getPackageManager()
                                .getApplicationIcon(data.getPackageName());
                        appIconView.setImageDrawable(icon);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "Cannot find icon for package " + data.getPackageName(), e);
                        appIconView.setImageResource(R.drawable.ic_music_note);
                    }
                }
                Trace.endAsyncSection(traceName, traceCookie);
            });
        });
    }

    private void scaleTransitionDrawableLayer(TransitionDrawable transitionDrawable, int layer,
            int targetWidth, int targetHeight) {
        Drawable drawable = transitionDrawable.getDrawable(layer);
        if (drawable == null) {
            return;
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width == 0 || height == 0 || targetWidth == 0 || targetHeight == 0) {
            return;
        }

        float scale;
        if ((width / (float) height) > (targetWidth / (float) targetHeight)) {
            // Drawable is wider than target view, scale to match height
            scale = targetHeight / (float) height;
        } else {
            // Drawable is taller than target view, scale to match width
            scale = targetWidth / (float) width;
        }
        transitionDrawable.setLayerSize(layer, (int) (scale * width), (int) (scale * height));
    }

    private void bindActionButtons(MediaData data) {
        MediaButton semanticActions = data.getSemanticActions();

        List<ImageButton> genericButtons = new ArrayList<>();
        for (int id : MediaViewHolder.Companion.getGenericButtonIds()) {
            genericButtons.add(mMediaViewHolder.getAction(id));
        }

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        if (semanticActions != null) {
            // Hide all the generic buttons
            for (ImageButton b: genericButtons) {
                setVisibleAndAlpha(collapsedSet, b.getId(), false);
                setVisibleAndAlpha(expandedSet, b.getId(), false);
            }

            for (int id : SEMANTIC_ACTIONS_ALL) {
                ImageButton button = mMediaViewHolder.getAction(id);
                MediaAction action = semanticActions.getActionById(id);
                setSemanticButton(button, action, semanticActions);
            }
        } else {
            // Hide buttons that only appear for semantic actions
            for (int id : SEMANTIC_ACTIONS_COMPACT) {
                setVisibleAndAlpha(collapsedSet, id, false);
                setVisibleAndAlpha(expandedSet, id, false);
            }

            // Set all the generic buttons
            List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
            List<MediaAction> actions = data.getActions();
            int i = 0;
            for (; i < actions.size() && i < genericButtons.size(); i++) {
                boolean showInCompact = actionsWhenCollapsed.contains(i);
                setGenericButton(
                        genericButtons.get(i),
                        actions.get(i),
                        collapsedSet,
                        expandedSet,
                        showInCompact);
            }
            for (; i < genericButtons.size(); i++) {
                // Hide any unused buttons
                setGenericButton(
                        genericButtons.get(i),
                        /* mediaAction= */ null,
                        collapsedSet,
                        expandedSet,
                        /* showInCompact= */ false);
            }
        }

        updateSeekBarVisibility();
    }

    private void updateSeekBarVisibility() {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        expandedSet.setVisibility(R.id.media_progress_bar, getSeekBarVisibility());
        expandedSet.setAlpha(R.id.media_progress_bar, mIsSeekBarEnabled ? 1.0f : 0.0f);
    }

    private int getSeekBarVisibility() {
        if (mIsSeekBarEnabled) {
            return ConstraintSet.VISIBLE;
        }
        // If disabled and "neighbours" are visible, set progress bar to INVISIBLE instead of GONE
        // so layout weights still work.
        return areAnyExpandedBottomActionsVisible() ? ConstraintSet.INVISIBLE : ConstraintSet.GONE;
    }

    private boolean areAnyExpandedBottomActionsVisible() {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        for (int id : MediaViewHolder.Companion.getExpandedBottomActionIds()) {
            if (expandedSet.getVisibility(id) == ConstraintSet.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private void setGenericButton(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            ConstraintSet collapsedSet,
            ConstraintSet expandedSet,
            boolean showInCompact) {
        bindButtonCommon(button, mediaAction);
        boolean visible = mediaAction != null;
        setVisibleAndAlpha(expandedSet, button.getId(), visible);
        setVisibleAndAlpha(collapsedSet, button.getId(), visible && showInCompact);
    }

    private void setSemanticButton(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            MediaButton semanticActions) {
        AnimationBindHandler animHandler;
        if (button.getTag() == null) {
            animHandler = new AnimationBindHandler();
            button.setTag(animHandler);
        } else {
            animHandler = (AnimationBindHandler) button.getTag();
        }

        animHandler.tryExecute(() -> {
            bindButtonWithAnimations(button, mediaAction, animHandler);
            setSemanticButtonVisibleAndAlpha(button.getId(), mediaAction, semanticActions);
            return Unit.INSTANCE;
        });
    }

    private void bindButtonWithAnimations(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            @NonNull AnimationBindHandler animHandler) {
        if (mediaAction != null) {
            if (animHandler.updateRebindId(mediaAction.getRebindId())) {
                animHandler.unregisterAll();
                animHandler.tryRegister(mediaAction.getIcon());
                animHandler.tryRegister(mediaAction.getBackground());
                bindButtonCommon(button, mediaAction);
            }
        } else {
            animHandler.unregisterAll();
            clearButton(button);
        }
    }

    private void bindButtonCommon(final ImageButton button, @Nullable MediaAction mediaAction) {
        if (mediaAction != null) {
            final Drawable icon = mediaAction.getIcon();
            button.setImageDrawable(icon);
            button.setContentDescription(mediaAction.getContentDescription());
            final Drawable bgDrawable = mediaAction.getBackground();
            button.setBackground(bgDrawable);

            Runnable action = mediaAction.getAction();
            if (action == null) {
                button.setEnabled(false);
            } else {
                button.setEnabled(true);
                button.setOnClickListener(v -> {
                    if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        mLogger.logTapAction(button.getId(), mUid, mPackageName, mInstanceId);
                        logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT);
                        action.run();

                        if (icon instanceof Animatable) {
                            ((Animatable) icon).start();
                        }
                        if (bgDrawable instanceof Animatable) {
                            ((Animatable) bgDrawable).start();
                        }
                    }
                });
            }
        } else {
            clearButton(button);
        }
    }

    private void clearButton(final ImageButton button) {
        button.setImageDrawable(null);
        button.setContentDescription(null);
        button.setEnabled(false);
        button.setBackground(null);
    }

    private void setSemanticButtonVisibleAndAlpha(
            int buttonId,
            @Nullable MediaAction mediaAction,
            MediaButton semanticActions) {
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        boolean showInCompact = SEMANTIC_ACTIONS_COMPACT.contains(buttonId);
        boolean hideWhenScrubbing = SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.contains(buttonId);
        boolean shouldBeHiddenDueToScrubbing =
                scrubbingTimeViewsEnabled(semanticActions) && hideWhenScrubbing && mIsScrubbing;
        boolean visible = mediaAction != null && !shouldBeHiddenDueToScrubbing;

        int notVisibleValue;
        if ((buttonId == R.id.actionPrev && semanticActions.getReservePrev())
                || (buttonId == R.id.actionNext && semanticActions.getReserveNext())) {
            notVisibleValue = ConstraintSet.INVISIBLE;
        } else {
            notVisibleValue = ConstraintSet.GONE;
        }
        setVisibleAndAlpha(expandedSet, buttonId, visible, notVisibleValue);
        setVisibleAndAlpha(collapsedSet, buttonId, visible && showInCompact);
    }

    /** Updates all the views that might change due to a scrubbing state change. */
    private void updateDisplayForScrubbingChange(@NonNull MediaButton semanticActions) {
        // Update visibilities of the scrubbing time views and the scrubbing-dependent buttons.
        bindScrubbingTime(mMediaData);
        SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.forEach((id) -> setSemanticButtonVisibleAndAlpha(
                id, semanticActions.getActionById(id), semanticActions));
        if (!mMetadataAnimationHandler.isRunning()) {
            // Trigger a state refresh so that we immediately update visibilities.
            mMediaViewController.refreshState();
        }
    }

    private void bindScrubbingTime(MediaData data) {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        int elapsedTimeId = mMediaViewHolder.getScrubbingElapsedTimeView().getId();
        int totalTimeId = mMediaViewHolder.getScrubbingTotalTimeView().getId();

        boolean visible = scrubbingTimeViewsEnabled(data.getSemanticActions()) && mIsScrubbing;
        setVisibleAndAlpha(expandedSet, elapsedTimeId, visible);
        setVisibleAndAlpha(expandedSet, totalTimeId, visible);
        // Never show in collapsed
        setVisibleAndAlpha(collapsedSet, elapsedTimeId, false);
        setVisibleAndAlpha(collapsedSet, totalTimeId, false);
    }

    private boolean scrubbingTimeViewsEnabled(@Nullable MediaButton semanticActions) {
        // The scrubbing time views replace the SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING action views,
        // so we should only allow scrubbing times to be shown if those action views are present.
        return semanticActions != null && SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.stream().allMatch(
                id -> semanticActions.getActionById(id) != null
        );
    }

    @Nullable
    private ActivityLaunchAnimator.Controller buildLaunchAnimatorController(
            TransitionLayout player) {
        if (!(player.getParent() instanceof ViewGroup)) {
            // TODO(b/192194319): Throw instead of just logging.
            Log.wtf(TAG, "Skipping player animation as it is not attached to a ViewGroup",
                    new Exception());
            return null;
        }

        // TODO(b/174236650): Make sure that the carousel indicator also fades out.
        // TODO(b/174236650): Instrument the animation to measure jank.
        return new GhostedViewLaunchAnimatorController(player,
                InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER) {
            @Override
            protected float getCurrentTopCornerRadius() {
                return mContext.getResources().getDimension(R.dimen.notification_corner_radius);
            }

            @Override
            protected float getCurrentBottomCornerRadius() {
                // TODO(b/184121838): Make IlluminationDrawable support top and bottom radius.
                return getCurrentTopCornerRadius();
            }
        };
    }

    /** Bind this recommendation view based on the given data. */
    public void bindRecommendation(@NonNull SmartspaceMediaData data) {
        if (mRecommendationViewHolder == null) {
            return;
        }

        if (!data.isValid()) {
            Log.e(TAG, "Received an invalid recommendation list; returning");
            return;
        }

        Trace.beginSection(
                "MediaControlPanel#bindRecommendation<" + data.getPackageName() + ">");

        mRecommendationData = data;
        mSmartspaceId = SmallHash.hash(data.getTargetId());
        mPackageName = data.getPackageName();
        mInstanceId = data.getInstanceId();

        // Set up recommendation card's header.
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = mContext.getPackageManager()
                    .getApplicationInfo(data.getPackageName(), 0 /* flags */);
            mUid = applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Fail to get media recommendation's app info", e);
            Trace.endSection();
            return;
        }

        CharSequence appName = data.getAppName(mContext);
        if (appName == null) {
            Log.w(TAG, "Fail to get media recommendation's app name");
            Trace.endSection();
            return;
        }

        PackageManager packageManager = mContext.getPackageManager();
        // Set up media source app's logo.
        Drawable icon = packageManager.getApplicationIcon(applicationInfo);
        ImageView headerLogoImageView = mRecommendationViewHolder.getCardIcon();
        headerLogoImageView.setImageDrawable(icon);
        fetchAndUpdateRecommendationColors(icon);

        // Set up media rec card's tap action if applicable.
        TransitionLayout recommendationCard = mRecommendationViewHolder.getRecommendations();
        setSmartspaceRecItemOnClickListener(recommendationCard, data.getCardAction(),
                /* interactedSubcardRank */ -1);
        bindRecommendationContentDescription(data);

        List<ImageView> mediaCoverItems = mRecommendationViewHolder.getMediaCoverItems();
        List<ViewGroup> mediaCoverContainers = mRecommendationViewHolder.getMediaCoverContainers();
        List<SmartspaceAction> recommendations = data.getValidRecommendations();

        boolean hasTitle = false;
        boolean hasSubtitle = false;
        for (int itemIndex = 0; itemIndex < NUM_REQUIRED_RECOMMENDATIONS; itemIndex++) {
            SmartspaceAction recommendation = recommendations.get(itemIndex);

            // Set up media item cover.
            ImageView mediaCoverImageView = mediaCoverItems.get(itemIndex);
            mediaCoverImageView.setImageIcon(recommendation.getIcon());

            // Set up the media item's click listener if applicable.
            ViewGroup mediaCoverContainer = mediaCoverContainers.get(itemIndex);
            setSmartspaceRecItemOnClickListener(mediaCoverContainer, recommendation, itemIndex);
            // Bubble up the long-click event to the card.
            mediaCoverContainer.setOnLongClickListener(v -> {
                View parent = (View) v.getParent();
                if (parent != null) {
                    parent.performLongClick();
                }
                return true;
            });

            // Set up the accessibility label for the media item.
            String artistName = recommendation.getExtras()
                    .getString(KEY_SMARTSPACE_ARTIST_NAME, "");
            if (artistName.isEmpty()) {
                mediaCoverImageView.setContentDescription(
                        mContext.getString(
                                R.string.controls_media_smartspace_rec_item_no_artist_description,
                                recommendation.getTitle(), appName));
            } else {
                mediaCoverImageView.setContentDescription(
                        mContext.getString(
                                R.string.controls_media_smartspace_rec_item_description,
                                recommendation.getTitle(), artistName, appName));
            }


            // Set up title
            CharSequence title = recommendation.getTitle();
            hasTitle |= !TextUtils.isEmpty(title);
            TextView titleView = mRecommendationViewHolder.getMediaTitles().get(itemIndex);
            titleView.setText(title);

            // Set up subtitle
            // It would look awkward to show a subtitle if we don't have a title.
            boolean shouldShowSubtitleText = !TextUtils.isEmpty(title);
            CharSequence subtitle = shouldShowSubtitleText ? recommendation.getSubtitle() : "";
            hasSubtitle |= !TextUtils.isEmpty(subtitle);
            TextView subtitleView = mRecommendationViewHolder.getMediaSubtitles().get(itemIndex);
            subtitleView.setText(subtitle);
        }
        mSmartspaceMediaItemsCount = NUM_REQUIRED_RECOMMENDATIONS;

        // If there's no subtitles and/or titles for any of the albums, hide those views.
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        final boolean titlesVisible = hasTitle;
        final boolean subtitlesVisible = hasSubtitle;
        mRecommendationViewHolder.getMediaTitles().forEach((titleView) ->
                setVisibleAndAlpha(expandedSet, titleView.getId(), titlesVisible));
        mRecommendationViewHolder.getMediaSubtitles().forEach((subtitleView) ->
                setVisibleAndAlpha(expandedSet, subtitleView.getId(), subtitlesVisible));

        // Guts
        Runnable onDismissClickedRunnable = () -> {
            closeGuts();
            mMediaDataManagerLazy.get().dismissSmartspaceRecommendation(
                    data.getTargetId(), MediaViewController.GUTS_ANIMATION_DURATION + 100L);

            Intent dismissIntent = data.getDismissIntent();
            if (dismissIntent == null) {
                Log.w(TAG, "Cannot create dismiss action click action: "
                        + "extras missing dismiss_intent.");
                return;
            }

            if (dismissIntent.getComponent() != null
                    && dismissIntent.getComponent().getClassName()
                    .equals(EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME)) {
                // Dismiss the card Smartspace data through Smartspace trampoline activity.
                mContext.startActivity(dismissIntent);
            } else {
                mBroadcastSender.sendBroadcast(dismissIntent);
            }
        };
        bindGutsMenuCommon(
                /* isDismissible= */ true,
                appName.toString(),
                mRecommendationViewHolder.getGutsViewHolder(),
                onDismissClickedRunnable);

        mController = null;
        if (mMetadataAnimationHandler == null || !mMetadataAnimationHandler.isRunning()) {
            mMediaViewController.refreshState();
        }
        Trace.endSection();
    }

    private void fetchAndUpdateRecommendationColors(Drawable appIcon) {
        mBackgroundExecutor.execute(() -> {
            ColorScheme colorScheme = new ColorScheme(
                    WallpaperColors.fromDrawable(appIcon), /* darkTheme= */ true);
            mMainExecutor.execute(() -> setRecommendationColors(colorScheme));
        });
    }

    private void setRecommendationColors(ColorScheme colorScheme) {
        if (mRecommendationViewHolder == null) {
            return;
        }

        int backgroundColor = MediaColorSchemesKt.surfaceFromScheme(colorScheme);
        int textPrimaryColor = MediaColorSchemesKt.textPrimaryFromScheme(colorScheme);
        int textSecondaryColor = MediaColorSchemesKt.textSecondaryFromScheme(colorScheme);

        mRecommendationViewHolder.getRecommendations()
                .setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        mRecommendationViewHolder.getMediaTitles().forEach(
                (title) -> title.setTextColor(textPrimaryColor));
        mRecommendationViewHolder.getMediaSubtitles().forEach(
                (subtitle) -> subtitle.setTextColor(textSecondaryColor));

        mRecommendationViewHolder.getGutsViewHolder().setColors(colorScheme);
    }

    private void bindGutsMenuCommon(
            boolean isDismissible,
            String appName,
            GutsViewHolder gutsViewHolder,
            Runnable onDismissClickedRunnable) {
        // Text
        String text;
        if (isDismissible) {
            text = mContext.getString(R.string.controls_media_close_session, appName);
        } else {
            text = mContext.getString(R.string.controls_media_active_session);
        }
        gutsViewHolder.getGutsText().setText(text);

        // Dismiss button
        gutsViewHolder.getDismissText().setVisibility(isDismissible ? View.VISIBLE : View.GONE);
        gutsViewHolder.getDismiss().setEnabled(isDismissible);
        gutsViewHolder.getDismiss().setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;
            logSmartspaceCardReported(SMARTSPACE_CARD_DISMISS_EVENT);
            mLogger.logLongPressDismiss(mUid, mPackageName, mInstanceId);

            onDismissClickedRunnable.run();
        });

        // Cancel button
        TextView cancelText = gutsViewHolder.getCancelText();
        if (isDismissible) {
            cancelText.setBackground(mContext.getDrawable(R.drawable.qs_media_outline_button));
        } else {
            cancelText.setBackground(mContext.getDrawable(R.drawable.qs_media_solid_button));
        }
        gutsViewHolder.getCancel().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                closeGuts();
            }
        });
        gutsViewHolder.setDismissible(isDismissible);

        // Settings button
        gutsViewHolder.getSettings().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                mLogger.logLongPressSettings(mUid, mPackageName, mInstanceId);
                mActivityStarter.startActivity(SETTINGS_INTENT, /* dismissShade= */true);
            }
        });
    }

    /**
     * Close the guts for this player.
     *
     * @param immediate {@code true} if it should be closed without animation
     */
    public void closeGuts(boolean immediate) {
        if (mMediaViewHolder != null) {
            mMediaViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        } else if (mRecommendationViewHolder != null) {
            mRecommendationViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.closeGuts(immediate);
        if (mMediaViewHolder != null) {
            bindPlayerContentDescription(mMediaData);
        } else if (mRecommendationViewHolder != null) {
            bindRecommendationContentDescription(mRecommendationData);
        }
    }

    private void closeGuts() {
        closeGuts(false);
    }

    private void openGuts() {
        if (mMediaViewHolder != null) {
            mMediaViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
        } else if (mRecommendationViewHolder != null) {
            mRecommendationViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.openGuts();
        if (mMediaViewHolder != null) {
            bindPlayerContentDescription(mMediaData);
        } else if (mRecommendationViewHolder != null) {
            bindRecommendationContentDescription(mRecommendationData);
        }
        mLogger.logLongPressOpen(mUid, mPackageName, mInstanceId);
    }

    /**
     * Scale artwork to fill the background of the panel
     */
    @UiThread
    private Drawable getScaledBackground(Icon icon, int width, int height) {
        if (icon == null) {
            return null;
        }
        Drawable drawable = icon.loadDrawable(mContext);
        Rect bounds = new Rect(0, 0, width, height);
        if (bounds.width() > width || bounds.height() > height) {
            float offsetX = (bounds.width() - width) / 2.0f;
            float offsetY = (bounds.height() - height) / 2.0f;
            bounds.offset((int) -offsetX, (int) -offsetY);
        }
        drawable.setBounds(bounds);
        return drawable;
    }

    /**
     * Get the current media controller
     *
     * @return the controller
     */
    public MediaController getController() {
        return mController;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     *
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        return isPlaying(mController);
    }

    /**
     * Check whether the given controller is currently playing
     *
     * @param controller media controller to check
     * @return whether it is playing, or false if no controller information
     */
    protected boolean isPlaying(MediaController controller) {
        if (controller == null) {
            return false;
        }

        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    private ColorMatrixColorFilter getGrayscaleFilter() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        return new ColorMatrixColorFilter(matrix);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible) {
        setVisibleAndAlpha(set, actionId, visible, ConstraintSet.GONE);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible,
            int notVisibleValue) {
        set.setVisibility(actionId, visible ? ConstraintSet.VISIBLE : notVisibleValue);
        set.setAlpha(actionId, visible ? 1.0f : 0.0f);
    }

    private void setSmartspaceRecItemOnClickListener(
            @NonNull View view,
            @NonNull SmartspaceAction action,
            int interactedSubcardRank) {
        if (view == null || action == null || action.getIntent() == null
                || action.getIntent().getExtras() == null) {
            Log.e(TAG, "No tap action can be set up");
            return;
        }

        view.setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;

            if (interactedSubcardRank == -1) {
                mLogger.logRecommendationCardTap(mPackageName, mInstanceId);
            } else {
                mLogger.logRecommendationItemTap(mPackageName, mInstanceId, interactedSubcardRank);
            }
            logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT,
                    interactedSubcardRank,
                    mSmartspaceMediaItemsCount);

            if (shouldSmartspaceRecItemOpenInForeground(action)) {
                // Request to unlock the device if the activity needs to be opened in foreground.
                mActivityStarter.postStartActivityDismissingKeyguard(
                        action.getIntent(),
                        0 /* delay */,
                        buildLaunchAnimatorController(
                                mRecommendationViewHolder.getRecommendations()));
            } else {
                // Otherwise, open the activity in background directly.
                view.getContext().startActivity(action.getIntent());
            }

            // Automatically scroll to the active player once the media is loaded.
            mMediaCarouselController.setShouldScrollToActivePlayer(true);
        });
    }

    /** Returns if the Smartspace action will open the activity in foreground. */
    private boolean shouldSmartspaceRecItemOpenInForeground(SmartspaceAction action) {
        if (action == null || action.getIntent() == null
                || action.getIntent().getExtras() == null) {
            return false;
        }

        String intentString = action.getIntent().getExtras().getString(EXTRAS_SMARTSPACE_INTENT);
        if (intentString == null) {
            return false;
        }

        try {
            Intent wrapperIntent = Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME);
            return wrapperIntent.getBooleanExtra(KEY_SMARTSPACE_OPEN_IN_FOREGROUND, false);
        } catch (URISyntaxException e) {
            Log.wtf(TAG, "Failed to create intent from URI: " + intentString);
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Get the surface given the current end location for MediaViewController
     * @return surface used for Smartspace logging
     */
    protected int getSurfaceForSmartspaceLogging() {
        int currentEndLocation = mMediaViewController.getCurrentEndLocation();
        if (currentEndLocation == MediaHierarchyManager.LOCATION_QQS
                || currentEndLocation == MediaHierarchyManager.LOCATION_QS) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE;
        } else if (currentEndLocation == MediaHierarchyManager.LOCATION_LOCKSCREEN) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN;
        } else if (currentEndLocation == MediaHierarchyManager.LOCATION_DREAM_OVERLAY) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DREAM_OVERLAY;
        }
        return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DEFAULT_SURFACE;
    }

    private void logSmartspaceCardReported(int eventId) {
        logSmartspaceCardReported(eventId,
                /* interactedSubcardRank */ 0,
                /* interactedSubcardCardinality */ 0);
    }

    private void logSmartspaceCardReported(int eventId,
            int interactedSubcardRank, int interactedSubcardCardinality) {
        mMediaCarouselController.logSmartspaceCardReported(eventId,
                mSmartspaceId,
                mUid,
                new int[]{getSurfaceForSmartspaceLogging()},
                interactedSubcardRank,
                interactedSubcardCardinality);
    }
}
