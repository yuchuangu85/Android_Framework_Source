/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telecom;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.ContactsContract;
import android.text.TextUtils;

import com.android.internal.telecom.ParcelUtils;
import com.android.server.telecom.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * CallAttributes represents a set of properties that define a new Call.  Apps should build an
 * instance of this class and use {@link TelecomManager#addCall} to start a new call with Telecom.
 *
 * <p>
 * Apps should first register a {@link PhoneAccount} via {@link TelecomManager#registerPhoneAccount}
 * and use the same {@link PhoneAccountHandle} registered with Telecom when creating an
 * instance of CallAttributes.
 */
public final class CallAttributes implements Parcelable {

    /** PhoneAccountHandle associated with the App managing calls **/
    private final PhoneAccountHandle mPhoneAccountHandle;

    /** Display name of the person on the other end of the call **/
    private final CharSequence mDisplayName;

    /** Address of the call. Note, this can be extended to a meeting link **/
    private final Uri mAddress;

    /** The direction (Outgoing/Incoming) of the new Call **/
    @Direction private final int mDirection;

    /** Information related to data being transmitted (voice, video, etc. ) **/
    @CallType private final int mCallType;

    /** Allows a package to opt into capabilities on the telecom side, on a per-call basis **/
    @CallCapability private final int mCallCapabilities;

    /** Indicate whether the call is excluded from the system call logs **/
    private final boolean mIsLogExcluded;
    /** Indicate whether this call was a group call **/
    private final boolean mIsGroupCall;
    /**
     * The VoIP contact directory or CP2 lookup URI that will be used by the system dialer to get
     * the enriched call info.
     */
    private final Uri mContactUri;

    /** @hide **/
    public static final String CALL_CAPABILITIES_KEY = "TelecomCapabilities";

    /** @hide **/
    public static final String DISPLAY_NAME_KEY = "DisplayName";

    /** @hide **/
    public static final String CALLER_PID_KEY = "CallerPid";

    /** @hide **/
    public static final String CALLER_UID_KEY = "CallerUid";

    private CallAttributes(@NonNull PhoneAccountHandle phoneAccountHandle,
            @NonNull CharSequence displayName,
            @NonNull Uri address,
            int direction,
            int callType,
            int callCapabilities,
            boolean isLogExcluded,
            boolean isGroupCall,
            Uri contactUri) {
        mPhoneAccountHandle = phoneAccountHandle;
        mDisplayName = displayName;
        mAddress = address;
        mDirection = direction;
        mCallType = callType;
        mCallCapabilities = callCapabilities;
        mIsLogExcluded = isLogExcluded;
        mIsGroupCall = isGroupCall;
        mContactUri = contactUri;
    }

    /** @hide */
    @IntDef(value = {DIRECTION_INCOMING, DIRECTION_OUTGOING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Direction {
    }
    /**
     * Indicates that the call is an incoming call.
     */
    public static final int DIRECTION_INCOMING = 1;
    /**
     * Indicates that the call is an outgoing call.
     */
    public static final int DIRECTION_OUTGOING = 2;

    /** @hide */
    @IntDef(value = {AUDIO_CALL, VIDEO_CALL, MESSAGING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallType {
    }
    /**
     * Used when answering or dialing a call to indicate that the call does not have a video
     * component
     */
    public static final int AUDIO_CALL = 1;
    /**
     * Indicates video transmission is supported
     */
    public static final int VIDEO_CALL = 2;
    /**
     * Intended for handling callback support for VoIP calls to indicate that the app's messaging
     * interface should be brought up.
     */
    @FlaggedApi(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public static final int MESSAGING = 3;

    /** @hide */
    @IntDef(value = {SUPPORTS_SET_INACTIVE, SUPPORTS_STREAM, SUPPORTS_TRANSFER,
            SUPPORTS_VIDEO_CALLING}, flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallCapability {
    }
    /**
     * The call being created can be set to inactive (traditionally referred to as hold).  This
     * means that once a new call goes active, if the active call needs to be held in order to
     * place or receive an incoming call, the active call will be placed on hold.  otherwise, the
     * active call may be disconnected.
     */
    public static final int SUPPORTS_SET_INACTIVE = 1 << 1;
    /**
     * The call can be streamed from a root device to another device to continue the call without
     * completely transferring it.
     */
    public static final int SUPPORTS_STREAM = 1 << 2;
    /**
     * The call can be completely transferred from one endpoint to another.
     */
    public static final int SUPPORTS_TRANSFER = 1 << 3;
    /**
     * The call supports video calling. This allows clients to gate video calling on a per call
     * basis as opposed to re-registering the phone account.
     */
    @FlaggedApi(Flags.FLAG_TRANSACTIONAL_VIDEO_STATE)
    public static final int SUPPORTS_VIDEO_CALLING = 1 << 4;

    /**
     * Build an instance of {@link CallAttributes}. In order to build a valid instance, a
     * {@link PhoneAccountHandle}, call direction, display name, and {@link Uri} address
     * are required.
     *
     * <p>
     * Note: Pass in the same {@link PhoneAccountHandle} that was used to register a
     * {@link PhoneAccount} with Telecom. see {@link TelecomManager#registerPhoneAccount}
     */
    public static final class Builder {
        // required and final fields
        private final PhoneAccountHandle mPhoneAccountHandle;
        @Direction private final int mDirection;
        private final CharSequence mDisplayName;
        private final Uri mAddress;
        // optional fields
        @CallType private int mCallType = CallAttributes.AUDIO_CALL;
        @CallCapability private int mCallCapabilities = SUPPORTS_SET_INACTIVE;
        private boolean mIsLogExcluded;
        private boolean mIsGroupCall;
        private Uri mContactUri;

        /**
         * Constructor for the CallAttributes.Builder class
         *
         * @param phoneAccountHandle that belongs to package registered with Telecom
         * @param callDirection of the new call that will be added to Telecom
         * @param displayName of the caller for incoming calls or initiating user for outgoing calls
         * @param address of the caller for incoming calls or destination for outgoing calls
         */
        public Builder(@NonNull PhoneAccountHandle phoneAccountHandle,
                @Direction int callDirection, @NonNull CharSequence displayName,
                @NonNull Uri address) {
            if (!isInRange(DIRECTION_INCOMING, DIRECTION_OUTGOING, callDirection)) {
                throw new IllegalArgumentException(String.format("CallDirection=[%d] is"
                                + " invalid. CallDirections value should be within [%d, %d]",
                        callDirection, DIRECTION_INCOMING, DIRECTION_OUTGOING));
            }
            Objects.requireNonNull(phoneAccountHandle);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(address);
            mPhoneAccountHandle = phoneAccountHandle;
            mDirection = callDirection;
            mDisplayName = displayName;
            mAddress = address;
        }

        /**
         * Sets the type of call; uses to indicate if a call is a video call or audio call.
         * @param callType The call type.
         * @return Builder
         */
        @NonNull
        public Builder setCallType(@CallType int callType) {
            if (!isInRange(AUDIO_CALL, VIDEO_CALL, callType)) {
                throw new IllegalArgumentException(String.format("CallType=[%d] is"
                                + " invalid. CallTypes value should be within [%d, %d]",
                        callType, AUDIO_CALL, VIDEO_CALL));
            }
            mCallType = callType;
            return this;
        }

        /**
         * Sets the capabilities of this call.  Use this to indicate whether your app supports
         * holding, streaming and call transfers.
         * @param callCapabilities Bitmask of call capabilities.
         * @return Builder
         */
        @NonNull
        public Builder setCallCapabilities(@CallCapability int callCapabilities) {
            mCallCapabilities = callCapabilities;
            return this;
        }

        /**
         * Sets the attribute to exclude the call from system call logs.
         * @param isExcluded whether the call should be excluded.
         * @return Builder
         */
        @FlaggedApi(Flags.FLAG_INTEGRATED_CALL_LOGS)
        @NonNull
        public Builder setLogExcluded(boolean isExcluded) {
            mIsLogExcluded = isExcluded;
            return this;
        }

        /**
         * Sets whether or not this call should be considered a group call.
         * @param isGroupCall whether the call is a group call.
         * @return Builder
         */
        @FlaggedApi(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
        @NonNull
        public Builder setIsGroupCall(boolean isGroupCall) {
            mIsGroupCall = isGroupCall;
            return this;
        }

        /**
         * Sets the contact directory URI for the VoIP app. This must be a valid URI pointing to the
         * VoIP contact directory or a valid CP2 contact.
         * @param contactUri The contact URI pointing to the VoIP contact directory or CP2 contact.
         * @return Builder
         */
        @FlaggedApi(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
        @NonNull
        public Builder setContactUri(@NonNull Uri contactUri) {
            Objects.requireNonNull(contactUri);
            validateVoipContactUri(contactUri);
            mContactUri = contactUri;
            return this;
        }

        /**
         * Build an instance of {@link CallAttributes} based on the last values passed to the
         * setters or default values.
         *
         * @return an instance of {@link CallAttributes}
         */
        @NonNull
        public CallAttributes build() {
            return new CallAttributes(mPhoneAccountHandle, mDisplayName, mAddress, mDirection,
                    mCallType, mCallCapabilities, mIsLogExcluded, mIsGroupCall, mContactUri);
        }

        /** @hide */
        private boolean isInRange(int floor, int ceiling, int value) {
            return value >= floor && value <= ceiling;
        }
    }

    /**
     * The {@link PhoneAccountHandle} that should be registered to Telecom to allow calls.  The
     * {@link PhoneAccountHandle} should be registered before creating a CallAttributes instance.
     *
     * @return the {@link PhoneAccountHandle} for this package that allows this call to be created
     */
    @NonNull public PhoneAccountHandle getPhoneAccountHandle() {
        return mPhoneAccountHandle;
    }

    /**
     * @return display name of the incoming caller or the person being called for an outgoing call
     */
    @NonNull public CharSequence getDisplayName() {
        return mDisplayName;
    }

    /**
     * @return address of the incoming caller
     *           or the address of the person being called for an outgoing call
     */
    @NonNull public Uri getAddress() {
        return mAddress;
    }

    /**
     * @return the direction of the new call.
     */
    public @Direction int getDirection() {
        return mDirection;
    }

    /**
     * @return Information related to data being transmitted (voice, video, etc. )
     */
    public @CallType int getCallType() {
        return mCallType;
    }

    /**
     * @return Whether the call is excluded from system call logs
     */
    @FlaggedApi(Flags.FLAG_INTEGRATED_CALL_LOGS)
    public boolean isLogExcluded() {
        return mIsLogExcluded;
    }

    /**
     * @return Whether the call is a group call
     */
    @FlaggedApi(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public boolean isGroupCall() {
        return mIsGroupCall;
    }

    /**
     * @return The contact URI pointing to the VoIP contact directory or CP2 contact
     */
    @FlaggedApi(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public @Nullable Uri getContactUri() {
        return mContactUri;
    }

    /**
     * @return The allowed capabilities of the new call
     */
    public @CallCapability int getCallCapabilities() {
        return mCallCapabilities;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@Nullable Parcel dest, int flags) {
        dest.writeParcelable(mPhoneAccountHandle, flags);
        ParcelUtils.writeCharSequence(dest, mDisplayName);
        dest.writeParcelable(mAddress, flags);
        dest.writeInt(mDirection);
        dest.writeInt(mCallType);
        dest.writeInt(mCallCapabilities);
        dest.writeBoolean(mIsLogExcluded);
        dest.writeBoolean(mIsGroupCall);
        dest.writeParcelable(mContactUri, flags);
    }

    /**
     * Responsible for creating CallAttribute objects for deserialized Parcels.
     */
    public static final @android.annotation.NonNull
            Parcelable.Creator<CallAttributes> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public CallAttributes createFromParcel(Parcel source) {
                    return new CallAttributes(source.readParcelable(getClass().getClassLoader(),
                            android.telecom.PhoneAccountHandle.class),
                            ParcelUtils.readCharSequence(source),
                            source.readParcelable(getClass().getClassLoader(),
                                    android.net.Uri.class),
                            source.readInt(),
                            source.readInt(),
                            source.readInt(),
                            source.readBoolean(),
                            source.readBoolean(),
                            source.readParcelable(Uri.class.getClassLoader(), Uri.class));
                }

                @Override
                public CallAttributes[] newArray(int size) {
                    return new CallAttributes[size];
                }
            };

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("{ CallAttributes: [phoneAccountHandle: ")
                .append(mPhoneAccountHandle)  /* PhoneAccountHandle#toString handles PII */
                .append("], [contactName: ")
                .append(Log.pii(mDisplayName))
                .append("], [address=")
                .append(Log.pii(mAddress))
                .append("], [direction=")
                .append(mDirection)
                .append("], [callType=")
                .append(mCallType)
                .append("], [mCallCapabilities=")
                .append(mCallCapabilities)
                .append("], [mIsLogExcluded=")
                .append(mIsLogExcluded)
                .append("], [mIsGroupCall=")
                .append(mIsGroupCall)
                .append("], [mContactUri=")
                .append(mContactUri)
                .append("]  }");

        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        CallAttributes that = (CallAttributes) obj;
        return this.mDirection == that.mDirection
                && this.mCallType == that.mCallType
                && this.mCallCapabilities == that.mCallCapabilities
                && Objects.equals(this.mPhoneAccountHandle, that.mPhoneAccountHandle)
                && Objects.equals(this.mAddress, that.mAddress)
                && TextUtils.equals(this.mDisplayName, that.mDisplayName)
                && this.mIsLogExcluded == that.mIsLogExcluded
                && this.mIsGroupCall == that.mIsGroupCall
                && Objects.equals(this.mContactUri, that.mContactUri);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(mPhoneAccountHandle, mAddress, mDisplayName,
                mDirection, mCallType, mCallCapabilities, mIsLogExcluded,
                mIsGroupCall, mContactUri);
    }

    /**
     * Validates the uri to ensure that it will resolve to a valid VoIP contact directory or CP2
     * contact URI. Also ensure that URIs are not passed across user boundaries.
     * @hide
     * @param uri VoIP contact URI to validate
     * @throws IllegalArgumentException if the uri is invalid
     */
    public static void validateVoipContactUri(Uri uri) {
        String errorMsg = String.format("The contact URI passed in, %s, is not"
                + " a valid URI. This must be a valid CP2 contact or VoIP contact "
                + "directory URI.", uri);
        // Todo: once VoIP contact directory URIs are established, we should validate on that
        //       as well.
        if (uri == null) {
            return;
        }
        final String scheme = uri.getScheme();
        final String authority = uri.getAuthority();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            throw new IllegalArgumentException(errorMsg);
        }
        // The authority should either be "com.android.contacts" or resolve to a user like
        // "0@com.android.contacts".
        if (authority == null || !authority.endsWith(ContactsContract.AUTHORITY)) {
            throw new IllegalArgumentException(errorMsg);
        }
        int callingUserId = Binder.getCallingUserHandle().getIdentifier();
        int requestingUserId = StatusHints.getUserIdFromAuthority(uri.getAuthority(),
                callingUserId);
        if (callingUserId != requestingUserId) {
            // If we are transcending the profile boundary, throw an error.
            throw new IllegalArgumentException("The contact URI passed in, " + uri + ", is not "
                    + "accessible by user " + callingUserId);
        }
    }
}
