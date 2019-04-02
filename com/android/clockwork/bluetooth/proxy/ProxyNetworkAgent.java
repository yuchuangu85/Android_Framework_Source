package com.android.clockwork.bluetooth.proxy;

import static com.android.clockwork.bluetooth.proxy.WearProxyConstants.PROXY_NETWORK_SUBTYPE_ID;
import static com.android.clockwork.bluetooth.proxy.WearProxyConstants.PROXY_NETWORK_SUBTYPE_NAME;
import static com.android.clockwork.bluetooth.proxy.WearProxyConstants.PROXY_NETWORK_TYPE_NAME;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.os.Looper;
import android.util.Log;
import com.android.clockwork.common.DebugAssert;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link NetworkAgent} that represents bluetooth companion proxy networks.
 *
 * Interacts with {@link ConnectivityService} to provide proxy network connectivity
 * over bluetooth.  {@link ProxyNetworkAgent} provides the container and manipulation
 * methods for all network agents used for sysproxy.
 *
 */
public class ProxyNetworkAgent {
    private static final String TAG = WearProxyConstants.LOG_TAG;
    private static final String NETWORK_AGENT_NAME = "CompanionProxyAgent";
    private static final boolean ALWAYS_CREATE_AGENT = true;
    private static final boolean MAYBE_RECYCLE_AGENT = false;

    private final Context mContext;
    private final NetworkCapabilities mCapabilities;
    private final ProxyLinkProperties mProxyLinkProperties;

    @VisibleForTesting
    @Nullable NetworkAgent mCurrentNetworkAgent;
    private int mCurrentNetworkScore;

    @VisibleForTesting
    final HashMap<NetworkAgent, NetworkInfo> mNetworkAgents
        = new HashMap<NetworkAgent, NetworkInfo>();

    /** Create a idle proxy network info object */
    private static NetworkInfo IDLE_NETWORK;
    static {
        try {
            IDLE_NETWORK = new NetworkInfo(ConnectivityManager.TYPE_PROXY, PROXY_NETWORK_SUBTYPE_ID,
                    PROXY_NETWORK_TYPE_NAME, PROXY_NETWORK_SUBTYPE_NAME);
        } catch (java.lang.NullPointerException e) {
            Log.e(TAG, "Expected testing failure: " + e);
        }
    }

    /**
     * Callback executed when Connectivity Service deems a network agent unwanted()
     */
    public interface Listener {
        public void onNetworkAgentUnwanted(int netId);
    }

    protected ProxyNetworkAgent(
            @NonNull final Context context,
            @NonNull final NetworkCapabilities capabilities,
            @NonNull final ProxyLinkProperties proxyLinkProperties) {
        mContext = context;
        mCapabilities = capabilities;
        mProxyLinkProperties = proxyLinkProperties;
    }

    @MainThread
    protected void sendCapabilities(final NetworkCapabilities capabilities) {
        DebugAssert.isMainThread();
        if (mCurrentNetworkAgent != null) {
            mCurrentNetworkAgent.sendNetworkCapabilities(capabilities);
        } else {
            Log.w(TAG, "Send capabilities with no network agent");
        }
    }

