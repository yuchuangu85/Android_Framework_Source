/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.input;

import static com.android.hardware.input.Flags.createVirtualKeyboardApi;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.os.Parcel;
import android.view.Display;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Common configurations to create virtual input devices.
 *
 * @hide
 */
@SystemApi
public abstract class VirtualInputDeviceConfig {

    /**
     * The maximum length of a device name (in bytes in UTF-8 encoding).
     *
     * This limitation comes directly from uinput.
     * See also UINPUT_MAX_NAME_SIZE in linux/uinput.h
     */
    private static final int DEVICE_NAME_MAX_LENGTH = 80;

    private static final ViewBehaviorConfig DEFAULT_VIEW_BEHAVIOR_CONFIG =
            new ViewBehaviorConfig.Builder().build();

    /** The vendor id uniquely identifies the company who manufactured the device. */
    private final int mVendorId;
    /**
     * The product id uniquely identifies which product within the address space of a given vendor,
     * identified by the device's vendor id.
     */
    private final int mProductId;
    /** The associated display ID of the virtual input device. */
    private final int mAssociatedDisplayId;
    /** The name of the virtual input device. */
    @NonNull
    private final String mInputDeviceName;
    @NonNull
    private final ViewBehaviorConfig mViewBehaviorConfig;

    protected VirtualInputDeviceConfig(@NonNull Builder<? extends Builder<?>> builder) {
        mVendorId = builder.mVendorId;
        mProductId = builder.mProductId;
        mAssociatedDisplayId = builder.mAssociatedDisplayId;
        mInputDeviceName = Objects.requireNonNull(builder.mInputDeviceName, "Missing device name");
        mViewBehaviorConfig = Objects.requireNonNull(builder.mViewBehaviorConfig,
                "Missing view behavior config");

        // Check if no display association is allowed.
        if (!createVirtualKeyboardApi()) {
            if (mAssociatedDisplayId == Display.INVALID_DISPLAY) {
                throw new IllegalArgumentException(
                        "Display association is required for virtual input devices.");
            }
        }

        // Comparison is greater or equal because the device name must fit into a const char*
        // including the \0-terminator. Therefore the actual number of bytes that can be used
        // for device name is DEVICE_NAME_MAX_LENGTH - 1
        if (mInputDeviceName.getBytes(StandardCharsets.UTF_8).length >= DEVICE_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Input device name exceeds maximum length of "
                    + DEVICE_NAME_MAX_LENGTH + "bytes: " + mInputDeviceName);
        }
    }

    protected VirtualInputDeviceConfig(@NonNull Parcel in) {
        mVendorId = in.readInt();
        mProductId = in.readInt();
        mAssociatedDisplayId = in.readInt();
        mInputDeviceName = Objects.requireNonNull(in.readString8(), "Missing device name");
        mViewBehaviorConfig = Objects.requireNonNull(in.readTypedObject(ViewBehaviorConfig.CREATOR),
                "Missing view behavior config");
    }

    /**
     * The vendor id uniquely identifies the company who manufactured the device.
     *
     * @see Builder#setVendorId(int) (int)
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * The product id uniquely identifies which product within the address space of a given vendor,
     * identified by the device's vendor id.
     *
     * @see Builder#setProductId(int)
     */
    public int getProductId() {
        return mProductId;
    }

    /**
     * The associated display ID of the virtual input device.
     *
     * @see Builder#setAssociatedDisplayId(int)
     */
    public int getAssociatedDisplayId() {
        return mAssociatedDisplayId;
    }

    /**
     * The name of the virtual input device.
     *
     * @see Builder#setInputDeviceName(String)
     */
    @NonNull
    public String getInputDeviceName() {
        return mInputDeviceName;
    }

    /**
     * Returns the {@link ViewBehaviorConfig} for the input device.
     *
     * @see android.view.InputDevice.ViewBehavior
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_INPUT_VIEW_BEHAVIOR)
    @NonNull
    public ViewBehaviorConfig getViewBehaviorConfig() {
        return mViewBehaviorConfig;
    }

    /**
     * Returns the {@link ViewBehaviorConfig} for the input device ({@code defaultValue} for
     * default {@link ViewBehaviorConfig}).
     *
     * @hide
     */
    @Nullable
    public ViewBehaviorConfig getViewBehaviorConfigOrDefault(
            @Nullable ViewBehaviorConfig defaultValue) {
        return DEFAULT_VIEW_BEHAVIOR_CONFIG.equals(mViewBehaviorConfig) ? defaultValue
                : mViewBehaviorConfig;
    }

    /**
     * Checks if a display ID is valid.
     *
     * @throws IllegalArgumentException if an invalid display is associated with this device.
     * @hide
     * @see Builder#setAssociatedDisplayId(int)
     */
    public void checkForAssociatedDisplay() {
        if (getAssociatedDisplayId() == Display.INVALID_DISPLAY) {
            throw new IllegalArgumentException(
                    "Display association is required for virtual devices.");
        }
    }

    void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mVendorId);
        dest.writeInt(mProductId);
        dest.writeInt(mAssociatedDisplayId);
        dest.writeString8(mInputDeviceName);
        dest.writeTypedObject(mViewBehaviorConfig, flags);
    }

    @Override
    public String toString() {
        return getClass().getName() + "( "
                + " name=" + mInputDeviceName
                + " vendorId=" + mVendorId
                + " productId=" + mProductId
                + " associatedDisplayId=" + mAssociatedDisplayId
                + " viewBehaviorConfig=" + mViewBehaviorConfig
                + additionalFieldsToString() + ")";
    }

    /** @hide */
    @NonNull
    String additionalFieldsToString() {
        return "";
    }

    /**
     * A builder for {@link VirtualInputDeviceConfig}
     *
     * @param <T> The subclass to be built.
     */
    @SuppressWarnings({"StaticFinalBuilder", "MissingBuildMethod"})
    public abstract static class Builder<T extends Builder<T>> {
        private int mVendorId;
        private int mProductId;
        private int mAssociatedDisplayId = Display.INVALID_DISPLAY;
        private String mInputDeviceName;
        @NonNull
        private ViewBehaviorConfig mViewBehaviorConfig = DEFAULT_VIEW_BEHAVIOR_CONFIG;

        /**
         * Sets the vendor id of the device, identifying the company who manufactured the device.
         */
        @NonNull
        public T setVendorId(int vendorId) {
            mVendorId = vendorId;
            return self();
        }

        /**
         * Sets the product id of the device, uniquely identifying the device within the address
         * space of a given vendor, identified by the device's vendor id.
         */
        @NonNull
        public T setProductId(int productId) {
            mProductId = productId;
            return self();
        }

        /**
         * Sets the associated display ID of the virtual input device.
         *
         * <p>If an explicit display association is specified, the associated display must be owned
         * by the caller. The virtual input device is restricted to the display with the given ID
         * and may not send events to any other display.</p>
         * <p>The specified display must be trusted or mirror display.</p>
         *
         * <p>If there is no specific display ID to associate with, use
         * {@link Display#DEFAULT_DISPLAY} or {@link Display#INVALID_DISPLAY}.
         * Using {@link Display#INVALID_DISPLAY} requires the caller to be have either
         * {@link android.Manifest.permission#INJECT_KEY_EVENTS} or
         * {@link android.Manifest.permission#INJECT_EVENTS}
         * </p>
         *
         * <p>Use {@link Display#DEFAULT_DISPLAY} if the virtual input device should only send
         * events to the primary default display. Events will not be sent to focused windows of the
         * non-primary display, e.g. extended monitor.</p>
         * <p>Use {@link Display#INVALID_DISPLAY} if there is no display association and
         * will only send events to the currently focused window of the currently focused display.
         * ONLY allowed for virtual input devices that exclusively sends key events, i.e. keyboard
         * and dpad, and if the caller has either of the aforementioned permissions.</p>
         *
         * @see android.hardware.display.DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED
         * @see android.hardware.display.DisplayManager#VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
         */
        @SuppressLint("RequiresPermission")
        @NonNull
        public T setAssociatedDisplayId(int displayId) {
            mAssociatedDisplayId = displayId;
            return self();
        }

        /**
         * Sets the name of the virtual input device. Required.
         *
         * <p>The name must be unique among all input devices that belong to the same virtual
         * device.</p>
         *
         * <p>The maximum allowed length of the name is 80 bytes in UTF-8 encoding, enforced by
         * {@code UINPUT_MAX_NAME_SIZE}.</p>
         */
        @NonNull
        public T setInputDeviceName(@NonNull String deviceName) {
            mInputDeviceName = Objects.requireNonNull(deviceName);
            return self();
        }

        /**
         * Sets an optional {@link ViewBehaviorConfig} for the input device.
         *
         * @return this builder, to allow for chaining of calls.
         * @see #getViewBehaviorConfig
         */
        @FlaggedApi(Flags.FLAG_VIRTUAL_INPUT_VIEW_BEHAVIOR)
        @NonNull
        public T setViewBehaviorConfig(@NonNull ViewBehaviorConfig viewBehaviorConfig) {
            mViewBehaviorConfig = Objects.requireNonNull(viewBehaviorConfig);
            return self();
        }

        /**
         * Each subclass should return itself to allow the builder to chain properly
         */
        T self() {
            return (T) this;
        }
    }
}
