/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.nsd;

import static android.permission.flags.Flags.FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.net.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Encapsulates parameters for {@link NsdManager#discoverServices}.
 */
@FlaggedApi(Flags.FLAG_NSD_SUBTYPES_SUPPORT_ENABLED)
public final class DiscoveryRequest implements Parcelable {

    /**
     * Flags for {@link DiscoveryRequest#getFlags()}.
     *
     * @hide
     */
    @LongDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_NO_PICKER,
            FLAG_SHOW_PICKER,
            FLAG_USER_APPROVED_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DiscoveryFlags {}

    /**
     * Indicates that a UI service picker should never be shown to the user for this request.
     *
     * <p>Starting from target SDK {@link android.os.Build.VERSION_CODES#CINNAMON_BUN}, if the
     * caller does not have the {@link android.Manifest.permission#ACCESS_LOCAL_NETWORK}
     * permission and does not specify {@link #FLAG_USER_APPROVED_ONLY}, this will cause the request
     * to fail with
     * {@link android.net.nsd.NsdManager.DiscoveryListener#onStartDiscoveryFailed(String, int)} and
     * {@link NsdManager#FAILURE_PERMISSION_DENIED}.
     *
     * <p>If neither {@link #FLAG_NO_PICKER} nor {@link #FLAG_SHOW_PICKER} is set, the picker will
     * be shown if the app does not have {@link android.Manifest.permission#ACCESS_LOCAL_NETWORK}
     * permission.
     */
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    @DiscoveryFlags
    public static final long FLAG_NO_PICKER = 1L << 0;

    /**
     * Indicates that a UI service picker must be shown to the user for this request.
     *
     * <p>If this flag is set, the user will be prompted to choose a service to be discovered among
     * all services matching the discovery service type and discovery filters.
     * {@link android.net.nsd.NsdManager.DiscoveryListener#onServiceFound(NsdServiceInfo)} will be
     * called at most once, and discovery will stop if a service is selected or the user cancels
     * the request. {@link android.Manifest.permission#ACCESS_LOCAL_NETWORK} permission is *not*
     * necessary when discovering with this flag.
     *
     * <p>Once a service has been selected by the user in the UI picker,
     * {@link NsdManager#resolveService(NsdServiceInfo, java.util.concurrent.Executor,
     * NsdManager.ResolveListener)} or {@link NsdManager#registerServiceInfoCallback(NsdServiceInfo,
     * Executor, NsdManager.ServiceInfoCallback)} can be called for that service without requiring
     * the {@link android.Manifest.permission#ACCESS_LOCAL_NETWORK} permission or any further UI
     * being displayed.
     *
     * <p>If neither {@link #FLAG_NO_PICKER} nor {@link #FLAG_SHOW_PICKER} is set, the picker will
     * be shown if the app does not have {@link android.Manifest.permission#ACCESS_LOCAL_NETWORK}
     * permission.
     *
     * <p>This flag cannot be combined with {@link #FLAG_NO_PICKER} or
     * {@link #FLAG_USER_APPROVED_ONLY}.
     */
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    @DiscoveryFlags
    public static final long FLAG_SHOW_PICKER = 1L << 1;

    /**
     * Indicates that only services previously approved by the user must be discovered.
     *
     * <p>If this flag is set, only services that have been selected by the user in previous
     * requests that used the service picker UI will be discovered.
     * {@link android.Manifest.permission#ACCESS_LOCAL_NETWORK} permission is *not* necessary when
     * discovering with this flag.
     *
     * <p>This flag implies {@link #FLAG_NO_PICKER}, and it cannot be combined with
     * {@link #FLAG_SHOW_PICKER}.
     *
     * @see #FLAG_SHOW_PICKER
     */
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    @DiscoveryFlags
    public static final long FLAG_USER_APPROVED_ONLY = 1L << 2;

    private final int mProtocolType;

    @NonNull
    private final String mServiceType;

    @Nullable
    private final String mSubtype;

    @Nullable
    private final Network mNetwork;

    private final long mFlags;

    @Nullable
    private final PatternMatcher mServiceNameFilter;

    @NonNull
    private final ArrayMap<String, PatternMatcher> mAttributeFilters;

    @Nullable
    private final String mDisplayNameAttribute;

    @NonNull
    public static final Creator<DiscoveryRequest> CREATOR =
            new Creator<>() {
                @Override
                public DiscoveryRequest createFromParcel(Parcel in) {
                    int protocolType = in.readInt();
                    String serviceType = in.readString();
                    String subtype = in.readString();
                    Network network =
                            in.readParcelable(Network.class.getClassLoader(), Network.class);
                    long flags = in.readLong();
                    PatternMatcher serviceNameFilter = in.readParcelable(
                            PatternMatcher.class.getClassLoader(), PatternMatcher.class);
                    ArrayMap<String, PatternMatcher> attributeFilters =
                            in.createTypedArrayMap(PatternMatcher.CREATOR);
                    String displayNameAttribute = in.readString();
                    return new DiscoveryRequest(protocolType, serviceType, subtype, network, flags,
                            serviceNameFilter, attributeFilters, displayNameAttribute);
                }

                @Override
                public DiscoveryRequest[] newArray(int size) {
                    return new DiscoveryRequest[size];
                }
            };

    private DiscoveryRequest(int protocolType, @NonNull String serviceType,
            @Nullable String subtype, @Nullable Network network, @DiscoveryFlags long flags,
            @Nullable PatternMatcher serviceNameFilter,
            @NonNull ArrayMap<String, PatternMatcher> attributeFilters,
            @Nullable String displayNameAttribute) {
        mProtocolType = protocolType;
        mServiceType = serviceType;
        mSubtype = subtype;
        mNetwork = network;
        mFlags = flags;
        mServiceNameFilter = serviceNameFilter;
        mAttributeFilters = attributeFilters;
        mDisplayNameAttribute = displayNameAttribute;
    }

    /**
     * Returns the service type in format of dot-joint string of two labels.
     *
     * For example, "_ipp._tcp" for internet printer and "_matter._tcp" for <a
     * href="https://csa-iot.org/all-solutions/matter">Matter</a> operational device.
     */
    @NonNull
    public String getServiceType() {
        return mServiceType;
    }

    /**
     * Returns the subtype without the trailing "._sub" label or {@code null} if no subtype is
     * specified.
     *
     * For example, the return value will be "_printer" for subtype "_printer._sub".
     */
    @Nullable
    public String getSubtype() {
        return mSubtype;
    }

    /**
     * Returns the service discovery protocol.
     *
     * @hide
     */
    public int getProtocolType() {
        return mProtocolType;
    }

    /**
     * Returns the {@link Network} on which the query should be sent or {@code null} if no
     * network is specified.
     */
    @Nullable
    public Network getNetwork() {
        return mNetwork;
    }

    /**
     * Returns the discovery flags.
     */
    @DiscoveryFlags
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    public long getFlags() {
        return mFlags;
    }

    /**
     * Returns the filter to apply to service names as per {@link NsdServiceInfo#getServiceName()}.
     *
     * <p>As per RFC6335 5.1., service names must be only US-ASCII letters, digits and hyphens,
     * and matching is not case-sensitive.
     *
     * <p>If null, services are not filtered by name.
     */
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    @Nullable
    public PatternMatcher getServiceNameFilter() {
        return mServiceNameFilter;
    }

    /**
     * Returns the filters to apply to attributes as per {@link NsdServiceInfo#getAttributes()}.
     */
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    @NonNull
    public Map<String, PatternMatcher> getAttributeFilters() {
        return Collections.unmodifiableMap(mAttributeFilters);
    }

    /**
     * Return the attribute to use to display the service in system UI service picker.
     *
     * <p>Attributes are as per {@link NsdServiceInfo#getAttributes()}.
     *
     * <p>If null, the service name as per {@link NsdServiceInfo#getServiceName()} is used.
     *
     * @see #FLAG_SHOW_PICKER
     */
    @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
    @Nullable
    public String getDisplayNameAttribute() {
        return mDisplayNameAttribute;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DiscoveryRequest {")
            .append("protocolType: ").append(mProtocolType)
            .append(", serviceType: ").append(mServiceType);
        if (mSubtype != null) {
            sb.append(", subtype: ").append(mSubtype);
        }
        if (mNetwork != null) {
            sb.append(", network: ").append(mNetwork);
        }
        sb.append(", flags: 0x").append(Long.toHexString(mFlags));
        if (mServiceNameFilter != null) {
            sb.append(", serviceNameFilter: ").append(mServiceNameFilter);
        }
        if (!mAttributeFilters.isEmpty()) {
            sb.append(", attributeFilters: ").append(mAttributeFilters);
        }
        if (mDisplayNameAttribute != null) {
            sb.append(", displayNameAttribute: ").append(mDisplayNameAttribute);
        }
        return sb.append('}').toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof DiscoveryRequest)) {
            return false;
        } else {
            DiscoveryRequest otherRequest = (DiscoveryRequest) other;
            return mProtocolType == otherRequest.mProtocolType
                    && Objects.equals(mServiceType, otherRequest.mServiceType)
                    && Objects.equals(mSubtype, otherRequest.mSubtype)
                    && Objects.equals(mNetwork, otherRequest.mNetwork)
                    && mFlags == otherRequest.mFlags
                    && patternEquals(mServiceNameFilter, otherRequest.mServiceNameFilter)
                    && Objects.equals(mDisplayNameAttribute, otherRequest.mDisplayNameAttribute)
                    && mapEquals(mAttributeFilters, otherRequest.mAttributeFilters);
        }
    }

    private static boolean mapEquals(ArrayMap<String, PatternMatcher> a,
            ArrayMap<String, PatternMatcher> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!patternEquals(a.valueAt(i), b.get(a.keyAt(i)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean patternEquals(@Nullable PatternMatcher a, @Nullable PatternMatcher b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        // PatternMatcher does not have .equals, but its toString implementation maps 1:1 to its
        // contents.
        return a.toString().equals(b.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProtocolType, mServiceType, mSubtype, mNetwork, mFlags,
                mServiceNameFilter == null ? null : mServiceNameFilter.toString(),
                // Do not calculate a full hashcode for the attributeFilters map as it would likely
                // be more costly than the performance gain in hashmaps. It is OK for non-equal
                // objects to have the same hashcode.
                mAttributeFilters.size(),
                mDisplayNameAttribute);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mProtocolType);
        dest.writeString(mServiceType);
        dest.writeString(mSubtype);
        dest.writeParcelable(mNetwork, flags);
        dest.writeLong(mFlags);
        dest.writeParcelable(mServiceNameFilter, flags);
        dest.writeTypedArrayMap(mAttributeFilters, flags);
        dest.writeString(mDisplayNameAttribute);
    }

    /** The builder for creating new {@link DiscoveryRequest} objects. */
    public static final class Builder {
        private final int mProtocolType;

        @NonNull
        private String mServiceType;

        @Nullable
        private String mSubtype;

        @Nullable
        private Network mNetwork;

        private long mFlags;

        @Nullable
        private PatternMatcher mServiceNameFilter;

        @NonNull
        private ArrayMap<String, PatternMatcher> mAttributeFilters = new ArrayMap<>();

        @Nullable
        private String mDisplayNameAttribute;

        /**
         * Creates a new default {@link Builder} object with given service type.
         *
         * @throws IllegalArgumentException if {@code serviceType} is {@code null} or an empty
         * string
         */
        public Builder(@NonNull String serviceType) {
            this(NsdManager.PROTOCOL_DNS_SD, serviceType);
        }

        /** @hide */
        public Builder(int protocolType, @NonNull String serviceType) {
            NsdManager.checkProtocol(protocolType);
            mProtocolType = protocolType;
            setServiceType(serviceType);
        }

        /**
         * Sets the service type to be discovered or {@code null} if no services should be queried.
         *
         * The {@code serviceType} must be a dot-joint string of two labels. For example,
         * "_ipp._tcp" for internet printer. Additionally, the first label must start with
         * underscore ('_') and the second label must be either "_udp" or "_tcp". Otherwise, {@link
         * NsdManager#discoverServices} will fail with {@link NsdManager#FAILURE_BAD_PARAMETER}.
         *
         * @throws IllegalArgumentException if {@code serviceType} is {@code null} or an empty
         * string
         *
         * @hide
         */
        @NonNull
        public Builder setServiceType(@NonNull String serviceType) {
            if (TextUtils.isEmpty(serviceType)) {
                throw new IllegalArgumentException("Service type cannot be empty");
            }
            mServiceType = serviceType;
            return this;
        }

        /**
         * Sets the optional subtype of the services to be discovered.
         *
         * If a non-empty {@code subtype} is specified, it must start with underscore ('_') and
         * have the trailing "._sub" removed. Otherwise, {@link NsdManager#discoverServices} will
         * fail with {@link NsdManager#FAILURE_BAD_PARAMETER}. For example, {@code subtype} should
         * be "_printer" for DNS name "_printer._sub._http._tcp". In this case, only services with
         * this {@code subtype} will be queried, rather than all services of the base service type.
         *
         * Note that a non-empty service type must be specified with {@link #setServiceType} if a
         * non-empty subtype is specified by this method.
         */
        @NonNull
        public Builder setSubtype(@Nullable String subtype) {
            mSubtype = subtype;
            return this;
        }

        /**
         * Sets the {@link Network} on which the discovery queries should be sent.
         *
         * @param network the discovery network or {@code null} if the query should be sent on
         * all supported networks
         */
        @NonNull
        public Builder setNetwork(@Nullable Network network) {
            mNetwork = network;
            return this;
        }

        /**
         * Set all the discovery flags.
         *
         * @param flags A bitmask of flags that should be enabled, or {@code 0} to disable all flags
         * @see #setFlags(long, long)
         */
        @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
        @NonNull
        public Builder setFlags(@DiscoveryFlags long flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Set the filter to apply to {@link NsdServiceInfo#getServiceName()} on received services.
         *
         * <p>As per RFC6335 5.1., service names must be only US-ASCII letters, digits and hyphens,
         * and matching is not case-sensitive.
         *
         * <p>Defaults to {@code null}, which means services will not be filtered by name.
         */
        @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
        @NonNull
        public Builder setServiceNameFilter(@Nullable PatternMatcher serviceNameFilter) {
            mServiceNameFilter = serviceNameFilter;
            return this;
        }

        /**
         * Set filters based on service attributes.
         *
         * <p>Service attributes are as per {@link NsdServiceInfo#getAttributes()}.
         *
         * <p>Keys of the provided map should match attribute keys. As per RFC6763 6.4. attribute
         * keys are expected to be printable US-ASCII values (0x20-0x7E), and the key matching is
         * not case-sensitive.
         *
         * <p>Values of the map are matchers that will be checked against attributes value bytes
         * read as uppercase hexadecimal if the pattern starts with 0x, or using UTF-8 encoding if
         * it does not. Value matching is case-sensitive.
         *
         * <p>If a map value is null, the corresponding attribute will be expected to be a boolean
         * attribute with no value as per RFC6763 6.4.
         *
         * <p>If a null or empty map is passed, no filtering will be done based on attributes (the
         * default).
         *
         * @param attributeFilters A map of attribute key -> filter for that attribute value
         */
        @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
        @NonNull
        public Builder setAttributeFilters(@Nullable Map<String, PatternMatcher> attributeFilters) {
            mAttributeFilters.clear();
            if (attributeFilters != null) {
                mAttributeFilters.putAll(attributeFilters);
            }
            return this;
        }

        /**
         * Set the service attribute to use to display the service in the service picker UI.
         *
         * <p>Attribute key matching is not case-sensitive, as per RFC6763 6.4.
         *
         * <p>Defaults to {@code null}, which means the service name will be used instead.
         *
         * @param attributeKey The key of the attribute, or null to use the service name.
         */
        @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
        @NonNull
        public Builder setDisplayNameAttribute(@Nullable String attributeKey) {
            mDisplayNameAttribute = attributeKey;
            return this;
        }

        /**
         * Sets the discovery flags.
         *
         * <p>Multiple flags may be enabled or disabled by passing the logical OR of the flags.
         *
         * <p>For example, to set {@link #FLAG_NO_PICKER}:
         * {@code setFlags(FLAG_NO_PICKER, FLAG_NO_PICKER)}
         *
         * <p>To disable {@link #FLAG_NO_PICKER}: {@code setFlags(0, FLAG_NO_PICKER)}
         *
         * @param flags a bitmask of values to set; may be a single flag,
         *              the logical OR of multiple flags, or 0 to clear
         * @param mask a bitmask indicating which flags to modify
         */
        @FlaggedApi(FLAG_ACCESS_LOCAL_NETWORK_PERMISSION_ENABLED)
        @NonNull
        public Builder setFlags(@DiscoveryFlags long flags, @DiscoveryFlags long mask) {
            mFlags = (mFlags & ~mask) | (flags & mask);
            return this;
        }

        /**
         * Creates a new {@link DiscoveryRequest} object.
         */
        @NonNull
        public DiscoveryRequest build() {
            if ((mFlags & FLAG_SHOW_PICKER) != 0) {
                if ((mFlags & FLAG_NO_PICKER) != 0) {
                    throw new IllegalArgumentException(
                            "Cannot combine FLAG_SHOW_PICKER and FLAG_NO_PICKER");
                }
                if ((mFlags & FLAG_USER_APPROVED_ONLY) != 0) {
                    throw new IllegalArgumentException(
                            "Cannot combine FLAG_SHOW_PICKER and FLAG_USER_APPROVED_ONLY");
                }
            }
            return new DiscoveryRequest(mProtocolType, mServiceType, mSubtype, mNetwork, mFlags,
                    mServiceNameFilter, mAttributeFilters, mDisplayNameAttribute);
        }
    }
}
