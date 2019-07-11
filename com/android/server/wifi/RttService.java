package com.android.server.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.RttManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.net.wifi.IRttManager;
import android.util.Slog;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;
import com.android.internal.util.State;
import com.android.server.SystemService;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import android.Manifest;

public final class RttService extends SystemService {

    public static final boolean DBG = true;

    class RttServiceImpl extends IRttManager.Stub {

        @Override
        public Messenger getMessenger() {
            return new Messenger(mClientHandler);
        }

        private class ClientHandler extends Handler {

            ClientHandler(android.os.Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {

                if (DBG) Log.d(TAG, "ClientHandler got" + msg);

                switch (msg.what) {

                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            AsyncChannel c = (AsyncChannel) msg.obj;
                            if (DBG) Slog.d(TAG, "New client listening to asynchronous messages: " +
                                    msg.replyTo);
                            ClientInfo cInfo = new ClientInfo(c, msg.replyTo);
                            mClients.put(msg.replyTo, cInfo);
                        } else {
                            Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                        }
                        return;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                            Slog.e(TAG, "Send failed, client connection lost");
                        } else {
                            if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                        }
                        if (DBG) Slog.d(TAG, "closing client " + msg.replyTo);
                        ClientInfo ci = mClients.remove(msg.replyTo);
                        if (ci != null) ci.cleanup();
                        return;
                    case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                        AsyncChannel ac = new AsyncChannel();
                        ac.connect(mContext, this, msg.replyTo);
                        return;
                }

                ClientInfo ci = mClients.get(msg.replyTo);
                if (ci == null) {
                    Slog.e(TAG, "Could not find client info for message " + msg.replyTo);
                    replyFailed(msg, RttManager.REASON_INVALID_LISTENER, "Could not find listener");
                    return;
                }

                int validCommands[] = {
                        RttManager.CMD_OP_START_RANGING,
                        RttManager.CMD_OP_STOP_RANGING
                        };

                for(int cmd : validCommands) {
                    if (cmd == msg.what) {
                        mStateMachine.sendMessage(Message.obtain(msg));
                        return;
                    }
                }

                replyFailed(msg, RttManager.REASON_INVALID_REQUEST, "Invalid request");
            }
        }

        private Context mContext;
        private RttStateMachine mStateMachine;
        private ClientHandler mClientHandler;

        RttServiceImpl() { }

        RttServiceImpl(Context context) {
            mContext = context;
        }

