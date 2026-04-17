/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/** Record of data traffic (in bytes) by an application identified by its UID. */
@Hide
@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
public final class UidTraffic implements Cloneable, Parcelable {
    private final int mAppUid;
    private long mRxBytes;
    private long mTxBytes;

    @Hide
    public UidTraffic(int appUid, long rx, long tx) {
        mAppUid = appUid;
        mRxBytes = rx;
        mTxBytes = tx;
    }

    @Hide
    private UidTraffic(Parcel in) {
        mAppUid = in.readInt();
        mRxBytes = in.readLong();
        mTxBytes = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAppUid);
        dest.writeLong(mRxBytes);
        dest.writeLong(mTxBytes);
    }

    @Hide
    @RequiresNoPermission
    public void setRxBytes(long bytes) {
        mRxBytes = bytes;
    }

    @Hide
    @RequiresNoPermission
    public void setTxBytes(long bytes) {
        mTxBytes = bytes;
    }

    @Hide
    @RequiresNoPermission
    public void addRxBytes(long bytes) {
        mRxBytes += bytes;
    }

    @Hide
    @RequiresNoPermission
    public void addTxBytes(long bytes) {
        mTxBytes += bytes;
    }

    /**
     * @return corresponding app Uid
     */
    @RequiresNoPermission
    public int getUid() {
        return mAppUid;
    }

    /**
     * @return rx bytes count
     */
    @RequiresNoPermission
    public long getRxBytes() {
        return mRxBytes;
    }

    /**
     * @return tx bytes count
     */
    @RequiresNoPermission
    public long getTxBytes() {
        return mTxBytes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public UidTraffic clone() {
        return new UidTraffic(mAppUid, mRxBytes, mTxBytes);
    }

    @Override
    public String toString() {
        return ("UidTraffic [mAppUid=" + mAppUid)
                + (", mRxBytes=" + mRxBytes)
                + (", mTxBytes=" + mTxBytes + "]");
    }

    public static final @NonNull Creator<UidTraffic> CREATOR =
            new Creator<UidTraffic>() {
                @Override
                public UidTraffic createFromParcel(Parcel source) {
                    return new UidTraffic(source);
                }

                @Override
                public UidTraffic[] newArray(int size) {
                    return new UidTraffic[size];
                }
            };
}
