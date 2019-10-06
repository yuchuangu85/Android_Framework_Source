package com.android.clockwork.bluetooth.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentHelper;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import com.android.internal.util.IndentingPrintWriter;
import java.lang.reflect.Field;
import java.util.Hashtable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Test for {@link ProxyNetworkAgent} */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowNetworkInfo.class, ShadowConnectivityManager.class })
public class ProxyNetworkAgentTest {
    private static final int NETWORK_SCORE = 123;
    private static final String COMPANION_NAME = "Companion Name";
    private static final String REASON = "Reason";

    private @Mock IndentingPrintWriter mockIndentingPrintWriter;
    private @Mock NetworkAgent mockNetworkAgent;
    private @Mock NetworkCapabilities mockCapabilities;
    private @Mock NetworkInfo mockNetworkInfo;
    private @Mock ProxyLinkProperties mockProxyLinkProperties;

    private ProxyNetworkAgent mProxyNetworkAgent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LinkProperties linkProperties = new LinkProperties();
        when(mockProxyLinkProperties.getLinkProperties()).thenReturn(linkProperties);
        Hashtable<String, LinkProperties> stackedLinks = new Hashtable<>();
        try {
            Field field = LinkProperties.class.getDeclaredField("mStackedLinks");
            field.setAccessible(true);
            field.set(linkProperties, stackedLinks);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail();
        }

        final Context context = RuntimeEnvironment.application;
        mProxyNetworkAgent = new ProxyNetworkAgent(
                context,
                mockCapabilities,
                mockProxyLinkProperties);
    }

    @Test
    public void testSetUpNetworkAgent_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;

        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }

    @Test
    public void testSetUpNetworkAgent_ExistingAgentReUse() {
        setupNetworkAgent();

        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotEquals(mockNetworkAgent, mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testSetUpNetworkAgent_ExistingAgentForceNew() {
        setupNetworkAgent();

        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotEquals(mockNetworkAgent, mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testMaybeSetUpNetworkAgent_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;

        mProxyNetworkAgent.maybeSetUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }

    @Test
    public void testMaybeSetUpNetworkAgent_ExistingAgentReUse() {
        setupNetworkAgent();

        mProxyNetworkAgent.maybeSetUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertEquals(mockNetworkAgent, mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testMaybeSetUpNetworkAgent_ExistingAgentForceNew() {
        setupNetworkAgent();

        mProxyNetworkAgent.maybeSetUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertEquals(mockNetworkAgent, mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testTearDownNetworkAgent_NoAgentForceNew() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;

        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotNull(mProxyNetworkAgent.mCurrentNetworkAgent);

        NetworkAgentHelper.callUnwanted(mProxyNetworkAgent.mCurrentNetworkAgent);

        assertTrue(mProxyNetworkAgent.mNetworkAgents.isEmpty());
        assertNull(mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testTearDownNetworkAgent_ExistingAgentForceNew() {
        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotNull(mProxyNetworkAgent.mCurrentNetworkAgent);

        NetworkAgent unwantedAgent = mProxyNetworkAgent.mCurrentNetworkAgent;

        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());

        NetworkAgentHelper.callUnwanted(unwantedAgent);

        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }

    @Test
    public void testTearDownNetworkAgent_ExistingAgentForceNewButMissingFromHash() {
        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotNull(mProxyNetworkAgent.mCurrentNetworkAgent);

        NetworkAgent unwantedAgent = mProxyNetworkAgent.mCurrentNetworkAgent;

        mProxyNetworkAgent.setUpNetworkAgent(REASON, COMPANION_NAME, null);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());

        // Secretly poison the hash here
        mProxyNetworkAgent.mNetworkAgents.remove(unwantedAgent);

        NetworkAgentHelper.callUnwanted(unwantedAgent);

        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }

    @Test
    public void testSetConnected_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.setConnected(REASON, COMPANION_NAME);

        verify(mockNetworkInfo, never()).setDetailedState(any(), anyString(), anyString());
        assertTrue(mProxyNetworkAgent.mNetworkAgents.isEmpty());
        verify(mockNetworkAgent, never()).sendNetworkInfo(mockNetworkInfo);
    }

    @Test
    public void testSetConnected_ExistingAgent() {
        setupNetworkAgent();

        mProxyNetworkAgent.setConnected(REASON, COMPANION_NAME);

        verify(mockNetworkAgent).sendNetworkInfo(mockNetworkInfo);
    }

    @Test
    public void testSendCapabilities_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.sendCapabilities(mockCapabilities);
        verify(mockNetworkAgent, never()).sendNetworkCapabilities(mockCapabilities);
    }

    @Test
    public void testSendCapabilities_ExistingAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.sendCapabilities(mockCapabilities);
        verify(mockNetworkAgent).sendNetworkCapabilities(mockCapabilities);
    }

    @Test
    public void testSendNetworkScore_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.setNetworkScore(NETWORK_SCORE);

        verify(mockNetworkAgent, never()).sendNetworkScore(NETWORK_SCORE);
    }

    @Test
    public void testSendNetworkScore_ExistingAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.setNetworkScore(NETWORK_SCORE);

        verify(mockNetworkAgent).sendNetworkScore(NETWORK_SCORE);
    }

    @Test
    public void testDump_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.dump(mockIndentingPrintWriter);
        verify(mockIndentingPrintWriter).printPair(anyString(), anyString());
    }

    @Test
    public void testDump_ExistingAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.dump(mockIndentingPrintWriter);
        verify(mockIndentingPrintWriter, atLeast(1)).printPair(anyString(), anyInt());
    }

    private void setupNetworkAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.mNetworkAgents.put(mockNetworkAgent, mockNetworkInfo);
    }
}
