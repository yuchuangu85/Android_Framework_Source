
package com.android.server.wifi;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 *
 */
public class BaseWifiDiagnostics {
    public static final byte CONNECTION_EVENT_STARTED = 0;
    public static final byte CONNECTION_EVENT_SUCCEEDED = 1;
    public static final byte CONNECTION_EVENT_FAILED = 2;

    protected final WifiNative mWifiNative;

    protected String mFirmwareVersion;
    protected String mDriverVersion;
    protected int mSupportedFeatureSet;

    public BaseWifiDiagnostics(WifiNative wifiNative) {
        mWifiNative = wifiNative;
    }

    public synchronized void startLogging(boolean verboseEnabled) {
        mFirmwareVersion = mWifiNative.getFirmwareVersion();
        mDriverVersion = mWifiNative.getDriverVersion();
        mSupportedFeatureSet = mWifiNative.getSupportedLoggerFeatureSet();
    }

    public synchronized void startPacketLog() { }

    public synchronized void stopPacketLog() { }

    public synchronized void stopLogging() { }

    /**
     * Inform the diagnostics module of a connection event.
     * @param connectionId A strictly increasing, non-negative, connection identifier
     * @param event The type of connection event (see CONNECTION_EVENT_* constants)
     */
    synchronized void reportConnectionEvent(long connectionId, byte event) {}

    public synchronized void captureBugReportData(int reason) { }

    public synchronized void captureAlertData(int errorCode, byte[] alertData) { }

    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        dump(pw);
        pw.println("*** firmware logging disabled, no debug data ****");
        pw.println("set config_wifi_enable_wifi_firmware_debugging to enable");
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
}