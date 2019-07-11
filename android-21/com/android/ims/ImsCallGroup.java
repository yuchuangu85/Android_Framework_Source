/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import java.util.ArrayList;

import com.android.ims.internal.ICallGroup;
import com.android.ims.internal.ICall;

/**
 * Manages all IMS calls which are established hereafter the initial 1-to-1 call is established.
 * It's for providing the dummy calls which are disconnected with the IMS network after
 * merged or extended to the conference.
 *
 * @hide
 */
public class ImsCallGroup implements ICallGroup {
    private Object mLockObj = new Object();
    private ImsCall mOwner;
    private ImsCall mNeutralReferrer;
    private ArrayList<ICall> mReferrers = new ArrayList<ICall>();

    public ImsCallGroup() {
    }

    @Override
    public ICall getNeutralReferrer() {
        synchronized(mLockObj) {
            return mNeutralReferrer;
        }
    }

    @Override
    public ICall getOwner() {
        synchronized(mLockObj) {
            return mOwner;
        }
    }

    @Override
    public ArrayList<ICall> getReferrers() {
        synchronized(mLockObj) {
            return mReferrers;
        }
    }

    @Override
    public boolean hasReferrer() {
        synchronized(mLockObj) {
            return !mReferrers.isEmpty();
        }
    }

    @Override
    public boolean isOwner(ICall call) {
        ImsCall owner;

        synchronized(mLockObj) {
            owner = mOwner;
        }

        if ((call == null) || (owner == null)) {
            return false;
        }

        if (!(call instanceof ImsCall)) {
            return false;
        }

        return isSameCall(owner, (ImsCall)call);
    }

    @Override
    public boolean isReferrer(ICall call) {
        if (call == null) {
            return false;
        }

        if (!(call instanceof ImsCall)) {
            return false;
        }

        synchronized(mLockObj) {
            for (ICall c : mReferrers) {
                if ((c != null) && isSameCall((ImsCall)c, (ImsCall)call)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void addReferrer(ICall call) {
        if (call == null) {
            return;
        }

        if (!(call instanceof ImsCall)) {
            return;
        }

        // If the call is already present, ignore it
        if (isReferrer(call)) {
            return;
        }

        synchronized(mLockObj) {
            mReferrers.add(call);
        }
    }

    @Override
    public void removeReferrer(ICall call) {
        if (call == null) {
            return;
        }

        if (!(call instanceof ImsCall)) {
            return;
        }

        synchronized(mLockObj) {
            mReferrers.remove(call);
        }
    }

    @Override
    public void setNeutralReferrer(ICall call) {
        if ((call != null) && !(call instanceof ImsCall)) {
            return;
        }

        synchronized(mLockObj) {
            mNeutralReferrer = (ImsCall)call;
        }
    }

    @Override
    public void setOwner(ICall call) {
        if ((call != null) && !(call instanceof ImsCall)) {
            return;
        }

        synchronized(mLockObj) {
            mOwner = (ImsCall)call;
        }
    }

    @Override
    public ICall getReferrer(String name) {
        if ((name == null) || (name.isEmpty())) {
            return null;
        }

        ArrayList<ICall> referrers = getReferrers();

        if (referrers == null) {
            return null;
        }

        for (ICall call : referrers) {
            if ((call != null) && call.checkIfRemoteUserIsSame(name)) {
                return call;
            }
        }

        return null;
    }

    private boolean isSameCall(ImsCall call1, ImsCall call2) {
        if ((call1 == null) || (call2 == null)) {
            return false;
        }

        return call1.equalsTo(call2);
    }
}
