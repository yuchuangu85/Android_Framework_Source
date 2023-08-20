/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.annotation.Nullable;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Xfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.util.IndentingPrintWriter;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.NotificationMediaManager;

import libcore.io.IoUtils;

import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Manages the lockscreen wallpaper.
 */
@SysUISingleton
public class LockscreenWallpaper extends IWallpaperManagerCallback.Stub implements Runnable,
        Dumpable {

    private static final String TAG = "LockscreenWallpaper";

    // TODO(b/253507223): temporary; remove this
    private static final String DISABLED_ERROR_MESSAGE = "Methods from LockscreenWallpaper.java "
            + "should not be called in this version. The lock screen wallpaper should be "
            + "managed by the WallpaperManagerService and not by this class.";

    private final NotificationMediaManager mMediaManager;
    private final WallpaperManager mWallpaperManager;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final Handler mH;

    private boolean mCached;
    private Bitmap mCache;
    private int mCurrentUserId;
    // The user selected in the UI, or null if no user is selected or UI doesn't support selecting
    // users.
    private UserHandle mSelectedUser;
    private AsyncTask<Void, Void, LoaderResult> mLoader;

    @Inject
    public LockscreenWallpaper(WallpaperManager wallpaperManager,
            @Nullable IWallpaperManager iWallpaperManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DumpManager dumpManager,
            NotificationMediaManager mediaManager,
            @Main Handler mainHandler,
            UserTracker userTracker) {
        dumpManager.registerDumpable(getClass().getSimpleName(), this);
        mWallpaperManager = wallpaperManager;
        mCurrentUserId = userTracker.getUserId();
        mUpdateMonitor = keyguardUpdateMonitor;
        mMediaManager = mediaManager;
        mH = mainHandler;

        if (iWallpaperManager != null && !mWallpaperManager.isLockscreenLiveWallpaperEnabled()) {
            // Service is disabled on some devices like Automotive
            try {
                iWallpaperManager.setLockWallpaperCallback(this);
            } catch (RemoteException e) {
                Log.e(TAG, "System dead?" + e);
            }
        }
    }

    public Bitmap getBitmap() {
        assertLockscreenLiveWallpaperNotEnabled();

        if (mCached) {
            return mCache;
        }
        if (!mWallpaperManager.isWallpaperSupported()) {
            mCached = true;
            mCache = null;
            return null;
        }

        LoaderResult result = loadBitmap(mCurrentUserId, mSelectedUser);
        if (result.success) {
            mCached = true;
            mCache = result.bitmap;
        }
        return mCache;
    }

    public LoaderResult loadBitmap(int currentUserId, UserHandle selectedUser) {
        // May be called on any thread - only use thread safe operations.

        assertLockscreenLiveWallpaperNotEnabled();


        if (!mWallpaperManager.isWallpaperSupported()) {
            // When wallpaper is not supported, show the system wallpaper
            return LoaderResult.success(null);
        }

        // Prefer the selected user (when specified) over the current user for the FLAG_SET_LOCK
        // wallpaper.
        final int lockWallpaperUserId =
                selectedUser != null ? selectedUser.getIdentifier() : currentUserId;
        ParcelFileDescriptor fd = mWallpaperManager.getWallpaperFile(
                WallpaperManager.FLAG_LOCK, lockWallpaperUserId);

        if (fd != null) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.HARDWARE;
                return LoaderResult.success(BitmapFactory.decodeFileDescriptor(
                        fd.getFileDescriptor(), null, options));
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "Can't decode file", e);
                return LoaderResult.fail();
            } finally {
                IoUtils.closeQuietly(fd);
            }
        } else {
            if (selectedUser != null) {
                // Show the selected user's static wallpaper.
                return LoaderResult.success(mWallpaperManager.getBitmapAsUser(
                        selectedUser.getIdentifier(), true /* hardware */));

            } else {
                // When there is no selected user, show the system wallpaper
                return LoaderResult.success(null);
            }
        }
    }

    public void setCurrentUser(int user) {
        assertLockscreenLiveWallpaperNotEnabled();

        if (user != mCurrentUserId) {
            if (mSelectedUser == null || user != mSelectedUser.getIdentifier()) {
                mCached = false;
            }
            mCurrentUserId = user;
        }
    }

    public void setSelectedUser(UserHandle selectedUser) {
        assertLockscreenLiveWallpaperNotEnabled();

        if (Objects.equals(selectedUser, mSelectedUser)) {
            return;
        }
        mSelectedUser = selectedUser;
        postUpdateWallpaper();
    }

    @Override
    public void onWallpaperChanged() {
        assertLockscreenLiveWallpaperNotEnabled();
        // Called on Binder thread.
        postUpdateWallpaper();
    }

    @Override
    public void onWallpaperColorsChanged(WallpaperColors colors, int which, int userId) {
        assertLockscreenLiveWallpaperNotEnabled();
    }

    private void postUpdateWallpaper() {
        assertLockscreenLiveWallpaperNotEnabled();
        if (mH == null) {
            Log.wtfStack(TAG, "Trying to use LockscreenWallpaper before initialization.");
            return;
        }
        mH.removeCallbacks(this);
        mH.post(this);
    }
    @Override
    public void run() {
        // Called in response to onWallpaperChanged on the main thread.

        assertLockscreenLiveWallpaperNotEnabled();

        if (mLoader != null) {
            mLoader.cancel(false /* interrupt */);
        }

        final int currentUser = mCurrentUserId;
        final UserHandle selectedUser = mSelectedUser;
        mLoader = new AsyncTask<Void, Void, LoaderResult>() {
            @Override
            protected LoaderResult doInBackground(Void... params) {
                return loadBitmap(currentUser, selectedUser);
            }

            @Override
            protected void onPostExecute(LoaderResult result) {
                super.onPostExecute(result);
                if (isCancelled()) {
                    return;
                }
                if (result.success) {
                    mCached = true;
                    mCache = result.bitmap;
                    mMediaManager.updateMediaMetaData(
                            true /* metaDataChanged */, true /* allowEnterAnimation */);
                }
                mLoader = null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // TODO(b/273443374): remove
    public boolean isLockscreenLiveWallpaperEnabled() {
        return mWallpaperManager.isLockscreenLiveWallpaperEnabled();
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println(getClass().getSimpleName() + ":");
        IndentingPrintWriter iPw = new IndentingPrintWriter(pw, "  ").increaseIndent();
        iPw.println("mCached=" + mCached);
        iPw.println("mCache=" + mCache);
        iPw.println("mCurrentUserId=" + mCurrentUserId);
        iPw.println("mSelectedUser=" + mSelectedUser);
    }

    private static class LoaderResult {
        public final boolean success;
        public final Bitmap bitmap;

        LoaderResult(boolean success, Bitmap bitmap) {
            this.success = success;
            this.bitmap = bitmap;
        }

        static LoaderResult success(Bitmap b) {
            return new LoaderResult(true, b);
        }

        static LoaderResult fail() {
            return new LoaderResult(false, null);
        }
    }

    /**
     * Drawable that aligns left horizontally and center vertically (like ImageWallpaper).
     */
    public static class WallpaperDrawable extends DrawableWrapper {

        private final ConstantState mState;
        private final Rect mTmpRect = new Rect();

        public WallpaperDrawable(Resources r, Bitmap b) {
            this(r, new ConstantState(b));
        }

        private WallpaperDrawable(Resources r, ConstantState state) {
            super(new BitmapDrawable(r, state.mBackground));
            mState = state;
        }

        @Override
        public void setXfermode(@Nullable Xfermode mode) {
            // DrawableWrapper does not call this for us.
            getDrawable().setXfermode(mode);
        }

        @Override
        public int getIntrinsicWidth() {
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            int vwidth = getBounds().width();
            int vheight = getBounds().height();
            int dwidth = mState.mBackground.getWidth();
            int dheight = mState.mBackground.getHeight();
            float scale;
            float dx = 0, dy = 0;

            if (dwidth * vheight > vwidth * dheight) {
                scale = (float) vheight / (float) dheight;
            } else {
                scale = (float) vwidth / (float) dwidth;
            }

            if (scale <= 1f) {
                scale = 1f;
            }
            dy = (vheight - dheight * scale) * 0.5f;

            mTmpRect.set(
                    bounds.left,
                    bounds.top + Math.round(dy),
                    bounds.left + Math.round(dwidth * scale),
                    bounds.top + Math.round(dheight * scale + dy));

            super.onBoundsChange(mTmpRect);
        }

        @Override
        public ConstantState getConstantState() {
            return mState;
        }

        static class ConstantState extends Drawable.ConstantState {

            private final Bitmap mBackground;

            ConstantState(Bitmap background) {
                mBackground = background;
            }

            @Override
            public Drawable newDrawable() {
                return newDrawable(null);
            }

            @Override
            public Drawable newDrawable(@Nullable Resources res) {
                return new WallpaperDrawable(res, this);
            }

            @Override
            public int getChangingConfigurations() {
                // DrawableWrapper already handles this for us.
                return 0;
            }
        }
    }

    /**
     * Feature b/253507223 will adapt the logic to always use the
     * WallpaperManagerService to render the lock screen wallpaper.
     * Methods of this class should not be called at all if the project flag is enabled.
     * TODO(b/253507223) temporary assertion; remove this
     */
    private void assertLockscreenLiveWallpaperNotEnabled() {
        if (mWallpaperManager.isLockscreenLiveWallpaperEnabled()) {
            throw new IllegalStateException(DISABLED_ERROR_MESSAGE);
        }
    }
}
