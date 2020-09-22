/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.internal.car;

/**
 * Provides constants that are defined somewhere else and must be cloned here
 */
final class ExternalConstants {

    private ExternalConstants() {
        throw new UnsupportedOperationException("contains only static constants");
    }

    // TODO(b/149797595): remove once ICar.aidl is split in 2
    static final class ICarConstants {
        static final String CAR_SERVICE_INTERFACE = "android.car.ICar";

        // These numbers should match with binder call order of
        // packages/services/Car/car-lib/src/android/car/ICar.aidl
        static final int ICAR_CALL_SET_CAR_SERVICE_HELPER = 0;
        static final int ICAR_CALL_ON_USER_LIFECYCLE = 1;
        static final int ICAR_CALL_FIRST_USER_UNLOCKED = 2;
        static final int ICAR_CALL_GET_INITIAL_USER_INFO = 3;
        static final int ICAR_CALL_SET_INITIAL_USER = 4;

        private ICarConstants() {
            throw new UnsupportedOperationException("contains only static constants");
        }
    }

   /**
     * Constants used by {@link android.user.user.CarUserManager} - they cannot be defined on
     * {@link android.car.userlib.CommonConstants} to avoid an extra dependency in the
     * {@code android.car} project
     */
    static final class CarUserManagerConstants {

        static final int USER_LIFECYCLE_EVENT_TYPE_STARTING = 1;
        static final int USER_LIFECYCLE_EVENT_TYPE_SWITCHING = 2;
        static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKING = 3;
        static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKED = 4;
        static final int USER_LIFECYCLE_EVENT_TYPE_STOPPING = 5;
        static final int USER_LIFECYCLE_EVENT_TYPE_STOPPED = 6;

        private CarUserManagerConstants() {
            throw new UnsupportedOperationException("contains only static constants");
        }
    }
}
