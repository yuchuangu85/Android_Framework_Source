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
 * limitations under the License
 */

package com.android.libraries.tv.tvsystem.display;

import java.util.Arrays;
import java.util.Objects;

/**
 * Product-specific information about the display or the directly connected device on the
 * display chain. For example, if the display is transitively connected, this field may contain
 * product information about the intermediate device.
 */
public final class DeviceProductInfo {
    private final String mName;
    private final String mManufacturerPnpId;
    private final String mProductId;
    private final Integer mModelYear;
    private final ManufactureDate mManufactureDate;
    private final int[] mRelativeAddress;

    public DeviceProductInfo(
            String name,
            String manufacturerPnpId,
            String productId,
            Integer modelYear,
            ManufactureDate manufactureDate,
            int[] relativeAddress) {
        this.mName = name;
        this.mManufacturerPnpId = manufacturerPnpId;
        this.mProductId = productId;
        this.mModelYear = modelYear;
        this.mManufactureDate = manufactureDate;
        this.mRelativeAddress = relativeAddress;
    }

    /**
     * @return Display name.
     */
    public String getName() {
        return mName;
    }

    /**
     * @return Manufacturer Plug and Play ID.
     */
    public String getManufacturerPnpId() {
        return mManufacturerPnpId;
    }

    /**
     * @return Manufacturer product ID.
     */
    public String getProductId() {
        return mProductId;
    }

    /**
     * @return Model year of the device. Typically exactly one of model year or
     *      manufacture date will be present.
     */
    public Integer getModelYear() {
        return mModelYear;
    }

    /**
     * @return Manufacture date. Typically exactly one of model year or manufacture
     * date will be present.
     */
    public ManufactureDate getManufactureDate() {
        return mManufactureDate;
    }

    /**
     * @return Relative address in the display network. For example, for HDMI connected devices this
     * can be its physical address. Each component of the address is in the range [0, 255].
     */
    public int[] getRelativeAddress() {
        return mRelativeAddress;
    }

    @Override
    public String toString() {
        return "DeviceProductInfo{"
                + "name="
                + mName
                + ", manufacturerPnpId="
                + mManufacturerPnpId
                + ", productId="
                + mProductId
                + ", modelYear="
                + mModelYear
                + ", manufactureDate="
                + mManufactureDate
                + ", relativeAddress="
                + Arrays.toString(mRelativeAddress)
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceProductInfo that = (DeviceProductInfo) o;
        return Objects.equals(mName, that.mName)
                && Objects.equals(mManufacturerPnpId, that.mManufacturerPnpId)
                && Objects.equals(mProductId, that.mProductId)
                && Objects.equals(mModelYear, that.mModelYear)
                && Objects.equals(mManufactureDate, that.mManufactureDate)
                && Arrays.equals(mRelativeAddress, that.mRelativeAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mManufacturerPnpId, mProductId, mModelYear, mManufactureDate,
                mRelativeAddress);
    }

    /**
     * Stores information about the date of manufacture.
     */
    public static class ManufactureDate {
        private final Integer mWeek;
        private final Integer mYear;

        public ManufactureDate(Integer week, Integer year) {
            mWeek = week;
            mYear = year;
        }

        public Integer getYear() {
            return mYear;
        }

        public Integer getWeek() {
            return mWeek;
        }

        @Override
        public String toString() {
            return "ManufactureDate{week=" + mWeek + ", year=" + mYear + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ManufactureDate that = (ManufactureDate) o;
            return Objects.equals(mWeek, that.mWeek) && Objects.equals(mYear, that.mYear);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWeek, mYear);
        }
    }
}