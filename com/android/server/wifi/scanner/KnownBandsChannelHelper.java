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

package com.android.server.wifi.scanner;

import static android.net.wifi.WifiScanner.WIFI_BAND_24_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_5_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY;
import static android.net.wifi.WifiScanner.WIFI_BAND_6_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_ALL;
import static android.net.wifi.WifiScanner.WIFI_BAND_COUNT;
import static android.net.wifi.WifiScanner.WIFI_BAND_INDEX_24_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_INDEX_5_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_INDEX_5_GHZ_DFS_ONLY;
import static android.net.wifi.WifiScanner.WIFI_BAND_INDEX_6_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_UNSPECIFIED;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations.WifiBandBasic;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.WifiBandIndex;
import android.util.ArraySet;

import com.android.server.wifi.WifiNative;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ChannelHelper that offers channel manipulation utilities when the channels in a band are known.
 * This allows more fine operations on channels than if band channels are not known.
 */
public class KnownBandsChannelHelper extends ChannelHelper {
    // 5G low includes U-NII-1 and Japan 4.9G band
    public static final int BAND_5_GHZ_LOW_END_FREQ = 5240;
    // 5G middle includes U-NII-2A and U-NII-2C
    public static final int BAND_5_GHZ_MID_END_FREQ = 5710;
    // 5G high includes U-NII-3
    public static final int BAND_5_GHZ_HIGH_END_FREQ = ScanResult.BAND_5_GHZ_END_FREQ_MHZ;
    // 6G low includes UNII-5
    public static final int BAND_6_GHZ_LOW_END_FREQ = 6425;
    // 6G middle includes UNII-6 and UNII-7
    public static final int BAND_6_GHZ_MID_END_FREQ = 6875;
    // 6G high includes UNII-8
    public static final int BAND_6_GHZ_HIGH_END_FREQ = ScanResult.BAND_6_GHZ_END_FREQ_MHZ;

    private WifiScanner.ChannelSpec[][] mBandsToChannels;

    protected void setBandChannels(int[] channels2G, int[] channels5G, int[] channelsDfs,
            int[] channels6G) {
        mBandsToChannels = new WifiScanner.ChannelSpec[WIFI_BAND_COUNT][];

        if (channels2G.length != 0) {
            mBandsToChannels[WIFI_BAND_INDEX_24_GHZ] =
                    new WifiScanner.ChannelSpec[channels2G.length];
            copyChannels(mBandsToChannels[WIFI_BAND_INDEX_24_GHZ], channels2G);
        } else {
            mBandsToChannels[WIFI_BAND_INDEX_24_GHZ] = NO_CHANNELS;
        }

        if (channels5G.length != 0) {
            mBandsToChannels[WIFI_BAND_INDEX_5_GHZ] =
                    new WifiScanner.ChannelSpec[channels5G.length];
            copyChannels(mBandsToChannels[WIFI_BAND_INDEX_5_GHZ], channels5G);
        } else {
            mBandsToChannels[WIFI_BAND_INDEX_5_GHZ] = NO_CHANNELS;
        }

        if (channelsDfs.length != 0) {
            mBandsToChannels[WIFI_BAND_INDEX_5_GHZ_DFS_ONLY] =
                    new WifiScanner.ChannelSpec[channelsDfs.length];
            copyChannels(mBandsToChannels[WIFI_BAND_INDEX_5_GHZ_DFS_ONLY], channelsDfs);
        } else {
            mBandsToChannels[WIFI_BAND_INDEX_5_GHZ_DFS_ONLY] = NO_CHANNELS;
        }

        if (channels6G.length != 0) {
            mBandsToChannels[WIFI_BAND_INDEX_6_GHZ] =
                    new WifiScanner.ChannelSpec[channels6G.length];
            copyChannels(mBandsToChannels[WIFI_BAND_INDEX_6_GHZ], channels6G);
        } else {
            mBandsToChannels[WIFI_BAND_INDEX_6_GHZ] = NO_CHANNELS;
        }
    }

    private static void copyChannels(
            WifiScanner.ChannelSpec[] channelSpec, int[] channels) {
        for (int i = 0; i < channels.length; i++) {
            channelSpec[i] = new WifiScanner.ChannelSpec(channels[i]);
        }
    }