    @MainThread
    protected void setNetworkScore(final int networkScore) {
        DebugAssert.isMainThread();
        if (mCurrentNetworkScore != networkScore) {
            mCurrentNetworkScore = networkScore;
            if (mCurrentNetworkAgent != null) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Set network score for current network  agent:"
                            + mCurrentNetworkAgent.netId + " score:" + mCurrentNetworkScore);
                }
                mCurrentNetworkAgent.sendNetworkScore(mCurrentNetworkScore);
            } else {
                Log.d(TAG, "Setting network score with future network agent");
            }
        }
    }

    @MainThread
    protected int getNetworkScore() {
        DebugAssert.isMainThread();
        if (mCurrentNetworkAgent != null) {
            return mCurrentNetworkScore;
        } else {
            return 0;
        }
    }

    @MainThread
    protected void maybeSetUpNetworkAgent(
            @Nullable final String reason,
            @Nullable final String companionName,
            @Nullable final Listener listener) {
        DebugAssert.isMainThread();
        doSetUpNetworkAgent(reason, companionName, mCurrentNetworkScore, listener,
                ProxyNetworkAgent.MAYBE_RECYCLE_AGENT);
    }

    @MainThread
    protected void setUpNetworkAgent(
            @Nullable final String reason,
            @Nullable final String companionName,
            @Nullable final Listener listener) {
        DebugAssert.isMainThread();
        doSetUpNetworkAgent(reason, companionName, mCurrentNetworkScore, listener,
                ProxyNetworkAgent.ALWAYS_CREATE_AGENT);
    }

    /**
     * Create or recycle network agent
     *
     * We want to re-use the existing network agent, if available, as we only want
     * a single network agent session active at any given time.
     *
     * We want to create a new one during initial start up, or if the previous network
     * agent becomes disconnected.
     *
     * The {@link NetworkAgent} constructor connects that objects handler with
     * {@link ConnectiviyService} in order to provide proxy network access.
     *
     * If there are no clients who want this network this agent will be torn down
     * and unwanted() will be called.  This will in turn propogate a second callback
     * to the creater of {@link ProxyNetworkAgent}.
     */
    private void doSetUpNetworkAgent(
            @Nullable final String reason,
            @Nullable final String companionName,
            final int networkScore,
            @Nullable final Listener listener,
            final boolean forceNewAgent) {
        DebugAssert.isMainThread();
        if (mCurrentNetworkAgent != null) {
            if (forceNewAgent) {
                Log.w(TAG, "Network updated and overwriting current network agent since"
                        + " one already existed ... previous agent:" + mCurrentNetworkAgent.netId);
            } else {
                Log.w(TAG, "Network updated and re-using existing network agent:"
                        + mCurrentNetworkAgent.netId);
                return;
            }
        }

        final NetworkInfo networkInfo = new NetworkInfo(IDLE_NETWORK);
        networkInfo.setIsAvailable(true);
        networkInfo.setDetailedState(DetailedState.CONNECTING, reason, companionName);
        mCurrentNetworkScore = networkScore;
        mCurrentNetworkAgent = new NetworkAgent(
                Looper.getMainLooper(),
                mContext,
                NETWORK_AGENT_NAME,
                networkInfo,
                mCapabilities,
                mProxyLinkProperties.getLinkProperties(),
                networkScore) {
            @Override
            protected void unwanted() {
                DebugAssert.isMainThread();
                if (listener != null) {
                    listener.onNetworkAgentUnwanted(this.netId);
                }
                tearDownNetworkAgent(this);
            }
        };
        mNetworkAgents.put(mCurrentNetworkAgent, networkInfo);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Created network agent:" + mCurrentNetworkAgent.netId);
        }
    }

    @MainThread
    private void tearDownNetworkAgent(
            @NonNull final NetworkAgent unwantedNetworkAgent) {
        DebugAssert.isMainThread();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Proxy agent is not longer needed"
                    + "...tearing down network agent:"
                    + unwantedNetworkAgent.netId);
        }
        final NetworkInfo networkInfo = mNetworkAgents.get(unwantedNetworkAgent);
        if (networkInfo == null) {
            Log.e(TAG, "Unable to find unwanted network agent in map"
                    + " network agent:" + unwantedNetworkAgent.netId);
            return;
        }
        mNetworkAgents.remove(unwantedNetworkAgent);
        if (unwantedNetworkAgent == mCurrentNetworkAgent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "disconnected unwanted current network agent:"
                        + mCurrentNetworkAgent.netId);
            }
            mCurrentNetworkAgent = null;
        } else {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "unwanted network agent already torn down"
                        + " unwanted:" + unwantedNetworkAgent.netId + " current:"
                        + (mCurrentNetworkAgent == null ? "none" : mCurrentNetworkAgent.netId));
            }
        }
    }

    @MainThread
    protected void invalidateCurrentNetworkAgent() {
        DebugAssert.isMainThread();
        mCurrentNetworkAgent = null;
    }

    @MainThread
    protected void setConnected(@Nullable final String reason,
            @Nullable final String companionName) {
        DebugAssert.isMainThread();
        if (mCurrentNetworkAgent == null) {
            Log.w(TAG, "Send network info with no network agent reason:"
                    + reason);
        }
        setCurrentNetworkInfo(DetailedState.CONNECTED, reason, companionName);
    }

    @MainThread
    protected void setDisconnected(@Nullable final String reason,
            @Nullable final String companionName) {
        DebugAssert.isMainThread();
        setCurrentNetworkInfo(DetailedState.DISCONNECTED, reason, companionName);
    }

    @MainThread
    private void setCurrentNetworkInfo(DetailedState detailedState, String reason,
            String extraInfo) {
        DebugAssert.isMainThread();
        if (mCurrentNetworkAgent != null) {
            final NetworkInfo networkInfo = mNetworkAgents.get(mCurrentNetworkAgent);
            networkInfo.setDetailedState(detailedState, reason, extraInfo);
            mNetworkAgents.put(mCurrentNetworkAgent, networkInfo);
            mCurrentNetworkAgent.sendNetworkInfo(networkInfo);
        }
    }

    @AnyThread
    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.printPair("Network agent id", (mCurrentNetworkAgent == null)
                ? "none" : mCurrentNetworkAgent.netId);
        ipw.printPair("score", (mCurrentNetworkAgent == null) ? 0 : mCurrentNetworkScore);
        ipw.println();
        ipw.increaseIndent();
        for (Map.Entry<NetworkAgent, NetworkInfo> entry : mNetworkAgents.entrySet()) {
            ipw.printPair(entry.getKey().toString(), entry.getValue());
        }
        ipw.decreaseIndent();
        ipw.println();
    }
}
