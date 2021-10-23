/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wm.shell.common;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IDisplayWindowListener;
import android.view.IWindowManager;

import androidx.annotation.BinderThread;

import com.android.wm.shell.common.DisplayChangeController.OnDisplayChangingListener;
import com.android.wm.shell.common.annotations.ShellMainThread;

import java.util.ArrayList;

/**
 * This module deals with display rotations coming from WM. When WM starts a rotation: after it has
 * frozen the screen, it will call into this class. This will then call all registered local
 * controllers and give them a chance to queue up task changes to be applied synchronously with that
 * rotation.
 */
public class DisplayController {
    private static final String TAG = "DisplayController";

    private final ShellExecutor mMainExecutor;
    private final Context mContext;
    private final IWindowManager mWmService;
    private final DisplayChangeController mChangeController;
    private final IDisplayWindowListener mDisplayContainerListener;

    private final SparseArray<DisplayRecord> mDisplays = new SparseArray<>();
    private final ArrayList<OnDisplaysChangedListener> mDisplayChangedListeners = new ArrayList<>();

    /**
     * Gets a display by id from DisplayManager.
     */
    public Display getDisplay(int displayId) {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        return displayManager.getDisplay(displayId);
    }

    public DisplayController(Context context, IWindowManager wmService,
            ShellExecutor mainExecutor) {
        mMainExecutor = mainExecutor;
        mContext = context;
        mWmService = wmService;
        mChangeController = new DisplayChangeController(mWmService, mainExecutor);
        mDisplayContainerListener = new DisplayWindowListenerImpl();
        try {
            mWmService.registerDisplayWindowListener(mDisplayContainerListener);
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to register hierarchy listener");
        }
    }

    /**
     * Gets the DisplayLayout associated with a display.
     */
    public @Nullable DisplayLayout getDisplayLayout(int displayId) {
        final DisplayRecord r = mDisplays.get(displayId);
        return r != null ? r.mDisplayLayout : null;
    }

    /**
     * Gets a display-specific context for a display.
     */
    public @Nullable Context getDisplayContext(int displayId) {
        final DisplayRecord r = mDisplays.get(displayId);
        return r != null ? r.mContext : null;
    }

    /**
     * Add a display window-container listener. It will get notified whenever a display's
     * configuration changes or when displays are added/removed from the WM hierarchy.
     */
    public void addDisplayWindowListener(OnDisplaysChangedListener listener) {
        synchronized (mDisplays) {
            if (mDisplayChangedListeners.contains(listener)) {
                return;
            }
            mDisplayChangedListeners.add(listener);
            for (int i = 0; i < mDisplays.size(); ++i) {
                listener.onDisplayAdded(mDisplays.keyAt(i));
            }
        }
    }

    /**
     * Remove a display window-container listener.
     */
    public void removeDisplayWindowListener(OnDisplaysChangedListener listener) {
        synchronized (mDisplays) {
            mDisplayChangedListeners.remove(listener);
        }
    }

    /**
     * Adds a display rotation controller.
     */
    public void addDisplayChangingController(OnDisplayChangingListener controller) {
        mChangeController.addRotationListener(controller);
    }

    /**
     * Removes a display rotation controller.
     */
    public void removeDisplayChangingController(OnDisplayChangingListener controller) {
        mChangeController.removeRotationListener(controller);
    }

    private void onDisplayAdded(int displayId) {
        synchronized (mDisplays) {
            if (mDisplays.get(displayId) != null) {
                return;
            }
            Display display = getDisplay(displayId);
            if (display == null) {
                // It's likely that the display is private to some app and thus not
                // accessible by system-ui.
                return;
            }
            DisplayRecord record = new DisplayRecord();
            record.mDisplayId = displayId;
            record.mContext = (displayId == Display.DEFAULT_DISPLAY) ? mContext
                    : mContext.createDisplayContext(display);
            record.mDisplayLayout = new DisplayLayout(record.mContext, display);
            mDisplays.put(displayId, record);
            for (int i = 0; i < mDisplayChangedListeners.size(); ++i) {
                mDisplayChangedListeners.get(i).onDisplayAdded(displayId);
            }
        }
    }

