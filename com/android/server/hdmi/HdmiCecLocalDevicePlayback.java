/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.annotation.CallSuper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LocalePicker;
import com.android.internal.app.LocalePicker.LocaleInfo;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Locale;

/**
 * Represent a logical device of type Playback residing in Android system.
 */
public class HdmiCecLocalDevicePlayback extends HdmiCecLocalDeviceSource {
    private static final String TAG = "HdmiCecLocalDevicePlayback";

    private static final boolean SET_MENU_LANGUAGE =
            HdmiProperties.set_menu_language_enabled().orElse(false);

    // Used to keep the device awake while it is the active source. For devices that
    // cannot wake up via CEC commands, this address the inconvenience of having to
    // turn them on. True by default, and can be disabled (i.e. device can go to sleep
    // in active device status) by explicitly setting the system property
    // persist.sys.hdmi.keep_awake to false.
    // Lazily initialized - should call getWakeLock() to get the instance.
    private ActiveWakeLock mWakeLock;

    // Determines what action should be taken upon receiving Routing Control messages.
    @VisibleForTesting
    protected HdmiProperties.playback_device_action_on_routing_control_values
            mPlaybackDeviceActionOnRoutingControl = HdmiProperties
                    .playback_device_action_on_routing_control()
                    .orElse(HdmiProperties.playback_device_action_on_routing_control_values.NONE);

