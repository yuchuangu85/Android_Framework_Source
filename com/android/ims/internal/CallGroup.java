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

package com.android.ims.internal;

import java.util.ArrayList;

/**
 * Wrapper class which has an ICallGroup interface.
 *
 * @hide
 */
public class CallGroup {
    private final ICallGroup mCallGroup;

    public CallGroup(ICallGroup callGroup) {
        mCallGroup = callGroup;
    }

    public ICall getNeutralReferrer() {
        if (mCallGroup == null) {
            return null;
        }

        return mCallGroup.getNeutralReferrer();
    }

    public ICall getOwner() {
        if (mCallGroup == null) {
            return null;
        }

        return mCallGroup.getOwner();
    }

    public ICall getReferrer(String name) {
        if (mCallGroup == null) {
            return null;
        }

        return mCallGroup.getReferrer(name);
    }

    public ArrayList<ICall> getReferrers() {
        if (mCallGroup == null) {
            return null;
        }

        return mCallGroup.getReferrers();
    }

    public boolean hasReferrer() {
        if (mCallGroup == null) {
            return false;
        }

        return mCallGroup.hasReferrer();
    }

    public boolean isOwner(ICall call) {
        if (mCallGroup == null) {
            return false;
        }

        return mCallGroup.isOwner(call);
    }

    public boolean isReferrer(ICall call) {
        if (mCallGroup == null) {
            return false;
        }

        return mCallGroup.isReferrer(call);
    }

    public void addReferrer(ICall call) {
        if (mCallGroup == null) {
            return;
        }

        mCallGroup.addReferrer(call);
    }

    public void removeReferrer(ICall call) {
        if (mCallGroup == null) {
            return;
        }

        mCallGroup.removeReferrer(call);
    }

    public void setNeutralReferrer(ICall call) {
        if (mCallGroup == null) {
            return;
        }

        mCallGroup.setNeutralReferrer(call);
    }

    public void setOwner(ICall call) {
        if (mCallGroup == null) {
            return;
        }

        mCallGroup.setOwner(call);
    }
}
