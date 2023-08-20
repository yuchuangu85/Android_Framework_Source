package com.android.clockwork.common;

import static com.android.clockwork.common.CellOnlyMode.ACTION_EXIT_CELL_ONLY_MODE;
import static com.android.clockwork.common.CellOnlyMode.CELL_ONLY_MODE_DURATION_SECONDS_SETTING_KEY;
import static com.android.clockwork.common.CellOnlyMode.CELL_ONLY_MODE_SETTING_KEY;
import static com.android.clockwork.common.CellOnlyMode.DEFAULT_CELL_ONLY_MODE_DURATION_MS;
import static com.android.clockwork.common.CellOnlyMode.EXIT_CELL_ONLY_LINGER_DURATION_MS;
import static com.android.clockwork.common.CellOnlyMode.MAX_CELL_ONLY_MODE_DURATION_MS;

import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.LEGACY)
public class CellOnlyModeTest {
    private final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    @Mock AlarmManager mockAlarmManager;

    private Context mContext;
    private ContentResolver mContentResolver;

    @Mock CellOnlyMode.Listener mockListener;
    CellOnlyMode mCellOnlyMode;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mContentResolver = RuntimeEnvironment.application.getContentResolver();
        mCellOnlyMode = new CellOnlyMode(mContext, mContentResolver, mockAlarmManager);
        mCellOnlyMode.addListener(mockListener);
        mCellOnlyMode.onBootCompleted();
    }

    @Test
    public void testContentObserverRegistered() {
        CellOnlyMode.SettingsObserver obs = mCellOnlyMode.getSettingsObserver();
        ShadowContentResolver scr = shadowOf(mContentResolver);
        Assert.assertEquals(1, scr.getContentObservers(Settings.System.getUriFor(CELL_ONLY_MODE_SETTING_KEY)).size());
    }

    @Test
    public void testOnBootCompleted() {
        Assert.assertTrue("BroadcastReceiver not registered for action: " + ACTION_EXIT_CELL_ONLY_MODE,
                shadowApplication.hasReceiverForIntent(new Intent(ACTION_EXIT_CELL_ONLY_MODE)));

        Assert.assertFalse(mCellOnlyMode.isCellOnlyModeEnabled());
    }

    @Test
    public void test_enableCellOnlyMode() {
        int cellOnlyDurationSeconds = 60;

        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_DURATION_SECONDS_SETTING_KEY, cellOnlyDurationSeconds);
        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_SETTING_KEY, 1);

        verify(mockListener).onCellOnlyModeChanged(true);
        verify(mockAlarmManager).setWindow(eq(AlarmManager.ELAPSED_REALTIME), anyLong(), eq(EXIT_CELL_ONLY_LINGER_DURATION_MS), eq(mCellOnlyMode.exitCellOnlyModeIntent));
    }

    @Test
    public void test_disableCellOnlyMode() {
        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_SETTING_KEY, 0);

        verify(mockListener).onCellOnlyModeChanged(false);
        verify(mockAlarmManager).cancel(mCellOnlyMode.exitCellOnlyModeIntent);
    }

    @Test
    public void test_alarmCancelsCellOnlyMode() {
        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_SETTING_KEY, 1);
        mContext.sendBroadcast(new Intent(ACTION_EXIT_CELL_ONLY_MODE));

        Assert.assertEquals(0, Settings.System.getInt(mContentResolver, CELL_ONLY_MODE_SETTING_KEY, 0));
        // onCellOnlyModeChange() 3 times:
        // - SettingsObserver.onChange()
        // - exitCellOnlyModeReceive.onReceive() -> which resets cell only mode settings
        // - SettingsObserver.onChange()
        verify(mockListener, times(3)).onCellOnlyModeChanged(false);
    }

    @Test
    public void test_cellOnlyDurationCalculation() {
        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_DURATION_SECONDS_SETTING_KEY, 0);
        Assert.assertEquals(DEFAULT_CELL_ONLY_MODE_DURATION_MS, mCellOnlyMode.getCellOnlyModeDuration());

        int pastMaxDurationSeconds = (int) TimeUnit.MILLISECONDS.toSeconds(MAX_CELL_ONLY_MODE_DURATION_MS) + 100;
        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_DURATION_SECONDS_SETTING_KEY, pastMaxDurationSeconds);
        Assert.assertEquals(MAX_CELL_ONLY_MODE_DURATION_MS, mCellOnlyMode.getCellOnlyModeDuration());

        int normalDurationSeconds = (int) TimeUnit.MILLISECONDS.toSeconds(MAX_CELL_ONLY_MODE_DURATION_MS/2);
        Settings.System.putInt(mContentResolver, CELL_ONLY_MODE_DURATION_SECONDS_SETTING_KEY, normalDurationSeconds);
        Assert.assertEquals(TimeUnit.SECONDS.toMillis(normalDurationSeconds), mCellOnlyMode.getCellOnlyModeDuration());
    }
}
