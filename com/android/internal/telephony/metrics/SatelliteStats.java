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

package com.android.internal.telephony.metrics;

import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;
import static android.telephony.satellite.NtnSignalStrength.NTN_SIGNAL_STRENGTH_NONE;

import static com.android.internal.telephony.metrics.PersistAtomsStorage.SATELLITE_SESSION_GAP_INVALID_SEC;
import static com.android.internal.telephony.satellite.SatelliteConstants.TRIGGERING_EVENT_UNKNOWN;

import android.telephony.TelephonyManager;
import android.telephony.satellite.NtnSignalStrength;
import android.telephony.satellite.SatelliteManager;

import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.nano.PersistAtomsProto.CarrierRoamingSatelliteControllerStats;
import com.android.internal.telephony.nano.PersistAtomsProto.CarrierRoamingSatelliteSession;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteAccessController;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteConfigUpdater;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteController;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteEntitlement;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteIncomingDatagram;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteOutgoingDatagram;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteProvision;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteSession;
import com.android.internal.telephony.nano.PersistAtomsProto.SatelliteSosMessageRecommender;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.telephony.Rlog;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Tracks Satellite metrics for each phone */
public class SatelliteStats {
    private static final String TAG = SatelliteStats.class.getSimpleName();
    private static final boolean DBG = false;

    private final PersistAtomsStorage mAtomsStorage =
            PhoneFactory.getMetricsCollector().getAtomsStorage();

    private static SatelliteStats sInstance = null;

    /** Gets the instance of SatelliteStats */
    public static SatelliteStats getInstance() {
        if (sInstance == null) {
            Rlog.d(TAG, "SatelliteStats created.");
            synchronized (SatelliteStats.class) {
                sInstance = new SatelliteStats();
            }
        }
        return sInstance;
    }

    /**
     * A data class to contain whole component of {@link SatelliteController) atom.
     * Refer to {@link #onSatelliteControllerMetrics(SatelliteControllerParams)}.
     */
    public static class SatelliteControllerParams {
        private final int mCountOfSatelliteServiceEnablementsSuccess;
        private final int mCountOfSatelliteServiceEnablementsFail;
        private final int mCountOfOutgoingDatagramSuccess;
        private final int mCountOfOutgoingDatagramFail;
        private final int mCountOfIncomingDatagramSuccess;
        private final int mCountOfIncomingDatagramFail;
        private final int mCountOfDatagramTypeSosSmsSuccess;
        private final int mCountOfDatagramTypeSosSmsFail;
        private final int mCountOfDatagramTypeLocationSharingSuccess;
        private final int mCountOfDatagramTypeLocationSharingFail;
        private final int mCountOfProvisionSuccess;
        private final int mCountOfProvisionFail;
        private final int mCountOfDeprovisionSuccess;
        private final int mCountOfDeprovisionFail;
        private final int mTotalServiceUptimeSec;
        private final int mTotalBatteryConsumptionPercent;
        private final int mTotalBatteryChargedTimeSec;
        private final int mCountOfDemoModeSatelliteServiceEnablementsSuccess;
        private final int mCountOfDemoModeSatelliteServiceEnablementsFail;
        private final int mCountOfDemoModeOutgoingDatagramSuccess;
        private final int mCountOfDemoModeOutgoingDatagramFail;
        private final int mCountOfDemoModeIncomingDatagramSuccess;
        private final int mCountOfDemoModeIncomingDatagramFail;
        private final int mCountOfDatagramTypeKeepAliveSuccess;
        private final int mCountOfDatagramTypeKeepAliveFail;
        private final int mCountOfAllowedSatelliteAccess;
        private final int mCountOfDisallowedSatelliteAccess;
        private final int mCountOfSatelliteAccessCheckFail;
        private static boolean sIsProvisioned;
        private static int sCarrierId = UNKNOWN_CARRIER_ID;
        private final int mCountOfSatelliteAllowedStateChangedEvents;
        private final int mCountOfSuccessfulLocationQueries;
        private final int mCountOfFailedLocationQueries;
        private final int mCountOfP2PSmsAvailableNotificationShown;
        private final int mCountOfP2PSmsAvailableNotificationRemoved;
        private static boolean sIsNtnOnlyCarrier;
        private static int sVersionOfSatelliteAccessConfig;
        private final int mCountOfIncomingDatagramTypeSosSmsSuccess;
        private final int mCountOfIncomingDatagramTypeSosSmsFail;
        private final int mCountOfOutgoingDatagramTypeSmsSuccess;
        private final int mCountOfOutgoingDatagramTypeSmsFail;
        private final int mCountOfIncomingDatagramTypeSmsSuccess;
        private final int mCountOfIncomingDatagramTypeSmsFail;
        private static int sCarrierRoamingSatelliteConfigVersion;
        private static int sMaxAllowedDataMode;
        private static @SatelliteConstants.SatelliteGlobalConnectType int sSupportedConnectionMode;

        private SatelliteControllerParams(Builder builder) {
            this.mCountOfSatelliteServiceEnablementsSuccess =
                    builder.mCountOfSatelliteServiceEnablementsSuccess;
            this.mCountOfSatelliteServiceEnablementsFail =
                    builder.mCountOfSatelliteServiceEnablementsFail;
            this.mCountOfOutgoingDatagramSuccess = builder.mCountOfOutgoingDatagramSuccess;
            this.mCountOfOutgoingDatagramFail = builder.mCountOfOutgoingDatagramFail;
            this.mCountOfIncomingDatagramSuccess = builder.mCountOfIncomingDatagramSuccess;
            this.mCountOfIncomingDatagramFail = builder.mCountOfIncomingDatagramFail;
            this.mCountOfDatagramTypeSosSmsSuccess = builder.mCountOfDatagramTypeSosSmsSuccess;
            this.mCountOfDatagramTypeSosSmsFail = builder.mCountOfDatagramTypeSosSmsFail;
            this.mCountOfDatagramTypeLocationSharingSuccess =
                    builder.mCountOfDatagramTypeLocationSharingSuccess;
            this.mCountOfDatagramTypeLocationSharingFail =
                    builder.mCountOfDatagramTypeLocationSharingFail;
            this.mCountOfProvisionSuccess = builder.mCountOfProvisionSuccess;
            this.mCountOfProvisionFail = builder.mCountOfProvisionFail;
            this.mCountOfDeprovisionSuccess = builder.mCountOfDeprovisionSuccess;
            this.mCountOfDeprovisionFail = builder.mCountOfDeprovisionFail;
            this.mTotalServiceUptimeSec = builder.mTotalServiceUptimeSec;
            this.mTotalBatteryConsumptionPercent = builder.mTotalBatteryConsumptionPercent;
            this.mTotalBatteryChargedTimeSec = builder.mTotalBatteryChargedTimeSec;
            this.mCountOfDemoModeSatelliteServiceEnablementsSuccess =
                    builder.mCountOfDemoModeSatelliteServiceEnablementsSuccess;
            this.mCountOfDemoModeSatelliteServiceEnablementsFail =
                    builder.mCountOfDemoModeSatelliteServiceEnablementsFail;
            this.mCountOfDemoModeOutgoingDatagramSuccess =
                    builder.mCountOfDemoModeOutgoingDatagramSuccess;
            this.mCountOfDemoModeOutgoingDatagramFail =
                    builder.mCountOfDemoModeOutgoingDatagramFail;
            this.mCountOfDemoModeIncomingDatagramSuccess =
                    builder.mCountOfDemoModeIncomingDatagramSuccess;
            this.mCountOfDemoModeIncomingDatagramFail =
                    builder.mCountOfDemoModeIncomingDatagramFail;
            this.mCountOfDatagramTypeKeepAliveSuccess =
                    builder.mCountOfDatagramTypeKeepAliveSuccess;
            this.mCountOfDatagramTypeKeepAliveFail =
                    builder.mCountOfDatagramTypeKeepAliveFail;
            this.mCountOfAllowedSatelliteAccess =
                    builder.mCountOfAllowedSatelliteAccess;
            this.mCountOfDisallowedSatelliteAccess =
                    builder.mCountOfDisallowedSatelliteAccess;
            this.mCountOfSatelliteAccessCheckFail =
                    builder.mCountOfSatelliteAccessCheckFail;

            // isProvisioned value should be updated only when it is meaningful.
            if (builder.mIsProvisioned.isPresent()) {
                this.sIsProvisioned = builder.mIsProvisioned.get();
            }

            // Carrier ID value should be updated only when it is meaningful.
            if (builder.mCarrierId.isPresent()) {
                this.sCarrierId = builder.mCarrierId.get();
            }

            // Global Connect Type value should be updated only when it is meaningful.
            if (builder.mSupportedConnectionMode.isPresent()) {
                this.sSupportedConnectionMode = builder.mSupportedConnectionMode.get();
            }

            this.mCountOfSatelliteAllowedStateChangedEvents =
                    builder.mCountOfSatelliteAllowedStateChangedEvents;
            this.mCountOfSuccessfulLocationQueries =
                    builder.mCountOfSuccessfulLocationQueries;
            this.mCountOfFailedLocationQueries =
                    builder.mCountOfFailedLocationQueries;
            this.mCountOfP2PSmsAvailableNotificationShown =
                    builder.mCountOfP2PSmsAvailableNotificationShown;
            this.mCountOfP2PSmsAvailableNotificationRemoved =
                    builder.mCountOfP2PSmsAvailableNotificationRemoved;


            // Ntn only carrier value should be updated only when it is meaningful.
            if (builder.mIsNtnOnlyCarrier.isPresent()) {
                this.sIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier.get();
            }
            // version satellite access config value should be updated only when it is meaningful.
            if (builder.mVersionOfSatelliteAccessConfig.isPresent()) {
                this.sVersionOfSatelliteAccessConfig =
                        builder.mVersionOfSatelliteAccessConfig.get();
            }

            this.mCountOfIncomingDatagramTypeSosSmsSuccess =
                    builder.mCountOfIncomingDatagramTypeSosSmsSuccess;
            this.mCountOfIncomingDatagramTypeSosSmsFail =
                    builder.mCountOfIncomingDatagramTypeSosSmsFail;
            this.mCountOfOutgoingDatagramTypeSmsSuccess =
                    builder.mCountOfOutgoingDatagramTypeSmsSuccess;
            this.mCountOfOutgoingDatagramTypeSmsFail = builder.mCountOfOutgoingDatagramTypeSmsFail;
            this.mCountOfIncomingDatagramTypeSmsSuccess =
                    builder.mCountOfIncomingDatagramTypeSmsSuccess;
            this.mCountOfIncomingDatagramTypeSmsFail = builder.mCountOfIncomingDatagramTypeSmsFail;

            // carrier roaming satellite config version should be updated only when it's meaningful.
            if (builder.mCarrierRoamingSatelliteConfigVersion.isPresent()) {
                this.sCarrierRoamingSatelliteConfigVersion =
                        builder.mCarrierRoamingSatelliteConfigVersion.get();
            }
            // max allowed data mode value should be updated only when it is meaningful.
            if (builder.mMaxAllowedDataMode.isPresent()) {
                this.sMaxAllowedDataMode = builder.mMaxAllowedDataMode.get();
            }
        }

        public int getCountOfSatelliteServiceEnablementsSuccess() {
            return mCountOfSatelliteServiceEnablementsSuccess;
        }

        public int getCountOfSatelliteServiceEnablementsFail() {
            return mCountOfSatelliteServiceEnablementsFail;
        }

        public int getCountOfOutgoingDatagramSuccess() {
            return mCountOfOutgoingDatagramSuccess;
        }

        public int getCountOfOutgoingDatagramFail() {
            return mCountOfOutgoingDatagramFail;
        }

        public int getCountOfIncomingDatagramSuccess() {
            return mCountOfIncomingDatagramSuccess;
        }

        public int getCountOfIncomingDatagramFail() {
            return mCountOfIncomingDatagramFail;
        }

        public int getCountOfDatagramTypeSosSmsSuccess() {
            return mCountOfDatagramTypeSosSmsSuccess;
        }

        public int getCountOfDatagramTypeSosSmsFail() {
            return mCountOfDatagramTypeSosSmsFail;
        }

        public int getCountOfDatagramTypeLocationSharingSuccess() {
            return mCountOfDatagramTypeLocationSharingSuccess;
        }

        public int getCountOfDatagramTypeLocationSharingFail() {
            return mCountOfDatagramTypeLocationSharingFail;
        }

        public int getCountOfProvisionSuccess() {
            return mCountOfProvisionSuccess;
        }

        public int getCountOfProvisionFail() {
            return mCountOfProvisionFail;
        }

        public int getCountOfDeprovisionSuccess() {
            return mCountOfDeprovisionSuccess;
        }

        public int getCountOfDeprovisionFail() {
            return mCountOfDeprovisionFail;
        }

        public int getTotalServiceUptimeSec() {
            return mTotalServiceUptimeSec;
        }

        public int getTotalBatteryConsumptionPercent() {
            return mTotalBatteryConsumptionPercent;
        }

        public int getTotalBatteryChargedTimeSec() {
            return mTotalBatteryChargedTimeSec;
        }

        public int getCountOfDemoModeSatelliteServiceEnablementsSuccess() {
            return mCountOfDemoModeSatelliteServiceEnablementsSuccess;
        }

        public int getCountOfDemoModeSatelliteServiceEnablementsFail() {
            return mCountOfDemoModeSatelliteServiceEnablementsFail;
        }

        public int getCountOfDemoModeOutgoingDatagramSuccess() {
            return mCountOfDemoModeOutgoingDatagramSuccess;
        }

        public int getCountOfDemoModeOutgoingDatagramFail() {
            return mCountOfDemoModeOutgoingDatagramFail;
        }

        public int getCountOfDemoModeIncomingDatagramSuccess() {
            return mCountOfDemoModeIncomingDatagramSuccess;
        }

        public int getCountOfDemoModeIncomingDatagramFail() {
            return mCountOfDemoModeIncomingDatagramFail;
        }

        public int getCountOfDatagramTypeKeepAliveSuccess() {
            return mCountOfDatagramTypeKeepAliveSuccess;
        }

        public int getCountOfDatagramTypeKeepAliveFail() {
            return mCountOfDatagramTypeKeepAliveFail;
        }

        public int getCountOfAllowedSatelliteAccess() {
            return mCountOfAllowedSatelliteAccess;
        }

        public int getCountOfDisallowedSatelliteAccess() {
            return mCountOfDisallowedSatelliteAccess;
        }

        public int getCountOfSatelliteAccessCheckFail() {
            return mCountOfSatelliteAccessCheckFail;
        }

        public static boolean isProvisioned() {
            return sIsProvisioned;
        }

        public static int getCarrierId() {
            return sCarrierId;
        }

        public int getCountOfSatelliteAllowedStateChangedEvents() {
            return mCountOfSatelliteAllowedStateChangedEvents;
        }

        public int getCountOfSuccessfulLocationQueries() {
            return mCountOfSuccessfulLocationQueries;
        }

        public int getCountOfFailedLocationQueries() {
            return mCountOfFailedLocationQueries;
        }

        public int getCountOfP2PSmsAvailableNotificationShown() {
            return mCountOfP2PSmsAvailableNotificationShown;
        }

        public int getCountOfP2PSmsAvailableNotificationRemoved() {
            return mCountOfP2PSmsAvailableNotificationRemoved;
        }

        public static boolean isNtnOnlyCarrier() {
            return sIsNtnOnlyCarrier;
        }

        public static int getVersionSatelliteAccessConfig() {
            return sVersionOfSatelliteAccessConfig;
        }

        public int getCountOfIncomingDatagramTypeSosSmsSuccess() {
            return mCountOfIncomingDatagramTypeSosSmsSuccess;
        }

        public int getCountOfIncomingDatagramTypeSosSmsFail() {
            return mCountOfIncomingDatagramTypeSosSmsFail;
        }

        public int getCountOfOutgoingDatagramTypeSmsSuccess() {
            return mCountOfOutgoingDatagramTypeSmsSuccess;
        }

        public int getCountOfOutgoingDatagramTypeSmsFail() {
            return mCountOfOutgoingDatagramTypeSmsFail;
        }

        public int getCountOfIncomingDatagramTypeSmsSuccess() {
            return mCountOfIncomingDatagramTypeSmsSuccess;
        }

        public int getCountOfIncomingDatagramTypeSmsFail() {
            return mCountOfIncomingDatagramTypeSmsFail;
        }

        public static int getCarrierRoamingSatelliteConfigVersion() {
            return sCarrierRoamingSatelliteConfigVersion;
        }

        public static int getMaxAllowedDataMode() {
            return sMaxAllowedDataMode;
        }

        public static int getSupportedConnectionMode() {
            return sSupportedConnectionMode;
        }

        /**
         * A builder class to create {@link SatelliteControllerParams} data structure class
         */
        public static class Builder {
            private int mCountOfSatelliteServiceEnablementsSuccess = 0;
            private int mCountOfSatelliteServiceEnablementsFail = 0;
            private int mCountOfOutgoingDatagramSuccess = 0;
            private int mCountOfOutgoingDatagramFail = 0;
            private int mCountOfIncomingDatagramSuccess = 0;
            private int mCountOfIncomingDatagramFail = 0;
            private int mCountOfDatagramTypeSosSmsSuccess = 0;
            private int mCountOfDatagramTypeSosSmsFail = 0;
            private int mCountOfDatagramTypeLocationSharingSuccess = 0;
            private int mCountOfDatagramTypeLocationSharingFail = 0;
            private int mCountOfProvisionSuccess;
            private int mCountOfProvisionFail;
            private int mCountOfDeprovisionSuccess;
            private int mCountOfDeprovisionFail;
            private int mTotalServiceUptimeSec = 0;
            private int mTotalBatteryConsumptionPercent = 0;
            private int mTotalBatteryChargedTimeSec = 0;
            private int mCountOfDemoModeSatelliteServiceEnablementsSuccess = 0;
            private int mCountOfDemoModeSatelliteServiceEnablementsFail = 0;
            private int mCountOfDemoModeOutgoingDatagramSuccess = 0;
            private int mCountOfDemoModeOutgoingDatagramFail = 0;
            private int mCountOfDemoModeIncomingDatagramSuccess = 0;
            private int mCountOfDemoModeIncomingDatagramFail = 0;
            private int mCountOfDatagramTypeKeepAliveSuccess = 0;
            private int mCountOfDatagramTypeKeepAliveFail = 0;
            private int mCountOfAllowedSatelliteAccess = 0;
            private int mCountOfDisallowedSatelliteAccess = 0;
            private int mCountOfSatelliteAccessCheckFail = 0;
            private Optional<Boolean> mIsProvisioned = Optional.empty();
            private Optional<Integer> mCarrierId = Optional.empty();
            private Optional<Integer> mSupportedConnectionMode = Optional.empty();
            private int mCountOfSatelliteAllowedStateChangedEvents = 0;
            private int mCountOfSuccessfulLocationQueries = 0;
            private int mCountOfFailedLocationQueries = 0;
            private int mCountOfP2PSmsAvailableNotificationShown = 0;
            private int mCountOfP2PSmsAvailableNotificationRemoved = 0;
            private Optional<Boolean> mIsNtnOnlyCarrier = Optional.empty();
            private Optional<Integer> mVersionOfSatelliteAccessConfig = Optional.empty();
            private int mCountOfIncomingDatagramTypeSosSmsSuccess;
            private int mCountOfIncomingDatagramTypeSosSmsFail;
            private int mCountOfOutgoingDatagramTypeSmsSuccess;
            private int mCountOfOutgoingDatagramTypeSmsFail;
            private int mCountOfIncomingDatagramTypeSmsSuccess;
            private int mCountOfIncomingDatagramTypeSmsFail;
            private Optional<Integer> mCarrierRoamingSatelliteConfigVersion = Optional.empty();
            private Optional<Integer> mMaxAllowedDataMode = Optional.empty();

