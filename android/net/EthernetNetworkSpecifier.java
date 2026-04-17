/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A {@link NetworkSpecifier} used to identify ethernet interfaces. Interfaces can be identified
 * by matching either interface name and/or MAC address.
 */
public final class EthernetNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    /**
     * Name of the network interface, or null.
     */
    @Nullable
    private final String mInterfaceName;

    /**
     * MAC address of the network interface, or null.
     */
    @Nullable
    private final MacAddress mMacAddress;

    /**
     * Create a new EthernetNetworkSpecifier with an interface name.
     * @param interfaceName Name of the ethernet interface the specifier refers to.
     */
    public EthernetNetworkSpecifier(@NonNull String interfaceName) {
        if (TextUtils.isEmpty(interfaceName)) {
            throw new IllegalArgumentException("Interface name cannot be null");
        }
        mInterfaceName = interfaceName;
        mMacAddress = null;
    }

    /**
     * Create a new EthernetNetworkSpecifier. Either interface name or MAC address needs to be
     * valid.
     * @param interfaceName Name of the ethernet interface the specifier refers to, or null.
     * @param macAddress MAC address of the ethernet interface the specifier refers to, or null.
     * @hide
     */
    public EthernetNetworkSpecifier(@Nullable String interfaceName,
            @Nullable MacAddress macAddress) {
        if (TextUtils.isEmpty(interfaceName) && macAddress == null) {
            throw new IllegalArgumentException("Either interface name or MAC address needs to be"
                    + " valid.");
        }
        mInterfaceName = interfaceName;
        mMacAddress = macAddress;
    }

    /**
     * Get the name of the ethernet interface the specifier refers to, or null.
     */
    @Nullable
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Get the MAC address of the ethernet interface the specifier refers to, or null.
     * @hide
     */
    @Nullable
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    /**
     * Check if this specifier can be satisfied by another specifier by interface name or/and MAC
     * address.
     * Note that because ethernet Networks always set an EthernetNetworkSpecifier that includes both
     * interface name and MAC address, the match is asymmetrical.
     * @hide
     */
    @Override
    public boolean canBeSatisfiedBy(@Nullable NetworkSpecifier other) {
        if (!(other instanceof EthernetNetworkSpecifier)) return false;
        final EthernetNetworkSpecifier rhs = (EthernetNetworkSpecifier) other;
        // If interface name is specified, match interface name.
        if (mInterfaceName != null && !mInterfaceName.equals(rhs.mInterfaceName)) {
            return false;
        }
        // If MAC address is specified, match MAC address.
        if (mMacAddress != null && !mMacAddress.equals(rhs.mMacAddress)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof EthernetNetworkSpecifier)) return false;
        EthernetNetworkSpecifier rhs = (EthernetNetworkSpecifier) o;
        return TextUtils.equals(mInterfaceName, rhs.mInterfaceName)
                && Objects.equals(mMacAddress, rhs.mMacAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMacAddress, mInterfaceName);
    }

    @Override
    public String toString() {
        return "EthernetNetworkSpecifier ( interface name: " + mInterfaceName
                + ", MAC address: " + Objects.toString(mMacAddress) + ")";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInterfaceName);
        dest.writeParcelable(mMacAddress, flags);
    }

    public static final @NonNull Parcelable.Creator<EthernetNetworkSpecifier> CREATOR =
            new Parcelable.Creator<EthernetNetworkSpecifier>() {
        public EthernetNetworkSpecifier createFromParcel(Parcel in) {
                    final String ifname = in.readString();
                    final MacAddress addr = in.readParcelable(MacAddress.class.getClassLoader());
                    return new EthernetNetworkSpecifier(ifname, addr);
        }
        public EthernetNetworkSpecifier[] newArray(int size) {
            return new EthernetNetworkSpecifier[size];
        }
    };
}
