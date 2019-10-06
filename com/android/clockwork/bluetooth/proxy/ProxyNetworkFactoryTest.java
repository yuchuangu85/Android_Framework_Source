package com.android.clockwork.bluetooth.proxy;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.NetworkCapabilities;
import com.android.internal.util.IndentingPrintWriter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

/** Test for {@link ProxyNetworkFactory} */
@RunWith(RobolectricTestRunner.class)
public class ProxyNetworkFactoryTest {
    private static final int NETWORK_SCORE = 123;

    private @Mock Context mockContext;
    private @Mock IndentingPrintWriter mockIndentingPrintWriter;
    private @Mock NetworkCapabilities mockCapabilities;

    private ProxyNetworkFactoryTestClass mProxyNetworkFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mProxyNetworkFactory = new ProxyNetworkFactoryTestClass(mockContext, mockCapabilities);
        assertEquals(1, mProxyNetworkFactory.registerMethod);
    }

    @Test
    public void testSetNetworkScore_SameScore() {
        mProxyNetworkFactory.setNetworkScore(0);
        assertEquals(0, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(0, mProxyNetworkFactory.scoreFilter);
    }

    @Test
    public void testSetNetworkScore_GreaterScore() {
        mProxyNetworkFactory.setNetworkScore(NETWORK_SCORE);
        assertEquals(1, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(NETWORK_SCORE, mProxyNetworkFactory.scoreFilter);
    }

    @Test
    public void testSetNetworkScore_LesserScore() {
        mProxyNetworkFactory.setNetworkScore(NETWORK_SCORE);
        mProxyNetworkFactory.setNetworkScore(0);

        assertEquals(1, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(NETWORK_SCORE, mProxyNetworkFactory.scoreFilter);
    }

    @Test
    public void testDump() {
        mProxyNetworkFactory.dump(mockIndentingPrintWriter);
        verify(mockIndentingPrintWriter).printPair(anyString(), anyInt());
    }

    private class ProxyNetworkFactoryTestClass extends ProxyNetworkFactory {
        private int registerMethod;
        private int setScoreFilterMethod;
        private int scoreFilter;

        private ProxyNetworkFactoryTestClass(Context context, NetworkCapabilities capabilities) {
            super(context, capabilities);
        }

        @Override
        public void register() {
            registerMethod++;
        }

        @Override
        public void setScoreFilter(int score) {
            scoreFilter = score;
            setScoreFilterMethod++;
        }
    }
}
