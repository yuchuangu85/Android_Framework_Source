/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.health.connect.datatypes;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Objects;
import java.util.function.Function;

/** Application Info class containing details about a given application */
public final class AppInfo {
    /** Application name/label */
    @Nullable private final String mName;

    /** Application icon as bytes */
    @Nullable private final byte[] mIconBytes;

    /** Application package name */
    private final String mPackageName;

    /**
     * Builder for {@link AppInfo}
     *
     * @hide
     */
    public static final class Builder {
        private final String mPackageName;
        @Nullable private String mName;
        @Nullable private byte[] mIconBytes;

        /**
         * @param packageName package name of the application
         */
        public Builder(@NonNull String packageName) {
            Objects.requireNonNull(packageName);
            mPackageName = packageName;
        }

        /** Sets the application name */
        public Builder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /** Sets the application icon */
        public Builder setIcon(@Nullable byte[] icon) {
            mIconBytes = icon;
            return this;
        }

        /**
         * @return Object of {@link AppInfo}
         */
        @NonNull
        public AppInfo build() {
            return new AppInfo(mPackageName, mName, mIconBytes);
        }
    }

    private AppInfo(
            @NonNull String packageName, @Nullable String name, @Nullable byte[] iconBytes) {
        Objects.requireNonNull(packageName);
        mPackageName = packageName;
        mName = name;
        mIconBytes = iconBytes;
    }

    /** Returns the application package name */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns the application icon as bitmap */
    @Nullable
    public Bitmap getIcon() {
        if (mIconBytes == null) {
            return null;
        }
        return BitmapFactory.decodeByteArray(mIconBytes, 0, mIconBytes.length);
    }

    /** @hide */
    @Nullable
    public byte[] getIconBytes() {
        return mIconBytes;
    }

    /** Returns the application name/label */
    @Nullable
    public String getName() {
        return mName;
    }

    /** @hide */
    @NonNull
    public AppInfo toMasked(@NonNull Function<String, String> packageMasker) {
        return new AppInfo(packageMasker.apply(mPackageName), mName, mIconBytes);
    }
}
