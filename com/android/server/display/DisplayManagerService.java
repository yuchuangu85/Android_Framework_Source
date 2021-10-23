/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CAPTURE_SECURE_VIDEO_OUTPUT;
import static android.Manifest.permission.CAPTURE_VIDEO_OUTPUT;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.hardware.display.DisplayManager.EventsMask;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;
import static android.hardware.display.DisplayManagerGlobal.DisplayEvent;
import static android.hardware.display.DisplayViewport.VIEWPORT_EXTERNAL;
import static android.hardware.display.DisplayViewport.VIEWPORT_INTERNAL;
import static android.hardware.display.DisplayViewport.VIEWPORT_VIRTUAL;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.Curve;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayGroupListener;
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener;
import android.hardware.display.DisplayManagerInternal.RefreshRateLimitation;
import android.hardware.display.DisplayManagerInternal.RefreshRateRange;
import android.hardware.display.DisplayViewport;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.display.WifiDisplayStatus;
import android.hardware.input.InputManagerInternal;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.DisplayProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Spline;
import android.view.Display;
import android.view.DisplayEventReceiver;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.display.DisplayDeviceConfig.SensorData;
import com.android.server.display.utils.SensorUtils;
import com.android.server.wm.SurfaceAnimationThread;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Manages attached displays.
 * <p>
 * The {@link DisplayManagerService} manages the global lifecycle of displays,
 * decides how to configure logical displays based on the physical display devices currently
 * attached, sends notifications to the system and to applications when the state
 * changes, and so on.
 * </p><p>
 * The display manager service relies on a collection of {@link DisplayAdapter} components,
 * for discovering and configuring physical display devices attached to the system.
 * There are separate display adapters for each manner that devices are attached:
 * one display adapter for physical displays, one for simulated non-functional
 * displays when the system is headless, one for simulated overlay displays used for
 * development, one for wifi displays, etc.
 * </p><p>
 * Display adapters are only weakly coupled to the display manager service.
 * Display adapters communicate changes in display device state to the display manager
 * service asynchronously via a {@link DisplayAdapter.Listener}, and through
 * the {@link DisplayDeviceRepository.Listener}, which is ultimately registered
 * by the display manager service.  This separation of concerns is important for
 * two main reasons.  First, it neatly encapsulates the responsibilities of these
 * two classes: display adapters handle individual display devices whereas
 * the display manager service handles the global state.  Second, it eliminates
 * the potential for deadlocks resulting from asynchronous display device discovery.
 * </p>
 *
 * <h3>Synchronization</h3>
 * <p>
 * Because the display manager may be accessed by multiple threads, the synchronization
 * story gets a little complicated.  In particular, the window manager may call into
 * the display manager while holding a surface transaction with the expectation that
 * it can apply changes immediately.  Unfortunately, that means we can't just do
 * everything asynchronously (*grump*).
 * </p><p>
 * To make this work, all of the objects that belong to the display manager must
 * use the same lock.  We call this lock the synchronization root and it has a unique
 * type {@link DisplayManagerService.SyncRoot}.  Methods that require this lock are
 * named with the "Locked" suffix.
 * </p><p>
 * Where things get tricky is that the display manager is not allowed to make
 * any potentially reentrant calls, especially into the window manager.  We generally
 * avoid this by making all potentially reentrant out-calls asynchronous.
 * </p>
 */
public final class DisplayManagerService extends SystemService {
    private static final String TAG = "DisplayManagerService";
    private static final boolean DEBUG = false;

    // When this system property is set to 0, WFD is forcibly disabled on boot.
    // When this system property is set to 1, WFD is forcibly enabled on boot.
    // Otherwise WFD is enabled according to the value of config_enableWifiDisplay.
    private static final String FORCE_WIFI_DISPLAY_ENABLE = "persist.debug.wfd.enable";

    private static final String PROP_DEFAULT_DISPLAY_TOP_INSET = "persist.sys.displayinset.top";
    private static final long WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000;
    private static final float THRESHOLD_FOR_REFRESH_RATES_DIVIDERS = 0.1f;

    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS = 1;
    private static final int MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS = 2;
    private static final int MSG_DELIVER_DISPLAY_EVENT = 3;
    private static final int MSG_REQUEST_TRAVERSAL = 4;
    private static final int MSG_UPDATE_VIEWPORT = 5;
    private static final int MSG_LOAD_BRIGHTNESS_CONFIGURATION = 6;
    private static final int MSG_DELIVER_DISPLAY_EVENT_FRAME_RATE_OVERRIDE = 7;
    private static final int MSG_DELIVER_DISPLAY_GROUP_EVENT = 8;

    private final Context mContext;
    private final DisplayManagerHandler mHandler;
    private final Handler mUiHandler;
    private final DisplayModeDirector mDisplayModeDirector;
    private WindowManagerInternal mWindowManagerInternal;
    private InputManagerInternal mInputManagerInternal;
    private IMediaProjectionManager mProjectionService;
    private int[] mUserDisabledHdrTypes = {};
    private boolean mAreUserDisabledHdrTypesAllowed = true;

    // The synchronization root for the display manager.
    // This lock guards most of the display manager's state.
    // NOTE: This is synchronized on while holding WindowManagerService.mWindowMap so never call
    // into WindowManagerService methods that require mWindowMap while holding this unless you are
    // very very sure that no deadlock can occur.
    private final SyncRoot mSyncRoot = new SyncRoot();

    // True if in safe mode.
    // This option may disable certain display adapters.
    public boolean mSafeMode;

    // True if we are in a special boot mode where only core applications and
    // services should be started.  This option may disable certain display adapters.
    public boolean mOnlyCore;

    // All callback records indexed by calling process id.
    public final SparseArray<CallbackRecord> mCallbacks =
            new SparseArray<CallbackRecord>();

    // List of all currently registered display adapters.
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();

    /**
     * Repository of all active {@link DisplayDevice}s.
     */
    private final DisplayDeviceRepository mDisplayDeviceRepo;

    /**
     * Contains all the {@link LogicalDisplay} instances and is responsible for mapping
     * {@link DisplayDevice}s to {@link LogicalDisplay}s. DisplayManagerService listens to display
     * event on this object.
     */
    private final LogicalDisplayMapper mLogicalDisplayMapper;

    // List of all display transaction listeners.
    private final CopyOnWriteArrayList<DisplayTransactionListener> mDisplayTransactionListeners =
            new CopyOnWriteArrayList<DisplayTransactionListener>();

    /** List of all display group listeners. */
    private final CopyOnWriteArrayList<DisplayGroupListener> mDisplayGroupListeners =
            new CopyOnWriteArrayList<>();

    /** All {@link DisplayPowerController}s indexed by {@link LogicalDisplay} ID. */
    private final SparseArray<DisplayPowerController> mDisplayPowerControllers =
            new SparseArray<>();

    /** {@link DisplayBlanker} used by all {@link DisplayPowerController}s. */
    private final DisplayBlanker mDisplayBlanker = new DisplayBlanker() {
        // Synchronized to avoid race conditions when updating multiple display states.
        @Override
        public synchronized void requestDisplayState(int displayId, int state, float brightness,
                float sdrBrightness) {
            boolean allInactive = true;
            boolean allOff = true;
            final boolean stateChanged;
            synchronized (mSyncRoot) {
                final int index = mDisplayStates.indexOfKey(displayId);
                if (index > -1) {
                    final int currentState = mDisplayStates.valueAt(index);
                    stateChanged = state != currentState;
                    if (stateChanged) {
                        final int size = mDisplayStates.size();
                        for (int i = 0; i < size; i++) {
                            final int displayState = i == index ? state : mDisplayStates.valueAt(i);
                            if (displayState != Display.STATE_OFF) {
                                allOff = false;
                            }
                            if (Display.isActiveState(displayState)) {
                                allInactive = false;
                            }
                            if (!allOff && !allInactive) {
                                break;
                            }
                        }
                    }
                } else {
                    stateChanged = false;
                }
            }

            // The order of operations is important for legacy reasons.
            if (state == Display.STATE_OFF) {
                requestDisplayStateInternal(displayId, state, brightness, sdrBrightness);
            }

            if (stateChanged) {
                mDisplayPowerCallbacks.onDisplayStateChange(allInactive, allOff);
            }

            if (state != Display.STATE_OFF) {
                requestDisplayStateInternal(displayId, state, brightness, sdrBrightness);
            }
        }
    };

    /**
     * Used to inform {@link com.android.server.power.PowerManagerService} of changes to display
     * state.
     */
    private DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks;

    /** The {@link Handler} used by all {@link DisplayPowerController}s. */
    private Handler mPowerHandler;

    // A map from LogicalDisplay ID to display power state.
    @GuardedBy("mSyncRoot")
    private final SparseIntArray mDisplayStates = new SparseIntArray();

    // A map from LogicalDisplay ID to display brightness.
    @GuardedBy("mSyncRoot")
    private final SparseArray<BrightnessPair> mDisplayBrightnesses = new SparseArray<>();

    // Set to true when there are pending display changes that have yet to be applied
    // to the surface flinger state.
    private boolean mPendingTraversal;

    // The Wifi display adapter, or null if not registered.
    private WifiDisplayAdapter mWifiDisplayAdapter;

    // The number of active wifi display scan requests.
    private int mWifiDisplayScanRequestCount;

    // The virtual display adapter, or null if not registered.
    private VirtualDisplayAdapter mVirtualDisplayAdapter;

    // The User ID of the current user
    private @UserIdInt int mCurrentUserId;

    // The stable device screen height and width. These are not tied to a specific display, even
    // the default display, because they need to be stable over the course of the device's entire
    // life, even if the default display changes (e.g. a new monitor is plugged into a PC-like
    // device).
    private Point mStableDisplaySize = new Point();

    // Whether the system has finished booting or not.
    private boolean mSystemReady;

    // The top inset of the default display.
    // This gets persisted so that the boot animation knows how to transition from the display's
    // full size to the size configured by the user. Right now we only persist and animate the top
    // inset, but theoretically we could do it for all of them.
    private int mDefaultDisplayTopInset;

    // Viewports of the default display and the display that should receive touch
    // input from an external source.  Used by the input system.
    @GuardedBy("mSyncRoot")
    private final ArrayList<DisplayViewport> mViewports = new ArrayList<>();

    // Persistent data store for all internal settings maintained by the display manager service.
    private final PersistentDataStore mPersistentDataStore = new PersistentDataStore();

    // Temporary callback list, used when sending display events to applications.
    // May be used outside of the lock but only on the handler thread.
    private final ArrayList<CallbackRecord> mTempCallbacks = new ArrayList<CallbackRecord>();

    // Temporary viewports, used when sending new viewport information to the
    // input system.  May be used outside of the lock but only on the handler thread.
    private final ArrayList<DisplayViewport> mTempViewports = new ArrayList<>();

    // The default color mode for default displays. Overrides the usual
    // Display.Display.COLOR_MODE_DEFAULT for displays with the
    // DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY flag set.
    private final int mDefaultDisplayDefaultColorMode;

    // Lists of UIDs that are present on the displays. Maps displayId -> array of UIDs.
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();

    private final Injector mInjector;

    // The minimum brightness curve, which guarantess that any brightness curve that dips below it
    // is rejected by the system.
    private final Curve mMinimumBrightnessCurve;
    private final Spline mMinimumBrightnessSpline;
    private final ColorSpace mWideColorSpace;

    private SensorManager mSensorManager;
    private BrightnessTracker mBrightnessTracker;


    // Whether minimal post processing is allowed by the user.
    @GuardedBy("mSyncRoot")
    private boolean mMinimalPostProcessingAllowed;

    // Receives notifications about changes to Settings.
    private SettingsObserver mSettingsObserver;

    private final boolean mAllowNonNativeRefreshRateOverride;

    private final BrightnessSynchronizer mBrightnessSynchronizer;

