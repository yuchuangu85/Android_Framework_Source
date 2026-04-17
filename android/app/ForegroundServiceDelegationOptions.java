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

package android.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A service module such as MediaSessionService, VOIP, Camera, Microphone, Location can ask
 * ActivityManagerService to start a foreground service delegate on behalf of the actual app,
 * by which the client app's process state can be promoted to a higher process state, equivalent to
 * that of a foreground service, which may be higher than the app's actual process state if the app
 * is in the background. This can help keep the app in memory and potentially give it extra
 * run-time. The app does not need to define an actual service component nor add it to their
 * manifest file.
 *
 * @hide
 */
public class ForegroundServiceDelegationOptions {

    /**
     * Used mainly for internal logging when a better type is not available or applicable.
     */
    public static final int DELEGATION_SERVICE_DEFAULT = 0;

    /**
     * Used when called through Shell Command.
     */
    public static final int DELEGATION_SERVICE_SPECIAL_USE = 1;

    /**
     * Used to identify delegation service related to a phone call.
     * @see #DELEGATION_SERVICE_VOIP
     * @deprecated Use a more specific type instead.
     */
    @Deprecated
    public static final int DELEGATION_SERVICE_PHONE_CALL = 2;

    /**
     * Used to identify delegation service related to a voip call.
     */
    public static final int DELEGATION_SERVICE_VOIP = 3;


