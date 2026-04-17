/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.widget;

import static com.android.internal.widget.LockPatternUtils.clamp;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.gatekeeper.GateKeeperResponse;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * Response object for a ILockSettings credential verification request.
 * @hide
 */
public final class VerifyCredentialResponse implements Parcelable {

    /**
     * Credential verification failed for a reason that isn't covered by one of the more specific
     * response codes.
     */
    private static final int RESPONSE_OTHER_ERROR = -1;

    /** Credential was successfully verified. */
    private static final int RESPONSE_OK = 0;

    /**
     * The credential could not be verified because a timeout is still active. {@link
     * #getTimeoutAsDuration()} gives the currently active timeout.
     *
     * <p>Alternatively, a timeout was not active, the credential was incorrect, and there is a
     * timeout before the <em>next</em> attempt will be allowed. {@link #getTimeoutAsDuration()}
     * gives the newly active timeout. The preferred response code in this case is {@link
     * #RESPONSE_CRED_INCORRECT}, but some devices use a rate-limiting HAL implementation that does
     * not differentiate this case from the "timeout is still active" case.
     */
    private static final int RESPONSE_RETRY = 1;

    /** Credential was shorter than the minimum length. */
    private static final int RESPONSE_CRED_TOO_SHORT = 2;

    /** Credential was incorrect and was already tried recently. */
    private static final int RESPONSE_CRED_ALREADY_TRIED = 3;

    /**
     * Credential was incorrect and none of {@link #RESPONSE_RETRY}, {@link
     * #RESPONSE_CRED_TOO_SHORT}, or {@link #RESPONSE_CRED_ALREADY_TRIED} applies.
     *
     * <p>{@link #getTimeoutAsDuration()} gives the newly active timeout, if any.
     */
    private static final int RESPONSE_CRED_INCORRECT = 4;

