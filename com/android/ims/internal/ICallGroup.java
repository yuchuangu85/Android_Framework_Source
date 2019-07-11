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
 * Provides the interface to manage all calls which are established
 * hereafter the initial 1-to-1 call is established.
 * It's for providing the dummy calls which are disconnected with the IMS network after
 * merged or extended to the conference.
 *
 * @hide
 */
public interface ICallGroup {
    /**
     * Gets the neutral referrer call object of this group.
     *
     * @return the neutral referrer call object
     */
    public ICall getNeutralReferrer();

    /**
     * Gets the owner call object of this group.
     *
     * @return the owner call object
     */
    public ICall getOwner();

    /**
     * Gets the referrer call object which is equal to the specified name.
     *
     * @return the referrer call object
     */
    public ICall getReferrer(String name);

    /**
     * Gets the referrer call objects of this group.
     *
     * @return the referrer call objects
     */
    public ArrayList<ICall> getReferrers();

    /**
     * Checks if the call group has a referrer.
     *
     * @return true if the call group has a referrer; false otherwise.
     */
    public boolean hasReferrer();

    /**
     * Checks if the specified call object is owner of this group.
     *
     * @param call the call object to be checked if it is an owner of this group
     * @return true if the specified call object is an owner; false otherwise
     */
    public boolean isOwner(ICall call);

    /**
     * Checks if the specified call object is a referrer of this group.
     *
     * @param call the call object to be checked if it is a referrer of this group
     * @return true if the specified call object is a referrer; false otherwise
     */
    public boolean isReferrer(ICall call);

    /**
     * Adds the call object to this call group.
     *
     * @param call the call object to be added to this group
     */
    public void addReferrer(ICall call);

    /**
     * Removes the call object from this call group.
     *
     * @param call the call object to be removed from this group
     */
    public void removeReferrer(ICall call);

    /**
     * Sets the referrer call object in the neutral state while the operation is in progress.
     *
     * @param call the call object to be added to this group if the operation is succeeded.
     */
    public void setNeutralReferrer(ICall call);

    /**
     * Sets the call object as the owner of this call group.
     * If the owner call object is already present, this method overwrites the existing owner
     * call object.
     *
     * @param call the call object to be added to this group as owner
     */
    public void setOwner(ICall call);
}
