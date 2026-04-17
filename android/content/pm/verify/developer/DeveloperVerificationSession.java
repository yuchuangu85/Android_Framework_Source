/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.verify.developer;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.Flags;
import android.content.pm.PackageInstaller;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * This class is used by the system to describe the details about a developer verification request
 * sent to the verification agent, aka the verifier. It includes the interfaces for the verifier to
 * communicate back to the system.
 * @hide
 */
@FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE)
@SystemApi
public final class DeveloperVerificationSession implements Parcelable {
    /**
     * The developer verification cannot be completed because of unknown reasons.
     */
    public static final int DEVELOPER_VERIFICATION_INCOMPLETE_UNKNOWN = 0;
    /**
     * The developer verification cannot be completed because the network is unavailable.
     */
    public static final int DEVELOPER_VERIFICATION_INCOMPLETE_NETWORK_UNAVAILABLE = 1;

    /**
     * @hide
     */
    @IntDef(prefix = {"DEVELOPER_VERIFICATION_INCOMPLETE_"}, value = {
            DEVELOPER_VERIFICATION_INCOMPLETE_UNKNOWN,
            DEVELOPER_VERIFICATION_INCOMPLETE_NETWORK_UNAVAILABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeveloperVerificationIncompleteReason {
    }

    /**
     * The developer verification is bypassed because of an unspecified reason. This field is
     * reserved and must not be used when reporting a developer verification bypass.
     * @hide
     */
    public static final int DEVELOPER_VERIFICATION_BYPASSED_REASON_UNSPECIFIED = 0;
    /**
     * The developer verification is bypassed because the installation was initiated from the
     * Android Debug Bridge (ADB) service.
     */
    public static final int DEVELOPER_VERIFICATION_BYPASSED_REASON_ADB = 1;
    /**
     * The developer verification is bypassed because the verification could not be performed due
     * to emergency conditions such as the verification service being unresponsive. Only critical
     * packages such as the verifier itself, the installer or the update owner of the verifier, or
     * the emergency installer are allowed to bypass the developer verification with this reason.
     */
    public static final int DEVELOPER_VERIFICATION_BYPASSED_REASON_EMERGENCY = 2;
    /**
     * The developer verification is bypassed because this is a testing environment and the result
     * of the verification does not rely on the actual verification service.
     */
    public static final int DEVELOPER_VERIFICATION_BYPASSED_REASON_TEST = 3;

    /**
     * Indicates whether the installation is through ADB.
     */
    @FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE_ADB)
    public static final int FLAG_VERIFICATION_IS_ADB = 0x00000001;
    /**
     * Indicates that the verification should be forced to take place when the installation is
     * through ADB. This flag will only be set when
     * {@link DeveloperVerificationSession#FLAG_VERIFICATION_IS_ADB} is also set. Even when this
     * flag is set, the system will only block the installation if the current
     * {@link DeveloperVerificationPolicy} is set to a blocking mode.
     */
    @FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE_ADB)
    public static final int FLAG_VERIFICATION_FORCED_ON_ADB = 0x00000002;

