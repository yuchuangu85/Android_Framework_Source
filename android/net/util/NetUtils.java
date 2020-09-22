/*
 * Copyright 2019 The Android Open Source Project
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

package android.net.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collection;

/**
 * Collection of network common utilities.
 * @hide
 */
public final class NetUtils {

    /**
     * Check if IP address type is consistent between two InetAddress.
     * @return true if both are the same type. False otherwise.
     */
    public static boolean addressTypeMatches(@NonNull InetAddress left,
            @NonNull InetAddress right) {
        return (((left instanceof Inet4Address) && (right instanceof Inet4Address))
                || ((left instanceof Inet6Address) && (right instanceof Inet6Address)));
    }

    /**
     * Find the route from a Collection of routes that best matches a given address.
     * May return null if no routes are applicable.
     * @param routes a Collection of RouteInfos to chose from
     * @param dest the InetAddress your trying to get to
     * @return the RouteInfo from the Collection that best fits the given address
     */
    @Nullable
    public static RouteInfo selectBestRoute(@Nullable Collection<RouteInfo> routes,
            @Nullable InetAddress dest) {
        if ((routes == null) || (dest == null)) return null;

        RouteInfo bestRoute = null;
        // pick a longest prefix match under same address type
        for (RouteInfo route : routes) {
            if (addressTypeMatches(route.getDestination().getAddress(), dest)) {
                if ((bestRoute != null)
                        && (bestRoute.getDestination().getPrefixLength()
                        >= route.getDestination().getPrefixLength())) {
                    continue;
                }
                if (route.matches(dest)) bestRoute = route;
            }
        }
        return bestRoute;
    }
}