    HdmiCecLocalDevicePlayback(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        if (reason == mService.INITIATED_BY_ENABLE_CEC) {
            mService.setAndBroadcastActiveSource(mService.getPhysicalAddress(),
                    getDeviceInfo().getDeviceType(), Constants.ADDR_BROADCAST,
                    "HdmiCecLocalDevicePlayback#onAddressAllocated()");
        }
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        // Actively send out an OSD name to the TV to update the TV panel in case the TV
        // does not query the OSD name on time. This is not a required behavior by the spec.
        // It is used for some TVs that need the OSD name update but don't query it themselves.
        buildAndSendSetOsdName(Constants.ADDR_TV);
        if (mService.audioSystem() == null) {
            // If current device is not a functional audio system device,
            // send message to potential audio system device in the system to get the system
            // audio mode status. If no response, set to false.
            mService.sendCecCommand(HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                    mAddress, Constants.ADDR_AUDIO_SYSTEM), new SendMessageCallback() {
                        @Override
                        public void onSendCompleted(int error) {
                            if (error != SendMessageResult.SUCCESS) {
                                HdmiLogger.debug(
                                        "AVR did not respond to <Give System Audio Mode Status>");
                                mService.setSystemAudioActivated(false);
                            }
                        }
                    });
        }
        startQueuedActions();
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        mService.writeStringSystemProperty(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                String.valueOf(addr));
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        mCecMessageCache.flushAll();
        // We'll not invalidate the active source on the hotplug event to pass CETC 11.2.2-2 ~ 3.
        if (!connected) {
            getWakeLock().release();
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            return;
        }
        boolean wasActiveSource = isActiveSource();
        // Invalidate the internal active source record when going to standby
        mService.setActiveSource(Constants.ADDR_INVALID, Constants.INVALID_PHYSICAL_ADDRESS,
                "HdmiCecLocalDevicePlayback#onStandby()");
        boolean mTvSendStandbyOnSleep = mService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP)
                    == HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED;
        if (!wasActiveSource) {
            return;
        }
        if (initiatedByCec || !mTvSendStandbyOnSleep) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildInactiveSource(mAddress,
                            mService.getPhysicalAddress()));
            return;
        }
        switch (standbyAction) {
            case HdmiControlService.STANDBY_SCREEN_OFF:
                // Get latest setting value
                @HdmiControlManager.PowerControlMode
                String sendStandbyOnSleep = mService.getHdmiCecConfig().getStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
                switch (sendStandbyOnSleep) {
                    case HdmiControlManager.POWER_CONTROL_MODE_TV:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(mAddress, Constants.ADDR_TV));
                        break;
                    case HdmiControlManager.POWER_CONTROL_MODE_BROADCAST:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(mAddress,
                                        Constants.ADDR_BROADCAST));
                        break;
                    case HdmiControlManager.POWER_CONTROL_MODE_NONE:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildInactiveSource(mAddress,
                                        mService.getPhysicalAddress()));
                        break;
                }
                break;
            case HdmiControlService.STANDBY_SHUTDOWN:
                // ACTION_SHUTDOWN is taken as a signal to power off all the devices.
                mService.sendCecCommand(
                        HdmiCecMessageBuilder.buildStandby(mAddress, Constants.ADDR_BROADCAST));
                break;
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onInitializeCecComplete(int initiatedBy) {
        if (initiatedBy == HdmiControlService.INITIATED_BY_SCREEN_ON) {
            oneTouchPlay(new IHdmiControlCallback.Stub() {
                @Override
                public void onComplete(int result) {
                    if (result != HdmiControlManager.RESULT_SUCCESS) {
                        Slog.w(TAG, "Failed to complete One Touch Play. result=" + result);
                    }
                }
            });
        }
    }

    @Override
    @CallSuper
    @ServiceThreadOnly
    @VisibleForTesting
    protected void setActiveSource(int logicalAddress, int physicalAddress, String caller) {
        assertRunOnServiceThread();
        super.setActiveSource(logicalAddress, physicalAddress, caller);
        if (isActiveSource()) {
            getWakeLock().acquire();
        } else {
            getWakeLock().release();
        }
    }

    @ServiceThreadOnly
    private ActiveWakeLock getWakeLock() {
        assertRunOnServiceThread();
        if (mWakeLock == null) {
            if (SystemProperties.getBoolean(Constants.PROPERTY_KEEP_AWAKE, true)) {
                mWakeLock = new SystemWakeLock();
            } else {
                // Create a stub lock object that doesn't do anything about wake lock,
                // hence allows the device to go to sleep even if it's the active source.
                mWakeLock = new ActiveWakeLock() {
                    @Override
                    public void acquire() { }
                    @Override
                    public void release() { }
                    @Override
                    public boolean isHeld() { return false; }
                };
                HdmiLogger.debug("No wakelock is used to keep the display on.");
            }
        }
        return mWakeLock;
    }

    @Override
    protected boolean canGoToStandby() {
        return !getWakeLock().isHeld();
    }

    @Override
    @ServiceThreadOnly
    protected void onActiveSourceLost() {
        assertRunOnServiceThread();
        mService.pauseActiveMediaSessions();
        switch (mService.getHdmiCecConfig().getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST)) {
            case HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW:
                mService.standby();
                return;
            case HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE:
                return;
        }
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        wakeUpIfActiveSource();
        return super.handleUserControlPressed(message);
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SET_MENU_LANGUAGE) {
            return Constants.ABORT_UNRECOGNIZED_OPCODE;
        }

        try {
            String iso3Language = new String(message.getParams(), 0, 3, "US-ASCII");
            Locale currentLocale = mService.getContext().getResources().getConfiguration().locale;
            if (currentLocale.getISO3Language().equals(iso3Language)) {
                // Do not switch language if the new language is the same as the current one.
                // This helps avoid accidental country variant switching from en_US to en_AU
                // due to the limitation of CEC. See the warning below.
                return Constants.HANDLED;
            }

            // Don't use Locale.getAvailableLocales() since it returns a locale
            // which is not available on Settings.
            final List<LocaleInfo> localeInfos = LocalePicker.getAllAssetLocales(
                    mService.getContext(), false);
            for (LocaleInfo localeInfo : localeInfos) {
                if (localeInfo.getLocale().getISO3Language().equals(iso3Language)) {
                    // WARNING: CEC adopts ISO/FDIS-2 for language code, while Android requires
                    // additional country variant to pinpoint the locale. This keeps the right
                    // locale from being chosen. 'eng' in the CEC command, for instance,
                    // will always be mapped to en-AU among other variants like en-US, en-GB,
                    // an en-IN, which may not be the expected one.
                    LocalePicker.updateLocale(localeInfo.getLocale());
                    return Constants.HANDLED;
                }
            }
            Slog.w(TAG, "Can't handle <Set Menu Language> of " + iso3Language);
            return Constants.ABORT_INVALID_OPERAND;
        } catch (UnsupportedEncodingException e) {
            Slog.w(TAG, "Can't handle <Set Menu Language>", e);
            return Constants.ABORT_INVALID_OPERAND;
        }
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleSetSystemAudioMode(HdmiCecMessage message) {
        // System Audio Mode only turns on/off when Audio System broadcasts on/off message.
        // For device with type 4 and 5, it can set system audio mode on/off
        // when there is another audio system device connected into the system first.
        if (message.getDestination() != Constants.ADDR_BROADCAST
                || message.getSource() != Constants.ADDR_AUDIO_SYSTEM
                || mService.audioSystem() != null) {
            return Constants.HANDLED;
        }
        boolean setSystemAudioModeOn = HdmiUtils.parseCommandParamSystemAudioStatus(message);
        if (mService.isSystemAudioActivated() != setSystemAudioModeOn) {
            mService.setSystemAudioActivated(setSystemAudioModeOn);
        }
        return Constants.HANDLED;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleSystemAudioModeStatus(HdmiCecMessage message) {
        // Only directly addressed System Audio Mode Status message can change internal
        // system audio mode status.
        if (message.getDestination() == mAddress
                && message.getSource() == Constants.ADDR_AUDIO_SYSTEM) {
            boolean setSystemAudioModeOn = HdmiUtils.parseCommandParamSystemAudioStatus(message);
            if (mService.isSystemAudioActivated() != setSystemAudioModeOn) {
                mService.setSystemAudioActivated(setSystemAudioModeOn);
            }
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams(), 2);
        handleRoutingChangeAndInformation(physicalAddress, message);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingInformation(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        handleRoutingChangeAndInformation(physicalAddress, message);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (physicalAddress != mService.getPhysicalAddress()) {
            setActiveSource(physicalAddress,
                    "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
            return;
        }
        if (!isActiveSource()) {
            // If routing is changed to the device while Active Source, don't invalidate the
            // Active Source
            setActiveSource(physicalAddress,
                    "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
        }
        switch (mPlaybackDeviceActionOnRoutingControl) {
            case WAKE_UP_AND_SEND_ACTIVE_SOURCE:
                setAndBroadcastActiveSource(message, physicalAddress,
                        "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
                break;
            case WAKE_UP_ONLY:
                mService.wakeUp();
                break;
            case NONE:
                break;
        }
    }

    @Override
    protected int findKeyReceiverAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    protected int findAudioReceiverAddress() {
        if (mService.isSystemAudioActivated()) {
            return Constants.ADDR_AUDIO_SYSTEM;
        }
        return Constants.ADDR_TV;
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);

        assertRunOnServiceThread();
        checkIfPendingActionsCleared();
    }

    @Override
    protected void dump(final IndentingPrintWriter pw) {
        super.dump(pw);
        pw.println("isActiveSource(): " + isActiveSource());
    }

    // Wrapper interface over PowerManager.WakeLock
    private interface ActiveWakeLock {
        void acquire();
        void release();
        boolean isHeld();
    }

    private class SystemWakeLock implements ActiveWakeLock {
        private final WakeLock mWakeLock;
        public SystemWakeLock() {
            mWakeLock = mService.getPowerManager().newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.setReferenceCounted(false);
        }

        @Override
        public void acquire() {
            mWakeLock.acquire();
            HdmiLogger.debug("active source: %b. Wake lock acquired", isActiveSource());
        }

        @Override
        public void release() {
            mWakeLock.release();
            HdmiLogger.debug("Wake lock released");
        }

        @Override
        public boolean isHeld() {
            return mWakeLock.isHeld();
        }
    }
}
