package com.android.clockwork.bluetooth.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.RemoteException;

import com.android.internal.util.IndentingPrintWriter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

/** Test for {@link ProxyServiceHelper} */
@RunWith(RobolectricTestRunner.class)
public class ProxyServiceHelperTest {
    private static final int NETWORK_SCORE = 123;
    private static final String COMPANION_NAME = "CompanionName";
    private static final String REASON = "Reason";

    @Mock Context mockContext;
    @Mock IndentingPrintWriter mockIndentingPrintWriter;
    @Mock ProxyNetworkFactory mockProxyNetworkFactory;
    @Mock ProxyNetworkAgent mockProxyNetworkAgent;

    private NetworkCapabilities.Builder capabilitiesBuilder =
        new NetworkCapabilities.Builder();

    private ProxyServiceHelperTestClass mProxyServiceHelper;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mProxyServiceHelper = new ProxyServiceHelperTestClass(
                mockContext,
                capabilitiesBuilder,
                false);
    }

    @Test
    public void testStartNetworkSession() {
        mProxyServiceHelper.startNetworkSession(REASON, "lo", 1500, null);
    }

    @Test
    public void testSetNetworkScore() {
        when(mProxyServiceHelper.getNetworkScore()).thenReturn(NETWORK_SCORE);
        mProxyServiceHelper.setNetworkScore(NETWORK_SCORE);
        verify(mockProxyNetworkFactory).setNetworkScore(NETWORK_SCORE);
        verify(mockProxyNetworkAgent).setNetworkScore(NETWORK_SCORE);
        assertEquals(NETWORK_SCORE, mProxyServiceHelper.getNetworkScore());
    }

    @Test
    public void testSetMetered() {
        mProxyServiceHelper.setMetered(true);
        assertFalse(capabilitiesBuilder.build()
            .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        verify(mockProxyNetworkAgent).sendCapabilities(any());
    }

    @Test
    public void testSetUnMetered() {
        mProxyServiceHelper.setMetered(false);
        assertTrue(capabilitiesBuilder.build()
            .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        verify(mockProxyNetworkAgent).sendCapabilities(any());
    }

    @Test
    public void testDump() {
        mProxyServiceHelper.dump(mockIndentingPrintWriter);
        verify(mockProxyNetworkFactory).dump(mockIndentingPrintWriter);
    }

    private class ProxyServiceHelperTestClass extends ProxyServiceHelper {
        public ProxyServiceHelperTestClass(
                final Context context,
                final NetworkCapabilities.Builder capabilitiesBuildr,
                final boolean isLocalEdition) {
            super(context, capabilitiesBuildr, isLocalEdition);
        }

        @Override
        protected ProxyNetworkFactory buildProxyNetworkFactory(
                final Context context,
                final NetworkCapabilities capabilities) {
            return mockProxyNetworkFactory;
        }

        @Override
        protected ProxyNetworkAgent buildProxyNetworkAgent(
                final Context context,
                final NetworkCapabilities capabilities,
                final boolean isLocalEdition) {
            return mockProxyNetworkAgent;
        }

        @Override
        protected void addNetworkCapabilitiesBandwidth() { }
     }
}
