/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.bluetooth.le;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;

import static java.util.Objects.requireNonNull;

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.bluetooth.Attributable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothScan;
import android.bluetooth.annotations.RequiresBluetoothLocationPermission;
import android.bluetooth.annotations.RequiresBluetoothScanPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class provides methods to perform scan related operations for Bluetooth LE devices. An
 * application can scan for a particular type of Bluetooth LE devices using {@link ScanFilter}. It
 * can also request different types of callbacks for delivering the result.
 *
 * <p>Use {@link BluetoothAdapter#getBluetoothLeScanner()} to get an instance of {@link
 * BluetoothLeScanner}.
 *
 * @see ScanFilter
 */
public final class BluetoothLeScanner {
    private static final String TAG = BluetoothLeScanner.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    /**
     * Extra containing a list of ScanResults. It can have one or more results if there was no
     * error. In case of error, {@link #EXTRA_ERROR_CODE} will contain the error code and this extra
     * will not be available.
     */
    public static final String EXTRA_LIST_SCAN_RESULT =
            "android.bluetooth.le.extra.LIST_SCAN_RESULT";

    /**
     * Optional extra indicating the error code, if any. The error code will be one of the
     * SCAN_FAILED_* codes in {@link ScanCallback}.
     */
    public static final String EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE";

    /**
     * Optional extra indicating the callback type, which will be one of CALLBACK_TYPE_* constants
     * in {@link ScanSettings}.
     *
     * @see ScanCallback#onScanResult(int, ScanResult)
     */
    public static final String EXTRA_CALLBACK_TYPE = "android.bluetooth.le.extra.CALLBACK_TYPE";

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mSource;
    private final Handler mHandler;
    private final Map<ScanCallback, BleScanCallbackWrapper> mLeScanClients;

    /** Use {@link BluetoothAdapter#getBluetoothLeScanner()} instead. */
    @Hide
    public BluetoothLeScanner(BluetoothAdapter bluetoothAdapter) {
        mAdapter = requireNonNull(bluetoothAdapter);
        mSource = mAdapter.getAttributionSource();
        mHandler = new Handler(Looper.getMainLooper());
        mLeScanClients = new HashMap<>();
    }

