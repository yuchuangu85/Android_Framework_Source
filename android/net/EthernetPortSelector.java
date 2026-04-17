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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A {@link EthernetPortSelector} used to identify ethernet ports. Ports can be identified by
 * matching either interface name or MAC address only.
 * This selector is different from {@link EthernetNetworkSpecifier} in the way that
 * {@link EthernetPortSelector} is used to identify an ethernet network which might include
 * network specific configuration, such as authentication etc,. While {@link EthernetPortSelector}
 * is solely used to identify an ethernet port which may or may not be connected.
 * @hide
 */
public final class EthernetPortSelector implements Parcelable {
    /**
     * Name of the network port, or null.
     */
    @Nullable
    private final String mInterfaceName;

    /**
     * MAC address of the network port, or null.
     */
    @Nullable
    private final MacAddress mMacAddress;

    /**
     * Create a new EthernetPortSelector by interface name.
     * @param interfaceName Name of the ethernet interface the specifier refers to.
     * @hide
     */
    public EthernetPortSelector(@NonNull String interfaceName) {
        // Interface name and MAC address cannot be both invalid or both set at the same time.
        if (TextUtils.isEmpty(interfaceName)) {
            throw new IllegalArgumentException("Interface name cannot be empty.");
        }
        mInterfaceName = interfaceName;
        mMacAddress = null;
    }

    /**
     * Create a new EthernetPortSelector by MAC address.
     * @param macAddress MAC address of the ethernet interface the specifier refers to, or null.
     * @hide
     */
    public EthernetPortSelector(@NonNull MacAddress macAddress) {
        // Interface name and MAC address cannot be both invalid or both set at the same time.
        if (macAddress == null) {
            throw new IllegalArgumentException("MAC address cannot be null.");
        }
        mInterfaceName = null;
        mMacAddress = macAddress;
    }

    /**
     * Get the name of the ethernet port the selector refers to, or null.
     * @hide
     */
    @Nullable
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Get the MAC address of the ethernet port the selector refers to, or null.
     * @hide
     */
    @Nullable
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return (o instanceof EthernetPortSelector rhs)
                && TextUtils.equals(mInterfaceName, rhs.mInterfaceName)
                && Objects.equals(mMacAddress, rhs.mMacAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMacAddress, mInterfaceName);
    }

    @Override
    public String toString() {
        String selectorInfo = mMacAddress != null ? mMacAddress.toString() : mInterfaceName;
        return "EthernetPortSelector (" + selectorInfo + ")";
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInterfaceName);
        dest.writeParcelable(mMacAddress, flags);
    }

    /** @hide */
    public static final @NonNull Parcelable.Creator<EthernetPortSelector> CREATOR =
            new Parcelable.Creator<>() {
                public EthernetPortSelector createFromParcel(Parcel in) {
                    final String ifname = in.readString();
                    final MacAddress addr = in.readParcelable(MacAddress.class.getClassLoader());
                    return ifname == null
                            ? new EthernetPortSelector(addr) : new EthernetPortSelector(ifname);

                }

                public EthernetPortSelector[] newArray(int size) {
                    return new EthernetPortSelector[size];
                }
            };
}