    /**
     * @hide
     */
    @FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE_ADB)
    @IntDef(flag = true, prefix = {"FLAG_VERIFICATION_"}, value = {
            FLAG_VERIFICATION_IS_ADB,
            FLAG_VERIFICATION_FORCED_ON_ADB,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VerificationFlags {}

    private final int mId;
    private final int mInstallSessionId;
    @NonNull
    private final String mPackageName;
    @NonNull
    private final Uri mStagedPackageUri;
    @NonNull
    private final SigningInfo mSigningInfo;
    @NonNull
    private final List<SharedLibraryInfo> mDeclaredLibraries;
    @Nullable
    private final PersistableBundle mExtensionParams;
    @NonNull
    private final IDeveloperVerificationSessionInterface mSession;
    private final int mVerificationFlags;
    /**
     * The current policy that is active for the developer verification session. It might not be
     * the same as the original policy that was initially assigned for this verification session,
     * because the active policy can be overridden by {@link #setPolicy(int)}.
     * <p>To improve the latency, store the original policy value and any changes made to it,
     * so that {@link #getPolicy()} does not need to make a binder call to retrieve the
     * currently active policy.</p>
     */
    private volatile @PackageInstaller.DeveloperVerificationPolicy int mPolicy;

    /**
     * Constructor used by the system to describe the details of a developer verification session.
     * @hide
     */
    public DeveloperVerificationSession(int id, int installSessionId, @NonNull String packageName,
            @NonNull Uri stagedPackageUri, @NonNull SigningInfo signingInfo,
            @NonNull List<SharedLibraryInfo> declaredLibraries,
            @Nullable PersistableBundle extensionParams,
            @PackageInstaller.DeveloperVerificationPolicy int defaultPolicy,
            @NonNull IDeveloperVerificationSessionInterface session) {
        this(id, installSessionId, packageName, stagedPackageUri,
                signingInfo, declaredLibraries, extensionParams, defaultPolicy, session,
                /* verificationFlags= */ 0);
    }

    /**
     * Constructor used by the system to describe the details of a developer verification session.
     * @hide
     */
    public DeveloperVerificationSession(int id, int installSessionId, @NonNull String packageName,
            @NonNull Uri stagedPackageUri, @NonNull SigningInfo signingInfo,
            @NonNull List<SharedLibraryInfo> declaredLibraries,
            @Nullable PersistableBundle extensionParams,
            @PackageInstaller.DeveloperVerificationPolicy int defaultPolicy,
            @NonNull IDeveloperVerificationSessionInterface session, int verificationFlags) {
        mId = id;
        mInstallSessionId = installSessionId;
        mPackageName = packageName;
        mStagedPackageUri = stagedPackageUri;
        mSigningInfo = signingInfo;
        mDeclaredLibraries = declaredLibraries;
        mExtensionParams = extensionParams;
        mPolicy = defaultPolicy;
        mSession = session;
        mVerificationFlags = verificationFlags;
    }

    /**
     * A unique identifier tied to this specific developer verification session.
     */
    public int getId() {
        return mId;
    }

    /**
     * The package name of the app that is to be verified.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * The id of the installation session associated with the developer verification.
     */
    public int getInstallSessionId() {
        return mInstallSessionId;
    }

    /**
     * The Uri of the path where the package's code files are located.
     */
    public @NonNull Uri getStagedPackageUri() {
        return mStagedPackageUri;
    }

    /**
     * Signing info of the package to be verified.
     */
    public @NonNull SigningInfo getSigningInfo() {
        return mSigningInfo;
    }

    /**
     * Returns a mapping of any shared libraries declared in the manifest
     * to the {@link SharedLibraryInfo.Type} that is declared.
     * <p>This will be an empty map if no shared libraries are declared by the package.</p>
     */
    @NonNull
    public List<SharedLibraryInfo> getDeclaredLibraries() {
        return Collections.unmodifiableList(mDeclaredLibraries);
    }

    /**
     * Returns any extension params associated with the developer verification request.
     */
    @NonNull
    public PersistableBundle getExtensionParams() {
        if (mExtensionParams == null) {
            return PersistableBundle.EMPTY;
        }
        return mExtensionParams;
    }

    /**
     * Get the point in time when this developer verification session
     * will timeout as incomplete if no other verification response is provided.
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     * @throws IllegalStateException if this is called after the session has finished, because
     * the {@link #reportVerificationComplete} or {@link #reportVerificationIncomplete} have
     * been called, or because the session has timed out.
     */
    @NonNull
    public Instant getTimeoutTime() {
        try {
            return Instant.ofEpochMilli(mSession.getTimeoutTimeMillis(mId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the current policy that is active for this developer verification session.
     * <p>If the policy for this session has been changed by {@link #setPolicy},
     * the return value of this method is the current policy that is active for this session.
     * Otherwise, the return value is the same as the initial policy that was assigned to the
     * session when it was first created.</p>
     */
    public @PackageInstaller.DeveloperVerificationPolicy int getPolicy() {
        return mPolicy;
    }

    /**
     * Override the verification policy for this developer verification session.
     * @return True if the override was successful, False otherwise.
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     * @throws IllegalStateException if this is called after the session has finished, because
     * the {@link #reportVerificationComplete} or {@link #reportVerificationIncomplete} have
     * been called, or because the session has timed out, unless the new policy value is the same
     * as the existing one.
     */
    public boolean setPolicy(@PackageInstaller.DeveloperVerificationPolicy int policy) {
        if (mPolicy == policy) {
            // No effective policy change
            return true;
        }
        try {
            if (mSession.setVerificationPolicy(mId, policy)) {
                mPolicy = policy;
                return true;
            } else {
                return false;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Extend the timeout for this developer verification session by the provided duration to
     * fetch relevant information over the network or wait for the network.
     * <p>
     * This may be called multiple times. If the request would bypass any max
     * duration by the system, the method will return a lower value than the
     * requested amount that indicates how much the time was extended.
     * </p>
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     */
    @NonNull
    public Duration extendTimeout(@NonNull Duration additionalDuration) {
        try {
            return Duration.ofMillis(
                    mSession.extendTimeoutMillis(mId, additionalDuration.toMillis()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report to the system that the developer verification verification could not be completed
     * along with an approximate reason to pass on to the installer.
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     * @throws IllegalStateException if this is called after the session has finished, because
     * this API or {@link #reportVerificationComplete} or {@link #reportVerificationBypassed}
     * have already been called once, or because the session has timed out.
     */
    public void reportVerificationIncomplete(@DeveloperVerificationIncompleteReason int reason) {
        try {
            mSession.reportVerificationIncomplete(mId, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report to the system that the developer verification verification has completed and the
     * install process may act on that status to either block in the case
     * of failure or continue to process the install in the case of success.
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     * @throws IllegalStateException if this is called after the session has finished, because
     * this API or {@link #reportVerificationIncomplete} or {@link #reportVerificationBypassed}
     * have already been called once, or because the session has timed out.
     */
    public void reportVerificationComplete(@NonNull DeveloperVerificationStatus status) {
        try {
            mSession.reportVerificationComplete(mId, status,  /* extensionResponse= */ null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Same as {@link #reportVerificationComplete(DeveloperVerificationStatus)}, but also provide
     * a result to the extension params provided in the request, which will be passed to the
     * installer in the installation result.
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     * @throws IllegalStateException if this is called after the session has finished, because
     * this API or {@link #reportVerificationIncomplete} or {@link #reportVerificationBypassed} have
     * already been called once, or because the session has timed out.
     */
    public void reportVerificationComplete(@NonNull DeveloperVerificationStatus status,
            @NonNull PersistableBundle extensionResponse) {
        try {
            mSession.reportVerificationComplete(mId, status, extensionResponse);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report to the system that the developer verification verification has been bypassed because
     * of a certain reason.
     * @param bypassReason The reason for the verification bypass, which must be a positive integer.
     * @throws IllegalArgumentException if @bypassReason is not a positive integer.
     * @throws SecurityException if the caller is not the current verifier bound by the system.
     * @throws IllegalStateException if this is called after the session has finished, because
     * this API or {@link #reportVerificationComplete} or or {@link #reportVerificationIncomplete}
     * have already been called once, or because the session has timed out.
     */
    public void reportVerificationBypassed(int bypassReason) {
        try {
            mSession.reportVerificationBypassed(mId, bypassReason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return any verification flags associated with this session e.g. whether this is a forced
     * verification, or if the install is through ADB, or both.
     * @return The currently set flags.
     */
    @FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE_ADB)
    public @VerificationFlags int getVerificationFlags() {
        return mVerificationFlags;
    }

    private DeveloperVerificationSession(@NonNull Parcel in) {
        mId = in.readInt();
        mInstallSessionId = in.readInt();
        mPackageName = in.readString8();
        mStagedPackageUri = Uri.CREATOR.createFromParcel(in);
        mSigningInfo = SigningInfo.CREATOR.createFromParcel(in);
        mDeclaredLibraries = in.createTypedArrayList(SharedLibraryInfo.CREATOR);
        mExtensionParams = in.readPersistableBundle(getClass().getClassLoader());
        mPolicy = in.readInt();
        mSession = IDeveloperVerificationSessionInterface.Stub.asInterface(in.readStrongBinder());
        mVerificationFlags = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mInstallSessionId);
        dest.writeString8(mPackageName);
        Uri.writeToParcel(dest, mStagedPackageUri);
        mSigningInfo.writeToParcel(dest, flags);
        dest.writeTypedList(mDeclaredLibraries);
        dest.writePersistableBundle(mExtensionParams);
        dest.writeInt(mPolicy);
        dest.writeStrongBinder(mSession.asBinder());
        dest.writeInt(mVerificationFlags);
    }

    @NonNull
    public static final Creator<DeveloperVerificationSession> CREATOR = new Creator<>() {
        @Override
        public DeveloperVerificationSession createFromParcel(@NonNull Parcel in) {
            return new DeveloperVerificationSession(in);
        }

        @Override
        public DeveloperVerificationSession[] newArray(int size) {
            return new DeveloperVerificationSession[size];
        }
    };
}
