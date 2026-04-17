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

package android.app.contextualsearch;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;

import java.util.Objects;

/**
 * Configuration for Contextual Search invocations. Typically the parameters added here are passed
 * to the Contextual Search provider app as specified by the device configuration.
 *
 * @hide
 */
@SystemApi
public final class ContextualSearchConfig implements Parcelable {

    private final int mDisplayId;
    @Nullable private final Rect mSourceBounds;
    @NonNull private final Bundle mIntentExtras;
    @Intent.Flags private final int mLaunchFlags;

    /**
     * Default configuration for Contextual Search.
     *
     * @hide
     */
    @NonNull
    public static final ContextualSearchConfig DEFAULT_CONFIG = new Builder().build();

    public static final @NonNull Creator<ContextualSearchConfig> CREATOR =
            new Creator<>() {
                @Override
                public ContextualSearchConfig createFromParcel(@NonNull Parcel in) {
                    return new ContextualSearchConfig(in);
                }

                @Override
                public ContextualSearchConfig[] newArray(int size) {
                    return new ContextualSearchConfig[size];
                }
            };

    ContextualSearchConfig(@NonNull Parcel in) {
        mDisplayId = in.readInt();
        mSourceBounds = in.readTypedObject(Rect.CREATOR);
        mIntentExtras = Objects.requireNonNull(
                in.readBundle(ContextualSearchConfig.class.getClassLoader()));
        mLaunchFlags = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDisplayId);
        dest.writeTypedObject(mSourceBounds, flags);
        dest.writeBundle(mIntentExtras);
        dest.writeInt(mLaunchFlags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private ContextualSearchConfig(@NonNull Builder builder) {
        mDisplayId = builder.mDisplayId;
        mSourceBounds = builder.mSourceBounds;
        mIntentExtras = builder.mIntentExtras;
        mLaunchFlags = builder.mLaunchFlags;
    }

    /**
     * @return The ID of the display where the search was triggered. This determines where the
     *         screenshot is taken and displayed for user interaction. If the display ID is invalid,
     *         the invocation will fail silently.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * @return The bounds of the source element that triggered the search, in screen coordinates.
     *         Can be null if not available.
     */
    @Nullable
    public Rect getSourceBounds() {
        return mSourceBounds == null ? null : new Rect(mSourceBounds);
    }

    /**
     * @return Extras to be added to the Intent sent to the Contextual Search app. These will be
     *         merged with any other extras added to the Intent by ContextualSearchManagerService.
     */
    @NonNull
    public Bundle getIntentExtras() {
        return new Bundle(mIntentExtras);
    }

    /**
     * Returns the launch flags for the Intent that launches Contextual Search.
     *
     * <p>Note: {@link Intent#FLAG_ACTIVITY_NEW_TASK} is always applied by the system.
     *
     * @return The launch flags if set, or the default flags if not set.
     */
    @Intent.Flags
    @SuppressLint("UnflaggedApi")
    public int getLaunchFlags() {
        return mLaunchFlags;
    }

    @Override
    public String toString() {
        return "ContextualSearchConfig{"
            + "mDisplayId=" + mDisplayId + ", "
            + "mSourceBounds=" + mSourceBounds + ", "
            + "mIntentExtras=" + mIntentExtras + ", "
            + "mLaunchFlags=" + mLaunchFlags
            + '}';
    }

    /**
     * Builder to create a {@link ContextualSearchConfig}.
     */
    public static final class Builder {

        private int mDisplayId;
        @Nullable private Rect mSourceBounds;
        @NonNull private final Bundle mIntentExtras;
        @Intent.Flags private int mLaunchFlags;

        /**
         * Creates a new Builder with default values.
         */
        public Builder() {
            mDisplayId = Display.INVALID_DISPLAY;
            mSourceBounds = null;
            mIntentExtras = new Bundle();
            mLaunchFlags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        }

        /**
         * Creates a new builder and initializes it with the values from the given
         * {@link ContextualSearchConfig}.
         *
         * @param config The config to copy values from.
         */
        public Builder(@NonNull ContextualSearchConfig config) {
            mDisplayId = config.getDisplayId();
            mSourceBounds = config.getSourceBounds();
            mIntentExtras = config.getIntentExtras();
            mLaunchFlags = config.getLaunchFlags();
        }

        /**
         * Sets the display ID for the contextual search invocation.
         *
         * <p>Defaults to {@link Display#INVALID_DISPLAY}.
         *
         * @param displayId The ID of the display where the search was triggered. This determines
         *                  where the screenshot is taken and displayed for user interaction. If the
         *                  display ID is invalid, the invocation will fail silently. If not
         *                  specified, the system will use {@link Display#DEFAULT_DISPLAY}, or the
         *                  Activity's display if launched from an Activity.
         * @return This Builder object to allow for chaining of calls.
         */
        @NonNull
        public Builder setDisplayId(int displayId) {
            mDisplayId = displayId;
            return this;
        }

        /**
         * Sets the source bounds for the contextual search invocation.
         *
         * <p>Defaults to {@code null}.
         *
         * @param sourceBounds The bounds of the source element that triggered the search, in screen
         *                     coordinates. Can be null if not available.
         * @return This Builder object to allow for chaining of calls.
         */
        @NonNull
        public Builder setSourceBounds(@Nullable Rect sourceBounds) {
            mSourceBounds = sourceBounds == null ? null : new Rect(sourceBounds);
            return this;
        }

        /**
         * Sets any additional extras to be added to the intent sent to the Contextual Search app.
         *
         * <p>Defaults to an empty {@link Bundle}.
         *
         * @param intentExtras This will be merged with any other extras added to the intent by
         *                     ContextualSearchManagerService. To avoid having your extras
         *                     overwritten, prefix the keys with an agreed package name.
         * @return This Builder object to allow for chaining of calls.
         */
        @NonNull
        public Builder setIntentExtras(@Nullable Bundle intentExtras) {
            mIntentExtras.clear();
            if (intentExtras != null) {
                mIntentExtras.putAll(intentExtras);
            }
            return this;
        }

        /**
         * Sets the launch flags for the contextual search invocation. These flags will replace the
         * default launch flags, which are:
         * {@link Intent#FLAG_ACTIVITY_NEW_TASK}, {@link Intent#FLAG_ACTIVITY_NO_ANIMATION},
         * {@link Intent#FLAG_ACTIVITY_NO_USER_ACTION}, and {@link Intent#FLAG_ACTIVITY_CLEAR_TASK}.
         *
         * <p>Note: {@link Intent#FLAG_ACTIVITY_NEW_TASK} is always added by the system.
         *
         * @param launchFlags The flags to replace the default flags on the Intent used to launch
         *                    Contextual Search.
         * @return This Builder object to allow for chaining of calls.
         */
        @NonNull
        @SuppressLint("UnflaggedApi")
        public Builder setLaunchFlags(@Intent.Flags int launchFlags) {
            mLaunchFlags = launchFlags;
            return this;
        }

        /**
         * Builds the {@link ContextualSearchConfig} instance.
         *
         * @return The built {@link ContextualSearchConfig} object.
         */
        @NonNull
        public ContextualSearchConfig build() {
            mLaunchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            return new ContextualSearchConfig(this);
        }
    }
}
