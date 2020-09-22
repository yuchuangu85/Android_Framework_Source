
package com.android.server.wifi;

import android.annotation.NonNull;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 *
 */
public class BaseWifiDiagnostics {
    public static final byte CONNECTION_EVENT_STARTED = 0;
    public static final byte CONNECTION_EVENT_SUCCEEDED = 1;
    public static final byte CONNECTION_EVENT_FAILED = 2;
    public static final byte CONNECTION_EVENT_TIMEOUT = 3;

    protected final WifiNative mWifiNative;

    protected String mFirmwareVersion;
    protected String mDriverVersion;
    protected int mSupportedFeatureSet;

    public BaseWifiDiagnostics(WifiNative wifiNative) {
        mWifiNative = wifiNative;
    }

    /**
     * start wifi HAL dependent logging features
     * @param ifaceName requesting to start logging
     */
    public synchronized void startLogging(@NonNull String ifaceName) {
        mFirmwareVersion = mWifiNative.getFirmwareVersion();
        mDriverVersion = mWifiNative.getDriverVersion();
        mSupportedFeatureSet = mWifiNative.getSupportedLoggerFeatureSet();
    }

    public synchronized void startPacketLog() { }

    public synchronized void stopPacketLog() { }

    /**
     * stop wifi HAL dependent logging features
     * @param ifaceName requesting to stop logging
     */
    public synchronized void stopLogging(@NonNull String ifaceName) { }

    /**
     * Inform the diagnostics module of a connection event.
     * @param event The type of connection event (see CONNECTION_EVENT_* constants)
     */
    public synchronized void reportConnectionEvent(byte event) {}

    public synchronized void captureBugReportData(int reason) { }

    public synchronized void captureAlertData(int errorCode, byte[] alertData) { }

    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        dump(pw);
        pw.println("*** logging disabled, no debug data ****");
    }

    /**
     * Starts taking a standard android bugreport
     * android will prompt the user to send the bugreport when it's complete
     * @param bugTitle Title of bugreport to generate
     * @param bugDetail Description of the bugreport to generate
     */
    public void takeBugReport(String bugTitle, String bugDetail) { }

    protected synchronized void dump(PrintWriter pw) {
        pw.println("Chipset information :-----------------------------------------------");
        pw.println("FW Version is: " + mFirmwareVersion);
        pw.println("Driver Version is: " + mDriverVersion);
        pw.println("Supported Feature set: " + mSupportedFeatureSet);
    }

    /** enables/disables wifi verbose logging */
    public synchronized void enableVerboseLogging(boolean verboseEnabled) { }

    /** enables packet fate monitoring */
    public void startPktFateMonitoring(@NonNull String ifaceName) {}

}