            /**
             * Sets countOfSatelliteServiceEnablementsSuccess value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfSatelliteServiceEnablementsSuccess(
                    int countOfSatelliteServiceEnablementsSuccess) {
                this.mCountOfSatelliteServiceEnablementsSuccess =
                        countOfSatelliteServiceEnablementsSuccess;
                return this;
            }

            /**
             * Sets countOfSatelliteServiceEnablementsFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfSatelliteServiceEnablementsFail(
                    int countOfSatelliteServiceEnablementsFail) {
                this.mCountOfSatelliteServiceEnablementsFail =
                        countOfSatelliteServiceEnablementsFail;
                return this;
            }

            /**
             * Sets countOfOutgoingDatagramSuccess value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setCountOfOutgoingDatagramSuccess(int countOfOutgoingDatagramSuccess) {
                this.mCountOfOutgoingDatagramSuccess = countOfOutgoingDatagramSuccess;
                return this;
            }

            /**
             * Sets countOfOutgoingDatagramFail value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setCountOfOutgoingDatagramFail(int countOfOutgoingDatagramFail) {
                this.mCountOfOutgoingDatagramFail = countOfOutgoingDatagramFail;
                return this;
            }

            /**
             * Sets countOfIncomingDatagramSuccess value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setCountOfIncomingDatagramSuccess(int countOfIncomingDatagramSuccess) {
                this.mCountOfIncomingDatagramSuccess = countOfIncomingDatagramSuccess;
                return this;
            }

            /**
             * Sets countOfIncomingDatagramFail value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setCountOfIncomingDatagramFail(int countOfIncomingDatagramFail) {
                this.mCountOfIncomingDatagramFail = countOfIncomingDatagramFail;
                return this;
            }

            /**
             * Sets countOfDatagramTypeSosSmsSuccess value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setCountOfDatagramTypeSosSmsSuccess(
                    int countOfDatagramTypeSosSmsSuccess) {
                this.mCountOfDatagramTypeSosSmsSuccess = countOfDatagramTypeSosSmsSuccess;
                return this;
            }

            /**
             * Sets countOfDatagramTypeSosSmsFail value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setCountOfDatagramTypeSosSmsFail(int countOfDatagramTypeSosSmsFail) {
                this.mCountOfDatagramTypeSosSmsFail = countOfDatagramTypeSosSmsFail;
                return this;
            }

            /**
             * Sets countOfDatagramTypeLocationSharingSuccess value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfDatagramTypeLocationSharingSuccess(
                    int countOfDatagramTypeLocationSharingSuccess) {
                this.mCountOfDatagramTypeLocationSharingSuccess =
                        countOfDatagramTypeLocationSharingSuccess;
                return this;
            }

            /**
             * Sets countOfDatagramTypeLocationSharingFail value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfDatagramTypeLocationSharingFail(
                    int countOfDatagramTypeLocationSharingFail) {
                this.mCountOfDatagramTypeLocationSharingFail =
                        countOfDatagramTypeLocationSharingFail;
                return this;
            }

            /**
             * Sets countOfProvisionSuccess value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfProvisionSuccess(int countOfProvisionSuccess) {
                this.mCountOfProvisionSuccess = countOfProvisionSuccess;
                return this;
            }

            /**
             * Sets countOfProvisionFail value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfProvisionFail(int countOfProvisionFail) {
                this.mCountOfProvisionFail = countOfProvisionFail;
                return this;
            }

            /**
             * Sets countOfDeprovisionSuccess value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfDeprovisionSuccess(int countOfDeprovisionSuccess) {
                this.mCountOfDeprovisionSuccess = countOfDeprovisionSuccess;
                return this;
            }

            /**
             * Sets countOfDeprovisionSuccess value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfDeprovisionFail(int countOfDeprovisionFail) {
                this.mCountOfDeprovisionFail = countOfDeprovisionFail;
                return this;
            }

            /**
             * Sets totalServiceUptimeSec value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setTotalServiceUptimeSec(int totalServiceUptimeSec) {
                this.mTotalServiceUptimeSec = totalServiceUptimeSec;
                return this;
            }

            /**
             * Sets totalBatteryConsumptionPercent value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setTotalBatteryConsumptionPercent(int totalBatteryConsumptionPercent) {
                this.mTotalBatteryConsumptionPercent = totalBatteryConsumptionPercent;
                return this;
            }

            /**
             * Sets totalBatteryChargedTimeSec value of {@link SatelliteController} atom then
             * returns Builder class
             */
            public Builder setTotalBatteryChargedTimeSec(int totalBatteryChargedTimeSec) {
                this.mTotalBatteryChargedTimeSec = totalBatteryChargedTimeSec;
                return this;
            }

            /**
             * Sets countOfDemoModeSatelliteServiceEnablementsSuccess value of
             * {@link SatelliteController} atom then returns Builder class
             */
            public Builder setCountOfDemoModeSatelliteServiceEnablementsSuccess(
                    int countOfDemoModeSatelliteServiceEnablementsSuccess) {
                this.mCountOfDemoModeSatelliteServiceEnablementsSuccess =
                        countOfDemoModeSatelliteServiceEnablementsSuccess;
                return this;
            }

            /**
             * Sets countOfDemoModeSatelliteServiceEnablementsFail value of
             * {@link SatelliteController} atom then returns Builder class
             */
            public Builder setCountOfDemoModeSatelliteServiceEnablementsFail(
                    int countOfDemoModeSatelliteServiceEnablementsFail) {
                this.mCountOfDemoModeSatelliteServiceEnablementsFail =
                        countOfDemoModeSatelliteServiceEnablementsFail;
                return this;
            }

            /**
             * Sets countOfDemoModeOutgoingDatagramSuccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDemoModeOutgoingDatagramSuccess(
                    int countOfDemoModeOutgoingDatagramSuccess) {
                this.mCountOfDemoModeOutgoingDatagramSuccess =
                        countOfDemoModeOutgoingDatagramSuccess;
                return this;
            }

            /**
             * Sets countOfDemoModeOutgoingDatagramFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDemoModeOutgoingDatagramFail(
                    int countOfDemoModeOutgoingDatagramFail) {
                this.mCountOfDemoModeOutgoingDatagramFail = countOfDemoModeOutgoingDatagramFail;
                return this;
            }

            /**
             * Sets countOfDemoModeIncomingDatagramSuccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDemoModeIncomingDatagramSuccess(
                    int countOfDemoModeIncomingDatagramSuccess) {
                this.mCountOfDemoModeIncomingDatagramSuccess =
                        countOfDemoModeIncomingDatagramSuccess;
                return this;
            }

            /**
             * Sets countOfDemoModeIncomingDatagramFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDemoModeIncomingDatagramFail(
                    int countOfDemoModeIncomingDatagramFail) {
                this.mCountOfDemoModeIncomingDatagramFail = countOfDemoModeIncomingDatagramFail;
                return this;
            }

            /**
             * Sets countOfDatagramTypeKeepAliveSuccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDatagramTypeKeepAliveSuccess(
                    int countOfDatagramTypeKeepAliveSuccess) {
                this.mCountOfDatagramTypeKeepAliveSuccess = countOfDatagramTypeKeepAliveSuccess;
                return this;
            }

            /**
             * Sets countOfDatagramTypeKeepAliveFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDatagramTypeKeepAliveFail(
                    int countOfDatagramTypeKeepAliveFail) {
                this.mCountOfDatagramTypeKeepAliveFail = countOfDatagramTypeKeepAliveFail;
                return this;
            }

            /**
             * Sets countOfAllowedSatelliteAccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfAllowedSatelliteAccess(
                    int countOfAllowedSatelliteAccess) {
                this.mCountOfAllowedSatelliteAccess =
                        countOfAllowedSatelliteAccess;
                return this;
            }

            /**
             * Sets countOfDisallowedSatelliteAccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfDisallowedSatelliteAccess(
                    int countOfDisallowedSatelliteAccess) {
                this.mCountOfDisallowedSatelliteAccess = countOfDisallowedSatelliteAccess;
                return this;
            }

            /**
             * Sets countOfSatelliteAccessCheckFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfSatelliteAccessCheckFail(
                    int countOfSatelliteAccessCheckFail) {
                this.mCountOfSatelliteAccessCheckFail = countOfSatelliteAccessCheckFail;
                return this;
            }

            /**
             * Sets isProvisioned value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setIsProvisioned(boolean isProvisioned) {
                this.mIsProvisioned = Optional.of(isProvisioned);
                return this;
            }

            /**
             * Sets Carrier ID value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = Optional.of(carrierId);
                return this;
            }

            /**
             * Sets countOfSatelliteAllowedStateChangedEvents value of {@link SatelliteController}
             * atom
             * then returns Builder class
             */
            public Builder setCountOfSatelliteAllowedStateChangedEvents(
                    int countOfSatelliteAllowedStateChangedEvents) {
                this.mCountOfSatelliteAllowedStateChangedEvents =
                        countOfSatelliteAllowedStateChangedEvents;
                return this;
            }

            /**
             * Sets countOfSuccessfulLocationQueries value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfSuccessfulLocationQueries(
                    int countOfSuccessfulLocationQueries) {
                this.mCountOfSuccessfulLocationQueries = countOfSuccessfulLocationQueries;
                return this;
            }

            /**
             * Sets countOfFailedLocationQueries value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfFailedLocationQueries(int countOfFailedLocationQueries) {
                this.mCountOfFailedLocationQueries = countOfFailedLocationQueries;
                return this;
            }

            /**
             * Sets countOfP2PSmsAvailableNotificationShown value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfP2PSmsAvailableNotificationShown(
                    int countOfP2PSmsAvailableNotificationShown) {
                this.mCountOfP2PSmsAvailableNotificationShown =
                        countOfP2PSmsAvailableNotificationShown;
                return this;
            }

            /**
             * Sets countOfP2PSmsAvailableNotificationRemoved value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfP2PSmsAvailableNotificationRemoved(
                    int countOfP2PSmsAvailableNotificationRemoved) {
                this.mCountOfP2PSmsAvailableNotificationRemoved =
                        countOfP2PSmsAvailableNotificationRemoved;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = Optional.of(isNtnOnlyCarrier);
                return this;
            }

            /**
             * Sets versionOfSatelliteAccessConfig value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setVersionOfSatelliteAccessControl(int version) {
                this.mVersionOfSatelliteAccessConfig = Optional.of(version);
                return this;
            }

            /**
             * Sets countOfIncomingDatagramTypeSosSmsSuccess value of {@link SatelliteController}
             * atom then returns Builder class
             */
            public Builder setCountOfIncomingDatagramTypeSosSmsSuccess(
                    int countOfIncomingDatagramTypeSosSmsSuccess) {
                this.mCountOfIncomingDatagramTypeSosSmsSuccess =
                        countOfIncomingDatagramTypeSosSmsSuccess;
                return this;
            }

            /**
             * Sets countOfIncomingDatagramTypeSosSmsFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfIncomingDatagramTypeSosSmsFail(
                    int countOfIncomingDatagramTypeSosSmsFail) {
                this.mCountOfIncomingDatagramTypeSosSmsFail = countOfIncomingDatagramTypeSosSmsFail;
                return this;
            }

            /**
             * Sets countOfOutgoingDatagramTypeSmsSuccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfOutgoingDatagramTypeSmsSuccess(
                    int countOfOutgoingDatagramTypeSmsSuccess) {
                this.mCountOfOutgoingDatagramTypeSmsSuccess = countOfOutgoingDatagramTypeSmsSuccess;
                return this;
            }

            /**
             * Sets countOfOutgoingDatagramTypeSmsFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfOutgoingDatagramTypeSmsFail(
                    int countOfOutgoingDatagramTypeSmsFail) {
                this.mCountOfOutgoingDatagramTypeSmsFail = countOfOutgoingDatagramTypeSmsFail;
                return this;
            }

            /**
             * Sets countOfIncomingDatagramTypeSmsSuccess value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfIncomingDatagramTypeSmsSuccess(
                    int countOfIncomingDatagramTypeSmsSuccess) {
                this.mCountOfIncomingDatagramTypeSmsSuccess = countOfIncomingDatagramTypeSmsSuccess;
                return this;
            }

            /**
             * Sets countOfIncomingDatagramTypeSmsFail value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCountOfIncomingDatagramTypeSmsFail(
                    int countOfIncomingDatagramTypeSmsFail) {
                this.mCountOfIncomingDatagramTypeSmsFail = countOfIncomingDatagramTypeSmsFail;
                return this;
            }

            /**
             * Sets carrier roaming satellite config version of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setCarrierRoamingSatelliteConfigVersion(
                    int carrierRoamingSatelliteConfigVersion) {
                this.mCarrierRoamingSatelliteConfigVersion =
                        Optional.of(carrierRoamingSatelliteConfigVersion);
                return this;
            }

            /**
             * Sets max allowed data mode value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setMaxAllowedDataMode(int maxAllowedDataMode) {
                this.mMaxAllowedDataMode = Optional.of(maxAllowedDataMode);
                return this;
            }

            /**
             * Sets supported connection mode value of {@link SatelliteController} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = Optional.of(supportedConnectionMode);
                return this;
            }

            /**
             * Returns ControllerParams, which contains whole component of
             * {@link SatelliteController} atom
             */
            public SatelliteControllerParams build() {
                return new SatelliteControllerParams(this);
            }
        }

