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

package android.media.tv.interactive;

import android.annotation.IntDef;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Parcelable to store metadata information about the broadcast interactive app that runs inside
 * the TV interactive app service engine.
 * @hide
 */
public class TvInteractiveAppInfo implements Parcelable {
    @IntDef({
            CONTROL_CODE_AUTOSTART,
            CONTROL_CODE_PRESENT,
            CONTROL_CODE_DESTROY,
            CONTROL_CODE_KILL,
            CONTROL_CODE_STORED_AUTOSTART,
            CONTROL_CODE_STORED_PRESENT,
            CONTROL_CODE_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ControlCode {}
    // --- Control code based on ETSI TS 102 809 V1.3.1 - Clause 5.3.5 ---
    /** App starts automatically when service is selected. */
    public static final int CONTROL_CODE_AUTOSTART = 0x01;
    /** App is available but waits for user to trigger. */
    public static final int CONTROL_CODE_PRESENT = 0x02;
    /** Signaling that the app must be terminated. */
    public static final int CONTROL_CODE_DESTROY = 0x03;
    /** Force termination of the app. */
    public static final int CONTROL_CODE_KILL = 0x04;
    // --- Extended control code ---
    /** Cached/downloaded app starts automatically when service is selected. */
    public static final int CONTROL_CODE_STORED_AUTOSTART = 0x11;
    /** Cached/downloaded app is available but waits for user to trigger. */
    public static final int CONTROL_CODE_STORED_PRESENT = 0x12;
    /** Unknown control code. */
    public static final int CONTROL_CODE_UNKNOWN = 0x00;

    @IntDef({
            INTERACTIVE_APP_TYPE_UNKNOWN,
            INTERACTIVE_APP_TYPE_NCL,
            INTERACTIVE_APP_TYPE_HTML5
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InteractiveAppType {}
    public static final int INTERACTIVE_APP_TYPE_UNKNOWN = 0;
    /**
     * Application uses Nested Context Language (NCL).
     * Common in ISDB-T standards (Ginga-NCL).
     */
    public static final int INTERACTIVE_APP_TYPE_NCL = 1;
    /**
     * Application uses HTML5 / Web Technologies.
     * Common in HbbTV, ATSC 3.0, and newer Ginga specifications.
     */
    public static final int INTERACTIVE_APP_TYPE_HTML5 = 2;

    // -- Extended interactive app states that covers a more detailed lifecycle.
    /**
     * Unknown state of interactive application.
     */
    public static final int INTERACTIVE_APP_STATE_UNKNOWN = 0;
    /**
     * Stopped (or not started) state of interactive application.
     */
    public static final int INTERACTIVE_APP_STATE_STOPPED = 1;
    /**
     * Signaled state of interactive application before it is loaded.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_STOPPED}
     */
    public static final int INTERACTIVE_APP_STATE_SIGNALED = 4;
    /**
     * Loading state of interactive application before it is ready.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_STOPPED}
     */
    public static final int INTERACTIVE_APP_STATE_LOADING = 5;
    /**
     * Ready state of interactive application, indicate app is ready to start.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_STOPPED}
     */
    public static final int INTERACTIVE_APP_STATE_READY = 6;
    /**
     * Starting state of interactive application.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_RUNNING}
     */
    public static final int INTERACTIVE_APP_STATE_STARTING = 7;
    /**
     * Started state of interactive application.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_RUNNING}
     */
    public static final int INTERACTIVE_APP_STATE_STARTED = 8;
    /**
     * Stopping state of interactive application, app has not been fully stopped yet.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_RUNNING}
     */
    public static final int INTERACTIVE_APP_STATE_STOPPING = 9;
    /**
     * Unloaded state of interactive application.
     * Sub-state of {@code TvInteractiveAppManager.INTERACTIVE_APP_STATE_STOPPED}
     */
    public static final int INTERACTIVE_APP_STATE_UNLOADED = 10;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "INTERACTIVE_APP_STATE_", value = {
            INTERACTIVE_APP_STATE_STOPPED,
            INTERACTIVE_APP_STATE_UNKNOWN,
            INTERACTIVE_APP_STATE_SIGNALED,
            INTERACTIVE_APP_STATE_LOADING,
            INTERACTIVE_APP_STATE_READY,
            INTERACTIVE_APP_STATE_STARTING,
            INTERACTIVE_APP_STATE_STARTED,
            INTERACTIVE_APP_STATE_STOPPING,
            INTERACTIVE_APP_STATE_UNLOADED
    })
    public @interface InteractiveAppState {}

    // Unique handle identifying the app instance.
    private final int mHandle;
    // The static id of the interactive app.
    private final String mId;
    // The organization id owning the interactive app.
    private final String mOrganization;
    // Type of the interactive app.
    private final @InteractiveAppType int mType;
    // Current state of the app.
    private final @InteractiveAppState int mState;
    // Control code associated with the app.
    private final @ControlCode int mControlCode;
    // Display name of the app.
    private final String mName;
    // User-facing description of the app.
    private final String mDescription;
    // Status flag to indicate if the service for this app is bound.
    private final boolean mIsServiceBound;
    // Status flag to indicate if the app is downloaded on non-volatile memory.
    private final boolean mIsStored;
    // Version of the interactive app.
    private final String mVersion;
    // Size of the interactive app.
    private final int mSize;
    // Icon of the interactive app.
    private final Bitmap mIcon;
    // Extra bundle.
    private final Bundle mExtra;

    private TvInteractiveAppInfo(Builder builder) {
        this.mHandle = builder.mHandle;
        this.mId = builder.mId;
        this.mOrganization = builder.mOrganization;
        this.mType = builder.mType;
        this.mState = builder.mState;
        this.mControlCode = builder.mControlCode;
        this.mName = builder.mName;
        this.mDescription = builder.mDescription;
        this.mIsServiceBound = builder.mIsServiceBound;
        this.mIsStored = builder.mIsStored;
        this.mVersion = builder.mVersion;
        this.mSize = builder.mSize;
        this.mIcon = builder.mIcon;
        this.mExtra = builder.mExtra;
    }

    public int getHandle() {
        return mHandle;
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public String getOrganization() {
        return mOrganization;
    }

    public @InteractiveAppType int getType() {
        return mType;
    }

    public @InteractiveAppState int getState() {
        return mState;
    }

    public @ControlCode int getControlCode() {
        return mControlCode;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    public String getDescription() {
        return mDescription;
    }

    public String getVersion() {
        return mVersion;
    }

    public boolean isServiceBound() {
        return mIsServiceBound;
    }

    public boolean isStored() {
        return mIsStored;
    }

    public int getSize() {
        return mSize;
    }

    public Bitmap getIcon() {
        return mIcon;
    }

    public Bundle getExtra() {
        return mExtra;
    }

    private TvInteractiveAppInfo(Parcel in) {
        mHandle = in.readInt();
        mId = in.readString8();
        mOrganization = in.readString8();
        mType = in.readInt();
        mState = in.readInt();
        mControlCode = in.readInt();
        mName = in.readString8();
        mDescription = in.readString8();
        mIsServiceBound = in.readInt() != 0;
        mIsStored = in.readInt() != 0;
        mVersion = in.readString8();
        mSize = in.readInt();
        mIcon = in.readParcelable(Bitmap.class.getClassLoader(), Bitmap.class);
        mExtra = in.readBundle(TvInteractiveAppInfo.class.getClassLoader());
    }

    public static final Creator<TvInteractiveAppInfo> CREATOR =
            new Creator<TvInteractiveAppInfo>() {
                @Override
                public TvInteractiveAppInfo createFromParcel(Parcel in) {
                    return new TvInteractiveAppInfo(in);
                }

                @Override
                public TvInteractiveAppInfo[] newArray(int size) {
                    return new TvInteractiveAppInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mHandle);
        dest.writeString8(mId);
        dest.writeString8(mOrganization);
        dest.writeInt(mType);
        dest.writeInt(mState);
        dest.writeInt(mControlCode);
        dest.writeString8(mName);
        dest.writeString8(mDescription);
        dest.writeInt(mIsServiceBound ? 1 : 0);
        dest.writeInt(mIsStored ? 1 : 0);
        dest.writeString8(mVersion);
        dest.writeInt(mSize);
        dest.writeParcelable(mIcon, flags);
        dest.writeBundle(mExtra);
    }

    /**
     * A builder class for {@link TvInteractiveAppInfo}.
     */
    public static final class Builder {
        private int mHandle;
        private String mId;
        private String mOrganization;
        private @InteractiveAppType int mType = INTERACTIVE_APP_TYPE_UNKNOWN;
        private @InteractiveAppState int mState = INTERACTIVE_APP_STATE_UNKNOWN;
        private @ControlCode int mControlCode = CONTROL_CODE_UNKNOWN;
        private String mName;
        private boolean mIsServiceBound;
        private boolean mIsStored;
        private String mDescription = "";  // optional
        private String mVersion = "";  // optional
        private int mSize = -1;  // optional
        private Bitmap mIcon = null; // optional
        private Bundle mExtra = null; // optional

        public Builder() {
        }

        /**
         * Sets the session-based identifier assigned by the server (TIAS) for the interactive app.
         * This handle is unique to this specific runtime instance.
         *
         * @param handle The handle of the interactive app.
         * @return This Builder object.
         */
        public Builder setHandle(int handle) {
            mHandle = handle;
            return this;
        }

        /**
         * Sets the static, semantic identifier of the interactive app.
         *
         * @param id The ID of the interactive app.
         * @return This Builder object.
         */
        public Builder setId(@NonNull String id) {
            mId = id;
            return this;
        }

        /**
         * Sets the organization of the interactive app.
         *
         * @param organization The organization of the interactive app.
         * @return This Builder object.
         */
        public Builder setOrganization(@NonNull String organization) {
            mOrganization = organization;
            return this;
        }

        /**
         * Sets the type of the interactive app.
         *
         * @param type The type of the interactive app.
         * @return This Builder object.
         */
        public Builder setType(@InteractiveAppType int type) {
            mType = type;
            return this;
        }

        /**
         * Sets the state of the interactive app.
         *
         * @param state The state of the interactive app.
         * @return This Builder object.
         */
        public Builder setState(@InteractiveAppState int state) {
            mState = state;
            return this;
        }

        /**
         * Sets the control code of the interactive app.
         *
         * @param controlCode The control code of the interactive app.
         * @return This Builder object.
         */
        public Builder setControlCode(@ControlCode int controlCode) {
            mControlCode = controlCode;
            return this;
        }

        /**
         * Sets the name of the interactive app.
         *
         * @param name The name of the interactive app.
         * @return This Builder object.
         */
        public Builder setName(@NonNull String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the description of the interactive app.
         *
         * @param description The description of the interactive app.
         * @return This Builder object.
         */
        public Builder setDescription(@NonNull String description) {
            mDescription = description;
            return this;
        }

        /**
         * Sets whether the service is bound.
         *
         * @param isServiceBound {@code true} if the service is bound, {@code false} otherwise.
         * @return This Builder object.
         */
        public Builder setIsServiceBound(boolean isServiceBound) {
            mIsServiceBound = isServiceBound;
            return this;
        }

        /**
         * Sets whether the app is stored.
         *
         * @param isStored {@code true} if the app is stored, {@code false} otherwise.
         * @return This Builder object.
         */
        public Builder setIsStored(boolean isStored) {
            mIsStored = isStored;
            return this;
        }

        /**
         * Sets the version of the interactive app.
         *
         * @param version The version of the interactive app.
         * @return This Builder object.
         */
        public Builder setVersion(@NonNull String version) {
            mVersion = version;
            return this;
        }

        /**
         * Sets the size of the interactive app.
         *
         * @param size The size of the interactive app.
         * @return This Builder object.
         */
        public Builder setSize(int size) {
            mSize = size;
            return this;
        }

        /**
         * Sets the icon of the interactive app.
         *
         * @param icon The icon of the interactive app.
         * @return This Builder object.
         */
        public Builder setIcon(Bitmap icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the extra bundle of the interactive app.
         *
         * @param extra bundle containing extra information.
         * @return This Builder object.
         */
        public Builder setExtra(Bundle extra) {
            mExtra = extra;
            return this;
        }

        /**
         * Builds a {@link TvInteractiveAppInfo} object.
         *
         * @return The {@link TvInteractiveAppInfo} object.
         */
        public TvInteractiveAppInfo build() {
            return new TvInteractiveAppInfo(this);
        }
    }
}
