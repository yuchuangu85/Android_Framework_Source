/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.lowpan;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.ip.IpManager;
import android.net.ip.IpManager.InitialConfiguration;
import android.net.ip.IpManager.ProvisioningConfiguration;
import android.net.lowpan.ILowpanInterface;
import android.net.lowpan.LowpanException;
import android.net.lowpan.LowpanInterface;
import android.net.lowpan.LowpanRuntimeException;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import com.android.internal.util.HexDump;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

/** Tracks connectivity of a LoWPAN interface. */
class LowpanInterfaceTracker extends StateMachine {

    // Misc Constants

    /** Network type string for NetworkInfo */
    private static final String NETWORK_TYPE = "LoWPAN";

    /** Tag used for logging */
    private static final String TAG = "LowpanInterfaceTracker";

    /**
     * Maximum network score for LoWPAN networks.
     *
     * <p>TODO: Research if 30 is an appropriate value.
     */
    private static final int NETWORK_SCORE = 30;

    /** Internal debugging flag. */
    private static final boolean DBG = true;

    /** Number of state machine log records. */
    public static final short NUM_LOG_RECS_NORMAL = 100;

    // Message Code Enumeration Constants

    /** The base for LoWPAN message codes */
    static final int BASE = Protocol.BASE_LOWPAN;

    static final int CMD_START_NETWORK = BASE + 3;
    static final int CMD_STOP_NETWORK = BASE + 4;
    static final int CMD_STATE_CHANGE = BASE + 5;
    static final int CMD_LINK_PROPERTIES_CHANGE = BASE + 6;
    static final int CMD_UNWANTED = BASE + 7;
    static final int CMD_PROVISIONING_SUCCESS = BASE + 8;
    static final int CMD_PROVISIONING_FAILURE = BASE + 9;

    // Services and interfaces

    ILowpanInterface mILowpanInterface;
    private LowpanInterface mLowpanInterface;
    private NetworkAgent mNetworkAgent;
    private NetworkFactory mNetworkFactory;
    private IpManager mIpManager;
    private final IpManager.Callback mIpManagerCallback = new IpManagerCallback();

    // Instance Variables

    private String mInterfaceName;
    private String mHwAddr;
    private Context mContext;
    private NetworkInfo mNetworkInfo;
    private LinkProperties mLinkProperties;
    private final NetworkCapabilities mNetworkCapabilities = new NetworkCapabilities();
    private String mState = "";

    // State machine state instances

    final DefaultState mDefaultState = new DefaultState();
    final NormalState mNormalState = new NormalState();
    final OfflineState mOfflineState = new OfflineState();
    final CommissioningState mCommissioningState = new CommissioningState();
    final AttachingState mAttachingState = new AttachingState();
    final AttachedState mAttachedState = new AttachedState();
    final ObtainingIpState mObtainingIpState = new ObtainingIpState();
    final FaultState mFaultState = new FaultState();
    final ConnectedState mConnectedState = new ConnectedState();

    private LocalLowpanCallback mLocalLowpanCallback = new LocalLowpanCallback();

    // Misc Private Classes

    private class LocalLowpanCallback extends LowpanInterface.Callback {
        @Override
        public void onEnabledChanged(boolean value) {}

        @Override
        public void onUpChanged(boolean value) {}

        @Override
        public void onConnectedChanged(boolean value) {}

        @Override
        public void onStateChanged(@NonNull String state) {
            LowpanInterfaceTracker.this.sendMessage(CMD_STATE_CHANGE, state);
        }
    }

    class IpManagerCallback extends IpManager.Callback {
        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            LowpanInterfaceTracker.this.sendMessage(CMD_PROVISIONING_SUCCESS, newLp);
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            LowpanInterfaceTracker.this.sendMessage(CMD_PROVISIONING_FAILURE, newLp);
        }

