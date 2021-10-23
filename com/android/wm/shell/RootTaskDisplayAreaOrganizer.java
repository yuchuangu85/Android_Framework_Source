/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import android.annotation.UiContext;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Display area organizer for the root/default TaskDisplayAreas */
public class RootTaskDisplayAreaOrganizer extends DisplayAreaOrganizer {

    private static final String TAG = RootTaskDisplayAreaOrganizer.class.getSimpleName();

    /** {@link DisplayAreaInfo} list, which is mapped by display IDs. */
    private final SparseArray<DisplayAreaInfo> mDisplayAreasInfo = new SparseArray<>();
    /** Display area leashes, which is mapped by display IDs. */
    private final SparseArray<SurfaceControl> mLeashes = new SparseArray<>();

    private final SparseArray<ArrayList<RootTaskDisplayAreaListener>> mListeners =
            new SparseArray<>();
    /** {@link DisplayAreaContext} list, which is mapped by display IDs. */
    private final SparseArray<DisplayAreaContext> mDisplayAreaContexts = new SparseArray<>();

    private final Context mContext;

    public RootTaskDisplayAreaOrganizer(Executor executor, Context context) {
        super(executor);
        mContext = context;
        List<DisplayAreaAppearedInfo> infos = registerOrganizer(FEATURE_DEFAULT_TASK_CONTAINER);
        for (int i = infos.size() - 1; i >= 0; --i) {
            onDisplayAreaAppeared(infos.get(i).getDisplayAreaInfo(), infos.get(i).getLeash());
        }
    }

    public void registerListener(int displayId, RootTaskDisplayAreaListener listener) {
        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners == null) {
            listeners = new ArrayList<>();
            mListeners.put(displayId, listeners);
        }

        listeners.add(listener);

        final DisplayAreaInfo info = mDisplayAreasInfo.get(displayId);
        if (info != null) {
            listener.onDisplayAreaAppeared(info);
        }
    }

    public void unregisterListener(RootTaskDisplayAreaListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            final List<RootTaskDisplayAreaListener> listeners = mListeners.valueAt(i);
            if (listeners == null) continue;
            listeners.remove(listener);
        }
    }

    public void attachToDisplayArea(int displayId, SurfaceControl.Builder b) {
        final SurfaceControl sc = mLeashes.get(displayId);
        b.setParent(sc);
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        if (displayAreaInfo.featureId != FEATURE_DEFAULT_TASK_CONTAINER) {
            throw new IllegalArgumentException(
                    "Unknown feature: " + displayAreaInfo.featureId
                            + "displayAreaInfo:" + displayAreaInfo);
        }

        final int displayId = displayAreaInfo.displayId;
        if (mDisplayAreasInfo.get(displayId) != null) {
            throw new IllegalArgumentException(
                    "Duplicate DA for displayId: " + displayId
                            + " displayAreaInfo:" + displayAreaInfo
                            + " mDisplayAreasInfo.get():" + mDisplayAreasInfo.get(displayId));
        }

        mDisplayAreasInfo.put(displayId, displayAreaInfo);
        mLeashes.put(displayId, leash);

        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; --i) {
                listeners.get(i).onDisplayAreaAppeared(displayAreaInfo);
            }
        }
        applyConfigChangesToContext(displayAreaInfo);
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        final int displayId = displayAreaInfo.displayId;
        if (mDisplayAreasInfo.get(displayId) == null) {
            throw new IllegalArgumentException(
                    "onDisplayAreaVanished() Unknown DA displayId: " + displayId
                            + " displayAreaInfo:" + displayAreaInfo
                            + " mDisplayAreasInfo.get():" + mDisplayAreasInfo.get(displayId));
        }

        mDisplayAreasInfo.remove(displayId);

        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; --i) {
                listeners.get(i).onDisplayAreaVanished(displayAreaInfo);
            }
        }
        mDisplayAreaContexts.remove(displayId);
    }

    @Override
    public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
        final int displayId = displayAreaInfo.displayId;
        if (mDisplayAreasInfo.get(displayId) == null) {
            throw new IllegalArgumentException(
                    "onDisplayAreaInfoChanged() Unknown DA displayId: " + displayId
                            + " displayAreaInfo:" + displayAreaInfo
                            + " mDisplayAreasInfo.get():" + mDisplayAreasInfo.get(displayId));
        }

        mDisplayAreasInfo.put(displayId, displayAreaInfo);

        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; --i) {
                listeners.get(i).onDisplayAreaInfoChanged(displayAreaInfo);
            }
        }
        applyConfigChangesToContext(displayAreaInfo);
    }

    /**
     * Applies the {@link DisplayAreaInfo} to the {@link DisplayAreaContext} specified by
     * {@link DisplayAreaInfo#displayId}.
     */
    private void applyConfigChangesToContext(@NonNull DisplayAreaInfo displayAreaInfo) {
        final int displayId = displayAreaInfo.displayId;
        final Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(displayId);
        if (display == null) {
            ProtoLog.w(WM_SHELL_TASK_ORG, "The display#%d has been removed."
                    + " Skip following steps", displayId);
            return;
        }
        DisplayAreaContext daContext = mDisplayAreaContexts.get(displayId);
        if (daContext == null) {
            daContext = new DisplayAreaContext(mContext, display);
            mDisplayAreaContexts.put(displayId, daContext);
        }
        daContext.updateConfigurationChanges(displayAreaInfo.configuration);
    }

    /**
     * Returns the UI context associated with RootTaskDisplayArea specified by {@code displayId}.
     */
    @Nullable
    @UiContext
    public Context getContext(int displayId) {
        return mDisplayAreaContexts.get(displayId);
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
    }

    @Override
    public String toString() {
        return TAG + "#" + mDisplayAreasInfo.size();
    }

    /** Callbacks for when root task display areas change. */
    public interface RootTaskDisplayAreaListener {
        default void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo) {
        }

        default void onDisplayAreaVanished(DisplayAreaInfo displayAreaInfo) {
        }

        default void onDisplayAreaInfoChanged(DisplayAreaInfo displayAreaInfo) {
        }

        default void dump(@NonNull PrintWriter pw, String prefix) {
        }
    }

    /**
     * A UI context to associate with a {@link com.android.server.wm.DisplayArea}.
     *
     * This context receives configuration changes through {@link DisplayAreaOrganizer} callbacks
     * and the core implementation is {@link Context#createTokenContext(IBinder, Display)} to apply
     * the configuration updates to the {@link android.content.res.Resources}.
     */
    @UiContext
    public static class DisplayAreaContext extends ContextWrapper {
        private final IBinder mToken = new Binder();
        private final ResourcesManager mResourcesManager = ResourcesManager.getInstance();

        public DisplayAreaContext(@NonNull Context context, @NonNull Display display) {
            super(null);
            attachBaseContext(context.createTokenContext(mToken, display));
        }

        private void updateConfigurationChanges(@NonNull Configuration newConfig) {
            final Configuration config = getResources().getConfiguration();
            final boolean configChanged = config.diff(newConfig) != 0;
            if (configChanged) {
                mResourcesManager.updateResourcesForActivity(mToken, newConfig, getDisplayId());
            }
        }
    }
}