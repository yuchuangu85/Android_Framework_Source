package com.android.clockwork.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.MatrixCursor;
import com.google.android.collect.Sets;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.HashSet;

import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_MODE_NON_ALT;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_MODE_UNKNOWN;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_URI;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.KEY_BLUETOOTH_MODE;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.KEY_COMPANION_ADDRESS;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.SETTINGS_COLUMN_KEY;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.SETTINGS_COLUMN_VALUE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class CompanionTrackerTest {

    private @Mock ContentResolver mockResolver;
    private @Mock BluetoothAdapter mockBtAdapter;

    private @Mock BluetoothDevice androidPhone;
    private @Mock BluetoothDevice iOSPhone;
    private @Mock BluetoothDevice btPeripheral;

    private @Mock BluetoothClass phoneBluetoothClass;
    private @Mock BluetoothClass peripheralBluetoothClass;

    private @Mock CompanionTracker.Listener mockListener;

    private CompanionTracker mTracker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTracker = new CompanionTracker(mockResolver, mockBtAdapter);
        mTracker.addListener(mockListener);

        when(phoneBluetoothClass.getMajorDeviceClass()).thenReturn(BluetoothClass.Device.Major.PHONE);
        when(peripheralBluetoothClass.getMajorDeviceClass()).thenReturn(BluetoothClass.Device.Major.PERIPHERAL);

        when(androidPhone.getType()).thenReturn(BluetoothDevice.DEVICE_TYPE_CLASSIC);
        when(androidPhone.getAddress()).thenReturn("AA:BB:CC:DD:EE:FF");
        when(androidPhone.getBluetoothClass()).thenReturn(phoneBluetoothClass);

        when(btPeripheral.getType()).thenReturn(BluetoothDevice.DEVICE_TYPE_CLASSIC);
        when(btPeripheral.getAddress()).thenReturn("MM:NN:OO:PP:QQ:RR");
        when(btPeripheral.getBluetoothClass()).thenReturn(peripheralBluetoothClass);

        when(iOSPhone.getType()).thenReturn(BluetoothDevice.DEVICE_TYPE_DUAL);
        when(iOSPhone.getAddress()).thenReturn("GG:HH:II:JJ:KK:LL");
        when(iOSPhone.getBluetoothClass()).thenReturn(phoneBluetoothClass);

        verify(mockResolver).registerContentObserver(
                BLUETOOTH_URI, false, mTracker.mSettingsObserver);
        reset(mockResolver);
    }

    @Test
    public void testNullBtAdapter() {
        CompanionTracker nullBtTracker = new CompanionTracker(mockResolver, null);
        nullBtTracker.addListener(mockListener);

        Assert.assertNull(nullBtTracker.getCompanion());
        Assert.assertFalse(nullBtTracker.isCompanionBle());

        nullBtTracker.onBluetoothAdapterReady();

        Assert.assertNull(nullBtTracker.getCompanion());
        Assert.assertFalse(nullBtTracker.isCompanionBle());

        mTracker.mSettingsObserver.onChange(false, BLUETOOTH_URI);

        nullBtTracker.onBluetoothAdapterReady();

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testPreAdapterEnabledGetters() {
        Assert.assertNull(mTracker.getCompanion());
        Assert.assertFalse(mTracker.isCompanionBle());
    }

    @Test
    public void testOnAndroidCompanionAddressChanged() {
        MatrixCursor cursor = buildCursor(androidPhone.getAddress(), -9999);
        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices())
                .thenReturn(Sets.newHashSet(androidPhone, btPeripheral));

        mTracker.mSettingsObserver.onChange(false, BLUETOOTH_URI);

        Assert.assertEquals(androidPhone, mTracker.getCompanion());
        Assert.assertFalse(mTracker.isCompanionBle());
        verify(mockListener).onCompanionChanged();
    }

    @Test
    public void testOnIosCompanionAddressChanged() {
        MatrixCursor cursor = buildCursor(iOSPhone.getAddress(), -9999);
        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices())
                .thenReturn(Sets.newHashSet(iOSPhone, btPeripheral));

        mTracker.mSettingsObserver.onChange(false, BLUETOOTH_URI);

        Assert.assertEquals(iOSPhone, mTracker.getCompanion());
        Assert.assertTrue(mTracker.isCompanionBle());
        verify(mockListener).onCompanionChanged();
    }

    @Test
    public void testAdapterReady_FreshUnpairedDevice() {
        MatrixCursor cursor = buildCursor(null, -9999);

        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices()).thenReturn(new HashSet<>());

        mTracker.onBluetoothAdapterReady();

        verify(mockResolver, never()).update(any(), any(), any(), any());
        Assert.assertNull(mTracker.getCompanion());
        Assert.assertFalse(mTracker.isCompanionBle());
    }

    @Test
    public void testAdapterReady_PairedAndroidDeviceNoMigrationNeeded() {
        MatrixCursor cursor = buildCursor(androidPhone.getAddress(), -9999);

        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices()).thenReturn(Sets.newHashSet(androidPhone));

        mTracker.onBluetoothAdapterReady();

        verify(mockResolver, never()).update(any(), any(), any(), any());
        Assert.assertEquals(androidPhone, mTracker.getCompanion());
        Assert.assertFalse(mTracker.isCompanionBle());
    }

    @Test
    public void testAdapterReady_PairedIOsDeviceNoMigrationNeeded() {
        MatrixCursor cursor = buildCursor(iOSPhone.getAddress(), -9999);
        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices()).thenReturn(Sets.newHashSet(iOSPhone));

        mTracker.onBluetoothAdapterReady();

        verify(mockResolver, never()).update(any(), any(), any(), any());
        Assert.assertEquals(iOSPhone, mTracker.getCompanion());
        Assert.assertTrue(mTracker.isCompanionBle());
    }

    @Test
    public void testAdapterReady_MigrationNeededPairedAndroidDevice() {
        MatrixCursor cursor = buildCursor(null, -9999);

        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices())
                .thenReturn(Sets.newHashSet(androidPhone, btPeripheral));

        mTracker.onBluetoothAdapterReady();

        ContentValues values = new ContentValues();
        values.put(KEY_COMPANION_ADDRESS, androidPhone.getAddress());
        verify(mockResolver).update(BLUETOOTH_URI, values, null, null);

        Assert.assertEquals(androidPhone, mTracker.getCompanion());
        Assert.assertFalse(mTracker.isCompanionBle());
    }

    @Test
    public void testAdapterReady_MigrationNeededPairedIOsDevice() {
        MatrixCursor cursor = buildCursor(null, -9999);

        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices())
                .thenReturn(Sets.newHashSet(iOSPhone, btPeripheral));

        mTracker.onBluetoothAdapterReady();

        ContentValues values = new ContentValues();
        values.put(KEY_COMPANION_ADDRESS, iOSPhone.getAddress());
        verify(mockResolver).update(BLUETOOTH_URI, values, null, null);

        Assert.assertEquals(iOSPhone, mTracker.getCompanion());
        Assert.assertTrue(mTracker.isCompanionBle());
    }

    @Test
    public void testBluetoothModeBeatsAdapterType() {
        MatrixCursor cursor = buildCursor(iOSPhone.getAddress(), BLUETOOTH_MODE_NON_ALT);
        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices()).thenReturn(Sets.newHashSet(iOSPhone));

        mTracker.onBluetoothAdapterReady();

        // even though the BluetoothDevice is BLE, the tracker should return false for
        // isCompanionBle because of the KEY_BLUETOOTH_MODE field
        Assert.assertEquals(iOSPhone, mTracker.getCompanion());
        Assert.assertFalse(mTracker.isCompanionBle());
    }

    @Test
    public void testBluetoothModeUnknownFallback() {
        MatrixCursor cursor = buildCursor(iOSPhone.getAddress(), BLUETOOTH_MODE_UNKNOWN);
        when(mockResolver.query(BLUETOOTH_URI, null, null, null, null))
                .thenReturn(cursor);
        when(mockBtAdapter.getBondedDevices()).thenReturn(Sets.newHashSet(iOSPhone));

        mTracker.onBluetoothAdapterReady();

        // since the BluetoothMode is unknown, it should return the type given by the device
        Assert.assertEquals(iOSPhone, mTracker.getCompanion());
        Assert.assertTrue(mTracker.isCompanionBle());
    }

    private MatrixCursor buildCursor(String companionAddress, int bluetoothMode) {
        MatrixCursor cursor = new MatrixCursor(
                new String[] {SETTINGS_COLUMN_KEY, SETTINGS_COLUMN_VALUE});
        if (companionAddress != null) {
            cursor.addRow(new Object[] {KEY_COMPANION_ADDRESS, companionAddress});
        }
        if (bluetoothMode >= 0) {
            cursor.addRow(new Object[] {KEY_BLUETOOTH_MODE, bluetoothMode});
        }
        return cursor;
    }
}
