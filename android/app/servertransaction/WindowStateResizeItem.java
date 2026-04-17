/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.IWindow;
import android.view.InsetsState;
import android.view.WindowRelayoutResult;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;

import com.android.internal.view.WindowClientTransactionHandler;

import java.util.Objects;

/**
 * Message to deliver window resize info.
 *
 * @hide
 */
public class WindowStateResizeItem extends WindowStateTransactionItem {

    private static final String TAG = "WindowStateResizeItem";

    @NonNull
    private final WindowRelayoutResult mLayout = new WindowRelayoutResult(new ClientWindowFrames(),
            new MergedConfiguration(), new InsetsState(), null /* insetControls */);

    private final boolean mReportDraw;
    private final boolean mForceLayout;
    private final int mDisplayId;
    private final boolean mSyncWithBuffers;
    private final boolean mDragResizing;

    public WindowStateResizeItem(@NonNull IWindow window, @NonNull ClientWindowFrames frames,
            boolean reportDraw, @NonNull MergedConfiguration configuration,
            @NonNull InsetsState insetsState, boolean forceLayout, int displayId, int syncSeqId,
            boolean syncWithBuffers, boolean dragResizing,
            @Nullable ActivityWindowInfo activityWindowInfo) {
        super(window);
        mLayout.frames.setTo(frames);
        mLayout.mergedConfiguration.setTo(configuration);
        mLayout.insetsState.set(insetsState, true /* copySources */);
        if (activityWindowInfo != null) {
            mLayout.activityWindowInfo = new ActivityWindowInfo(activityWindowInfo);
        } else {
            mLayout.activityWindowInfo = null;
        }
        mReportDraw = reportDraw;
        mForceLayout = forceLayout;
        mDisplayId = displayId;
        mLayout.syncSeqId = syncSeqId;
        mSyncWithBuffers = syncWithBuffers;
        mDragResizing = dragResizing;
    }

    @Override
    public void preExecute(@NonNull WindowClientTransactionHandler client) {
        client.updatePendingResize(mLayout, mReportDraw, mForceLayout, mDisplayId, mSyncWithBuffers,
                mDragResizing);
    }

    @Override
    public void execute(@NonNull WindowClientTransactionHandler client) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "windowResized");
        client.handleResized();
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull IWindow window,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "windowResized");
        try {
            window.resized(mLayout, mReportDraw, mForceLayout, mDisplayId, mSyncWithBuffers,
                    mDragResizing);
        } catch (RemoteException e) {
            // Should be a local call.
            // An exception could happen if the process is restarted. It is safe to ignore since
            // the window should no longer exist.
            Log.w(TAG, "The original window no longer exists in the new process", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    public boolean getSyncWithBuffersForTest() {
        return mSyncWithBuffers;
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        mLayout.writeToParcel(dest, flags);
        dest.writeBoolean(mReportDraw);
        dest.writeBoolean(mForceLayout);
        dest.writeInt(mDisplayId);
        dest.writeBoolean(mSyncWithBuffers);
        dest.writeBoolean(mDragResizing);
    }

    /** Reads from Parcel. */
    private WindowStateResizeItem(@NonNull Parcel in) {
        super(in);
        mLayout.readFromParcel(in);
        mReportDraw = in.readBoolean();
        mForceLayout = in.readBoolean();
        mDisplayId = in.readInt();
        mSyncWithBuffers = in.readBoolean();
        mDragResizing = in.readBoolean();
    }

    public static final @NonNull Creator<WindowStateResizeItem> CREATOR = new Creator<>() {
        public WindowStateResizeItem createFromParcel(@NonNull Parcel in) {
            return new WindowStateResizeItem(in);
        }

        public WindowStateResizeItem[] newArray(int size) {
            return new WindowStateResizeItem[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final WindowStateResizeItem other = (WindowStateResizeItem) o;
        return Objects.equals(mLayout.frames, other.mLayout.frames)
                && Objects.equals(mLayout.mergedConfiguration, other.mLayout.mergedConfiguration)
                && Objects.equals(mLayout.insetsState, other.mLayout.insetsState)
                && mReportDraw == other.mReportDraw
                && mForceLayout == other.mForceLayout
                && mDisplayId == other.mDisplayId
                && mLayout.syncSeqId == other.mLayout.syncSeqId
                && mSyncWithBuffers == other.mSyncWithBuffers
                && mDragResizing == other.mDragResizing
                && Objects.equals(mLayout.activityWindowInfo, other.mLayout.activityWindowInfo);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mLayout.frames);
        result = 31 * result + Objects.hashCode(mLayout.mergedConfiguration);
        result = 31 * result + Objects.hashCode(mLayout.insetsState);
        result = 31 * result + (mReportDraw ? 1 : 0);
        result = 31 * result + (mForceLayout ? 1 : 0);
        result = 31 * result + mDisplayId;
        result = 31 * result + mLayout.syncSeqId;
        result = 31 * result + (mSyncWithBuffers ? 1 : 0);
        result = 31 * result + (mDragResizing ? 1 : 0);
        result = 31 * result + Objects.hashCode(mLayout.activityWindowInfo);
        return result;
    }

    @Override
    public String toString() {
        return "WindowStateResizeItem{" + super.toString()
                + ", reportDrawn=" + mReportDraw
                + ", syncSeqId=" + mLayout.syncSeqId + (mSyncWithBuffers ? "+buf" : "")
                + ", configuration=" + mLayout.mergedConfiguration
                + ", activityWindowInfo=" + mLayout.activityWindowInfo
                + "}";
    }
}