    /**
     * Applications use {@link android.view.Display#getRefreshRate} and
     * {@link android.view.Display.Mode#getRefreshRate} to know what is the display refresh rate.
     * Starting with Android S, the platform might throttle down applications frame rate to a
     * divisor of the refresh rate if it is more preferable (for example if the application called
     * to {@link android.view.Surface#setFrameRate}).
     * Applications will experience {@link android.view.Choreographer#postFrameCallback} callbacks
     * and backpressure at the throttled frame rate.
     *
     * {@link android.view.Display#getRefreshRate} will always return the application frame rate
     * and not the physical display refresh rate to allow applications to do frame pacing correctly.
     *
     * {@link android.view.Display.Mode#getRefreshRate} will return the application frame rate if
     * compiled to a previous release and starting with Android S it will return the physical
     * display refresh rate.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    static final long DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE = 170503758L;

    public DisplayManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    DisplayManagerService(Context context, Injector injector) {
        super(context);
        mInjector = injector;
        mContext = context;
        mHandler = new DisplayManagerHandler(DisplayThread.get().getLooper());
        mUiHandler = UiThread.getHandler();
        mDisplayDeviceRepo = new DisplayDeviceRepository(mSyncRoot, mPersistentDataStore);
        mLogicalDisplayMapper = new LogicalDisplayMapper(mContext, mDisplayDeviceRepo,
                new LogicalDisplayListener(), mSyncRoot, mHandler);
        mDisplayModeDirector = new DisplayModeDirector(context, mHandler);
        mBrightnessSynchronizer = new BrightnessSynchronizer(mContext);
        Resources resources = mContext.getResources();
        mDefaultDisplayDefaultColorMode = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultDisplayDefaultColorMode);
        mDefaultDisplayTopInset = SystemProperties.getInt(PROP_DEFAULT_DISPLAY_TOP_INSET, -1);
        float[] lux = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_minimumBrightnessCurveLux));
        float[] nits = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_minimumBrightnessCurveNits));
        mMinimumBrightnessCurve = new Curve(lux, nits);
        mMinimumBrightnessSpline = Spline.createSpline(lux, nits);

        mCurrentUserId = UserHandle.USER_SYSTEM;
        ColorSpace[] colorSpaces = SurfaceControl.getCompositionColorSpaces();
        mWideColorSpace = colorSpaces[1];
        mAllowNonNativeRefreshRateOverride = mInjector.getAllowNonNativeRefreshRateOverride();

        mSystemReady = false;
    }

    public void setupSchedulerPolicies() {
        // android.display and android.anim is critical to user experience and we should make sure
        // it is not in the default foregroup groups, add it to top-app to make sure it uses all
        // the cores and scheduling settings for top-app when it runs.
        Process.setThreadGroupAndCpuset(DisplayThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
        Process.setThreadGroupAndCpuset(AnimationThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
        Process.setThreadGroupAndCpuset(SurfaceAnimationThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
    }

    @Override
    public void onStart() {
        // We need to pre-load the persistent data store so it's ready before the default display
        // adapter is up so that we have it's configuration. We could load it lazily, but since
        // we're going to have to read it in eventually we may as well do it here rather than after
        // we've waited for the display to register itself with us.
        synchronized (mSyncRoot) {
            mPersistentDataStore.loadIfNeeded();
            loadStableDisplayValuesLocked();
        }
        mHandler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS);

        // If there was a runtime restart then we may have stale caches left around, so we need to
        // make sure to invalidate them upon every start.
        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();

        publishBinderService(Context.DISPLAY_SERVICE, new BinderService(),
                true /*allowIsolated*/);
        publishLocalService(DisplayManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_WAIT_FOR_DEFAULT_DISPLAY) {
            synchronized (mSyncRoot) {
                long timeout = SystemClock.uptimeMillis()
                        + mInjector.getDefaultDisplayDelayTimeout();
                while (mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY) == null
                        || mVirtualDisplayAdapter == null) {
                    long delay = timeout - SystemClock.uptimeMillis();
                    if (delay <= 0) {
                        throw new RuntimeException("Timeout waiting for default display "
                                + "to be initialized. DefaultDisplay="
                                + mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY)
                                + ", mVirtualDisplayAdapter=" + mVirtualDisplayAdapter);
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "waitForDefaultDisplay: waiting, timeout=" + delay);
                    }
                    try {
                        mSyncRoot.wait(delay);
                    } catch (InterruptedException ex) {
                    }
                }
            }
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mDisplayModeDirector.onBootCompleted();
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        final int newUserId = to.getUserIdentifier();
        final int userSerial = getUserManager().getUserSerialNumber(newUserId);
        synchronized (mSyncRoot) {
            final DisplayPowerController displayPowerController = mDisplayPowerControllers.get(
                    Display.DEFAULT_DISPLAY);
            if (mCurrentUserId != newUserId) {
                mCurrentUserId = newUserId;
                BrightnessConfiguration config =
                        mPersistentDataStore.getBrightnessConfiguration(userSerial);
                displayPowerController.setBrightnessConfiguration(config);
                handleSettingsChange();
            }
            displayPowerController.onSwitchUser(newUserId);
        }
    }

    // TODO: Use dependencies or a boot phase
    public void windowManagerAndInputReady() {
        synchronized (mSyncRoot) {
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);

            DeviceStateManager deviceStateManager =
                    mContext.getSystemService(DeviceStateManager.class);
            deviceStateManager.registerCallback(new HandlerExecutor(mHandler),
                    new DeviceStateListener());

            scheduleTraversalLocked(false);
        }
    }

    /**
     * Called when the system is ready to go.
     */
    public void systemReady(boolean safeMode, boolean onlyCore) {
        synchronized (mSyncRoot) {
            mSafeMode = safeMode;
            mOnlyCore = onlyCore;
            mSystemReady = true;
            // Just in case the top inset changed before the system was ready. At this point, any
            // relevant configuration should be in place.
            recordTopInsetLocked(mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY));

            updateSettingsLocked();

            updateUserDisabledHdrTypesFromSettingsLocked();
        }

        mDisplayModeDirector.setDesiredDisplayModeSpecsListener(
                new DesiredDisplayModeSpecsObserver());
        mDisplayModeDirector.start(mSensorManager);

        mHandler.sendEmptyMessage(MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS);

        mSettingsObserver = new SettingsObserver();

        mBrightnessSynchronizer.startSynchronizing();
    }

    @VisibleForTesting
    Handler getDisplayHandler() {
        return mHandler;
    }

    @VisibleForTesting
    DisplayDeviceRepository getDisplayDeviceRepository() {
        return mDisplayDeviceRepo;
    }

    private void loadStableDisplayValuesLocked() {
        final Point size = mPersistentDataStore.getStableDisplaySize();
        if (size.x > 0 && size.y > 0) {
            // Just set these values directly so we don't write the display persistent data again
            // unnecessarily
            mStableDisplaySize.set(size.x, size.y);
        } else {
            final Resources res = mContext.getResources();
            final int width = res.getInteger(
                    com.android.internal.R.integer.config_stableDeviceDisplayWidth);
            final int height = res.getInteger(
                    com.android.internal.R.integer.config_stableDeviceDisplayHeight);
            if (width > 0 && height > 0) {
                setStableDisplaySizeLocked(width, height);
            }
        }
    }

    private Point getStableDisplaySizeInternal() {
        Point r = new Point();
        synchronized (mSyncRoot) {
            if (mStableDisplaySize.x > 0 && mStableDisplaySize.y > 0) {
                r.set(mStableDisplaySize.x, mStableDisplaySize.y);
            }
        }
        return r;
    }

    private void registerDisplayTransactionListenerInternal(
            DisplayTransactionListener listener) {
        // List is self-synchronized copy-on-write.
        mDisplayTransactionListeners.add(listener);
    }

    private void unregisterDisplayTransactionListenerInternal(
            DisplayTransactionListener listener) {
        // List is self-synchronized copy-on-write.
        mDisplayTransactionListeners.remove(listener);
    }

    @VisibleForTesting
    void setDisplayInfoOverrideFromWindowManagerInternal(int displayId, DisplayInfo info) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                if (display.setDisplayInfoOverrideFromWindowManagerLocked(info)) {
                    handleLogicalDisplayChangedLocked(display);
                    scheduleTraversalLocked(false);
                }
            }
        }
    }

    /**
     * @see DisplayManagerInternal#getNonOverrideDisplayInfo(int, DisplayInfo)
     */
    private void getNonOverrideDisplayInfoInternal(int displayId, DisplayInfo outInfo) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                display.getNonOverrideDisplayInfoLocked(outInfo);
            }
        }
    }

    @VisibleForTesting
    void performTraversalInternal(SurfaceControl.Transaction t) {
        synchronized (mSyncRoot) {
            if (!mPendingTraversal) {
                return;
            }
            mPendingTraversal = false;

            performTraversalLocked(t);
        }

        // List is self-synchronized copy-on-write.
        for (DisplayTransactionListener listener : mDisplayTransactionListeners) {
            listener.onDisplayTransaction(t);
        }
    }

    private float clampBrightness(int displayState, float brightnessState) {
        if (displayState == Display.STATE_OFF) {
            brightnessState = PowerManager.BRIGHTNESS_OFF_FLOAT;
        } else if (brightnessState != PowerManager.BRIGHTNESS_OFF_FLOAT
                && brightnessState < PowerManager.BRIGHTNESS_MIN) {
            brightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        } else if (brightnessState > PowerManager.BRIGHTNESS_MAX) {
            brightnessState = PowerManager.BRIGHTNESS_MAX;
        }
        return brightnessState;
    }

    private void requestDisplayStateInternal(int displayId, int state, float brightnessState,
            float sdrBrightnessState) {
        if (state == Display.STATE_UNKNOWN) {
            state = Display.STATE_ON;
        }

        brightnessState = clampBrightness(state, brightnessState);
        sdrBrightnessState = clampBrightness(state, sdrBrightnessState);

        // Update the display state within the lock.
        // Note that we do not need to schedule traversals here although it
        // may happen as a side-effect of displays changing state.
        final Runnable runnable;
        final String traceMessage;
        synchronized (mSyncRoot) {
            final int index = mDisplayStates.indexOfKey(displayId);

            final BrightnessPair brightnessPair =
                    index < 0 ? null : mDisplayBrightnesses.valueAt(index);
            if (index < 0 || (mDisplayStates.valueAt(index) == state
                    && brightnessPair.brightness == brightnessState
                    && brightnessPair.sdrBrightness == sdrBrightnessState)) {
                return; // Display no longer exists or no change.
            }

            traceMessage = "requestDisplayStateInternal("
                    + displayId + ", "
                    + Display.stateToString(state)
                    + ", brightness=" + brightnessState
                    + ", sdrBrightness=" + sdrBrightnessState + ")";
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, traceMessage, displayId);

            mDisplayStates.setValueAt(index, state);
            brightnessPair.brightness = brightnessState;
            brightnessPair.sdrBrightness = sdrBrightnessState;
            runnable = updateDisplayStateLocked(mLogicalDisplayMapper.getDisplayLocked(displayId)
                    .getPrimaryDisplayDeviceLocked());
        }

        // Setting the display power state can take hundreds of milliseconds
        // to complete so we defer the most expensive part of the work until
        // after we have exited the critical section to avoid blocking other
        // threads for a long time.
        if (runnable != null) {
            runnable.run();
        }
        Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, traceMessage, displayId);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(mHandler);

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(
                        Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED), false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            handleSettingsChange();
        }
    }

    private void handleSettingsChange() {
        synchronized (mSyncRoot) {
            updateSettingsLocked();
            scheduleTraversalLocked(false);
        }
    }

    private void updateSettingsLocked() {
        mMinimalPostProcessingAllowed = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED, 1, UserHandle.USER_CURRENT) != 0;
    }

    private void updateUserDisabledHdrTypesFromSettingsLocked() {
        mAreUserDisabledHdrTypesAllowed = (Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
                1) != 0);

        String userDisabledHdrTypes = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.USER_DISABLED_HDR_FORMATS);

        if (userDisabledHdrTypes != null) {
            try {
                String[] userDisabledHdrTypeStrings =
                        TextUtils.split(userDisabledHdrTypes, ",");
                mUserDisabledHdrTypes = new int[userDisabledHdrTypeStrings.length];
                for (int i = 0; i < userDisabledHdrTypeStrings.length; i++) {
                    mUserDisabledHdrTypes[i] = Integer.parseInt(userDisabledHdrTypeStrings[i]);
                }
            } catch (NumberFormatException e) {
                Slog.e(TAG,
                        "Failed to parse USER_DISABLED_HDR_FORMATS. "
                                + "Clearing the setting.", e);
                clearUserDisabledHdrTypesLocked();
            }
        } else {
            clearUserDisabledHdrTypesLocked();
        }
    }

    private void clearUserDisabledHdrTypesLocked() {
        mUserDisabledHdrTypes = new int[]{};
        synchronized (mSyncRoot) {
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.USER_DISABLED_HDR_FORMATS, "");
        }
    }

    private DisplayInfo getDisplayInfoForFrameRateOverride(DisplayEventReceiver.FrameRateOverride[]
            frameRateOverrides, DisplayInfo info, int callingUid) {
        float frameRateHz = 0;
        for (DisplayEventReceiver.FrameRateOverride frameRateOverride : frameRateOverrides) {
            if (frameRateOverride.uid == callingUid) {
                frameRateHz = frameRateOverride.frameRateHz;
                break;
            }
        }
        if (frameRateHz == 0) {
            return info;
        }

        // Override the refresh rate only if it is a divider of the current
        // refresh rate. This calculation needs to be in sync with the native code
        // in RefreshRateConfigs::getRefreshRateDividerForUid
        Display.Mode currentMode = info.getMode();
        float numPeriods = currentMode.getRefreshRate() / frameRateHz;
        float numPeriodsRound = Math.round(numPeriods);
        if (Math.abs(numPeriods - numPeriodsRound) > THRESHOLD_FOR_REFRESH_RATES_DIVIDERS) {
            return info;
        }
        frameRateHz = currentMode.getRefreshRate() / numPeriodsRound;

        DisplayInfo overriddenInfo = new DisplayInfo();
        overriddenInfo.copyFrom(info);
        for (Display.Mode mode : info.supportedModes) {
            if (!mode.equalsExceptRefreshRate(currentMode)) {
                continue;
            }

            if (mode.getRefreshRate() >= frameRateHz - THRESHOLD_FOR_REFRESH_RATES_DIVIDERS
                    && mode.getRefreshRate()
                    <= frameRateHz + THRESHOLD_FOR_REFRESH_RATES_DIVIDERS) {
                if (DEBUG) {
                    Slog.d(TAG, "found matching modeId " + mode.getModeId());
                }
                overriddenInfo.refreshRateOverride = mode.getRefreshRate();

                if (!CompatChanges.isChangeEnabled(DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE,
                        callingUid)) {
                    overriddenInfo.modeId = mode.getModeId();
                }
                return overriddenInfo;
            }
        }

        if (mAllowNonNativeRefreshRateOverride) {
            overriddenInfo.refreshRateOverride = frameRateHz;
            if (!CompatChanges.isChangeEnabled(DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE,
                    callingUid)) {
                overriddenInfo.supportedModes = Arrays.copyOf(info.supportedModes,
                        info.supportedModes.length + 1);
                overriddenInfo.supportedModes[overriddenInfo.supportedModes.length - 1] =
                        new Display.Mode(Display.DISPLAY_MODE_ID_FOR_FRAME_RATE_OVERRIDE,
                                currentMode.getPhysicalWidth(), currentMode.getPhysicalHeight(),
                                overriddenInfo.refreshRateOverride);
                overriddenInfo.modeId =
                        overriddenInfo.supportedModes[overriddenInfo.supportedModes.length - 1]
                                .getModeId();
            }
            return overriddenInfo;
        }

        return info;
    }

    private DisplayInfo getDisplayInfoInternal(int displayId, int callingUid) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayInfo info =
                        getDisplayInfoForFrameRateOverride(display.getFrameRateOverrides(),
                                display.getDisplayInfoLocked(), callingUid);
                if (info.hasAccess(callingUid)
                        || isUidPresentOnDisplayInternal(callingUid, displayId)) {
                    return info;
                }
            }
            return null;
        }
    }

    private void registerCallbackInternal(IDisplayManagerCallback callback, int callingPid,
            int callingUid, @EventsMask long eventsMask) {
        synchronized (mSyncRoot) {
            CallbackRecord record = mCallbacks.get(callingPid);

            if (record != null) {
                record.updateEventsMask(eventsMask);
                return;
            }

            record = new CallbackRecord(callingPid, callingUid, callback, eventsMask);
            try {
                IBinder binder = callback.asBinder();
                binder.linkToDeath(record, 0);
            } catch (RemoteException ex) {
                // give up
                throw new RuntimeException(ex);
            }

            mCallbacks.put(callingPid, record);
        }
    }

    private void onCallbackDied(CallbackRecord record) {
        synchronized (mSyncRoot) {
            mCallbacks.remove(record.mPid);
            stopWifiDisplayScanLocked(record);
        }
    }

    private void startWifiDisplayScanInternal(int callingPid) {
        synchronized (mSyncRoot) {
            CallbackRecord record = mCallbacks.get(callingPid);
            if (record == null) {
                throw new IllegalStateException("The calling process has not "
                        + "registered an IDisplayManagerCallback.");
            }
            startWifiDisplayScanLocked(record);
        }
    }

    private void startWifiDisplayScanLocked(CallbackRecord record) {
        if (!record.mWifiDisplayScanRequested) {
            record.mWifiDisplayScanRequested = true;
            if (mWifiDisplayScanRequestCount++ == 0) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestStartScanLocked();
                }
            }
        }
    }

    private void stopWifiDisplayScanInternal(int callingPid) {
        synchronized (mSyncRoot) {
            CallbackRecord record = mCallbacks.get(callingPid);
            if (record == null) {
                throw new IllegalStateException("The calling process has not "
                        + "registered an IDisplayManagerCallback.");
            }
            stopWifiDisplayScanLocked(record);
        }
    }

    private void stopWifiDisplayScanLocked(CallbackRecord record) {
        if (record.mWifiDisplayScanRequested) {
            record.mWifiDisplayScanRequested = false;
            if (--mWifiDisplayScanRequestCount == 0) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestStopScanLocked();
                }
            } else if (mWifiDisplayScanRequestCount < 0) {
                Slog.wtf(TAG, "mWifiDisplayScanRequestCount became negative: "
                        + mWifiDisplayScanRequestCount);
                mWifiDisplayScanRequestCount = 0;
            }
        }
    }

    private void connectWifiDisplayInternal(String address) {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestConnectLocked(address);
            }
        }
    }

    private void pauseWifiDisplayInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestPauseLocked();
            }
        }
    }

    private void resumeWifiDisplayInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestResumeLocked();
            }
        }
    }

    private void disconnectWifiDisplayInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestDisconnectLocked();
            }
        }
    }

    private void renameWifiDisplayInternal(String address, String alias) {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestRenameLocked(address, alias);
            }
        }
    }

    private void forgetWifiDisplayInternal(String address) {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestForgetLocked(address);
            }
        }
    }

    private WifiDisplayStatus getWifiDisplayStatusInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                return mWifiDisplayAdapter.getWifiDisplayStatusLocked();
            }
            return new WifiDisplayStatus();
        }
    }

    private void setUserDisabledHdrTypesInternal(int[] userDisabledHdrTypes) {
        synchronized (mSyncRoot) {
            if (userDisabledHdrTypes == null) {
                Slog.e(TAG, "Null is not an expected argument to "
                        + "setUserDisabledHdrTypesInternal");
                return;
            }
            Arrays.sort(userDisabledHdrTypes);
            if (Arrays.equals(mUserDisabledHdrTypes, userDisabledHdrTypes)) {
                return;
            }
            String userDisabledFormatsString = "";
            if (userDisabledHdrTypes.length != 0) {
                userDisabledFormatsString = TextUtils.join(",",
                        Arrays.stream(userDisabledHdrTypes).boxed().toArray());
            }
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.USER_DISABLED_HDR_FORMATS, userDisabledFormatsString);
            mUserDisabledHdrTypes = userDisabledHdrTypes;
            if (!mAreUserDisabledHdrTypesAllowed) {
                mLogicalDisplayMapper.forEachLocked(
                        display -> {
                            display.setUserDisabledHdrTypes(userDisabledHdrTypes);
                            handleLogicalDisplayChangedLocked(display);
                        });
            }
        }
    }

    private void setAreUserDisabledHdrTypesAllowedInternal(
            boolean areUserDisabledHdrTypesAllowed) {
        synchronized (mSyncRoot) {
            if (mAreUserDisabledHdrTypesAllowed == areUserDisabledHdrTypesAllowed) {
                return;
            }
            mAreUserDisabledHdrTypesAllowed = areUserDisabledHdrTypesAllowed;
            if (mUserDisabledHdrTypes.length == 0) {
                return;
            }
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
                    areUserDisabledHdrTypesAllowed ? 1 : 0);
            int userDisabledHdrTypes[] = {};
            if (!mAreUserDisabledHdrTypesAllowed) {
                userDisabledHdrTypes = mUserDisabledHdrTypes;
            }
            int[] finalUserDisabledHdrTypes = userDisabledHdrTypes;
            mLogicalDisplayMapper.forEachLocked(
                    display -> {
                        display.setUserDisabledHdrTypes(finalUserDisabledHdrTypes);
                        handleLogicalDisplayChangedLocked(display);
                    });
        }
    }

    private void requestColorModeInternal(int displayId, int colorMode) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null &&
                    display.getRequestedColorModeLocked() != colorMode) {
                display.setRequestedColorModeLocked(colorMode);
                scheduleTraversalLocked(false);
            }
        }
    }

    private int createVirtualDisplayInternal(IVirtualDisplayCallback callback,
            IMediaProjection projection, int callingUid, String packageName, Surface surface,
            int flags, VirtualDisplayConfig virtualDisplayConfig) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                Slog.w(TAG, "Rejecting request to create private virtual display "
                        + "because the virtual display adapter is not available.");
                return -1;
            }

            DisplayDevice device = mVirtualDisplayAdapter.createVirtualDisplayLocked(
                    callback, projection, callingUid, packageName, surface, flags,
                    virtualDisplayConfig);
            if (device == null) {
                return -1;
            }

            // DisplayDevice events are handled manually for Virtual Displays.
            // TODO: multi-display Fix this so that generic add/remove events are not handled in a
            // different code path for virtual displays.  Currently this happens so that we can
            // return a valid display ID synchronously upon successful Virtual Display creation.
            // This code can run on any binder thread, while onDisplayDeviceAdded() callbacks are
            // called on the DisplayThread (which we don't want to wait for?).
            // One option would be to actually wait here on the binder thread
            // to be notified when the virtual display is created (or failed).
            mDisplayDeviceRepo.onDisplayDeviceEvent(device,
                    DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED);

            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display != null) {
                return display.getDisplayIdLocked();
            }

            // Something weird happened and the logical display was not created.
            Slog.w(TAG, "Rejecting request to create virtual display "
                    + "because the logical display was not created.");
            mVirtualDisplayAdapter.releaseVirtualDisplayLocked(callback.asBinder());
            mDisplayDeviceRepo.onDisplayDeviceEvent(device,
                    DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED);
        }
        return -1;
    }

    private void resizeVirtualDisplayInternal(IBinder appToken,
            int width, int height, int densityDpi) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            mVirtualDisplayAdapter.resizeVirtualDisplayLocked(appToken, width, height, densityDpi);
        }
    }

    private void setVirtualDisplaySurfaceInternal(IBinder appToken, Surface surface) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            mVirtualDisplayAdapter.setVirtualDisplaySurfaceLocked(appToken, surface);
        }
    }

    private void releaseVirtualDisplayInternal(IBinder appToken) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            DisplayDevice device =
                    mVirtualDisplayAdapter.releaseVirtualDisplayLocked(appToken);
            if (device != null) {
                // TODO: multi-display - handle virtual displays the same as other display adapters.
                mDisplayDeviceRepo.onDisplayDeviceEvent(device,
                        DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }
    }

    private void setVirtualDisplayStateInternal(IBinder appToken, boolean isOn) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            mVirtualDisplayAdapter.setVirtualDisplayStateLocked(appToken, isOn);
        }
    }

    private void registerDefaultDisplayAdapters() {
        // Register default display adapters.
        synchronized (mSyncRoot) {
            // main display adapter
            registerDisplayAdapterLocked(new LocalDisplayAdapter(
                    mSyncRoot, mContext, mHandler, mDisplayDeviceRepo));

            // Standalone VR devices rely on a virtual display as their primary display for
            // 2D UI. We register virtual display adapter along side the main display adapter
            // here so that it is ready by the time the system sends the home Intent for
            // early apps like SetupWizard/Launcher. In particular, SUW is displayed using
            // the virtual display inside VR before any VR-specific apps even run.
            mVirtualDisplayAdapter = mInjector.getVirtualDisplayAdapter(mSyncRoot, mContext,
                    mHandler, mDisplayDeviceRepo);
            if (mVirtualDisplayAdapter != null) {
                registerDisplayAdapterLocked(mVirtualDisplayAdapter);
            }
        }
    }

    private void registerAdditionalDisplayAdapters() {
        synchronized (mSyncRoot) {
            if (shouldRegisterNonEssentialDisplayAdaptersLocked()) {
                registerOverlayDisplayAdapterLocked();
                registerWifiDisplayAdapterLocked();
            }
        }
    }

    private void registerOverlayDisplayAdapterLocked() {
        registerDisplayAdapterLocked(new OverlayDisplayAdapter(
                mSyncRoot, mContext, mHandler, mDisplayDeviceRepo, mUiHandler));
    }

    private void registerWifiDisplayAdapterLocked() {
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWifiDisplay)
                || SystemProperties.getInt(FORCE_WIFI_DISPLAY_ENABLE, -1) == 1) {
            mWifiDisplayAdapter = new WifiDisplayAdapter(
                    mSyncRoot, mContext, mHandler, mDisplayDeviceRepo,
                    mPersistentDataStore);
            registerDisplayAdapterLocked(mWifiDisplayAdapter);
        }
    }

    private boolean shouldRegisterNonEssentialDisplayAdaptersLocked() {
        // In safe mode, we disable non-essential display adapters to give the user
        // an opportunity to fix broken settings or other problems that might affect
        // system stability.
        // In only-core mode, we disable non-essential display adapters to minimize
        // the number of dependencies that are started while in this mode and to
        // prevent problems that might occur due to the device being encrypted.
        return !mSafeMode && !mOnlyCore;
    }

    private void registerDisplayAdapterLocked(DisplayAdapter adapter) {
        mDisplayAdapters.add(adapter);
        adapter.registerLocked();
    }

    private void handleLogicalDisplayAddedLocked(LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        final int displayId = display.getDisplayIdLocked();
        final boolean isDefault = displayId == Display.DEFAULT_DISPLAY;
        configureColorModeLocked(display, device);
        if (!mAreUserDisabledHdrTypesAllowed) {
            display.setUserDisabledHdrTypes(mUserDisabledHdrTypes);
        }
        if (isDefault) {
            recordStableDisplayStatsIfNeededLocked(display);
            recordTopInsetLocked(display);
        }
        addDisplayPowerControllerLocked(display);
        mDisplayStates.append(displayId, Display.STATE_UNKNOWN);

        final float brightnessDefault = display.getDisplayInfoLocked().brightnessDefault;
        mDisplayBrightnesses.append(displayId,
                new BrightnessPair(brightnessDefault, brightnessDefault));

        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();

        // Wake up waitForDefaultDisplay.
        if (isDefault) {
            mSyncRoot.notifyAll();
        }

        sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);

        Runnable work = updateDisplayStateLocked(device);
        if (work != null) {
            work.run();
        }
        scheduleTraversalLocked(false);
    }

    private void handleLogicalDisplayChangedLocked(@NonNull LogicalDisplay display) {
        updateViewportPowerStateLocked(display);

        final int displayId = display.getDisplayIdLocked();
        if (displayId == Display.DEFAULT_DISPLAY) {
            recordTopInsetLocked(display);
        }
        // We don't bother invalidating the display info caches here because any changes to the
        // display info will trigger a cache invalidation inside of LogicalDisplay before we hit
        // this point.
        sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        scheduleTraversalLocked(false);
        mPersistentDataStore.saveIfNeeded();

        DisplayPowerController dpc = mDisplayPowerControllers.get(displayId);
        if (dpc != null) {
            dpc.onDisplayChanged();
        }
    }

    private void handleLogicalDisplayFrameRateOverridesChangedLocked(
            @NonNull LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        // We don't bother invalidating the display info caches here because any changes to the
        // display info will trigger a cache invalidation inside of LogicalDisplay before we hit
        // this point.
        sendDisplayEventFrameRateOverrideLocked(displayId);
        scheduleTraversalLocked(false);
    }

    private void handleLogicalDisplayRemovedLocked(@NonNull LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        final DisplayPowerController dpc = mDisplayPowerControllers.removeReturnOld(displayId);
        if (dpc != null) {
            dpc.stop();
        }
        mDisplayStates.delete(displayId);
        mDisplayBrightnesses.delete(displayId);
        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();
        sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
        scheduleTraversalLocked(false);
    }

    private void handleLogicalDisplaySwappedLocked(@NonNull LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        final Runnable work = updateDisplayStateLocked(device);
        if (work != null) {
            mHandler.post(work);
        }
        handleLogicalDisplayChangedLocked(display);
    }

    private void handleLogicalDisplayDeviceStateTransitionLocked(@NonNull LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        final DisplayPowerController dpc = mDisplayPowerControllers.get(displayId);
        if (dpc != null) {
            dpc.onDeviceStateTransition();
        }
    }

    private Runnable updateDisplayStateLocked(DisplayDevice device) {
        // Blank or unblank the display immediately to match the state requested
        // by the display power controller (if known).
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if ((info.flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display == null) {
                return null;
            }
            final int displayId = display.getDisplayIdLocked();
            final int state = mDisplayStates.get(displayId);

            // Only send a request for display state if display state has already been initialized.
            if (state != Display.STATE_UNKNOWN) {
                final BrightnessPair brightnessPair = mDisplayBrightnesses.get(displayId);
                return device.requestDisplayStateLocked(state, brightnessPair.brightness,
                        brightnessPair.sdrBrightness);
            }
        }
        return null;
    }

    private void configureColorModeLocked(LogicalDisplay display, DisplayDevice device) {
        if (display.getPrimaryDisplayDeviceLocked() == device) {
            int colorMode = mPersistentDataStore.getColorMode(device);
            if (colorMode == Display.COLOR_MODE_INVALID) {
                if ((device.getDisplayDeviceInfoLocked().flags
                     & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0) {
                    colorMode = mDefaultDisplayDefaultColorMode;
                } else {
                    colorMode = Display.COLOR_MODE_DEFAULT;
                }
            }
            display.setRequestedColorModeLocked(colorMode);
        }
    }

    // If we've never recorded stable device stats for this device before and they aren't
    // explicitly configured, go ahead and record the stable device stats now based on the status
    // of the default display at first boot.
    private void recordStableDisplayStatsIfNeededLocked(LogicalDisplay d) {
        if (mStableDisplaySize.x <= 0 && mStableDisplaySize.y <= 0) {
            DisplayInfo info = d.getDisplayInfoLocked();
            setStableDisplaySizeLocked(info.getNaturalWidth(), info.getNaturalHeight());
        }
    }

    private void recordTopInsetLocked(@Nullable LogicalDisplay d) {
        // We must only persist the inset after boot has completed, otherwise we will end up
        // overwriting the persisted value before the masking flag has been loaded from the
        // resource overlay.
        if (!mSystemReady || d == null) {
            return;
        }
        int topInset = d.getInsets().top;
        if (topInset == mDefaultDisplayTopInset) {
            return;
        }
        mDefaultDisplayTopInset = topInset;
        SystemProperties.set(PROP_DEFAULT_DISPLAY_TOP_INSET, Integer.toString(topInset));
    }

    private void setStableDisplaySizeLocked(int width, int height) {
        mStableDisplaySize = new Point(width, height);
        try {
            mPersistentDataStore.setStableDisplaySize(mStableDisplaySize);
        } finally {
            mPersistentDataStore.saveIfNeeded();
        }
    }

    @VisibleForTesting
    Curve getMinimumBrightnessCurveInternal() {
        return mMinimumBrightnessCurve;
    }

    int getPreferredWideGamutColorSpaceIdInternal() {
        return mWideColorSpace.getId();
    }

    void setShouldAlwaysRespectAppRequestedModeInternal(boolean enabled) {
        mDisplayModeDirector.setShouldAlwaysRespectAppRequestedMode(enabled);
    }

    boolean shouldAlwaysRespectAppRequestedModeInternal() {
        return mDisplayModeDirector.shouldAlwaysRespectAppRequestedMode();
    }

    void setRefreshRateSwitchingTypeInternal(@DisplayManager.SwitchingType int newValue) {
        mDisplayModeDirector.setModeSwitchingType(newValue);
    }

    @DisplayManager.SwitchingType
    int getRefreshRateSwitchingTypeInternal() {
        return mDisplayModeDirector.getModeSwitchingType();
    }

    private void setBrightnessConfigurationForUserInternal(
            @Nullable BrightnessConfiguration c, @UserIdInt int userId,
            @Nullable String packageName) {
        validateBrightnessConfiguration(c);
        final int userSerial = getUserManager().getUserSerialNumber(userId);
        synchronized (mSyncRoot) {
            try {
                mPersistentDataStore.setBrightnessConfigurationForUser(c, userSerial,
                        packageName);
            } finally {
                mPersistentDataStore.saveIfNeeded();
            }
            if (userId == mCurrentUserId) {
                mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY).setBrightnessConfiguration(c);
            }
        }
    }

    @VisibleForTesting
    void validateBrightnessConfiguration(BrightnessConfiguration config) {
        if (config == null) {
            return;
        }
        if (isBrightnessConfigurationTooDark(config)) {
            throw new IllegalArgumentException("brightness curve is too dark");
        }
    }

    private boolean isBrightnessConfigurationTooDark(BrightnessConfiguration config) {
        Pair<float[], float[]> curve = config.getCurve();
        float[] lux = curve.first;
        float[] nits = curve.second;
        for (int i = 0; i < lux.length; i++) {
            if (nits[i] < mMinimumBrightnessSpline.interpolate(lux[i])) {
                return true;
            }
        }
        return false;
    }

    private void loadBrightnessConfiguration() {
        synchronized (mSyncRoot) {
            final int userSerial = getUserManager().getUserSerialNumber(mCurrentUserId);
            BrightnessConfiguration config =
                    mPersistentDataStore.getBrightnessConfiguration(userSerial);
            mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY).setBrightnessConfiguration(
                    config);
        }
    }

    private void performTraversalLocked(SurfaceControl.Transaction t) {
        // Clear all viewports before configuring displays so that we can keep
        // track of which ones we have configured.
        clearViewportsLocked();

        // Configure each display device.
        mLogicalDisplayMapper.forEachLocked((LogicalDisplay display) -> {
            final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
            if (device != null) {
                configureDisplayLocked(t, device);
                device.performTraversalLocked(t);
            }
        });

        // Tell the input system about these new viewports.
        if (mInputManagerInternal != null) {
            mHandler.sendEmptyMessage(MSG_UPDATE_VIEWPORT);
        }
    }

    private void setDisplayPropertiesInternal(int displayId, boolean hasContent,
            float requestedRefreshRate, int requestedModeId, float requestedMinRefreshRate,
            float requestedMaxRefreshRate, boolean preferMinimalPostProcessing,
            boolean inTraversal) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display == null) {
                return;
            }

            boolean shouldScheduleTraversal = false;

            if (display.hasContentLocked() != hasContent) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " hasContent flag changed: "
                            + "hasContent=" + hasContent + ", inTraversal=" + inTraversal);
                }

                display.setHasContentLocked(hasContent);
                shouldScheduleTraversal = true;
            }
            if (requestedModeId == 0 && requestedRefreshRate != 0) {
                // Scan supported modes returned by display.getInfo() to find a mode with the same
                // size as the default display mode but with the specified refresh rate instead.
                Display.Mode mode = display.getDisplayInfoLocked().findDefaultModeByRefreshRate(
                        requestedRefreshRate);
                if (mode != null) {
                    requestedModeId = mode.getModeId();
                } else {
                    Slog.e(TAG, "Couldn't find a mode for the requestedRefreshRate: "
                            + requestedRefreshRate + " on Display: " + displayId);
                }
            }
            mDisplayModeDirector.getAppRequestObserver().setAppRequest(
                    displayId, requestedModeId, requestedMinRefreshRate, requestedMaxRefreshRate);

            if (display.getDisplayInfoLocked().minimalPostProcessingSupported) {
                boolean mppRequest = mMinimalPostProcessingAllowed && preferMinimalPostProcessing;

                if (display.getRequestedMinimalPostProcessingLocked() != mppRequest) {
                    display.setRequestedMinimalPostProcessingLocked(mppRequest);
                    shouldScheduleTraversal = true;
                }
            }

            if (shouldScheduleTraversal) {
                scheduleTraversalLocked(inTraversal);
            }
        }
    }

    private void setDisplayOffsetsInternal(int displayId, int x, int y) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display == null) {
                return;
            }
            if (display.getDisplayOffsetXLocked() != x
                    || display.getDisplayOffsetYLocked() != y) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " burn-in offset set to ("
                            + x + ", " + y + ")");
                }
                display.setDisplayOffsetsLocked(x, y);
                scheduleTraversalLocked(false);
            }
        }
    }

    private void setDisplayScalingDisabledInternal(int displayId, boolean disable) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display == null) {
                return;
            }
            if (display.isDisplayScalingDisabled() != disable) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " content scaling disabled = " + disable);
                }
                display.setDisplayScalingDisabledLocked(disable);
                scheduleTraversalLocked(false);
            }
        }
    }

    // Updates the lists of UIDs that are present on displays.
    private void setDisplayAccessUIDsInternal(SparseArray<IntArray> newDisplayAccessUIDs) {
        synchronized (mSyncRoot) {
            mDisplayAccessUIDs.clear();
            for (int i = newDisplayAccessUIDs.size() - 1; i >= 0; i--) {
                mDisplayAccessUIDs.append(newDisplayAccessUIDs.keyAt(i),
                        newDisplayAccessUIDs.valueAt(i));
            }
        }
    }

    // Checks if provided UID's content is present on the display and UID has access to it.
    private boolean isUidPresentOnDisplayInternal(int uid, int displayId) {
        synchronized (mSyncRoot) {
            final IntArray displayUIDs = mDisplayAccessUIDs.get(displayId);
            return displayUIDs != null && displayUIDs.indexOf(uid) != -1;
        }
    }

    @Nullable
    private IBinder getDisplayToken(int displayId) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                if (device != null) {
                    return device.getDisplayTokenLocked();
                }
            }
        }

        return null;
    }

    private SurfaceControl.ScreenshotHardwareBuffer systemScreenshotInternal(int displayId) {
        synchronized (mSyncRoot) {
            final IBinder token = getDisplayToken(displayId);
            if (token == null) {
                return null;
            }
            final LogicalDisplay logicalDisplay = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (logicalDisplay == null) {
                return null;
            }

            final DisplayInfo displayInfo = logicalDisplay.getDisplayInfoLocked();
            final SurfaceControl.DisplayCaptureArgs captureArgs =
                    new SurfaceControl.DisplayCaptureArgs.Builder(token)
                            .setSize(displayInfo.getNaturalWidth(), displayInfo.getNaturalHeight())
                            .setUseIdentityTransform(true)
                            .setCaptureSecureLayers(true)
                            .setAllowProtected(true)
                            .build();
            return SurfaceControl.captureDisplay(captureArgs);
        }
    }

    private SurfaceControl.ScreenshotHardwareBuffer userScreenshotInternal(int displayId) {
        synchronized (mSyncRoot) {
            final IBinder token = getDisplayToken(displayId);
            if (token == null) {
                return null;
            }

            final SurfaceControl.DisplayCaptureArgs captureArgs =
                    new SurfaceControl.DisplayCaptureArgs.Builder(token)
                            .build();
            return SurfaceControl.captureDisplay(captureArgs);
        }
    }

    @VisibleForTesting
    DisplayedContentSamplingAttributes getDisplayedContentSamplingAttributesInternal(
            int displayId) {
        final IBinder token = getDisplayToken(displayId);
        if (token == null) {
            return null;
        }
        return SurfaceControl.getDisplayedContentSamplingAttributes(token);
    }

    @VisibleForTesting
    boolean setDisplayedContentSamplingEnabledInternal(
            int displayId, boolean enable, int componentMask, int maxFrames) {
        final IBinder token = getDisplayToken(displayId);
        if (token == null) {
            return false;
        }
        return SurfaceControl.setDisplayedContentSamplingEnabled(
                token, enable, componentMask, maxFrames);
    }

    @VisibleForTesting
    DisplayedContentSample getDisplayedContentSampleInternal(int displayId,
            long maxFrames, long timestamp) {
        final IBinder token = getDisplayToken(displayId);
        if (token == null) {
            return null;
        }
        return SurfaceControl.getDisplayedContentSample(token, maxFrames, timestamp);
    }

    void resetBrightnessConfiguration() {
        setBrightnessConfigurationForUserInternal(null, mContext.getUserId(),
                mContext.getPackageName());
    }

    void setAutoBrightnessLoggingEnabled(boolean enabled) {
        synchronized (mSyncRoot) {
            final DisplayPowerController displayPowerController = mDisplayPowerControllers.get(
                    Display.DEFAULT_DISPLAY);
            if (displayPowerController != null) {
                displayPowerController.setAutoBrightnessLoggingEnabled(enabled);
            }
        }
    }

    void setDisplayWhiteBalanceLoggingEnabled(boolean enabled) {
        synchronized (mSyncRoot) {
            final DisplayPowerController displayPowerController = mDisplayPowerControllers.get(
                    Display.DEFAULT_DISPLAY);
            if (displayPowerController != null) {
                displayPowerController.setDisplayWhiteBalanceLoggingEnabled(enabled);
            }
        }
    }

    void setDisplayModeDirectorLoggingEnabled(boolean enabled) {
        synchronized (mSyncRoot) {
            if (mDisplayModeDirector != null) {
                mDisplayModeDirector.setLoggingEnabled(enabled);
            }
        }
    }

    void setAmbientColorTemperatureOverride(float cct) {
        synchronized (mSyncRoot) {
            final DisplayPowerController displayPowerController = mDisplayPowerControllers.get(
                    Display.DEFAULT_DISPLAY);
            if (displayPowerController != null) {
                displayPowerController.setAmbientColorTemperatureOverride(cct);
            }
        }
    }

    private void clearViewportsLocked() {
        mViewports.clear();
    }

    private Optional<Integer> getViewportType(DisplayDeviceInfo info) {
        // Get the corresponding viewport type.
        switch (info.touch) {
            case DisplayDeviceInfo.TOUCH_INTERNAL:
                return Optional.of(VIEWPORT_INTERNAL);
            case DisplayDeviceInfo.TOUCH_EXTERNAL:
                return Optional.of(VIEWPORT_EXTERNAL);
            case DisplayDeviceInfo.TOUCH_VIRTUAL:
                if (!TextUtils.isEmpty(info.uniqueId)) {
                    return Optional.of(VIEWPORT_VIRTUAL);
                }
                // fallthrough
            default:
                Slog.w(TAG, "Display " + info + " does not support input device matching.");
        }
        return Optional.empty();
    }

    private void configureDisplayLocked(SurfaceControl.Transaction t, DisplayDevice device) {
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        final boolean ownContent = (info.flags & DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY) != 0;

        // Find the logical display that the display device is showing.
        // Certain displays only ever show their own content.
        LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
        if (!ownContent) {
            if (display != null && !display.hasContentLocked()) {
                // If the display does not have any content of its own, then
                // automatically mirror the requested logical display contents if possible.
                display = mLogicalDisplayMapper.getDisplayLocked(
                        device.getDisplayIdToMirrorLocked());
            }
            if (display == null) {
                display = mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY);
            }
        }

        // Apply the logical display configuration to the display device.
        if (display == null) {
            // TODO: no logical display for the device, blank it
            Slog.w(TAG, "Missing logical display to use for physical display device: "
                    + device.getDisplayDeviceInfoLocked());
            return;
        }
        display.configureDisplayLocked(t, device, info.state == Display.STATE_OFF);
        final Optional<Integer> viewportType = getViewportType(info);
        if (viewportType.isPresent()) {
            populateViewportLocked(viewportType.get(), display.getDisplayIdLocked(), device, info);
        }
    }

    /**
     * Get internal or external viewport. Create it if does not currently exist.
     * @param viewportType - either INTERNAL or EXTERNAL
     * @return the viewport with the requested type
     */
    private DisplayViewport getViewportLocked(int viewportType, String uniqueId) {
        if (viewportType != VIEWPORT_INTERNAL && viewportType != VIEWPORT_EXTERNAL
                && viewportType != VIEWPORT_VIRTUAL) {
            Slog.wtf(TAG, "Cannot call getViewportByTypeLocked for type "
                    + DisplayViewport.typeToString(viewportType));
            return null;
        }

        DisplayViewport viewport;
        final int count = mViewports.size();
        for (int i = 0; i < count; i++) {
            viewport = mViewports.get(i);
            if (viewport.type == viewportType && uniqueId.equals(viewport.uniqueId)) {
                return viewport;
            }
        }

        // Creates the viewport if none exists.
        viewport = new DisplayViewport();
        viewport.type = viewportType;
        viewport.uniqueId = uniqueId;
        mViewports.add(viewport);
        return viewport;
    }

    private void populateViewportLocked(int viewportType, int displayId, DisplayDevice device,
            DisplayDeviceInfo info) {
        final DisplayViewport viewport = getViewportLocked(viewportType, info.uniqueId);
        device.populateViewportLocked(viewport);
        viewport.valid = true;
        viewport.displayId = displayId;
        viewport.isActive = Display.isActiveState(info.state);
    }

    private void updateViewportPowerStateLocked(LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        final Optional<Integer> viewportType = getViewportType(info);
        if (viewportType.isPresent()) {
            for (DisplayViewport d : mViewports) {
                if (d.type == viewportType.get() && info.uniqueId.equals(d.uniqueId)) {
                    // Update display view port power state
                    d.isActive = Display.isActiveState(info.state);
                }
            }
            if (mInputManagerInternal != null) {
                mHandler.sendEmptyMessage(MSG_UPDATE_VIEWPORT);
            }
        }
    }

    private void sendDisplayEventLocked(int displayId, @DisplayEvent int event) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_EVENT, displayId, event);
        mHandler.sendMessage(msg);
    }

    private void sendDisplayGroupEvent(int groupId, int event) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_GROUP_EVENT, groupId, event);
        mHandler.sendMessage(msg);
    }

    private void sendDisplayEventFrameRateOverrideLocked(int displayId) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_EVENT_FRAME_RATE_OVERRIDE,
                displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        mHandler.sendMessage(msg);
    }

    // Requests that performTraversals be called at a
    // later time to apply changes to surfaces and displays.
    private void scheduleTraversalLocked(boolean inTraversal) {
        if (!mPendingTraversal && mWindowManagerInternal != null) {
            mPendingTraversal = true;
            if (!inTraversal) {
                mHandler.sendEmptyMessage(MSG_REQUEST_TRAVERSAL);
            }
        }
    }

    // Runs on Handler thread.
    // Delivers display event notifications to callbacks.
    private void deliverDisplayEvent(int displayId, ArraySet<Integer> uids,
            @DisplayEvent int event) {
        if (DEBUG) {
            Slog.d(TAG, "Delivering display event: displayId="
                    + displayId + ", event=" + event);
        }

        // Grab the lock and copy the callbacks.
        final int count;
        synchronized (mSyncRoot) {
            count = mCallbacks.size();
            mTempCallbacks.clear();
            for (int i = 0; i < count; i++) {
                if (uids == null || uids.contains(mCallbacks.valueAt(i).mUid)) {
                    mTempCallbacks.add(mCallbacks.valueAt(i));
                }
            }
        }

        // After releasing the lock, send the notifications out.
        for (int i = 0; i < mTempCallbacks.size(); i++) {
            mTempCallbacks.get(i).notifyDisplayEventAsync(displayId, event);
        }
        mTempCallbacks.clear();
    }

    // Runs on Handler thread.
    // Delivers display group event notifications to callbacks.
    private void deliverDisplayGroupEvent(int groupId, int event) {
        if (DEBUG) {
            Slog.d(TAG, "Delivering display group event: groupId=" + groupId + ", event="
                    + event);
        }

        switch (event) {
            case LogicalDisplayMapper.DISPLAY_GROUP_EVENT_ADDED:
                for (DisplayGroupListener listener : mDisplayGroupListeners) {
                    listener.onDisplayGroupAdded(groupId);
                }
                break;

            case LogicalDisplayMapper.DISPLAY_GROUP_EVENT_CHANGED:
                for (DisplayGroupListener listener : mDisplayGroupListeners) {
                    listener.onDisplayGroupChanged(groupId);
                }
                break;

            case LogicalDisplayMapper.DISPLAY_GROUP_EVENT_REMOVED:
                for (DisplayGroupListener listener : mDisplayGroupListeners) {
                    listener.onDisplayGroupRemoved(groupId);
                }
                break;
        }
    }

    private IMediaProjectionManager getProjectionService() {
        if (mProjectionService == null) {
            IBinder b = ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE);
            mProjectionService = IMediaProjectionManager.Stub.asInterface(b);
        }
        return mProjectionService;
    }

    private UserManager getUserManager() {
        return mContext.getSystemService(UserManager.class);
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DISPLAY MANAGER (dumpsys display)");

        synchronized (mSyncRoot) {
            pw.println("  mOnlyCode=" + mOnlyCore);
            pw.println("  mSafeMode=" + mSafeMode);
            pw.println("  mPendingTraversal=" + mPendingTraversal);
            pw.println("  mViewports=" + mViewports);
            pw.println("  mDefaultDisplayDefaultColorMode=" + mDefaultDisplayDefaultColorMode);
            pw.println("  mWifiDisplayScanRequestCount=" + mWifiDisplayScanRequestCount);
            pw.println("  mStableDisplaySize=" + mStableDisplaySize);
            pw.println("  mMinimumBrightnessCurve=" + mMinimumBrightnessCurve);

            pw.println();
            if (!mAreUserDisabledHdrTypesAllowed) {
                pw.println("  mUserDisabledHdrTypes: size=" + mUserDisabledHdrTypes.length);
                for (int type : mUserDisabledHdrTypes) {
                    pw.println("  " + type);
                }
            }

            pw.println();
            final int displayStateCount = mDisplayStates.size();
            pw.println("Display States: size=" + displayStateCount);
            for (int i = 0; i < displayStateCount; i++) {
                final int displayId = mDisplayStates.keyAt(i);
                final int displayState = mDisplayStates.valueAt(i);
                final BrightnessPair brightnessPair = mDisplayBrightnesses.valueAt(i);
                pw.println("  Display Id=" + displayId);
                pw.println("  Display State=" + Display.stateToString(displayState));
                pw.println("  Display Brightness=" + brightnessPair.brightness);
                pw.println("  Display SdrBrightness=" + brightnessPair.sdrBrightness);
            }

            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
            ipw.increaseIndent();

            pw.println();
            pw.println("Display Adapters: size=" + mDisplayAdapters.size());
            for (DisplayAdapter adapter : mDisplayAdapters) {
                pw.println("  " + adapter.getName());
                adapter.dumpLocked(ipw);
            }

            pw.println();
            pw.println("Display Devices: size=" + mDisplayDeviceRepo.sizeLocked());
            mDisplayDeviceRepo.forEachLocked(device -> {
                pw.println("  " + device.getDisplayDeviceInfoLocked());
                device.dumpLocked(ipw);
            });

            pw.println();
            mLogicalDisplayMapper.dumpLocked(pw);

            pw.println();
            mDisplayModeDirector.dump(pw);

            final int callbackCount = mCallbacks.size();
            pw.println();
            pw.println("Callbacks: size=" + callbackCount);
            for (int i = 0; i < callbackCount; i++) {
                CallbackRecord callback = mCallbacks.valueAt(i);
                pw.println("  " + i + ": mPid=" + callback.mPid
                        + ", mWifiDisplayScanRequested=" + callback.mWifiDisplayScanRequested);
            }

            final int displayPowerControllerCount = mDisplayPowerControllers.size();
            pw.println();
            pw.println("Display Power Controllers: size=" + displayPowerControllerCount);
            for (int i = 0; i < displayPowerControllerCount; i++) {
                mDisplayPowerControllers.valueAt(i).dump(pw);
            }
            if (mBrightnessTracker != null) {
                pw.println();
                mBrightnessTracker.dump(pw);
            }
            pw.println();
            mPersistentDataStore.dump(pw);
        }
    }

    private static float[] getFloatArray(TypedArray array) {
        int length = array.length();
        float[] floatArray = new float[length];
        for (int i = 0; i < length; i++) {
            floatArray[i] = array.getFloat(i, Float.NaN);
        }
        array.recycle();
        return floatArray;
    }

    /**
     * This is the object that everything in the display manager locks on.
     * We make it an inner class within the {@link DisplayManagerService} to so that it is
     * clear that the object belongs to the display manager service and that it is
     * a unique object with a special purpose.
     */
    public static final class SyncRoot {
    }

    @VisibleForTesting
    static class Injector {
        VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener) {
            return new VirtualDisplayAdapter(syncRoot, context, handler, displayAdapterListener);
        }

        long getDefaultDisplayDelayTimeout() {
            return WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT;
        }

        boolean getAllowNonNativeRefreshRateOverride() {
            return DisplayProperties
                    .debug_allow_non_native_refresh_rate_override().orElse(false);
        }
    }

    @VisibleForTesting
    DisplayDeviceInfo getDisplayDeviceInfoInternal(int displayId) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayDevice displayDevice = display.getPrimaryDisplayDeviceLocked();
                return displayDevice.getDisplayDeviceInfoLocked();
            }
            return null;
        }
    }

    @VisibleForTesting
    int getDisplayIdToMirrorInternal(int displayId) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayDevice displayDevice = display.getPrimaryDisplayDeviceLocked();
                return displayDevice.getDisplayIdToMirrorLocked();
            }
            return Display.INVALID_DISPLAY;
        }
    }

    @VisibleForTesting
    Surface getVirtualDisplaySurfaceInternal(IBinder appToken) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return null;
            }
            return mVirtualDisplayAdapter.getVirtualDisplaySurfaceLocked(appToken);
        }
    }

    private void initializeDisplayPowerControllersLocked() {
        mLogicalDisplayMapper.forEachLocked(this::addDisplayPowerControllerLocked);
    }

    private void addDisplayPowerControllerLocked(LogicalDisplay display) {
        if (mPowerHandler == null) {
            // initPowerManagement has not yet been called.
            return;
        }
        if (mBrightnessTracker == null) {
            mBrightnessTracker = new BrightnessTracker(mContext, null);
        }

        final BrightnessSetting brightnessSetting = new BrightnessSetting(mPersistentDataStore,
                display, mSyncRoot);
        final DisplayPowerController displayPowerController = new DisplayPowerController(
                mContext, mDisplayPowerCallbacks, mPowerHandler, mSensorManager,
                mDisplayBlanker, display, mBrightnessTracker, brightnessSetting,
                () -> handleBrightnessChange(display));
        mDisplayPowerControllers.append(display.getDisplayIdLocked(), displayPowerController);
    }

    private void handleBrightnessChange(LogicalDisplay display) {
        sendDisplayEventLocked(display.getDisplayIdLocked(),
                DisplayManagerGlobal.EVENT_DISPLAY_BRIGHTNESS_CHANGED);
    }

    private DisplayDevice getDeviceForDisplayLocked(int displayId) {
        final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
        return display == null ? null : display.getPrimaryDisplayDeviceLocked();
    }

    private final class DisplayManagerHandler extends Handler {
        public DisplayManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS:
                    registerDefaultDisplayAdapters();
                    break;

                case MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS:
                    registerAdditionalDisplayAdapters();
                    break;

                case MSG_DELIVER_DISPLAY_EVENT:
                    deliverDisplayEvent(msg.arg1, null, msg.arg2);
                    break;

                case MSG_REQUEST_TRAVERSAL:
                    mWindowManagerInternal.requestTraversalFromDisplayManager();
                    break;

                case MSG_UPDATE_VIEWPORT: {
                    final boolean changed;
                    synchronized (mSyncRoot) {
                        changed = !mTempViewports.equals(mViewports);
                        if (changed) {
                            mTempViewports.clear();
                            for (DisplayViewport d : mViewports) {
                                mTempViewports.add(d.makeCopy());
                            }
                        }
                    }
                    if (changed) {
                        mInputManagerInternal.setDisplayViewports(mTempViewports);
                    }
                    break;
                }

                case MSG_LOAD_BRIGHTNESS_CONFIGURATION:
                    loadBrightnessConfiguration();
                    break;

                case MSG_DELIVER_DISPLAY_EVENT_FRAME_RATE_OVERRIDE:
                    ArraySet<Integer> uids;
                    synchronized (mSyncRoot) {
                        int displayId = msg.arg1;
                        final LogicalDisplay display =
                                mLogicalDisplayMapper.getDisplayLocked(displayId);
                        uids = display.getPendingFrameRateOverrideUids();
                        display.clearPendingFrameRateOverrideUids();
                    }
                    deliverDisplayEvent(msg.arg1, uids, msg.arg2);
                    break;

                case MSG_DELIVER_DISPLAY_GROUP_EVENT:
                    deliverDisplayGroupEvent(msg.arg1, msg.arg2);
                    break;

            }
        }
    }

    private final class LogicalDisplayListener implements LogicalDisplayMapper.Listener {
        @Override
        public void onLogicalDisplayEventLocked(LogicalDisplay display, int event) {
            switch (event) {
                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_ADDED:
                    handleLogicalDisplayAddedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CHANGED:
                    handleLogicalDisplayChangedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_REMOVED:
                    handleLogicalDisplayRemovedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_SWAPPED:
                    handleLogicalDisplaySwappedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED:
                    handleLogicalDisplayFrameRateOverridesChangedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION:
                    handleLogicalDisplayDeviceStateTransitionLocked(display);
                    break;
            }
        }

        @Override
        public void onDisplayGroupEventLocked(int groupId, int event) {
            sendDisplayGroupEvent(groupId, event);
        }

        @Override
        public void onTraversalRequested() {
            synchronized (mSyncRoot) {
                scheduleTraversalLocked(false);
            }
        }
    }

    private final class CallbackRecord implements DeathRecipient {
        public final int mPid;
        public final int mUid;
        private final IDisplayManagerCallback mCallback;
        private @EventsMask AtomicLong mEventsMask;

        public boolean mWifiDisplayScanRequested;

        CallbackRecord(int pid, int uid, IDisplayManagerCallback callback,
                @EventsMask long eventsMask) {
            mPid = pid;
            mUid = uid;
            mCallback = callback;
            mEventsMask = new AtomicLong(eventsMask);
        }

        public void updateEventsMask(@EventsMask long eventsMask) {
            mEventsMask.set(eventsMask);
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Display listener for pid " + mPid + " died.");
            }
            onCallbackDied(this);
        }

        public void notifyDisplayEventAsync(int displayId, @DisplayEvent int event) {
            if (!shouldSendEvent(event)) {
                return;
            }

            try {
                mCallback.onDisplayEvent(displayId, event);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process "
                        + mPid + " that displays changed, assuming it died.", ex);
                binderDied();
            }
        }

        private boolean shouldSendEvent(@DisplayEvent int event) {
            final long mask = mEventsMask.get();
            switch (event) {
                case DisplayManagerGlobal.EVENT_DISPLAY_ADDED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_ADDED) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_CHANGED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_CHANGED) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_REMOVED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_REMOVED) != 0;
                default:
                    // This should never happen.
                    Slog.e(TAG, "Unknown display event " + event);
                    return true;
            }
        }
    }

    @VisibleForTesting
    final class BinderService extends IDisplayManager.Stub {
        /**
         * Returns information about the specified logical display.
         *
         * @param displayId The logical display id.
         * @return The logical display info, return {@code null} if the display does not exist or
         * the calling UID isn't present on the display.  The returned object must be treated as
         * immutable.
         */
        @Override // Binder call
        public DisplayInfo getDisplayInfo(int displayId) {
            final int callingUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                return getDisplayInfoInternal(displayId, callingUid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Returns the list of all display ids.
         */
        @Override // Binder call
        public int[] getDisplayIds() {
            final int callingUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mLogicalDisplayMapper.getDisplayIdsLocked(callingUid);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean isUidPresentOnDisplay(int uid, int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                return isUidPresentOnDisplayInternal(uid, displayId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Returns the stable device display size, in pixels.
         */
        @Override // Binder call
        public Point getStableDisplaySize() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getStableDisplaySizeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void registerCallback(IDisplayManagerCallback callback) {
            registerCallbackWithEventMask(callback, DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                    | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        }

        @Override // Binder call
        public void registerCallbackWithEventMask(IDisplayManagerCallback callback,
                @EventsMask long eventsMask) {
            if (callback == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                registerCallbackInternal(callback, callingPid, callingUid, eventsMask);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void startWifiDisplayScan() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to start wifi display scans");

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                startWifiDisplayScanInternal(callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void stopWifiDisplayScan() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to stop wifi display scans");

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                stopWifiDisplayScanInternal(callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void connectWifiDisplay(String address) {
            if (address == null) {
                throw new IllegalArgumentException("address must not be null");
            }
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to connect to a wifi display");

            final long token = Binder.clearCallingIdentity();
            try {
                connectWifiDisplayInternal(address);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void disconnectWifiDisplay() {
            // This request does not require special permissions.
            // Any app can request disconnection from the currently active wifi display.
            // This exception should no longer be needed once wifi display control moves
            // to the media router service.

            final long token = Binder.clearCallingIdentity();
            try {
                disconnectWifiDisplayInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void renameWifiDisplay(String address, String alias) {
            if (address == null) {
                throw new IllegalArgumentException("address must not be null");
            }
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to rename to a wifi display");

            final long token = Binder.clearCallingIdentity();
            try {
                renameWifiDisplayInternal(address, alias);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void forgetWifiDisplay(String address) {
            if (address == null) {
                throw new IllegalArgumentException("address must not be null");
            }
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to forget to a wifi display");

            final long token = Binder.clearCallingIdentity();
            try {
                forgetWifiDisplayInternal(address);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void pauseWifiDisplay() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to pause a wifi display session");

            final long token = Binder.clearCallingIdentity();
            try {
                pauseWifiDisplayInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void resumeWifiDisplay() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to resume a wifi display session");

            final long token = Binder.clearCallingIdentity();
            try {
                resumeWifiDisplayInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public WifiDisplayStatus getWifiDisplayStatus() {
            // This request does not require special permissions.
            // Any app can get information about available wifi displays.

            final long token = Binder.clearCallingIdentity();
            try {
                return getWifiDisplayStatusInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setUserDisabledHdrTypes(int[] userDisabledFormats) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.WRITE_SECURE_SETTINGS,
                    "Permission required to write the user settings.");

            final long token = Binder.clearCallingIdentity();
            try {
                setUserDisabledHdrTypesInternal(userDisabledFormats);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setAreUserDisabledHdrTypesAllowed(boolean areUserDisabledHdrTypesAllowed) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.WRITE_SECURE_SETTINGS,
                    "Permission required to write the user settings.");
            final long token = Binder.clearCallingIdentity();
            try {
                setAreUserDisabledHdrTypesAllowedInternal(areUserDisabledHdrTypesAllowed);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean areUserDisabledHdrTypesAllowed() {
            synchronized (mSyncRoot) {
                return mAreUserDisabledHdrTypesAllowed;
            }
        }

        @Override // Binder call
        public int[] getUserDisabledHdrTypes() {
            return mUserDisabledHdrTypes;
        }

        @Override // Binder call
        public void requestColorMode(int displayId, int colorMode) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONFIGURE_DISPLAY_COLOR_MODE,
                    "Permission required to change the display color mode");
            final long token = Binder.clearCallingIdentity();
            try {
                requestColorModeInternal(displayId, colorMode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int createVirtualDisplay(VirtualDisplayConfig virtualDisplayConfig,
                IVirtualDisplayCallback callback, IMediaProjection projection, String packageName) {
            final int callingUid = Binder.getCallingUid();
            if (!validatePackageName(callingUid, packageName)) {
                throw new SecurityException("packageName must match the calling uid");
            }
            if (callback == null) {
                throw new IllegalArgumentException("appToken must not be null");
            }
            if (virtualDisplayConfig == null) {
                throw new IllegalArgumentException("virtualDisplayConfig must not be null");
            }
            final Surface surface = virtualDisplayConfig.getSurface();
            int flags = virtualDisplayConfig.getFlags();

            if (surface != null && surface.isSingleBuffered()) {
                throw new IllegalArgumentException("Surface can't be single-buffered");
            }

            if ((flags & VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
                flags |= VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

                // Public displays can't be allowed to show content when locked.
                if ((flags & VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0) {
                    throw new IllegalArgumentException(
                            "Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE");
                }
            }
            if ((flags & VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
                flags &= ~VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
            }
            if ((flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
                flags &= ~VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
            }

            if (projection != null) {
                try {
                    if (!getProjectionService().isValidMediaProjection(projection)) {
                        throw new SecurityException("Invalid media projection");
                    }
                    flags = projection.applyVirtualDisplayFlags(flags);
                } catch (RemoteException e) {
                    throw new SecurityException("unable to validate media projection or flags");
                }
            }

            if (callingUid != Process.SYSTEM_UID &&
                    (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
                if (!canProjectVideo(projection)) {
                    throw new SecurityException("Requires CAPTURE_VIDEO_OUTPUT or "
                            + "CAPTURE_SECURE_VIDEO_OUTPUT permission, or an appropriate "
                            + "MediaProjection token in order to create a screen sharing virtual "
                            + "display.");
                }
            }
            if (callingUid != Process.SYSTEM_UID && (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
                if (!canProjectSecureVideo(projection)) {
                    throw new SecurityException("Requires CAPTURE_SECURE_VIDEO_OUTPUT "
                            + "or an appropriate MediaProjection token to create a "
                            + "secure virtual display.");
                }
            }

            if (callingUid != Process.SYSTEM_UID && (flags & VIRTUAL_DISPLAY_FLAG_TRUSTED) != 0) {
                if (!checkCallingPermission(ADD_TRUSTED_DISPLAY, "createVirtualDisplay()")) {
                    EventLog.writeEvent(0x534e4554, "162627132", callingUid,
                            "Attempt to create a trusted display without holding permission!");
                    throw new SecurityException("Requires ADD_TRUSTED_DISPLAY permission to "
                            + "create a trusted virtual display.");
                }
            }

            if (callingUid != Process.SYSTEM_UID
                    && (flags & VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP) != 0) {
                if (!checkCallingPermission(ADD_TRUSTED_DISPLAY, "createVirtualDisplay()")) {
                    throw new SecurityException("Requires ADD_TRUSTED_DISPLAY permission to "
                            + "create a virtual display which is not in the default DisplayGroup.");
                }
            }

            if ((flags & VIRTUAL_DISPLAY_FLAG_TRUSTED) == 0) {
                flags &= ~VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
            }

            // Sometimes users can have sensitive information in system decoration windows. An app
            // could create a virtual display with system decorations support and read the user info
            // from the surface.
            // We should only allow adding flag VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            // to trusted virtual displays.
            final int trustedDisplayWithSysDecorFlag =
                    (VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                            | VIRTUAL_DISPLAY_FLAG_TRUSTED);
            if ((flags & trustedDisplayWithSysDecorFlag)
                    == VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                    && !checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "createVirtualDisplay()")) {
                    throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
            }

            final long token = Binder.clearCallingIdentity();
            try {
                return createVirtualDisplayInternal(callback, projection, callingUid, packageName,
                        surface, flags, virtualDisplayConfig);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void resizeVirtualDisplay(IVirtualDisplayCallback callback,
                int width, int height, int densityDpi) {
            if (width <= 0 || height <= 0 || densityDpi <= 0) {
                throw new IllegalArgumentException("width, height, and densityDpi must be "
                        + "greater than 0");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                resizeVirtualDisplayInternal(callback.asBinder(), width, height, densityDpi);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setVirtualDisplaySurface(IVirtualDisplayCallback callback, Surface surface) {
            if (surface != null && surface.isSingleBuffered()) {
                throw new IllegalArgumentException("Surface can't be single-buffered");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                setVirtualDisplaySurfaceInternal(callback.asBinder(), surface);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void releaseVirtualDisplay(IVirtualDisplayCallback callback) {
            final long token = Binder.clearCallingIdentity();
            try {
                releaseVirtualDisplayInternal(callback.asBinder());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setVirtualDisplayState(IVirtualDisplayCallback callback, boolean isOn) {
            final long token = Binder.clearCallingIdentity();
            try {
                setVirtualDisplayStateInternal(callback.asBinder(), isOn);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(String callingPackage) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.BRIGHTNESS_SLIDER_USAGE,
                    "Permission to read brightness events.");

            final int callingUid = Binder.getCallingUid();
            AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
            final int mode = appOpsManager.noteOp(AppOpsManager.OP_GET_USAGE_STATS,
                    callingUid, callingPackage);
            final boolean hasUsageStats;
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                hasUsageStats = mContext.checkCallingPermission(
                        Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            } else {
                hasUsageStats = mode == AppOpsManager.MODE_ALLOWED;
            }

            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .getBrightnessEvents(userId, hasUsageStats);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_LIGHT_STATS,
                    "Permission required to to access ambient light stats.");
            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .getAmbientBrightnessStats(userId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setBrightnessConfigurationForUser(
                BrightnessConfiguration c, @UserIdInt int userId, String packageName) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS,
                    "Permission required to change the display's brightness configuration");
            if (userId != UserHandle.getCallingUserId()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        "Permission required to change the display brightness"
                        + " configuration of another user");
            }
            if (packageName != null && !validatePackageName(getCallingUid(), packageName)) {
                packageName = null;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                setBrightnessConfigurationForUserInternal(c, userId, packageName);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public BrightnessConfiguration getBrightnessConfigurationForUser(int userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS,
                    "Permission required to read the display's brightness configuration");
            if (userId != UserHandle.getCallingUserId()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        "Permission required to read the display brightness"
                                + " configuration of another user");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                final int userSerial = getUserManager().getUserSerialNumber(userId);
                synchronized (mSyncRoot) {
                    BrightnessConfiguration config =
                            mPersistentDataStore.getBrightnessConfiguration(userSerial);
                    if (config == null) {
                        config = mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                                .getDefaultBrightnessConfiguration();
                    }
                    return config;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public BrightnessConfiguration getDefaultBrightnessConfiguration() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS,
                    "Permission required to read the display's default brightness configuration");
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .getDefaultBrightnessConfiguration();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public BrightnessInfo getBrightnessInfo(int displayId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS,
                    "Permission required to read the display's brightness info.");
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    DisplayPowerController dpc = mDisplayPowerControllers.get(displayId);
                    if (dpc != null) {
                        return dpc.getBrightnessInfo();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override // Binder call
        public boolean isMinimalPostProcessingRequested(int displayId) {
            synchronized (mSyncRoot) {
                return mLogicalDisplayMapper.getDisplayLocked(displayId)
                        .getRequestedMinimalPostProcessingLocked();
            }
        }

        @Override // Binder call
        public void setTemporaryBrightness(int displayId, float brightness) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS,
                    "Permission required to set the display's brightness");
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    mDisplayPowerControllers.get(displayId)
                            .setTemporaryBrightness(brightness);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setBrightness(int displayId, float brightness) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS,
                    "Permission required to set the display's brightness");
            if (!isValidBrightness(brightness)) {
                Slog.w(TAG, "Attempted to set invalid brightness" + brightness);
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    DisplayPowerController dpc = mDisplayPowerControllers.get(displayId);
                    if (dpc != null) {
                        dpc.putScreenBrightnessSetting(brightness);
                    }
                    mPersistentDataStore.saveIfNeeded();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public float getBrightness(int displayId) {
            float brightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS,
                    "Permission required to set the display's brightness");
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    DisplayPowerController dpc = mDisplayPowerControllers.get(displayId);
                    if (dpc != null) {
                        brightness = dpc.getScreenBrightnessSetting();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return brightness;
        }

        @Override // Binder call
        public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS,
                    "Permission required to set the display's auto brightness adjustment");
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .setTemporaryAutoBrightnessAdjustment(adjustment);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            new DisplayManagerShellCommand(DisplayManagerService.this).exec(this, in, out, err,
                    args, callback, resultReceiver);
        }

        @Override // Binder call
        public Curve getMinimumBrightnessCurve() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getMinimumBrightnessCurveInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int getPreferredWideGamutColorSpaceId() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getPreferredWideGamutColorSpaceIdInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS,
                    "Permission required to override display mode requests.");
            final long token = Binder.clearCallingIdentity();
            try {
                setShouldAlwaysRespectAppRequestedModeInternal(enabled);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean shouldAlwaysRespectAppRequestedMode() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS,
                    "Permission required to override display mode requests.");
            final long token = Binder.clearCallingIdentity();
            try {
                return shouldAlwaysRespectAppRequestedModeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setRefreshRateSwitchingType(int newValue) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MODIFY_REFRESH_RATE_SWITCHING_TYPE,
                    "Permission required to modify refresh rate switching type.");
            final long token = Binder.clearCallingIdentity();
            try {
                setRefreshRateSwitchingTypeInternal(newValue);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int getRefreshRateSwitchingType() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getRefreshRateSwitchingTypeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private boolean validatePackageName(int uid, String packageName) {
            if (packageName != null) {
                String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
                if (packageNames != null) {
                    for (String n : packageNames) {
                        if (n.equals(packageName)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean canProjectVideo(IMediaProjection projection) {
            if (projection != null) {
                try {
                    if (projection.canProjectVideo()) {
                        return true;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to query projection service for permissions", e);
                }
            }
            if (checkCallingPermission(CAPTURE_VIDEO_OUTPUT, "canProjectVideo()")) {
                return true;
            }
            return canProjectSecureVideo(projection);
        }

        private boolean canProjectSecureVideo(IMediaProjection projection) {
            if (projection != null) {
                try {
                    if (projection.canProjectSecureVideo()){
                        return true;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to query projection service for permissions", e);
                }
            }
            return checkCallingPermission(CAPTURE_SECURE_VIDEO_OUTPUT, "canProjectSecureVideo()");
        }

        private boolean checkCallingPermission(String permission, String func) {
            if (mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            final String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid() + " requires " + permission;
            Slog.w(TAG, msg);
            return false;
        }

    }

    private static boolean isValidBrightness(float brightness) {
        return !Float.isNaN(brightness)
                && (brightness >= PowerManager.BRIGHTNESS_MIN)
                && (brightness <= PowerManager.BRIGHTNESS_MAX);
    }

    private final class LocalService extends DisplayManagerInternal {

        @Override
        public void initPowerManagement(final DisplayPowerCallbacks callbacks, Handler handler,
                SensorManager sensorManager) {
            synchronized (mSyncRoot) {
                mDisplayPowerCallbacks = callbacks;
                mSensorManager = sensorManager;
                mPowerHandler = handler;
                initializeDisplayPowerControllersLocked();
            }

            mHandler.sendEmptyMessage(MSG_LOAD_BRIGHTNESS_CONFIGURATION);
        }

        @Override
        public boolean requestPowerState(int groupId, DisplayPowerRequest request,
                boolean waitForNegativeProximity) {
            synchronized (mSyncRoot) {
                final DisplayGroup displayGroup = mLogicalDisplayMapper.getDisplayGroupLocked(
                        groupId);
                if (displayGroup == null) {
                    return true;
                }

                final int size = displayGroup.getSizeLocked();
                boolean ready = true;
                for (int i = 0; i < size; i++) {
                    final int id = displayGroup.getIdLocked(i);
                    final DisplayDevice displayDevice = mLogicalDisplayMapper.getDisplayLocked(
                            id).getPrimaryDisplayDeviceLocked();
                    final int flags = displayDevice.getDisplayDeviceInfoLocked().flags;
                    if ((flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0) {
                        final DisplayPowerController displayPowerController =
                                mDisplayPowerControllers.get(id);
                        ready &= displayPowerController.requestPowerState(request,
                                waitForNegativeProximity);
                    }
                }

                return ready;
            }
        }

        @Override
        public boolean isProximitySensorAvailable() {
            synchronized (mSyncRoot) {
                return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                        .isProximitySensorAvailable();
            }
        }

        @Override
        public void registerDisplayGroupListener(DisplayGroupListener listener) {
            mDisplayGroupListeners.add(listener);
        }

        @Override
        public void unregisterDisplayGroupListener(DisplayGroupListener listener) {
            mDisplayGroupListeners.remove(listener);
        }

        @Override
        public SurfaceControl.ScreenshotHardwareBuffer systemScreenshot(int displayId) {
            return systemScreenshotInternal(displayId);
        }

        @Override
        public SurfaceControl.ScreenshotHardwareBuffer userScreenshot(int displayId) {
            return userScreenshotInternal(displayId);
        }

        @Override
        public DisplayInfo getDisplayInfo(int displayId) {
            return getDisplayInfoInternal(displayId, Process.myUid());
        }

        @Override
        public Point getDisplayPosition(int displayId) {
            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display != null) {
                    return display.getDisplayPosition();
                }
                return null;
            }
        }

        @Override
        public void registerDisplayTransactionListener(DisplayTransactionListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            registerDisplayTransactionListenerInternal(listener);
        }

        @Override
        public void unregisterDisplayTransactionListener(DisplayTransactionListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            unregisterDisplayTransactionListenerInternal(listener);
        }

        @Override
        public void setDisplayInfoOverrideFromWindowManager(int displayId, DisplayInfo info) {
            setDisplayInfoOverrideFromWindowManagerInternal(displayId, info);
        }

        @Override
        public void getNonOverrideDisplayInfo(int displayId, DisplayInfo outInfo) {
            getNonOverrideDisplayInfoInternal(displayId, outInfo);
        }

        @Override
        public void performTraversal(SurfaceControl.Transaction t) {
            performTraversalInternal(t);
        }

        @Override
        public void setDisplayProperties(int displayId, boolean hasContent,
                float requestedRefreshRate, int requestedMode, float requestedMinRefreshRate,
                float requestedMaxRefreshRate, boolean requestedMinimalPostProcessing,
                boolean inTraversal) {
            setDisplayPropertiesInternal(displayId, hasContent, requestedRefreshRate,
                    requestedMode, requestedMinRefreshRate, requestedMaxRefreshRate,
                    requestedMinimalPostProcessing, inTraversal);
        }

        @Override
        public void setDisplayOffsets(int displayId, int x, int y) {
            setDisplayOffsetsInternal(displayId, x, y);
        }

        @Override
        public void setDisplayScalingDisabled(int displayId, boolean disableScaling) {
            setDisplayScalingDisabledInternal(displayId, disableScaling);
        }

        @Override
        public void setDisplayAccessUIDs(SparseArray<IntArray> newDisplayAccessUIDs) {
            setDisplayAccessUIDsInternal(newDisplayAccessUIDs);
        }

        @Override
        public void persistBrightnessTrackerState() {
            synchronized (mSyncRoot) {
                mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                        .persistBrightnessTrackerState();
            }
        }

        @Override
        public void onOverlayChanged() {
            synchronized (mSyncRoot) {
                mDisplayDeviceRepo.forEachLocked(DisplayDevice::onOverlayChangedLocked);
            }
        }

        @Override
        public DisplayedContentSamplingAttributes getDisplayedContentSamplingAttributes(
                int displayId) {
            return getDisplayedContentSamplingAttributesInternal(displayId);
        }

        @Override
        public boolean setDisplayedContentSamplingEnabled(
                int displayId, boolean enable, int componentMask, int maxFrames) {
            return setDisplayedContentSamplingEnabledInternal(
                    displayId, enable, componentMask, maxFrames);
        }

        @Override
        public DisplayedContentSample getDisplayedContentSample(int displayId,
                long maxFrames, long timestamp) {
            return getDisplayedContentSampleInternal(displayId, maxFrames, timestamp);
        }

        @Override
        public void ignoreProximitySensorUntilChanged() {
            mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                    .ignoreProximitySensorUntilChanged();
        }

        @Override
        public int getRefreshRateSwitchingType() {
            return getRefreshRateSwitchingTypeInternal();
        }

        @Override
        public RefreshRateRange getRefreshRateForDisplayAndSensor(int displayId, String sensorName,
                String sensorType) {
            final SensorManager sensorManager;
            synchronized (mSyncRoot) {
                sensorManager = mSensorManager;
            }
            if (sensorManager == null) {
                return null;
            }

            // Verify that the specified sensor exists.
            final Sensor sensor = SensorUtils.findSensor(sensorManager, sensorType, sensorName,
                    SensorUtils.NO_FALLBACK);
            if (sensor == null) {
                return null;
            }

            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display == null) {
                    return null;
                }
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                if (device == null) {
                    return null;
                }
                final DisplayDeviceConfig config = device.getDisplayDeviceConfig();
                SensorData sensorData = config.getProximitySensor();
                if (sensorData.matches(sensorName, sensorType)) {
                    return new RefreshRateRange(sensorData.minRefreshRate,
                            sensorData.maxRefreshRate);
                }
            }
            return null;
        }

        @Override
        public List<RefreshRateLimitation> getRefreshRateLimitations(int displayId) {
            final DisplayDeviceConfig config;
            synchronized (mSyncRoot) {
                final DisplayDevice device = getDeviceForDisplayLocked(displayId);
                if (device == null) {
                    return null;
                }
                config = device.getDisplayDeviceConfig();
            }
            return config.getRefreshRateLimitations();
        }
    }

    class DesiredDisplayModeSpecsObserver
            implements DisplayModeDirector.DesiredDisplayModeSpecsListener {

        private final Consumer<LogicalDisplay> mSpecsChangedConsumer = display -> {
            int displayId = display.getDisplayIdLocked();
            DisplayModeDirector.DesiredDisplayModeSpecs desiredDisplayModeSpecs =
                    mDisplayModeDirector.getDesiredDisplayModeSpecs(displayId);
            DisplayModeDirector.DesiredDisplayModeSpecs existingDesiredDisplayModeSpecs =
                    display.getDesiredDisplayModeSpecsLocked();
            if (DEBUG) {
                Slog.i(TAG,
                        "Comparing display specs: " + desiredDisplayModeSpecs
                                + ", existing: " + existingDesiredDisplayModeSpecs);
            }
            if (!desiredDisplayModeSpecs.equals(existingDesiredDisplayModeSpecs)) {
                display.setDesiredDisplayModeSpecsLocked(desiredDisplayModeSpecs);
                mChanged = true;
            }
        };

        @GuardedBy("mSyncRoot")
        private boolean mChanged = false;

        public void onDesiredDisplayModeSpecsChanged() {
            synchronized (mSyncRoot) {
                mChanged = false;
                mLogicalDisplayMapper.forEachLocked(mSpecsChangedConsumer);
                if (mChanged) {
                    scheduleTraversalLocked(false);
                    mChanged = false;
                }
            }
        }
    }

    /**
     * Listens to changes in device state and reports the state to LogicalDisplayMapper.
     */
    class DeviceStateListener implements DeviceStateManager.DeviceStateCallback {
        @Override
        public void onStateChanged(int deviceState) {
            synchronized (mSyncRoot) {
                mLogicalDisplayMapper.setDeviceStateLocked(deviceState);
            }
        }
    };

    private class BrightnessPair {
        public float brightness;
        public float sdrBrightness;

        BrightnessPair(float brightness, float sdrBrightness) {
            this.brightness = brightness;
            this.sdrBrightness = sdrBrightness;
        }
    }

    /**
     * Functional interface for providing time.
     * TODO(b/184781936): merge with PowerManagerService.Clock
     */
    @VisibleForTesting
    public interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }
}