    @IntDef({
        RESPONSE_OTHER_ERROR,
        RESPONSE_OK,
        RESPONSE_RETRY,
        RESPONSE_CRED_TOO_SHORT,
        RESPONSE_CRED_ALREADY_TRIED,
        RESPONSE_CRED_INCORRECT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ResponseCode {}

    public static final VerifyCredentialResponse OK = new VerifyCredentialResponse.Builder()
            .build();
    public static final VerifyCredentialResponse OTHER_ERROR = fromError(RESPONSE_OTHER_ERROR);
    private static final String TAG = "VerifyCredentialResponse";

    private final @ResponseCode int mResponseCode;
    private final Duration mTimeout;
    @Nullable private final byte[] mGatekeeperHAT;
    private final long mGatekeeperPasswordHandle;

    public static final Parcelable.Creator<VerifyCredentialResponse> CREATOR =
            new Parcelable.Creator<VerifyCredentialResponse>() {
                @Override
                public VerifyCredentialResponse createFromParcel(Parcel source) {
                    final @ResponseCode int responseCode = source.readInt();
                    final long timeoutSeconds = source.readLong();
                    final long timeoutNanos = source.readInt();
                    final byte[] gatekeeperHAT = source.createByteArray();
                    long gatekeeperPasswordHandle = source.readLong();

                    return new VerifyCredentialResponse(
                            responseCode,
                            Duration.ofSeconds(timeoutSeconds, timeoutNanos),
                            gatekeeperHAT,
                            gatekeeperPasswordHandle);
                }

                @Override
                public VerifyCredentialResponse[] newArray(int size) {
                    return new VerifyCredentialResponse[size];
                }
            };

    public static class Builder {
        @Nullable private byte[] mGatekeeperHAT;
        private long mGatekeeperPasswordHandle;

        /**
         * @param gatekeeperHAT Gatekeeper HardwareAuthToken, minted upon successful authentication.
         */
        public Builder setGatekeeperHAT(byte[] gatekeeperHAT) {
            mGatekeeperHAT = gatekeeperHAT;
            return this;
        }

        public Builder setGatekeeperPasswordHandle(long gatekeeperPasswordHandle) {
            mGatekeeperPasswordHandle = gatekeeperPasswordHandle;
            return this;
        }

        /**
         * Builds a VerifyCredentialResponse with {@link #RESPONSE_OK} and any other parameters
         * that were preveiously set.
         * @return
         */
        public VerifyCredentialResponse build() {
            return new VerifyCredentialResponse(
                    RESPONSE_OK,
                    Duration.ZERO /* timeout */,
                    mGatekeeperHAT,
                    mGatekeeperPasswordHandle);
        }
    }

    /**
     * Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_RETRY} and the given timeout
     * in milliseconds.
     */
    public static VerifyCredentialResponse fromTimeout(int timeout) {
        return fromTimeout(Duration.ofMillis(timeout));
    }

    /**
     * Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_RETRY} and the given timeout.
     */
    public static VerifyCredentialResponse fromTimeout(Duration timeout) {
        return new VerifyCredentialResponse(
                RESPONSE_RETRY,
                timeout,
                null /* gatekeeperHAT */,
                0L /* gatekeeperPasswordHandle */);
    }

    /**
     * Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_CRED_INCORRECT} and the given
     * timeout.
     */
    public static VerifyCredentialResponse credIncorrect(Duration timeout) {
        return new VerifyCredentialResponse(
                VerifyCredentialResponse.RESPONSE_CRED_INCORRECT,
                timeout,
                /* gatekeeperHAT= */ null,
                /* gatekeeperPasswordHandle= */ 0L);
    }

    /** Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_OTHER_ERROR}. */
    public static VerifyCredentialResponse fromError() {
        return fromError(RESPONSE_OTHER_ERROR);
    }

    /** Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_CRED_TOO_SHORT}. */
    public static VerifyCredentialResponse credTooShort() {
        return fromError(RESPONSE_CRED_TOO_SHORT);
    }

    /** Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_CRED_ALREADY_TRIED}. */
    public static VerifyCredentialResponse credAlreadyTried() {
        return fromError(RESPONSE_CRED_ALREADY_TRIED);
    }

    /** Builds a {@link VerifyCredentialResponse} with {@link #RESPONSE_CRED_INCORRECT}. */
    public static VerifyCredentialResponse credIncorrect() {
        return fromError(RESPONSE_CRED_INCORRECT);
    }

    /**
     * Builds a {@link VerifyCredentialResponse} for an error response that does not use any of the
     * additional fields.
     */
    private static VerifyCredentialResponse fromError(@ResponseCode int responseCode) {
        Preconditions.checkArgument(
                responseCode == RESPONSE_OTHER_ERROR
                        || responseCode == RESPONSE_CRED_TOO_SHORT
                        || responseCode == RESPONSE_CRED_ALREADY_TRIED
                        || responseCode == RESPONSE_CRED_INCORRECT);
        return new VerifyCredentialResponse(
                responseCode,
                Duration.ZERO /* timeout */,
                null /* gatekeeperHAT */,
                0L /* gatekeeperPasswordHandle */);
    }

    private VerifyCredentialResponse(
            @ResponseCode int responseCode,
            Duration timeout,
            @Nullable byte[] gatekeeperHAT,
            long gatekeeperPasswordHandle) {
        mResponseCode = responseCode;
        mTimeout = timeout;
        mGatekeeperHAT = gatekeeperHAT;
        mGatekeeperPasswordHandle = gatekeeperPasswordHandle;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResponseCode);
        // A {@link Duration} is represented by
        // * a 64-bit seconds part
        // * a 32-bit nanosecond part between 0 and 999,999,999.
        dest.writeLong(mTimeout.getSeconds());
        dest.writeInt(mTimeout.getNano());
        dest.writeByteArray(mGatekeeperHAT);
        dest.writeLong(mGatekeeperPasswordHandle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Nullable
    public byte[] getGatekeeperHAT() {
        return mGatekeeperHAT;
    }

    public long getGatekeeperPasswordHandle() {
        return mGatekeeperPasswordHandle;
    }

    public boolean containsGatekeeperPasswordHandle() {
        return mGatekeeperPasswordHandle != 0L;
    }

    /**
     * Get the timeout clamped to {@link Integer#MAX_VALUE} milliseconds.
     *
     * <p>A negative timeout should never occur here, since the rate-limiters do not report negative
     *    timeouts. If a negative timeout is seen anyway, fail secure and treat it as possibly
     *    intended to be an unsigned value, i.e. MAX_VALUE rather than MIN_VALUE.
     */
    public Duration getTimeout() {
        if (android.security.Flags.enableUnclampedLockouts()) {
            return mTimeout;
        }
        return Duration.ofMillis(clamp(mTimeout));
    }

    public Duration getTimeoutAsDuration() {
        return mTimeout;
    }

    /** Returns true if credential verification succeeded. */
    public boolean isMatched() {
        return mResponseCode == RESPONSE_OK;
    }

    /**
     * Returns true if credential verification failed and there is a timeout before the next request
     * will be allowed.
     */
    public boolean hasTimeout() {
        // Check mTimeout directly. It can be nonzero for either RESPONSE_RETRY or
        // RESPONSE_CRED_INCORRECT.
        return !mTimeout.isZero();
    }

    /**
     * Returns true if credential verification failed because the credential was shorter than the
     * minimum length.
     */
    public boolean isCredTooShort() {
        return mResponseCode == RESPONSE_CRED_TOO_SHORT;
    }

    /**
     * Returns true if credential verification failed because the credential was incorrect and was
     * already tried recently.
     */
    public boolean isCredAlreadyTried() {
        return mResponseCode == RESPONSE_CRED_ALREADY_TRIED;
    }

    /**
     * Returns true if the credential is known for certain to be incorrect. Returns false in all
     * other cases: credential verification succeeded, or credential verification failed but it is
     * not known for certain that the credential is incorrect. (A credential that failed to verify
     * could still be correct if there is an active timeout or if there was a transient error.)
     */
    public boolean isCredCertainlyIncorrect() {
        return mResponseCode == RESPONSE_CRED_TOO_SHORT
                || mResponseCode == RESPONSE_CRED_ALREADY_TRIED
                || mResponseCode == RESPONSE_CRED_INCORRECT;
    }

    /**
     * Returns true if credential verification failed for a reason that isn't covered by {@link
     * #hasTimeout()} or {@link #isCredCertainlyIncorrect()}.
     */
    public boolean isOtherError() {
        return mResponseCode == RESPONSE_OTHER_ERROR;
    }

    @Override
    public String toString() {
        return "Response: "
                + mResponseCode
                + ", Timeout: "
                + mTimeout
                + ", GK HAT: "
                + (mGatekeeperHAT != null)
                + ", GK PW: "
                + (mGatekeeperPasswordHandle != 0L);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        VerifyCredentialResponse that = (VerifyCredentialResponse) o;
        return mResponseCode == that.mResponseCode
                && Objects.equals(mTimeout, that.mTimeout)
                && mGatekeeperPasswordHandle == that.mGatekeeperPasswordHandle
                && Objects.deepEquals(mGatekeeperHAT, that.mGatekeeperHAT);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mResponseCode,
                mTimeout,
                Arrays.hashCode(mGatekeeperHAT),
                mGatekeeperPasswordHandle);
    }

    public static VerifyCredentialResponse fromGateKeeperResponse(
            GateKeeperResponse gateKeeperResponse) {
        int responseCode = gateKeeperResponse.getResponseCode();
        if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
            return fromTimeout(gateKeeperResponse.getTimeout());
        } else if (responseCode == GateKeeperResponse.RESPONSE_OK) {
            byte[] token = gateKeeperResponse.getPayload();
            if (token == null) {
                // something's wrong if there's no payload with a challenge
                Slog.e(TAG, "verifyChallenge response had no associated payload");
                return fromError();
            } else {
                return new VerifyCredentialResponse.Builder().setGatekeeperHAT(token).build();
            }
        } else {
            return fromError();
        }
    }
}