    @IntDef(flag = false, prefix = { "DELEGATION_SERVICE_" }, value = {
            DELEGATION_SERVICE_DEFAULT,
            DELEGATION_SERVICE_SPECIAL_USE,
            DELEGATION_SERVICE_PHONE_CALL,
            DELEGATION_SERVICE_VOIP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DelegationService {}

    // The actual app's PID
    public final int mClientPid;
    // The actual app's UID
    public final int mClientUid;
    // The actual app's package name
    @NonNull
    public final String mClientPackageName;
    // The actual app's app thread
    @Nullable
    public final IApplicationThread mClientAppThread;
    public final boolean mSticky; // Is it a sticky service

    // The delegation service's instance name which is to identify the delegate.
    @NonNull
    public String mClientInstanceName;
    // The foreground service types it consists of.
    public final int mForegroundServiceTypes;
    /**
     * The service's name such as MediaSessionService, VOIP, Camera, Microphone, Location. This is
     * the internal module's name which actually starts the FGS delegate on behalf of the client
     * app.
     */
    public final @DelegationService int mDelegationService;

    /**
     * The optional notification Id of the foreground service delegation.
     */
    public final int mClientNotificationId;

    /**
     * The optional notification of the foreground service delegation.
     */
    public final @Nullable Notification mClientNotification;

    public ForegroundServiceDelegationOptions(int clientPid,
            int clientUid,
            @NonNull String clientPackageName,
            @NonNull IApplicationThread clientAppThread,
            boolean isSticky,
            @NonNull String clientInstanceName,
            int foregroundServiceTypes,
            @DelegationService int delegationService) {
        this(clientPid, clientUid, clientPackageName, clientAppThread, isSticky,
                clientInstanceName, foregroundServiceTypes, delegationService,
                0 /* notificationId */, null /* notification */);
    }

    public ForegroundServiceDelegationOptions(int clientPid,
            int clientUid,
            @NonNull String clientPackageName,
            @NonNull IApplicationThread clientAppThread,
            boolean isSticky,
            @NonNull String clientInstanceName,
            int foregroundServiceTypes,
            @DelegationService int delegationService,
            int clientNotificationId,
            @Nullable Notification clientNotification) {
        if (delegationService == DELEGATION_SERVICE_DEFAULT) {
            throw new IllegalArgumentException(
                "Default is not allowed to be passed in. "
                + "Use a more specific Delegation Service Identifier!");
        }
        mClientPid = clientPid;
        mClientUid = clientUid;
        mClientPackageName = clientPackageName;
        mClientAppThread = clientAppThread;
        mSticky = isSticky;
        mClientInstanceName = clientInstanceName;
        mForegroundServiceTypes = foregroundServiceTypes;
        mDelegationService = delegationService;
        mClientNotificationId = clientNotificationId;
        mClientNotification = clientNotification;
    }

    /**
     * A service delegates a foreground service state to a clientUID using a instanceName.
     * This delegation is uniquely identified by
     * mDelegationService/mClientUid/mClientPid/mClientInstanceName
     */
    public boolean isSameDelegate(ForegroundServiceDelegationOptions that) {
        return this.mDelegationService == that.mDelegationService
                && this.mClientUid == that.mClientUid
                && this.mClientPid == that.mClientPid
                && this.mClientInstanceName.equals(that.mClientInstanceName);
    }

    /**
     * Construct a component name for this delegate.
     */
    public ComponentName getComponentName() {
        return new ComponentName(mClientPackageName, serviceCodeToString(mDelegationService)
                + ":" + mClientInstanceName);
    }

    /**
     * Get string description of this delegate options.
     */
    public String getDescription() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("ForegroundServiceDelegate{")
                .append("package:")
                .append(mClientPackageName)
                .append(",")
                .append("service:")
                .append(serviceCodeToString(mDelegationService))
                .append(",")
                .append("uid:")
                .append(mClientUid)
                .append(",")
                .append("pid:")
                .append(mClientPid)
                .append(",")
                .append("instance:")
                .append(mClientInstanceName)
                .append("}");
        return sb.toString();
    }

    /**
     * Map the integer service code to string name.
     * @param serviceCode
     * @return
     */
    public static String serviceCodeToString(@DelegationService int serviceCode) {
        switch (serviceCode) {
            case DELEGATION_SERVICE_DEFAULT:
                return "DEFAULT";
            case DELEGATION_SERVICE_SPECIAL_USE:
                return "SPECIAL_USE";
            case DELEGATION_SERVICE_PHONE_CALL:
                return "PHONE_CALL";
            case DELEGATION_SERVICE_VOIP:
                return "VOIP";
            default:
                return "(unknown:" + serviceCode + ")";
        }
    }

    /**
     * The helper class to build the instance of {@link ForegroundServiceDelegate}.
     *
     * @hide
     */
    public static class Builder {
        int mClientPid; // The actual app PID
        int mClientUid; // The actual app UID
        String mClientPackageName; // The actual app's package name
        int mClientNotificationId; // The actual app's notification id
        Notification mClientNotification; // The actual app's notification
        IApplicationThread mClientAppThread; // The actual app's app thread
        boolean mSticky; // Is it a sticky service
        String mClientInstanceName; // The delegation service instance name
        int mForegroundServiceTypes; // The foreground service types it consists of
        @DelegationService int mDelegationService; // The internal service's name, i.e. VOIP

        /**
         * Set the client app's PID.
         */
        public Builder setClientPid(int clientPid) {
            mClientPid = clientPid;
            return this;
        }

        /**
         * Set the client app's UID.
         */
        public Builder setClientUid(int clientUid) {
            mClientUid = clientUid;
            return this;
        }

        /**
         * Set the client app's package name.
         */
        public Builder setClientPackageName(@NonNull String clientPackageName) {
            mClientPackageName = clientPackageName;
            return this;
        }

        /**
         * Set the notification from the client app.
         */
        public Builder setClientNotification(int clientNotificationId,
                @Nullable Notification clientNotification) {
            mClientNotificationId = clientNotificationId;
            mClientNotification = clientNotification;
            return this;
        }

        /**
         * Set the client app's application thread.
         */
        public Builder setClientAppThread(@NonNull IApplicationThread clientAppThread) {
            mClientAppThread = clientAppThread;
            return this;
        }

        /**
         * Set the client instance of this service.
         */
        public Builder setClientInstanceName(@NonNull String clientInstanceName) {
            mClientInstanceName = clientInstanceName;
            return this;
        }

        /**
         * Set stickiness of this service.
         */
        public Builder setSticky(boolean isSticky) {
            mSticky = isSticky;
            return this;
        }

        /**
         * Set the foreground service type.
         */
        public Builder setForegroundServiceTypes(int foregroundServiceTypes) {
            mForegroundServiceTypes = foregroundServiceTypes;
            return this;
        }

        /**
         * Set the delegation service type.
         */
        public Builder setDelegationService(@DelegationService int delegationService) {
            mDelegationService = delegationService;
            return this;
        }

        /**
         * @return An instance of {@link ForegroundServiceDelegationOptions}.
         */
        public ForegroundServiceDelegationOptions build() {
            return new ForegroundServiceDelegationOptions(mClientPid,
                mClientUid,
                mClientPackageName,
                mClientAppThread,
                mSticky,
                mClientInstanceName,
                mForegroundServiceTypes,
                mDelegationService,
                mClientNotificationId,
                mClientNotification
            );
        }
    }
}
