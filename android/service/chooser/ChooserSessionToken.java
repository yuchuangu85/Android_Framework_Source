/*
 * Copyright 2025 The Android Open Source Project
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

package android.service.chooser;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An opaque token that serves as a Chooser session identifier.
 * <p>A token can be saved in an Activity's saved state although it can not survive the app process
 * termination (i.e. it won't be read back from the saved state if the system kills and re-creates
 * the app process as the associated interactive session is also gets terminated in this case).</p>
 *
 * @see ChooserSession#getToken()
 * @see ChooserManager
 */
@FlaggedApi(Flags.FLAG_INTERACTIVE_CHOOSER)
public final class ChooserSessionToken implements Parcelable {
    private final IBinder mBinder;

    /*package*/ ChooserSessionToken(IBinder binder) {
        if (!ChooserSession.isSessionBinder(binder)) {
            throw new IllegalArgumentException("Unexpected binder type");
        }
        mBinder = binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mBinder);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof ChooserSessionToken token) && mBinder.equals(token.mBinder);
    }

    @Override
    public int hashCode() {
        return mBinder.hashCode();
    }

    @NonNull
    public static final Parcelable.Creator<ChooserSessionToken> CREATOR = new Creator<>() {
        @Override
        public ChooserSessionToken createFromParcel(Parcel source) {
            IBinder binder = source.readStrongBinder();
            return ChooserSession.isSessionBinder(binder) ? new ChooserSessionToken(binder) : null;
        }

        @Override
        public ChooserSessionToken[] newArray(int size) {
            return new ChooserSessionToken[size];
        }
    };
}
