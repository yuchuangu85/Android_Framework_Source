/*
 * Copyright (C) 2006 The Android Open Source Project
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
package com.android.internal.telephony.dataconnection;

import android.content.Context;
import android.net.NetworkConfig;
import android.net.NetworkRequest;
import android.telephony.data.ApnSetting.ApnType;

import java.util.HashMap;

public class DcRequest implements Comparable<DcRequest> {
    private static final String LOG_TAG = "DcRequest";

    public final NetworkRequest networkRequest;
    public final int priority;
    public final @ApnType int apnType;

    public DcRequest(NetworkRequest nr, Context context) {
        initApnPriorities(context);
        networkRequest = nr;
        apnType = ApnContext.getApnTypeFromNetworkRequest(networkRequest);
        priority = priorityForApnType(apnType);
    }

    public String toString() {
        return networkRequest.toString() + ", priority=" + priority + ", apnType=" + apnType;
    }

    public int hashCode() {
        return networkRequest.hashCode();
    }

    public boolean equals(Object o) {
        if (o instanceof DcRequest) {
            return networkRequest.equals(((DcRequest)o).networkRequest);
        }
        return false;
    }

    public int compareTo(DcRequest o) {
        return o.priority - priority;
    }

    private static final HashMap<Integer, Integer> sApnPriorityMap =
            new HashMap<Integer, Integer>();

    private void initApnPriorities(Context context) {
        synchronized (sApnPriorityMap) {
            if (sApnPriorityMap.isEmpty()) {
                String[] networkConfigStrings = context.getResources().getStringArray(
                        com.android.internal.R.array.networkAttributes);
                for (String networkConfigString : networkConfigStrings) {
                    NetworkConfig networkConfig = new NetworkConfig(networkConfigString);
                    final int apnType = ApnContext.getApnTypeFromNetworkType(networkConfig.type);
                    sApnPriorityMap.put(apnType, networkConfig.priority);
                }
            }
        }
    }

    private int priorityForApnType(int apnType) {
        Integer priority = sApnPriorityMap.get(apnType);
        return (priority != null ? priority.intValue() : 0);
    }
}
