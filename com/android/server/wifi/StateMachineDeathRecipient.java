/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import com.android.internal.util.StateMachine;

/**
 * Allows StateMachine instances to subscribe to binder death.
 *
 * @hide
 */
public class StateMachineDeathRecipient implements DeathRecipient {

    private final StateMachine mStateMachine;
    private final int mDeathCommand;
    private IBinder mLinkedBinder;

    /**
     * Construct a StateMachineDeathRecipient.
     *
     * @param sm StateMachine instance to receive a message upon Binder death.
     * @param command message to send the state machine.
     */
    public StateMachineDeathRecipient(StateMachine sm, int command) {
        mStateMachine = sm;
        mDeathCommand = command;
    }

    /**
     * Listen for the death of a binder.
     *
     * This method will unlink from death notifications from any
     * previously linked IBinder instance.
     *
     * @param binder remote object to listen for death.
     * @return true iff we have successfully subscribed to death notifications of a live
     *         IBinder instance.
     */
    public boolean linkToDeath(IBinder binder) {
        unlinkToDeath();
        try {
            binder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            // The remote has already died.
            return false;
        }
        mLinkedBinder = binder;
        return true;
    }

    /**
     * Unlink from notifications from the last linked IBinder instance.
     */
    public void unlinkToDeath() {
        if (mLinkedBinder == null) {
            return;
        }
        mLinkedBinder.unlinkToDeath(this, 0);
        mLinkedBinder = null;
    }

    /**
     * Called by the binder subsystem upon remote object death.
     */
    @Override
    public void binderDied() {
        mStateMachine.sendMessage(mDeathCommand);
    }
}