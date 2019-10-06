package com.android.clockwork.bluetooth;

import static com.android.clockwork.bluetooth.WearBluetoothMediatorSettings.BLUETOOTH_SETTINGS_PREF_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * Test for {@link WearBluetoothMediatorSettings}
 */
@RunWith(RobolectricTestRunner.class)
public class WearBluetoothMediatorSettingsTest {
    private ContentResolver cr;

    private @Mock WearBluetoothMediatorSettings.Listener mockListener;
    private WearBluetoothMediatorSettings mBluetoothSettings;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        cr = RuntimeEnvironment.application.getContentResolver();
        mBluetoothSettings = new WearBluetoothMediatorSettings(cr);
        mBluetoothSettings.addListener(mockListener);
    }

    @Test
    public void testContentObserverRegistered() {
        ShadowContentResolver scr = shadowOf(cr);
        for (Uri uri : mBluetoothSettings.getObservedUris()) {
            assertEquals(1, scr.getContentObservers(uri).size());
        }
    }

    @Ignore @Test
    public void testGettersDefaultReturnValues() {
        assertTrue(mBluetoothSettings.getIsSettingsPreferenceBluetoothOn());
        assertFalse(mBluetoothSettings.getIsInAirplaneMode());
    }

    @Test
    public void testGettersForNonDefaultValues() {
        Settings.System.putInt(cr, BLUETOOTH_SETTINGS_PREF_KEY, 0);
        assertFalse(mBluetoothSettings.getIsSettingsPreferenceBluetoothOn());

        Settings.Global.putInt(cr, Settings.Global.BLUETOOTH_ON, 2);

        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 1);
        assertTrue(mBluetoothSettings.getIsInAirplaneMode());
    }

    @Test
    public void testNotifyListeners() {
        WearBluetoothMediatorSettings.SettingsObserver obs
            = mBluetoothSettings.getSettingsObserver();

        Settings.System.putInt(cr, BLUETOOTH_SETTINGS_PREF_KEY, 1);
        obs.onChange(false, Settings.System.getUriFor(BLUETOOTH_SETTINGS_PREF_KEY));
        verify(mockListener).onSettingsPreferenceBluetoothSettingChanged(true);
        reset(mockListener);

        Settings.System.putInt(cr, BLUETOOTH_SETTINGS_PREF_KEY, 0);
        obs.onChange(false, Settings.System.getUriFor(BLUETOOTH_SETTINGS_PREF_KEY));
        verify(mockListener).onSettingsPreferenceBluetoothSettingChanged(false);
        reset(mockListener);

        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 1);
        obs.onChange(false, Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON));
        verify(mockListener).onAirplaneModeSettingChanged(true);
        reset(mockListener);

        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0);
        obs.onChange(false, Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON));
        verify(mockListener).onAirplaneModeSettingChanged(false);
        reset(mockListener);
    }

    @Test
    public void testGetAndPutUserPreferenceBluetoothOn() {
        mBluetoothSettings.setSettingsPreferenceBluetoothOn(true);
        assertTrue(mBluetoothSettings.getIsSettingsPreferenceBluetoothOn());

        mBluetoothSettings.setSettingsPreferenceBluetoothOn(false);
        assertFalse(mBluetoothSettings.getIsSettingsPreferenceBluetoothOn());
    }

    @Test
    public void testAirplaneMode() {
        WearBluetoothMediatorSettings.SettingsObserver obs
            = mBluetoothSettings.getSettingsObserver();

        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 1);
        obs.onChange(false, Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON));

        assertTrue(mBluetoothSettings.getIsInAirplaneMode());
        reset(mockListener);

        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0);
        obs.onChange(false, Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON));

        assertFalse(mBluetoothSettings.getIsInAirplaneMode());
    }
}
