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

package com.android.server.wifi.hotspot2;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.Clock;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache for storing ANQP data.  This is simply a data cache, all the logic related to
 * ANQP data query will be handled elsewhere (e.g. the consumer of the cache).
 */
public class AnqpCache {
    @VisibleForTesting
    public static final long CACHE_SWEEP_INTERVAL_MILLISECONDS = 60000L;

    private long mLastSweep;
    private Clock mClock;

    private final Map<ANQPNetworkKey, ANQPData> mANQPCache;

    public AnqpCache(Clock clock) {
        mClock = clock;
        mANQPCache = new HashMap<>();
        mLastSweep = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Add an ANQP entry associated with the given key.
     *
     * @param key The key that's associated with the entry
     * @param anqpElements The ANQP elements from the AP
     */
    public void addEntry(ANQPNetworkKey key,
            Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        ANQPData data = new ANQPData(mClock, anqpElements);
        mANQPCache.put(key, data);
    }

    /**
     * Get the ANQP data associated with the given AP.
     *
     * @param key The key that's associated with the entry
     * @return {@link ANQPData}
     */
    public ANQPData getEntry(ANQPNetworkKey key) {
        return mANQPCache.get(key);
    }

    /**
     * Go through the cache to remove any expired entries.
     */
    public void sweep() {
        long now = mClock.getElapsedSinceBootMillis();
        // Check if it is time to perform the sweep.
        if (now < mLastSweep + CACHE_SWEEP_INTERVAL_MILLISECONDS) {
            return;
        }

        // Get all expired keys.
        List<ANQPNetworkKey> expiredKeys = new ArrayList<>();
        for (Map.Entry<ANQPNetworkKey, ANQPData> entry : mANQPCache.entrySet()) {
            if (entry.getValue().expired(now)) {
                expiredKeys.add(entry.getKey());
            }
        }

        // Remove all expired entries.
        for (ANQPNetworkKey key : expiredKeys) {
            mANQPCache.remove(key);
        }
        mLastSweep = now;
    }

    public void dump(PrintWriter out) {
        out.println("Last sweep " + Utils.toHMS(mClock.getElapsedSinceBootMillis() - mLastSweep)
                + " ago.");
        for (Map.Entry<ANQPNetworkKey, ANQPData> entry : mANQPCache.entrySet()) {
            out.println(entry.getKey() + ": " + entry.getValue());
        }
    }
}
