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
package com.android.internal.telephony.euicc;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.euicc.EuiccService;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Representation of an {@link EuiccController} operation which failed with a resolvable error.
 *
 * <p>This class tracks the operation which failed and the reason for failure. Once the error is
 * resolved, the operation can be resumed with {@link #continueOperation}.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class EuiccOperation implements Parcelable {
    private static final String TAG = "EuiccOperation";

    public static final Creator<EuiccOperation> CREATOR = new Creator<EuiccOperation>() {
        @Override
        public EuiccOperation createFromParcel(Parcel in) {
            return new EuiccOperation(in);
        }

        @Override
        public EuiccOperation[] newArray(int size) {
            return new EuiccOperation[size];
        }
    };

    @VisibleForTesting
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ACTION_GET_METADATA_DEACTIVATE_SIM,
            ACTION_DOWNLOAD_DEACTIVATE_SIM,
            ACTION_DOWNLOAD_NO_PRIVILEGES,
    })
    @interface Action {}

    @VisibleForTesting
    static final int ACTION_GET_METADATA_DEACTIVATE_SIM = 1;
    @VisibleForTesting
    static final int ACTION_DOWNLOAD_DEACTIVATE_SIM = 2;
    @VisibleForTesting
    static final int ACTION_DOWNLOAD_NO_PRIVILEGES = 3;
    @VisibleForTesting
    static final int ACTION_GET_DEFAULT_LIST_DEACTIVATE_SIM = 4;
    @VisibleForTesting
    static final int ACTION_SWITCH_DEACTIVATE_SIM = 5;
    @VisibleForTesting
    static final int ACTION_SWITCH_NO_PRIVILEGES = 6;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public final @Action int mAction;

    private final long mCallingToken;

    @Nullable
    private final DownloadableSubscription mDownloadableSubscription;
    private final int mSubscriptionId;
    private final boolean mSwitchAfterDownload;
    @Nullable
    private final String mCallingPackage;

    /**
     * {@link EuiccManager#getDownloadableSubscriptionMetadata} failed with
     * {@link EuiccService#RESULT_MUST_DEACTIVATE_SIM}.
     */
    public static EuiccOperation forGetMetadataDeactivateSim(long callingToken,
            DownloadableSubscription subscription, String callingPackage) {
        return new EuiccOperation(ACTION_GET_METADATA_DEACTIVATE_SIM, callingToken,
                subscription, 0 /* subscriptionId */, false /* switchAfterDownload */,
                callingPackage);
    }

    /**
     * {@link EuiccManager#downloadSubscription} failed with a mustDeactivateSim error. Should only
     * be used for privileged callers; for unprivileged callers, use
     * {@link #forDownloadNoPrivileges} to avoid a double prompt.
     */
    public static EuiccOperation forDownloadDeactivateSim(long callingToken,
            DownloadableSubscription subscription, boolean switchAfterDownload,
            String callingPackage) {
        return new EuiccOperation(ACTION_DOWNLOAD_DEACTIVATE_SIM, callingToken,
                subscription,  0 /* subscriptionId */, switchAfterDownload, callingPackage);
    }

    /**
     * {@link EuiccManager#downloadSubscription} failed because the calling app does not have
     * permission to manage the current active subscription, or because we cannot determine the
     * privileges without deactivating the current SIM first.
     */
    public static EuiccOperation forDownloadNoPrivileges(long callingToken,
            DownloadableSubscription subscription, boolean switchAfterDownload,
            String callingPackage) {
        return new EuiccOperation(ACTION_DOWNLOAD_NO_PRIVILEGES, callingToken,
                subscription,  0 /* subscriptionId */, switchAfterDownload, callingPackage);
    }

    static EuiccOperation forGetDefaultListDeactivateSim(long callingToken, String callingPackage) {
        return new EuiccOperation(ACTION_GET_DEFAULT_LIST_DEACTIVATE_SIM, callingToken,
                null /* downloadableSubscription */, 0 /* subscriptionId */,
                false /* switchAfterDownload */, callingPackage);
    }

    static EuiccOperation forSwitchDeactivateSim(long callingToken, int subscriptionId,
            String callingPackage) {
        return new EuiccOperation(ACTION_SWITCH_DEACTIVATE_SIM, callingToken,
                null /* downloadableSubscription */, subscriptionId,
                false /* switchAfterDownload */, callingPackage);
    }

    static EuiccOperation forSwitchNoPrivileges(long callingToken, int subscriptionId,
            String callingPackage) {
        return new EuiccOperation(ACTION_SWITCH_NO_PRIVILEGES, callingToken,
                null /* downloadableSubscription */, subscriptionId,
                false /* switchAfterDownload */, callingPackage);
    }

    EuiccOperation(@Action int action,
            long callingToken,
            @Nullable DownloadableSubscription downloadableSubscription,
            int subscriptionId,
            boolean switchAfterDownload,
            String callingPackage) {
        mAction = action;
        mCallingToken = callingToken;
        mDownloadableSubscription = downloadableSubscription;
        mSubscriptionId = subscriptionId;
        mSwitchAfterDownload = switchAfterDownload;
        mCallingPackage = callingPackage;
    }

    EuiccOperation(Parcel in) {
        mAction = in.readInt();
        mCallingToken = in.readLong();
        mDownloadableSubscription = in.readTypedObject(DownloadableSubscription.CREATOR);
        mSubscriptionId = in.readInt();
        mSwitchAfterDownload = in.readBoolean();
        mCallingPackage = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAction);
        dest.writeLong(mCallingToken);
        dest.writeTypedObject(mDownloadableSubscription, flags);
        dest.writeInt(mSubscriptionId);
        dest.writeBoolean(mSwitchAfterDownload);
        dest.writeString(mCallingPackage);
    }

    /**
     * Resume this operation based on the results of the resolution activity.
     *
     * @param resolutionExtras The resolution extras as provided to
     *     {@link EuiccManager#continueOperation}.
     * @param callbackIntent The callback intent to trigger after the operation completes.
     */
    public void continueOperation(Bundle resolutionExtras, PendingIntent callbackIntent) {
        // Restore the identity of the caller. We should err on the side of caution and redo any
        // permission checks before continuing with the operation in case the caller state has
        // changed. Resolution flows can re-clear the identity if required.
        Binder.restoreCallingIdentity(mCallingToken);

        switch (mAction) {
            case ACTION_GET_METADATA_DEACTIVATE_SIM:
                resolvedGetMetadataDeactivateSim(
                        resolutionExtras.getBoolean(EuiccService.RESOLUTION_EXTRA_CONSENT),
                        callbackIntent);
                break;
            case ACTION_DOWNLOAD_DEACTIVATE_SIM:
                resolvedDownloadDeactivateSim(
                        resolutionExtras.getBoolean(EuiccService.RESOLUTION_EXTRA_CONSENT),
                        callbackIntent);
                break;
            case ACTION_DOWNLOAD_NO_PRIVILEGES:
                resolvedDownloadNoPrivileges(
                        resolutionExtras.getBoolean(EuiccService.RESOLUTION_EXTRA_CONSENT),
                        callbackIntent);
                break;
            case ACTION_GET_DEFAULT_LIST_DEACTIVATE_SIM:
                resolvedGetDefaultListDeactivateSim(
                        resolutionExtras.getBoolean(EuiccService.RESOLUTION_EXTRA_CONSENT),
                        callbackIntent);
                break;
            case ACTION_SWITCH_DEACTIVATE_SIM:
                resolvedSwitchDeactivateSim(
                        resolutionExtras.getBoolean(EuiccService.RESOLUTION_EXTRA_CONSENT),
                        callbackIntent);
                break;
            case ACTION_SWITCH_NO_PRIVILEGES:
                resolvedSwitchNoPrivileges(
                        resolutionExtras.getBoolean(EuiccService.RESOLUTION_EXTRA_CONSENT),
                        callbackIntent);
                break;
            default:
                Log.wtf(TAG, "Unknown action: " + mAction);
                break;
        }
    }

    private void resolvedGetMetadataDeactivateSim(
            boolean consent, PendingIntent callbackIntent) {
        if (consent) {
            // User has consented; perform the lookup, but this time, tell the LPA to deactivate any
            // required active SIMs.
            EuiccController.get().getDownloadableSubscriptionMetadata(
                    mDownloadableSubscription,
                    true /* forceDeactivateSim */,
                    mCallingPackage,
                    callbackIntent);
        } else {
            // User has not consented; fail the operation.
            fail(callbackIntent);
        }
    }

    private void resolvedDownloadDeactivateSim(
            boolean consent, PendingIntent callbackIntent) {
        if (consent) {
            // User has consented; perform the download, but this time, tell the LPA to deactivate
            // any required active SIMs.
            EuiccController.get().downloadSubscription(
                    mDownloadableSubscription,
                    mSwitchAfterDownload,
                    mCallingPackage,
                    true /* forceDeactivateSim */,
                    callbackIntent);
        } else {
            // User has not consented; fail the operation.
            fail(callbackIntent);
        }
    }

    private void resolvedDownloadNoPrivileges(boolean consent, PendingIntent callbackIntent) {
        if (consent) {
            // User has consented; perform the download with full privileges.
            long token = Binder.clearCallingIdentity();
            try {
                // Note: We turn on "forceDeactivateSim" here under the assumption that the
                // privilege prompt should also cover permission to deactivate an active SIM, as
                // the privilege prompt makes it clear that we're switching from the current
                // carrier.
                EuiccController.get().downloadSubscriptionPrivileged(
                        token,
                        mDownloadableSubscription,
                        mSwitchAfterDownload,
                        true /* forceDeactivateSim */,
                        mCallingPackage,
                        callbackIntent);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else {
            // User has not consented; fail the operation.
            fail(callbackIntent);
        }
    }

    private void resolvedGetDefaultListDeactivateSim(
            boolean consent, PendingIntent callbackIntent) {
        if (consent) {
            // User has consented; perform the lookup, but this time, tell the LPA to deactivate any
            // required active SIMs.
            EuiccController.get().getDefaultDownloadableSubscriptionList(
                    true /* forceDeactivateSim */, mCallingPackage, callbackIntent);
        } else {
            // User has not consented; fail the operation.
            fail(callbackIntent);
        }
    }

    private void resolvedSwitchDeactivateSim(
            boolean consent, PendingIntent callbackIntent) {
        if (consent) {
            // User has consented; perform the switch, but this time, tell the LPA to deactivate any
            // required active SIMs.
            EuiccController.get().switchToSubscription(
                    mSubscriptionId,
                    true /* forceDeactivateSim */,
                    mCallingPackage,
                    callbackIntent);
        } else {
            // User has not consented; fail the operation.
            fail(callbackIntent);
        }
    }

    private void resolvedSwitchNoPrivileges(boolean consent, PendingIntent callbackIntent) {
        if (consent) {
            // User has consented; perform the switch with full privileges.
            long token = Binder.clearCallingIdentity();
            try {
                // Note: We turn on "forceDeactivateSim" here under the assumption that the
                // privilege prompt should also cover permission to deactivate an active SIM, as
                // the privilege prompt makes it clear that we're switching from the current
                // carrier. Also note that in practice, we'd need to deactivate the active SIM to
                // even reach this point, because we cannot fetch the metadata needed to check the
                // privileges without doing so.
                EuiccController.get().switchToSubscriptionPrivileged(
                        token,
                        mSubscriptionId,
                        true /* forceDeactivateSim */,
                        mCallingPackage,
                        callbackIntent);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else {
            // User has not consented; fail the operation.
            fail(callbackIntent);
        }
    }

    private static void fail(PendingIntent callbackIntent) {
        EuiccController.get().sendResult(
                callbackIntent,
                EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,
                null /* extrasIntent */);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
