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

package com.android.widget;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.DataSourceDesc;
import android.media.MediaMetadata;
import android.media.MediaPlayer2;
import android.media.MediaPlayer2.MediaPlayer2EventCallback;
import android.media.MediaPlayer2.OnSubtitleDataListener;
import android.media.MediaPlayer2Impl;
import android.media.SubtitleData;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaMetadataRetriever;
import android.media.Metadata;
import android.media.PlaybackParams;
import android.media.TimedText;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.SessionToken2;
import android.media.update.VideoView2Provider;
import android.media.update.ViewGroupProvider;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.MediaControlView2;
import android.widget.TextView;
import android.widget.VideoView2;

import com.android.internal.graphics.palette.Palette;
import com.android.media.RoutePlayer;
import com.android.media.subtitle.ClosedCaptionRenderer;
import com.android.media.subtitle.SubtitleController;
import com.android.media.subtitle.SubtitleTrack;
import com.android.media.update.ApiHelper;
import com.android.media.update.R;
import com.android.support.mediarouter.media.MediaItemStatus;
import com.android.support.mediarouter.media.MediaControlIntent;
import com.android.support.mediarouter.media.MediaRouter;
import com.android.support.mediarouter.media.MediaRouteSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class VideoView2Impl extends BaseLayout
        implements VideoView2Provider, VideoViewInterface.SurfaceListener {
    private static final String TAG = "VideoView2";
    private static final boolean DEBUG = true; // STOPSHIP: Log.isLoggable(TAG, Log.DEBUG);
    private static final long DEFAULT_SHOW_CONTROLLER_INTERVAL_MS = 2000;

    private final VideoView2 mInstance;

    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;

    private static final int INVALID_TRACK_INDEX = -1;
    private static final float INVALID_SPEED = 0f;

    private static final int SIZE_TYPE_EMBEDDED = 0;
    private static final int SIZE_TYPE_FULL = 1;
    // TODO: add support for Minimal size type.
    private static final int SIZE_TYPE_MINIMAL = 2;

    private AccessibilityManager mAccessibilityManager;
    private AudioManager mAudioManager;
    private AudioAttributes mAudioAttributes;
    private int mAudioFocusType = AudioManager.AUDIOFOCUS_GAIN; // legacy focus gain

    private Pair<Executor, VideoView2.OnCustomActionListener> mCustomActionListenerRecord;
    private VideoView2.OnViewTypeChangedListener mViewTypeChangedListener;
    private VideoView2.OnFullScreenRequestListener mFullScreenRequestListener;

    private VideoViewInterface mCurrentView;
    private VideoTextureView mTextureView;
    private VideoSurfaceView mSurfaceView;

    private MediaPlayer2 mMediaPlayer;
    private DataSourceDesc mDsd;
    private MediaControlView2 mMediaControlView;
    private MediaSession mMediaSession;
    private MediaController mMediaController;
    private Metadata mMetadata;
    private MediaMetadata2 mMediaMetadata;
    private MediaMetadataRetriever mRetriever;
    private boolean mNeedUpdateMediaType;
    private Bundle mMediaTypeData;
    private String mTitle;

    // TODO: move music view inside SurfaceView/TextureView or implement VideoViewInterface.
    private WindowManager mManager;
    private Resources mResources;
    private View mMusicView;
    private Drawable mMusicAlbumDrawable;
    private String mMusicTitleText;
    private String mMusicArtistText;
    private boolean mIsMusicMediaType;
    private int mPrevWidth;
    private int mPrevHeight;
    private int mDominantColor;
    private int mSizeType;

    private PlaybackState.Builder mStateBuilder;
    private List<PlaybackState.CustomAction> mCustomActionList;
    private int mTargetState = STATE_IDLE;
    private int mCurrentState = STATE_IDLE;
    private int mCurrentBufferPercentage;
    private long mSeekWhenPrepared;  // recording the seek position while preparing

    private int mVideoWidth;
    private int mVideoHeight;

    private ArrayList<Integer> mVideoTrackIndices;
    private ArrayList<Integer> mAudioTrackIndices;
    private ArrayList<Pair<Integer, SubtitleTrack>> mSubtitleTrackIndices;
    private SubtitleController mSubtitleController;

    // selected video/audio/subtitle track index as MediaPlayer2 returns
    private int mSelectedVideoTrackIndex;
    private int mSelectedAudioTrackIndex;
    private int mSelectedSubtitleTrackIndex;

    private SubtitleView mSubtitleView;
    private boolean mSubtitleEnabled;

    private float mSpeed;
    // TODO: Remove mFallbackSpeed when integration with MediaPlayer2's new setPlaybackParams().
    // Refer: https://docs.google.com/document/d/1nzAfns6i2hJ3RkaUre3QMT6wsDedJ5ONLiA_OOBFFX8/edit
    private float mFallbackSpeed;  // keep the original speed before 'pause' is called.
    private float mVolumeLevelFloat;
    private int mVolumeLevel;

    private long mShowControllerIntervalMs;

    private MediaRouter mMediaRouter;
    private MediaRouteSelector mRouteSelector;
    private MediaRouter.RouteInfo mRoute;
    private RoutePlayer mRoutePlayer;

    private final MediaRouter.Callback mRouterCallback = new MediaRouter.Callback() {
        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo route) {
            if (route.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)) {
                // Stop local playback (if necessary)
                resetPlayer();
                mRoute = route;
                mRoutePlayer = new RoutePlayer(mInstance.getContext(), route);
                mRoutePlayer.setPlayerEventCallback(new RoutePlayer.PlayerEventCallback() {
                    @Override
                    public void onPlayerStateChanged(MediaItemStatus itemStatus) {
                        PlaybackState.Builder psBuilder = new PlaybackState.Builder();
                        psBuilder.setActions(RoutePlayer.PLAYBACK_ACTIONS);
                        long position = itemStatus.getContentPosition();
                        switch (itemStatus.getPlaybackState()) {
                            case MediaItemStatus.PLAYBACK_STATE_PENDING:
                                psBuilder.setState(PlaybackState.STATE_NONE, position, 0);
                                mCurrentState = STATE_IDLE;
                                break;
                            case MediaItemStatus.PLAYBACK_STATE_PLAYING:
                                psBuilder.setState(PlaybackState.STATE_PLAYING, position, 1);
                                mCurrentState = STATE_PLAYING;
                                break;
                            case MediaItemStatus.PLAYBACK_STATE_PAUSED:
                                psBuilder.setState(PlaybackState.STATE_PAUSED, position, 0);
                                mCurrentState = STATE_PAUSED;
                                break;
                            case MediaItemStatus.PLAYBACK_STATE_BUFFERING:
                                psBuilder.setState(PlaybackState.STATE_BUFFERING, position, 0);
                                mCurrentState = STATE_PAUSED;
                                break;
                            case MediaItemStatus.PLAYBACK_STATE_FINISHED:
                                psBuilder.setState(PlaybackState.STATE_STOPPED, position, 0);
                                mCurrentState = STATE_PLAYBACK_COMPLETED;
                                break;
                        }

                        PlaybackState pbState = psBuilder.build();
                        mMediaSession.setPlaybackState(pbState);

                        MediaMetadata.Builder mmBuilder = new MediaMetadata.Builder();
                        mmBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION,
                                itemStatus.getContentDuration());
                        mMediaSession.setMetadata(mmBuilder.build());
                    }
                });
                // Start remote playback (if necessary)
                mRoutePlayer.openVideo(mDsd);
            }
        }

        @Override
        public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo route, int reason) {
            if (mRoute != null && mRoutePlayer != null) {
                mRoutePlayer.release();
                mRoutePlayer = null;
            }
            if (mRoute == route) {
                mRoute = null;
            }
            if (reason != MediaRouter.UNSELECT_REASON_ROUTE_CHANGED) {
                // TODO: Resume local playback  (if necessary)
                openVideo(mDsd);
            }
        }
    };

    public VideoView2Impl(VideoView2 instance,
            ViewGroupProvider superProvider, ViewGroupProvider privateProvider) {
        super(instance, superProvider, privateProvider);
        mInstance = instance;
    }

    @Override
    public void initialize(@Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        mVideoWidth = 0;
        mVideoHeight = 0;
        mSpeed = 1.0f;
        mFallbackSpeed = mSpeed;
        mSelectedSubtitleTrackIndex = INVALID_TRACK_INDEX;
        // TODO: add attributes to get this value.
        mShowControllerIntervalMs = DEFAULT_SHOW_CONTROLLER_INTERVAL_MS;

        mAccessibilityManager = AccessibilityManager.getInstance(mInstance.getContext());

        mAudioManager = (AudioManager) mInstance.getContext()
                .getSystemService(Context.AUDIO_SERVICE);
        mAudioAttributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        mInstance.setFocusable(true);
        mInstance.setFocusableInTouchMode(true);
        mInstance.requestFocus();

        // TODO: try to keep a single child at a time rather than always having both.
        mTextureView = new VideoTextureView(mInstance.getContext());
        mSurfaceView = new VideoSurfaceView(mInstance.getContext());
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        mTextureView.setLayoutParams(params);
        mSurfaceView.setLayoutParams(params);
        mTextureView.setSurfaceListener(this);
        mSurfaceView.setSurfaceListener(this);
        mInstance.addView(mTextureView);
        mInstance.addView(mSurfaceView);

        mSubtitleView = new SubtitleView(mInstance.getContext());
        mSubtitleView.setLayoutParams(params);
        mSubtitleView.setBackgroundColor(0);
        mInstance.addView(mSubtitleView);

        boolean enableControlView = (attrs == null) || attrs.getAttributeBooleanValue(
                "http://schemas.android.com/apk/res/android",
                "enableControlView", true);
        if (enableControlView) {
            mMediaControlView = new MediaControlView2(mInstance.getContext());
        }

        mSubtitleEnabled = (attrs == null) || attrs.getAttributeBooleanValue(
                "http://schemas.android.com/apk/res/android",
                "enableSubtitle", false);

        // TODO: Choose TextureView when SurfaceView cannot be created.
        // Choose surface view by default
        int viewType = (attrs == null) ? VideoView2.VIEW_TYPE_SURFACEVIEW
                : attrs.getAttributeIntValue(
                "http://schemas.android.com/apk/res/android",
                "viewType", VideoView2.VIEW_TYPE_SURFACEVIEW);
        if (viewType == VideoView2.VIEW_TYPE_SURFACEVIEW) {
            Log.d(TAG, "viewType attribute is surfaceView.");
            mTextureView.setVisibility(View.GONE);
            mSurfaceView.setVisibility(View.VISIBLE);
            mCurrentView = mSurfaceView;
        } else if (viewType == VideoView2.VIEW_TYPE_TEXTUREVIEW) {
            Log.d(TAG, "viewType attribute is textureView.");
            mTextureView.setVisibility(View.VISIBLE);
            mSurfaceView.setVisibility(View.GONE);
            mCurrentView = mTextureView;
        }

        MediaRouteSelector.Builder builder = new MediaRouteSelector.Builder();
        builder.addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        builder.addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO);
        builder.addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO);
        mRouteSelector = builder.build();
    }

    @Override
    public void setMediaControlView2_impl(MediaControlView2 mediaControlView, long intervalMs) {
        mMediaControlView = mediaControlView;
        mShowControllerIntervalMs = intervalMs;
        // TODO: Call MediaControlView2.setRouteSelector only when cast availalbe.
        ((MediaControlView2Impl) mMediaControlView.getProvider()).setRouteSelector(mRouteSelector);

        if (mInstance.isAttachedToWindow()) {
            attachMediaControlView();
        }
    }

    @Override
    public MediaController getMediaController_impl() {
        if (mMediaSession == null) {
            throw new IllegalStateException("MediaSession instance is not available.");
        }
        return mMediaController;
    }

    @Override
    public SessionToken2 getMediaSessionToken_impl() {
        // TODO: implement this
        return null;
    }

    @Override
    public MediaControlView2 getMediaControlView2_impl() {
        return mMediaControlView;
    }

    @Override
    public MediaMetadata2 getMediaMetadata_impl() {
        return mMediaMetadata;
    }

    @Override
    public void setMediaMetadata_impl(MediaMetadata2 metadata) {
        // TODO: integrate this with MediaSession2#MediaItem2
        mMediaMetadata = metadata;

        // TODO: add support for handling website link
        mMediaTypeData = new Bundle();
        boolean isAd = metadata == null ?
                false : metadata.getLong(MediaMetadata2.METADATA_KEY_ADVERTISEMENT) != 0;
        mMediaTypeData.putBoolean(
                MediaControlView2Impl.KEY_STATE_IS_ADVERTISEMENT, isAd);

        if (mMediaSession != null) {
            mMediaSession.sendSessionEvent(
                    MediaControlView2Impl.EVENT_UPDATE_MEDIA_TYPE_STATUS, mMediaTypeData);
        } else {
            // Update later inside OnPreparedListener after MediaSession is initialized.
            mNeedUpdateMediaType = true;
        }
    }

    @Override
    public void setSubtitleEnabled_impl(boolean enable) {
        if (enable != mSubtitleEnabled) {
            selectOrDeselectSubtitle(enable);
        }
        mSubtitleEnabled = enable;
    }

    @Override
    public boolean isSubtitleEnabled_impl() {
        return mSubtitleEnabled;
    }

    // TODO: remove setSpeed_impl once MediaController2 is ready.
    @Override
    public void setSpeed_impl(float speed) {
        if (speed <= 0.0f) {
            Log.e(TAG, "Unsupported speed (" + speed + ") is ignored.");
            return;
        }
        mSpeed = speed;
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            applySpeed();
        }
        updatePlaybackState();
    }

    @Override
    public void setAudioFocusRequest_impl(int focusGain) {
        if (focusGain != AudioManager.AUDIOFOCUS_NONE
                && focusGain != AudioManager.AUDIOFOCUS_GAIN
                && focusGain != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                && focusGain != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                && focusGain != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) {
            throw new IllegalArgumentException("Illegal audio focus type " + focusGain);
        }
        mAudioFocusType = focusGain;
    }

    @Override
    public void setAudioAttributes_impl(AudioAttributes attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        mAudioAttributes = attributes;
    }

    @Override
    public void setVideoPath_impl(String path) {
        mInstance.setVideoUri(Uri.parse(path));
    }

    @Override
    public void setVideoUri_impl(Uri uri) {
        mInstance.setVideoUri(uri, null);
    }

    @Override
    public void setVideoUri_impl(Uri uri, Map<String, String> headers) {
        DataSourceDesc.Builder builder = new DataSourceDesc.Builder();
        builder.setDataSource(mInstance.getContext(), uri, headers, null);
        mInstance.setDataSource(builder.build());
    }

    @Override
    public void setMediaItem_impl(MediaItem2 mediaItem) {
        // TODO: implement this
    }

    @Override
    public void setDataSource_impl(DataSourceDesc dsd) {
        mDsd = dsd;
        mSeekWhenPrepared = 0;
        openVideo(dsd);
    }

    @Override
    public void setViewType_impl(int viewType) {
        if (viewType == mCurrentView.getViewType()) {
            return;
        }
        VideoViewInterface targetView;
        if (viewType == VideoView2.VIEW_TYPE_TEXTUREVIEW) {
            Log.d(TAG, "switching to TextureView");
            targetView = mTextureView;
        } else if (viewType == VideoView2.VIEW_TYPE_SURFACEVIEW) {
            Log.d(TAG, "switching to SurfaceView");
            targetView = mSurfaceView;
        } else {
            throw new IllegalArgumentException("Unknown view type: " + viewType);
        }
        ((View) targetView).setVisibility(View.VISIBLE);
        targetView.takeOver(mCurrentView);
        mInstance.requestLayout();
    }

    @Override
    public int getViewType_impl() {
        return mCurrentView.getViewType();
    }

    @Override
    public void setCustomActions_impl(
            List<PlaybackState.CustomAction> actionList,
            Executor executor, VideoView2.OnCustomActionListener listener) {
        mCustomActionList = actionList;
        mCustomActionListenerRecord = new Pair<>(executor, listener);

        // Create a new playback builder in order to clear existing the custom actions.
        mStateBuilder = null;
        updatePlaybackState();
    }

    @Override
    public void setOnViewTypeChangedListener_impl(VideoView2.OnViewTypeChangedListener l) {
        mViewTypeChangedListener = l;
    }

    @Override
    public void setFullScreenRequestListener_impl(VideoView2.OnFullScreenRequestListener l) {
        mFullScreenRequestListener = l;
    }

    @Override
    public void onAttachedToWindow_impl() {
        super.onAttachedToWindow_impl();

        // Create MediaSession
        mMediaSession = new MediaSession(mInstance.getContext(), "VideoView2MediaSession");
        mMediaSession.setCallback(new MediaSessionCallback());
        mMediaSession.setActive(true);
        mMediaController = mMediaSession.getController();
        mMediaRouter = MediaRouter.getInstance(mInstance.getContext());
        mMediaRouter.setMediaSession(mMediaSession);
        mMediaRouter.addCallback(mRouteSelector, mRouterCallback);
        attachMediaControlView();
        // TODO: remove this after moving MediaSession creating code inside initializing VideoView2
        if (mCurrentState == STATE_PREPARED) {
            extractTracks();
            extractMetadata();
            extractAudioMetadata();
            if (mNeedUpdateMediaType) {
                mMediaSession.sendSessionEvent(
                        MediaControlView2Impl.EVENT_UPDATE_MEDIA_TYPE_STATUS,
                        mMediaTypeData);
                mNeedUpdateMediaType = false;
            }
        }
    }

    @Override
    public void onDetachedFromWindow_impl() {
        super.onDetachedFromWindow_impl();

        mMediaSession.release();
        mMediaSession = null;
        mMediaController = null;
    }

    @Override
    public CharSequence getAccessibilityClassName_impl() {
        return VideoView2.class.getName();
    }

    @Override
    public boolean onTouchEvent_impl(MotionEvent ev) {
        if (DEBUG) {
            Log.d(TAG, "onTouchEvent(). mCurrentState=" + mCurrentState
                    + ", mTargetState=" + mTargetState);
        }
        if (ev.getAction() == MotionEvent.ACTION_UP && mMediaControlView != null) {
            if (!mIsMusicMediaType || mSizeType != SIZE_TYPE_FULL) {
                toggleMediaControlViewVisibility();
            }
        }

        return super.onTouchEvent_impl(ev);
    }

    @Override
    public boolean onTrackballEvent_impl(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP && mMediaControlView != null) {
            if (!mIsMusicMediaType || mSizeType != SIZE_TYPE_FULL) {
                toggleMediaControlViewVisibility();
            }
        }

        return super.onTrackballEvent_impl(ev);
    }

    @Override
    public boolean dispatchTouchEvent_impl(MotionEvent ev) {
        // TODO: Test touch event handling logic thoroughly and simplify the logic.
        return super.dispatchTouchEvent_impl(ev);
    }

    @Override
    public void onMeasure_impl(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure_impl(widthMeasureSpec, heightMeasureSpec);

        if (mIsMusicMediaType) {
            if (mPrevWidth != mInstance.getMeasuredWidth()
                    || mPrevHeight != mInstance.getMeasuredHeight()) {
                int currWidth = mInstance.getMeasuredWidth();
                int currHeight = mInstance.getMeasuredHeight();
                Point screenSize = new Point();
                mManager.getDefaultDisplay().getSize(screenSize);
                int screenWidth = screenSize.x;
                int screenHeight = screenSize.y;

                if (currWidth == screenWidth && currHeight == screenHeight) {
                    int orientation = retrieveOrientation();
                    if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        inflateMusicView(R.layout.full_landscape_music);
                    } else {
                        inflateMusicView(R.layout.full_portrait_music);
                    }

                    if (mSizeType != SIZE_TYPE_FULL) {
                        mSizeType = SIZE_TYPE_FULL;
                        // Remove existing mFadeOut callback
                        mMediaControlView.removeCallbacks(mFadeOut);
                        mMediaControlView.setVisibility(View.VISIBLE);
                    }
                } else {
                    if (mSizeType != SIZE_TYPE_EMBEDDED) {
                        mSizeType = SIZE_TYPE_EMBEDDED;
                        inflateMusicView(R.layout.embedded_music);
                        // Add new mFadeOut callback
                        mMediaControlView.postDelayed(mFadeOut, mShowControllerIntervalMs);
                    }
                }
                mPrevWidth = currWidth;
                mPrevHeight = currHeight;
            }
        }
    }

    ///////////////////////////////////////////////////
    // Implements VideoViewInterface.SurfaceListener
    ///////////////////////////////////////////////////

    @Override
    public void onSurfaceCreated(View view, int width, int height) {
        if (DEBUG) {
            Log.d(TAG, "onSurfaceCreated(). mCurrentState=" + mCurrentState
                    + ", mTargetState=" + mTargetState + ", width/height: " + width + "/" + height
                    + ", " + view.toString());
        }
        if (needToStart()) {
            mMediaController.getTransportControls().play();
        }
    }

    @Override
    public void onSurfaceDestroyed(View view) {
        if (DEBUG) {
            Log.d(TAG, "onSurfaceDestroyed(). mCurrentState=" + mCurrentState
                    + ", mTargetState=" + mTargetState + ", " + view.toString());
        }
    }

    @Override
    public void onSurfaceChanged(View view, int width, int height) {
        // TODO: Do we need to call requestLayout here?
        if (DEBUG) {
            Log.d(TAG, "onSurfaceChanged(). width/height: " + width + "/" + height
                    + ", " + view.toString());
        }
    }

    @Override
    public void onSurfaceTakeOverDone(VideoViewInterface view) {
        if (DEBUG) {
            Log.d(TAG, "onSurfaceTakeOverDone(). Now current view is: " + view);
        }
        mCurrentView = view;
        if (mViewTypeChangedListener != null) {
            mViewTypeChangedListener.onViewTypeChanged(mInstance, view.getViewType());
        }
        if (needToStart()) {
            mMediaController.getTransportControls().play();
        }
    }

    ///////////////////////////////////////////////////
    // Protected or private methods
    ///////////////////////////////////////////////////

    private void attachMediaControlView() {
        // Get MediaController from MediaSession and set it inside MediaControlView
        mMediaControlView.setController(mMediaSession.getController());

        LayoutParams params =
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        mInstance.addView(mMediaControlView, params);
    }

    private boolean isInPlaybackState() {
        return (mMediaPlayer != null || mRoutePlayer != null)
                && mCurrentState != STATE_ERROR
                && mCurrentState != STATE_IDLE
                && mCurrentState != STATE_PREPARING;
    }

    private boolean needToStart() {
        return (mMediaPlayer != null || mRoutePlayer != null)
                && mCurrentState != STATE_PLAYING
                && mTargetState == STATE_PLAYING;
    }

    // Creates a MediaPlayer2 instance and prepare playback.
    private void openVideo(DataSourceDesc dsd) {
        Uri uri = dsd.getUri();
        Map<String, String> headers = dsd.getUriHeaders();
        resetPlayer();
        if (isRemotePlayback()) {
            mRoutePlayer.openVideo(dsd);
            return;
        }
        if (mAudioFocusType != AudioManager.AUDIOFOCUS_NONE) {
            // TODO this should have a focus listener
            AudioFocusRequest focusRequest;
            focusRequest = new AudioFocusRequest.Builder(mAudioFocusType)
                    .setAudioAttributes(mAudioAttributes)
                    .build();
            mAudioManager.requestAudioFocus(focusRequest);
        }

        try {
            Log.d(TAG, "openVideo(): creating new MediaPlayer2 instance.");
            mMediaPlayer = new MediaPlayer2Impl();
            mSurfaceView.setMediaPlayer(mMediaPlayer);
            mTextureView.setMediaPlayer(mMediaPlayer);
            mCurrentView.assignSurfaceToMediaPlayer(mMediaPlayer);

            final Context context = mInstance.getContext();
            // TODO: Add timely firing logic for more accurate sync between CC and video frame
            mSubtitleController = new SubtitleController(context);
            mSubtitleController.registerRenderer(new ClosedCaptionRenderer(context));
            mSubtitleController.setAnchor((SubtitleController.Anchor) mSubtitleView);
            Executor executor = new Executor() {
                @Override
                public void execute(Runnable runnable) {
                    runnable.run();
                }
            };
            mMediaPlayer.setMediaPlayer2EventCallback(executor, mMediaPlayer2Callback);

            mCurrentBufferPercentage = -1;
            mMediaPlayer.setDataSource(dsd);
            mMediaPlayer.setAudioAttributes(mAudioAttributes);
            mMediaPlayer.setOnSubtitleDataListener(mSubtitleListener);
            // we don't set the target state here either, but preserve the
            // target state that was there before.
            mCurrentState = STATE_PREPARING;
            mMediaPlayer.prepare();

            // Save file name as title since the file may not have a title Metadata.
            mTitle = uri.getPath();
            String scheme = uri.getScheme();
            if (scheme != null && scheme.equals("file")) {
                mTitle = uri.getLastPathSegment();
            }
            mRetriever = new MediaMetadataRetriever();
            mRetriever.setDataSource(mInstance.getContext(), uri);

            if (DEBUG) {
                Log.d(TAG, "openVideo(). mCurrentState=" + mCurrentState
                        + ", mTargetState=" + mTargetState);
            }
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Unable to open content: " + uri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mMediaPlayer2Callback.onError(mMediaPlayer, dsd,
                    MediaPlayer2.MEDIA_ERROR_UNKNOWN, MediaPlayer2.MEDIA_ERROR_IO);
        }
    }

    /*
     * Reset the media player in any state
     */
    private void resetPlayer() {
        if (mMediaPlayer != null) {
            final MediaPlayer2 player = mMediaPlayer;
            new AsyncTask<MediaPlayer2, Void, Void>() {
                @Override
                protected Void doInBackground(MediaPlayer2... players) {
                    // TODO: Fix NPE while MediaPlayer2.close()
                    //players[0].close();
                    return null;
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, player);
            mMediaPlayer = null;
            mTextureView.setMediaPlayer(null);
            mSurfaceView.setMediaPlayer(null);
            //mPendingSubtitleTracks.clear();
            mCurrentState = STATE_IDLE;
            mTargetState = STATE_IDLE;
            if (mAudioFocusType != AudioManager.AUDIOFOCUS_NONE) {
                mAudioManager.abandonAudioFocus(null);
            }
        }
        mVideoWidth = 0;
        mVideoHeight = 0;
    }

    private void updatePlaybackState() {
        if (mStateBuilder == null) {
            // Get the capabilities of the player for this stream
            mMetadata = mMediaPlayer.getMetadata(MediaPlayer2.METADATA_ALL,
                    MediaPlayer2.BYPASS_METADATA_FILTER);

            // Add Play action as default
            long playbackActions = PlaybackState.ACTION_PLAY;
            if (mMetadata != null) {
                if (!mMetadata.has(Metadata.PAUSE_AVAILABLE)
                        || mMetadata.getBoolean(Metadata.PAUSE_AVAILABLE)) {
                    playbackActions |= PlaybackState.ACTION_PAUSE;
                }
                if (!mMetadata.has(Metadata.SEEK_BACKWARD_AVAILABLE)
                        || mMetadata.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE)) {
                    playbackActions |= PlaybackState.ACTION_REWIND;
                }
                if (!mMetadata.has(Metadata.SEEK_FORWARD_AVAILABLE)
                        || mMetadata.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE)) {
                    playbackActions |= PlaybackState.ACTION_FAST_FORWARD;
                }
                if (!mMetadata.has(Metadata.SEEK_AVAILABLE)
                        || mMetadata.getBoolean(Metadata.SEEK_AVAILABLE)) {
                    playbackActions |= PlaybackState.ACTION_SEEK_TO;
                }
            } else {
                playbackActions |= (PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD |
                        PlaybackState.ACTION_SEEK_TO);
            }
            mStateBuilder = new PlaybackState.Builder();
            mStateBuilder.setActions(playbackActions);

            if (mCustomActionList != null) {
                for (PlaybackState.CustomAction action : mCustomActionList) {
                    mStateBuilder.addCustomAction(action);
                }
            }
        }
        mStateBuilder.setState(getCorrespondingPlaybackState(),
                mMediaPlayer.getCurrentPosition(), mSpeed);
        if (mCurrentState != STATE_ERROR
            && mCurrentState != STATE_IDLE
            && mCurrentState != STATE_PREPARING) {
            // TODO: this should be replaced with MediaPlayer2.getBufferedPosition() once it is
            // implemented.
            if (mCurrentBufferPercentage == -1) {
                mStateBuilder.setBufferedPosition(-1);
            } else {
                mStateBuilder.setBufferedPosition(
                        (long) (mCurrentBufferPercentage / 100.0 * mMediaPlayer.getDuration()));
            }
        }

        // Set PlaybackState for MediaSession
        if (mMediaSession != null) {
            PlaybackState state = mStateBuilder.build();
            mMediaSession.setPlaybackState(state);
        }
    }

    private int getCorrespondingPlaybackState() {
        switch (mCurrentState) {
            case STATE_ERROR:
                return PlaybackState.STATE_ERROR;
            case STATE_IDLE:
                return PlaybackState.STATE_NONE;
            case STATE_PREPARING:
                return PlaybackState.STATE_CONNECTING;
            case STATE_PREPARED:
                return PlaybackState.STATE_PAUSED;
            case STATE_PLAYING:
                return PlaybackState.STATE_PLAYING;
            case STATE_PAUSED:
                return PlaybackState.STATE_PAUSED;
            case STATE_PLAYBACK_COMPLETED:
                return PlaybackState.STATE_STOPPED;
            default:
                return -1;
        }
    }

    private final Runnable mFadeOut = new Runnable() {
        @Override
        public void run() {
            if (mCurrentState == STATE_PLAYING) {
                mMediaControlView.setVisibility(View.GONE);
            }
        }
    };

    private void showController() {
        // TODO: Decide what to show when the state is not in playback state
        if (mMediaControlView == null || !isInPlaybackState()
                || (mIsMusicMediaType && mSizeType == SIZE_TYPE_FULL)) {
            return;
        }
        mMediaControlView.removeCallbacks(mFadeOut);
        mMediaControlView.setVisibility(View.VISIBLE);
        if (mShowControllerIntervalMs != 0
            && !mAccessibilityManager.isTouchExplorationEnabled()) {
            mMediaControlView.postDelayed(mFadeOut, mShowControllerIntervalMs);
        }
    }

    private void toggleMediaControlViewVisibility() {
        if (mMediaControlView.getVisibility() == View.VISIBLE) {
            mMediaControlView.removeCallbacks(mFadeOut);
            mMediaControlView.setVisibility(View.GONE);
        } else {
            showController();
        }
    }

    private void applySpeed() {
        PlaybackParams params = mMediaPlayer.getPlaybackParams().allowDefaults();
        if (mSpeed != params.getSpeed()) {
            try {
                params.setSpeed(mSpeed);
                mMediaPlayer.setPlaybackParams(params);
                mFallbackSpeed = mSpeed;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "PlaybackParams has unsupported value: " + e);
                // TODO: should revise this part after integrating with MP2.
                // If mSpeed had an illegal value for speed rate, system will determine best
                // handling (see PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT).
                // Note: The pre-MP2 returns 0.0f when it is paused. In this case, VideoView2 will
                // use mFallbackSpeed instead.
                float fallbackSpeed = mMediaPlayer.getPlaybackParams().allowDefaults().getSpeed();
                if (fallbackSpeed > 0.0f) {
                    mFallbackSpeed = fallbackSpeed;
                }
                mSpeed = mFallbackSpeed;
            }
        }
    }

    private boolean isRemotePlayback() {
        if (mMediaController == null) {
            return false;
        }
        PlaybackInfo playbackInfo = mMediaController.getPlaybackInfo();
        return playbackInfo != null
                && playbackInfo.getPlaybackType() == PlaybackInfo.PLAYBACK_TYPE_REMOTE;
    }

    private void selectOrDeselectSubtitle(boolean select) {
        if (!isInPlaybackState()) {
            return;
        }
        if (select) {
            if (mSubtitleTrackIndices.size() > 0) {
                // TODO: make this selection dynamic
                mSelectedSubtitleTrackIndex = mSubtitleTrackIndices.get(0).first;
                mSubtitleController.selectTrack(mSubtitleTrackIndices.get(0).second);
                mMediaPlayer.selectTrack(mSelectedSubtitleTrackIndex);
                mSubtitleView.setVisibility(View.VISIBLE);
            }
        } else {
            if (mSelectedSubtitleTrackIndex != INVALID_TRACK_INDEX) {
                mMediaPlayer.deselectTrack(mSelectedSubtitleTrackIndex);
                mSelectedSubtitleTrackIndex = INVALID_TRACK_INDEX;
                mSubtitleView.setVisibility(View.GONE);
            }
        }
    }

    private void extractTracks() {
        List<MediaPlayer2.TrackInfo> trackInfos = mMediaPlayer.getTrackInfo();
        mVideoTrackIndices = new ArrayList<>();
        mAudioTrackIndices = new ArrayList<>();
        mSubtitleTrackIndices = new ArrayList<>();
        mSubtitleController.reset();
        for (int i = 0; i < trackInfos.size(); ++i) {
            int trackType = trackInfos.get(i).getTrackType();
            if (trackType == MediaPlayer2.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                mVideoTrackIndices.add(i);
            } else if (trackType == MediaPlayer2.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                mAudioTrackIndices.add(i);
            } else if (trackType == MediaPlayer2.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                    || trackType == MediaPlayer2.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                SubtitleTrack track = mSubtitleController.addTrack(trackInfos.get(i).getFormat());
                if (track != null) {
                    mSubtitleTrackIndices.add(new Pair<>(i, track));
                }
            }
        }
        // Select first tracks as default
        if (mVideoTrackIndices.size() > 0) {
            mSelectedVideoTrackIndex = 0;
        }
        if (mAudioTrackIndices.size() > 0) {
            mSelectedAudioTrackIndex = 0;
        }
        if (mVideoTrackIndices.size() == 0 && mAudioTrackIndices.size() > 0) {
            mIsMusicMediaType = true;
        }

        Bundle data = new Bundle();
        data.putInt(MediaControlView2Impl.KEY_VIDEO_TRACK_COUNT, mVideoTrackIndices.size());
        data.putInt(MediaControlView2Impl.KEY_AUDIO_TRACK_COUNT, mAudioTrackIndices.size());
        data.putInt(MediaControlView2Impl.KEY_SUBTITLE_TRACK_COUNT, mSubtitleTrackIndices.size());
        if (mSubtitleTrackIndices.size() > 0) {
            selectOrDeselectSubtitle(mSubtitleEnabled);
        }
        mMediaSession.sendSessionEvent(MediaControlView2Impl.EVENT_UPDATE_TRACK_STATUS, data);
    }

    private void extractMetadata() {
        // Get and set duration and title values as MediaMetadata for MediaControlView2
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        if (mMetadata != null && mMetadata.has(Metadata.TITLE)) {
            mTitle = mMetadata.getString(Metadata.TITLE);
        }
        builder.putString(MediaMetadata.METADATA_KEY_TITLE, mTitle);
        builder.putLong(
                MediaMetadata.METADATA_KEY_DURATION, mMediaPlayer.getDuration());

        if (mMediaSession != null) {
            mMediaSession.setMetadata(builder.build());
        }
    }

    private void extractAudioMetadata() {
        if (!mIsMusicMediaType) {
            return;
        }

        mResources = ApiHelper.getLibResources(mInstance.getContext());
        mManager = (WindowManager) mInstance.getContext().getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);

        byte[] album = mRetriever.getEmbeddedPicture();
        if (album != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(album, 0, album.length);
            mMusicAlbumDrawable = new BitmapDrawable(bitmap);

            // TODO: replace with visualizer
            Palette.generateAsync(bitmap, new Palette.PaletteAsyncListener() {
                public void onGenerated(Palette palette) {
                    // TODO: add dominant color for default album image.
                    mDominantColor = palette.getDominantColor(0);
                    if (mMusicView != null) {
                        mMusicView.setBackgroundColor(mDominantColor);
                    }
                }
            });
        } else {
            mMusicAlbumDrawable = mResources.getDrawable(R.drawable.ic_default_album_image);
        }

        String title = mRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        if (title != null) {
            mMusicTitleText = title;
        } else {
            mMusicTitleText = mResources.getString(R.string.mcv2_music_title_unknown_text);
        }

        String artist = mRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        if (artist != null) {
            mMusicArtistText = artist;
        } else {
            mMusicArtistText = mResources.getString(R.string.mcv2_music_artist_unknown_text);
        }

        // Send title and artist string to MediaControlView2
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        builder.putString(MediaMetadata.METADATA_KEY_TITLE, mMusicTitleText);
        builder.putString(MediaMetadata.METADATA_KEY_ARTIST, mMusicArtistText);
        mMediaSession.setMetadata(builder.build());

        // Display Embedded mode as default
        mInstance.removeView(mSurfaceView);
        mInstance.removeView(mTextureView);
        inflateMusicView(R.layout.embedded_music);
    }

    private int retrieveOrientation() {
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;

        return (height > width) ?
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT :
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    private void inflateMusicView(int layoutId) {
        mInstance.removeView(mMusicView);

        View v = ApiHelper.inflateLibLayout(mInstance.getContext(), layoutId);
        v.setBackgroundColor(mDominantColor);

        ImageView albumView = v.findViewById(R.id.album);
        if (albumView != null) {
            albumView.setImageDrawable(mMusicAlbumDrawable);
        }

        TextView titleView = v.findViewById(R.id.title);
        if (titleView != null) {
            titleView.setText(mMusicTitleText);
        }

        TextView artistView = v.findViewById(R.id.artist);
        if (artistView != null) {
            artistView.setText(mMusicArtistText);
        }

        mMusicView = v;
        mInstance.addView(mMusicView, 0);
    }

    OnSubtitleDataListener mSubtitleListener =
            new OnSubtitleDataListener() {
                @Override
                public void onSubtitleData(MediaPlayer2 mp, SubtitleData data) {
                    if (DEBUG) {
                        Log.d(TAG, "onSubtitleData(): getTrackIndex: " + data.getTrackIndex()
                                + ", getCurrentPosition: " + mp.getCurrentPosition()
                                + ", getStartTimeUs(): " + data.getStartTimeUs()
                                + ", diff: "
                                + (data.getStartTimeUs()/1000 - mp.getCurrentPosition())
                                + "ms, getDurationUs(): " + data.getDurationUs()
                                );

                    }
                    final int index = data.getTrackIndex();
                    if (index != mSelectedSubtitleTrackIndex) {
                        Log.d(TAG, "onSubtitleData(): getTrackIndex: " + data.getTrackIndex()
                                + ", selected track index: " + mSelectedSubtitleTrackIndex);
                        return;
                    }
                    for (Pair<Integer, SubtitleTrack> p : mSubtitleTrackIndices) {
                        if (p.first == index) {
                            SubtitleTrack track = p.second;
                            track.onData(data);
                        }
                    }
                }
            };

    MediaPlayer2EventCallback mMediaPlayer2Callback =
            new MediaPlayer2EventCallback() {
                @Override
                public void onVideoSizeChanged(
                        MediaPlayer2 mp, DataSourceDesc dsd, int width, int height) {
                    if (DEBUG) {
                        Log.d(TAG, "onVideoSizeChanged(): size: " + width + "/" + height);
                    }
                    mVideoWidth = mp.getVideoWidth();
                    mVideoHeight = mp.getVideoHeight();
                    if (DEBUG) {
                        Log.d(TAG, "onVideoSizeChanged(): mVideoSize:" + mVideoWidth + "/"
                                + mVideoHeight);
                    }
                    if (mVideoWidth != 0 && mVideoHeight != 0) {
                        mInstance.requestLayout();
                    }
                }

                // TODO: Remove timed text related code later once relevant Renderer is defined.
                // This is just for debugging purpose.
                @Override
                public void onTimedText(
                        MediaPlayer2 mp, DataSourceDesc dsd, TimedText text) {
                        Log.d(TAG, "TimedText: " + text.getText());
                }

                @Override
                public void onInfo(
                        MediaPlayer2 mp, DataSourceDesc dsd, int what, int extra) {
                    if (what == MediaPlayer2.MEDIA_INFO_METADATA_UPDATE) {
                        extractTracks();
                    } else if (what == MediaPlayer2.MEDIA_INFO_PREPARED) {
                        this.onPrepared(mp, dsd);
                    } else if (what == MediaPlayer2.MEDIA_INFO_PLAYBACK_COMPLETE) {
                        this.onCompletion(mp, dsd);
                    } else if (what == MediaPlayer2.MEDIA_INFO_BUFFERING_UPDATE) {
                        this.onBufferingUpdate(mp, dsd, extra);
                    }
                }

                @Override
                public void onError(
                        MediaPlayer2 mp, DataSourceDesc dsd, int frameworkErr, int implErr) {
                    if (DEBUG) {
                        Log.d(TAG, "Error: " + frameworkErr + "," + implErr);
                    }
                    mCurrentState = STATE_ERROR;
                    mTargetState = STATE_ERROR;
                    updatePlaybackState();

                    if (mMediaControlView != null) {
                        mMediaControlView.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onCallCompleted(MediaPlayer2 mp, DataSourceDesc dsd, int what,
                        int status) {
                    if (what == MediaPlayer2.CALL_COMPLETED_SEEK_TO && status == 0) {
                        updatePlaybackState();
                    }
                }

                private void onPrepared(MediaPlayer2 mp, DataSourceDesc dsd) {
                    if (DEBUG) {
                        Log.d(TAG, "OnPreparedListener(). mCurrentState=" + mCurrentState
                                + ", mTargetState=" + mTargetState);
                    }
                    mCurrentState = STATE_PREPARED;
                    // Create and set playback state for MediaControlView2
                    updatePlaybackState();

                    // TODO: change this to send TrackInfos to MediaControlView2
                    // TODO: create MediaSession when initializing VideoView2
                    if (mMediaSession != null) {
                        extractTracks();
                        extractMetadata();
                        extractAudioMetadata();
                    }

                    if (mMediaControlView != null) {
                        mMediaControlView.setEnabled(true);
                    }
                    int videoWidth = mp.getVideoWidth();
                    int videoHeight = mp.getVideoHeight();

                    // mSeekWhenPrepared may be changed after seekTo() call
                    long seekToPosition = mSeekWhenPrepared;
                    if (seekToPosition != 0) {
                        mMediaController.getTransportControls().seekTo(seekToPosition);
                    }

                    if (videoWidth != 0 && videoHeight != 0) {
                        if (videoWidth != mVideoWidth || videoHeight != mVideoHeight) {
                            if (DEBUG) {
                                Log.i(TAG, "OnPreparedListener() : ");
                                Log.i(TAG, " video size: " + videoWidth + "/" + videoHeight);
                                Log.i(TAG, " measuredSize: " + mInstance.getMeasuredWidth() + "/"
                                        + mInstance.getMeasuredHeight());
                                Log.i(TAG, " viewSize: " + mInstance.getWidth() + "/"
                                        + mInstance.getHeight());
                            }
                            mVideoWidth = videoWidth;
                            mVideoHeight = videoHeight;
                            mInstance.requestLayout();
                        }

                        if (needToStart()) {
                            mMediaController.getTransportControls().play();
                        }
                    } else {
                        // We don't know the video size yet, but should start anyway.
                        // The video size might be reported to us later.
                        if (needToStart()) {
                            mMediaController.getTransportControls().play();
                        }
                    }
                }

                private void onCompletion(MediaPlayer2 mp, DataSourceDesc dsd) {
                    mCurrentState = STATE_PLAYBACK_COMPLETED;
                    mTargetState = STATE_PLAYBACK_COMPLETED;
                    updatePlaybackState();
                    if (mAudioFocusType != AudioManager.AUDIOFOCUS_NONE) {
                        mAudioManager.abandonAudioFocus(null);
                    }
                }

                private void onBufferingUpdate(MediaPlayer2 mp, DataSourceDesc dsd, int percent) {
                    mCurrentBufferPercentage = percent;
                    updatePlaybackState();
                }
            };

    private class MediaSessionCallback extends MediaSession.Callback {
        @Override
        public void onCommand(String command, Bundle args, ResultReceiver receiver) {
            if (isRemotePlayback()) {
                mRoutePlayer.onCommand(command, args, receiver);
            } else {
                switch (command) {
                    case MediaControlView2Impl.COMMAND_SHOW_SUBTITLE:
                        int subtitleIndex = args.getInt(
                                MediaControlView2Impl.KEY_SELECTED_SUBTITLE_INDEX,
                                INVALID_TRACK_INDEX);
                        if (subtitleIndex != INVALID_TRACK_INDEX) {
                            int subtitleTrackIndex = mSubtitleTrackIndices.get(subtitleIndex).first;
                            if (subtitleTrackIndex != mSelectedSubtitleTrackIndex) {
                                mSelectedSubtitleTrackIndex = subtitleTrackIndex;
                                mInstance.setSubtitleEnabled(true);
                            }
                        }
                        break;
                    case MediaControlView2Impl.COMMAND_HIDE_SUBTITLE:
                        mInstance.setSubtitleEnabled(false);
                        break;
                    case MediaControlView2Impl.COMMAND_SET_FULLSCREEN:
                        if (mFullScreenRequestListener != null) {
                            mFullScreenRequestListener.onFullScreenRequest(
                                    mInstance,
                                    args.getBoolean(MediaControlView2Impl.ARGUMENT_KEY_FULLSCREEN));
                        }
                        break;
                    case MediaControlView2Impl.COMMAND_SELECT_AUDIO_TRACK:
                        int audioIndex = args.getInt(MediaControlView2Impl.KEY_SELECTED_AUDIO_INDEX,
                                INVALID_TRACK_INDEX);
                        if (audioIndex != INVALID_TRACK_INDEX) {
                            int audioTrackIndex = mAudioTrackIndices.get(audioIndex);
                            if (audioTrackIndex != mSelectedAudioTrackIndex) {
                                mSelectedAudioTrackIndex = audioTrackIndex;
                                mMediaPlayer.selectTrack(mSelectedAudioTrackIndex);
                            }
                        }
                        break;
                    case MediaControlView2Impl.COMMAND_SET_PLAYBACK_SPEED:
                        float speed = args.getFloat(
                                MediaControlView2Impl.KEY_PLAYBACK_SPEED, INVALID_SPEED);
                        if (speed != INVALID_SPEED && speed != mSpeed) {
                            mInstance.setSpeed(speed);
                            mSpeed = speed;
                        }
                        break;
                    case MediaControlView2Impl.COMMAND_MUTE:
                        mVolumeLevel = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                        break;
                    case MediaControlView2Impl.COMMAND_UNMUTE:
                        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mVolumeLevel, 0);
                        break;
                }
            }
            showController();
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            mCustomActionListenerRecord.first.execute(() ->
                    mCustomActionListenerRecord.second.onCustomAction(action, extras));
            showController();
        }

        @Override
        public void onPlay() {
            if (isInPlaybackState() && (mCurrentView.hasAvailableSurface() || mIsMusicMediaType)) {
                if (isRemotePlayback()) {
                    mRoutePlayer.onPlay();
                } else {
                    applySpeed();
                    mMediaPlayer.play();
                    mCurrentState = STATE_PLAYING;
                    updatePlaybackState();
                }
                mCurrentState = STATE_PLAYING;
            }
            mTargetState = STATE_PLAYING;
            if (DEBUG) {
                Log.d(TAG, "onPlay(). mCurrentState=" + mCurrentState
                        + ", mTargetState=" + mTargetState);
            }
            showController();
        }

        @Override
        public void onPause() {
            if (isInPlaybackState()) {
                if (isRemotePlayback()) {
                    mRoutePlayer.onPause();
                    mCurrentState = STATE_PAUSED;
                } else if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.pause();
                    mCurrentState = STATE_PAUSED;
                    updatePlaybackState();
                }
            }
            mTargetState = STATE_PAUSED;
            if (DEBUG) {
                Log.d(TAG, "onPause(). mCurrentState=" + mCurrentState
                        + ", mTargetState=" + mTargetState);
            }
            showController();
        }

        @Override
        public void onSeekTo(long pos) {
            if (isInPlaybackState()) {
                if (isRemotePlayback()) {
                    mRoutePlayer.onSeekTo(pos);
                } else {
                    mMediaPlayer.seekTo(pos, MediaPlayer2.SEEK_PREVIOUS_SYNC);
                    mSeekWhenPrepared = 0;
                }
            } else {
                mSeekWhenPrepared = pos;
            }
            showController();
        }

        @Override
        public void onStop() {
            if (isRemotePlayback()) {
                mRoutePlayer.onStop();
            } else {
                resetPlayer();
            }
            showController();
        }
    }
}
