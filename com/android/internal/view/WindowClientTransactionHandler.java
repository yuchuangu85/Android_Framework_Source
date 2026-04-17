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

package com.android.internal.view;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.RemoteException;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.WindowRelayoutResult;

/**
 * Defines operations that a {@link android.app.servertransaction.WindowStateTransactionItem}
 * can perform on the client window.
 * @see android.app.ClientTransactionHandler
 */
public abstract class WindowClientTransactionHandler extends IWindow.Stub {

    /** Returns the {@link Handler} used this handler. */
    @Nullable
    public Handler getHandler() {
        return null;
    }

    /**
     * Notifies that the transaction item is going to be executed.
     * @deprecated This won't be called if improve_fluid_resizing_performance is enabled.
     */
    @Deprecated
    public void onExecutingWindowStateTransactionItem() {
    }

    /**
     * Sets the pending resize parameters in case they will be updated by other transaction item.
     */
    public void updatePendingResize(WindowRelayoutResult layout, boolean reportDraw,
            boolean forceLayout, int displayId, boolean syncWithBuffers, boolean dragResizing) {
        try {
            resized(layout, reportDraw, forceLayout, displayId, syncWithBuffers, dragResizing);
        } catch (RemoteException e) {
            // Shouldn't happen since it is not an IPC call.
            throw new RuntimeException(e);
        }
    }

    /**
     * Handles the resize parameters in the UI thread used by this handler.
     */
    public void handleResized() {
    }

    /**
     * Sets pending insets source controls in case they will be updated by other transaction item.
     */
    public void updatePendingInsetsControls(InsetsState state, InsetsSourceControl.Array controls) {
        try {
            insetsControlChanged(state, controls);
        } catch (RemoteException e) {
            // Shouldn't happen since it is not an IPC call.
            throw new RuntimeException(e);
        }
    }

    /** Handles the insets source controls in the UI thread used by this handler. */
    public void handleInsetsControlChanged() {
    }
}
