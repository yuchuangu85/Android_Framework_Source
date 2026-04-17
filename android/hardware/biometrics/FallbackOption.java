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

package android.hardware.biometrics;

import static android.hardware.biometrics.Flags.FLAG_ADD_FALLBACK;

import android.annotation.FlaggedApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Contains the information for a fallback option to be displayed within Biometric Prompt.
 */
@FlaggedApi(FLAG_ADD_FALLBACK)
public final class FallbackOption implements Parcelable {
    @NonNull private final CharSequence mText;
    @BiometricManager.IconType private final int mIconType;

    public @NonNull CharSequence getText() {
        return mText;
    }

    public @BiometricManager.IconType int getIconType() {
        return mIconType;
    }

    public FallbackOption(@NonNull CharSequence text,
            @BiometricManager.IconType int iconType) {
        this.mText = text;
        this.mIconType = iconType;
    }

    private FallbackOption(Parcel in) {
        mText = in.readCharSequence();
        mIconType = in.readInt();
    }

    @NonNull public static final Creator<FallbackOption> CREATOR =
            new Creator<>() {
                @Override
                public FallbackOption createFromParcel(Parcel in) {
                    return new FallbackOption(in);
                }

                @Override
                public FallbackOption[] newArray(int size) {
                    return new FallbackOption[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeCharSequence(mText);
        dest.writeInt(mIconType);
    }
}
