package com.android.clockwork.bluetooth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.MockBluetoothProxyHelper;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.ParcelFileDescriptor;
import com.android.clockwork.bluetooth.proxy.ProxyServiceHelper;
import com.android.internal.util.IndentingPrintWriter;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Test for {@link CompanionProxyShard} */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowBluetoothAdapter.class)
public class CompanionProxyShardTest {

    private static final int INSTANCE = -1;
    private static final int FD = 2;
    private static final int NETWORK_SCORE = 123;
    private static final int NETWORK_SCORE2 = 456;
    private static final int DISCONNECT_STATUS = 789;

    private static final int WHAT_START_SYSPROXY = 1;
    private static final int WHAT_JNI_ACTIVE_NETWORK_STATE = 2;
    private static final int WHAT_JNI_DISCONNECTED = 3;
    private static final int WHAT_RESET_CONNECTION = 4;

    private static final boolean CONNECTED = true;
    private static final boolean DISCONNECTED = !CONNECTED;
    private static final boolean WITH_INTERNET = true;
    private static final boolean NO_INTERNET = !WITH_INTERNET;

    private static final int INVALID_NETWORK_TYPE = ConnectivityManager.TYPE_NONE;

    private @Mock BluetoothAdapter mockBluetoothAdapter;
    private @Mock BluetoothDevice mockBluetoothDevice;
    private @Mock Context mockContext;
    private @Mock IndentingPrintWriter mockIndentingPrintWriter;
    private @Mock ParcelFileDescriptor mockParcelFileDescriptor;
    private @Mock ProxyServiceHelper mockProxyServiceHelper;
    private @Mock CompanionProxyShard.Listener mockCompanionProxyShardListener;

    private CompanionProxyShardTestClass mCompanionProxyShard;
    private MockBluetoothProxyHelper mBluetoothProxyHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        ShadowLooper.pauseMainLooper();

        when(mockParcelFileDescriptor.detachFd()).thenReturn(FD);
        mBluetoothProxyHelper = new MockBluetoothProxyHelper(mockBluetoothAdapter);
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        when(mockProxyServiceHelper.getNetworkScore()).thenReturn(NETWORK_SCORE);

