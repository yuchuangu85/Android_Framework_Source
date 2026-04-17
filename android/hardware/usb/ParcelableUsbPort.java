/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.usb;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.Immutable;

/**
 * A parcelable wrapper to send UsbPorts over binders.
 *
 * @hide
 */
@Immutable
public final class ParcelableUsbPort implements Parcelable {
    private final @NonNull String mId;
    private final int mSupportedModes;
    private final int mSupportedContaminantProtectionModes;
    private final boolean mSupportsEnableContaminantPresenceProtection;
    private final boolean mSupportsEnableContaminantPresenceDetection;
    private final boolean mSupportsComplianceWarnings;
    private final int mSupportedAltModesMask;
    private final boolean mSupportsPartnerBc12Type;
    private final boolean mSupportsPowerProfiles;

    private ParcelableUsbPort(@NonNull String id, int supportedModes,
            int supportedContaminantProtectionModes,
            boolean supportsEnableContaminantPresenceProtection,
            boolean supportsEnableContaminantPresenceDetection,
            boolean supportsComplianceWarnings,
            int supportedAltModesMask,
            boolean supportsPartnerBc12Type,
            boolean supportsPowerProfiles) {
        mId = id;
        mSupportedModes = supportedModes;
        mSupportedContaminantProtectionModes = supportedContaminantProtectionModes;
        mSupportsEnableContaminantPresenceProtection =
                supportsEnableContaminantPresenceProtection;
        mSupportsEnableContaminantPresenceDetection =
                supportsEnableContaminantPresenceDetection;
        mSupportsComplianceWarnings =
                supportsComplianceWarnings;
        mSupportedAltModesMask = supportedAltModesMask;
        mSupportsPartnerBc12Type = supportsPartnerBc12Type;
        mSupportsPowerProfiles = supportsPowerProfiles;
    }

    /**
     * Create the parcelable version of a {@link UsbPort}.
     *
     * @param port The port to create a parcealable version of
     *
     * @return The parcelable version of the port
     */
    public static @NonNull ParcelableUsbPort of(@NonNull UsbPort port) {
        return new ParcelableUsbPort(port.getId(), port.getSupportedModes(),
                port.getSupportedContaminantProtectionModes(),
                port.supportsEnableContaminantPresenceProtection(),
                port.supportsEnableContaminantPresenceDetection(),
                port.supportsComplianceWarnings(),
                port.getSupportedAltModesMask(),
                port.supportsPartnerBc12Type(),
                port.supportsPowerProfiles());
    }

    /**
     * Create a {@link UsbPort} from this object.
     *
     * @param usbManager A link to the usbManager in the current context
     *
     * @return The UsbPort for this object
     */
    public @NonNull UsbPort getUsbPort(@NonNull UsbManager usbManager) {
        UsbPort.Builder builder = new UsbPort.Builder();
        builder.setId(mId);
        builder.setUsbManager(usbManager);
        builder.setSupportedModes(mSupportedModes);
        builder.setSupportedContaminantProtectionModes(mSupportedContaminantProtectionModes);
        builder.setSupportsEnableContaminantPresenceProtection(
                mSupportsEnableContaminantPresenceProtection);
        builder.setSupportsEnableContaminantPresenceDetection(
                mSupportsEnableContaminantPresenceDetection);
        builder.setSupportsComplianceWarnings(mSupportsComplianceWarnings);
        builder.setSupportedAltModes(mSupportedAltModesMask);
        builder.setSupportsPartnerBc12Type(mSupportsPartnerBc12Type);
        builder.setSupportsPowerProfiles(mSupportsPowerProfiles);

        return builder.build();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mSupportedModes);
        dest.writeInt(mSupportedContaminantProtectionModes);
        dest.writeBoolean(mSupportsEnableContaminantPresenceProtection);
        dest.writeBoolean(mSupportsEnableContaminantPresenceDetection);
        dest.writeBoolean(mSupportsComplianceWarnings);
        dest.writeInt(mSupportedAltModesMask);
        dest.writeBoolean(mSupportsPartnerBc12Type);
        dest.writeBoolean(mSupportsPowerProfiles);
    }

    public static final @android.annotation.NonNull Creator<ParcelableUsbPort> CREATOR =
            new Creator<ParcelableUsbPort>() {
                @Override
                public ParcelableUsbPort createFromParcel(Parcel in) {
                    String id = in.readString();
                    int supportedModes = in.readInt();
                    int supportedContaminantProtectionModes = in.readInt();
                    boolean supportsEnableContaminantPresenceProtection = in.readBoolean();
                    boolean supportsEnableContaminantPresenceDetection = in.readBoolean();
                    boolean supportsComplianceWarnings = in.readBoolean();
                    int supportedAltModesMask = in.readInt();
                    boolean supportsPartnerBc12Type = in.readBoolean();
                    boolean supportsPowerProfiles = in.readBoolean();

                    return new ParcelableUsbPort(id, supportedModes,
                            supportedContaminantProtectionModes,
                            supportsEnableContaminantPresenceProtection,
                            supportsEnableContaminantPresenceDetection,
                            supportsComplianceWarnings,
                            supportedAltModesMask,
                            supportsPartnerBc12Type,
                            supportsPowerProfiles);
                }

                @Override
                public ParcelableUsbPort[] newArray(int size) {
                    return new ParcelableUsbPort[size];
                }
            };
}
