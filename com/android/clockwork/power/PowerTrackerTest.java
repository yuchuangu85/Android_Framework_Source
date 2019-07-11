package com.android.clockwork.power;

import android.content.Intent;
import android.os.PowerManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
public class PowerTrackerTest {
    final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    @Mock PowerManager mockPowerManager;
    @Mock PowerTracker.Listener mockListener;
    PowerTracker powerTracker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        powerTracker = new PowerTracker(shadowApplication.getApplicationContext(), mockPowerManager);
        powerTracker.addListener(mockListener);
        powerTracker.onBootCompleted();
    }

    @Test
    public void testConstructorRegistersAppropriateReceivers() {
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(Intent.ACTION_POWER_CONNECTED)));
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(Intent.ACTION_POWER_DISCONNECTED)));
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)));
    }

    @Test
    public void testChargingState() {
        Assert.assertFalse(powerTracker.isCharging());
        shadowApplication.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
        Assert.assertTrue(powerTracker.isCharging());
        verify(mockListener).onChargingStateChanged();

        reset(mockListener);
        shadowApplication.sendBroadcast(new Intent(Intent.ACTION_POWER_DISCONNECTED));
        Assert.assertFalse(powerTracker.isCharging());
        verify(mockListener).onChargingStateChanged();
    }

    @Test
    public void testPowerSaveMode() {
        when(mockPowerManager.isPowerSaveMode()).thenReturn(true);
        shadowApplication.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        Assert.assertTrue(powerTracker.isInPowerSave());
        verify(mockListener).onPowerSaveModeChanged();

        reset(mockListener);

        when(mockPowerManager.isPowerSaveMode()).thenReturn(false);
        shadowApplication.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        Assert.assertFalse(powerTracker.isInPowerSave());
        verify(mockListener).onPowerSaveModeChanged();
    }

}