        ShadowBluetoothAdapter.setAdapter(mockBluetoothAdapter);
    }

    @Test
    public void testStartNetworkWithWifiInternet_WasDisconnected() {
        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET
        verify(mockParcelFileDescriptor).detachFd();

        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER
        assertEquals(1, mCompanionProxyShard.connectNativeCount);

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(ConnectivityManager.TYPE_WIFI, false);

        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, WITH_INTERNET);
        verify(mockProxyServiceHelper).startNetworkSession(anyString(), anyObject());

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetworkNoInternet_WasDisconnected() {
        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET
        verify(mockParcelFileDescriptor).detachFd();

        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER
        assertEquals(1, mCompanionProxyShard.connectNativeCount);

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(INVALID_NETWORK_TYPE, false);

        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, NO_INTERNET);
        verify(mockProxyServiceHelper).stopNetworkSession(anyString());

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_WasConnectedWithWifiInternet() {
        connectNetworkWithWifiInternet();
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, WITH_INTERNET);

        mCompanionProxyShard.startNetwork();
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER

        verify(mockCompanionProxyShardListener, times(2)).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, WITH_INTERNET);
        verify(mockProxyServiceHelper, times(2)).startNetworkSession(anyString(), anyObject());

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_WasConnectedNoInternet() {
        connectNetworkNoInternet();
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, NO_INTERNET);

        mCompanionProxyShard.startNetwork();
        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY

        verify(mockCompanionProxyShardListener, times(2)).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, NO_INTERNET);
        verify(mockProxyServiceHelper, times(2)).stopNetworkSession(anyString());

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_Closed() {
        connectNetworkWithWifiInternet();
        mCompanionProxyShard.mIsClosed = true;

        mCompanionProxyShard.startNetwork();
        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY

        verify(mockProxyServiceHelper).getNetworkScore();
        assertEquals(1, mCompanionProxyShard.mStartAttempts);

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_AdapterIsNull() {
        // Force bluetooth adapter to return null
        ShadowBluetoothAdapter.forceNull = true;

        mCompanionProxyShard = createCompanionProxyShard();
        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET

        verify(mockParcelFileDescriptor, never()).detachFd();
        // Restore bluetooth adapter to return a valid instance
        ShadowBluetoothAdapter.forceNull = false;
     }

    @Test
    public void testStartNetwork_NullParcelFileDescriptor() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(null);

        mCompanionProxyShard = createCompanionProxyShard();
        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET

        // Simulate JNI callback
        assertEquals(0, mCompanionProxyShard.connectNativeCount);

        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_RESET_CONNECTION));
    }

    @Test
    public void testStartNetwork_BluetoothServiceIsNull() {
        mBluetoothProxyHelper.setBluetoothService(null);

        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET

        verify(mockParcelFileDescriptor, never()).detachFd();
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_RESET_CONNECTION));
     }

    @Test
    public void testUpdateNetwork_ConnectedWithWifiInternet() {
        connectNetworkWithWifiInternet();
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, WITH_INTERNET);

        when(mockProxyServiceHelper.getNetworkScore()).thenReturn(NETWORK_SCORE2);
        mCompanionProxyShard.updateNetwork(NETWORK_SCORE2);

        verify(mockProxyServiceHelper).setNetworkScore(NETWORK_SCORE);
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE2, WITH_INTERNET);
    }

    @Test
    public void testUpdateNetwork_ConnectedNoInternet() {
        connectNetworkNoInternet();

        when(mockProxyServiceHelper.getNetworkScore()).thenReturn(NETWORK_SCORE2);
        mCompanionProxyShard.updateNetwork(NETWORK_SCORE2);

        verify(mockProxyServiceHelper).setNetworkScore(NETWORK_SCORE2);
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE2, NO_INTERNET);
    }

    @Test
    public void testUpdateNetwork_Disconnected() {
        when(mockProxyServiceHelper.getNetworkScore()).thenReturn(NETWORK_SCORE2);
        mCompanionProxyShard = createCompanionProxyShard();
        mCompanionProxyShard.updateNetwork(NETWORK_SCORE2);

        verify(mockProxyServiceHelper).setNetworkScore(NETWORK_SCORE2);
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(DISCONNECTED,
                NETWORK_SCORE2, false);
    }

    @Test
    public void testWifiToCell() {
        connectNetworkWithWifiInternet();

        mCompanionProxyShard.simulateJniCallbackConnect(ConnectivityManager.TYPE_MOBILE, true);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        assertEquals(ConnectivityManager.TYPE_MOBILE, mCompanionProxyShard.mNetworkType);
        verify(mockCompanionProxyShardListener, times(2)).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, WITH_INTERNET);
        verify(mockProxyServiceHelper).setMetered(false);
        verify(mockProxyServiceHelper).setMetered(true);
    }

    @Test
    public void testCellToWifi() {
        connectNetworkWithCellInternet();

        mCompanionProxyShard.simulateJniCallbackConnect(ConnectivityManager.TYPE_WIFI, false);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        assertEquals(ConnectivityManager.TYPE_WIFI, mCompanionProxyShard.mNetworkType);
        verify(mockCompanionProxyShardListener, times(2)).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, WITH_INTERNET);
        verify(mockProxyServiceHelper).setMetered(true);
        verify(mockProxyServiceHelper).setMetered(false);
    }

    @Test
    public void testJniActiveNetworkState_AlreadyClosed() {
        connectNetworkWithWifiInternet();

        mCompanionProxyShard.mIsClosed = true;
        mCompanionProxyShard.simulateJniCallbackConnect(ConnectivityManager.TYPE_WIFI, true);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        ensureMessageQueueEmpty();
    }

    @Test
    public void testJniActiveNetworkState_ConnectedPhoneWithCell() {
        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER

        mCompanionProxyShard.simulateJniCallbackConnect(ConnectivityManager.TYPE_MOBILE, true);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        verify(mockProxyServiceHelper).startNetworkSession(anyString(), anyObject());
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(true, 123, true);
        verify(mockProxyServiceHelper).setMetered(true);
        ensureMessageQueueEmpty();
    }

    @Test
    public void testJniActiveNetworkState_ConnectedPhoneNoInternet() {
        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER

        mCompanionProxyShard.simulateJniCallbackConnect(INVALID_NETWORK_TYPE, true);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_ACTIVE_NETWORK_STATE

        verify(mockProxyServiceHelper).stopNetworkSession(anyString());
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(CONNECTED,
                NETWORK_SCORE, NO_INTERNET);

        ensureMessageQueueEmpty();
    }

    @Test
    public void testJniDisconnect_NotClosed() {
        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER

        mCompanionProxyShard.simulateJniCallbackDisconnect(-1);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_DISCONNECT

        verify(mockCompanionProxyShardListener).onProxyConnectionChange(DISCONNECTED,
                NETWORK_SCORE, false);
        verify(mockProxyServiceHelper).stopNetworkSession(anyString());

        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));
    }

    @Test
    public void testJniDisconnect_Closed() {
        mCompanionProxyShard = createCompanionProxyShard();

        ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK RFCOMM SOCKET
        ShadowLooper.runMainLooperOneTask();  // ASYNC TASK SYSPROXY DELIVER

        mCompanionProxyShard.mIsClosed = true;

        mCompanionProxyShard.simulateJniCallbackDisconnect(-1);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_DISCONNECT

        ensureMessageQueueEmpty();
    }

    @Test
    public void testClose_WasConnectedWithWifiInternet() {
        connectNetworkWithWifiInternet();

        mCompanionProxyShard.close();

        verify(mockProxyServiceHelper).stopNetworkSession(anyString());
        verify(mockCompanionProxyShardListener).onProxyConnectionChange(DISCONNECTED,
                NETWORK_SCORE, false);
    }

    @Test
    public void testResetConnection_SysproxyConnected() {
        connectNetworkWithWifiInternet();

        setWaitingForAsyncDiconnectResponse(true);
        try {
            mCompanionProxyShard.startNetwork();
            ShadowLooper.runMainLooperOneTask();  // WHAT_START_SYSPROXY

            assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_RESET_CONNECTION));

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

            assertEquals(1, mCompanionProxyShard.connectNativeCount);
            assertEquals(1, mCompanionProxyShard.disconnectNativeCount);
            assertEquals(mCompanionProxyShard.disconnectReturnValue,
                    getWaitingForAsyncDiconnectResponse());

        } finally {
            // Restore static variable to default
            setWaitingForAsyncDiconnectResponse(false);
        }
    }

    @Test
    public void testResetConnection_SysproxyDisconnected() {
        connectNetworkWithWifiInternet();
        mCompanionProxyShard.onDisconnect(DISCONNECT_STATUS);
        ShadowLooper.runMainLooperOneTask();  // WHAT_JNI_DISCONNECTED

        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));
        setWaitingForAsyncDiconnectResponse(true);
        try {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

            assertEquals(1, mCompanionProxyShard.connectNativeCount);
            assertEquals(0, mCompanionProxyShard.disconnectNativeCount);
            assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));
        } finally {
            // Restore static variable to default
            setWaitingForAsyncDiconnectResponse(false);
        }
    }

    @Test
    public void testDump() {
        mCompanionProxyShard = createCompanionProxyShard();

        mCompanionProxyShard.dump(mockIndentingPrintWriter);
        verify(mockIndentingPrintWriter).increaseIndent();
        verify(mockIndentingPrintWriter).decreaseIndent();
    }

    // Create the companion proxy shard to be used in the tests.
    // The class abstracts away dependencies on difficult framework methods and fields.
    private CompanionProxyShardTestClass createCompanionProxyShard() {
        CompanionProxyShardTestClass companionProxyShard
            = new CompanionProxyShardTestClass(mockContext, mockProxyServiceHelper,
                    mockBluetoothDevice, mockCompanionProxyShardListener, NETWORK_SCORE);

        return companionProxyShard;
    }

    private void ensureMessageQueueEmpty() {
        for (int i = WHAT_START_SYSPROXY; i <= WHAT_RESET_CONNECTION; i++) {
            assertFalse(mCompanionProxyShard.mHandler.hasMessages(i));
        }
    }

    private void connectNetworkWithWifiInternet() {
        doStartNetwork(ConnectivityManager.TYPE_WIFI, false);
        assertEquals(ConnectivityManager.TYPE_WIFI, mCompanionProxyShard.mNetworkType);
    }

    private void connectNetworkWithCellInternet() {
        doStartNetwork(ConnectivityManager.TYPE_MOBILE, true);
        assertEquals(ConnectivityManager.TYPE_MOBILE, mCompanionProxyShard.mNetworkType);
    }

    private void connectNetworkNoInternet() {
        doStartNetwork(INVALID_NETWORK_TYPE, false);
    }

    private void doStartNetwork(int networkType, boolean metered) {
        mCompanionProxyShard = createCompanionProxyShard();
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(networkType, metered);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(1, mCompanionProxyShard.connectNativeCount);
    }

    private class CompanionProxyShardTestClass extends CompanionProxyShard {
        int connectNativeCount;
        int disconnectNativeCount;

        boolean connectReturnValue = true;
        boolean disconnectReturnValue = true;

        private CompanionProxyShardTestClass(
                final Context context,
                final ProxyServiceHelper proxyServiceHelper,
                final BluetoothDevice device,
                final Listener listener,
                final int networkScore) {
            super(context, proxyServiceHelper, device, listener, networkScore);
        }

        @Override
        protected boolean connectNative(int fd) {
            connectNativeCount += 1;
            return connectReturnValue;
        }

        void simulateJniCallbackConnect(int networkType, boolean isMetered) {
            super.onActiveNetworkState(networkType, isMetered);
        }

        @Override
        protected boolean disconnectNative() {
            disconnectNativeCount += 1;
            return disconnectReturnValue;
        }

        void simulateJniCallbackDisconnect(int status) {
            super.onDisconnect(status);
        }
    }

    private void setWaitingForAsyncDiconnectResponse(final boolean isWaiting) {
        try {
            Field field
                = CompanionProxyShard.class.getDeclaredField("sWaitingForAsyncDisconnectResponse");
            field.setAccessible(true);
            field.set(null, isWaiting);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            fail();
        }
    }

    private boolean getWaitingForAsyncDiconnectResponse() {
        boolean isWaiting = false;
        try {
            Field field
                = CompanionProxyShard.class.getDeclaredField("sWaitingForAsyncDisconnectResponse");
            field.setAccessible(true);
            isWaiting = field.getBoolean(null);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            fail();
        }
        return isWaiting;
    }
}
