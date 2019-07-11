package com.android.clockwork.cellular;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import com.android.clockwork.flags.UserAbsentRadiosOffObserver;
import com.android.clockwork.power.PowerTracker;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.google.android.clockwork.signaldetector.SignalStateDetector;
import com.google.android.clockwork.signaldetector.SignalStateModel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;

/** Test for {@link WearCellularMediator} */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = Config.NEWEST_SDK)
public class WearCellularMediatorTest {
    final ShadowApplication shadowApplication = ShadowApplication.getInstance();
    private static final String CELL_AUTO_SETTING_KEY = "clockwork_cell_auto_setting";
    private static final Uri CELL_AUTO_SETTING_URI =
        Settings.System.getUriFor(CELL_AUTO_SETTING_KEY);
    private static final Uri CELL_ON_URI = Settings.Global.getUriFor(Settings.Global.CELL_ON);

    private ContentResolver mContentResolver;
    private ContentValues contentValues;
    private Context mContext;
    private ShadowContentResolver mShadowContentResolver;
    private WearCellularMediator mMediator;

    @Captor ArgumentCaptor<Message> msgCaptor;
    @Mock Handler mockHandler;
    @Mock PowerTracker mockPowerTracker;
    @Mock UserAbsentRadiosOffObserver mockUserAbsentRadiosOffObserver;
    @Mock SignalStateDetector mMockSignalStateDetector;
    @Mock TelephonyManager mockTelephonyManager;
    @Mock WearCellularMediatorSettings mockSettings;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = ShadowApplication.getInstance().getApplicationContext();
        mContentResolver =  mContext.getContentResolver();
        mShadowContentResolver = shadowOf(mContentResolver);

        when(mockSettings.getCellAutoSetting()).thenReturn(WearCellularMediator.CELL_AUTO_ON);
        when(mockSettings.getCellState()).thenReturn(PhoneConstants.CELL_ON_FLAG);
        when(mockSettings.getRadioOnState()).thenReturn(
                WearCellularMediator.RADIO_ON_STATE_UNKNOWN);
        when(mockSettings.shouldTurnCellularOffDuringPowerSave()).thenReturn(true);
        when(mockSettings.getMobileSignalDetectorAllowed()).thenReturn(true);
        when(mockPowerTracker.isInPowerSave()).thenReturn(false);

        when(mockUserAbsentRadiosOffObserver.isEnabled()).thenReturn(true);

