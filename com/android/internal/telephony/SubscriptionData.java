/*
 * Copyright (c) 2010-2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony;

import java.util.Arrays;

/**
 * Class holding a list of subscriptions
 */
public class SubscriptionData {
    public Subscription [] subscription;

    public SubscriptionData(int numSub) {
        subscription = new Subscription[numSub];
        for (int i = 0; i < numSub; i++) {
            subscription[i] = new Subscription();
        }
    }

    public int getLength() {
        if (subscription != null) {
            return subscription.length;
        }
        return 0;
    }

    public SubscriptionData copyFrom(SubscriptionData from) {
        if (from != null) {
            subscription = new Subscription[from.getLength()];
            for (int i = 0; i < from.getLength(); i++) {
                subscription[i] = new Subscription();
                subscription[i].copyFrom(from.subscription[i]);
            }
        }
        return this;
    }

    public String getIccId() {
        if (subscription.length > 0 && subscription[0] != null) {
            return subscription[0].iccId;
        }
        return null;
    }

    public boolean hasSubscription(Subscription sub){
        for (int i = 0; i < subscription.length; i++) {
            if (subscription[i].isSame(sub)) {
                return true;
            }
        }
        return false;
    }

    public Subscription getSubscription(Subscription sub){
        for (int i = 0; i < subscription.length; i++) {
            if (subscription[i].isSame(sub)) {
                return subscription[i];
            }
        }
        return null;
    }

    public String toString() {
        return Arrays.toString(subscription);
    }
}
