/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.service.dreams;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Represents a dream item within the dream playlist.
 *
 * @hide
 */
public final class DreamItem implements Parcelable {
    @NonNull public final ComponentName componentName;
    @Nullable public final ComponentName settingsActivity;
    @Nullable public final Icon previewImage;
    @Nullable public final CharSequence title;
    @Nullable public final CharSequence description;
    @Nullable public final Icon icon;

    private DreamItem(Builder builder) {
        this.componentName = builder.mComponentName;
        this.settingsActivity = builder.mSettingsActivity;
        this.previewImage = builder.mPreviewImage;
        this.title = builder.mTitle;
        this.description = builder.mDescription;
        this.icon = builder.mIcon;
    }

    private DreamItem(Parcel in) {
        componentName = Objects.requireNonNull(in.readTypedObject(ComponentName.CREATOR));
        settingsActivity = in.readTypedObject(ComponentName.CREATOR);
        previewImage = in.readTypedObject(Icon.CREATOR);
        title = in.readCharSequence();
        description = in.readCharSequence();
        icon = in.readTypedObject(Icon.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(componentName, flags);
        dest.writeTypedObject(settingsActivity, flags);
        dest.writeTypedObject(previewImage, flags);
        dest.writeCharSequence(title);
        dest.writeCharSequence(description);
        dest.writeTypedObject(icon, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DreamItem)) return false;
        DreamItem that = (DreamItem) o;
        return Objects.equals(componentName, that.componentName)
                && Objects.equals(settingsActivity, that.settingsActivity)
                && Objects.equals(previewImage, that.previewImage)
                && TextUtils.equals(title, that.title)
                && TextUtils.equals(description, that.description)
                && Objects.equals(icon, that.icon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                componentName, settingsActivity, previewImage, title, description, icon);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(DreamItem.class.getSimpleName());
        sb.append('[').append(title);
        sb.append(',').append(componentName);
        if (settingsActivity != null) {
            sb.append(",settings=").append(settingsActivity);
        }
        return sb.append(']').toString();
    }

    public static final @NonNull Creator<DreamItem> CREATOR =
            new Creator<DreamItem>() {
                @Override
                public DreamItem createFromParcel(Parcel in) {
                    return new DreamItem(in);
                }

                @Override
                public DreamItem[] newArray(int size) {
                    return new DreamItem[size];
                }
            };

    /** Builder for {@link DreamItem}. */
    public static final class Builder {
        private final ComponentName mComponentName;
        private ComponentName mSettingsActivity;
        private Icon mPreviewImage;
        private CharSequence mTitle;
        private CharSequence mDescription;
        private Icon mIcon;

        public Builder(@NonNull ComponentName componentName) {
            mComponentName = Objects.requireNonNull(componentName);
        }

        public Builder setSettingsActivity(@Nullable ComponentName settingsActivity) {
            mSettingsActivity = settingsActivity;
            return this;
        }

        public Builder setPreviewImage(@Nullable Icon previewImage) {
            mPreviewImage = previewImage;
            return this;
        }

        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        public Builder setDescription(@Nullable CharSequence description) {
            mDescription = description;
            return this;
        }

        public Builder setIcon(@Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        public DreamItem build() {
            return new DreamItem(this);
        }
    }
}