        mMediator = new WearCellularMediator(
                mContext,
                mContentResolver,
                mockTelephonyManager,
                mockSettings,
                mockPowerTracker,
                mockUserAbsentRadiosOffObserver,
                mMockSignalStateDetector);
        mMediator.mHandler = mockHandler;
        mMediator.onBootCompleted(true);
        when(mMockSignalStateDetector.isStarted()).thenReturn(true);
    }

    @Test
    public void testOnBootComplete() {
        verify(mockUserAbsentRadiosOffObserver).addListener(mMediator);

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_PROXY_CONNECTED);
    }

    @Test
    public void testInitWithContext() {
        verify(mockPowerTracker).addListener(mMediator);

        assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED)));
    }

    @Test
    public void testTurnCellAutoOff() {
        when(mockSettings.getCellAutoSetting()).thenReturn(WearCellularMediator.CELL_AUTO_OFF);
        mShadowContentResolver.notifyChange(CELL_AUTO_SETTING_URI, null);
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NO_CELL_AUTO);
    }

    @Test
    public void testHighBandwidthRequest() {
        mMediator.updateNumHighBandwidthRequests(1);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);
    }

    @Test
    public void testCellularTransportRequest() {
        mMediator.updateNumCellularRequests(1);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);

        // Going into power save mode should turn cell off.
        when(mockPowerTracker.isInPowerSave()).thenReturn(true);
        mMediator.onPowerSaveModeChanged();

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_POWER_SAVE);
    }

    @Test
    public void testActivityMode() {
        mMediator.updateNumCellularRequests(1);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);

        mMediator.updateActivityMode(true);
        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_ACTIVITY_MODE);

        mMediator.updateActivityMode(false);
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);
    }

  @Test
    public void testDeviceIdleUserAbsent() {
        mMediator.updateNumCellularRequests(1);
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);

        when(mockPowerTracker.isDeviceIdle()).thenReturn(true);
        mMediator.onDeviceIdleModeChanged();
        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_USER_ABSENT);

        when(mockUserAbsentRadiosOffObserver.isEnabled()).thenReturn(false);
        mMediator.onUserAbsentRadiosOffChanged(false);
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);

        when(mockPowerTracker.isDeviceIdle()).thenReturn(false);
        mMediator.onDeviceIdleModeChanged();
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);

        when(mockPowerTracker.isDeviceIdle()).thenReturn(true);
        mMediator.onDeviceIdleModeChanged();
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);

        when(mockUserAbsentRadiosOffObserver.isEnabled()).thenReturn(true);
        mMediator.onUserAbsentRadiosOffChanged(true);
        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_USER_ABSENT);

        when(mockPowerTracker.isDeviceIdle()).thenReturn(false);
        mMediator.onDeviceIdleModeChanged();
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_NETWORK_REQUEST);
    }

    @Test
    public void testInPhoneCall() {
        Intent intent = new Intent(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK);
        shadowApplication.sendBroadcast(intent);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PHONE_CALL);
    }

    @Test
    public void testProxyDisconnect() {
        mMediator.updateProxyConnected(false);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PROXY_DISCONNECTED);
    }

    @Test
    public void testTurnCellStateOff() {
        // Turn radio on first.
        mMediator.updateProxyConnected(false);

        reset(mockTelephonyManager);
        when(mockSettings.getCellState()).thenReturn(PhoneConstants.CELL_OFF_FLAG);
        mShadowContentResolver.notifyChange(WearCellularMediator.CELL_ON_URI, null);

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_CELL_SETTING);

        // Cell radio stays off even if proxy disconnect.
        mMediator.updateProxyConnected(false);
        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_CELL_SETTING);

        // Cell radio stays off even if network request.
        mMediator.updateNumHighBandwidthRequests(1);
        mMediator.updateNumCellularRequests(1);
        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_CELL_SETTING);

        // Cell radio should be turned on if in call broadcast received.
        Intent intent = new Intent(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK);
        shadowApplication.sendBroadcast(intent);
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PHONE_CALL);
    }

    @Test
    public void testInPowerSave() {
        // Turn radio on first.
        mMediator.updateProxyConnected(false);

        when(mockPowerTracker.isInPowerSave()).thenReturn(true);
        mMediator.onPowerSaveModeChanged();

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_POWER_SAVE);

        // But the incall broadcast should turn it back on.
        Intent intent = new Intent(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK);
        shadowApplication.sendBroadcast(intent);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PHONE_CALL);
    }

    @Test
    public void tesCellularOffDuringPowerSaveSettingIsFalse() {
        // Turn radio on first.
        mMediator.updateProxyConnected(false);

        when(mockSettings.shouldTurnCellularOffDuringPowerSave()).thenReturn(false);
        when(mockPowerTracker.isInPowerSave()).thenReturn(true);

        mMediator.onPowerSaveModeChanged();

        // Because power save change should have been ignored, last change should have been due to
        // proxy disconnecting.
        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PROXY_DISCONNECTED);
    }

    @Test
    public void testSimCardAbsent() {
        // Turn radio on first.
        mMediator.updateProxyConnected(false);

        reset(mockTelephonyManager);

        Intent simIntent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        simIntent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE,
                IccCardConstants.INTENT_VALUE_ICC_ABSENT);
        shadowApplication.sendBroadcast(simIntent);

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_SIM_ABSENT);

        // Incall broadcast should turn it back on.
        Intent intent = new Intent(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK);
        shadowApplication.sendBroadcast(intent);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PHONE_CALL);
    }

    @Test
    public void testGetRadioOnState() {
        reset(mockTelephonyManager);
        // We replaced mMediator.mHandler in setUp() so instantiate it again here so that we can
        // test the getRadioOnState() in the real Handler.
        mMediator = new WearCellularMediator(
                shadowApplication.getApplicationContext(),
                mockTelephonyManager,
                mockSettings,
                mockPowerTracker,
                mockUserAbsentRadiosOffObserver);
        // Trying to turn radio power off when radio is off.
        when(mockSettings.getRadioOnState()).thenReturn(WearCellularMediator.RADIO_ON_STATE_OFF);
        Message simAbsentMsg = Message.obtain(mMediator.mHandler,
                WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_SIM_ABSENT);
        mMediator.mHandler.handleMessage(simAbsentMsg);

        // Don't change radio power since it's already off
        verify(mockTelephonyManager, never()).setRadioPower(false);

        // Trying to turn radio power off when radio is on.
        reset(mockTelephonyManager);
        when(mockSettings.getRadioOnState()).thenReturn(WearCellularMediator.RADIO_ON_STATE_ON);
        mMediator.mHandler.handleMessage(simAbsentMsg);

        verify(mockTelephonyManager).setRadioPower(false);
        verifyLatestDecision(WearCellularMediator.Reason.OFF_SIM_ABSENT);
    }

    @Test
    public void testBadSignalWithProxyDisconnected_doesNothing() {
        mMediator.updateProxyConnected(false);

        mMediator.onSignalStateChanged(SignalStateModel.STATE_NO_SIGNAL);
        mMediator.onSignalStateChanged(SignalStateModel.STATE_UNSTABLE_SIGNAL);

        verifyPowerChange(WearCellularMediator.MSG_ENABLE_CELL,
                WearCellularMediator.Reason.ON_PROXY_DISCONNECTED);
        verify(mockTelephonyManager, never()).setRadioPower(anyBoolean());
    }

    @Test
    public void testNoSignalWithProxyConnected_disablesRadio() {
        mMediator.onSignalStateChanged(SignalStateModel.STATE_NO_SIGNAL);

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_NO_SIGNAL);
    }

    @Test
    public void testUnstableSignalWithProxyConnected_disablesRadio() {
        mMediator.onSignalStateChanged(SignalStateModel.STATE_UNSTABLE_SIGNAL);

        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_UNSTABLE_SIGNAL);
    }

    @Test
    public void testDetectorDisallowed_stopsDetector() {
        when(mockSettings.getMobileSignalDetectorAllowed()).thenReturn(false);
        mShadowContentResolver.notifyChange(WearCellularConstants.MOBILE_SIGNAL_DETECTOR_URI, null);

        verify(mMockSignalStateDetector).stopDetector();
    }

    @Test
    public void testChangesBeforeBootDoNothing() {
        // Reinstantiate so we can test without boot completed
        mMediator = new WearCellularMediator(
                mContext,
                mContentResolver,
                mockTelephonyManager,
                mockSettings,
                mockPowerTracker,
                mockUserAbsentRadiosOffObserver,
                mMockSignalStateDetector);
        mMediator.mHandler = mockHandler;
        reset(mockHandler);

        // Poke at the various settings, ensure never trying to update state
        mMediator.updateProxyConnected(false);
        verify(mockHandler, never()).sendMessage(msgCaptor.capture());

        mMediator.updateProxyConnected(true);
        verify(mockHandler, never()).sendMessage(msgCaptor.capture());

        mMediator.updateNumHighBandwidthRequests(1);
        mMediator.updateNumCellularRequests(1);
        verify(mockHandler, never()).sendMessage(msgCaptor.capture());

        mMediator.updateNumHighBandwidthRequests(0);
        mMediator.updateNumCellularRequests(0);
        verify(mockHandler, never()).sendMessage(msgCaptor.capture());

        mMediator.onBootCompleted(true);

        // Verify state changes occur after boot completed
        verifyPowerChange(WearCellularMediator.MSG_DISABLE_CELL,
                WearCellularMediator.Reason.OFF_PROXY_CONNECTED);
    }

    private void verifyLatestDecision(WearCellularMediator.Reason reason) {
        assertEquals(reason, mMediator.getDecisionHistory().getMostRecentEvent().reason);
    }

    private void verifyPowerChange(int what, WearCellularMediator.Reason reason) {
        verify(mockHandler, atLeastOnce()).sendMessage(msgCaptor.capture());
        assertEquals(what, msgCaptor.getValue().what);
        assertEquals(reason, msgCaptor.getValue().obj);
    }
}
