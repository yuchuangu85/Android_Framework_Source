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

package android.content.pm;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Holds information about how to launch an app, including its {@link ComponentName}
 * and the associated {@link Intent}.
 *
 * <p>This is a parcelable data class and should be used when passing app launch details
 * across process boundaries or between system components.</p>
 *
 * @hide
 */
public class AppLaunchInfo implements Parcelable {
    private final ComponentName mComponentName;
    private final Intent mLaunchIntent;

    /**
     * Constructs a new {@link AppLaunchInfo} instance.
     *
     * @param componentName the component to be launched
     * @param launchIntent the intent used to launch the component
     */
    public AppLaunchInfo(ComponentName componentName, Intent launchIntent) {
        this.mComponentName = componentName;
        this.mLaunchIntent = launchIntent;
    }

    protected AppLaunchInfo(Parcel in) {
        mComponentName = in.readParcelable(ComponentName.class.getClassLoader(),
                ComponentName.class);
        mLaunchIntent = in.readParcelable(Intent.class.getClassLoader(), Intent.class);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mComponentName, flags);
        dest.writeParcelable(mLaunchIntent, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Parcelable creator used to instantiate {@link AppLaunchInfo} objects from a {@link Parcel}.
     */
    public static final Creator<AppLaunchInfo> CREATOR = new Creator<AppLaunchInfo>() {
        @Override
        public AppLaunchInfo createFromParcel(Parcel in) {
            return new AppLaunchInfo(in);
        }

        @Override
        public AppLaunchInfo[] newArray(int size) {
            return new AppLaunchInfo[size];
        }
    };

    /**
     * Returns the {@link ComponentName} of the app to be launched.
     *
     * @return the target component
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Returns the {@link Intent} that can be used to launch the app.
     *
     * @return the launch intent
     */
    public Intent getLaunchIntent() {
        return mLaunchIntent;
    }
}

