/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.net.module.util.CollectionUtils.toIntArray;
import static com.android.net.module.util.NetworkCapabilitiesUtils.deduceTransportTypeForLegacyNetworkType;

import android.annotation.NonNull;
import android.service.NetworkIdentitySetProto;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.VisibleForTesting;

import com.android.net.module.util.BitUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Identity of a {@code iface}, defined by the set of {@link NetworkIdentity}
 * active on that interface.
 *
 * @hide
 */
public class NetworkIdentitySet extends HashSet<NetworkIdentity> {
    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADD_ROAMING = 2;
    private static final int VERSION_ADD_NETWORK_ID = 3;
    private static final int VERSION_ADD_METERED = 4;
    private static final int VERSION_ADD_DEFAULT_NETWORK = 5;
    private static final int VERSION_ADD_OEM_MANAGED_NETWORK = 6;
    @VisibleForTesting
    public static final int VERSION_ADD_SUB_ID = 7;
    @VisibleForTesting
    public static final int VERSION_ADD_TRANSPORT_TYPES = 8;

    /**
     * Construct a {@link NetworkIdentitySet} object.
     */
    public NetworkIdentitySet() {
        super();
    }

    /** @hide */
    public NetworkIdentitySet(@NonNull Set<NetworkIdentity> ident) {
        super(ident);
    }

    /** @hide */
    public NetworkIdentitySet(DataInput in) throws IOException {
        final int version = in.readInt();
        final int size = in.readInt();
        for (int i = 0; i < size; i++) {
            if (version <= VERSION_INIT) {
                final int ignored = in.readInt();
            }
            final int type = in.readInt();
            final int ratType = in.readInt();
            final String subscriberId = readOptionalString(in);
            final String networkId;
            if (version >= VERSION_ADD_NETWORK_ID) {
                networkId = readOptionalString(in);
            } else {
                networkId = null;
            }
            final boolean roaming;
            if (version >= VERSION_ADD_ROAMING) {
                roaming = in.readBoolean();
            } else {
                roaming = false;
            }

            final boolean metered;
            if (version >= VERSION_ADD_METERED) {
                metered = in.readBoolean();
            } else {
                // If this is the old data and the type is mobile, treat it as metered. (Note that
                // if this is a mobile network, TYPE_MOBILE is the only possible type that could be
                // used.)
                metered = (type == TYPE_MOBILE);
            }

            final boolean defaultNetwork;
            if (version >= VERSION_ADD_DEFAULT_NETWORK) {
                defaultNetwork = in.readBoolean();
            } else {
                defaultNetwork = true;
            }

            final int oemNetCapabilities;
            if (version >= VERSION_ADD_OEM_MANAGED_NETWORK) {
                oemNetCapabilities = in.readInt();
            } else {
                oemNetCapabilities = NetworkIdentity.OEM_NONE;
            }

            final int subId;
            if (version >= VERSION_ADD_SUB_ID) {
                subId = in.readInt();
            } else {
                subId = INVALID_SUBSCRIPTION_ID;
            }

            final long transportTypesBits;
            if (version >= VERSION_ADD_TRANSPORT_TYPES) {
                transportTypesBits = in.readLong();
            } else {
                final int deducedTransport = deduceTransportTypeForLegacyNetworkType(type);
                if (deducedTransport != -1) {
                    transportTypesBits = BitUtils.packBits(new int[]{ deducedTransport });
                } else {
                    // Ignore legacy or unknown types. This is fine since this means
                    // the legacy data cannot be queried by transport types,
                    // which is not even supported now.
                    transportTypesBits = 0;
                }
            }

            add(new NetworkIdentity(type, ratType, subscriberId, networkId, roaming, metered,
                    defaultNetwork, oemNetCapabilities, subId, transportTypesBits));
        }
    }

    /**
     * Method to serialize this object into a {@code DataOutput}.
     * @hide
     */
    public void writeToStream(DataOutput out, boolean storeTransportTypes) throws IOException {
        if (storeTransportTypes) {
            out.writeInt(VERSION_ADD_TRANSPORT_TYPES);
        } else {
            out.writeInt(VERSION_ADD_SUB_ID);
        }
        out.writeInt(size());
        for (NetworkIdentity ident : this) {
            out.writeInt(ident.getType());
            out.writeInt(ident.getRatType());
            writeOptionalString(out, ident.getSubscriberId());
            writeOptionalString(out, ident.getWifiNetworkKey());
            out.writeBoolean(ident.isRoaming());
            out.writeBoolean(ident.isMetered());
            out.writeBoolean(ident.isDefaultNetwork());
            out.writeInt(ident.getOemManaged());
            out.writeInt(ident.getSubId());
            if (storeTransportTypes) {
                out.writeLong(BitUtils.packBits(toIntArray(ident.getTransportTypes())));
            }
        }
    }

    /**
     * @return whether any {@link NetworkIdentity} in this set is considered metered.
     * @hide
     */
    public boolean isAnyMemberMetered() {
        if (isEmpty()) {
            return false;
        }
        for (NetworkIdentity ident : this) {
            if (ident.isMetered()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether any {@link NetworkIdentity} in this set is considered roaming.
     * @hide
     */
    public boolean isAnyMemberRoaming() {
        if (isEmpty()) {
            return false;
        }
        for (NetworkIdentity ident : this) {
            if (ident.isRoaming()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether any {@link NetworkIdentity} in this set is considered on the default
     *         network.
     * @hide
     */
    public boolean areAllMembersOnDefaultNetwork() {
        if (isEmpty()) {
            return true;
        }
        for (NetworkIdentity ident : this) {
            if (!ident.isDefaultNetwork()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes an optional string to a {@link DataOutput} stream.
     *
     * @hide
     */
    @VisibleForTesting
    public static void writeOptionalString(DataOutput out, String value) throws IOException {
        if (value != null) {
            out.writeByte(1);
            out.writeUTF(value);
        } else {
            out.writeByte(0);
        }
    }

    private static String readOptionalString(DataInput in) throws IOException {
        if (in.readByte() != 0) {
            return in.readUTF();
        } else {
            return null;
        }
    }

    public static int compare(@NonNull NetworkIdentitySet left, @NonNull NetworkIdentitySet right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        if (left.isEmpty() && right.isEmpty()) return 0;
        if (left.isEmpty()) return -1;
        if (right.isEmpty()) return 1;

        final NetworkIdentity leftIdent = left.iterator().next();
        final NetworkIdentity rightIdent = right.iterator().next();
        return NetworkIdentity.compare(leftIdent, rightIdent);
    }

    /**
     * Method to dump this object into proto debug file.
     * @hide
     */
    public void dumpDebug(ProtoOutputStream proto, long tag) {
        final long start = proto.start(tag);

        for (NetworkIdentity ident : this) {
            ident.dumpDebug(proto, NetworkIdentitySetProto.IDENTITIES);
        }

        proto.end(start);
    }
}
