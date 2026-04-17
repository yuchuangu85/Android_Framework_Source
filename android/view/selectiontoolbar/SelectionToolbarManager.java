/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.view.selectiontoolbar;

import static android.permission.flags.Flags.systemSelectionToolbarEnabled;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import java.util.Objects;

/**
 * The {@link SelectionToolbarManager} class provides ways for apps to control the
 * selection toolbar.
 *
 * @hide
 */
@SystemService(Context.SELECTION_TOOLBAR_SERVICE)
public final class SelectionToolbarManager {

    private final ISelectionToolbarManager mService;

    public SelectionToolbarManager(@NonNull ISelectionToolbarManager service) {
        mService = service;
    }

    /**
     * Request to show selection toolbar for a given View.
     */
    public void showToolbar(@NonNull ShowInfo showInfo,
            @NonNull ISelectionToolbarCallback callback) {
        try {
            Objects.requireNonNull(showInfo);
            Objects.requireNonNull(callback);
            mService.showToolbar(showInfo, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to hide selection toolbar for the current UID.
     */
    public void hideToolbar() {
        try {
            mService.hideToolbar();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Dismiss to dismiss selection toolbar for the current UID.
     */
    public void dismissToolbar() {
        try {
            mService.dismissToolbar();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean isRemoteSelectionToolbarEnabled() {
        return systemSelectionToolbarEnabled();
    }

    /**
     * Returns {@code true} if remote render selection toolbar enabled, otherwise
     * returns {@code false}.
     */
    public static boolean isRemoteSelectionToolbarEnabled(Context context) {
        SelectionToolbarManager manager = context.getSystemService(SelectionToolbarManager.class);
        if (manager != null) {
            return manager.isRemoteSelectionToolbarEnabled();
        }
        return false;
    }
}
