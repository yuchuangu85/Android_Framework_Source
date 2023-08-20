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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManagerGlobal;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.external.TileLifecycleManager.TileChangeListener;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.settings.DisplayTracker;

import dagger.Lazy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;



public class CustomTile extends QSTileImpl<State> implements TileChangeListener {
    public static final String PREFIX = "custom(";

    private static final long CUSTOM_STALE_TIMEOUT = DateUtils.HOUR_IN_MILLIS;

    private static final boolean DEBUG = false;

    // We don't want to thrash binding and unbinding if the user opens and closes the panel a lot.
    // So instead we have a period of waiting.
    private static final long UNBIND_DELAY = 30000;

    private final ComponentName mComponent;
    private final Tile mTile;
    private final IWindowManager mWindowManager;
    private final IBinder mToken = new Binder();
    private final IQSTileService mService;
    private final TileServiceManager mServiceManager;
    private final int mUser;
    private final CustomTileStatePersister mCustomTileStatePersister;
    private final DisplayTracker mDisplayTracker;
    @Nullable
    private android.graphics.drawable.Icon mDefaultIcon;
    @Nullable
    private CharSequence mDefaultLabel;
    @Nullable
    private View mViewClicked;

    private final Context mUserContext;

    private boolean mListening;
    private boolean mIsTokenGranted;
    private boolean mIsShowingDialog;

    private final TileServiceKey mKey;

    private final AtomicBoolean mInitialDefaultIconFetched = new AtomicBoolean(false);
    private final TileServices mTileServices;

    private CustomTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            Looper backgroundLooper,
            Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            String action,
            Context userContext,
            CustomTileStatePersister customTileStatePersister,
            TileServices tileServices,
            DisplayTracker displayTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mTileServices = tileServices;
        mWindowManager = WindowManagerGlobal.getWindowManagerService();
        mComponent = ComponentName.unflattenFromString(action);
        mTile = new Tile();
        mUserContext = userContext;
        mUser = mUserContext.getUserId();
        mKey = new TileServiceKey(mComponent, mUser);

