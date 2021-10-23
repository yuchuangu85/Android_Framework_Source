/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.utils.Snappable;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.Watcher;

@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public abstract class SettingBase implements Watchable, Snappable {
    // TODO: make this variable protected, or even private with a getter and setter.
    // Simply making it protected or private requires that the name be changed to conformm
    // to the Android naming convention, and that touches quite a few files.
    int pkgFlags;

    // TODO: make this variable protected, or even private with a getter and setter.
    // Simply making it protected or private requires that the name be changed to conformm
    // to the Android naming convention, and that touches quite a few files.
    int pkgPrivateFlags;

    /**
     * Watchable machinery
     */
    private final Watchable mWatchable = new WatchableImpl();

    /**
     * Ensures an observer is in the list, exactly once. The observer cannot be null.  The
     * function quietly returns if the observer is already in the list.
     *
     * @param observer The {@link Watcher} to be notified when the {@link Watchable} changes.
     */
    @Override
    public void registerObserver(@NonNull Watcher observer) {
        mWatchable.registerObserver(observer);
    }

    /**
     * Ensures an observer is not in the list. The observer must not be null.  The function
     * quietly returns if the objserver is not in the list.
     *
     * @param observer The {@link Watcher} that should not be in the notification list.
     */
    @Override
    public void unregisterObserver(@NonNull Watcher observer) {
        mWatchable.unregisterObserver(observer);
    }

    /**
     * Return true if the {@link Watcher) is a registered observer.
     * @param observer A {@link Watcher} that might be registered
     * @return true if the observer is registered with this {@link Watchable}.
     */
    @Override
    public boolean isRegisteredObserver(@NonNull Watcher observer) {
        return mWatchable.isRegisteredObserver(observer);
    }

    /**
     * Invokes {@link Watcher#onChange} on each registered observer.  The method can be called
     * with the {@link Watchable} that generated the event.  In a tree of {@link Watchable}s, this
     * is generally the first (deepest) {@link Watchable} to detect a change.
     *
     * @param what The {@link Watchable} that generated the event.
     */
    @Override
    public void dispatchChange(@Nullable Watchable what) {
        mWatchable.dispatchChange(what);
    }

    /**
     * Notify listeners that this object has changed.
     */
    protected void onChanged() {
        dispatchChange(this);
    }

    /**
     * The legacy permission state that is read from package settings persistence for migration.
     * This state here can not reflect the current permission state and should not be used for
     * purposes other than migration.
     */
    @Deprecated
    protected final LegacyPermissionState mLegacyPermissionsState;

    SettingBase(int pkgFlags, int pkgPrivateFlags) {
        setFlags(pkgFlags);
        setPrivateFlags(pkgPrivateFlags);
        mLegacyPermissionsState = new LegacyPermissionState();
    }

    SettingBase(SettingBase orig) {
        mLegacyPermissionsState = new LegacyPermissionState();
        doCopy(orig);
    }

    public void copyFrom(SettingBase orig) {
        doCopy(orig);
    }

    private void doCopy(SettingBase orig) {
        pkgFlags = orig.pkgFlags;
        pkgPrivateFlags = orig.pkgPrivateFlags;
        mLegacyPermissionsState.copyFrom(orig.mLegacyPermissionsState);
        onChanged();
    }

    @Deprecated
    public LegacyPermissionState getLegacyPermissionState() {
        return mLegacyPermissionsState;
    }

    void setFlags(int pkgFlags) {
        this.pkgFlags = pkgFlags
                & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_EXTERNAL_STORAGE);
        onChanged();
    }

    void setPrivateFlags(int pkgPrivateFlags) {
        this.pkgPrivateFlags = pkgPrivateFlags
                & (ApplicationInfo.PRIVATE_FLAG_PRIVILEGED
                | ApplicationInfo.PRIVATE_FLAG_OEM
                | ApplicationInfo.PRIVATE_FLAG_VENDOR
                | ApplicationInfo.PRIVATE_FLAG_PRODUCT
                | ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT
                | ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER
                | ApplicationInfo.PRIVATE_FLAG_ODM);
        onChanged();
    }
}
