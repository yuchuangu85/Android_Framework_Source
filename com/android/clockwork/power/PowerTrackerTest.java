package com.android.clockwork.power;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class PowerTrackerTest {
    private final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    private @Mock PowerManager mockPowerManager;
    private @Mock PowerTracker.Listener mockListener;
    private Context context;
    private PowerTracker powerTracker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        context = RuntimeEnvironment.application;
        powerTracker = new PowerTracker(context, mockPowerManager);
        powerTracker.addListener(mockListener);
        powerTracker.onBootCompleted();
    }

    @Test
    public void testConstructorRegistersAppropriateReceivers() {
        assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(Intent.ACTION_POWER_CONNECTED)));
        assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(Intent.ACTION_POWER_DISCONNECTED)));
        assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)));
    }

    @Test
    public void testChargingState() {
        assertFalse(powerTracker.isCharging());
        context.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
        assertTrue(powerTracker.isCharging());
        verify(mockListener).onChargingStateChanged();

        reset(mockListener);
        context.sendBroadcast(new Intent(Intent.ACTION_POWER_DISCONNECTED));
        assertFalse(powerTracker.isCharging());
        verify(mockListener).onChargingStateChanged();
    }

    @Test
    public void testPowerSaveMode() {
        when(mockPowerManager.isPowerSaveMode()).thenReturn(true);
        context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        assertTrue(powerTracker.isInPowerSave());
        verify(mockListener).onPowerSaveModeChanged();

        reset(mockListener);

        when(mockPowerManager.isPowerSaveMode()).thenReturn(false);
        context.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        assertFalse(powerTracker.isInPowerSave());
        verify(mockListener).onPowerSaveModeChanged();
    }
}
