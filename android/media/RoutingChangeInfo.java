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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Captures information about the change in media routing for logging purposes.
 *
 * @hide
 */
public final class RoutingChangeInfo implements Parcelable {

    // Indicates the start point of a media session.
    private final @EntryPoint int mEntryPoint;

    // Indicates that the route was a suggested route.
    private final boolean mIsSuggested;

    // Indicates the suggestion providers for a route.
    private final @SuggestionProviderFlags int mSuggestionProviderFlags;

    /**
     * Indicates that a routing session started as the result of selecting a route from the output
     * switcher.
     *
     * @hide
     */
    public static final int ENTRY_POINT_SYSTEM_OUTPUT_SWITCHER = 1;

    /**
     * Indicates that a routing session started as the result of selecting the device suggestion
     * pill in the system media controls.
     *
     * @hide
     */
    public static final int ENTRY_POINT_SYSTEM_MEDIA_CONTROLS = 2;

    /**
     * Indicates that a routing session was started from a local media router instance where the
     * entry point was not specified.
     *
     * <p>This entry point is marked when {@link MediaRouter2#transferTo(MediaRoute2Info)} is called
     * on a local media router instance.
     *
     * @hide
     */
    public static final int ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED = 3;

    /**
     * Indicates that a routing session was started from a proxy media router instance where the
     * entry point was not specified.
     *
     * <p>This entry point is marked when {@link MediaRouter2#transferTo(MediaRoute2Info)} is called
     * on a proxy media router instance.
     *
     * @hide
     */
    public static final int ENTRY_POINT_PROXY_ROUTER_UNSPECIFIED = 4;

    /**
     * Indicates that a routing session started as the result of selecting a route from the output
     * switcher in TV.
     *
     * @hide
     */
    public static final int ENTRY_POINT_TV_OUTPUT_SWITCHER = 5;

    /** @hide */
    @IntDef(
            prefix = "ENTRY_POINT",
            value = {
                ENTRY_POINT_SYSTEM_OUTPUT_SWITCHER,
                ENTRY_POINT_SYSTEM_MEDIA_CONTROLS,
                ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED,
                ENTRY_POINT_PROXY_ROUTER_UNSPECIFIED,
                ENTRY_POINT_TV_OUTPUT_SWITCHER
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EntryPoint {}

    /**
     * Flag indicating that the route was suggested by {@link RouteListingPreference}.
     *
     * @hide
     */
    public static final int SUGGESTION_PROVIDER_RLP = 1;

    /**
     * Flag indicating that the route was suggested by the app as a device suggestion.
     *
     * @hide
     */
    public static final int SUGGESTION_PROVIDER_DEVICE_SUGGESTION_APP = 1 << 1;

    /**
     * Flag indicating that the route was suggested as a device suggestion by an app not playing the
     * media.
     *
     * @hide
     */
    public static final int SUGGESTION_PROVIDER_DEVICE_SUGGESTION_OTHER = 1 << 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "SUGGESTION_PROVIDER",
            flag = true,
            value = {
                SUGGESTION_PROVIDER_RLP,
                SUGGESTION_PROVIDER_DEVICE_SUGGESTION_APP,
                SUGGESTION_PROVIDER_DEVICE_SUGGESTION_OTHER
            })
    public @interface SuggestionProviderFlags {}

    @NonNull
    public static final Creator<RoutingChangeInfo> CREATOR =
            new Creator<>() {
                @Override
                public RoutingChangeInfo createFromParcel(Parcel in) {
                    return new RoutingChangeInfo(in);
                }

                @Override
                public RoutingChangeInfo[] newArray(int size) {
                    return new RoutingChangeInfo[size];
                }
            };

    public RoutingChangeInfo(@EntryPoint int entryPoint, boolean isSuggested) {
        this(entryPoint, isSuggested, /* suggestionProviderFlags= */ 0);
    }

    public RoutingChangeInfo(
            @EntryPoint int entryPoint,
            boolean isSuggested,
            @SuggestionProviderFlags int suggestionProviderFlags) {
        mEntryPoint = entryPoint;
        mIsSuggested = isSuggested;
        mSuggestionProviderFlags = suggestionProviderFlags;
    }

    private RoutingChangeInfo(Parcel in) {
        mEntryPoint = in.readInt();
        mIsSuggested = in.readBoolean();
        mSuggestionProviderFlags = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
        dest.writeInt(mEntryPoint);
        dest.writeBoolean(mIsSuggested);
        dest.writeInt(mSuggestionProviderFlags);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof RoutingChangeInfo other)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        return other.getEntryPoint() == this.mEntryPoint
                && other.isSuggested() == this.mIsSuggested
                && other.mSuggestionProviderFlags == this.mSuggestionProviderFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mEntryPoint, mIsSuggested, mSuggestionProviderFlags);
    }

    public @EntryPoint int getEntryPoint() {
        return mEntryPoint;
    }

    public boolean isSuggested() {
        return mIsSuggested;
    }

    public @SuggestionProviderFlags int getSuggestionProviderFlags() {
        return mSuggestionProviderFlags;
    }

    /**
     * Returns whether the route had an active suggestion from the active route listing preference.
     */
    public boolean isSuggestedByRlp() {
        return (mSuggestionProviderFlags & SUGGESTION_PROVIDER_RLP) != 0;
    }

    /** Returns whether the route had an active device suggestion from the media app. */
    public boolean isSuggestedByMediaApp() {
        return (mSuggestionProviderFlags & SUGGESTION_PROVIDER_DEVICE_SUGGESTION_APP) != 0;
    }

    /** Whether the route had an active device suggestion from an app other than the media app. */
    public boolean isSuggestedByAnotherApp() {
        return (mSuggestionProviderFlags & SUGGESTION_PROVIDER_DEVICE_SUGGESTION_OTHER) != 0;
    }
}