    @Override
    public WifiScanner.ChannelSpec[][] getAvailableScanChannels(int band) {
        if (band <= WIFI_BAND_UNSPECIFIED || band > WIFI_BAND_ALL) {
            // Invalid value for band.
            return null;
        }

        WifiScanner.ChannelSpec[][] channels = new WifiScanner.ChannelSpec[WIFI_BAND_COUNT][];
        for (@WifiBandIndex int index = 0; index < WIFI_BAND_COUNT; index++) {
            if ((band & (1 << index)) != 0) {
                channels[index] = mBandsToChannels[index];
            } else {
                channels[index] = NO_CHANNELS;
            }
        }
        return channels;
    }

    @Override
    public boolean satisfies(ChannelHelper otherChannelHelper) {
        if (!(otherChannelHelper instanceof KnownBandsChannelHelper)) return false;
        KnownBandsChannelHelper otherKnownBandsChannelHelper =
                (KnownBandsChannelHelper) otherChannelHelper;
        // Compare all the channels in every band
        for (@WifiBandIndex int i = 0; i < WIFI_BAND_COUNT; i++) {
            Set<Integer> thisFrequencies = Arrays.stream(mBandsToChannels[i])
                    .map(spec -> spec.frequency)
                    .collect(Collectors.toSet());
            Set<Integer> otherFrequencies = Arrays.stream(
                    otherKnownBandsChannelHelper.mBandsToChannels[i])
                    .map(spec -> spec.frequency)
                    .collect(Collectors.toSet());
            if (!thisFrequencies.containsAll(otherFrequencies)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int estimateScanDuration(WifiScanner.ScanSettings settings) {
        if (settings.band == WIFI_BAND_UNSPECIFIED) {
            return settings.channels.length * SCAN_PERIOD_PER_CHANNEL_MS;
        } else {
            WifiScanner.ChannelSpec[][] channels = getAvailableScanChannels(settings.band);
            int len = 0;
            for (int i = 0; i < channels.length; ++i) {
                len += channels[i].length;
            }
            return  len * SCAN_PERIOD_PER_CHANNEL_MS;
        }
    }

    private boolean isDfsChannel(int frequency) {
        for (WifiScanner.ChannelSpec dfsChannel :
                mBandsToChannels[WIFI_BAND_INDEX_5_GHZ_DFS_ONLY]) {
            if (frequency == dfsChannel.frequency) {
                return true;
            }
        }
        return false;
    }

    // TODO this should be rewritten to be based on the input data instead of hardcoded ranges
    private int getBandFromChannel(int frequency) {
        if (ScanResult.is24GHz(frequency)) {
            return WIFI_BAND_24_GHZ;
        } else if (ScanResult.is5GHz(frequency)) {
            if (isDfsChannel(frequency)) {
                return WIFI_BAND_5_GHZ_DFS_ONLY;
            } else {
                return WIFI_BAND_5_GHZ;
            }
        } else if (ScanResult.is6GHz(frequency)) {
            return WIFI_BAND_6_GHZ;
        } else {
            return WIFI_BAND_UNSPECIFIED;
        }
    }

    private @WifiBandIndex int getIndexForBand(@WifiBandBasic int band) {
        switch (band) {
            case WIFI_BAND_24_GHZ:
                return WIFI_BAND_INDEX_24_GHZ;
            case WIFI_BAND_5_GHZ:
                return WIFI_BAND_INDEX_5_GHZ;
            case WIFI_BAND_5_GHZ_DFS_ONLY:
                return WIFI_BAND_INDEX_5_GHZ_DFS_ONLY;
            case WIFI_BAND_6_GHZ:
                return WIFI_BAND_INDEX_6_GHZ;
            default:
                return -1;
        }
    }

    @Override
    public boolean settingsContainChannel(WifiScanner.ScanSettings settings, int channel) {
        WifiScanner.ChannelSpec[] settingsChannels;
        @WifiBandBasic int band;
        // If band is not specified in settings, limit check on channels in settings
        if (settings.band == WIFI_BAND_UNSPECIFIED) {
            settingsChannels = settings.channels;
        } else {
            // Get the proper band for this channel
            band = getBandFromChannel(channel);
            // Check if this band is included in band specified in settings
            if ((settings.band & band) == WIFI_BAND_UNSPECIFIED) {
                return false;
            }

            settingsChannels = mBandsToChannels[getIndexForBand(band)];
        }
        // Now search for the channel
        for (int i = 0; i < settingsChannels.length; ++i) {
            if (settingsChannels[i].frequency == channel) {
                return true;
            }
        }
        return false;
    }

    /**
     * ChannelCollection that merges channels so that the optimal schedule will be generated.
     * When the max channels value is satisfied this implementation will always create a channel
     * list that includes no more than the added channels.
     */
    public class KnownBandsChannelCollection extends ChannelCollection {
        /**
         * Stores all channels, including those that belong to added bands.
         */
        private final ArraySet<Integer> mChannels = new ArraySet<Integer>();
        /**
         * Contains only the bands that were explicitly added as bands.
         */
        private int mExactBands = 0;
        /**
         * Contains all bands, including those that were added because an added channel was in that
         * band.
         */
        private int mAllBands = 0;

        @Override
        public void addChannel(int frequency) {
            mChannels.add(frequency);
            mAllBands |= getBandFromChannel(frequency);
        }

        @Override
        public void addBand(int band) {
            mExactBands |= band;
            mAllBands |= band;
            WifiScanner.ChannelSpec[][] bandChannels = getAvailableScanChannels(band);
            for (int i = 0; i < bandChannels.length; ++i) {
                for (int j = 0; j < bandChannels[i].length; ++j) {
                    mChannels.add(bandChannels[i][j].frequency);
                }
            }
        }

        @Override
        public boolean containsChannel(int channel) {
            return mChannels.contains(channel);
        }

        @Override
        public boolean containsBand(int band) {
            WifiScanner.ChannelSpec[][] bandChannels = getAvailableScanChannels(band);
            for (int i = 0; i < bandChannels.length; ++i) {
                for (int j = 0; j < bandChannels[i].length; ++j) {
                    if (!mChannels.contains(bandChannels[i][j].frequency)) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public boolean partiallyContainsBand(int band) {
            WifiScanner.ChannelSpec[][] bandChannels = getAvailableScanChannels(band);
            for (int i = 0; i < bandChannels.length; ++i) {
                for (int j = 0; j < bandChannels[i].length; ++j) {
                    if (mChannels.contains(bandChannels[i][j].frequency)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean isEmpty() {
            return mChannels.isEmpty();
        }

        @Override
        public boolean isAllChannels() {
            return containsBand(WIFI_BAND_ALL);
        }

        @Override
        public void clear() {
            mAllBands = 0;
            mExactBands = 0;
            mChannels.clear();
        }

        @Override
        public Set<Integer> getMissingChannelsFromBand(int band) {
            ArraySet<Integer> missingChannels = new ArraySet<>();
            WifiScanner.ChannelSpec[][] bandChannels = getAvailableScanChannels(band);
            for (int i = 0; i < bandChannels.length; ++i) {
                for (int j = 0; j < bandChannels[i].length; ++j) {
                    if (!mChannels.contains(bandChannels[i][j].frequency)) {
                        missingChannels.add(bandChannels[i][j].frequency);
                    }
                }
            }
            return missingChannels;
        }

        @Override
        public Set<Integer> getContainingChannelsFromBand(int band) {
            ArraySet<Integer> containingChannels = new ArraySet<>();
            WifiScanner.ChannelSpec[][] bandChannels = getAvailableScanChannels(band);
            for (int i = 0; i < bandChannels.length; ++i) {
                for (int j = 0; j < bandChannels[i].length; ++j) {
                    if (mChannels.contains(bandChannels[i][j].frequency)) {
                        containingChannels.add(bandChannels[i][j].frequency);
                    }
                }
            }
            return containingChannels;
        }

        @Override
        public Set<Integer> getChannelSet() {
            if (!isEmpty() && mAllBands != mExactBands) {
                return mChannels;
            } else {
                return new ArraySet<>();
            }
        }

        @Override
        public void fillBucketSettings(WifiNative.BucketSettings bucketSettings, int maxChannels) {
            if ((mChannels.size() > maxChannels || mAllBands == mExactBands)
                    && mAllBands != 0) {
                bucketSettings.band = mAllBands;
                bucketSettings.num_channels = 0;
                bucketSettings.channels = null;
            } else {
                bucketSettings.band = WIFI_BAND_UNSPECIFIED;
                bucketSettings.num_channels = mChannels.size();
                bucketSettings.channels = new WifiNative.ChannelSettings[mChannels.size()];
                for (int i = 0; i < mChannels.size(); ++i) {
                    WifiNative.ChannelSettings channelSettings = new WifiNative.ChannelSettings();
                    channelSettings.frequency = mChannels.valueAt(i);
                    bucketSettings.channels[i] = channelSettings;
                }
            }
        }

        @Override
        public Set<Integer> getScanFreqs() {
            if (mExactBands == WIFI_BAND_ALL) {
                return null;
            } else {
                return new ArraySet<Integer>(mChannels);
            }
        }

        public Set<Integer> getAllChannels() {
            return new ArraySet<Integer>(mChannels);
        }
    }

    @Override

    public KnownBandsChannelCollection createChannelCollection() {
        return new KnownBandsChannelCollection();
    }
}
