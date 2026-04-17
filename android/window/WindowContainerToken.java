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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Interface for a window container to communicate with the window manager. This also acts as a
 * token.
 * @hide
 */
@TestApi
public final class WindowContainerToken implements Parcelable {

    private final IWindowContainerToken mRealToken;

    /** @hide */
    public WindowContainerToken(IWindowContainerToken realToken) {
        mRealToken = realToken;
    }

    /**
     * Creates a token to represent a container-like entity that isn't backed by an
     * actual WM window (eg. a SCVH that should be animated as part of a transition).
     * @hide
     */
    public static WindowContainerToken createProxy(@NonNull String name) {
        final Binder tmp = new Binder(name);
        return new WindowContainerToken(new IWindowContainerToken() {
            @Override
            public IBinder asBinder() {
                return tmp;
            }
        });
    }

    private WindowContainerToken(Parcel in) {
        mRealToken = IWindowContainerToken.Stub.asInterface(in.readStrongBinder());
    }

    /** @hide */
    public IBinder asBinder() {
        return mRealToken.asBinder();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mRealToken.asBinder());
    }

    @NonNull
    public static final Creator<WindowContainerToken> CREATOR =
            new Creator<WindowContainerToken>() {
                @Override
                public WindowContainerToken createFromParcel(Parcel in) {
                    return new WindowContainerToken(in);
                }

                @Override
                public WindowContainerToken[] newArray(int size) {
                    return new WindowContainerToken[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return mRealToken.asBinder().hashCode();
    }

    @Override
    public String toString() {
        return "WCT{" + mRealToken.asBinder() + "}";
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof WindowContainerToken)) {
            return false;
        }
        return mRealToken.asBinder() == ((WindowContainerToken) obj).asBinder();
    }
}
