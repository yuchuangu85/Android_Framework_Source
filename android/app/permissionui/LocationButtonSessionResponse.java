/*
 * Copyright (C) 2025 The Android Open Source Project
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
package android.app.permissionui;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControlViewHost;

/**
 * A class that contains the information about the  new Location Button session.
 *
 * @hide
 */
public final class LocationButtonSessionResponse implements Parcelable {
    private final ILocationButtonSession mSession;
    private final SurfaceControlViewHost.SurfacePackage mSurfacePackage;

    public LocationButtonSessionResponse(@NonNull ILocationButtonSession session,
            @NonNull SurfaceControlViewHost.SurfacePackage surfacePackage) {
        mSession = session;
        mSurfacePackage = surfacePackage;
    }

    private LocationButtonSessionResponse(@NonNull Parcel in) {
        mSession = ILocationButtonSession.Stub.asInterface(in.readStrongBinder());
        mSurfacePackage = in.readTypedObject(SurfaceControlViewHost.SurfacePackage.CREATOR);
    }

    @NonNull
    public ILocationButtonSession getSession() {
        return mSession;
    }

    @NonNull
    public SurfaceControlViewHost.SurfacePackage getSurfacePackage() {
        return mSurfacePackage;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mSession.asBinder());
        dest.writeTypedObject(mSurfacePackage, flags);
    }

    @NonNull
    public static final Creator<LocationButtonSessionResponse> CREATOR =
            new Creator<LocationButtonSessionResponse>() {
                @Override
                public LocationButtonSessionResponse createFromParcel(Parcel in) {
                    return new LocationButtonSessionResponse(in);
                }

                @Override
                public LocationButtonSessionResponse[] newArray(int size) {
                    return new LocationButtonSessionResponse[size];
                }
            };
}