    private void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        synchronized (mDisplays) {
            DisplayRecord dr = mDisplays.get(displayId);
            if (dr == null) {
                Slog.w(TAG, "Skipping Display Configuration change on non-added"
                        + " display.");
                return;
            }
            Display display = getDisplay(displayId);
            if (display == null) {
                Slog.w(TAG, "Skipping Display Configuration change on invalid"
                        + " display. It may have been removed.");
                return;
            }
            Context perDisplayContext = mContext;
            if (displayId != Display.DEFAULT_DISPLAY) {
                perDisplayContext = mContext.createDisplayContext(display);
            }
            dr.mContext = perDisplayContext.createConfigurationContext(newConfig);
            dr.mDisplayLayout = new DisplayLayout(dr.mContext, display);
            for (int i = 0; i < mDisplayChangedListeners.size(); ++i) {
                mDisplayChangedListeners.get(i).onDisplayConfigurationChanged(
                        displayId, newConfig);
            }
        }
    }

    private void onDisplayRemoved(int displayId) {
        synchronized (mDisplays) {
            if (mDisplays.get(displayId) == null) {
                return;
            }
            for (int i = mDisplayChangedListeners.size() - 1; i >= 0; --i) {
                mDisplayChangedListeners.get(i).onDisplayRemoved(displayId);
            }
            mDisplays.remove(displayId);
        }
    }

    private void onFixedRotationStarted(int displayId, int newRotation) {
        synchronized (mDisplays) {
            if (mDisplays.get(displayId) == null || getDisplay(displayId) == null) {
                Slog.w(TAG, "Skipping onFixedRotationStarted on unknown"
                        + " display, displayId=" + displayId);
                return;
            }
            for (int i = mDisplayChangedListeners.size() - 1; i >= 0; --i) {
                mDisplayChangedListeners.get(i).onFixedRotationStarted(
                        displayId, newRotation);
            }
        }
    }

    private void onFixedRotationFinished(int displayId) {
        synchronized (mDisplays) {
            if (mDisplays.get(displayId) == null || getDisplay(displayId) == null) {
                Slog.w(TAG, "Skipping onFixedRotationFinished on unknown"
                        + " display, displayId=" + displayId);
                return;
            }
            for (int i = mDisplayChangedListeners.size() - 1; i >= 0; --i) {
                mDisplayChangedListeners.get(i).onFixedRotationFinished(displayId);
            }
        }
    }

    private static class DisplayRecord {
        int mDisplayId;
        Context mContext;
        DisplayLayout mDisplayLayout;
    }

    @BinderThread
    private class DisplayWindowListenerImpl extends IDisplayWindowListener.Stub {
        @Override
        public void onDisplayAdded(int displayId) {
            mMainExecutor.execute(() -> {
                DisplayController.this.onDisplayAdded(displayId);
            });
        }

        @Override
        public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
            mMainExecutor.execute(() -> {
                DisplayController.this.onDisplayConfigurationChanged(displayId, newConfig);
            });
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            mMainExecutor.execute(() -> {
                DisplayController.this.onDisplayRemoved(displayId);
            });
        }

        @Override
        public void onFixedRotationStarted(int displayId, int newRotation) {
            mMainExecutor.execute(() -> {
                DisplayController.this.onFixedRotationStarted(displayId, newRotation);
            });
        }

        @Override
        public void onFixedRotationFinished(int displayId) {
            mMainExecutor.execute(() -> {
                DisplayController.this.onFixedRotationFinished(displayId);
            });
        }
    }

    /**
     * Gets notified when a display is added/removed to the WM hierarchy and when a display's
     * window-configuration changes.
     *
     * @see IDisplayWindowListener
     */
    @ShellMainThread
    public interface OnDisplaysChangedListener {
        /**
         * Called when a display has been added to the WM hierarchy.
         */
        default void onDisplayAdded(int displayId) {}

        /**
         * Called when a display's window-container configuration changes.
         */
        default void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {}

        /**
         * Called when a display is removed.
         */
        default void onDisplayRemoved(int displayId) {}

        /**
         * Called when fixed rotation on a display is started.
         */
        default void onFixedRotationStarted(int displayId, int newRotation) {}

        /**
         * Called when fixed rotation on a display is finished.
         */
        default void onFixedRotationFinished(int displayId) {}
    }
}
