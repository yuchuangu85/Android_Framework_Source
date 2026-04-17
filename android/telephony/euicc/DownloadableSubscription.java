/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.telephony.euicc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.carrier.CarrierIdentifier;
import android.telephony.UiccAccessRule;

import com.android.internal.telephony.flags.Flags;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Information about a subscription which is downloadable to an eUICC using
 * {@link EuiccManager#downloadSubscription(DownloadableSubscription, boolean, PendingIntent)}.
 *
 * <p>For example, a DownloadableSubscription can be created through an activation code parsed from
 * a QR code. A server address can be parsed from the activation code to download more information
 * about the profile, such as carrier name, access rules, etc.
 */
public final class DownloadableSubscription implements Parcelable {

    public static final @android.annotation.NonNull Creator<DownloadableSubscription> CREATOR =
            new Creator<DownloadableSubscription>() {
                @Override
                public DownloadableSubscription createFromParcel(Parcel in) {
                    return new DownloadableSubscription(in);
                }

                @Override
                public DownloadableSubscription[] newArray(int size) {
                    return new DownloadableSubscription[size];
                }
            };

    /**
     * Activation code. May be null for subscriptions which are not based on activation codes, e.g.
     * to download a default subscription assigned to this device.
     * Should use getEncodedActivationCode() instead.
     * @hide
     * @deprecated - Do not use. This will be private. Use getEncodedActivationCode() instead.
     */
    @Nullable
    @Deprecated
    public final String encodedActivationCode;

    @Nullable private String confirmationCode;

    // see getCarrierName and setCarrierName
    @Nullable
    private String carrierName;

    // see getAccessRules and setAccessRules
    @Nullable
    private List<UiccAccessRule> accessRules;

    @Nullable
    private CarrierIdentifier carrierIdentifier;

    /** Gets the activation code. */
    @Nullable
    public String getEncodedActivationCode() {
        return encodedActivationCode;
    }

    /** @hide */
    private DownloadableSubscription(String encodedActivationCode) {
        this.encodedActivationCode = encodedActivationCode;
    }

    private DownloadableSubscription(Parcel in) {
        encodedActivationCode = in.readString();
        confirmationCode = in.readString();
        carrierName = in.readString();
        accessRules = new ArrayList<UiccAccessRule>();
        in.readTypedList(accessRules, UiccAccessRule.CREATOR);
        if (Flags.downloadableSubscriptionIncludeCarrierIdentifierInternal()) {
            carrierIdentifier = in.readParcelable(null, CarrierIdentifier.class);
        }
    }

    private DownloadableSubscription(String encodedActivationCode, String confirmationCode,
            String carrierName, List<UiccAccessRule> accessRules) {
        this(encodedActivationCode, confirmationCode, carrierName, accessRules, null);
    }

    private DownloadableSubscription(String encodedActivationCode, String confirmationCode,
            String carrierName, List<UiccAccessRule> accessRules,
            CarrierIdentifier carrierIdentifier) {
        this.encodedActivationCode = encodedActivationCode;
        this.confirmationCode = confirmationCode;
        this.carrierName = carrierName;
        this.accessRules = accessRules;
        this.carrierIdentifier = carrierIdentifier;
    }

    public static final class Builder {
        @Nullable private String encodedActivationCode;
        @Nullable private String confirmationCode;
        @Nullable private String carrierName;
        List<UiccAccessRule> accessRules;
        @Nullable private CarrierIdentifier carrierIdentifier;

        /** @hide */
        @SystemApi
        public Builder() {}

        public Builder(@NonNull DownloadableSubscription baseSubscription) {
            encodedActivationCode = baseSubscription.getEncodedActivationCode();
            confirmationCode = baseSubscription.getConfirmationCode();
            carrierName = baseSubscription.getCarrierName();
            accessRules = baseSubscription.getAccessRules();
        }

        public Builder(@NonNull String encodedActivationCode) {
            this.encodedActivationCode = encodedActivationCode;
        }

        /**
         * Builds a {@link DownloadableSubscription} object.
         * @return a non-null {@link DownloadableSubscription} object.
         */
        @NonNull
        public DownloadableSubscription build() {
            if (Flags.downloadableSubscriptionIncludeCarrierIdentifierInternal()) {
                return new DownloadableSubscription(encodedActivationCode, confirmationCode,
                        carrierName, accessRules, carrierIdentifier);
            } else {
                return new DownloadableSubscription(encodedActivationCode, confirmationCode,
                        carrierName, accessRules);
            }
        }

        /**
         * Sets the encoded activation code.
         * @param value the activation code to use. An activation code can be parsed from a user
         *              scanned QR code. The format of activation code is defined in SGP.22. For
         *              example, "1$SMDP.GSMA.COM$04386-AGYFT-A74Y8-3F815$1.3.6.1.4.1.31746". For
         *              detail, see {@code com.android.euicc.data.ActivationCode}. Must not be null.
         */
        @NonNull
        public Builder setEncodedActivationCode(@NonNull String value) {
            encodedActivationCode = value;
            return this;
        }

        /**
         * Sets the confirmation code.
         * @param value the confirmation code to use to authenticate the carrier server got
         *              subscription download.
         */
        @NonNull
        public Builder setConfirmationCode(@NonNull String value) {
            confirmationCode = value;
            return this;
        }

        /**
         * Sets the user-visible carrier name.
         * @param value carrier name.
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setCarrierName(@NonNull String value) {
            carrierName = value;
            return this;
        }

        /**
         * Sets the {@link UiccAccessRule}s dictating access to this subscription.
         * @param value A list of {@link UiccAccessRule}s.
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setAccessRules(@NonNull List<UiccAccessRule> value) {
            accessRules = value;
            return this;
        }

        /**
         * Sets the {@link CarrierIdentifier} for this subscription.
         * @param value {@link CarrierIdentifier}.
         * @hide
         */
        @NonNull
        public Builder setCarrierIdentifier(@NonNull CarrierIdentifier value) {
            if (!Flags.downloadableSubscriptionIncludeCarrierIdentifierInternal()) {
                throw new UnsupportedOperationException(
                        "Naughty attempt to access setCarrierIdentifier");
            }
            carrierIdentifier = value;
            return this;
        }
    }

    /**
     * Create a DownloadableSubscription for the given activation code.
     *
     * <p>This fills the encodedActivationCode field. Other fields like confirmationCode,
     * carrierName and accessRules may be filled in the implementation of
     * {@code android.service.euicc.EuiccService} if exists.
     *
     * @param encodedActivationCode the activation code to use. An activation code can be parsed
     *         from a user scanned QR code. The format of activation code is defined in SGP.22. For
     *         example, "1$SMDP.GSMA.COM$04386-AGYFT-A74Y8-3F815$1.3.6.1.4.1.31746". For detail, see
     *         {@code com.android.euicc.data.ActivationCode}. Must not be null.
     *
     * @return the {@link DownloadableSubscription} which may be passed to
     *     {@link EuiccManager#downloadSubscription}.
     */
    public static DownloadableSubscription forActivationCode(String encodedActivationCode) {
        Preconditions.checkNotNull(encodedActivationCode, "Activation code may not be null");
        return new DownloadableSubscription(encodedActivationCode);
    }

    /**
     * Sets the confirmation code.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }

    /**
     * Returns the confirmation code.
     *
     * <p>As an example, the confirmation code can be input by the user through a carrier app or the
     * UI component of the eUICC local profile assistant (LPA) application.
     */
    @Nullable
    public String getConfirmationCode() {
        return confirmationCode;
    }

    /**
     * Set the user-visible carrier name.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setCarrierName(String carrierName) {
        this.carrierName = carrierName;
    }

    /**
     * Returns the user-visible carrier name.
     *
     * <p>Only present for downloadable subscriptions that were queried from a server (as opposed to
     * those created with {@link #forActivationCode}). May be populated with
     * {@link EuiccManager#getDownloadableSubscriptionMetadata}.
     * @hide
     */
    @SystemApi
    @Nullable
    public String getCarrierName() {
        return carrierName;
    }

    /**
     * Returns the {@link UiccAccessRule}s in list dictating access to this subscription.
     *
     * <p>Only present for downloadable subscriptions that were queried from a server (as opposed to
     * those created with {@link #forActivationCode}). May be populated with
     * {@link EuiccManager#getDownloadableSubscriptionMetadata}.
     * @hide
     */
    @SystemApi
    public List<UiccAccessRule> getAccessRules() {
        return accessRules;
    }

    /**
     * Returns the {@link CarrierIdentifier} for this subscription.
     *
     * <p>Only present for downloadable subscriptions that were queried from a server (as opposed to
     * those created with {@link #forActivationCode}). May be populated with
     * {@link EuiccManager#getDownloadableSubscriptionMetadata}.
     * @hide
     */
    @Nullable
    public CarrierIdentifier getCarrierIdentifier() {
        if (!Flags.downloadableSubscriptionIncludeCarrierIdentifierInternal()) {
            throw new UnsupportedOperationException(
                    "Naughty attempt to access getCarrierIdentifier");
        }
        return carrierIdentifier;
    }


    /**
     * Set the {@link UiccAccessRule}s dictating access to this subscription.
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setAccessRules(List<UiccAccessRule> accessRules) {
        this.accessRules = accessRules;
    }

    /**
     * @hide
     * @deprecated - Do not use.
     */
    @Deprecated
    public void setAccessRules(UiccAccessRule[] accessRules) {
        this.accessRules = Arrays.asList(accessRules);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(encodedActivationCode);
        dest.writeString(confirmationCode);
        dest.writeString(carrierName);
        dest.writeTypedList(accessRules);
        if (Flags.downloadableSubscriptionIncludeCarrierIdentifierInternal()) {
            dest.writeParcelable(carrierIdentifier, flags);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
