/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.wificond;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Objects;

/**
 * SingleScanSettings for wificond
 *
 * @hide
 */
public class SingleScanSettings implements Parcelable {
    private static final String TAG = "SingleScanSettings";

    public ArrayList<ChannelSettings> channelSettings;
    public ArrayList<HiddenNetwork> hiddenNetworks;

    /** public constructor */
    public SingleScanSettings() { }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof SingleScanSettings)) {
            return false;
        }
        SingleScanSettings settings = (SingleScanSettings) rhs;
        if (settings == null) {
            return false;
        }
        return channelSettings.equals(settings.channelSettings)
                && hiddenNetworks.equals(settings.hiddenNetworks);
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(channelSettings, hiddenNetworks);
    }


    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flags| is ignored.
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(channelSettings);
        out.writeTypedList(hiddenNetworks);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<SingleScanSettings> CREATOR =
            new Parcelable.Creator<SingleScanSettings>() {
        /**
         * Caller is responsible for providing a valid parcel.
         */
        @Override
        public SingleScanSettings createFromParcel(Parcel in) {
            SingleScanSettings result = new SingleScanSettings();
            result.channelSettings = new ArrayList<ChannelSettings>();
            in.readTypedList(result.channelSettings, ChannelSettings.CREATOR);
            result.hiddenNetworks = new ArrayList<HiddenNetwork>();
            in.readTypedList(result.hiddenNetworks, HiddenNetwork.CREATOR);
            if (in.dataAvail() != 0) {
                Log.e(TAG, "Found trailing data after parcel parsing.");
            }

            return result;
        }

        @Override
        public SingleScanSettings[] newArray(int size) {
            return new SingleScanSettings[size];
        }
    };
}