        @Override
        public void onLinkPropertiesChange(LinkProperties newLp) {
            LowpanInterfaceTracker.this.sendMessage(CMD_LINK_PROPERTIES_CHANGE, newLp);
        }
    }

    // State Definitions

    class DefaultState extends State {
        @Override
        public void enter() {
            if (DBG) {
                Log.i(TAG, "DefaultState.enter()");
            }

            mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_NONE, 0, NETWORK_TYPE, "");
            mNetworkInfo.setIsAvailable(true);

            mLowpanInterface.registerCallback(mLocalLowpanCallback);

            mState = "";

            sendMessage(CMD_STATE_CHANGE, mLowpanInterface.getState());
        }

        @Override
        public boolean processMessage(Message message) {
            boolean retValue = NOT_HANDLED;

            switch (message.what) {
                case CMD_START_NETWORK:
                    if (DBG) {
                        Log.i(TAG, "CMD_START_NETWORK");
                    }
                    try {
                        mLowpanInterface.setEnabled(true);
                    } catch (LowpanException | LowpanRuntimeException x) {
                        Log.e(TAG, "Exception while enabling: " + x);
                        transitionTo(mFaultState);
                        return HANDLED;
                    }
                    break;

                case CMD_STOP_NETWORK:
                    if (DBG) {
                        Log.i(TAG, "CMD_STOP_NETWORK");
                    }
                    try {
                        mLowpanInterface.setEnabled(false);
                    } catch (LowpanException | LowpanRuntimeException x) {
                        Log.e(TAG, "Exception while disabling: " + x);
                        transitionTo(mFaultState);
                        return HANDLED;
                    }
                    break;

                case CMD_STATE_CHANGE:
                    if (!mState.equals(message.obj)) {
                        if (DBG) {
                            Log.i(
                                    TAG,
                                    "LowpanInterface changed state from \""
                                            + mState
                                            + "\" to \""
                                            + message.obj
                                            + "\".");
                        }
                        mState = (String) message.obj;
                        switch (mState) {
                            case LowpanInterface.STATE_OFFLINE:
                                transitionTo(mOfflineState);
                                break;
                            case LowpanInterface.STATE_COMMISSIONING:
                                transitionTo(mCommissioningState);
                                break;
                            case LowpanInterface.STATE_ATTACHING:
                                transitionTo(mAttachingState);
                                break;
                            case LowpanInterface.STATE_ATTACHED:
                                transitionTo(mObtainingIpState);
                                break;
                            case LowpanInterface.STATE_FAULT:
                                transitionTo(mFaultState);
                                break;
                        }
                    }
                    retValue = HANDLED;
                    break;
            }
            return retValue;
        }

        @Override
        public void exit() {
            mLowpanInterface.unregisterCallback(mLocalLowpanCallback);
        }
    }

    class NormalState extends State {
        @Override
        public void enter() {
            if (DBG) {
                Log.i(TAG, "NormalState.enter()");
            }

            mIpManager = new IpManager(mContext, mInterfaceName, mIpManagerCallback);

            if (mHwAddr == null) {
                byte[] hwAddr = null;
                try {
                    hwAddr = mLowpanInterface.getService().getMacAddress();

                } catch (RemoteException | ServiceSpecificException x) {
                    // Don't let misbehavior of the interface service
                    // crash the system service.
                    Log.e(TAG, "Call to getMacAddress() failed: " + x);
                    transitionTo(mFaultState);
                }

                if (hwAddr != null) {
                    mHwAddr = HexDump.toHexString(hwAddr);
                }
            }

            mNetworkFactory.register();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_UNWANTED:
                    if (mNetworkAgent == message.obj) {
                        if (DBG) {
                            Log.i(TAG, "UNWANTED.");
                        }

                        try {
                            mLowpanInterface.setEnabled(false);
                        } catch (LowpanException | LowpanRuntimeException x) {
                            Log.e(TAG, "Exception while disabling: " + x);
                            transitionTo(mFaultState);
                            return HANDLED;
                        }

                        shutdownNetworkAgent();
                    }
                    break;

                case CMD_LINK_PROPERTIES_CHANGE:
                    mLinkProperties = (LinkProperties) message.obj;
                    if (DBG) {
                        Log.i(TAG, "Got LinkProperties: " + mLinkProperties);
                    }
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendLinkProperties(mLinkProperties);
                    }
                    break;

                case CMD_PROVISIONING_FAILURE:
                    Log.i(TAG, "Provisioning Failure: " + message.obj);
                    break;
            }

            return NOT_HANDLED;
        }

        @Override
        public void exit() {
            shutdownNetworkAgent();
            mNetworkFactory.unregister();

            if (mIpManager != null) {
                mIpManager.shutdown();
            }
            mIpManager = null;
        }
    }

    class OfflineState extends State {
        @Override
        public void enter() {
            shutdownNetworkAgent();
            mNetworkInfo.setIsAvailable(true);

            mIpManager.stop();
        }

        @Override
        public boolean processMessage(Message message) {
            return NOT_HANDLED;
        }

        @Override
        public void exit() {}
    }

    class CommissioningState extends State {
        @Override
        public void enter() {}

        @Override
        public boolean processMessage(Message message) {
            return NOT_HANDLED;
        }

        @Override
        public void exit() {}
    }

    class AttachingState extends State {
        @Override
        public void enter() {
            mNetworkInfo.setDetailedState(DetailedState.CONNECTING, null, mHwAddr);
            mNetworkInfo.setIsAvailable(true);
            bringUpNetworkAgent();
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        @Override
        public boolean processMessage(Message message) {
            return NOT_HANDLED;
        }

        @Override
        public void exit() {}
    }

    class AttachedState extends State {
        @Override
        public void enter() {
            bringUpNetworkAgent();
            mNetworkInfo.setIsAvailable(true);
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_STATE_CHANGE:
                    if (!mState.equals(message.obj)
                            && !LowpanInterface.STATE_ATTACHED.equals(message.obj)) {
                        return NOT_HANDLED;
                    }
                    return HANDLED;

                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        public void exit() {
            mNetworkInfo.setIsAvailable(false);
        }
    }

    class ObtainingIpState extends State {
        @Override
        public void enter() {
            InitialConfiguration initialConfiguration = new InitialConfiguration();

            try {
                for (LinkAddress address : mLowpanInterface.getLinkAddresses()) {
                    if (DBG) {
                        Log.i(TAG, "Adding link address: " + address);
                    }

                    initialConfiguration.ipAddresses.add(address);

                    IpPrefix prefix = new IpPrefix(address.getAddress(), address.getPrefixLength());

                    initialConfiguration.directlyConnectedRoutes.add(prefix);
                }

                for (IpPrefix prefix : mLowpanInterface.getLinkNetworks()) {
                    if (DBG) {
                        Log.i(TAG, "Adding directly connected route: " + prefix);
                    }

                    initialConfiguration.directlyConnectedRoutes.add(prefix);
                }

            } catch (LowpanException | LowpanRuntimeException x) {
                Log.e(TAG, "Exception while populating InitialConfiguration: " + x);
                transitionTo(mFaultState);
                return;

            } catch (RuntimeException x) {
                if (x.getCause() instanceof RemoteException) {
                    // Don't let misbehavior of an interface service
                    // crash the system service.
                    Log.e(TAG, "RuntimeException while populating InitialConfiguration: " + x);
                    transitionTo(mFaultState);

                } else {
                    // This exception wasn't remote in origin, so we rethrow.
                    throw x;
                }
            }

            if (!initialConfiguration.isValid()) {
                Log.e(TAG, "Invalid initial configuration: " + initialConfiguration);
                transitionTo(mFaultState);
                return;
            }

            if (DBG) {
                Log.d(TAG, "Using Initial configuration: " + initialConfiguration);
            }

            final ProvisioningConfiguration.Builder builder =
                    mIpManager.buildProvisioningConfiguration();

            builder.withInitialConfiguration(initialConfiguration).withProvisioningTimeoutMs(0);

            // LoWPAN networks generally don't have internet connectivity,
            // so the reachability monitor would almost always fail.
            builder.withoutIpReachabilityMonitor();

            // We currently only support IPv6 on LoWPAN networks, although
            // theoretically we could make this determination by examining
            // the InitialConfiguration for any IPv4 addresses.
            builder.withoutIPv4();

            mIpManager.startProvisioning(builder.build());

            mNetworkInfo.setDetailedState(DetailedState.OBTAINING_IPADDR, null, mHwAddr);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        @Override
        public boolean processMessage(Message message) {

            switch (message.what) {
                case CMD_PROVISIONING_SUCCESS:
                    Log.i(TAG, "Provisioning Success: " + message.obj);
                    transitionTo(mConnectedState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }

        @Override
        public void exit() {}
    }

    class ConnectedState extends State {
        @Override
        public void enter() {
            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, mHwAddr);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent.sendNetworkScore(NETWORK_SCORE);
        }

        @Override
        public boolean processMessage(Message message) {
            return NOT_HANDLED;
        }

        @Override
        public void exit() {
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkScore(0);
            }
        }
    }

    class FaultState extends State {
        @Override
        public void enter() {}

        @Override
        public boolean processMessage(Message message) {
            return NOT_HANDLED;
        }

        @Override
        public void exit() {}
    }

    public LowpanInterfaceTracker(Context context, ILowpanInterface ifaceService, Looper looper) {
        super(TAG, looper);

        if (DBG) {
            Log.i(TAG, "LowpanInterfaceTracker() begin");
        }

        setDbg(DBG);
        setLogRecSize(NUM_LOG_RECS_NORMAL);
        setLogOnlyTransitions(false);

        mILowpanInterface = ifaceService;
        mLowpanInterface = new LowpanInterface(context, ifaceService, looper);
        mContext = context;

        mInterfaceName = mLowpanInterface.getName();

        mLinkProperties = new LinkProperties();
        mLinkProperties.setInterfaceName(mInterfaceName);

        // Initialize capabilities
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mNetworkCapabilities.setLinkUpstreamBandwidthKbps(100);
        mNetworkCapabilities.setLinkDownstreamBandwidthKbps(100);

        // CHECKSTYLE:OFF IndentationCheck
        addState(mDefaultState);
        addState(mFaultState, mDefaultState);
        addState(mNormalState, mDefaultState);
        addState(mOfflineState, mNormalState);
        addState(mCommissioningState, mNormalState);
        addState(mAttachingState, mNormalState);
        addState(mAttachedState, mNormalState);
        addState(mObtainingIpState, mAttachedState);
        addState(mConnectedState, mAttachedState);
        // CHECKSTYLE:ON IndentationCheck

        setInitialState(mDefaultState);

        mNetworkFactory =
                new NetworkFactory(looper, context, NETWORK_TYPE, mNetworkCapabilities) {
                    @Override
                    protected void startNetwork() {
                        LowpanInterfaceTracker.this.sendMessage(CMD_START_NETWORK);
                    }

                    @Override
                    protected void stopNetwork() {
                        LowpanInterfaceTracker.this.sendMessage(CMD_STOP_NETWORK);
                    }
                };

        if (DBG) {
            Log.i(TAG, "LowpanInterfaceTracker() end");
        }
    }

    private void shutdownNetworkAgent() {
        mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mHwAddr);
        mNetworkInfo.setIsAvailable(false);

        if (mNetworkAgent != null) {
            mNetworkAgent.sendNetworkScore(0);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        mNetworkAgent = null;
    }

    private void bringUpNetworkAgent() {
        if (mNetworkAgent == null) {
            mNetworkAgent =
                    new NetworkAgent(
                            mNetworkFactory.getLooper(),
                            mContext,
                            NETWORK_TYPE,
                            mNetworkInfo,
                            mNetworkCapabilities,
                            mLinkProperties,
                            NETWORK_SCORE) {
                        public void unwanted() {
                            LowpanInterfaceTracker.this.sendMessage(CMD_UNWANTED, this);
                        };
                    };
        }
    }

    public void register() {
        if (DBG) {
            Log.i(TAG, "register()");
        }
        start();
    }

    public void unregister() {
        if (DBG) {
            Log.i(TAG, "unregister()");
        }
        quit();
    }
}
