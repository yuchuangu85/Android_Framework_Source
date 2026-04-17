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
package android.text;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings.Secure;

import com.android.text.flags.Flags;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This is the API surface for interacting with the settings that determine whether characters in
 * password inputs or other secret fields are echoed briefly or hidden immediately.
 */
@FlaggedApi(Flags.FLAG_SPLIT_SHOW_PASSWORDS_TO_TOUCH_AND_PHYSICAL)
@SuppressLint("PackageLayering")
public final class ShowSecretsSetting {
    private static final int SHOW = 1;
    private static final int HIDE = 0;

    private ShowSecretsSetting() {}

    /**
     * If enabled system services and SDKs will use the new split settings instead of {@link
     * android.provider.Settings.System#TEXT_SHOW_PASSWORD}.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.CINNAMON_BUN)
    @TestApi
    public static final long SPLIT_SHOW_PASSWORDS_TO_TOUCH_AND_PHYSICAL = 417951523;

    /**
     * Returns {@code true} when characters entered into a password, pin or other secret field from
     * touch/virtual input sources should either be shown or echoed briefly.
     */
    public static boolean shouldShowTouchInput(@NonNull Context context) {
        return Secure.getIntForUser(
                        Objects.requireNonNull(context).getContentResolver(),
                        Secure.TEXT_SHOW_PASSWORD_TOUCH,
                        SHOW,
                        context.getUser().getIdentifier())
                == SHOW;
    }

    /**
     * Returns {@code true} when characters entered into a password, pin or other secret field from
     * hardware/physical input sources should either be shown or echoed briefly.
     */
    public static boolean shouldShowPhysicalInput(@NonNull Context context) {
        return Secure.getIntForUser(
                        Objects.requireNonNull(context).getContentResolver(),
                        Secure.TEXT_SHOW_PASSWORD_PHYSICAL,
                        HIDE,
                        context.getUser().getIdentifier())
                == SHOW;
    }

    /**
     * Set the underlying setting to either show/echo or hide characters from touch/virtual input in
     * password-like input fields immediately.
     *
     * @param context The Context to access.
     * @param shouldShow Set to {@code true} if characters should be shown/echoed briefly.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public static void setShouldShowTouchInput(@NonNull Context context, boolean shouldShow) {
        setSettingValue(
                Objects.requireNonNull(context).getContentResolver(),
                Secure.TEXT_SHOW_PASSWORD_TOUCH,
                shouldShow,
                context.getUser());
    }

    /**
     * Set the underlying setting to either show/echo or hide characters from hardware/physical
     * inputs in password-like input fields immediately.
     *
     * @param context The Context to access.
     * @param shouldShow Set to {@code true} if characters should be shown/echoed briefly.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public static void setShouldShowPhysicalInput(@NonNull Context context, boolean shouldShow) {
        setSettingValue(
                Objects.requireNonNull(context).getContentResolver(),
                Secure.TEXT_SHOW_PASSWORD_PHYSICAL,
                shouldShow,
                context.getUser());
    }

    /**
     * Registers a callback to be notified when show password settings change. The callback will be
     * invoked on the main thread.
     *
     * @param context The context used to access settings.
     * @param callback The callback to invoke.
     * @return A runnable that unregisters the callback when run.
     */
    @SuppressLint("PairedRegistration")
    @NonNull
    public static Runnable registerCallback(
            @NonNull Context context, @NonNull Runnable callback) {
        return registerCallback(context, context.getMainExecutor(), callback);
    }

    /**
     * Registers a callback to be notified when show password settings change.
     *
     * @param context The context used to access settings.
     * @param executor The executor on which to invoke the callback.
     * @param callback The callback to invoke.
     * @return A runnable that unregisters the callback when run.
     */
    @SuppressLint("PairedRegistration")
    @NonNull
    public static Runnable registerCallback(
            @NonNull Context context,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Runnable callback) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        ContentObserver observer =
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        executor.execute(callback);
                    }
                };
        ContentResolver resolver = context.getContentResolver();
        resolver.registerContentObserver(
                Secure.getUriFor(Secure.TEXT_SHOW_PASSWORD_TOUCH), true, observer);
        resolver.registerContentObserver(
                Secure.getUriFor(Secure.TEXT_SHOW_PASSWORD_PHYSICAL), true, observer);
        return () -> resolver.unregisterContentObserver(observer);
    }

    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    private static void setSettingValue(
            ContentResolver resolver, String key, boolean newValue, UserHandle user) {
        Secure.putIntForUser(resolver, key, newValue ? SHOW : HIDE, user.getIdentifier());
    }
}