    /**
     * Start Bluetooth LE scan with default parameters and no filters. The scan results will be
     * delivered through {@code callback}. For unfiltered scans, scanning is stopped on screen off
     * to save power. Scanning is resumed when screen is turned on again. To avoid this, use {@link
     * #startScan(List, ScanSettings, ScanCallback)} with desired {@link ScanFilter}.
     *
     * <p>An app must have {@link android.Manifest.permission#ACCESS_COARSE_LOCATION
     * ACCESS_COARSE_LOCATION} permission in order to get results. An App targeting Android Q or
     * later must have {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION}
     * permission in order to get results.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_SCAN} permission. Additionally, an app must have the
     * {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} if it is used for BLE scan only mode
     * (when the adapter state is not {@link BluetoothAdapter#STATE_ON}).
     *
     * @param callback Callback used to deliver scan results.
     * @throws IllegalArgumentException If {@code callback} is null.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresBluetoothLocationPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_PRIVILEGED, BLUETOOTH_SCAN},
            conditional = true)
    public void startScan(final ScanCallback callback) {
        startScan(null, new ScanSettings.Builder().build(), callback);
    }

    /**
     * Start Bluetooth LE scan. The scan results will be delivered through {@code callback}. For
     * unfiltered scans, scanning is stopped on screen off to save power. Scanning is resumed when
     * screen is turned on again. To avoid this, do filtered scanning by using proper {@link
     * ScanFilter}.
     *
     * <p>An app must have {@link android.Manifest.permission#ACCESS_COARSE_LOCATION
     * ACCESS_COARSE_LOCATION} permission in order to get results. An App targeting Android Q or
     * later must have {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION}
     * permission in order to get results.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_SCAN} permission. Additionally, an app must have the
     * {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} if any of the following is true:
     *
     * <ul>
     *   <li>it is used for BLE scan only mode (when the adapter state is not {@link
     *       BluetoothAdapter#STATE_ON}).
     *   <li>the {@link ScanSettings} uses {@link ScanSettings#SCAN_MODE_AMBIENT_DISCOVERY}.
     *   <li>the {@link ScanSettings} uses batched scanning ({@link
     *       ScanSettings#getReportDelayMillis()} > 0) with {@link
     *       ScanSettings#SCAN_RESULT_TYPE_ABBREVIATED}.
     *   <li>a {@link ScanFilter} has a device address set, and either the address type is not
     *       {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC} or the IRK is not null.
     * </ul>
     *
     * @param filters {@link ScanFilter}s for finding exact BLE devices.
     * @param settings Settings for the scan.
     * @param callback Callback used to deliver scan results.
     * @throws IllegalArgumentException If {@code settings} or {@code callback} is null.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresBluetoothLocationPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_PRIVILEGED, BLUETOOTH_SCAN},
            conditional = true)
    public void startScan(
            List<ScanFilter> filters, ScanSettings settings, final ScanCallback callback) {
        doStartScan(filters, settings, /* workSource */ null, callback, /* callbackIntent= */ null);
    }

    /**
     * Start Bluetooth LE scan using a {@link PendingIntent}. The scan results will be delivered via
     * the PendingIntent. Use this method of scanning if your process is not always running and it
     * should be started when scan results are available.
     *
     * <p>An app must have {@link android.Manifest.permission#ACCESS_COARSE_LOCATION
     * ACCESS_COARSE_LOCATION} permission in order to get results. An App targeting Android Q or
     * later must have {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION}
     * permission in order to get results.
     *
     * <p>When the PendingIntent is delivered, the Intent passed to the receiver or activity will
     * contain one or more of the extras {@link #EXTRA_CALLBACK_TYPE}, {@link #EXTRA_ERROR_CODE} and
     * {@link #EXTRA_LIST_SCAN_RESULT} to indicate the result of the scan.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_SCAN} permission. Additionally, an app must have the
     * {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} if any of the following is true:
     *
     * <ul>
     *   <li>it is used for BLE scan only mode (when the adapter state is not {@link
     *       BluetoothAdapter#STATE_ON}).
     *   <li>the {@link ScanSettings} uses {@link ScanSettings#SCAN_MODE_AMBIENT_DISCOVERY}.
     *   <li>the {@link ScanSettings} uses batched scanning ({@link
     *       ScanSettings#getReportDelayMillis()} > 0) with {@link
     *       ScanSettings#SCAN_RESULT_TYPE_ABBREVIATED}.
     *   <li>a {@link ScanFilter} has a device address set, and either the address type is not
     *       {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC} or the IRK is not null.
     * </ul>
     *
     * @param filters Optional list of ScanFilters for finding exact BLE devices.
     * @param settings Optional settings for the scan.
     * @param callbackIntent The PendingIntent to deliver the result to.
     * @return Returns 0 for success or an error code from {@link ScanCallback} if the scan request
     *     could not be sent.
     * @see #stopScan(PendingIntent)
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresBluetoothLocationPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_PRIVILEGED, BLUETOOTH_SCAN},
            conditional = true)
    public int startScan(
            @Nullable List<ScanFilter> filters,
            @Nullable ScanSettings settings,
            @NonNull PendingIntent callbackIntent) {
        return doStartScan(
                filters,
                settings != null ? settings : new ScanSettings.Builder().build(),
                /* workSource */ null,
                /* callback */ null,
                callbackIntent);
    }

    /**
     * Start Bluetooth LE scan. Same as {@link #startScan(ScanCallback)} but allows the caller to
     * specify on behalf of which application(s) the work is being done.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_SCAN} permission. Additionally, an app must have the
     * {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} if it is used for BLE scan only mode
     * (when the adapter state is not {@link BluetoothAdapter#STATE_ON}).
     *
     * <p>This method also requires the {@link android.Manifest.permission#UPDATE_DEVICE_STATS}
     * permission if the {@code workSource} is not null.
     *
     * @param workSource {@link WorkSource} identifying the application(s) for which to blame for
     *     the scan.
     * @param callback Callback used to deliver scan results.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresBluetoothLocationPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_PRIVILEGED, BLUETOOTH_SCAN, UPDATE_DEVICE_STATS},
            conditional = true)
    public void startScanFromSource(final WorkSource workSource, final ScanCallback callback) {
        startScanFromSource(null, new ScanSettings.Builder().build(), workSource, callback);
    }

    /**
     * Start Bluetooth LE scan. Same as {@link #startScan(List, ScanSettings, ScanCallback)} but
     * allows the caller to specify on behalf of which application(s) the work is being done.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_SCAN} permission. Additionally, an app must have the
     * {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} if any of the following is true:
     *
     * <ul>
     *   <li>it is used for BLE scan only mode (when the adapter state is not {@link
     *       BluetoothAdapter#STATE_ON}).
     *   <li>the {@link ScanSettings} uses {@link ScanSettings#SCAN_MODE_AMBIENT_DISCOVERY}.
     *   <li>the {@link ScanSettings} uses batched scanning ({@link
     *       ScanSettings#getReportDelayMillis()} > 0) with {@link
     *       ScanSettings#SCAN_RESULT_TYPE_ABBREVIATED}.
     *   <li>a {@link ScanFilter} has a device address set, and either the address type is not
     *       {@link BluetoothDevice#ADDRESS_TYPE_PUBLIC} or the IRK is not null.
     * </ul>
     *
     * <p>This method also requires the {@link android.Manifest.permission#UPDATE_DEVICE_STATS}
     * permission if the {@code workSource} is not null.
     *
     * @param filters {@link ScanFilter}s for finding exact BLE devices.
     * @param settings Settings for the scan.
     * @param workSource {@link WorkSource} identifying the application(s) for which to blame for
     *     the scan.
     * @param callback Callback used to deliver scan results.
     */
    @Hide
    @SystemApi
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresBluetoothLocationPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_PRIVILEGED, BLUETOOTH_SCAN, UPDATE_DEVICE_STATS},
            conditional = true)
    public void startScanFromSource(
            List<ScanFilter> filters,
            ScanSettings settings,
            final WorkSource workSource,
            final ScanCallback callback) {
        doStartScan(filters, settings, workSource, callback, /* callbackIntent= */ null);
    }

    @RequiresPermission(
            allOf = {BLUETOOTH_PRIVILEGED, BLUETOOTH_SCAN},
            conditional = true)
    private int doStartScan(
            List<ScanFilter> filters,
            ScanSettings settings,
            final WorkSource workSource,
            final ScanCallback callback,
            final PendingIntent callbackIntent) {
        if (callback == null && callbackIntent == null) {
            throw new IllegalArgumentException("callback is null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings is null");
        }
        if (!BluetoothLeUtils.checkAdapterStateOn(mAdapter)) {
            Log.w(TAG, "doStartScan(): BLE is not available");
            return postCallbackErrorOrReturn(callback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        }
        synchronized (mLeScanClients) {
            if (callback != null && mLeScanClients.containsKey(callback)) {
                return postCallbackErrorOrReturn(
                        callback, ScanCallback.SCAN_FAILED_ALREADY_STARTED);
            }
            IBluetoothScan scan = mAdapter.getBluetoothScan();
            if (scan == null) {
                return postCallbackErrorOrReturn(callback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            }
            if (!isSettingsConfigAllowedForScan(settings)) {
                return postCallbackErrorOrReturn(
                        callback, ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            }
            if (!isHardwareResourcesAvailableForScan(settings)) {
                return postCallbackErrorOrReturn(
                        callback, ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES);
            }
            if (!isSettingsAndFilterComboAllowed(settings, filters)) {
                return postCallbackErrorOrReturn(
                        callback, ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            }
            if (!mAdapter.isOffloadedScanBatchingSupported()
                    && settings.getReportDelayMillis() > 0) {
                Log.w(TAG, "Batch scan requested but not supported");
                return postCallbackErrorOrReturn(
                        callback, ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);
            }
            // If no filters are provided, initialize an empty list to simplify downstream logic
            if (filters == null) {
                filters = new ArrayList<>();
            }
            if (callback != null) {
                new BleScanCallbackWrapper(scan, filters, settings, workSource, callback)
                        .registerAndStartScan();
            } else {
                try {
                    scan.registerPiAndStartScan(callbackIntent, settings, filters, mSource);
                } catch (RemoteException e) {
                    return ScanCallback.SCAN_FAILED_INTERNAL_ERROR;
                }
            }
        }
        return ScanCallback.NO_ERROR;
    }

    /** Stops an ongoing Bluetooth LE scan. */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresPermission(BLUETOOTH_SCAN)
    public void stopScan(ScanCallback callback) {
        if (!BluetoothLeUtils.checkAdapterStateOn(mAdapter)) {
            Log.w(TAG, "stopScan(callback): BLE is not available");
            return;
        }
        synchronized (mLeScanClients) {
            BleScanCallbackWrapper wrapper = mLeScanClients.remove(callback);
            if (wrapper == null) {
                Log.d(TAG, "could not find callback wrapper");
                return;
            }
            wrapper.stopLeScan();
        }
    }

    /**
     * Stops an ongoing Bluetooth LE scan started using a PendingIntent. When creating the
     * PendingIntent parameter, please do not use the FLAG_CANCEL_CURRENT flag. Otherwise, the stop
     * scan may have no effect.
     *
     * @param callbackIntent The PendingIntent that was used to start the scan.
     * @see #startScan(List, ScanSettings, PendingIntent)
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresPermission(BLUETOOTH_SCAN)
    public void stopScan(PendingIntent callbackIntent) {
        if (!BluetoothLeUtils.checkAdapterStateOn(mAdapter)) {
            Log.w(TAG, "stopScan(callbackIntent): BLE is not available");
            return;
        }
        try {
            IBluetoothScan scan = mAdapter.getBluetoothScan();
            if (scan == null) {
                Log.w(TAG, "stopScan called after bluetooth has been turned off");
                return;
            }
            scan.stopScanForIntent(callbackIntent, mSource);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to stop scan", e);
        }
    }

    /**
     * Flush pending batch scan results stored in Bluetooth controller. This will return Bluetooth
     * LE scan results batched on bluetooth controller. Returns immediately, batch scan results data
     * will be delivered through the {@code callback}.
     *
     * @param callback Callback of the Bluetooth LE Scan, it has to be the same instance as the one
     *     used to start scan.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothScanPermission
    @RequiresPermission(BLUETOOTH_SCAN)
    public void flushPendingScanResults(ScanCallback callback) {
        if (!BluetoothLeUtils.checkAdapterStateOn(mAdapter)) {
            Log.w(TAG, "flushPendingScanResults(): BLE is not available");
            return;
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null!");
        }
        synchronized (mLeScanClients) {
            BleScanCallbackWrapper wrapper = mLeScanClients.get(callback);
            if (wrapper == null) {
                return;
            }
            wrapper.flushPendingBatchResults();
        }
    }

    /**
     * Start truncated scan.
     *
     * @removed this is not used anywhere
     */
    @Hide
    @Deprecated
    @SystemApi
    @RequiresBluetoothScanPermission
    @RequiresPermission(BLUETOOTH_SCAN)
    public void startTruncatedScan(
            List<TruncatedFilter> truncatedFilters,
            ScanSettings settings,
            final ScanCallback callback) {
        Log.wtf(TAG, "startTruncatedScan is deprecated and not supported; Will be removed soon");
    }

    /** Cleans up scan clients. Should be called when bluetooth is down. */
    @Hide
    @RequiresNoPermission
    public void cleanup() {
        mLeScanClients.clear();
    }

    /** Bluetooth Scan interface callbacks */
    private final class BleScanCallbackWrapper extends IScannerCallback.Stub {
        private final IBluetoothScan mScan;
        private final List<ScanFilter> mFilters;
        private final ScanSettings mSettings;
        private final WorkSource mWorkSource;
        private final ScanCallback mCallback;

        // 0: not registered
        // > 0: registered and scan started
        private int mScannerId;

        BleScanCallbackWrapper(
                IBluetoothScan bluetoothScan,
                List<ScanFilter> filters,
                ScanSettings settings,
                WorkSource workSource,
                ScanCallback scanCallback) {
            mScan = bluetoothScan;
            mFilters = filters;
            mSettings = settings;
            mWorkSource = workSource;
            mCallback = scanCallback;
            mScannerId = 0;
        }

        // The permission {@link android.Manifest.permission#UPDATE_DEVICE_STATS} is required by
        // IBluetoothScan#registerAndStartScan only when `mWorkSource` is non-null. The @SystemApi
        // methods that provide a WorkSource, such as `startScanFromSource()`, are already annotated
        // with this permission. This suppression avoids propagating the conditional requirement to
        // Public API methods that do not use a WorkSource.
        @SuppressLint("IncorrectRequiresPermissionPropagation")
        @RequiresPermission(BLUETOOTH_SCAN)
        void registerAndStartScan() {
            synchronized (this) {
                try {
                    mScan.registerAndStartScan(this, mSettings, mFilters, mWorkSource, mSource);
                    mLeScanClients.put(mCallback, this);
                } catch (RemoteException e) {
                    Log.e(TAG, "registerAndStartScan(): Exception", e);
                    postCallbackError(mCallback, ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
                }
            }
        }

        @RequiresPermission(BLUETOOTH_SCAN)
        void stopLeScan() {
            synchronized (this) {
                if (mScannerId <= 0) {
                    Log.e(TAG, "stopLeScan(): Error state, mScannerId=" + mScannerId);
                    return;
                }
                try {
                    mScan.stopScan(mScannerId, mSource);
                    mScan.unregisterScanner(mScannerId, mSource);
                } catch (RemoteException e) {
                    Log.e(TAG, "stopLeScan(): Failed to stop scan and unregister", e);
                }
                mScannerId = -1;
            }
        }

        @RequiresPermission(BLUETOOTH_SCAN)
        void flushPendingBatchResults() {
            synchronized (this) {
                if (mScannerId <= 0) {
                    Log.e(TAG, "flushPendingBatchResults(): Error state, mScannerId=" + mScannerId);
                    return;
                }
                try {
                    mScan.flushPendingBatchResults(mScannerId, mSource);
                } catch (RemoteException e) {
                    Log.e(TAG, "flushPendingBatchResults(): Failed to get pending scan results", e);
                }
            }
        }

        /** Application interface registered - app is ready to go */
        @Override
        public void onScannerRegistered(int status, int scannerId) {
            String header =
                    "onScannerRegistered(status=" + status + ", scannerId=" + scannerId + "): ";
            Log.d(TAG, header + "mScannerId=" + mScannerId);
            synchronized (this) {
                if (status == ScanCallback.NO_ERROR) {
                    mScannerId = scannerId;
                } else {
                    // If scanning too frequently, don't report anything to the app.
                    if (status == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                        Log.e(TAG, header + "Failed. App is scanning too frequently");
                    } else {
                        postCallbackError(
                                mCallback,
                                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED);
                    }
                    mLeScanClients.remove(mCallback);
                }
            }
        }

        /** Callback reporting an LE scan result. */
        @Hide
        @Override
        public void onScanResult(final ScanResult scanResult) {
            Attributable.setAttributionSource(scanResult, mSource);
            if (VDBG) {
                Log.d(TAG, "onScanResult(): " + scanResult.toString());
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onScanResult(): mScannerId=" + mScannerId);
            }

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mScannerId <= 0) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "onScanResult(): Ignoring result as scan stopped");
                    }
                    return;
                }
            }
            mHandler.post(
                    () -> {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "onScanResult(): Handler run");
                        }
                        mCallback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
                    });
        }

        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            Attributable.setAttributionSource(results, mSource);
            mHandler.post(() -> mCallback.onBatchScanResults(results));
        }

        @Override
        public void onFoundOrLost(final boolean onFound, final ScanResult scanResult) {
            Attributable.setAttributionSource(scanResult, mSource);
            if (VDBG) {
                Log.d(TAG, "onFoundOrLost(): onFound=" + onFound + " " + scanResult.toString());
            }

            // Check null in case the scan has been stopped
            synchronized (this) {
                if (mScannerId <= 0) {
                    return;
                }
            }
            int callbackType =
                    onFound
                            ? ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                            : ScanSettings.CALLBACK_TYPE_MATCH_LOST;
            mHandler.post(() -> mCallback.onScanResult(callbackType, scanResult));
        }

        @Override
        public void onScanManagerErrorCallback(final int errorCode) {
            if (VDBG) {
                Log.d(TAG, "onScanManagerErrorCallback(): errorCode=" + errorCode);
            }
            synchronized (this) {
                if (mScannerId <= 0) {
                    return;
                }
            }
            postCallbackError(mCallback, errorCode);
        }
    }

    private int postCallbackErrorOrReturn(final ScanCallback callback, final int errorCode) {
        if (callback == null) {
            return errorCode;
        } else {
            postCallbackError(callback, errorCode);
            return ScanCallback.NO_ERROR;
        }
    }

    private void postCallbackError(final ScanCallback callback, final int errorCode) {
        mHandler.post(() -> callback.onScanFailed(errorCode));
    }

    private boolean isSettingsConfigAllowedForScan(ScanSettings settings) {
        if (mAdapter.isOffloadedFilteringSupported()) {
            return true;
        }
        final int callbackType = settings.getCallbackType();
        // Only support regular scan if no offloaded filter support.
        if (callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                && settings.getReportDelayMillis() == 0) {
            return true;
        }
        return false;
    }

    private static boolean isSettingsAndFilterComboAllowed(
            ScanSettings settings, List<ScanFilter> filterList) {
        final int callbackType = settings.getCallbackType();
        // If onlost/onfound is requested, a non-empty filter is expected
        if ((callbackType
                        & (ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                | ScanSettings.CALLBACK_TYPE_MATCH_LOST))
                != 0) {
            if (filterList == null) {
                return false;
            }
            for (ScanFilter filter : filterList) {
                if (filter.isAllFieldsEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    @RequiresPermission(BLUETOOTH_SCAN)
    private boolean isHardwareResourcesAvailableForScan(ScanSettings settings) {
        final int callbackType = settings.getCallbackType();
        if ((callbackType & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                || (callbackType & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
            // For onlost/onfound, we required hw support be available
            return (mAdapter.isOffloadedFilteringSupported()
                    && mAdapter.isHardwareTrackingFiltersAvailable());
        }
        return true;
    }
}