        public void startService(Context context) {
            mContext = context;

            HandlerThread thread = new HandlerThread("WifiRttService");
            thread.start();

            mClientHandler = new ClientHandler(thread.getLooper());
            mStateMachine = new RttStateMachine(thread.getLooper());

            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            int state = intent.getIntExtra(
                                    WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
                            if (DBG) Log.d(TAG, "SCAN_AVAILABLE : " + state);
                            if (state == WifiManager.WIFI_STATE_ENABLED) {
                                mStateMachine.sendMessage(CMD_DRIVER_LOADED);
                            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                                mStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                            }
                        }
                    }, new IntentFilter(WifiManager.WIFI_SCAN_AVAILABLE));

            mStateMachine.start();
        }

        private class RttRequest {
            Integer key;
            ClientInfo ci;
            RttManager.RttParams[] params;

            @Override
            public String toString() {
                String str = getClass().getName() + "@" + Integer.toHexString(hashCode());
                if(this.key != null) {
                    return str + " key: " + this.key;
                } else {
                    return str + " key: " + " , null";
                }
            }
        }

        private class ClientInfo {
            private final AsyncChannel mChannel;
            private final Messenger mMessenger;
            HashMap<Integer, RttRequest> mRequests = new HashMap<Integer,
                    RttRequest>();

            ClientInfo(AsyncChannel c, Messenger m) {
                mChannel = c;
                mMessenger = m;
            }

            boolean addRttRequest(int key, RttManager.ParcelableRttParams parcelableParams) {
                if (parcelableParams == null) {
                    return false;
                }

                RttManager.RttParams params[] = parcelableParams.mParams;

                RttRequest request = new RttRequest();
                request.key = key;
                request.ci = this;
                request.params = params;
                mRequests.put(key, request);
                mRequestQueue.add(request);
                return true;
            }

            void removeRttRequest(int key) {
                mRequests.remove(key);
            }

            void reportResult(RttRequest request, RttManager.RttResult[] results) {
                RttManager.ParcelableRttResults parcelableResults =
                        new RttManager.ParcelableRttResults(results);

                mChannel.sendMessage(RttManager.CMD_OP_SUCCEEDED,
                        0, request.key, parcelableResults);
                mRequests.remove(request.key);
            }

            void reportFailed(RttRequest request, int reason, String description) {
                reportFailed(request.key, reason, description);
            }

            void reportFailed(int key, int reason, String description) {
                Bundle bundle = new Bundle();
                bundle.putString(RttManager.DESCRIPTION_KEY, description);
                mChannel.sendMessage(RttManager.CMD_OP_FAILED, key, reason, bundle);
                mRequests.remove(key);
            }

            void reportAborted(int key) {
                mChannel.sendMessage(RttManager.CMD_OP_ABORTED, 0, key);
                //All Queued RTT request will be cleaned
                cleanup();
            }

            void cleanup() {
                mRequests.clear();
                mRequestQueue.clear();
            }
        }

        private Queue<RttRequest> mRequestQueue = new LinkedList<RttRequest>();
        private HashMap<Messenger, ClientInfo> mClients = new HashMap<Messenger, ClientInfo>(4);

        private static final int BASE = Protocol.BASE_WIFI_RTT_SERVICE;

        private static final int CMD_DRIVER_LOADED                       = BASE + 0;
        private static final int CMD_DRIVER_UNLOADED                     = BASE + 1;
        private static final int CMD_ISSUE_NEXT_REQUEST                  = BASE + 2;
        private static final int CMD_RTT_RESPONSE                        = BASE + 3;

        class RttStateMachine extends StateMachine {

            DefaultState mDefaultState = new DefaultState();
            EnabledState mEnabledState = new EnabledState();
            RequestPendingState mRequestPendingState = new RequestPendingState();

            RttStateMachine(Looper looper) {
                super("RttStateMachine", looper);

                addState(mDefaultState);
                addState(mEnabledState);
                    addState(mRequestPendingState, mEnabledState);

                setInitialState(mDefaultState);
            }

            class DefaultState extends State {
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "DefaultState got" + msg);
                    switch (msg.what) {
                        case CMD_DRIVER_LOADED:
                            transitionTo(mEnabledState);
                            break;
                        case CMD_ISSUE_NEXT_REQUEST:
                            deferMessage(msg);
                            break;
                        case RttManager.CMD_OP_START_RANGING:
                            replyFailed(msg, RttManager.REASON_NOT_AVAILABLE, "Try later");
                            break;
                        case RttManager.CMD_OP_STOP_RANGING:
                            return HANDLED;
                        default:
                            return NOT_HANDLED;
                    }
                    return HANDLED;
                }
            }

            class EnabledState extends State {
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "EnabledState got" + msg);
                    ClientInfo ci = mClients.get(msg.replyTo);

                    switch (msg.what) {
                        case CMD_DRIVER_UNLOADED:
                            transitionTo(mDefaultState);
                            break;
                        case CMD_ISSUE_NEXT_REQUEST:
                            deferMessage(msg);
                            transitionTo(mRequestPendingState);
                            break;
                        case RttManager.CMD_OP_START_RANGING: {
                            //check permission
                            if(DBG) Log.d(TAG, "UID is: " + msg.sendingUid);
                            if (!enforcePermissionCheck(msg)) {
                                Log.e(TAG, "UID: " + msg.sendingUid + " has no" +
                                        " LOCATION_HARDWARE Permission");
                                break;
                            }

                            RttManager.ParcelableRttParams params =
                                    (RttManager.ParcelableRttParams)msg.obj;
                            if (params == null) {
                                replyFailed(msg,
                                        RttManager.REASON_INVALID_REQUEST, "No params");
                            } else if (ci.addRttRequest(msg.arg2, params) == false) {
                                replyFailed(msg,
                                        RttManager.REASON_INVALID_REQUEST, "Unspecified");
                            } else {
                                sendMessage(CMD_ISSUE_NEXT_REQUEST);
                            }
                        }
                            break;
                        case RttManager.CMD_OP_STOP_RANGING:
                            if(!enforcePermissionCheck(msg)) {
                                break;
                            }

                            for (Iterator<RttRequest> it = mRequestQueue.iterator();
                                    it.hasNext(); ) {
                                RttRequest request = it.next();
                                if (request.key == msg.arg2) {
                                    if (DBG) Log.d(TAG, "Cancelling not-yet-scheduled RTT");
                                    mRequestQueue.remove(request);
                                    request.ci.reportAborted(request.key);
                                    break;
                                }
                            }
                            break;
                        default:
                            return NOT_HANDLED;
                    }
                    return HANDLED;
                }
            }

            class RequestPendingState extends State {
                RttRequest mOutstandingRequest;
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "RequestPendingState got" + msg);
                    switch (msg.what) {
                        case CMD_DRIVER_UNLOADED:
                            if (mOutstandingRequest != null) {
                                WifiNative.cancelRtt(mOutstandingRequest.params);
                                if (DBG) Log.d(TAG, "abort key: " + mOutstandingRequest.key);
                                mOutstandingRequest.ci.reportAborted(mOutstandingRequest.key);
                                mOutstandingRequest = null;
                            }
                            transitionTo(mDefaultState);
                            break;
                        case CMD_ISSUE_NEXT_REQUEST:
                            if (mOutstandingRequest == null) {
                                mOutstandingRequest = issueNextRequest();
                                if (mOutstandingRequest == null) {
                                    transitionTo(mEnabledState);
                                }
                                if(mOutstandingRequest != null) {
                                    if (DBG) Log.d(TAG, "new mOutstandingRequest.key is: " +
                                            mOutstandingRequest.key);
                                } else {
                                    if (DBG) Log.d(TAG,
                                            "CMD_ISSUE_NEXT_REQUEST: mOutstandingRequest =null ");
                                }
                            } else {
                                /* just wait; we'll issue next request after
                                 * current one is finished */
                                 if (DBG) Log.d(TAG, "Current mOutstandingRequest.key is: " +
                                         mOutstandingRequest.key);
                                 if (DBG) Log.d(TAG, "Ignoring CMD_ISSUE_NEXT_REQUEST");
                            }
                            break;
                        case CMD_RTT_RESPONSE:
                            if (DBG) Log.d(TAG, "Received an RTT response from: " + msg.arg2);
                            mOutstandingRequest.ci.reportResult(
                                    mOutstandingRequest, (RttManager.RttResult[])msg.obj);
                            mOutstandingRequest = null;
                            sendMessage(CMD_ISSUE_NEXT_REQUEST);
                            break;
                        case RttManager.CMD_OP_STOP_RANGING:
                            if(!enforcePermissionCheck(msg)) {
                                Log.e(TAG, "UID: " + msg.sendingUid + " has no " +
                                        "LOCATION_HARDWARE Permission");
                                break;
                            }

                            if (mOutstandingRequest != null
                                    && msg.arg2 == mOutstandingRequest.key) {
                                if (DBG) Log.d(TAG, "Cancelling ongoing RTT of: " + msg.arg2);
                                WifiNative.cancelRtt(mOutstandingRequest.params);
                                mOutstandingRequest.ci.reportAborted(mOutstandingRequest.key);
                                mOutstandingRequest = null;
                                sendMessage(CMD_ISSUE_NEXT_REQUEST);
                            } else {
                                /* Let EnabledState handle this */
                                return NOT_HANDLED;
                            }
                            break;
                        default:
                            return NOT_HANDLED;
                    }
                    return HANDLED;
                }
            }
        }

        void replySucceeded(Message msg, Object obj) {
            if (msg.replyTo != null) {
                Message reply = Message.obtain();
                reply.what = RttManager.CMD_OP_SUCCEEDED;
                reply.arg2 = msg.arg2;
                reply.obj = obj;
                try {
                    msg.replyTo.send(reply);
                } catch (RemoteException e) {
                    // There's not much we can do if reply can't be sent!
                }
            } else {
                // locally generated message; doesn't need a reply!
            }
        }

        void replyFailed(Message msg, int reason, String description) {
            Message reply = Message.obtain();
            reply.what = RttManager.CMD_OP_FAILED;
            reply.arg1 = reason;
            reply.arg2 = msg.arg2;

            Bundle bundle = new Bundle();
            bundle.putString(RttManager.DESCRIPTION_KEY, description);
            reply.obj = bundle;

            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        }

        boolean enforcePermissionCheck(Message msg) {
            try {
                mContext.enforcePermission(Manifest.permission.LOCATION_HARDWARE,
                         -1, msg.sendingUid, "LocationRTT");
            } catch (SecurityException e) {
                replyFailed(msg,RttManager.REASON_PERMISSION_DENIED, "No params");
                return false;
            }
            return true;
        }

        private WifiNative.RttEventHandler mEventHandler = new WifiNative.RttEventHandler() {
            @Override
            public void onRttResults(RttManager.RttResult[] result) {
                mStateMachine.sendMessage(CMD_RTT_RESPONSE, result);
            }
        };

        RttRequest issueNextRequest() {
            RttRequest request = null;
            while (mRequestQueue.isEmpty() == false) {
                request = mRequestQueue.remove();
                if(request !=  null) {
                    if (WifiNative.requestRtt(request.params, mEventHandler)) {
                        if (DBG) Log.d(TAG, "Issued next RTT request with key: " + request.key);
                        return request;
                    } else {
                        Log.e(TAG, "Fail to issue key at native layer");
                        request.ci.reportFailed(request,
                                RttManager.REASON_UNSPECIFIED, "Failed to start");
                    }
                }
            }

            /* all requests exhausted */
            if (DBG) Log.d(TAG, "No more requests left");
            return null;
        }
        @Override
        public RttManager.RttCapabilities getRttCapabilities() {
            return WifiNative.getRttCapabilities();
        }
    }

    private static final String TAG = "RttService";
    RttServiceImpl mImpl;

    public RttService(Context context) {
        super(context);
        Log.i(TAG, "Creating " + Context.WIFI_RTT_SERVICE);
    }

    @Override
    public void onStart() {
        mImpl = new RttServiceImpl(getContext());

        Log.i(TAG, "Starting " + Context.WIFI_RTT_SERVICE);
        publishBinderService(Context.WIFI_RTT_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.i(TAG, "Registering " + Context.WIFI_RTT_SERVICE);
            if (mImpl == null) {
                mImpl = new RttServiceImpl(getContext());
            }
            mImpl.startService(getContext());
        }
    }


}