        mServiceManager = tileServices.getTileWrapper(this);
        mService = mServiceManager.getTileService();
        mCustomTileStatePersister = customTileStatePersister;
        mDisplayTracker = displayTracker;
    }

    @Override
    protected void handleInitialize() {
        updateDefaultTileAndIcon();
        if (mInitialDefaultIconFetched.compareAndSet(false, true)) {
            if (mDefaultIcon == null) {
                Log.w(TAG, "No default icon for " + getTileSpec() + ", destroying tile");
                mHost.removeTile(getTileSpec());
            }
        }
        if (mServiceManager.isToggleableTile()) {
            // Replace states with BooleanState
            resetStates();
        }
        mServiceManager.setTileChangeListener(this);
        if (mServiceManager.isActiveTile()) {
            Tile t = mCustomTileStatePersister.readState(mKey);
            if (t != null) {
                applyTileState(t, /* overwriteNulls */ false);
                mServiceManager.clearPendingBind();
                refreshState();
            }
        }
    }

    @Override
    protected long getStaleTimeout() {
        return CUSTOM_STALE_TIMEOUT + DateUtils.MINUTE_IN_MILLIS * mHost.indexOf(getTileSpec());
    }

    private void updateDefaultTileAndIcon() {
        try {
            PackageManager pm = mUserContext.getPackageManager();
            int flags = PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_DIRECT_BOOT_AWARE;
            if (isSystemApp(pm)) {
                flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
            }

            ServiceInfo info = pm.getServiceInfo(mComponent, flags);
            int icon = info.icon != 0 ? info.icon
                    : info.applicationInfo.icon;
            // Update the icon if its not set or is the default icon.
            boolean updateIcon = mTile.getIcon() == null
                    || iconEquals(mTile.getIcon(), mDefaultIcon);
            mDefaultIcon = icon != 0 ? android.graphics.drawable.Icon
                    .createWithResource(mComponent.getPackageName(), icon) : null;
            if (updateIcon) {
                mTile.setIcon(mDefaultIcon);
            }
            // Update the label if there is no label or it is the default label.
            boolean updateLabel = mTile.getLabel() == null
                    || TextUtils.equals(mTile.getLabel(), mDefaultLabel);
            mDefaultLabel = info.loadLabel(pm);
            if (updateLabel) {
                mTile.setLabel(mDefaultLabel);
            }
        } catch (PackageManager.NameNotFoundException e) {
            mDefaultIcon = null;
            mDefaultLabel = null;
        }
    }

    private boolean isSystemApp(PackageManager pm) throws PackageManager.NameNotFoundException {
        return pm.getApplicationInfo(mComponent.getPackageName(), 0).isSystemApp();
    }

    /**
     * Compare two icons, only works for resources.
     */
    private boolean iconEquals(@Nullable android.graphics.drawable.Icon icon1,
                               @Nullable android.graphics.drawable.Icon icon2) {
        if (icon1 == icon2) {
            return true;
        }
        if (icon1 == null || icon2 == null) {
            return false;
        }
        if (icon1.getType() != android.graphics.drawable.Icon.TYPE_RESOURCE
                || icon2.getType() != android.graphics.drawable.Icon.TYPE_RESOURCE) {
            return false;
        }
        if (icon1.getResId() != icon2.getResId()) {
            return false;
        }
        if (!Objects.equals(icon1.getResPackage(), icon2.getResPackage())) {
            return false;
        }
        return true;
    }

    @Override
    public void onTileChanged(ComponentName tile) {
        mHandler.post(this::updateDefaultTileAndIcon);
    }

    /**
     * Custom tile is considered available if there is a default icon (obtained from PM).
     * <p>
     * It will return {@code true} before initialization, so tiles are not destroyed prematurely.
     */
    @Override
    public boolean isAvailable() {
        if (mInitialDefaultIconFetched.get()) {
            return mDefaultIcon != null;
        } else {
            return true;
        }
    }

    public int getUser() {
        return mUser;
    }

    public ComponentName getComponent() {
        return mComponent;
    }

    @Override
    public LogMaker populate(LogMaker logMaker) {
        return super.populate(logMaker).setComponentName(mComponent);
    }

    public Tile getQsTile() {
        // TODO(b/191145007) Move to background thread safely
        updateDefaultTileAndIcon();
        return mTile;
    }

    /**
     * Update state of {@link this#mTile} from a remote {@link TileService}.
     *
     * @param tile tile populated with state to apply
     */
    public void updateTileState(Tile tile) {
        // This comes from a binder call IQSService.updateQsTile
        mHandler.post(() -> handleUpdateTileState(tile));
    }

    private void handleUpdateTileState(Tile tile) {
        applyTileState(tile, /* overwriteNulls */ true);
        if (mServiceManager.isActiveTile()) {
            mCustomTileStatePersister.persistState(mKey, tile);
        }
    }

    @WorkerThread
    private void applyTileState(Tile tile, boolean overwriteNulls) {
        if (tile.getIcon() != null || overwriteNulls) {
            mTile.setIcon(tile.getIcon());
        }
        if (tile.getLabel() != null || overwriteNulls) {
            mTile.setLabel(tile.getLabel());
        }
        if (tile.getSubtitle() != null || overwriteNulls) {
            mTile.setSubtitle(tile.getSubtitle());
        }
        if (tile.getContentDescription() != null || overwriteNulls) {
            mTile.setContentDescription(tile.getContentDescription());
        }
        if (tile.getStateDescription() != null || overwriteNulls) {
            mTile.setStateDescription(tile.getStateDescription());
        }
        mTile.setActivityLaunchForClick(tile.getActivityLaunchForClick());
        mTile.setState(tile.getState());
    }

    public void onDialogShown() {
        mIsShowingDialog = true;
    }

    public void onDialogHidden() {
        mIsShowingDialog = false;
        try {
            if (DEBUG) Log.d(TAG, "Removing token");
            mWindowManager.removeWindowToken(mToken, mDisplayTracker.getDefaultDisplayId());
        } catch (RemoteException e) {
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;

        try {
            if (listening) {
                updateDefaultTileAndIcon();
                refreshState();
                if (!mServiceManager.isActiveTile() || !isTileReady()) {
                    mServiceManager.setBindRequested(true);
                    mService.onStartListening();
                }
            } else {
                mViewClicked = null;
                mService.onStopListening();
                if (mIsTokenGranted && !mIsShowingDialog) {
                    try {
                        if (DEBUG) Log.d(TAG, "Removing token");
                        mWindowManager.removeWindowToken(mToken,
                                mDisplayTracker.getDefaultDisplayId());
                    } catch (RemoteException e) {
                    }
                    mIsTokenGranted = false;
                }
                mIsShowingDialog = false;
                mServiceManager.setBindRequested(false);
            }
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mIsTokenGranted) {
            try {
                if (DEBUG) Log.d(TAG, "Removing token");
                mWindowManager.removeWindowToken(mToken, mDisplayTracker.getDefaultDisplayId());
            } catch (RemoteException e) {
            }
        }
        mTileServices.freeService(this, mServiceManager);
    }

    @Override
    public State newTileState() {
        if (mServiceManager != null && mServiceManager.isToggleableTile()) {
            return new BooleanState();
        }
        return new State();
    }

    @Override
    public Intent getLongClickIntent() {
        Intent i = new Intent(TileService.ACTION_QS_TILE_PREFERENCES);
        i.setPackage(mComponent.getPackageName());
        i = resolveIntent(i);
        if (i != null) {
            i.putExtra(Intent.EXTRA_COMPONENT_NAME, mComponent);
            i.putExtra(TileService.EXTRA_STATE, mTile.getState());
            return i;
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.fromParts("package", mComponent.getPackageName(), null));
    }

    @Nullable
    private Intent resolveIntent(Intent i) {
        ResolveInfo result = mContext.getPackageManager().resolveActivityAsUser(i, 0, mUser);
        return result != null ? new Intent(TileService.ACTION_QS_TILE_PREFERENCES)
                .setClassName(result.activityInfo.packageName, result.activityInfo.name) : null;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (mTile.getState() == Tile.STATE_UNAVAILABLE) {
            return;
        }
        mViewClicked = view;
        try {
            if (DEBUG) Log.d(TAG, "Adding token");
            mWindowManager.addWindowToken(mToken, TYPE_QS_DIALOG,
                    mDisplayTracker.getDefaultDisplayId(), null /* options */);
            mIsTokenGranted = true;
        } catch (RemoteException e) {
        }
        try {
            if (mServiceManager.isActiveTile()) {
                mServiceManager.setBindRequested(true);
                mService.onStartListening();
            }

            if (mTile.getActivityLaunchForClick() != null) {
                startActivityAndCollapse(mTile.getActivityLaunchForClick());
            } else {
                mService.onClick(mToken);
            }
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        int tileState = mTile.getState();
        if (mServiceManager.hasPendingBind()) {
            tileState = Tile.STATE_UNAVAILABLE;
        }
        state.state = tileState;
        Drawable drawable = null;
        try {
            drawable = mTile.getIcon().loadDrawable(mUserContext);
        } catch (Exception e) {
            Log.w(TAG, "Invalid icon, forcing into unavailable state");
            state.state = Tile.STATE_UNAVAILABLE;
            drawable = mDefaultIcon.loadDrawable(mUserContext);
        }

        final Drawable drawableF = drawable;
        state.iconSupplier = () -> {
            if (drawableF == null) return null;
            Drawable.ConstantState cs = drawableF.getConstantState();
            if (cs != null) {
                return new DrawableIcon(cs.newDrawable());
            }
            return null;
        };
        state.label = mTile.getLabel();

        CharSequence subtitle = mTile.getSubtitle();
        if (subtitle != null && subtitle.length() > 0) {
            state.secondaryLabel = subtitle;
        } else {
            state.secondaryLabel = null;
        }

        if (mTile.getContentDescription() != null) {
            state.contentDescription = mTile.getContentDescription();
        } else {
            state.contentDescription = state.label;
        }

        if (mTile.getStateDescription() != null) {
            state.stateDescription = mTile.getStateDescription();
        } else {
            state.stateDescription = null;
        }

        if (state instanceof BooleanState) {
            state.expandedAccessibilityClassName = Switch.class.getName();
            ((BooleanState) state).value = (state.state == Tile.STATE_ACTIVE);
        }

    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CUSTOM;
    }

    @Override
    public final String getMetricsSpec() {
        return mComponent.getPackageName();
    }

    public void startUnlockAndRun() {
        mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
            try {
                mService.onUnlockComplete();
            } catch (RemoteException e) {
            }
        });
    }

    /**
     * Starts an {@link android.app.Activity}
     * @param pendingIntent A PendingIntent for an Activity to be launched immediately.
     */
    public void startActivityAndCollapse(PendingIntent pendingIntent) {
        if (!pendingIntent.isActivity()) {
            Log.i(TAG, "Intent not for activity.");
        } else if (!mIsTokenGranted) {
            Log.i(TAG, "Launching activity before click");
        } else {
            Log.i(TAG, "The activity is starting");
            ActivityLaunchAnimator.Controller controller = mViewClicked == null
                    ? null
                    : ActivityLaunchAnimator.Controller.fromView(mViewClicked, 0);
            mUiHandler.post(() ->
                    mActivityStarter.startPendingIntentDismissingKeyguard(
                            pendingIntent, null, controller)
            );
        }
    }

    public static String toSpec(ComponentName name) {
        return PREFIX + name.flattenToShortString() + ")";
    }

    public static ComponentName getComponentFromSpec(String spec) {
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return ComponentName.unflattenFromString(action);
    }

    private static String getAction(String spec) {
        if (spec == null || !spec.startsWith(PREFIX) || !spec.endsWith(")")) {
            throw new IllegalArgumentException("Bad custom tile spec: " + spec);
        }
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return action;
    }

    /**
     * Create a {@link CustomTile} for a given spec and user.
     *
     * @param builder     including injected common dependencies.
     * @param spec        as provided by {@link CustomTile#toSpec}
     * @param userContext context for the user that is creating this tile.
     * @return a new {@link CustomTile}
     */
    public static CustomTile create(Builder builder, String spec, Context userContext) {
        return builder
                .setSpec(spec)
                .setUserContext(userContext)
                .build();
    }

    public static class Builder {
        final Lazy<QSHost> mQSHostLazy;
        final QsEventLogger mUiEventLogger;
        final Looper mBackgroundLooper;
        final Handler mMainHandler;
        private final FalsingManager mFalsingManager;
        final MetricsLogger mMetricsLogger;
        final StatusBarStateController mStatusBarStateController;
        final ActivityStarter mActivityStarter;
        final QSLogger mQSLogger;
        final CustomTileStatePersister mCustomTileStatePersister;
        private TileServices mTileServices;
        final DisplayTracker mDisplayTracker;

        Context mUserContext;
        String mSpec = "";

        @Inject
        public Builder(
                Lazy<QSHost> hostLazy,
                QsEventLogger uiEventLogger,
                @Background Looper backgroundLooper,
                @Main Handler mainHandler,
                FalsingManager falsingManager,
                MetricsLogger metricsLogger,
                StatusBarStateController statusBarStateController,
                ActivityStarter activityStarter,
                QSLogger qsLogger,
                CustomTileStatePersister customTileStatePersister,
                TileServices tileServices,
                DisplayTracker displayTracker
        ) {
            mQSHostLazy = hostLazy;
            mUiEventLogger = uiEventLogger;
            mBackgroundLooper = backgroundLooper;
            mMainHandler = mainHandler;
            mFalsingManager = falsingManager;
            mMetricsLogger = metricsLogger;
            mStatusBarStateController = statusBarStateController;
            mActivityStarter = activityStarter;
            mQSLogger = qsLogger;
            mCustomTileStatePersister = customTileStatePersister;
            mTileServices = tileServices;
            mDisplayTracker = displayTracker;
        }

        Builder setSpec(@NonNull String spec) {
            mSpec = spec;
            return this;
        }

        Builder setUserContext(@NonNull Context userContext) {
            mUserContext = userContext;
            return this;
        }

        @VisibleForTesting
        public CustomTile build() {
            if (mUserContext == null) {
                throw new NullPointerException("UserContext cannot be null");
            }
            String action = getAction(mSpec);
            return new CustomTile(
                    mQSHostLazy.get(),
                    mUiEventLogger,
                    mBackgroundLooper,
                    mMainHandler,
                    mFalsingManager,
                    mMetricsLogger,
                    mStatusBarStateController,
                    mActivityStarter,
                    mQSLogger,
                    action,
                    mUserContext,
                    mCustomTileStatePersister,
                    mTileServices,
                    mDisplayTracker
            );
        }
    }
}
