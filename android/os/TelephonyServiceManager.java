/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.os;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import static com.android.internal.telephony.flags.Flags.FLAG_ENABLE_PHONE_NUMBER_PARSING_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;

/**
 * Provides a way to register and obtain the system service binder objects managed by the telephony
 * service.
 *
 * <p>Only the telephony mainline module will be able to access an instance of this class.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
@FlaggedApi(FLAG_ENABLE_PHONE_NUMBER_PARSING_API)
public final class TelephonyServiceManager {

    /**
     * @hide
     */
    public TelephonyServiceManager() {
    }

    /**
     * A class that exposes the methods to register and obtain each system service.
     */
    public static final class ServiceRegisterer {
        private final String mServiceName;

        /**
         * @hide
         */
        public ServiceRegisterer(String serviceName) {
            mServiceName = serviceName;
        }

        /**
         * Publish the service so it is accessible to other services and apps.
         *
         * @param service the service object
         *
         * @hide
         *
         */
        // TODO: Replaced all callers to use publishBinderService().
        public void register(@NonNull IBinder service) {
            ServiceManager.addService(mServiceName, service);
        }

        /**
         * Publish the service so it is accessible to other services and apps.
         *
         * @param service the service object
         */
        public void publishBinderService(@NonNull IBinder service) {
            ServiceManager.addService(mServiceName, service);
        }

        /**
         * Get the system server binding object for a service.
         *
         * <p>This blocks until the service instance is ready,
         * or a timeout happens, in which case it returns null.
         *
         * @return The binder object, or null if the service is not found or a timeout occurs.
         */
        @Nullable
        public IBinder get() {
            return ServiceManager.getService(mServiceName);
        }

        /**
         * Get the system server binding object for a service.
         *
         * <p>This blocks until the service instance is ready,
         * or a timeout happens, in which case it throws {@link ServiceNotFoundException}.
         *
         * @hide
         */
        @NonNull
        public IBinder getOrThrow() throws ServiceNotFoundException {
            try {
                return ServiceManager.getServiceOrThrow(mServiceName);
            } catch (ServiceManager.ServiceNotFoundException e) {
                throw new ServiceNotFoundException(mServiceName);
            }
        }

        /**
         * Get the system server binding object for a service. If the specified service is
         * not available, it returns null.
         *
         * @hide
         */
        @Nullable
        public IBinder tryGet() {
            return ServiceManager.checkService(mServiceName);
        }
    }

    /**
     * See {@link ServiceRegisterer#getOrThrow}.
     *
     * @hide
     */
    public static class ServiceNotFoundException extends ServiceManager.ServiceNotFoundException {
        /**
         * Constructor.
         *
         * @param name the name of the binder service that cannot be found.
         *
         */
        public ServiceNotFoundException(@NonNull String name) {
            super(name);
        }
    }

    /**
     * Returns {@link ServiceRegisterer} for the "telephony" service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getTelephonyServiceRegisterer() {
        return new ServiceRegisterer(Context.TELEPHONY_SERVICE);
    }

    /**
     * Returns {@link ServiceRegisterer} for the telephony IMS service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getTelephonyImsServiceRegisterer() {
        return new ServiceRegisterer(Context.TELEPHONY_IMS_SERVICE);
    }

    /**
     * Returns {@link ServiceRegisterer} for the telephony RCS message service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getTelephonyRcsMessageServiceRegisterer() {
        return new ServiceRegisterer(Context.TELEPHONY_RCS_MESSAGE_SERVICE);
    }

    /**
     * Returns {@link ServiceRegisterer} for the subscription service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getSubscriptionServiceRegisterer() {
        return new ServiceRegisterer("isub");
    }

    /**
     * Returns {@link ServiceRegisterer} for the phone sub service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getPhoneSubServiceRegisterer() {
        return new ServiceRegisterer("iphonesubinfo");
    }

    /**
     * Returns {@link ServiceRegisterer} for the opportunistic network service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getOpportunisticNetworkServiceRegisterer() {
        return new ServiceRegisterer("ions");
    }

    /**
     * Returns {@link ServiceRegisterer} for the carrier config service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getCarrierConfigServiceRegisterer() {
        return new ServiceRegisterer(Context.CARRIER_CONFIG_SERVICE);
    }

    /**
     * Returns {@link ServiceRegisterer} for the "SMS" service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getSmsServiceRegisterer() {
        return new ServiceRegisterer("isms");
    }

    /**
     * Returns {@link ServiceRegisterer} for the eUICC controller service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getEuiccControllerService() {
        return new ServiceRegisterer("econtroller");
    }

    /**
     * Returns {@link ServiceRegisterer} for the eUICC card controller service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getEuiccCardControllerServiceRegisterer() {
        return new ServiceRegisterer("euicc_card_controller");
    }

    /**
     * Returns {@link ServiceRegisterer} for the ICC phone book service.
     *
     * @hide
     */
    @NonNull
    public ServiceRegisterer getIccPhoneBookServiceRegisterer() {
        return new ServiceRegisterer("simphonebook");
    }

    /**
     * Returns {@link ServiceRegisterer} for the Phone Number Service.
     */
    @NonNull
    public ServiceRegisterer getPhoneNumberServiceRegisterer() {
        return new ServiceRegisterer(Context.TELEPHONY_PHONE_NUMBER_SERVICE);
    }
}
