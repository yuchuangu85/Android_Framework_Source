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

package com.android.server.devicestate;

import static android.Manifest.permission.CONTROL_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;

import static com.android.server.devicestate.DeviceState.FLAG_CANCEL_OVERRIDE_REQUESTS;
import static com.android.server.devicestate.OverrideRequestController.STATUS_ACTIVE;
import static com.android.server.devicestate.OverrideRequestController.STATUS_CANCELED;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateInfo;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManagerInternal;
import android.hardware.devicestate.IDeviceStateManager;
import android.hardware.devicestate.IDeviceStateManagerCallback;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowProcessController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A system service that manages the state of a device with user-configurable hardware like a
 * foldable phone.
 * <p>
 * Device state is an abstract concept that allows mapping the current state of the device to the
 * state of the system. For example, system services (like
 * {@link com.android.server.display.DisplayManagerService display manager} and
 * {@link com.android.server.wm.WindowManagerService window manager}) and system UI may have
 * different behaviors depending on the physical state of the device. This is useful for
 * variable-state devices, like foldable or rollable devices, that can be configured by users into
 * differing hardware states, which each may have a different expected use case.
 * </p>
 * <p>
 * The {@link DeviceStateManagerService} is responsible for receiving state change requests from
 * the {@link DeviceStateProvider} to modify the current device state and communicating with the
 * {@link DeviceStatePolicy policy} to ensure the system is configured to match the requested state.
 * </p>
 * The service also provides the {@link DeviceStateManager} API allowing clients to listen for
 * changes in device state and submit requests to override the device state provided by the
 * {@link DeviceStateProvider}.
 *
 * @see DeviceStatePolicy
 * @see DeviceStateManager
 */
public final class DeviceStateManagerService extends SystemService {
    private static final String TAG = "DeviceStateManagerService";
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    // Handler on the {@link DisplayThread} used to dispatch calls to the policy and to registered
    // callbacks though its handler (mHandler). Provides a guarantee of callback order when
    // leveraging mHandler and also enables posting messages with the service lock held.
    private final Handler mHandler;
    @NonNull
    private final DeviceStatePolicy mDeviceStatePolicy;
    @NonNull
    private final BinderService mBinderService;
    @NonNull
    private final OverrideRequestController mOverrideRequestController;
    @VisibleForTesting
    @NonNull
    public ActivityTaskManagerInternal mActivityTaskManagerInternal;

    // All supported device states keyed by identifier.
    @GuardedBy("mLock")
    private SparseArray<DeviceState> mDeviceStates = new SparseArray<>();

    // The current committed device state. Will be empty until the first device state provided by
    // the DeviceStateProvider is committed.
    @GuardedBy("mLock")
    @NonNull
    private Optional<DeviceState> mCommittedState = Optional.empty();
    // The device state that is currently awaiting callback from the policy to be committed.
    @GuardedBy("mLock")
    @NonNull
    private Optional<DeviceState> mPendingState = Optional.empty();
    // Whether or not the policy is currently waiting to be notified of the current pending state.
    @GuardedBy("mLock")
    private boolean mIsPolicyWaitingForState = false;

    // The device state that is set by the DeviceStateProvider. Will be empty until the first
    // callback from the provider and then will always contain the most recent value.
    @GuardedBy("mLock")
    @NonNull
    private Optional<DeviceState> mBaseState = Optional.empty();

    // The current active override request. When set the device state specified here will take
    // precedence over mBaseState.
    @GuardedBy("mLock")
    @NonNull
    private Optional<OverrideRequest> mActiveOverride = Optional.empty();

    // List of processes registered to receive notifications about changes to device state and
    // request status indexed by process id.
    @GuardedBy("mLock")
    private final SparseArray<ProcessRecord> mProcessRecords = new SparseArray<>();

    private Set<Integer> mDeviceStatesAvailableForAppRequests;

    public DeviceStateManagerService(@NonNull Context context) {
        this(context, DeviceStatePolicy.Provider
                .fromResources(context.getResources())
                .instantiate(context));
    }

