package android.bluetooth;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.when;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Mock {@link BluetoothAdapter} in the same package to access package private methods */
public class MockBluetoothProxyHelper {
    private @Mock IBluetooth mockBluetoothService;
    private @Mock IBluetoothSocketManager mockBluetoothSocketManager;
    private @Mock ParcelFileDescriptor mockParcelFileDescriptor;

    private BluetoothAdapter mockBluetoothAdapter;

    public MockBluetoothProxyHelper(BluetoothAdapter mockBluetoothAdapter) {
        this.mockBluetoothAdapter = mockBluetoothAdapter;

        MockitoAnnotations.initMocks(this);

        // Mocks out package protected method
        when(mockBluetoothAdapter.getBluetoothService(any())).thenReturn(mockBluetoothService);

        // IBluetooth package protected method
        try {
            when(mockBluetoothService.getSocketManager()).thenReturn(mockBluetoothSocketManager);
        } catch (RemoteException e) {
            fail(e.getMessage());
        }

        // IBluetooth package protected method
        try {
            when(mockBluetoothSocketManager.connectSocket(anyObject(), anyInt(), anyObject(),
                        anyInt(), anyInt())).thenReturn(mockParcelFileDescriptor);
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
    }

    public void setBluetoothService(IBluetooth bluetoothProxyService) {
        when(mockBluetoothAdapter.getBluetoothService(any())).thenReturn(bluetoothProxyService);
    }

    public void setBluetoothSocketManager(IBluetoothSocketManager bluetoothSocketManager) {
        try {
            when(mockBluetoothService.getSocketManager()).thenReturn(bluetoothSocketManager);
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
    }

    public void setMockParcelFileDescriptor(ParcelFileDescriptor parcelFileDescriptor) {
        try {
            when(mockBluetoothSocketManager.connectSocket(anyObject(), anyInt(), anyObject(),
                        anyInt(), anyInt())).thenReturn(parcelFileDescriptor);
        } catch (RemoteException e) {
            fail(e.getMessage());
        }
    }
}
