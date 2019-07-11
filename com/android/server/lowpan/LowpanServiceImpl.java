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

import android.content.Context;
import android.net.lowpan.ILowpanInterface;
import android.net.lowpan.ILowpanManager;
import android.net.lowpan.ILowpanManagerListener;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LowpanService handles remote LoWPAN operation requests by implementing the ILowpanManager
 * interface.
 *
 * @hide
 */
public class LowpanServiceImpl extends ILowpanManager.Stub {
    private static final String TAG = LowpanServiceImpl.class.getSimpleName();
    private final Set<ILowpanManagerListener> mListenerSet = new HashSet<>();
    private final Map<String, LowpanInterfaceTracker> mInterfaceMap = new HashMap<>();
    private final Context mContext;
    private final HandlerThread mHandlerThread = new HandlerThread("LowpanServiceThread");
    private final AtomicBoolean mStarted = new AtomicBoolean(false);

    public LowpanServiceImpl(Context context) {
        mContext = context;
    }

    public Looper getLooper() {
        Looper looper = mHandlerThread.getLooper();
        if (looper == null) {
            mHandlerThread.start();
            looper = mHandlerThread.getLooper();
        }

        return looper;
    }

    public void checkAndStartLowpan() {
        synchronized (mInterfaceMap) {
            if (mStarted.compareAndSet(false, true)) {
                for (Map.Entry<String, LowpanInterfaceTracker> entry : mInterfaceMap.entrySet()) {
                    entry.getValue().register();
                }
            }
        }

        // TODO: Bring up any daemons(like wpantund)?
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_LOWPAN_STATE, "LowpanService");
    }

    private void enforceManagePermission() {
        // TODO: Change to android.Manifest.permission.MANAGE_lowpanInterfaceS
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_LOWPAN_STATE, "LowpanService");
    }

    public ILowpanInterface getInterface(String name) {
        ILowpanInterface iface = null;

        enforceAccessPermission();

        synchronized (mInterfaceMap) {
            LowpanInterfaceTracker tracker = mInterfaceMap.get(name);
            if (tracker != null) {
                iface = tracker.mILowpanInterface;
            }
        }

        return iface;
    }

    public String[] getInterfaceList() {
        enforceAccessPermission();
        synchronized (mInterfaceMap) {
            return mInterfaceMap.keySet().toArray(new String[mInterfaceMap.size()]);
        }
    }

    private void onInterfaceRemoved(ILowpanInterface lowpanInterface, String name) {
        Log.i(TAG, "Removed LoWPAN interface `" + name + "` (" + lowpanInterface.toString() + ")");
        synchronized (mListenerSet) {
            for (ILowpanManagerListener listener : mListenerSet) {
                try {
                    listener.onInterfaceRemoved(lowpanInterface);
                } catch (RemoteException | ServiceSpecificException x) {
                    // Don't let misbehavior of a listener
                    // crash the system service.
                    Log.e(TAG, "Exception caught: " + x);

                    // TODO: Consider removing the listener...?
                }
            }
        }
    }

    private void onInterfaceAdded(ILowpanInterface lowpanInterface, String name) {
        Log.i(TAG, "Added LoWPAN interface `" + name + "` (" + lowpanInterface.toString() + ")");
        synchronized (mListenerSet) {
            for (ILowpanManagerListener listener : mListenerSet) {
                try {
                    listener.onInterfaceAdded(lowpanInterface);
                } catch (RemoteException | ServiceSpecificException x) {
                    // Don't let misbehavior of a listener
                    // crash the system service.
                    Log.e(TAG, "Exception caught: " + x);

                    // TODO: Consider removing the listener...?
                }
            }
        }
    }

    public void addInterface(ILowpanInterface lowpanInterface) {
        enforceManagePermission();

        final String name;

        try {
            // We allow blocking calls to get the name of the interface.
            Binder.allowBlocking(lowpanInterface.asBinder());

            name = lowpanInterface.getName();
            lowpanInterface
                    .asBinder()
                    .linkToDeath(
                            new IBinder.DeathRecipient() {
                                @Override
                                public void binderDied() {
                                    Log.w(
                                            TAG,
                                            "LoWPAN interface `"
                                                    + name
                                                    + "` ("
                                                    + lowpanInterface.toString()
                                                    + ") died.");
                                    removeInterface(lowpanInterface);
                                }
                            },
                            0);

        } catch (RemoteException | ServiceSpecificException x) {
            // Don't let misbehavior of an interface
            // crash the system service.
            Log.e(TAG, "Exception caught: " + x);
            return;
        }

        final LowpanInterfaceTracker previous;
        final LowpanInterfaceTracker agent;

        synchronized (mInterfaceMap) {
            previous = mInterfaceMap.get(name);

            agent = new LowpanInterfaceTracker(mContext, lowpanInterface, getLooper());

            mInterfaceMap.put(name, agent);
        }

        if (previous != null) {
            previous.unregister();
            onInterfaceRemoved(previous.mILowpanInterface, name);
        }

        if (mStarted.get()) {
            agent.register();
        }

        onInterfaceAdded(lowpanInterface, name);
    }

    private void removeInterfaceByName(String name) {
        final ILowpanInterface lowpanInterface;

        enforceManagePermission();

        if (name == null) {
            return;
        }

        final LowpanInterfaceTracker agent;

        synchronized (mInterfaceMap) {
            agent = mInterfaceMap.get(name);

            if (agent == null) {
                return;
            }

            lowpanInterface = agent.mILowpanInterface;

            if (mStarted.get()) {
                agent.unregister();
            }

            mInterfaceMap.remove(name);
        }

        onInterfaceRemoved(lowpanInterface, name);
    }

    public void removeInterface(ILowpanInterface lowpanInterface) {
        String name = null;

        try {
            name = lowpanInterface.getName();
        } catch (RemoteException | ServiceSpecificException x) {
            // Directly fetching the name failed, so fall back to
            // a reverse lookup.
            synchronized (mInterfaceMap) {
                for (Map.Entry<String, LowpanInterfaceTracker> entry : mInterfaceMap.entrySet()) {
                    if (entry.getValue().mILowpanInterface == lowpanInterface) {
                        name = entry.getKey();
                        break;
                    }
                }
            }
        }

        removeInterfaceByName(name);
    }

    public void addListener(ILowpanManagerListener listener) {
        enforceAccessPermission();
        synchronized (mListenerSet) {
            if (!mListenerSet.contains(listener)) {
                try {
                    listener.asBinder()
                            .linkToDeath(
                                    new IBinder.DeathRecipient() {
                                        @Override
                                        public void binderDied() {
                                            synchronized (mListenerSet) {
                                                mListenerSet.remove(listener);
                                            }
                                        }
                                    },
                                    0);
                    mListenerSet.add(listener);
                } catch (RemoteException x) {
                    // We only get this exception if listener has already died.
                    Log.e(TAG, "Exception caught: " + x);
                }
            }
        }
    }

    public void removeListener(ILowpanManagerListener listener) {
        enforceAccessPermission();
        synchronized (mListenerSet) {
            mListenerSet.remove(listener);
            // TODO: Shouldn't we be unlinking from the death notification?
        }
    }
}