    @VisibleForTesting
    DeviceStateManagerService(@NonNull Context context, @NonNull DeviceStatePolicy policy) {
        super(context);
        // We use the DisplayThread because this service indirectly drives
        // display (on/off) and window (position) events through its callbacks.
        DisplayThread displayThread = DisplayThread.get();
        mHandler = new Handler(displayThread.getLooper());
        mOverrideRequestController = new OverrideRequestController(
                this::onOverrideRequestStatusChangedLocked);
        mDeviceStatePolicy = policy;
        mDeviceStatePolicy.getDeviceStateProvider().setListener(new DeviceStateProviderListener());
        mBinderService = new BinderService();
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DEVICE_STATE_SERVICE, mBinderService);
        publishLocalService(DeviceStateManagerInternal.class, new LocalService());

        synchronized (mLock) {
            readStatesAvailableForRequestFromApps();
        }
    }

    @VisibleForTesting
    Handler getHandler() {
        return mHandler;
    }

    /**
     * Returns the current state the system is in. Note that the system may be in the process of
     * configuring a different state.
     * <p>
     * Note: This method will return {@link Optional#empty()} if called before the first state has
     * been committed, otherwise it will return the last committed state.
     *
     * @see #getPendingState()
     */
    @NonNull
    Optional<DeviceState> getCommittedState() {
        synchronized (mLock) {
            return mCommittedState;
        }
    }

    /**
     * Returns the state the system is currently configuring, or {@link Optional#empty()} if the
     * system is not in the process of configuring a state.
     */
    @VisibleForTesting
    @NonNull
    Optional<DeviceState> getPendingState() {
        synchronized (mLock) {
            return mPendingState;
        }
    }

    /**
     * Returns the base state. The service will configure the device to match the base state when
     * there is no active request to override the base state.
     * <p>
     * Note: This method will return {@link Optional#empty()} if called before a base state is
     * provided to the service by the {@link DeviceStateProvider}, otherwise it will return the
     * most recent provided value.
     *
     * @see #getOverrideState()
     */
    @NonNull
    Optional<DeviceState> getBaseState() {
        synchronized (mLock) {
            return mBaseState;
        }
    }

    /**
     * Returns the current override state, or {@link Optional#empty()} if no override state is
     * requested. If an override states is present, the returned state will take precedence over
     * the base state returned from {@link #getBaseState()}.
     */
    @NonNull
    Optional<DeviceState> getOverrideState() {
        synchronized (mLock) {
            if (mActiveOverride.isPresent()) {
                return getStateLocked(mActiveOverride.get().getRequestedState());
            }
            return Optional.empty();
        }
    }

    /** Returns the list of currently supported device states. */
    DeviceState[] getSupportedStates() {
        synchronized (mLock) {
            DeviceState[] supportedStates = new DeviceState[mDeviceStates.size()];
            for (int i = 0; i < supportedStates.length; i++) {
                supportedStates[i] = mDeviceStates.valueAt(i);
            }
            return supportedStates;
        }
    }

    /** Returns the list of currently supported device state identifiers. */
    private int[] getSupportedStateIdentifiersLocked() {
        int[] supportedStates = new int[mDeviceStates.size()];
        for (int i = 0; i < supportedStates.length; i++) {
            supportedStates[i] = mDeviceStates.valueAt(i).getIdentifier();
        }
        return supportedStates;
    }

    @NonNull
    private DeviceStateInfo getDeviceStateInfoLocked() {
        if (!mBaseState.isPresent() || !mCommittedState.isPresent()) {
            throw new IllegalStateException("Trying to get the current DeviceStateInfo before the"
                    + " initial state has been committed.");
        }

        final int[] supportedStates = getSupportedStateIdentifiersLocked();
        final int baseState = mBaseState.get().getIdentifier();
        final int currentState = mCommittedState.get().getIdentifier();

        return new DeviceStateInfo(supportedStates, baseState, currentState);
    }

    @VisibleForTesting
    IDeviceStateManager getBinderService() {
        return mBinderService;
    }

    private void updateSupportedStates(DeviceState[] supportedDeviceStates) {
        synchronized (mLock) {
            final int[] oldStateIdentifiers = getSupportedStateIdentifiersLocked();

            // Whether or not at least one device state has the flag FLAG_CANCEL_OVERRIDE_REQUESTS
            // set. If set to true, the OverrideRequestController will be configured to allow sticky
            // requests.
            boolean hasTerminalDeviceState = false;
            mDeviceStates.clear();
            for (int i = 0; i < supportedDeviceStates.length; i++) {
                DeviceState state = supportedDeviceStates[i];
                if (state.hasFlag(FLAG_CANCEL_OVERRIDE_REQUESTS)) {
                    hasTerminalDeviceState = true;
                }
                mDeviceStates.put(state.getIdentifier(), state);
            }

            mOverrideRequestController.setStickyRequestsAllowed(hasTerminalDeviceState);

            final int[] newStateIdentifiers = getSupportedStateIdentifiersLocked();
            if (Arrays.equals(oldStateIdentifiers, newStateIdentifiers)) {
                return;
            }

            mOverrideRequestController.handleNewSupportedStates(newStateIdentifiers);
            updatePendingStateLocked();

            if (!mPendingState.isPresent()) {
                // If the change in the supported states didn't result in a change of the pending
                // state commitPendingState() will never be called and the callbacks will never be
                // notified of the change.
                notifyDeviceStateInfoChangedAsync();
            }

            mHandler.post(this::notifyPolicyIfNeeded);
        }
    }

    /**
     * Returns {@code true} if the provided state is supported. Requires that
     * {@link #mDeviceStates} is sorted prior to calling.
     */
    private boolean isSupportedStateLocked(int identifier) {
        return mDeviceStates.contains(identifier);
    }

    /**
     * Returns the {@link DeviceState} with the supplied {@code identifier}, or {@code null} if
     * there is no device state with the identifier.
     */
    @Nullable
    private Optional<DeviceState> getStateLocked(int identifier) {
        return Optional.ofNullable(mDeviceStates.get(identifier));
    }

    /**
     * Sets the base state.
     *
     * @throws IllegalArgumentException if the {@code identifier} is not a supported state.
     *
     * @see #isSupportedStateLocked(int)
     */
    private void setBaseState(int identifier) {
        synchronized (mLock) {
            final Optional<DeviceState> baseStateOptional = getStateLocked(identifier);
            if (!baseStateOptional.isPresent()) {
                throw new IllegalArgumentException("Base state is not supported");
            }

            final DeviceState baseState = baseStateOptional.get();
            if (mBaseState.isPresent() && mBaseState.get().equals(baseState)) {
                // Base state hasn't changed. Nothing to do.
                return;
            }
            mBaseState = Optional.of(baseState);

            if (baseState.hasFlag(FLAG_CANCEL_OVERRIDE_REQUESTS)) {
                mOverrideRequestController.cancelOverrideRequest();
            }
            mOverrideRequestController.handleBaseStateChanged();
            updatePendingStateLocked();

            if (!mPendingState.isPresent()) {
                // If the change in base state didn't result in a change of the pending state
                // commitPendingState() will never be called and the callbacks will never be
                // notified of the change.
                notifyDeviceStateInfoChangedAsync();
            }

            mHandler.post(this::notifyPolicyIfNeeded);
        }
    }

    /**
     * Tries to update the current pending state with the current requested state. Must call
     * {@link #notifyPolicyIfNeeded()} to actually notify the policy that the state is being
     * changed.
     *
     * @return {@code true} if the pending state has changed as a result of this call, {@code false}
     * otherwise.
     */
    private boolean updatePendingStateLocked() {
        if (mPendingState.isPresent()) {
            // Have pending state, can not configure a new state until the state is committed.
            return false;
        }

        final DeviceState stateToConfigure;
        if (mActiveOverride.isPresent()) {
            stateToConfigure = getStateLocked(mActiveOverride.get().getRequestedState()).get();
        } else if (mBaseState.isPresent()
                && isSupportedStateLocked(mBaseState.get().getIdentifier())) {
            // Base state could have recently become unsupported after a change in supported states.
            stateToConfigure = mBaseState.get();
        } else {
            stateToConfigure = null;
        }

        if (stateToConfigure == null) {
            // No currently requested state.
            return false;
        }

        if (mCommittedState.isPresent() && stateToConfigure.equals(mCommittedState.get())) {
            // The state requesting to be committed already matches the current committed state.
            return false;
        }

        mPendingState = Optional.of(stateToConfigure);
        mIsPolicyWaitingForState = true;
        return true;
    }

    /**
     * Notifies the policy to configure the supplied state. Should not be called with {@link #mLock}
     * held.
     */
    private void notifyPolicyIfNeeded() {
        if (Thread.holdsLock(mLock)) {
            Throwable error = new Throwable("Attempting to notify DeviceStatePolicy with service"
                    + " lock held");
            error.fillInStackTrace();
            Slog.w(TAG, error);
        }
        int state;
        synchronized (mLock) {
            if (!mIsPolicyWaitingForState) {
                return;
            }
            mIsPolicyWaitingForState = false;
            state = mPendingState.get().getIdentifier();
        }

        if (DEBUG) {
            Slog.d(TAG, "Notifying policy to configure state: " + state);
        }
        mDeviceStatePolicy.configureDeviceForState(state, this::commitPendingState);
    }

    /**
     * Commits the current pending state after a callback from the {@link DeviceStatePolicy}.
     *
     * <pre>
     *              -------------    -----------              -------------
     * Provider ->  | Requested | -> | Pending | -> Policy -> | Committed |
     *              -------------    -----------              -------------
     * </pre>
     * <p>
     * When a new state is requested it immediately enters the requested state. Once the policy is
     * available to accept a new state, which could also be immediately if there is no current
     * pending state at the point of request, the policy is notified and a callback is provided to
     * trigger the state to be committed.
     * </p>
     */
    private void commitPendingState() {
        synchronized (mLock) {
            final DeviceState newState = mPendingState.get();
            if (DEBUG) {
                Slog.d(TAG, "Committing state: " + newState);
            }

            FrameworkStatsLog.write(FrameworkStatsLog.DEVICE_STATE_CHANGED,
                    newState.getIdentifier(), !mCommittedState.isPresent());

            mCommittedState = Optional.of(newState);
            mPendingState = Optional.empty();
            updatePendingStateLocked();

            // Notify callbacks of a change.
            notifyDeviceStateInfoChangedAsync();

            // The top request could have come in while the service was awaiting callback
            // from the policy. In that case we only set it to active if it matches the
            // current committed state, otherwise it will be set to active when its
            // requested state is committed.
            OverrideRequest activeRequest = mActiveOverride.orElse(null);
            if (activeRequest != null
                    && activeRequest.getRequestedState() == newState.getIdentifier()) {
                ProcessRecord processRecord = mProcessRecords.get(activeRequest.getPid());
                if (processRecord != null) {
                    processRecord.notifyRequestActiveAsync(activeRequest.getToken());
                }
            }

            // Try to configure the next state if needed.
            mHandler.post(this::notifyPolicyIfNeeded);
        }
    }

    private void notifyDeviceStateInfoChangedAsync() {
        synchronized (mLock) {
            if (mProcessRecords.size() == 0) {
                return;
            }

            ArrayList<ProcessRecord> registeredProcesses = new ArrayList<>();
            for (int i = 0; i < mProcessRecords.size(); i++) {
                registeredProcesses.add(mProcessRecords.valueAt(i));
            }

            DeviceStateInfo info = getDeviceStateInfoLocked();

            for (int i = 0; i < registeredProcesses.size(); i++) {
                registeredProcesses.get(i).notifyDeviceStateInfoAsync(info);
            }
        }
    }

    private void onOverrideRequestStatusChangedLocked(@NonNull OverrideRequest request,
            @OverrideRequestController.RequestStatus int status) {
        if (status == STATUS_ACTIVE) {
            mActiveOverride = Optional.of(request);
        } else if (status == STATUS_CANCELED) {
            if (mActiveOverride.isPresent() && mActiveOverride.get() == request) {
                mActiveOverride = Optional.empty();
            }
        } else {
            throw new IllegalArgumentException("Unknown request status: " + status);
        }

        boolean updatedPendingState = updatePendingStateLocked();

        ProcessRecord processRecord = mProcessRecords.get(request.getPid());
        if (processRecord == null) {
            // If the process is no longer registered with the service, for example if it has died,
            // there is no need to notify it of a change in request status.
            mHandler.post(this::notifyPolicyIfNeeded);
            return;
        }

        if (status == STATUS_ACTIVE) {
            if (!updatedPendingState && !mPendingState.isPresent()) {
                // If the pending state was not updated and there is not currently a pending state
                // then this newly active request will never be notified of a change in state.
                // Schedule the notification now.
                processRecord.notifyRequestActiveAsync(request.getToken());
            }
        } else {
            processRecord.notifyRequestCanceledAsync(request.getToken());
        }

        mHandler.post(this::notifyPolicyIfNeeded);
    }

    private void registerProcess(int pid, IDeviceStateManagerCallback callback) {
        synchronized (mLock) {
            if (mProcessRecords.contains(pid)) {
                throw new SecurityException("The calling process has already registered an"
                        + " IDeviceStateManagerCallback.");
            }

            ProcessRecord record = new ProcessRecord(callback, pid, this::handleProcessDied,
                    mHandler);
            try {
                callback.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mProcessRecords.put(pid, record);

            DeviceStateInfo currentInfo = mCommittedState.isPresent()
                    ? getDeviceStateInfoLocked() : null;
            if (currentInfo != null) {
                // If there is not a committed state we'll wait to notify the process of the initial
                // value.
                record.notifyDeviceStateInfoAsync(currentInfo);
            }
        }
    }

    private void handleProcessDied(ProcessRecord processRecord) {
        synchronized (mLock) {
            mProcessRecords.remove(processRecord.mPid);
            mOverrideRequestController.handleProcessDied(processRecord.mPid);
        }
    }

    private void requestStateInternal(int state, int flags, int callingPid,
            @NonNull IBinder token) {
        synchronized (mLock) {
            final ProcessRecord processRecord = mProcessRecords.get(callingPid);
            if (processRecord == null) {
                throw new IllegalStateException("Process " + callingPid
                        + " has no registered callback.");
            }

            if (mOverrideRequestController.hasRequest(token)) {
                throw new IllegalStateException("Request has already been made for the supplied"
                        + " token: " + token);
            }

            final Optional<DeviceState> deviceState = getStateLocked(state);
            if (!deviceState.isPresent()) {
                throw new IllegalArgumentException("Requested state: " + state
                        + " is not supported.");
            }

            OverrideRequest request = new OverrideRequest(token, callingPid, state, flags);
            mOverrideRequestController.addRequest(request);
        }
    }

    private void cancelStateRequestInternal(int callingPid) {
        synchronized (mLock) {
            final ProcessRecord processRecord = mProcessRecords.get(callingPid);
            if (processRecord == null) {
                throw new IllegalStateException("Process " + callingPid
                        + " has no registered callback.");
            }
            mActiveOverride.ifPresent(mOverrideRequestController::cancelRequest);
        }
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DEVICE STATE MANAGER (dumpsys device_state)");

        synchronized (mLock) {
            pw.println("  mCommittedState=" + mCommittedState);
            pw.println("  mPendingState=" + mPendingState);
            pw.println("  mBaseState=" + mBaseState);
            pw.println("  mOverrideState=" + getOverrideState());

            final int processCount = mProcessRecords.size();
            pw.println();
            pw.println("Registered processes: size=" + processCount);
            for (int i = 0; i < processCount; i++) {
                ProcessRecord processRecord = mProcessRecords.valueAt(i);
                pw.println("  " + i + ": mPid=" + processRecord.mPid);
            }

            mOverrideRequestController.dumpInternal(pw);
        }
    }

    /**
     * Allow top processes to request or cancel a device state change. If the calling process ID is
     * not the top app, then check if this process holds the
     * {@link android.Manifest.permission.CONTROL_DEVICE_STATE} permission. If the calling process
     * is the top app, check to verify they are requesting a state we've deemed to be able to be
     * available for an app process to request. States that can be requested are based around
     * features that we've created that require specific device state overrides.
     * @param callingPid Process ID that is requesting this state change
     * @param state state that is being requested.
     */
    private void assertCanRequestDeviceState(int callingPid, int state) {
        final WindowProcessController topApp = mActivityTaskManagerInternal.getTopApp();
        if (topApp == null || topApp.getPid() != callingPid
                || !isStateAvailableForAppRequests(state)) {
            getContext().enforceCallingOrSelfPermission(CONTROL_DEVICE_STATE,
                    "Permission required to request device state, "
                            + "or the call must come from the top app "
                            + "and be a device state that is available for apps to request.");
        }
    }

    /**
     * Checks if the process can control the device state. If the calling process ID is
     * not the top app, then check if this process holds the CONTROL_DEVICE_STATE permission.
     *
     * @param callingPid Process ID that is requesting this state change
     */
    private void assertCanControlDeviceState(int callingPid) {
        final WindowProcessController topApp = mActivityTaskManagerInternal.getTopApp();
        if (topApp == null || topApp.getPid() != callingPid) {
            getContext().enforceCallingOrSelfPermission(CONTROL_DEVICE_STATE,
                    "Permission required to request device state, "
                            + "or the call must come from the top app.");
        }
    }

    private boolean isStateAvailableForAppRequests(int state) {
        synchronized (mLock) {
            return mDeviceStatesAvailableForAppRequests.contains(state);
        }
    }

    /**
     * Adds device state values that are available to be requested by the top level app.
     */
    @GuardedBy("mLock")
    private void readStatesAvailableForRequestFromApps() {
        mDeviceStatesAvailableForAppRequests = new HashSet<>();
        String[] availableAppStatesConfigIdentifiers = getContext().getResources()
                .getStringArray(R.array.config_deviceStatesAvailableForAppRequests);
        for (int i = 0; i < availableAppStatesConfigIdentifiers.length; i++) {
            String identifierToFetch = availableAppStatesConfigIdentifiers[i];
            int configValueIdentifier = getContext().getResources()
                    .getIdentifier(identifierToFetch, "integer", "android");
            int state = getContext().getResources().getInteger(configValueIdentifier);
            if (isValidState(state)) {
                mDeviceStatesAvailableForAppRequests.add(state);
            } else {
                Slog.e(TAG, "Invalid device state was found in the configuration file. State id: "
                        + state);
            }
        }
    }

    @GuardedBy("mLock")
    private boolean isValidState(int state) {
        for (int i = 0; i < mDeviceStates.size(); i++) {
            if (state == mDeviceStates.valueAt(i).getIdentifier()) {
                return true;
            }
        }
        return false;
    }

    private final class DeviceStateProviderListener implements DeviceStateProvider.Listener {
        @Override
        public void onSupportedDeviceStatesChanged(DeviceState[] newDeviceStates) {
            if (newDeviceStates.length == 0) {
                throw new IllegalArgumentException("Supported device states must not be empty");
            }
            updateSupportedStates(newDeviceStates);
        }

        @Override
        public void onStateChanged(
                @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier) {
            if (identifier < MINIMUM_DEVICE_STATE || identifier > MAXIMUM_DEVICE_STATE) {
                throw new IllegalArgumentException("Invalid identifier: " + identifier);
            }

            setBaseState(identifier);
        }
    }

    private static final class ProcessRecord implements IBinder.DeathRecipient {
        public interface DeathListener {
            void onProcessDied(ProcessRecord record);
        }

        private static final int STATUS_ACTIVE = 0;

        private static final int STATUS_SUSPENDED = 1;

        private static final int STATUS_CANCELED = 2;

        @IntDef(prefix = {"STATUS_"}, value = {
                STATUS_ACTIVE,
                STATUS_SUSPENDED,
                STATUS_CANCELED
        })
        @Retention(RetentionPolicy.SOURCE)
        private @interface RequestStatus {}

        private final IDeviceStateManagerCallback mCallback;
        private final int mPid;
        private final DeathListener mDeathListener;
        private final Handler mHandler;

        private final WeakHashMap<IBinder, Integer> mLastNotifiedStatus = new WeakHashMap<>();

        ProcessRecord(IDeviceStateManagerCallback callback, int pid, DeathListener deathListener,
                Handler handler) {
            mCallback = callback;
            mPid = pid;
            mDeathListener = deathListener;
            mHandler = handler;
        }

        @Override
        public void binderDied() {
            mDeathListener.onProcessDied(this);
        }

        public void notifyDeviceStateInfoAsync(@NonNull DeviceStateInfo info) {
            mHandler.post(() -> {
                try {
                    mCallback.onDeviceStateInfoChanged(info);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify process " + mPid + " that device state changed.",
                            ex);
                }
            });
        }

        public void notifyRequestActiveAsync(IBinder token) {
            @RequestStatus Integer lastStatus = mLastNotifiedStatus.get(token);
            if (lastStatus != null
                    && (lastStatus == STATUS_ACTIVE || lastStatus == STATUS_CANCELED)) {
                return;
            }

            mLastNotifiedStatus.put(token, STATUS_ACTIVE);
            mHandler.post(() -> {
                try {
                    mCallback.onRequestActive(token);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify process " + mPid + " that request state changed.",
                            ex);
                }
            });
        }

        public void notifyRequestCanceledAsync(IBinder token) {
            @RequestStatus Integer lastStatus = mLastNotifiedStatus.get(token);
            if (lastStatus != null && lastStatus == STATUS_CANCELED) {
                return;
            }

            mLastNotifiedStatus.put(token, STATUS_CANCELED);
            mHandler.post(() -> {
                try {
                    mCallback.onRequestCanceled(token);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify process " + mPid + " that request state changed.",
                            ex);
                }
            });
        }
    }

    /** Implementation of {@link IDeviceStateManager} published as a binder service. */
    private final class BinderService extends IDeviceStateManager.Stub {
        @Override // Binder call
        public DeviceStateInfo getDeviceStateInfo() {
            synchronized (mLock) {
                return getDeviceStateInfoLocked();
            }
        }

        @Override // Binder call
        public void registerCallback(IDeviceStateManagerCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Device state callback must not be null.");
            }

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                registerProcess(callingPid, callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void requestState(IBinder token, int state, int flags) {
            final int callingPid = Binder.getCallingPid();
            // Allow top processes to request a device state change
            // If the calling process ID is not the top app, then we check if this process
            // holds a permission to CONTROL_DEVICE_STATE
            assertCanRequestDeviceState(callingPid, state);

            if (token == null) {
                throw new IllegalArgumentException("Request token must not be null.");
            }

            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                requestStateInternal(state, flags, callingPid, token);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override // Binder call
        public void cancelStateRequest() {
            final int callingPid = Binder.getCallingPid();
            // Allow top processes to cancel a device state change
            // If the calling process ID is not the top app, then we check if this process
            // holds a permission to CONTROL_DEVICE_STATE
            assertCanControlDeviceState(callingPid);

            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                cancelStateRequestInternal(callingPid);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override // Binder call
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver result) {
            new DeviceStateManagerShellCommand(DeviceStateManagerService.this)
                    .exec(this, in, out, err, args, callback, result);
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /** Implementation of {@link DeviceStateManagerInternal} published as a local service. */
    private final class LocalService extends DeviceStateManagerInternal {
        @Override
        public int[] getSupportedStateIdentifiers() {
            synchronized (mLock) {
                return getSupportedStateIdentifiersLocked();
            }
        }
    }
}