        @Override
        public String toString() {
            return "ControllerParams("
                    + ", countOfSatelliteServiceEnablementsSuccess="
                    + mCountOfSatelliteServiceEnablementsSuccess
                    + ", countOfSatelliteServiceEnablementsFail="
                    + mCountOfSatelliteServiceEnablementsFail
                    + ", countOfOutgoingDatagramSuccess=" + mCountOfOutgoingDatagramSuccess
                    + ", countOfOutgoingDatagramFail=" + mCountOfOutgoingDatagramFail
                    + ", countOfIncomingDatagramSuccess=" + mCountOfIncomingDatagramSuccess
                    + ", countOfIncomingDatagramFail=" + mCountOfIncomingDatagramFail
                    + ", countOfDatagramTypeSosSms=" + mCountOfDatagramTypeSosSmsSuccess
                    + ", countOfDatagramTypeSosSms=" + mCountOfDatagramTypeSosSmsFail
                    + ", countOfDatagramTypeLocationSharing="
                    + mCountOfDatagramTypeLocationSharingSuccess
                    + ", countOfDatagramTypeLocationSharing="
                    + mCountOfDatagramTypeLocationSharingFail
                    + ", serviceUptimeSec=" + mTotalServiceUptimeSec
                    + ", batteryConsumptionPercent=" + mTotalBatteryConsumptionPercent
                    + ", batteryChargedTimeSec=" + mTotalBatteryChargedTimeSec
                    + ", countOfDemoModeSatelliteServiceEnablementsSuccess="
                    + mCountOfDemoModeSatelliteServiceEnablementsSuccess
                    + ", countOfDemoModeSatelliteServiceEnablementsFail="
                    + mCountOfDemoModeSatelliteServiceEnablementsFail
                    + ", countOfDemoModeOutgoingDatagramSuccess="
                    + mCountOfDemoModeOutgoingDatagramSuccess
                    + ", countOfDemoModeOutgoingDatagramFail="
                    + mCountOfDemoModeOutgoingDatagramFail
                    + ", countOfDemoModeIncomingDatagramSuccess="
                    + mCountOfDemoModeIncomingDatagramSuccess
                    + ", countOfDemoModeIncomingDatagramFail="
                    + mCountOfDemoModeIncomingDatagramFail
                    + ", countOfDatagramTypeKeepAliveSuccess="
                    + mCountOfDatagramTypeKeepAliveSuccess
                    + ", countOfDatagramTypeKeepAliveFail="
                    + mCountOfDatagramTypeKeepAliveFail
                    + ", countOfAllowedSatelliteAccess=" + mCountOfAllowedSatelliteAccess
                    + ", countOfDisallowedSatelliteAccess=" + mCountOfDisallowedSatelliteAccess
                    + ", countOfSatelliteAccessCheckFail=" + mCountOfSatelliteAccessCheckFail
                    + ", isProvisioned=" + sIsProvisioned
                    + ", carrierId=" + sCarrierId
                    + ", countOfSatelliteAllowedStateChangedEvents="
                    + mCountOfSatelliteAllowedStateChangedEvents
                    + ", countOfSuccessfulLocationQueries=" + mCountOfSuccessfulLocationQueries
                    + ", countOfFailedLocationQueries=" + mCountOfFailedLocationQueries
                    + ", countOfP2PSmsAvailableNotificationShown="
                    + mCountOfP2PSmsAvailableNotificationShown
                    + ", countOfP2PSmsAvailableNotificationRemoved="
                    + mCountOfP2PSmsAvailableNotificationRemoved
                    + ", isNtnOnlyCarrier=" + sIsNtnOnlyCarrier
                    + ", versionOfSatelliteAccessConfig=" + sVersionOfSatelliteAccessConfig
                    + ", countOfIncomingDatagramTypeSosSmsSuccess="
                    + mCountOfIncomingDatagramTypeSosSmsSuccess
                    + ", countOfIncomingDatagramTypeSosSmsFail="
                    + mCountOfIncomingDatagramTypeSosSmsFail
                    + ", countOfOutgoingDatagramTypeSmsSuccess="
                    + mCountOfOutgoingDatagramTypeSmsSuccess
                    + ", countOfOutgoingDatagramTypeSmsFail=" + mCountOfOutgoingDatagramTypeSmsFail
                    + ", countOfIncomingDatagramTypeSmsSuccess="
                    + mCountOfIncomingDatagramTypeSmsSuccess
                    + ", countOfIncomingDatagramTypeSmsFail=" + mCountOfIncomingDatagramTypeSmsFail
                    + ", carrierRoamingSatelliteConfigVersion="
                    + sCarrierRoamingSatelliteConfigVersion
                    + ", maxAllowedDataMode=" + sMaxAllowedDataMode
                    + ", supportedConnectionMode=" + sSupportedConnectionMode
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteSession) atom.
     * Refer to {@link #onSatelliteSessionMetrics(SatelliteSessionParams)}.
     */
    public static class SatelliteSessionParams {
        private final int mSatelliteServiceInitializationResult;
        private final int mSatelliteTechnology;
        private final int mTerminationResult;
        private final long mInitializationProcessingTimeMillis;
        private final long mTerminationProcessingTimeMillis;
        private final int mSessionDurationSec;
        private final int mCountOfOutgoingDatagramSuccess;
        private final int mCountOfOutgoingDatagramFailed;
        private final int mCountOfIncomingDatagramSuccess;
        private final int mCountOfIncomingDatagramFailed;
        private final boolean mIsDemoMode;
        private final @NtnSignalStrength.NtnSignalStrengthLevel int mMaxNtnSignalStrengthLevel;
        private final int mCarrierId;
        private final int mCountOfSatelliteNotificationDisplayed;
        private final int mCountOfAutoExitDueToScreenOff;
        private final int mCountOfAutoExitDueToTnNetwork;
        private final boolean mIsEmergency;
        private final int mMaxInactivityDurationSec;
        private final boolean mIsNtnOnlyCarrier;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;
        private final String mPlmn;
        private final int mScreenOnTimeSec;
        private final int mBatteryLevelDropPercent;
        private final boolean mWasChargingDuringSession;
        private final int mBatteryDesignCapacityMah;
        private final long mEnergyConsumedNwh;


        private SatelliteSessionParams(Builder builder) {
            this.mSatelliteServiceInitializationResult =
                    builder.mSatelliteServiceInitializationResult;
            this.mSatelliteTechnology = builder.mSatelliteTechnology;
            this.mTerminationResult = builder.mTerminationResult;
            this.mInitializationProcessingTimeMillis = builder.mInitializationProcessingTimeMillis;
            this.mTerminationProcessingTimeMillis =
                    builder.mTerminationProcessingTimeMillis;
            this.mSessionDurationSec = builder.mSessionDurationSec;
            this.mCountOfOutgoingDatagramSuccess = builder.mCountOfOutgoingDatagramSuccess;
            this.mCountOfOutgoingDatagramFailed = builder.mCountOfOutgoingDatagramFailed;
            this.mCountOfIncomingDatagramSuccess = builder.mCountOfIncomingDatagramSuccess;
            this.mCountOfIncomingDatagramFailed = builder.mCountOfIncomingDatagramFailed;
            this.mIsDemoMode = builder.mIsDemoMode;
            this.mMaxNtnSignalStrengthLevel = builder.mMaxNtnSignalStrengthLevel;
            this.mCarrierId = builder.mCarrierId;
            this.mCountOfSatelliteNotificationDisplayed =
                    builder.mCountOfSatelliteNotificationDisplayed;
            this.mCountOfAutoExitDueToScreenOff = builder.mCountOfAutoExitDueToScreenOff;
            this.mCountOfAutoExitDueToTnNetwork = builder.mCountOfAutoExitDueToTnNetwork;
            this.mIsEmergency = builder.mIsEmergency;
            this.mIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier;
            this.mMaxInactivityDurationSec = builder.mMaxInactivityDurationSec;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mSessionConnectionMode = builder.mSessionConnectionMode;
            this.mPlmn = builder.mPlmn;
            this.mScreenOnTimeSec = builder.mScreenOnTimeSec;
            this.mBatteryLevelDropPercent = builder.mBatteryLevelDropPercent;
            this.mWasChargingDuringSession = builder.mWasChargingDuringSession;
            this.mBatteryDesignCapacityMah = builder.mBatteryDesignCapacityMah;
            this.mEnergyConsumedNwh = builder.mEnergyConsumedNwh;
        }

        public int getSatelliteServiceInitializationResult() {
            return mSatelliteServiceInitializationResult;
        }

        public int getSatelliteTechnology() {
            return mSatelliteTechnology;
        }

        public int getTerminationResult() {
            return mTerminationResult;
        }

        public long getInitializationProcessingTime() {
            return mInitializationProcessingTimeMillis;
        }

        public long getTerminationProcessingTime() {
            return mTerminationProcessingTimeMillis;
        }

        public int getSessionDuration() {
            return mSessionDurationSec;
        }

        public int getCountOfOutgoingDatagramSuccess() {
            return mCountOfOutgoingDatagramSuccess;
        }

        public int getCountOfOutgoingDatagramFailed() {
            return mCountOfOutgoingDatagramFailed;
        }

        public int getCountOfIncomingDatagramSuccess() {
            return mCountOfIncomingDatagramSuccess;
        }

        public int getCountOfIncomingDatagramFailed() {
            return mCountOfIncomingDatagramFailed;
        }

        public boolean getIsDemoMode() {
            return mIsDemoMode;
        }

        public @NtnSignalStrength.NtnSignalStrengthLevel int getMaxNtnSignalStrengthLevel() {
            return mMaxNtnSignalStrengthLevel;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public int getCountOfSatelliteNotificationDisplayed() {
            return mCountOfSatelliteNotificationDisplayed;
        }

        public int getCountOfAutoExitDueToScreenOff() {
            return mCountOfAutoExitDueToScreenOff;
        }

        public int getCountOfAutoExitDueToTnNetwork() {
            return mCountOfAutoExitDueToTnNetwork;
        }

        public boolean getIsEmergency() {
            return mIsEmergency;
        }

        public boolean isNtnOnlyCarrier() {
            return mIsNtnOnlyCarrier;
        }

        public int getMaxInactivityDurationSec() {
            return mMaxInactivityDurationSec;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getSessionConnectionMode() {
            return mSessionConnectionMode;
        }

        public String getPlmn() {
            return mPlmn;
        }

        /**
         * Returns the screen on time period for the session.
         */
        public int getScreenOnTimeSec() {
            return mScreenOnTimeSec;
        }

        /**
         * Returns the battery drop level while the satellite session was enabled.
         * @return the battery drop percentage (0 or a positive integer), or -1 if the measurement
         * was invalid (e.g., failed to retrieve start or end battery level).
         */
        public int getBatteryLevelDropPercent() {
            return mBatteryLevelDropPercent;
        }

        /**
         * Returns {@code true} if the device was charged at any point during the satellite session.
         */
        public boolean wasChargingDuringSession() {
            return mWasChargingDuringSession;
        }

        /**
         * Returns the factory-rated design capacity of the battery in milliampere-hours (mAh).
         */
        public int getBatteryDesignCapacityMah() {
            return mBatteryDesignCapacityMah;
        }

        /**
         * Returns the absolute energy consumed during the satellite session in nanowatt-hours.
         */
        public long getEnergyConsumedNwh() {
            return mEnergyConsumedNwh;
        }

        /**
         * A builder class to create {@link SatelliteSessionParams} data structure class
         */
        public static class Builder {
            private int mSatelliteServiceInitializationResult = -1;
            private int mSatelliteTechnology = -1;
            private int mTerminationResult = -1;
            private long mInitializationProcessingTimeMillis = -1;
            private long mTerminationProcessingTimeMillis = -1;
            private int mSessionDurationSec = -1;
            private int mCountOfOutgoingDatagramSuccess = -1;
            private int mCountOfOutgoingDatagramFailed = -1;
            private int mCountOfIncomingDatagramSuccess = -1;
            private int mCountOfIncomingDatagramFailed = -1;
            private boolean mIsDemoMode = false;
            private @NtnSignalStrength.NtnSignalStrengthLevel int mMaxNtnSignalStrengthLevel =
                    NTN_SIGNAL_STRENGTH_NONE;
            private int mCarrierId = UNKNOWN_CARRIER_ID;
            private int mCountOfSatelliteNotificationDisplayed = -1;
            private int mCountOfAutoExitDueToScreenOff = -1;
            private int mCountOfAutoExitDueToTnNetwork = -1;
            private boolean mIsEmergency = false;
            private boolean mIsNtnOnlyCarrier = false;
            private int mMaxInactivityDurationSec = -1;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode =
                    SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
            private String mPlmn = "UNKNOWN";
            private int mScreenOnTimeSec = 0;
            private int mBatteryLevelDropPercent = 0;
            private boolean mWasChargingDuringSession = false;
            private int mBatteryDesignCapacityMah = 0;
            private long mEnergyConsumedNwh = 0;

            /**
             * Sets satelliteServiceInitializationResult value of {@link SatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSatelliteServiceInitializationResult(
                    int satelliteServiceInitializationResult) {
                this.mSatelliteServiceInitializationResult = satelliteServiceInitializationResult;
                return this;
            }

            /**
             * Sets satelliteTechnology value of {@link SatelliteSession} atoms then
             * returns Builder class
             */
            public Builder setSatelliteTechnology(int satelliteTechnology) {
                this.mSatelliteTechnology = satelliteTechnology;
                return this;
            }

            /** Sets the satellite de-initialization result. */
            public Builder setTerminationResult(
                    @SatelliteManager.SatelliteResult int result) {
                this.mTerminationResult = result;
                return this;
            }

            /** Sets the satellite initialization processing time. */
            public Builder setInitializationProcessingTime(long processingTime) {
                this.mInitializationProcessingTimeMillis = processingTime;
                return this;
            }

            /** Sets the satellite de-initialization processing time. */
            public Builder setTerminationProcessingTime(long processingTime) {
                this.mTerminationProcessingTimeMillis = processingTime;
                return this;
            }

            /** Sets the total enabled time for the satellite session. */
            public Builder setSessionDuration(int sessionDurationSec) {
                this.mSessionDurationSec = sessionDurationSec;
                return this;
            }

            /** Sets the total number of successful outgoing datagram transmission. */
            public Builder setCountOfOutgoingDatagramSuccess(int countOfoutgoingDatagramSuccess) {
                this.mCountOfOutgoingDatagramSuccess = countOfoutgoingDatagramSuccess;
                return this;
            }

            /** Sets the total number of failed outgoing datagram transmission. */
            public Builder setCountOfOutgoingDatagramFailed(int countOfoutgoingDatagramFailed) {
                this.mCountOfOutgoingDatagramFailed = countOfoutgoingDatagramFailed;
                return this;
            }

            /** Sets the total number of successful incoming datagram transmission. */
            public Builder setCountOfIncomingDatagramSuccess(int countOfincomingDatagramSuccess) {
                this.mCountOfIncomingDatagramSuccess = countOfincomingDatagramSuccess;
                return this;
            }

            /** Sets the total number of failed incoming datagram transmission. */
            public Builder setCountOfIncomingDatagramFailed(int countOfincomingDatagramFailed) {
                this.mCountOfIncomingDatagramFailed = countOfincomingDatagramFailed;
                return this;
            }

            /** Sets whether enabled satellite session is for demo mode or not. */
            public Builder setIsDemoMode(boolean isDemoMode) {
                this.mIsDemoMode = isDemoMode;
                return this;
            }

            /** Sets the max ntn signal strength for the satellite session. */
            public Builder setMaxNtnSignalStrengthLevel(
                    @NtnSignalStrength.NtnSignalStrengthLevel int maxNtnSignalStrengthLevel) {
                this.mMaxNtnSignalStrengthLevel = maxNtnSignalStrengthLevel;
                return this;
            }

            /** Sets the currently active NB-IoT NTN carrier ID. */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets Total number of times the user is notified that the device is eligible for
             * satellite service for this session.
             */
            public Builder setCountOfSatelliteNotificationDisplayed(
                    int countOfSatelliteNotificationDisplayed) {
                this.mCountOfSatelliteNotificationDisplayed = countOfSatelliteNotificationDisplayed;
                return this;
            }

            /**
             * Sets Total number of times exit P2P message service automatically due to screen is
             * off and timer is expired.
             */
            public Builder setCountOfAutoExitDueToScreenOff(
                    int countOfAutoExitDueToScreenOff) {
                this.mCountOfAutoExitDueToScreenOff = countOfAutoExitDueToScreenOff;
                return this;
            }

            /**
             * Sets Total number of times times exit P2P message service automatically due to
             * scan TN network.
             */
            public Builder setCountOfAutoExitDueToTnNetwork(
                    int countOfAutoExitDueToTnNetwork) {
                this.mCountOfAutoExitDueToTnNetwork = countOfAutoExitDueToTnNetwork;
                return this;
            }

            /** Sets whether enabled satellite session is for emergency or not. */
            public Builder setIsEmergency(boolean isEmergency) {
                this.mIsEmergency = isEmergency;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteSession} atom
             * then returns Builder class
            */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
                return this;
            }

            /** Sets the max user inactivity duration in seconds. */
            public Builder setMaxInactivityDurationSec(int maxInactivityDurationSec) {
                this.mMaxInactivityDurationSec = maxInactivityDurationSec;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets sessionConnectionMode value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setSessionConnectionMode(int sessionConnectionMode) {
                this.mSessionConnectionMode = sessionConnectionMode;
                return this;
            }

            /**
             * Sets plmn value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setPlmn(String plmn) {
                this.mPlmn = plmn;
                return this;
            }

            /**
             * Sets screenOnTimeSec value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setScreenOnTimeSec(int screenOnTimeSec) {
                this.mScreenOnTimeSec = screenOnTimeSec;
                return this;
            }

            /**
             * Sets batteryLevelDropPercent value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setBatteryLevelDropPercent(int batteryLevelDropPercent) {
                this.mBatteryLevelDropPercent = batteryLevelDropPercent;
                return this;
            }

            /**
             * Sets whether the charger was connected during satellite session was enabled in the
             * {@link SatelliteSession} atom then returns Builder class
             */
            public Builder setWasChargingDuringSession(boolean wasChargingDuringSession) {
                this.mWasChargingDuringSession = wasChargingDuringSession;
                return this;
            }

            /**
             * Sets batteryDesignCapacityMah value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setBatteryDesignCapacityMah(int batteryDesignCapacityMah) {
                this.mBatteryDesignCapacityMah = batteryDesignCapacityMah;
                return this;
            }

            /**
             * Sets energyConsumedNwh value of {@link SatelliteSession} atom
             * then returns Builder class
             */
            public Builder setEnergyConsumedNwh(long energyConsumedNwh) {
                this.mEnergyConsumedNwh = energyConsumedNwh;
                return this;
            }

            /**
             * Returns SessionParams, which contains whole component of
             * {@link SatelliteSession} atom
             */
            public SatelliteSessionParams build() {
                return new SatelliteSessionParams(this);
            }
        }

        @Override
        public String toString() {
            return "SessionParams("
                    + ", satelliteServiceInitializationResult="
                    + mSatelliteServiceInitializationResult
                    + ", TerminationResult=" + mTerminationResult
                    + ", InitializationProcessingTimeMillis=" + mInitializationProcessingTimeMillis
                    + ", TerminationProcessingTimeMillis=" + mTerminationProcessingTimeMillis
                    + ", SessionDurationSec=" + mSessionDurationSec
                    + ", CountOfOutgoingDatagramSuccess=" + mCountOfOutgoingDatagramSuccess
                    + ", CountOfOutgoingDatagramFailed=" + mCountOfOutgoingDatagramFailed
                    + ", CountOfIncomingDatagramSuccess=" + mCountOfIncomingDatagramSuccess
                    + ", CountOfIncomingDatagramFailed=" + mCountOfIncomingDatagramFailed
                    + ", IsDemoMode=" + mIsDemoMode
                    + ", MaxNtnSignalStrengthLevel=" + mMaxNtnSignalStrengthLevel
                    + ", CarrierId=" + mCarrierId
                    + ", CountOfSatelliteNotificationDisplayed"
                    + mCountOfSatelliteNotificationDisplayed
                    + ", CountOfAutoExitDueToScreenOff" + mCountOfAutoExitDueToScreenOff
                    + ", CountOfAutoExitDueToTnNetwork" + mCountOfAutoExitDueToTnNetwork
                    + ", IsEmergency=" + mIsEmergency
                    + ", IsNtnOnlyCarrier=" + mIsNtnOnlyCarrier
                    + ", MaxInactivityDurationSec=" + mMaxInactivityDurationSec
                    + ", SupportedConnectionMode=" + mSupportedConnectionMode
                    + ", SessionConnectionMode=" + mSessionConnectionMode
                    + ", PLMN=" + mPlmn
                    + ", ScreenOntimeSec=" + mScreenOnTimeSec
                    + ", BatteryLevelDropPercent=" + mBatteryLevelDropPercent
                    + ", WasChargingDuringSession=" + mWasChargingDuringSession
                    + ", BatteryDesignCapacityMah=" + mBatteryDesignCapacityMah
                    + ", EnergyConsumedNwh=" + mEnergyConsumedNwh
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteIncomingDatagram} atom.
     * Refer to {@link #onSatelliteIncomingDatagramMetrics(SatelliteIncomingDatagramParams)}.
     */
    public static class SatelliteIncomingDatagramParams {
        private final int mResultCode;
        private final int mDatagramSizeBytes;
        private final long mDatagramTransferTimeMillis;
        private final boolean mIsDemoMode;
        private final int mCarrierId;
        private final boolean mIsNtnOnlyCarrier;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;
        private final String mPlmn;

        private SatelliteIncomingDatagramParams(Builder builder) {
            this.mResultCode = builder.mResultCode;
            this.mDatagramSizeBytes = builder.mDatagramSizeBytes;
            this.mDatagramTransferTimeMillis = builder.mDatagramTransferTimeMillis;
            this.mIsDemoMode = builder.mIsDemoMode;
            this.mCarrierId = builder.mCarrierId;
            this.mIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mSessionConnectionMode = builder.mSessionConnectionMode;
            this.mPlmn = builder.mPlmn;
        }

        public int getResultCode() {
            return mResultCode;
        }

        public int getDatagramSizeBytes() {
            return mDatagramSizeBytes;
        }

        public long getDatagramTransferTimeMillis() {
            return mDatagramTransferTimeMillis;
        }

        public boolean getIsDemoMode() {
            return mIsDemoMode;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean isNtnOnlyCarrier() {
            return mIsNtnOnlyCarrier;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getSessionConnectionMode() {
            return mSessionConnectionMode;
        }

        public String getPlmn() {
            return mPlmn;
        }

        /**
         * A builder class to create {@link SatelliteIncomingDatagramParams} data structure class
         */
        public static class Builder {
            private int mResultCode = -1;
            private int mDatagramSizeBytes = -1;
            private long mDatagramTransferTimeMillis = -1;
            private boolean mIsDemoMode = false;
            private int mCarrierId = UNKNOWN_CARRIER_ID;
            private boolean mIsNtnOnlyCarrier = false;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode =
                    SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
            private String mPlmn = "UNKNOWN";

            /**
             * Sets resultCode value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
             */
            public Builder setResultCode(int resultCode) {
                this.mResultCode = resultCode;
                return this;
            }

            /**
             * Sets datagramSizeBytes value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
             */
            public Builder setDatagramSizeBytes(int datagramSizeBytes) {
                this.mDatagramSizeBytes = datagramSizeBytes;
                return this;
            }

            /**
             * Sets datagramTransferTimeMillis value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
             */
            public Builder setDatagramTransferTimeMillis(long datagramTransferTimeMillis) {
                this.mDatagramTransferTimeMillis = datagramTransferTimeMillis;
                return this;
            }

            /**
             * Sets whether transferred datagram is in demo mode or not
             * then returns Builder class
             */
            public Builder setIsDemoMode(boolean isDemoMode) {
                this.mIsDemoMode = isDemoMode;
                return this;
            }

            /** Sets the currently active NB-IoT NTN carrier ID. */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
            */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets sesisonConnectionMode value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
             */
            public Builder setSessionConnectionMode(int sessionConnectionMode) {
                this.mSessionConnectionMode = sessionConnectionMode;
                return this;
            }

            /**
             * Sets satellite plmn value of {@link SatelliteIncomingDatagram} atom
             * then returns Builder class
             */
            public Builder setPlmn(String plmn) {
                this.mPlmn = plmn;
                return this;
            }

            /**
             * Returns IncomingDatagramParams, which contains whole component of
             * {@link SatelliteIncomingDatagram} atom
             */
            public SatelliteIncomingDatagramParams build() {
                return new SatelliteIncomingDatagramParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "IncomingDatagramParams("
                    + ", resultCode=" + mResultCode
                    + ", datagramSizeBytes=" + mDatagramSizeBytes
                    + ", datagramTransferTimeMillis=" + mDatagramTransferTimeMillis
                    + ", isDemoMode=" + mIsDemoMode
                    + ", CarrierId=" + mCarrierId
                    + ", isNtnOnlyCarrier=" + mIsNtnOnlyCarrier
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", sessionConnectionMode=" + mSessionConnectionMode
                    + ", plmn=" + mPlmn
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteOutgoingDatagram} atom.
     * Refer to {@link #onSatelliteOutgoingDatagramMetrics(SatelliteOutgoingDatagramParams)}.
     */
    public static class SatelliteOutgoingDatagramParams {
        private final int mDatagramType;
        private final int mResultCode;
        private final int mDatagramSizeBytes;
        private final long mDatagramTransferTimeMillis;
        private final boolean mIsDemoMode;
        private final int mCarrierId;
        private final boolean mIsNtnOnlyCarrier;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;
        private final String mPlmn;

        private SatelliteOutgoingDatagramParams(Builder builder) {
            this.mDatagramType = builder.mDatagramType;
            this.mResultCode = builder.mResultCode;
            this.mDatagramSizeBytes = builder.mDatagramSizeBytes;
            this.mDatagramTransferTimeMillis = builder.mDatagramTransferTimeMillis;
            this.mIsDemoMode = builder.mIsDemoMode;
            this.mCarrierId = builder.mCarrierId;
            this.mIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mSessionConnectionMode = builder.mSessionConnectionMode;
            this.mPlmn = builder.mPlmn;
        }

        public int getDatagramType() {
            return mDatagramType;
        }

        public int getResultCode() {
            return mResultCode;
        }

        public int getDatagramSizeBytes() {
            return mDatagramSizeBytes;
        }

        public long getDatagramTransferTimeMillis() {
            return mDatagramTransferTimeMillis;
        }

        public boolean getIsDemoMode() {
            return mIsDemoMode;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean isNtnOnlyCarrier() {
            return mIsNtnOnlyCarrier;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getSessionConnectionMode() {
            return mSessionConnectionMode;
        }

        public String getPlmn() {
            return mPlmn;
        }

        /**
         * A builder class to create {@link SatelliteOutgoingDatagramParams} data structure class
         */
        public static class Builder {
            private int mDatagramType = -1;
            private int mResultCode = -1;
            private int mDatagramSizeBytes = -1;
            private long mDatagramTransferTimeMillis = -1;
            private boolean mIsDemoMode = false;
            private int mCarrierId = UNKNOWN_CARRIER_ID;
            private boolean mIsNtnOnlyCarrier = false;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode =
                    SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
            private String mPlmn = "UNKNOWN";


            /**
             * Sets datagramType value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setDatagramType(int datagramType) {
                this.mDatagramType = datagramType;
                return this;
            }

            /**
             * Sets resultCode value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setResultCode(int resultCode) {
                this.mResultCode = resultCode;
                return this;
            }

            /**
             * Sets datagramSizeBytes value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setDatagramSizeBytes(int datagramSizeBytes) {
                this.mDatagramSizeBytes = datagramSizeBytes;
                return this;
            }

            /**
             * Sets datagramTransferTimeMillis value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setDatagramTransferTimeMillis(long datagramTransferTimeMillis) {
                this.mDatagramTransferTimeMillis = datagramTransferTimeMillis;
                return this;
            }

            /**
             * Sets whether transferred datagram is in demo mode or not
             * then returns Builder class
             */
            public Builder setIsDemoMode(boolean isDemoMode) {
                this.mIsDemoMode = isDemoMode;
                return this;
            }

            /** Sets the currently active NB-IoT NTN carrier ID. */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
            */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets sessionConnectionMode value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setSessionConnectionMode(int sessionConnectionMode) {
                this.mSessionConnectionMode = sessionConnectionMode;
                return this;
            }

            /**
             * Sets satellite plmn value of {@link SatelliteOutgoingDatagram} atom
             * then returns Builder class
             */
            public Builder setPlmn(String plmn) {
                this.mPlmn = plmn;
                return this;
            }

            /**
             * Returns OutgoingDatagramParams, which contains whole component of
             * {@link SatelliteOutgoingDatagram} atom
             */
            public SatelliteOutgoingDatagramParams build() {
                return new SatelliteOutgoingDatagramParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "OutgoingDatagramParams("
                    + "datagramType=" + mDatagramType
                    + ", resultCode=" + mResultCode
                    + ", datagramSizeBytes=" + mDatagramSizeBytes
                    + ", datagramTransferTimeMillis=" + mDatagramTransferTimeMillis
                    + ", isDemoMode=" + mIsDemoMode
                    + ", CarrierId=" + mCarrierId
                    + ", isNtnOnlyCarrier=" + mIsNtnOnlyCarrier
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", sessionConnectionMode=" + mSessionConnectionMode
                    + ", plmn=" + mPlmn
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteProvision} atom.
     * Refer to {@link #onSatelliteProvisionMetrics(SatelliteProvisionParams)}.
     */
    public static class SatelliteProvisionParams {
        private final int mResultCode;
        private final int mProvisioningTimeSec;
        private final boolean mIsProvisionRequest;
        private final boolean mIsCanceled;
        private final int mCarrierId;
        private final boolean mIsNtnOnlyCarrier;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;

        private SatelliteProvisionParams(Builder builder) {
            this.mResultCode = builder.mResultCode;
            this.mProvisioningTimeSec = builder.mProvisioningTimeSec;
            this.mIsProvisionRequest = builder.mIsProvisionRequest;
            this.mIsCanceled = builder.mIsCanceled;
            this.mCarrierId = builder.mCarrierId;
            this.mIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
        }

        public int getResultCode() {
            return mResultCode;
        }

        public int getProvisioningTimeSec() {
            return mProvisioningTimeSec;
        }

        public boolean getIsProvisionRequest() {
            return mIsProvisionRequest;
        }

        public boolean getIsCanceled() {
            return mIsCanceled;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean isNtnOnlyCarrier() {
            return mIsNtnOnlyCarrier;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        /**
         * A builder class to create {@link SatelliteProvisionParams} data structure class
         */
        public static class Builder {
            private int mResultCode = -1;
            private int mProvisioningTimeSec = -1;
            private boolean mIsProvisionRequest = false;
            private boolean mIsCanceled = false;
            private int mCarrierId = UNKNOWN_CARRIER_ID;
            private boolean mIsNtnOnlyCarrier = false;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;


            /**
             * Sets resultCode value of {@link SatelliteProvision} atom
             * then returns Builder class
             */
            public Builder setResultCode(int resultCode) {
                this.mResultCode = resultCode;
                return this;
            }

            /**
             * Sets provisioningTimeSec value of {@link SatelliteProvision} atom
             * then returns Builder class
             */
            public Builder setProvisioningTimeSec(int provisioningTimeSec) {
                this.mProvisioningTimeSec = provisioningTimeSec;
                return this;
            }

            /**
             * Sets isProvisionRequest value of {@link SatelliteProvision} atom
             * then returns Builder class
             */
            public Builder setIsProvisionRequest(boolean isProvisionRequest) {
                this.mIsProvisionRequest = isProvisionRequest;
                return this;
            }

            /**
             * Sets isCanceled value of {@link SatelliteProvision} atom
             * then returns Builder class
             */
            public Builder setIsCanceled(boolean isCanceled) {
                this.mIsCanceled = isCanceled;
                return this;
            }

            /** Sets the currently active NB-IoT NTN carrier ID. */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteProvision} atom
             * then returns Builder class
            */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteProvision} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Returns ProvisionParams, which contains whole component of
             * {@link SatelliteProvision} atom
             */
            public SatelliteProvisionParams build() {
                return new SatelliteProvisionParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "ProvisionParams("
                    + "resultCode=" + mResultCode
                    + ", provisioningTimeSec=" + mProvisioningTimeSec
                    + ", isProvisionRequest=" + mIsProvisionRequest
                    + ", isCanceled" + mIsCanceled
                    + ", CarrierId=" + mCarrierId
                    + ", isNtnOnlyCarrier=" + mIsNtnOnlyCarrier
                    + "' supportedConnectionMode=" + mSupportedConnectionMode
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteSosMessageRecommender} atom.
     * Refer to {@link #onSatelliteSosMessageRecommender(SatelliteSosMessageRecommenderParams)}.
     */
    public static class SatelliteSosMessageRecommenderParams {
        private final boolean mIsDisplaySosMessageSent;
        private final int mCountOfTimerStarted;
        private final boolean mIsImsRegistered;
        private final int mCellularServiceState;
        private final boolean mIsMultiSim;
        private final int mRecommendingHandoverType;
        private final boolean mIsSatelliteAllowedInCurrentLocation;
        private final boolean mIsWifiConnected;
        private final int mCarrierId;
        private final boolean mIsNtnOnlyCarrier;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;
        private final String mPlmn;
        private final boolean mIsInCarrierRoamingNtnMode;
        private final int mCarrierRoamingSatelliteEmergencyMessagingProvider;
        private final int mEmergencyNumberSourceUsedInHandoverIntent;

        private SatelliteSosMessageRecommenderParams(Builder builder) {
            this.mIsDisplaySosMessageSent = builder.mIsDisplaySosMessageSent;
            this.mCountOfTimerStarted = builder.mCountOfTimerStarted;
            this.mIsImsRegistered = builder.mIsImsRegistered;
            this.mCellularServiceState = builder.mCellularServiceState;
            this.mIsMultiSim = builder.mIsMultiSim;
            this.mRecommendingHandoverType = builder.mRecommendingHandoverType;
            this.mIsSatelliteAllowedInCurrentLocation =
                    builder.mIsSatelliteAllowedInCurrentLocation;
            this.mIsWifiConnected = builder.mIsWifiConnected;
            this.mCarrierId = builder.mCarrierId;
            this.mIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mSessionConnectionMode = builder.mSessionConnectionMode;
            this.mPlmn = builder.mPlmn;
            this.mIsInCarrierRoamingNtnMode = builder.mIsInCarrierRoamingNtnMode;
            this.mCarrierRoamingSatelliteEmergencyMessagingProvider =
                builder.mCarrierRoamingSatelliteEmergencyMessagingProvider;
            this.mEmergencyNumberSourceUsedInHandoverIntent =
                builder.mEmergencyNumberSourceUsedInHandoverIntent;
        }

        public boolean isDisplaySosMessageSent() {
            return mIsDisplaySosMessageSent;
        }

        public int getCountOfTimerStarted() {
            return mCountOfTimerStarted;
        }

        public boolean isImsRegistered() {
            return mIsImsRegistered;
        }

        public int getCellularServiceState() {
            return mCellularServiceState;
        }

        public boolean isMultiSim() {
            return mIsMultiSim;
        }

        public int getRecommendingHandoverType() {
            return mRecommendingHandoverType;
        }

        public boolean isSatelliteAllowedInCurrentLocation() {
            return mIsSatelliteAllowedInCurrentLocation;
        }

        public boolean isWifiConnected() {
            return mIsWifiConnected;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean isNtnOnlyCarrier() {
            return mIsNtnOnlyCarrier;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getSessionConnectionMode() {
            return mSessionConnectionMode;
        }

        public String getPlmn() {
            return mPlmn;
        }

        public boolean getIsInCarrierRoamingNtnMode() {
            return mIsInCarrierRoamingNtnMode;
        }

        public int getCarrierRoamingSatelliteEmergencyMessagingProvider() {
            return mCarrierRoamingSatelliteEmergencyMessagingProvider;
        }

        public int getEmergencyNumberSourceUsedInHandoverIntent() {
            return mEmergencyNumberSourceUsedInHandoverIntent;
        }

        /**
         * A builder class to create {@link SatelliteSosMessageRecommender} data structure class
         */
        public static class Builder {
            private boolean mIsDisplaySosMessageSent = false;
            private int mCountOfTimerStarted = -1;
            private boolean mIsImsRegistered = false;
            private int mCellularServiceState = -1;
            private boolean mIsMultiSim = false;
            private int mRecommendingHandoverType = -1;
            private boolean mIsSatelliteAllowedInCurrentLocation = false;
            private boolean mIsWifiConnected = false;
            private int mCarrierId = UNKNOWN_CARRIER_ID;
            private boolean mIsNtnOnlyCarrier = false;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode =
                    SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
            private String mPlmn = "UNKNOWN";
            private boolean mIsInCarrierRoamingNtnMode = false;
            private int mCarrierRoamingSatelliteEmergencyMessagingProvider =
                SatelliteManager.CARRIER_ROAMING_SATELLITE_EMERGENCY_MESSAGING_PROVIDER_UNKNOWN;
            private int mEmergencyNumberSourceUsedInHandoverIntent =
                SatelliteConstants.EMERGENCY_NUMBER_SOURCE_UNKNOWN;

            /**
             * Sets resultCode value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setDisplaySosMessageSent(
                    boolean isDisplaySosMessageSent) {
                this.mIsDisplaySosMessageSent = isDisplaySosMessageSent;
                return this;
            }

            /**
             * Sets countOfTimerIsStarted value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setCountOfTimerStarted(int countOfTimerStarted) {
                this.mCountOfTimerStarted = countOfTimerStarted;
                return this;
            }

            /**
             * Sets isImsRegistered value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setImsRegistered(boolean isImsRegistered) {
                this.mIsImsRegistered = isImsRegistered;
                return this;
            }

            /**
             * Sets cellularServiceState value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setCellularServiceState(int cellularServiceState) {
                this.mCellularServiceState = cellularServiceState;
                return this;
            }

            /**
             * Sets isMultiSim value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setIsMultiSim(boolean isMultiSim) {
                this.mIsMultiSim = isMultiSim;
                return this;
            }

            /**
             * Sets recommendingHandoverType value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setRecommendingHandoverType(int recommendingHandoverType) {
                this.mRecommendingHandoverType = recommendingHandoverType;
                return this;
            }

            /**
             * Sets isSatelliteAllowedInCurrentLocation value of
             * {@link SatelliteSosMessageRecommender} atom then returns Builder class.
             */
            public Builder setIsSatelliteAllowedInCurrentLocation(
                    boolean satelliteAllowedInCurrentLocation) {
                mIsSatelliteAllowedInCurrentLocation = satelliteAllowedInCurrentLocation;
                return this;
            }

            /**
             * Sets whether Wi-Fi is connected value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setIsWifiConnected(boolean isWifiConnected) {
                this.mIsWifiConnected = isWifiConnected;
                return this;
            }

            /**
             * Sets carrier ID value of {@link SatelliteSosMessageRecommender} atom then returns
             * Builder class.
             */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
            */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets sessionConnectionMode value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setSessionConnectionMode(int sessionConnectionMode) {
                this.mSessionConnectionMode = sessionConnectionMode;
                return this;
            }

            /**
             * Sets satellite plmn value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setPlmn(String plmn) {
                this.mPlmn = plmn;
                return this;
            }

            /**
             * Sets isInCarrierRoamingNtnMode value of {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setIsInCarrierRoamingNtnMode(boolean isInCarrierRoamingNtnMode) {
                this.mIsInCarrierRoamingNtnMode = isInCarrierRoamingNtnMode;
                return this;
            }

            /**
             * Sets carrierRoamingSatelliteEmergencyMessagingProvider value of
             * {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setCarrierRoamingSatelliteEmergencyMessagingProvider(int provider) {
                this.mCarrierRoamingSatelliteEmergencyMessagingProvider = provider;
                return this;
            }

            /**
             * Sets emergencyNumberSourceUsedInHandoverIntent value of
             * {@link SatelliteSosMessageRecommender} atom
             * then returns Builder class
             */
            public Builder setEmergencyNumberSourceUsedInHandoverIntent(int source) {
                this.mEmergencyNumberSourceUsedInHandoverIntent = source;
                return this;
            }

            /**
             * Returns SosMessageRecommenderParams, which contains whole component of
             * {@link SatelliteSosMessageRecommenderParams} atom
             */
            public SatelliteSosMessageRecommenderParams build() {
                return new SatelliteSosMessageRecommenderParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "SosMessageRecommenderParams("
                    + "isDisplaySosMessageSent=" + mIsDisplaySosMessageSent
                    + ", countOfTimerStarted=" + mCountOfTimerStarted
                    + ", isImsRegistered=" + mIsImsRegistered
                    + ", cellularServiceState=" + mCellularServiceState
                    + ", isMultiSim=" + mIsMultiSim
                    + ", recommendingHandoverType=" + mRecommendingHandoverType
                    + ", isSatelliteAllowedInCurrentLocation="
                    + mIsSatelliteAllowedInCurrentLocation
                    + ", isWifiConnected=" + mIsWifiConnected
                    + ", carrierId=" + mCarrierId
                    + ", isNtnOnlyCarrier=" + mIsNtnOnlyCarrier
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", sessionConnectionMode=" + mSessionConnectionMode
                    + ", plmn=" + mPlmn
                    + ", isInCarrierRoamingNtnMode=" + mIsInCarrierRoamingNtnMode
                    + ", carrierRoamingSatelliteEmergencyMessagingProvider ="
                    + mCarrierRoamingSatelliteEmergencyMessagingProvider
                    + ", mEmergencyNumberSourceUsedInHandoverIntent ="
                    + mEmergencyNumberSourceUsedInHandoverIntent
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link CarrierRoamingSatelliteSession} atom.
     * Refer to {@link #onCarrierRoamingSatelliteSessionMetrics(
     * CarrierRoamingSatelliteSessionParams)}.
     */
    public static class CarrierRoamingSatelliteSessionParams {
        private final int mCarrierId;
        private final boolean mIsNtnRoamingInHomeCountry;
        private final int mTotalSatelliteModeTimeSec;
        private final int mNumberOfSatelliteConnections;
        private final int mAvgDurationOfSatelliteConnectionSec;
        private final int mSatelliteConnectionGapMinSec;
        private final int mSatelliteConnectionGapAvgSec;
        private final int mSatelliteConnectionGapMaxSec;
        private final int mRsrpAvg;
        private final int mRsrpMedian;
        private final int mRssnrAvg;
        private final int mRssnrMedian;
        private final int mCountOfIncomingSms;
        private final int mCountOfOutgoingSms;
        private final int mCountOfIncomingMms;
        private final int mCountOfOutgoingMms;
        private final int[] mSupportedSatelliteServices;
        private final int mServiceDataPolicy;
        private final long mSatelliteDataConsumedBytes;
        private final boolean mIsMultiSim;
        private final boolean mIsNbIotNtn;
        private final int mCountOfDataConnections;
        private final int[] mLastFailCauses;
        private final int mCountOfDataDisconnections;
        private final int mCountOfDataStalls;
        private final int mAverageUplinkBandwidthKbps;
        private final int mAverageDownlinkBandwidthKbps;
        private final int mMinUplinkBandwidthKbps;
        private final int mMaxUplinkBandwidthKbps;
        private final int mMinDownlinkBandwidthKbps;
        private final int mMaxDownlinkBandwidthKbps;
        private final String[] mSatelliteSupportedApps;
        private final int[] mSatelliteSupportedUids;
        private final long[] mPerAppSatelliteDataConsumedBytes;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;
        private final String mPlmn;
        private final boolean mIsWifiEnabled;
        private final boolean mIsWfcEnabled;
        private final boolean mIsWfcRegistered;
        private final int mScreenOnTimeSec;
        private final int mBatteryLevelDropPercent;
        private final boolean mWasChargingDuringSession;
        private final int mBatteryDesignCapacityMah;
        private final long mEnergyConsumedNwh;
        private final @SatelliteConstants.SatelliteEligibilitySource int mEligibilitySource;
        private final boolean mIsWifiConnected;

        private CarrierRoamingSatelliteSessionParams(Builder builder) {
            this.mCarrierId = builder.mCarrierId;
            this.mIsNtnRoamingInHomeCountry = builder.mIsNtnRoamingInHomeCountry;
            this.mTotalSatelliteModeTimeSec = builder.mTotalSatelliteModeTimeSec;
            this.mNumberOfSatelliteConnections = builder.mNumberOfSatelliteConnections;
            this.mAvgDurationOfSatelliteConnectionSec =
                    builder.mAvgDurationOfSatelliteConnectionSec;
            this.mSatelliteConnectionGapMinSec = builder.mSatelliteConnectionGapMinSec;
            this.mSatelliteConnectionGapAvgSec = builder.mSatelliteConnectionGapAvgSec;
            this.mSatelliteConnectionGapMaxSec = builder.mSatelliteConnectionGapMaxSec;
            this.mRsrpAvg = builder.mRsrpAvg;
            this.mRsrpMedian = builder.mRsrpMedian;
            this.mRssnrAvg = builder.mRssnrAvg;
            this.mRssnrMedian = builder.mRssnrMedian;
            this.mCountOfIncomingSms = builder.mCountOfIncomingSms;
            this.mCountOfOutgoingSms = builder.mCountOfOutgoingSms;
            this.mCountOfIncomingMms = builder.mCountOfIncomingMms;
            this.mCountOfOutgoingMms = builder.mCountOfOutgoingMms;
            this.mSupportedSatelliteServices = builder.mSupportedSatelliteServices;
            this.mServiceDataPolicy = builder.mServiceDataPolicy;
            this.mSatelliteDataConsumedBytes =
                    builder.mSatelliteDataConsumedBytes;
            this.mIsMultiSim = builder.mIsMultiSim;
            this.mIsNbIotNtn = builder.mIsNbIotNtn;
            this.mCountOfDataConnections = builder.mCountOfDataConnections;
            this.mLastFailCauses = builder.mLastFailCauses;
            this.mCountOfDataDisconnections = builder.mCountOfDataDisconnections;
            this.mCountOfDataStalls = builder.mCountOfDataStalls;
            this.mAverageUplinkBandwidthKbps =  builder.mAverageUplinkBandwidthKbps;
            this.mAverageDownlinkBandwidthKbps = builder.mAverageDownlinkBandwidthKbps;
            this.mMinUplinkBandwidthKbps = builder.mMinUplinkBandwidthKbps;
            this.mMaxUplinkBandwidthKbps = builder.mMaxUplinkBandwidthKbps;
            this.mMinDownlinkBandwidthKbps = builder.mMinDownlinkBandwidthKbps;
            this.mMaxDownlinkBandwidthKbps = builder.mMaxDownlinkBandwidthKbps;
            this.mSatelliteSupportedApps = builder.mSatelliteSupportedApps;
            this.mSatelliteSupportedUids = builder.mSatelliteSupportedUids;
            this.mPerAppSatelliteDataConsumedBytes = builder.mPerAppSatelliteDataConsumedBytes;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mSessionConnectionMode = builder.mSessionConnectionMode;
            this.mPlmn = builder.mPlmn;
            this.mIsWifiEnabled = builder.mIsWifiEnabled;
            this.mIsWfcEnabled = builder.mIsWfcEnabled;
            this.mIsWfcRegistered = builder.mIsWfcRegistered;
            this.mScreenOnTimeSec = builder.mScreenOnTimeSec;
            this.mBatteryLevelDropPercent = builder.mBatteryLevelDropPercent;
            this.mWasChargingDuringSession = builder.mWasChargingDuringSession;
            this.mBatteryDesignCapacityMah = builder.mBatteryDesignCapacityMah;
            this.mEnergyConsumedNwh = builder.mEnergyConsumedNwh;
            this.mEligibilitySource = builder.mEligibilitySource;
            this.mIsWifiConnected = builder.mIsWifiConnected;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean getIsNtnRoamingInHomeCountry() {
            return mIsNtnRoamingInHomeCountry;
        }

        public int getTotalSatelliteModeTimeSec() {
            return mTotalSatelliteModeTimeSec;
        }

        public int getNumberOfSatelliteConnections() {
            return mNumberOfSatelliteConnections;
        }

        public int getAvgDurationOfSatelliteConnectionSec() {
            return mAvgDurationOfSatelliteConnectionSec;
        }

        public int getSatelliteConnectionGapMinSec() {
            return mSatelliteConnectionGapMinSec;
        }

        public int getSatelliteConnectionGapAvgSec() {
            return mSatelliteConnectionGapAvgSec;
        }

        public int getSatelliteConnectionGapMaxSec() {
            return mSatelliteConnectionGapMaxSec;
        }

        public int getRsrpAvg() {
            return mRsrpAvg;
        }

        public int getRsrpMedian() {
            return mRsrpMedian;
        }

        public int getRssnrAvg() {
            return mRssnrAvg;
        }

        public int getRssnrMedian() {
            return mRssnrMedian;
        }

        public int getCountOfIncomingSms() {
            return mCountOfIncomingSms;
        }

        public int getCountOfOutgoingSms() {
            return mCountOfOutgoingSms;
        }

        public int getCountOfIncomingMms() {
            return mCountOfIncomingMms;
        }

        public int getCountOfOutgoingMms() {
            return mCountOfOutgoingMms;
        }

        public int[] getSupportedSatelliteServices() {
            return mSupportedSatelliteServices;
        }

        public int getServiceDataPolicy() {
            return mServiceDataPolicy;
        }

        public long getSatelliteDataConsumedBytes() {
            return mSatelliteDataConsumedBytes;
        }

        public boolean isMultiSim() {
            return mIsMultiSim;
        }

        public boolean isNbIotNtn() {
            return mIsNbIotNtn;
        }

        public int getCountOfDataConnections() {
            return mCountOfDataConnections;
        }

        public int[] getLastFailCauses() {
            return mLastFailCauses;
        }

        public int getCountOfDataDisconnections() {
            return mCountOfDataDisconnections;
        }

        public int getCountOfDataStalls() {
            return mCountOfDataStalls;
        }

        public int getAverageUplinkBandwidthKbps() {
            return mAverageUplinkBandwidthKbps;
        }

        public int getAverageDownlinkBandwidthKbps() {
            return mAverageDownlinkBandwidthKbps;
        }

        public int getMinimumUplinkBandwidthKbps() {
            return mMinUplinkBandwidthKbps;
        }

        public int getMaximumUplinkBandwidthKbps() {
            return mMaxUplinkBandwidthKbps;
        }

        public int getMinimumDownlinkBandwidthKbps() {
            return mMinDownlinkBandwidthKbps;
        }

        public int getMaximumDownlinkBandwidthKbps() {
            return mMaxDownlinkBandwidthKbps;
        }

        public String[] getSatelliteSupportedApps() {
            return mSatelliteSupportedApps;
        }

        public int[] getSatelliteSupportedUids() {
            return mSatelliteSupportedUids;
        }

        public long[] getPerAppSatelliteDataConsumedBytes() {
            return mPerAppSatelliteDataConsumedBytes;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getSessionConnectionMode() {
            return mSessionConnectionMode;
        }

        public String getPlmn() {
            return mPlmn;
        }

        public boolean isWifiEnabled() {
            return mIsWifiEnabled;
        }

        public boolean isWfcEnabled() {
            return mIsWfcEnabled;
        }

        public boolean isWfcRegistered() {
            return mIsWfcRegistered;
        }

        /**
         * Returns the screen on time period for the session.
         */
        public int getScreenOnTimeSec() {
            return mScreenOnTimeSec;
        }

        /**
         * Returns the battery drop level while the satellite session was enabled.
         * @return the battery drop percentage (0 or a positive integer), or -1 if the measurement
         * was invalid (e.g., failed to retrieve start or end battery level).
         */
        public int getBatteryLevelDropPercent() {
            return mBatteryLevelDropPercent;
        }

        /**
         * Returns {@code true} if the device was charged at any point during the satellite session.
         */
        public boolean wasChargingDuringSession() {
            return mWasChargingDuringSession;
        }

        /**
         * Returns the factory-rated design capacity of the battery in milliampere-hours (mAh).
         */
        public int getBatteryDesignCapacityMah() {
            return mBatteryDesignCapacityMah;
        }

        /**
         * Returns the absolute energy consumed during the satellite session in nanowatt-hours.
         */
        public long getEnergyConsumedNwh() {
            return mEnergyConsumedNwh;
        }

        /**
         * Returns the eligibility source for carrier roaming satellite source.
         */
        public @SatelliteConstants.SatelliteEligibilitySource int getEligibilitySource() {
            return mEligibilitySource;
        }

        /**
         * Returns whether wifi was connected during the session.
         */
        public boolean isWifiConnected() {
            return mIsWifiConnected;
        }

        /**
         * A builder class to create {@link CarrierRoamingSatelliteSessionParams} data structure
         * class
         */
        public static class Builder {
            private int mCarrierId = -1;
            private boolean mIsNtnRoamingInHomeCountry = false;
            private int mTotalSatelliteModeTimeSec = 0;
            private int mNumberOfSatelliteConnections = 0;
            private int mAvgDurationOfSatelliteConnectionSec = 0;
            private int mSatelliteConnectionGapMinSec = 0;
            private int mSatelliteConnectionGapAvgSec = 0;
            private int mSatelliteConnectionGapMaxSec = 0;
            private int mRsrpAvg = 0;
            private int mRsrpMedian = 0;
            private int mRssnrAvg = 0;
            private int mRssnrMedian = 0;
            private int mCountOfIncomingSms = 0;
            private int mCountOfOutgoingSms = 0;
            private int mCountOfIncomingMms = 0;
            private int mCountOfOutgoingMms = 0;
            private int[] mSupportedSatelliteServices = new int[0];
            int mServiceDataPolicy =
                    SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNKNOWN;
            long mSatelliteDataConsumedBytes = 0L;
            private boolean mIsMultiSim = false;
            private boolean mIsNbIotNtn = false;
            private int mCountOfDataConnections = 0;
            private int[] mLastFailCauses = new int[5];
            private int mCountOfDataDisconnections = 0;
            private int mCountOfDataStalls = 0;
            private int mAverageUplinkBandwidthKbps = 0;
            private int mAverageDownlinkBandwidthKbps = 0;
            private int mMinUplinkBandwidthKbps = Integer.MAX_VALUE;
            private int mMaxUplinkBandwidthKbps = 0;
            private int mMinDownlinkBandwidthKbps = Integer.MAX_VALUE;
            private int mMaxDownlinkBandwidthKbps = 0;
            private String[] mSatelliteSupportedApps = null;
            private int[] mSatelliteSupportedUids = new int[5];
            private long[] mPerAppSatelliteDataConsumedBytes = new long[]{0L};
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode =
                    SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
            private String mPlmn = "UNKNOWN";
            private boolean mIsWifiEnabled = false;
            private boolean mIsWfcEnabled = false;
            private boolean mIsWfcRegistered = false;
            private int mScreenOnTimeSec = 0;
            private int mBatteryLevelDropPercent = 0;
            private boolean mWasChargingDuringSession = false;
            private int mBatteryDesignCapacityMah = 0;
            private long mEnergyConsumedNwh = 0;
            private @SatelliteConstants.SatelliteEligibilitySource int mEligibilitySource =
                    SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_UNKNOWN;
            private boolean mIsWifiConnected = false;


            /**
             * Sets carrierId value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets isNtnRoamingInHomeCountry value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setIsNtnRoamingInHomeCountry(boolean isNtnRoamingInHomeCountry) {
                this.mIsNtnRoamingInHomeCountry = isNtnRoamingInHomeCountry;
                return this;
            }

            /**
             * Sets totalSatelliteModeTimeSec value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setTotalSatelliteModeTimeSec(int totalSatelliteModeTimeSec) {
                this.mTotalSatelliteModeTimeSec = totalSatelliteModeTimeSec;
                return this;
            }


            /**
             * Sets numberOfSatelliteConnections value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setNumberOfSatelliteConnections(int numberOfSatelliteConnections) {
                this.mNumberOfSatelliteConnections = numberOfSatelliteConnections;
                return this;
            }

            /**
             * Sets avgDurationOfSatelliteConnectionSec value of
             * {@link CarrierRoamingSatelliteSession} atom then returns Builder class
             */
            public Builder setAvgDurationOfSatelliteConnectionSec(
                    int avgDurationOfSatelliteConnectionSec) {
                this.mAvgDurationOfSatelliteConnectionSec = avgDurationOfSatelliteConnectionSec;
                return this;
            }

            /**
             * Sets satelliteConnectionGapMinSec value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSatelliteConnectionGapMinSec(int satelliteConnectionGapMinSec) {
                this.mSatelliteConnectionGapMinSec = satelliteConnectionGapMinSec;
                return this;
            }

            /**
             * Sets satelliteConnectionGapAvgSec value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSatelliteConnectionGapAvgSec(int satelliteConnectionGapAvgSec) {
                this.mSatelliteConnectionGapAvgSec = satelliteConnectionGapAvgSec;
                return this;
            }

            /**
             * Sets satelliteConnectionGapMaxSec value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSatelliteConnectionGapMaxSec(int satelliteConnectionGapMaxSec) {
                this.mSatelliteConnectionGapMaxSec = satelliteConnectionGapMaxSec;
                return this;
            }

            /**
             * Sets rsrpAvg value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setRsrpAvg(int rsrpAvg) {
                this.mRsrpAvg = rsrpAvg;
                return this;
            }

            /**
             * Sets rsrpMedian value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setRsrpMedian(int rsrpMedian) {
                this.mRsrpMedian = rsrpMedian;
                return this;
            }

            /**
             * Sets rssnrAvg value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setRssnrAvg(int rssnrAvg) {
                this.mRssnrAvg = rssnrAvg;
                return this;
            }

            /**
             * Sets rssnrMedian value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setRssnrMedian(int rssnrMedian) {
                this.mRssnrMedian = rssnrMedian;
                return this;
            }


            /**
             * Sets countOfIncomingSms value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfIncomingSms(int countOfIncomingSms) {
                this.mCountOfIncomingSms = countOfIncomingSms;
                return this;
            }

            /**
             * Sets countOfOutgoingSms value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfOutgoingSms(int countOfOutgoingSms) {
                this.mCountOfOutgoingSms = countOfOutgoingSms;
                return this;
            }

            /**
             * Sets countOfIncomingMms value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfIncomingMms(int countOfIncomingMms) {
                this.mCountOfIncomingMms = countOfIncomingMms;
                return this;
            }

            /**
             * Sets countOfOutgoingMms value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfOutgoingMms(int countOfOutgoingMms) {
                this.mCountOfOutgoingMms = countOfOutgoingMms;
                return this;
            }

            /**
             * Sets supportedSatelliteServices value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSupportedSatelliteServices(int[] supportedSatelliteServices) {
                this.mSupportedSatelliteServices = supportedSatelliteServices;
                Arrays.sort(this.mSupportedSatelliteServices);
                return this;
            }

            /**
             * Sets serviceDataPolicy value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setServiceDataPolicy(int serviceDataPolicy) {
                this.mServiceDataPolicy = serviceDataPolicy;
                return this;
            }

            /**
             * Sets satelliteDataConsumedPerSessionBytes value of
             * {@link CarrierRoamingSatelliteSession} atom then returns Builder class
             */
            public Builder setSatelliteDataConsumedBytes(
                    long satelliteDataConsumedPerSessionBytes) {
                this.mSatelliteDataConsumedBytes = satelliteDataConsumedPerSessionBytes;
                return this;
            }

            /**
             * Sets isMultiSim value of {@link CarrierRoamingSatelliteSession} atom, which indicates
             * whether multi sim are activated or not, then returns Builder class
             */
            public Builder setIsMultiSim(boolean isMultiSim) {
                this.mIsMultiSim = isMultiSim;
                return this;
            }

            /**
             * Sets isNbIotNtn value of {@link CarrierRoamingSatelliteSession} atom, which indicates
             * whether satellite service tech is NB-IoT-NTN or not
             */
            public Builder setIsNbIotNtn(boolean isNbIotNtn) {
                this.mIsNbIotNtn = isNbIotNtn;
                return this;
            }

            /**
             * Sets countOfDataConnections value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfDataConnections(int countOfDataConnections) {
                this.mCountOfDataConnections = countOfDataConnections;
                return this;
            }

            /**
             * Sets lastFailCauses value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setLastFailCauses(int[] lastFailCauses) {
                this.mLastFailCauses = lastFailCauses;
                Arrays.sort(this.mLastFailCauses);
                return this;
            }

            /**
             * Sets countOfDataDisconnections value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfDataDisconnections(int countOfDataDisconnections) {
                this.mCountOfDataDisconnections = countOfDataDisconnections;
                return this;
            }

            /**
             * Sets countOfDataStalls value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setCountOfDataStalls(int countOfDataStalls) {
                this.mCountOfDataStalls = countOfDataStalls;
                return this;
            }

            /**
             * Sets averageUplinkBandwidth value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setAverageUplinkBandwidthKbps(int averageUplinkBandwidthKbps) {
                this.mAverageUplinkBandwidthKbps = averageUplinkBandwidthKbps;
                return this;
            }

            /**
             * Sets averageDownlinkBandwidth value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setAverageDownlinkBandwidthKbps(int averageDownlinkBandwidthKbps) {
                this.mAverageDownlinkBandwidthKbps = averageDownlinkBandwidthKbps;
                return this;
            }

            /**
             * Sets minUplinkBandwidth value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setMinimumUplinkBandwidthKbps(int minUplinkBandwidthKbps) {
                this.mMinUplinkBandwidthKbps = minUplinkBandwidthKbps;
                return this;
            }

            /**
             * Sets maxUplinkBandwidth value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setMaximumUplinkBandwidthKbps(int maxUplinkBandwidthKbps) {
                this.mMaxUplinkBandwidthKbps = maxUplinkBandwidthKbps;
                return this;
            }

            /**
             * Sets minDownlinkBandwidth value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setMinimumDownlinkBandwidthKbps(int minDownlinkBandwidthKbps) {
                this.mMinDownlinkBandwidthKbps = minDownlinkBandwidthKbps;
                return this;
            }

            /**
             * Sets maxUplinkBandwidth value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setMaximumDownlinkBandwidthKbps(int maxDownlinkBandwidthKbps) {
                this.mMaxDownlinkBandwidthKbps = maxDownlinkBandwidthKbps;
                return this;
            }

            /**
             * Sets satelliteSupportedApps value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSatelliteSupportedApps(String[] satelliteSupportedApps) {
                this.mSatelliteSupportedApps = satelliteSupportedApps;
                return this;
            }

            /**
             * Sets satelliteSupportedUids value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setSatelliteSupportedUids(int[] satelliteSupportedUids) {
                this.mSatelliteSupportedUids = satelliteSupportedUids;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link CarrierRoamingSatelliteSession} atom,
             * which indicates the global connect type
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets sessionConnectionMode value of {@link CarrierRoamingSatelliteSession} atom,
             * which indicates the session connect type
             */
            public Builder setSessionConnectionMode(int sessionConnectionMode) {
                this.mSessionConnectionMode = sessionConnectionMode;
                return this;
            }

            /**
             * Sets satellite plmn value of {@link CarrierRoamingSatelliteSession} atom,
             * which indicates the session connect type
             */
            public Builder setPlmn(String plmn) {
                this.mPlmn = plmn;
                return this;
            }

            /**
             * Sets perAppSatelliteDataConsumedBytes value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class
             */
            public Builder setPerAppSatelliteDataConsumedBytes(
                    long[] perAppSatelliteDataConsumedBytes) {
                this.mPerAppSatelliteDataConsumedBytes = perAppSatelliteDataConsumedBytes;
                return this;
            }

            /**
             * Sets isWifiEnabled value of {@link CarrierRoamingSatelliteSession} atom, which
             * indicates if wifi is enabled during the session
             */
            public Builder setIsWifiEnabled(boolean isWifiEnabled) {
                this.mIsWifiEnabled = isWifiEnabled;
                return this;
            }

            /**
             * Sets isWfcEnabled value of {@link CarrierRoamingSatelliteSession} atom, which
             * indicates if wifi calling is enabled during the session
             */
            public Builder setIsWfcEnabled(boolean isWfcEnabled) {
                this.mIsWfcEnabled = isWfcEnabled;
                return this;
            }

            /**
             * Sets isWfcRegistered value of {@link CarrierRoamingSatelliteSession} atom, which
             * indicates if wifi calling is registered during the session
             */
            public Builder setIsWfcRegistered(boolean isWfcRegistered) {
                this.mIsWfcRegistered = isWfcRegistered;
                return this;
            }

            /**
             * Sets screenOnTimeSec value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setScreenOnTimeSec(int screenOnTimeSec) {
                this.mScreenOnTimeSec = screenOnTimeSec;
                return this;
            }

            /**
             * Sets batteryLevelDropPercent value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setBatteryLevelDropPercent(int batteryLevelDropPercent) {
                this.mBatteryLevelDropPercent = batteryLevelDropPercent;
                return this;
            }

            /**
             * Sets whether the charger was connected during satellite session was enabled in the
             * {@link CarrierRoamingSatelliteSession} atom then returns Builder class
             */
            public Builder setWasChargingDuringSession(boolean wasChargingDuringSession) {
                this.mWasChargingDuringSession = wasChargingDuringSession;
                return this;
            }

            /**
             * Sets batteryDesignCapacityMah value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setBatteryDesignCapacityMah(int batteryDesignCapacityMah) {
                this.mBatteryDesignCapacityMah = batteryDesignCapacityMah;
                return this;
            }

            /**
             * Sets energyConsumedNwh value of {@link CarrierRoamingSatelliteSession} atom
             * then returns Builder class
             */
            public Builder setEnergyConsumedNwh(long energyConsumedNwh) {
                this.mEnergyConsumedNwh = energyConsumedNwh;
                return this;
            }

            /**
             * Sets the eligibility source value of {@link CarrierRoamingSatelliteSession}
             * atom then returns Builder class.
             */
            public Builder setEligibilitySource(
                    @SatelliteConstants.SatelliteEligibilitySource int eligibilitySource) {
                this.mEligibilitySource = eligibilitySource;
                return this;
            }

            /**
             * Sets whether wifi was connected during the session.
             */
            public Builder setIsWifiConnected(boolean isWifiConnected) {
                this.mIsWifiConnected = isWifiConnected;
                return this;
            }

            /**
             * Returns CarrierRoamingSatelliteSessionParams, which contains whole component of
             * {@link CarrierRoamingSatelliteSession} atom
             */
            public CarrierRoamingSatelliteSessionParams build() {
                return new CarrierRoamingSatelliteSessionParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "CarrierRoamingSatelliteSessionParams("
                    + "carrierId=" + mCarrierId
                    + ", isNtnRoamingInHomeCountry=" + mIsNtnRoamingInHomeCountry
                    + ", totalSatelliteModeTimeSec=" + mTotalSatelliteModeTimeSec
                    + ", numberOfSatelliteConnections=" + mNumberOfSatelliteConnections
                    + ", avgDurationOfSatelliteConnectionSec="
                    + mAvgDurationOfSatelliteConnectionSec
                    + ", satelliteConnectionGapMinSec=" + mSatelliteConnectionGapMinSec
                    + ", satelliteConnectionGapAvgSec=" + mSatelliteConnectionGapAvgSec
                    + ", satelliteConnectionGapMaxSec=" + mSatelliteConnectionGapMaxSec
                    + ", rsrpAvg=" + mRsrpAvg
                    + ", rsrpMedian=" + mRsrpMedian
                    + ", rssnrAvg=" + mRssnrAvg
                    + ", rssnrMedian=" + mRssnrMedian
                    + ", countOfIncomingSms=" + mCountOfIncomingSms
                    + ", countOfOutgoingSms=" + mCountOfOutgoingSms
                    + ", countOfIncomingMms=" + mCountOfIncomingMms
                    + ", countOfOutgoingMms=" + mCountOfOutgoingMms
                    + ", supportedSatelliteServices=" + Arrays.toString(mSupportedSatelliteServices)
                    + ", serviceDataPolicy=" + mServiceDataPolicy
                    + ", SatelliteDataConsumedBytes=" + mSatelliteDataConsumedBytes
                    + ", isMultiSim=" + mIsMultiSim
                    + ", isNbIotNtn=" + mIsNbIotNtn
                    + ", countOfDataConnections=" + mCountOfDataConnections
                    + ", lastFailCauses=" +  Arrays.toString(mLastFailCauses)
                    + ", countOfDataDisconnections=" + mCountOfDataDisconnections
                    + ", countOfDataStalls=" + mCountOfDataStalls
                    + ", averageUplinkBandwidthKbps=" + mAverageUplinkBandwidthKbps
                    + ", averageDownlinkBandwidthKbps=" + mAverageDownlinkBandwidthKbps
                    + ", minUplinkBandwidthKbps=" + mMinUplinkBandwidthKbps
                    + ", maxUplinkBandwidthKbps=" + mMaxUplinkBandwidthKbps
                    + ", minDownlinkBandwidthKbps=" + mMinDownlinkBandwidthKbps
                    + ", maxDownlinkBandwidthKbps=" + mMaxDownlinkBandwidthKbps
                    + ", satelliteSupportedApps=" + Arrays.toString(mSatelliteSupportedApps)
                    + ", satelliteSupportedUids=" + Arrays.toString(mSatelliteSupportedUids)
                    + ", perAppSatelliteDataConsumedBytes=" + Arrays.toString(
                    mPerAppSatelliteDataConsumedBytes)
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", sessionConnectionMode=" + mSessionConnectionMode
                    + ", plmn=" + mPlmn
                    + ", mIsWifiEnabled=" + mIsWifiEnabled
                    + ", mIsWfcEnabled=" + mIsWfcEnabled
                    + ", mIsWfcRegistered=" + mIsWfcRegistered
                    + ", ScreenOntimeSec=" + mScreenOnTimeSec
                    + ", BatteryLevelDropPercent=" + mBatteryLevelDropPercent
                    + ", WasChargingDuringSession=" + mWasChargingDuringSession
                    + ", BatteryDesignCapacityMah=" + mBatteryDesignCapacityMah
                    + ", EnergyConsumedNwh=" + mEnergyConsumedNwh
                    + ", eligibilitySource=" + mEligibilitySource
                    + ", isWifiConnected=" + mIsWifiConnected
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link CarrierRoamingSatelliteControllerStats}
     * atom. Refer to {@link #onCarrierRoamingSatelliteControllerStatsMetrics(
     * CarrierRoamingSatelliteControllerStatsParams)}.
     */
    public static class CarrierRoamingSatelliteControllerStatsParams {
        private final int mConfigDataSource;
        private final int mCountOfEntitlementStatusQueryRequest;
        private final int mCountOfSatelliteConfigUpdateRequest;
        private final int mCountOfSatelliteNotificationDisplayed;
        private final int mSatelliteSessionGapMinSec;
        private final int mSatelliteSessionGapAvgSec;
        private final int mSatelliteSessionGapMaxSec;
        private final int mCarrierId;
        private final boolean mIsDeviceEntitled;
        private final boolean mIsMultiSim;
        private final int mCountOfSatelliteSessions;
        private final boolean mIsNbIotNtn;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final int mCountOfSessionConnectionModeAutomatic;
        private final int mCountOfSessionConnectionModeManual;
        private final int mServiceDataPolicy;
        private final int mSessionDurationSec;
        private final boolean mIsSatelliteAttachSupported;
        private final @SatelliteConstants.SatelliteEligibilitySource int mEligibilitySource;

        private CarrierRoamingSatelliteControllerStatsParams(Builder builder) {
            this.mConfigDataSource = builder.mConfigDataSource;
            this.mCountOfEntitlementStatusQueryRequest =
                    builder.mCountOfEntitlementStatusQueryRequest;
            this.mCountOfSatelliteConfigUpdateRequest =
                    builder.mCountOfSatelliteConfigUpdateRequest;
            this.mCountOfSatelliteNotificationDisplayed =
                    builder.mCountOfSatelliteNotificationDisplayed;
            this.mCountOfSessionConnectionModeAutomatic =
                    builder.mCountOfSessionConnectionModeAutomatic;
            this.mCountOfSessionConnectionModeManual =
                    builder.mCountOfSessionConnectionModeManual;
            this.mSatelliteSessionGapMinSec = builder.mSatelliteSessionGapMinSec.orElse(
                    SATELLITE_SESSION_GAP_INVALID_SEC);
            this.mSatelliteSessionGapAvgSec = builder.mSatelliteSessionGapAvgSec.orElse(
                    SATELLITE_SESSION_GAP_INVALID_SEC);
            this.mSatelliteSessionGapMaxSec = builder.mSatelliteSessionGapMaxSec.orElse(
                    SATELLITE_SESSION_GAP_INVALID_SEC);
            this.mCarrierId = builder.mCarrierId;
            this.mIsDeviceEntitled = builder.mIsDeviceEntitled;
            this.mIsMultiSim = builder.mIsMultiSim;
            this.mCountOfSatelliteSessions = builder.mCountOfSatelliteSessions;
            this.mIsNbIotNtn = builder.mIsNbIotNtn;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mServiceDataPolicy = builder.mServiceDataPolicy;
            this.mSessionDurationSec = builder.mSessionDurationSec;
            this.mIsSatelliteAttachSupported = builder.mIsSatelliteAttachSupported;
            this.mEligibilitySource = builder.mEligibilitySource;
        }

        public int getConfigDataSource() {
            return mConfigDataSource;
        }

        public int getCountOfEntitlementStatusQueryRequest() {
            return mCountOfEntitlementStatusQueryRequest;
        }

        public int getCountOfSatelliteConfigUpdateRequest() {
            return mCountOfSatelliteConfigUpdateRequest;
        }

        public int getCountOfSatelliteNotificationDisplayed() {
            return mCountOfSatelliteNotificationDisplayed;
        }

        public int getSatelliteSessionGapMinSec() {
            return mSatelliteSessionGapMinSec;
        }

        public int getSatelliteSessionGapAvgSec() {
            return mSatelliteSessionGapAvgSec;
        }

        public int getSatelliteSessionGapMaxSec() {
            return mSatelliteSessionGapMaxSec;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public boolean isDeviceEntitled() {
            return mIsDeviceEntitled;
        }

        public boolean isMultiSim() {
            return mIsMultiSim;
        }

        public int getCountOfSatelliteSessions() {
            return mCountOfSatelliteSessions;
        }

        public boolean isNbIotNtn() {
            return mIsNbIotNtn;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getCountOfSessionConnectionModeAutomatic() {
            return mCountOfSessionConnectionModeAutomatic;
        }

        public int getCountOfSessionConnectionModeManual() {
            return mCountOfSessionConnectionModeManual;
        }

        public int getServiceDataPolicy() {
            return mServiceDataPolicy;
        }

        public int getSessionDurationSec() {
            return mSessionDurationSec;
        }

        /** Returns whether the device is able to scan satellite network. */
        public boolean isSatelliteAttachSupported() {
            return mIsSatelliteAttachSupported;
        }

        /** Returns the eligibility condition for corresponding carrier ID. */
        public @SatelliteConstants.SatelliteEligibilitySource int getEligibilitySource() {
            return mEligibilitySource;
        }
        /**
         * A builder class to create {@link CarrierRoamingSatelliteControllerStatsParams}
         * data structure class
         */
        public static class Builder {
            private int mConfigDataSource = SatelliteConstants.CONFIG_DATA_SOURCE_UNKNOWN;
            private int mCountOfEntitlementStatusQueryRequest = 0;
            private int mCountOfSatelliteConfigUpdateRequest = 0;
            private int mCountOfSatelliteNotificationDisplayed = 0;
            private Optional<Integer> mSatelliteSessionGapMinSec = Optional.empty();
            private Optional<Integer> mSatelliteSessionGapAvgSec = Optional.empty();
            private Optional<Integer> mSatelliteSessionGapMaxSec = Optional.empty();
            private int mCarrierId;
            private boolean mIsDeviceEntitled;
            private boolean mIsMultiSim;
            private int mCountOfSatelliteSessions = 0;
            private boolean mIsNbIotNtn;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private int mCountOfSessionConnectionModeAutomatic = 0;
            private int mCountOfSessionConnectionModeManual = 0;
            private int mServiceDataPolicy =
                    SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNKNOWN;
            private int mSessionDurationSec = 0;
            private boolean mIsSatelliteAttachSupported;
            private @SatelliteConstants.SatelliteEligibilitySource int mEligibilitySource =
                    SatelliteConstants.SATELLITE_ELIGIBILITY_SOURCE_UNKNOWN;

            /**
             * Sets configDataSource value of {@link CarrierRoamingSatelliteControllerStats} atom
             * then returns Builder class
             */
            public Builder setConfigDataSource(int configDataSource) {
                this.mConfigDataSource = configDataSource;
                return this;
            }

            /**
             * Sets countOfEntitlementStatusQueryRequest value of
             * {@link CarrierRoamingSatelliteControllerStats} atom then returns Builder class
             */
            public Builder setCountOfEntitlementStatusQueryRequest(
                    int countOfEntitlementStatusQueryRequest) {
                this.mCountOfEntitlementStatusQueryRequest = countOfEntitlementStatusQueryRequest;
                return this;
            }

            /**
             * Sets countOfSatelliteConfigUpdateRequest value of
             * {@link CarrierRoamingSatelliteControllerStats} atom then returns Builder class
             */
            public Builder setCountOfSatelliteConfigUpdateRequest(
                    int countOfSatelliteConfigUpdateRequest) {
                this.mCountOfSatelliteConfigUpdateRequest = countOfSatelliteConfigUpdateRequest;
                return this;
            }

            /**
             * Sets countOfSatelliteNotificationDisplayed value of
             * {@link CarrierRoamingSatelliteControllerStats} atom then returns Builder class
             */
            public Builder setCountOfSatelliteNotificationDisplayed(
                    int countOfSatelliteNotificationDisplayed) {
                this.mCountOfSatelliteNotificationDisplayed = countOfSatelliteNotificationDisplayed;
                return this;
            }

            /**
             * Sets satelliteSessionGapMinSec value of
             * {@link CarrierRoamingSatelliteControllerStats} atom then returns Builder class
             */
            public Builder setSatelliteSessionGapMinSec(int satelliteSessionGapMinSec) {
                this.mSatelliteSessionGapMinSec = Optional.of(satelliteSessionGapMinSec);
                return this;
            }

            /**
             * Sets satelliteSessionGapAvgSec value of
             * {@link CarrierRoamingSatelliteControllerStats} atom then returns Builder class
             */
            public Builder setSatelliteSessionGapAvgSec(int satelliteSessionGapAvgSec) {
                this.mSatelliteSessionGapAvgSec = Optional.of(satelliteSessionGapAvgSec);
                return this;
            }

            /**
             * Sets satelliteSessionGapMaxSec value of
             * {@link CarrierRoamingSatelliteControllerStats} atom then returns Builder class
             */
            public Builder setSatelliteSessionGapMaxSec(int satelliteSessionGapMaxSec) {
                this.mSatelliteSessionGapMaxSec = Optional.of(satelliteSessionGapMaxSec);
                return this;
            }

            /** Sets the currently active NB-IoT NTN carrier ID. */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /** Sets whether the device is currently entitled or not. */
            public Builder setIsDeviceEntitled(boolean isDeviceEntitled) {
                this.mIsDeviceEntitled = isDeviceEntitled;
                return this;
            }

            /** Sets whether the device is in DSDS state or not. */
            public Builder setIsMultiSim(boolean isMultiSim) {
                this.mIsMultiSim = isMultiSim;
                return this;
            }

            /**
             * Increase the countOfSatelliteSession value of
             * {@link CarrierRoamingSatelliteControllerStats} atom by one then returns Builder class
             */
            public Builder increaseCountOfSatelliteSessions() {
                this.mCountOfSatelliteSessions++;
                return this;
            }

            /**
             * Increase the count of automatic satellite sessions value of
             * {@link CarrierRoamingSatelliteControllerStats} atom by one then returns Builder class
             */
            public Builder increaseCountOfSessionConnectionModeAutomatic(boolean automatic) {
                if (!automatic) {
                    return this;
                }
                this.mCountOfSessionConnectionModeAutomatic++;
                return this;
            }

            /**
             * Increase the count of manual satellite sessions value of
             * {@link CarrierRoamingSatelliteControllerStats} atom by one then returns Builder class
             */
            public Builder increaseCountOfSessionConnectionModeManual(boolean manual) {
                if (!manual) {
                    return this;
                }
                this.mCountOfSessionConnectionModeManual++;
                return this;
            }

            /** Sets whether the device is in NB-NoT-NTN state or not. */
            public Builder setIsNbIotNtn(boolean isNbIotNtn) {
                this.mIsNbIotNtn = isNbIotNtn;
                return this;
            }

            /** Sets whether the global connect type is hybrid or auto or manual. */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /** Sets the session duration in seconds when each session ends. */
            public Builder setSessionDurationSec(int sessionDurationSec) {
                this.mSessionDurationSec = sessionDurationSec;
                return this;
            }

            /**
             * Sets serviceDataPolicy value of {@link CarrierRoamingSatelliteControllerStats} atom
             * then returns Builder class
             */
            public Builder setServiceDataPolicy(int serviceDataPolicy) {
                this.mServiceDataPolicy = serviceDataPolicy;
                return this;
            }

            /** Sets whether the device is able to scan satellite network or not. */
            public Builder setSatelliteAttachSupported(boolean isSatelliteAttachSupported) {
                this.mIsSatelliteAttachSupported = isSatelliteAttachSupported;
                return this;
            }

            /**
             * Sets the eligibility source value of {@link CarrierRoamingSatelliteControllerStats}
             * atom then returns Builder class.
             */
            public Builder setEligibilitySource(
                    @SatelliteConstants.SatelliteEligibilitySource int eligibilitySource) {
                this.mEligibilitySource = eligibilitySource;
                return this;
            }

            /**
             * Returns CarrierRoamingSatelliteControllerStatsParams, which contains whole component
             * of {@link CarrierRoamingSatelliteControllerStats} atom
             */
            public CarrierRoamingSatelliteControllerStatsParams build() {
                return new CarrierRoamingSatelliteControllerStatsParams(Builder.this);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            CarrierRoamingSatelliteControllerStatsParams that =
                    (CarrierRoamingSatelliteControllerStatsParams) obj;
            return mConfigDataSource == that.getConfigDataSource()
                    && mCountOfEntitlementStatusQueryRequest
                    == that.getCountOfEntitlementStatusQueryRequest()
                    && mCountOfSatelliteConfigUpdateRequest
                    == that.getCountOfSatelliteConfigUpdateRequest()
                    && mCountOfSatelliteNotificationDisplayed
                    == that.getCountOfSatelliteNotificationDisplayed()
                    && mSatelliteSessionGapMinSec == that.getSatelliteSessionGapMinSec()
                    && mSatelliteSessionGapAvgSec == that.getSatelliteSessionGapAvgSec()
                    && mSatelliteSessionGapMaxSec == that.getSatelliteSessionGapMaxSec()
                    && mCarrierId == that.getCarrierId()
                    && mIsDeviceEntitled == that.isDeviceEntitled()
                    && mIsMultiSim == that.isMultiSim()
                    && mCountOfSatelliteSessions == that.getCountOfSatelliteSessions()
                    && mIsNbIotNtn == that.isNbIotNtn()
                    && mSupportedConnectionMode == that.getSupportedConnectionMode()
                    && mCountOfSessionConnectionModeAutomatic == that
                    .getCountOfSessionConnectionModeAutomatic()
                    && mCountOfSessionConnectionModeManual == that
                    .getCountOfSessionConnectionModeManual()
                    && mServiceDataPolicy == that.getServiceDataPolicy()
                    && mSessionDurationSec == that.getSessionDurationSec()
                    && mIsSatelliteAttachSupported == that.isSatelliteAttachSupported()
                    && mEligibilitySource == that.getEligibilitySource();
        }

        @Override
        public int hashCode() {
            return Objects.hash(mConfigDataSource, mCountOfEntitlementStatusQueryRequest,
                    mCountOfSatelliteConfigUpdateRequest, mCountOfSatelliteNotificationDisplayed,
                    mSatelliteSessionGapMinSec, mSatelliteSessionGapAvgSec,
                    mSatelliteSessionGapMaxSec, mCarrierId, mIsDeviceEntitled, mIsMultiSim,
                    mCountOfSatelliteSessions, mIsNbIotNtn, mSupportedConnectionMode,
                    mCountOfSessionConnectionModeAutomatic, mCountOfSessionConnectionModeManual,
                    mSessionDurationSec, mIsSatelliteAttachSupported, mEligibilitySource);
        }

        @Override
        public String toString() {
            return "CarrierRoamingSatelliteControllerStatsParams("
                    + "configDataSource=" + mConfigDataSource
                    + ", countOfEntitlementStatusQueryRequest="
                    + mCountOfEntitlementStatusQueryRequest
                    + ", countOfSatelliteConfigUpdateRequest="
                    + mCountOfSatelliteConfigUpdateRequest
                    + ", countOfSatelliteNotificationDisplayed="
                    + mCountOfSatelliteNotificationDisplayed
                    + ", satelliteSessionGapMinSec=" + mSatelliteSessionGapMinSec
                    + ", satelliteSessionGapAvgSec=" + mSatelliteSessionGapAvgSec
                    + ", satelliteSessionGapMaxSec=" + mSatelliteSessionGapMaxSec
                    + ", carrierId=" + mCarrierId
                    + ", isDeviceEntitled=" + mIsDeviceEntitled
                    + ", isMultiSim=" + mIsMultiSim
                    + ", countOfSatelliteSession=" + mCountOfSatelliteSessions
                    + ", isNbIotNtn=" + mIsNbIotNtn
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", countOfSessionConnectionModeAutomatic="
                    + mCountOfSessionConnectionModeAutomatic
                    + ", countOfSessionConnectionModeManual="
                    + mCountOfSessionConnectionModeManual
                    + ", serviceDataPolicy=" + mServiceDataPolicy
                    + ", totalSessionDurationSec" + mSessionDurationSec
                    + ", satelliteAttachSupported=" + mIsSatelliteAttachSupported
                    + ", eligibilitySource=" + mEligibilitySource
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteEntitlement} atom.
     * Refer to {@link #onSatelliteEntitlementMetrics(SatelliteEntitlementParams)}.
     */
    public static class SatelliteEntitlementParams {
        private final int mCarrierId;
        private final int mResult;
        private final int mEntitlementStatus;
        private final boolean mIsRetry;
        private final int mCount;
        private final boolean mIsAllowedServiceEntitlement;
        private final int[] mEntitlementServiceType;
        private final int mEntitlementDataPolicy;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final int mHttpStatusCode;
        private final @SatelliteConstants.SatelliteEntitlementQueryTrigger int mTriggerEvent;

        private SatelliteEntitlementParams(Builder builder) {
            this.mCarrierId = builder.mCarrierId;
            this.mResult = builder.mResult;
            this.mEntitlementStatus = builder.mEntitlementStatus;
            this.mIsRetry = builder.mIsRetry;
            this.mCount = builder.mCount;
            this.mIsAllowedServiceEntitlement = builder.mIsAllowedServiceEntitlement;
            this.mEntitlementServiceType = builder.mEntitlementServiceType;
            this.mEntitlementDataPolicy = builder.mEntitlementDataPolicy;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mHttpStatusCode = builder.mHttpStatusCode;
            this.mTriggerEvent = builder.mTriggerEvent;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        public int getResult() {
            return mResult;
        }

        public int getEntitlementStatus() {
            return mEntitlementStatus;
        }

        public boolean getIsRetry() {
            return mIsRetry;
        }

        public int getCount() {
            return mCount;
        }

        public boolean getIsAllowedServiceEntitlement() {
            return mIsAllowedServiceEntitlement;
        }

        public int[] getEntitlementServiceType() {
            return mEntitlementServiceType;
        }

        public int getEntitlementDataPolicy() {
            return mEntitlementDataPolicy;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getHttpStatusCode() {
            return mHttpStatusCode;
        }

        public int getTriggerEvent() {
            return mTriggerEvent;
        }

        /**
         * A builder class to create {@link SatelliteEntitlementParams} data structure class
         */
        public static class Builder {
            private int mCarrierId = -1;
            private int mResult = -1;
            private int mEntitlementStatus = -1;
            private boolean mIsRetry = false;
            private int mCount = -1;
            private boolean mIsAllowedServiceEntitlement = false;
            private int[] mEntitlementServiceType = new int[0];
            private int mEntitlementDataPolicy =
                    SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNKNOWN;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private int mHttpStatusCode = 0;
            private @SatelliteConstants.SatelliteEntitlementQueryTrigger int mTriggerEvent =
                    SatelliteConstants.SATELLITE_ENTITLEMENT_QUERY_TRIGGER_UNKNOWN;

            /**
             * Sets carrierId value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /**
             * Sets result value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setResult(int result) {
                this.mResult = result;
                return this;
            }

            /**
             * Sets entitlementStatus value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setEntitlementStatus(int entitlementStatus) {
                this.mEntitlementStatus = entitlementStatus;
                return this;
            }

            /**
             * Sets isRetry value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setIsRetry(boolean isRetry) {
                this.mIsRetry = isRetry;
                return this;
            }

            /**
             * Sets count value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setCount(int count) {
                this.mCount = count;
                return this;
            }

            /**
             * Sets isAllowedServiceEntitlement value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setIsAllowedServiceEntitlement(boolean isAllowedServiceEntitlement) {
                this.mIsAllowedServiceEntitlement = isAllowedServiceEntitlement;
                return this;
            }

            /**
             * Sets entitlementServiceType value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setEntitlementServiceType(int[] entitlementServiceType) {
                this.mEntitlementServiceType = entitlementServiceType;
                Arrays.sort(this.mEntitlementServiceType);
                return this;
            }

            /**
             * Sets entitlementDataPolicy value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setEntitlementDataPolicy(int entitlementDataPolicy) {
                this.mEntitlementDataPolicy = entitlementDataPolicy;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteEntitlement} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets httpStatusCode value of {@link SatelliteEntitlement} atom
             * then returns Builder class.
             */
            public Builder setHttpStatusCode(int httpStatusCode) {
                this.mHttpStatusCode = httpStatusCode;
                return this;
            }

            /**
             * Sets triggerEvent value of {@link SatelliteEntitlement} atom
             * then returns Builder class.
             */
            public Builder setTriggerEvent(int triggerEvent) {
                this.mTriggerEvent = triggerEvent;
                return this;
            }

            /**
             * Returns SatelliteEntitlementParams, which contains whole component of
             * {@link SatelliteEntitlement} atom
             */
            public SatelliteEntitlementParams build() {
                return new SatelliteEntitlementParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "SatelliteEntitlementParams("
                    + "carrierId=" + mCarrierId
                    + ", result=" + mResult
                    + ", entitlementStatus=" + mEntitlementStatus
                    + ", isRetry=" + mIsRetry
                    + ", count=" + mCount
                    + ", isAllowedServiceEntitlement=" + mIsAllowedServiceEntitlement
                    + ", entitlementServiceType=" + Arrays.toString(mEntitlementServiceType)
                    + ", entitlementServicePolicy=" + mEntitlementDataPolicy
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", HttpStatusCode=" + mHttpStatusCode
                    + ", TriggerEvent=" + mTriggerEvent
                    + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteConfigUpdater} atom.
     * Refer to {@link #onSatelliteConfigUpdaterMetrics(SatelliteConfigUpdaterParams)}.
     */
    public static class SatelliteConfigUpdaterParams {
        private final int mConfigVersion;
        private final int mOemConfigResult;
        private final int mCarrierConfigResult;
        private final int mCount;

        private SatelliteConfigUpdaterParams(Builder builder) {
            this.mConfigVersion = builder.mConfigVersion;
            this.mOemConfigResult = builder.mOemConfigResult;
            this.mCarrierConfigResult = builder.mCarrierConfigResult;
            this.mCount = builder.mCount;
        }

        public int getConfigVersion() {
            return mConfigVersion;
        }

        public int getOemConfigResult() {
            return mOemConfigResult;
        }

        public int getCarrierConfigResult() {
            return mCarrierConfigResult;
        }

        public int getCount() {
            return mCount;
        }

        /**
         * A builder class to create {@link SatelliteConfigUpdaterParams} data structure class
         */
        public static class Builder {
            private int mConfigVersion = -1;
            private int mOemConfigResult = -1;
            private int mCarrierConfigResult = -1;
            private int mCount = -1;

            /**
             * Sets configVersion value of {@link SatelliteConfigUpdater} atom
             * then returns Builder class
             */
            public Builder setConfigVersion(int configVersion) {
                this.mConfigVersion = configVersion;
                return this;
            }

            /**
             * Sets oemConfigResult value of {@link SatelliteConfigUpdater} atom
             * then returns Builder class
             */
            public Builder setOemConfigResult(int oemConfigResult) {
                this.mOemConfigResult = oemConfigResult;
                return this;
            }

            /**
             * Sets carrierConfigResult value of {@link SatelliteConfigUpdater} atom
             * then returns Builder class
             */
            public Builder setCarrierConfigResult(int carrierConfigResult) {
                this.mCarrierConfigResult = carrierConfigResult;
                return this;
            }

            /**
             * Sets count value of {@link SatelliteConfigUpdater} atom
             * then returns Builder class
             */
            public Builder setCount(int count) {
                this.mCount = count;
                return this;
            }

            /**
             * Returns SatelliteConfigUpdaterParams, which contains whole component of
             * {@link SatelliteConfigUpdater} atom
             */
            public SatelliteConfigUpdaterParams build() {
                return new SatelliteConfigUpdaterParams(Builder.this);
            }
        }

        @Override
        public String toString() {
            return "SatelliteConfigUpdaterParams("
                    + "configVersion=" + mConfigVersion
                    + ", oemConfigResult=" + mOemConfigResult
                    + ", carrierConfigResult=" + mCarrierConfigResult
                    + ", count=" + mCount + ")";
        }
    }

    /**
     * A data class to contain whole component of {@link SatelliteAccessControllerParams} atom.
     * Refer to {@link #onSatelliteAccessControllerMetrics(SatelliteAccessControllerParams)}.
     */
    public static class SatelliteAccessControllerParams {
        private final @SatelliteConstants.AccessControlType int mAccessControlType;
        private final long mLocationQueryTimeMillis;
        private final long mOnDeviceLookupTimeMillis;
        private final long mTotalCheckingTimeMillis;
        private final boolean mIsAllowed;
        private final boolean mIsEmergency;
        private final @SatelliteManager.SatelliteResult int mResultCode;
        private final String[] mCountryCodes;
        private final @SatelliteConstants.ConfigDataSource int mConfigDataSource;
        private final int mCarrierId;
        private final int mTriggeringEvent;
        private final boolean mIsNtnOnlyCarrier;
        private final @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
        private final @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;

        private SatelliteAccessControllerParams(Builder builder) {
            this.mAccessControlType = builder.mAccessControlType;
            this.mLocationQueryTimeMillis = builder.mLocationQueryTimeMillis;
            this.mOnDeviceLookupTimeMillis = builder.mOnDeviceLookupTimeMillis;
            this.mTotalCheckingTimeMillis = builder.mTotalCheckingTimeMillis;
            this.mIsAllowed = builder.mIsAllowed;
            this.mIsEmergency = builder.mIsEmergency;
            this.mResultCode = builder.mResultCode;
            this.mCountryCodes = builder.mCountryCodes;
            this.mConfigDataSource = builder.mConfigDataSource;
            this.mCarrierId = builder.mCarrierId;
            this.mTriggeringEvent = builder.mTriggeringEvent;
            this.mIsNtnOnlyCarrier = builder.mIsNtnOnlyCarrier;
            this.mSupportedConnectionMode = builder.mSupportedConnectionMode;
            this.mSessionConnectionMode = builder.mSessionConnectionMode;
        }

        public @SatelliteConstants.AccessControlType int getAccessControlType() {
            return mAccessControlType;
        }

        public long getLocationQueryTime() {
            return mLocationQueryTimeMillis;
        }

        public long getOnDeviceLookupTime() {
            return mOnDeviceLookupTimeMillis;
        }

        public long getTotalCheckingTime() {
            return mTotalCheckingTimeMillis;
        }

        public boolean getIsAllowed() {
            return mIsAllowed;
        }

        public boolean getIsEmergency() {
            return mIsEmergency;
        }

        public @SatelliteManager.SatelliteResult int getResultCode() {
            return mResultCode;
        }

        public String[] getCountryCodes() {
            return mCountryCodes;
        }

        public @SatelliteConstants.ConfigDataSource int getConfigDataSource() {
            return mConfigDataSource;
        }

        public int getCarrierId() {
            return mCarrierId;
        }

        @SatelliteConstants.TriggeringEvent public int getTriggeringEvent() {
            return mTriggeringEvent;
        }

        public boolean isNtnOnlyCarrier() {
            return mIsNtnOnlyCarrier;
        }

        public int getSupportedConnectionMode() {
            return mSupportedConnectionMode;
        }

        public int getSessionConnectionMode() {
            return mSessionConnectionMode;
        }

        /**
         * A builder class to create {@link SatelliteAccessControllerParams} data structure class
         */
        public static class Builder {
            private @SatelliteConstants.AccessControlType int mAccessControlType;
            private long mLocationQueryTimeMillis;
            private long mOnDeviceLookupTimeMillis;
            private long mTotalCheckingTimeMillis;
            private boolean mIsAllowed;
            private boolean mIsEmergency;
            private @SatelliteManager.SatelliteResult int mResultCode;
            private String[] mCountryCodes;
            private @SatelliteConstants.ConfigDataSource int mConfigDataSource;
            private int mCarrierId = UNKNOWN_CARRIER_ID;
            private @SatelliteConstants.TriggeringEvent int mTriggeringEvent =
                    TRIGGERING_EVENT_UNKNOWN;
            private boolean mIsNtnOnlyCarrier = false;
            private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode =
                    SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
            private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode =
                    SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;

            /**
             * Sets AccessControlType value of {@link SatelliteAccessController}
             * atom then returns Builder class
             */
            public Builder setAccessControlType(
                    @SatelliteConstants.AccessControlType int accessControlType) {
                this.mAccessControlType = accessControlType;
                return this;
            }

            /** Sets the location query time for current satellite enablement. */
            public Builder setLocationQueryTime(long locationQueryTimeMillis) {
                this.mLocationQueryTimeMillis = locationQueryTimeMillis;
                return this;
            }

            /** Sets the on device lookup time for current satellite enablement. */
            public Builder setOnDeviceLookupTime(long onDeviceLookupTimeMillis) {
                this.mOnDeviceLookupTimeMillis = onDeviceLookupTimeMillis;
                return this;
            }

            /** Sets the total checking time for current satellite enablement. */
            public Builder setTotalCheckingTime(long totalCheckingTimeMillis) {
                this.mTotalCheckingTimeMillis = totalCheckingTimeMillis;
                return this;
            }

            /** Sets whether the satellite communication is allowed from current location. */
            public Builder setIsAllowed(boolean isAllowed) {
                this.mIsAllowed = isAllowed;
                return this;
            }

            /** Sets whether the current satellite enablement is for emergency or not. */
            public Builder setIsEmergency(boolean isEmergency) {
                this.mIsEmergency = isEmergency;
                return this;
            }

            /** Sets the result code for checking whether satellite service is allowed from current
             location. */
            public Builder setResult(int result) {
                this.mResultCode = result;
                return this;
            }

            /** Sets the country code for current location while attempting satellite enablement. */
            public Builder setCountryCodes(String[] countryCodes) {
                this.mCountryCodes = Arrays.stream(countryCodes).toArray(String[]::new);
                return this;
            }

            /** Sets the config data source for checking whether satellite service is allowed from
             current location. */
            public Builder setConfigDatasource(int configDatasource) {
                this.mConfigDataSource = configDatasource;
                return this;
            }

            /** Sets the currently active NB-IoT NTN carrier ID. */
            public Builder setCarrierId(int carrierId) {
                this.mCarrierId = carrierId;
                return this;
            }

            /** Sets the triggering evenr for current satellite access controller metric. */
            public Builder setTriggeringEvent(
                    @SatelliteConstants.TriggeringEvent int triggeringEvent) {
                this.mTriggeringEvent = triggeringEvent;
                return this;
            }

            /**
             * Sets isNtnOnlyCarrier value of {@link SatelliteAccessController} atom
             * then returns Builder class
            */
            public Builder setIsNtnOnlyCarrier(boolean isNtnOnlyCarrier) {
                this.mIsNtnOnlyCarrier = isNtnOnlyCarrier;
                return this;
            }

            /**
             * Sets supportedConnectionMode value of {@link SatelliteAccessController} atom
             * then returns Builder class
             */
            public Builder setSupportedConnectionMode(int supportedConnectionMode) {
                this.mSupportedConnectionMode = supportedConnectionMode;
                return this;
            }

            /**
             * Sets sessionConnectionMode value of {@link SatelliteAccessController} atom
             * then returns Builder class
             */
            public Builder setSessionConnectionMode(int sessionConnectionMode) {
                this.mSessionConnectionMode = sessionConnectionMode;
                return this;
            }

            /**
             * Returns AccessControllerParams, which contains whole component of
             * {@link SatelliteAccessController} atom
             */
            public SatelliteAccessControllerParams build() {
                return new SatelliteAccessControllerParams(this);
            }
        }

        @Override
        public String toString() {
            return "AccessControllerParams("
                    + ", AccessControlType=" + mAccessControlType
                    + ", LocationQueryTime=" + mLocationQueryTimeMillis
                    + ", OnDeviceLookupTime=" + mOnDeviceLookupTimeMillis
                    + ", TotalCheckingTime=" + mTotalCheckingTimeMillis
                    + ", IsAllowed=" + mIsAllowed
                    + ", IsEmergency=" + mIsEmergency
                    + ", ResultCode=" + mResultCode
                    + ", CountryCodes=" + Arrays.toString(mCountryCodes)
                    + ", ConfigDataSource=" + mConfigDataSource
                    + ", CarrierId=" + mCarrierId
                    + ", TriggeringEvent=" + mTriggeringEvent
                    + ", IsNtnOnlyCarrier=" + mIsNtnOnlyCarrier
                    + ", supportedConnectionMode=" + mSupportedConnectionMode
                    + ", sessionConnectionMode=" + mSessionConnectionMode
                    + ")";
        }
    }

    /**  Create a new atom or update an existing atom for SatelliteController metrics */
    public synchronized void onSatelliteControllerMetrics(SatelliteControllerParams param) {
        SatelliteController proto = new SatelliteController();
        proto.countOfSatelliteServiceEnablementsSuccess =
                param.getCountOfSatelliteServiceEnablementsSuccess();
        proto.countOfSatelliteServiceEnablementsFail =
                param.getCountOfSatelliteServiceEnablementsFail();
        proto.countOfOutgoingDatagramSuccess = param.getCountOfOutgoingDatagramSuccess();
        proto.countOfOutgoingDatagramFail = param.getCountOfOutgoingDatagramFail();
        proto.countOfIncomingDatagramSuccess = param.getCountOfIncomingDatagramSuccess();
        proto.countOfIncomingDatagramFail = param.getCountOfIncomingDatagramFail();
        proto.countOfDatagramTypeSosSmsSuccess = param.getCountOfDatagramTypeSosSmsSuccess();
        proto.countOfDatagramTypeSosSmsFail = param.getCountOfDatagramTypeSosSmsFail();
        proto.countOfDatagramTypeLocationSharingSuccess =
                param.getCountOfDatagramTypeLocationSharingSuccess();
        proto.countOfDatagramTypeLocationSharingFail =
                param.getCountOfDatagramTypeLocationSharingFail();
        proto.countOfProvisionSuccess = param.getCountOfProvisionSuccess();
        proto.countOfProvisionFail = param.getCountOfProvisionFail();
        proto.countOfDeprovisionSuccess = param.getCountOfDeprovisionSuccess();
        proto.countOfDeprovisionFail = param.getCountOfDeprovisionFail();
        proto.totalServiceUptimeSec = param.getTotalServiceUptimeSec();
        proto.totalBatteryConsumptionPercent = param.getTotalBatteryConsumptionPercent();
        proto.totalBatteryChargedTimeSec = param.getTotalBatteryChargedTimeSec();
        proto.countOfDemoModeSatelliteServiceEnablementsSuccess =
                param.getCountOfDemoModeSatelliteServiceEnablementsSuccess();
        proto.countOfDemoModeSatelliteServiceEnablementsFail =
                param.getCountOfDemoModeSatelliteServiceEnablementsFail();
        proto.countOfDemoModeOutgoingDatagramSuccess =
                param.getCountOfDemoModeOutgoingDatagramSuccess();
        proto.countOfDemoModeOutgoingDatagramFail = param.getCountOfDemoModeOutgoingDatagramFail();
        proto.countOfDemoModeIncomingDatagramSuccess =
                param.getCountOfDemoModeIncomingDatagramSuccess();
        proto.countOfDemoModeIncomingDatagramFail = param.getCountOfDemoModeIncomingDatagramFail();
        proto.countOfDatagramTypeKeepAliveSuccess = param.getCountOfDatagramTypeKeepAliveSuccess();
        proto.countOfDatagramTypeKeepAliveFail = param.getCountOfDatagramTypeKeepAliveFail();
        proto.countOfAllowedSatelliteAccess = param.getCountOfAllowedSatelliteAccess();
        proto.countOfDisallowedSatelliteAccess = param.getCountOfDisallowedSatelliteAccess();
        proto.countOfSatelliteAccessCheckFail = param.getCountOfSatelliteAccessCheckFail();
        proto.isProvisioned = param.isProvisioned();
        proto.carrierId = param.getCarrierId();
        proto.countOfSatelliteAllowedStateChangedEvents =
                param.getCountOfSatelliteAllowedStateChangedEvents();
        proto.countOfSuccessfulLocationQueries = param.getCountOfSuccessfulLocationQueries();
        proto.countOfFailedLocationQueries = param.getCountOfFailedLocationQueries();
        proto.countOfP2PSmsAvailableNotificationShown =
                param.getCountOfP2PSmsAvailableNotificationShown();
        proto.countOfP2PSmsAvailableNotificationRemoved =
                param.getCountOfP2PSmsAvailableNotificationRemoved();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.versionOfSatelliteAccessConfig = param.getVersionSatelliteAccessConfig();
        proto.countOfIncomingDatagramTypeSosSmsSuccess =
                param.getCountOfIncomingDatagramTypeSosSmsSuccess();
        proto.countOfIncomingDatagramTypeSosSmsFail =
                param.getCountOfIncomingDatagramTypeSosSmsFail();
        proto.countOfOutgoingDatagramTypeSmsSuccess =
                param.getCountOfOutgoingDatagramTypeSmsSuccess();
        proto.countOfOutgoingDatagramTypeSmsFail = param.getCountOfOutgoingDatagramTypeSmsFail();
        proto.countOfIncomingDatagramTypeSmsSuccess =
                param.getCountOfIncomingDatagramTypeSmsSuccess();
        proto.countOfIncomingDatagramTypeSmsFail = param.getCountOfIncomingDatagramTypeSmsFail();
        proto.carrierRoamingSatelliteConfigVersion =
                param.getCarrierRoamingSatelliteConfigVersion();
        proto.maxAllowedDataMode = param.getMaxAllowedDataMode();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();

        if (DBG) logd("onSatelliteControllerMetrics" + param);
        mAtomsStorage.addSatelliteControllerStats(proto);
    }

    /**  Create a new atom or update an existing atom for SatelliteSession metrics */
    public synchronized void onSatelliteSessionMetrics(SatelliteSessionParams param) {
        SatelliteSession proto = new SatelliteSession();
        proto.satelliteServiceInitializationResult =
                param.getSatelliteServiceInitializationResult();
        proto.satelliteTechnology = param.getSatelliteTechnology();
        proto.count = 1;
        proto.satelliteServiceTerminationResult = param.getTerminationResult();
        proto.initializationProcessingTimeMillis = param.getInitializationProcessingTime();
        proto.terminationProcessingTimeMillis = param.getTerminationProcessingTime();
        proto.sessionDurationSeconds = param.getSessionDuration();
        proto.countOfOutgoingDatagramSuccess = param.getCountOfOutgoingDatagramSuccess();
        proto.countOfOutgoingDatagramFailed = param.getCountOfOutgoingDatagramFailed();
        proto.countOfIncomingDatagramSuccess = param.getCountOfIncomingDatagramSuccess();
        proto.countOfIncomingDatagramFailed = param.getCountOfIncomingDatagramFailed();
        proto.isDemoMode = param.getIsDemoMode();
        proto.maxNtnSignalStrengthLevel = param.getMaxNtnSignalStrengthLevel();
        proto.carrierId = param.getCarrierId();
        proto.countOfSatelliteNotificationDisplayed =
                param.getCountOfSatelliteNotificationDisplayed();
        proto.countOfAutoExitDueToScreenOff = param.getCountOfAutoExitDueToScreenOff();
        proto.countOfAutoExitDueToTnNetwork = param.getCountOfAutoExitDueToTnNetwork();
        proto.isEmergency = param.getIsEmergency();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.maxInactivityDurationSec = param.getMaxInactivityDurationSec();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.sessionConnectionMode = param.getSessionConnectionMode();
        proto.screenOnTimeSec = param.getScreenOnTimeSec();
        proto.batteryLevelDropPercent = param.getBatteryLevelDropPercent();
        proto.wasChargingDuringSession = param.wasChargingDuringSession();
        proto.batteryDesignCapacityMah = param.getBatteryDesignCapacityMah();
        proto.energyConsumedNwh = param.getEnergyConsumedNwh();
        if (DBG) logd("onSatelliteSessionMetrics" + param);
        mAtomsStorage.addSatelliteSessionStats(proto);
    }

    /**  Create a new atom for SatelliteIncomingDatagram metrics */
    public synchronized void onSatelliteIncomingDatagramMetrics(
            SatelliteIncomingDatagramParams param) {
        SatelliteIncomingDatagram proto = new SatelliteIncomingDatagram();
        proto.resultCode = param.getResultCode();
        proto.datagramSizeBytes = param.getDatagramSizeBytes();
        proto.datagramTransferTimeMillis = param.getDatagramTransferTimeMillis();
        proto.isDemoMode = param.getIsDemoMode();
        proto.carrierId = param.getCarrierId();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.sessionConnectionMode = param.getSessionConnectionMode();
        if (DBG) logd("onSatelliteIncomingDatagramMetrics" + param);
        mAtomsStorage.addSatelliteIncomingDatagramStats(proto);
    }

    /**  Create a new atom for SatelliteOutgoingDatagram metrics */
    public synchronized void onSatelliteOutgoingDatagramMetrics(
            SatelliteOutgoingDatagramParams param) {
        SatelliteOutgoingDatagram proto = new SatelliteOutgoingDatagram();
        proto.datagramType = param.getDatagramType();
        proto.resultCode = param.getResultCode();
        proto.datagramSizeBytes = param.getDatagramSizeBytes();
        proto.datagramTransferTimeMillis = param.getDatagramTransferTimeMillis();
        proto.isDemoMode = param.getIsDemoMode();
        proto.carrierId = param.getCarrierId();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.sessionConnectionMode = param.getSessionConnectionMode();
        if (DBG) logd("onSatelliteOutgoingDatagramMetrics: " + param);
        mAtomsStorage.addSatelliteOutgoingDatagramStats(proto);
    }

    /**  Create a new atom for SatelliteProvision metrics */
    public synchronized void onSatelliteProvisionMetrics(SatelliteProvisionParams param) {
        SatelliteProvision proto = new SatelliteProvision();
        proto.resultCode = param.getResultCode();
        proto.provisioningTimeSec = param.getProvisioningTimeSec();
        proto.isProvisionRequest = param.getIsProvisionRequest();
        proto.isCanceled = param.getIsCanceled();
        proto.carrierId = param.getCarrierId();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        if (DBG) logd("onSatelliteProvisionMetrics: " + param);
        mAtomsStorage.addSatelliteProvisionStats(proto);
    }

    /**  Create a new atom or update an existing atom for SatelliteSosMessageRecommender metrics */
    public synchronized void onSatelliteSosMessageRecommender(
            SatelliteSosMessageRecommenderParams param) {
        SatelliteSosMessageRecommender proto = new SatelliteSosMessageRecommender();
        proto.isDisplaySosMessageSent = param.isDisplaySosMessageSent();
        proto.countOfTimerStarted = param.getCountOfTimerStarted();
        proto.isImsRegistered = param.isImsRegistered();
        proto.cellularServiceState = param.getCellularServiceState();
        proto.isMultiSim = param.isMultiSim();
        proto.recommendingHandoverType = param.getRecommendingHandoverType();
        proto.isSatelliteAllowedInCurrentLocation = param.isSatelliteAllowedInCurrentLocation();
        proto.isWifiConnected = param.isWifiConnected();
        proto.carrierId = param.getCarrierId();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.count = 1;
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.sessionConnectionMode = param.getSessionConnectionMode();
        proto.plmn = param.getPlmn();
        proto.isInCarrierRoamingNtnMode = param.getIsInCarrierRoamingNtnMode();
        proto.carrierRoamingSatelliteEmergencyMessagingProvider =
            param.getCarrierRoamingSatelliteEmergencyMessagingProvider();
        proto.emergencyNumberSourceUsedInHandoverIntent =
            param.getEmergencyNumberSourceUsedInHandoverIntent();
        if (DBG) logd("onSatelliteSosMessageRecommender: " + param);
        mAtomsStorage.addSatelliteSosMessageRecommenderStats(proto);
    }

    /**  Create a new atom for CarrierRoamingSatelliteSession metrics */
    public synchronized  void onCarrierRoamingSatelliteSessionMetrics(
            CarrierRoamingSatelliteSessionParams param) {
        CarrierRoamingSatelliteSession proto = new CarrierRoamingSatelliteSession();
        proto.carrierId = param.getCarrierId();
        proto.isNtnRoamingInHomeCountry = param.getIsNtnRoamingInHomeCountry();
        proto.totalSatelliteModeTimeSec = param.getTotalSatelliteModeTimeSec();
        proto.numberOfSatelliteConnections = param.getNumberOfSatelliteConnections();
        proto.avgDurationOfSatelliteConnectionSec = param.getAvgDurationOfSatelliteConnectionSec();
        proto.satelliteConnectionGapMinSec = param.mSatelliteConnectionGapMinSec;
        proto.satelliteConnectionGapAvgSec = param.mSatelliteConnectionGapAvgSec;
        proto.satelliteConnectionGapMaxSec = param.mSatelliteConnectionGapMaxSec;
        proto.rsrpAvg = param.mRsrpAvg;
        proto.rsrpMedian = param.mRsrpMedian;
        proto.rssnrAvg = param.mRssnrAvg;
        proto.rssnrMedian = param.mRssnrMedian;
        proto.countOfIncomingSms = param.mCountOfIncomingSms;
        proto.countOfOutgoingSms = param.mCountOfOutgoingSms;
        proto.countOfIncomingMms = param.mCountOfIncomingMms;
        proto.countOfOutgoingMms = param.mCountOfOutgoingMms;
        proto.supportedSatelliteServices = param.mSupportedSatelliteServices;
        proto.serviceDataPolicy = param.mServiceDataPolicy;
        proto.satelliteDataConsumedBytes = param.mSatelliteDataConsumedBytes;
        proto.isMultiSim = param.isMultiSim();
        proto.isNbIotNtn = param.isNbIotNtn();
        proto.countOfDataConnections = param.mCountOfDataConnections;
        proto.lastFailCauses = param.mLastFailCauses;
        proto.countOfDataDisconnections = param.mCountOfDataDisconnections;
        proto.countOfDataStalls = param.mCountOfDataStalls;
        proto.averageUplinkBandwidthKbps = param.mAverageUplinkBandwidthKbps;
        proto.averageDownlinkBandwidthKbps = param.mAverageDownlinkBandwidthKbps;
        proto.minUplinkBandwidthKbps = param.mMinUplinkBandwidthKbps;
        proto.maxUplinkBandwidthKbps = param.mMaxUplinkBandwidthKbps;
        proto.minDownlinkBandwidthKbps = param.mMinDownlinkBandwidthKbps;
        proto.maxDownlinkBandwidthKbps = param.mMaxDownlinkBandwidthKbps;
        proto.satelliteSupportedApps = param.mSatelliteSupportedApps;
        proto.satelliteSupportedUids = param.mSatelliteSupportedUids;
        proto.perAppSatelliteDataConsumedBytes = param.mPerAppSatelliteDataConsumedBytes;
        proto.supportedConnectionMode = param.mSupportedConnectionMode;
        proto.sessionConnectionMode = param.getSessionConnectionMode();
        proto.isWifiEnabled = param.isWifiEnabled();
        proto.isWfcEnabled = param.isWfcEnabled();
        proto.isWfcRegistered = param.isWfcRegistered();
        proto.screenOnTimeSec = param.getScreenOnTimeSec();
        proto.batteryLevelDropPercent = param.getBatteryLevelDropPercent();
        proto.wasChargingDuringSession = param.wasChargingDuringSession();
        proto.batteryDesignCapacityMah = param.getBatteryDesignCapacityMah();
        proto.energyConsumedNwh = param.getEnergyConsumedNwh();
        proto.eligibilitySource = param.getEligibilitySource();
        proto.plmn = param.getPlmn();
        proto.isWifiConnected = param.isWifiConnected();
        if (DBG) logd("onCarrierRoamingSatelliteSessionMetrics: " + param);
        mAtomsStorage.addCarrierRoamingSatelliteSessionStats(proto);
    }

    /**  Create a new atom for CarrierRoamingSatelliteSession metrics
     *
     * <p>Note: When building the {@link CarrierRoamingSatelliteControllerStatsParams} to pass to
     * this method, the following base dimensions should always be fetched and set, typically for
     * the current subId:
     * <ul>
     *   <li>{@code CarrierId}
     *   <li>{@code IsDeviceEntitled}
     *   <li>{@code IsMultiSim}
     *   <li>{@code IsNbIotNtn}
     *   <li>{@code SupportedConnectionMode}
     * </ul>
     */
    public synchronized void onCarrierRoamingSatelliteControllerStatsMetrics(
            CarrierRoamingSatelliteControllerStatsParams param) {
        if (param.getCarrierId() == TelephonyManager.UNKNOWN_CARRIER_ID) {
            logd("onCarrierRoamingSatelliteControllerStatsMetrics: carrier id is -1, ignore.");
            return;
        }

        CarrierRoamingSatelliteControllerStats proto = new CarrierRoamingSatelliteControllerStats();
        proto.configDataSource = param.mConfigDataSource;
        proto.countOfEntitlementStatusQueryRequest = param.mCountOfEntitlementStatusQueryRequest;
        proto.countOfSatelliteConfigUpdateRequest = param.mCountOfSatelliteConfigUpdateRequest;
        proto.countOfSatelliteNotificationDisplayed = param.mCountOfSatelliteNotificationDisplayed;
        proto.satelliteSessionGapMinSec = param.getSatelliteSessionGapMinSec();
        proto.satelliteSessionGapAvgSec = param.getSatelliteSessionGapAvgSec();
        proto.satelliteSessionGapMaxSec = param.getSatelliteSessionGapMaxSec();
        proto.carrierId = param.getCarrierId();
        proto.isDeviceEntitled = param.isDeviceEntitled();
        proto.isMultiSim = param.isMultiSim();
        proto.countOfSatelliteSessions = param.getCountOfSatelliteSessions();
        proto.isNbIotNtn = param.isNbIotNtn();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.countOfSessionConnectionModeAutomatic =
                param.getCountOfSessionConnectionModeAutomatic();
        proto.countOfSessionConnectionModeManual = param.getCountOfSessionConnectionModeManual();
        proto.serviceDataPolicy = param.getServiceDataPolicy();
        proto.totalSessionDurationSec = param.getSessionDurationSec();
        proto.satelliteAttachSupported = param.isSatelliteAttachSupported();
        proto.eligibilitySource = param.getEligibilitySource();
        if (DBG) logd("onCarrierRoamingSatelliteControllerStatsMetrics: " + param);
        mAtomsStorage.addCarrierRoamingSatelliteControllerStats(proto);
    }

    /** Reset carrier roaming satellite controller stats after atom is pulled. */
    public synchronized void resetCarrierRoamingSatelliteControllerStats() {
        if (DBG) logd("resetCarrierRoamingSatelliteControllerStats:");
        com.android.internal.telephony.satellite.metrics
                .CarrierRoamingSatelliteControllerStats.getOrCreateInstance()
                .resetSessionGapLists();
    }

    /**  Create a new atom for SatelliteEntitlement metrics */
    public synchronized  void onSatelliteEntitlementMetrics(SatelliteEntitlementParams param) {
        SatelliteEntitlement proto = new SatelliteEntitlement();
        proto.carrierId = param.getCarrierId();
        proto.result = param.getResult();
        proto.entitlementStatus = param.getEntitlementStatus();
        proto.isRetry = param.getIsRetry();
        proto.count = param.getCount();
        proto.isAllowedServiceEntitlement = param.getIsAllowedServiceEntitlement();
        proto.entitlementServiceType = param.getEntitlementServiceType();
        proto.entitlementDataPolicy = param.getEntitlementDataPolicy();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.httpStatusCode = param.getHttpStatusCode();
        proto.triggerEvent = param.getTriggerEvent();
        if (DBG) logd("onSatelliteEntitlementMetrics: " + param);
        mAtomsStorage.addSatelliteEntitlementStats(proto);
    }

    /**  Create a new atom for SatelliteConfigUpdater metrics */
    public synchronized  void onSatelliteConfigUpdaterMetrics(SatelliteConfigUpdaterParams param) {
        SatelliteConfigUpdater proto = new SatelliteConfigUpdater();
        proto.configVersion = param.getConfigVersion();
        proto.oemConfigResult = param.getOemConfigResult();
        proto.carrierConfigResult = param.getCarrierConfigResult();
        proto.count = param.getCount();
        if (DBG) logd("onSatelliteConfigUpdaterMetrics: " + param);
        mAtomsStorage.addSatelliteConfigUpdaterStats(proto);
    }

    /**  Create a new atom or update an existing atom for SatelliteAccessController metrics */
    public synchronized void onSatelliteAccessControllerMetrics(
            SatelliteAccessControllerParams param) {
        SatelliteAccessController proto = new SatelliteAccessController();
        proto.accessControlType = param.getAccessControlType();
        proto.locationQueryTimeMillis = param.getLocationQueryTime();
        proto.onDeviceLookupTimeMillis = param.getOnDeviceLookupTime();
        proto.totalCheckingTimeMillis = param.getTotalCheckingTime();
        proto.isAllowed = param.getIsAllowed();
        proto.isEmergency = param.getIsEmergency();
        proto.resultCode = param.getResultCode();
        proto.countryCodes = param.getCountryCodes();
        proto.configDataSource = param.getConfigDataSource();
        proto.carrierId = param.getCarrierId();
        proto.triggeringEvent = param.getTriggeringEvent();
        proto.isNtnOnlyCarrier = param.isNtnOnlyCarrier();
        proto.supportedConnectionMode = param.getSupportedConnectionMode();
        proto.sessionConnectionMode = param.getSessionConnectionMode();
        if (DBG) logd("onSatelliteAccessControllerMetrics: " + param);
        mAtomsStorage.addSatelliteAccessControllerStats(proto);
    }

    private static void logd(String msg) {
        Rlog.d(TAG, msg);
    }
}
