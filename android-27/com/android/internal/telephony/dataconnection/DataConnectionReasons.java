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

package com.android.internal.telephony.dataconnection;

import java.util.HashSet;

/**
 * The class to describe the reasons of allowing or disallowing to establish a data connection.
 */
public class DataConnectionReasons {
    private HashSet<DataDisallowedReasonType> mDataDisallowedReasonSet = new HashSet<>();
    private DataAllowedReasonType mDataAllowedReason = DataAllowedReasonType.NONE;

    public DataConnectionReasons() {}

    void add(DataDisallowedReasonType reason) {
        // Adding a disallowed reason will clean up the allowed reason because they are
        // mutual exclusive.
        mDataAllowedReason = DataAllowedReasonType.NONE;
        mDataDisallowedReasonSet.add(reason);
    }

    void add(DataAllowedReasonType reason) {
        // Adding an allowed reason will clean up the disallowed reasons because they are
        // mutual exclusive.
        mDataDisallowedReasonSet.clear();

        // Only higher priority allowed reason can overwrite the old one. See
        // DataAllowedReasonType for the oder.
        if (reason.ordinal() > mDataAllowedReason.ordinal()) {
            mDataAllowedReason = reason;
        }
    }

    @Override
    public String toString() {
        StringBuilder reasonStr = new StringBuilder();
        if (mDataDisallowedReasonSet.size() > 0) {
            reasonStr.append("Data disallowed, reasons:");
            for (DataDisallowedReasonType reason : mDataDisallowedReasonSet) {
                reasonStr.append(" ").append(reason);
            }
        } else {
            reasonStr.append("Data allowed, reason:");
            reasonStr.append(" ").append(mDataAllowedReason);
        }
        return reasonStr.toString();
    }

    void copyFrom(DataConnectionReasons reasons) {
        this.mDataDisallowedReasonSet = reasons.mDataDisallowedReasonSet;
        this.mDataAllowedReason = reasons.mDataAllowedReason;
    }

    boolean allowed() {
        return mDataDisallowedReasonSet.size() == 0;
    }

    boolean contains(DataDisallowedReasonType reason) {
        return mDataDisallowedReasonSet.contains(reason);
    }

    /**
     * Check if only one disallowed reason prevent data connection.
     *
     * @param reason The given reason to check
     * @return True if the given reason is the only one that prevents data connection
     */
    public boolean containsOnly(DataDisallowedReasonType reason) {
        return mDataDisallowedReasonSet.size() == 1 && contains(reason);
    }

    boolean contains(DataAllowedReasonType reason) {
        return reason == mDataAllowedReason;
    }

    boolean containsHardDisallowedReasons() {
        for (DataDisallowedReasonType reason : mDataDisallowedReasonSet) {
            if (reason.isHardReason()) {
                return true;
            }
        }
        return false;
    }

    // Disallowed reasons. There could be multiple reasons if data connection is not allowed.
    public enum DataDisallowedReasonType {
        // Soft failure reasons. Normally the reasons from users or policy settings.
        DATA_DISABLED(false),                   // Data is disabled by the user or policy.
        ROAMING_DISABLED(false),                // Data roaming is disabled by the user.

        // Belows are all hard failure reasons.
        NOT_ATTACHED(true),
        RECORD_NOT_LOADED(true),
        INVALID_PHONE_STATE(true),
        CONCURRENT_VOICE_DATA_NOT_ALLOWED(true),
        PS_RESTRICTED(true),
        UNDESIRED_POWER_STATE(true),
        INTERNAL_DATA_DISABLED(true),
        DEFAULT_DATA_UNSELECTED(true),
        RADIO_DISABLED_BY_CARRIER(true),
        APN_NOT_CONNECTABLE(true),
        ON_IWLAN(true),
        IN_ECBM(true);

        private boolean mIsHardReason;

        boolean isHardReason() {
            return mIsHardReason;
        }

        DataDisallowedReasonType(boolean isHardReason) {
            mIsHardReason = isHardReason;
        }
    }

    // Data allowed reasons. There will be only one reason if data is allowed.
    enum DataAllowedReasonType {
        // Note that unlike disallowed reasons, we only have one allowed reason every time
        // when we check data is allowed or not. The order of these allowed reasons is very
        // important. The lower ones take precedence over the upper ones.
        NONE,
        NORMAL,
        UNMETERED_APN,
        RESTRICTED_REQUEST,
        EMERGENCY_APN,
    }
}
