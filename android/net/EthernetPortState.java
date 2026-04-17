/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.annotation.Nullable;
import android.net.EthernetManager.InterfaceState;
import android.net.EthernetManager.Role;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A class representing information of an ethernet interface.
 * EthernetPortState is mainly used in network callbacks.
 * @hide
 */
public final class EthernetPortState implements Parcelable {
    // Role of this ethernet interface, one of {@link EthernetManager.ROLE_SERVER},
    // {@link EthernetManager.ROLE_CLIENT}, or {@link EthernetManager.ROLE_NONE}.
    @Role
    private final int mRole;

    // State of this ethernet interface, one of {@link EthernetManager.STATE_LINK_UP},
    // {@link EthernetManager.STATE_LINK_DOWN}, or {@link EthernetManager.STATE_ABSENT}.
    @InterfaceState
    private final int mState;

    // Configuration of this ethernet interface.
    private final EthernetConfiguration mConfig;

    // Name of this ethernet interface.
    private final String mInterfaceName;

    // MAC address of this ethernet interface.
    private final MacAddress mMacAddress;

    // Interface index of this ethernet interface.
    private final int mInterfaceIndex;

    public EthernetPortState(String interfaceName, MacAddress macAddress,
            int interfaceIndex, EthernetConfiguration config, @Role int role,
            @InterfaceState int state) {
        if (role != EthernetManager.ROLE_CLIENT && role != EthernetManager.ROLE_SERVER
                && role != EthernetManager.ROLE_NONE) {
            throw new IllegalArgumentException("Interface role is not valid, should be one of "
                    + "{EthernetManager.ROLE_SERVER, EthernetManager.ROLE_CLIENT, "
                    + "EthernetManager.ROLE_NONE}.");
        }
        mRole = role;

        if (state != EthernetManager.STATE_ABSENT && state != EthernetManager.STATE_LINK_DOWN
                && state != EthernetManager.STATE_LINK_UP) {
            throw new IllegalArgumentException("Interface state is not valid, should be one of "
                    + "{EthernetManager.STATE_ABSENT, EthernetManager.STATE_LINK_DOWN, "
                    + "EthernetManager.STATE_LINK_UP}.");
        }
        mState = state;

        if (TextUtils.isEmpty(interfaceName) || macAddress == null || interfaceIndex <= 0) {
            throw new IllegalArgumentException("All of interface name, MAC address and interface"
                    + " index need to be valid.");
        }
        mInterfaceName = interfaceName;
        mMacAddress = macAddress;
        mInterfaceIndex = interfaceIndex;

        Objects.requireNonNull(config);
        mConfig = config;
    }

    /**
     * Returns role of this ethernet interface, one of {@link EthernetManager.ROLE_SERVER},
     * {@link EthernetManager.ROLE_CLIENT}, or {@link EthernetManager.ROLE_NONE}.
     */
    @Role
    public int getRole() {
        return mRole;
    }


    /**
     * Returns state of this ethernet interface, one of {@link EthernetManager.STATE_LINK_UP},
     * {@link EthernetManager.STATE_LINK_DOWN}, or {@link EthernetManager.STATE_ABSENT}.
     */
    @InterfaceState
    public int getState() {
        return mState;
    }

    /**
     * Returns the static configuration regarding meteredness. Note that this meteredness is only
     * configured meteredness, while real meteredness state is only retrievable via
     * {@link ConnectivityManager}.
     */
    public boolean getMeteredConfiguration() {
        return mConfig.getNetworkCapabilities()
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    /** Returns the name of this ethernet interface */
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /** Returns the MAC address of this ethernet interface */
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    /** Returns the interface index of this ethernet interface */
    public int getInterfaceIndex() {
        return mInterfaceIndex;
    }

    /** Returns the configuration of this ethernet interface */
    public EthernetConfiguration getConfiguration() {
        return mConfig;
    }

    /** Returns a NetworkSpecifier that is guaranteed to match with this ethernet interface. */
    public NetworkSpecifier getNetworkSpecifier() {
        return new EthernetNetworkSpecifier(mInterfaceName, mMacAddress);
    }

    @Override
    public String toString() {
        return  "Role:" + mRole
                + ", State:" + mState
                + ", Configurations: " + mConfig
                + ", Interface name:" + mInterfaceName
                + ", MAC address:" + Objects.toString(mMacAddress)
                + ", Interface index:" + mInterfaceIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRole, mState, mConfig,
                mInterfaceName, mMacAddress, mInterfaceIndex);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof EthernetPortState)) {
            return false;
        }

        final EthernetPortState other = (EthernetPortState) o;
        return (this.mRole == other.mRole)
                && (this.mState == other.mState)
                && Objects.equals(this.mConfig, other.mConfig)
                && TextUtils.equals(this.mInterfaceName, other.mInterfaceName)
                && Objects.equals(this.mMacAddress, other.mMacAddress)
                && (this.mInterfaceIndex == other.mInterfaceIndex);
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRole);
        dest.writeInt(mState);
        dest.writeParcelable(mConfig, flags);
        dest.writeString(mInterfaceName);
        dest.writeParcelable(mMacAddress, flags);
        dest.writeInt(mInterfaceIndex);
    }

    /** Implement the Parcelable interface */
    public static final Creator<EthernetPortState> CREATOR =
            new Creator<EthernetPortState>() {
                @Override
                public EthernetPortState createFromParcel(Parcel in) {
                    int role = in.readInt();
                    int state = in.readInt();
                    EthernetConfiguration config = in.readParcelable(
                            EthernetConfiguration.class.getClassLoader());
                    String ifname = in.readString();
                    MacAddress hwaddr = in.readParcelable(
                            MacAddress.class.getClassLoader());
                    int index = in.readInt();
                    return new EthernetPortState(
                            ifname, hwaddr, index, config, role, state);
                }

                @Override
                public EthernetPortState[] newArray(int size) {
                    return new EthernetPortState[size];
                }
            };

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }
}
