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

package com.android.wm.shell.transition;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;

/**
 * Handler that deals with RemoteTransitions. It will only request to handle a transition
 * if the request includes a specific remote.
 */
public class RemoteTransitionHandler implements Transitions.TransitionHandler {
    private static final String TAG = "RemoteTransitionHandler";

    private final ShellExecutor mMainExecutor;

    /** Includes remotes explicitly requested by, eg, ActivityOptions */
    private final ArrayMap<IBinder, IRemoteTransition> mRequestedRemotes = new ArrayMap<>();

    /** Ordered by specificity. Last filters will be checked first */
    private final ArrayList<Pair<TransitionFilter, IRemoteTransition>> mFilters =
            new ArrayList<>();

    private final IBinder.DeathRecipient mTransitionDeathRecipient =
            new IBinder.DeathRecipient() {
                @Override
                @BinderThread
                public void binderDied() {
                    mMainExecutor.execute(() -> mFilters.clear());
                }
            };

    RemoteTransitionHandler(@NonNull ShellExecutor mainExecutor) {
        mMainExecutor = mainExecutor;
    }

    void addFiltered(TransitionFilter filter, IRemoteTransition remote) {
        try {
            remote.asBinder().linkToDeath(mTransitionDeathRecipient, 0 /* flags */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to link to death");
            return;
        }
        mFilters.add(new Pair<>(filter, remote));
    }

    void removeFiltered(IRemoteTransition remote) {
        boolean removed = false;
        for (int i = mFilters.size() - 1; i >= 0; --i) {
            if (mFilters.get(i).second == remote) {
                mFilters.remove(i);
                removed = true;
            }
        }
        if (removed) {
            remote.asBinder().unlinkToDeath(mTransitionDeathRecipient, 0 /* flags */);
        }
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder transition) {
        mRequestedRemotes.remove(transition);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        IRemoteTransition pendingRemote = mRequestedRemotes.get(transition);
        if (pendingRemote == null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition %s doesn't have "
                    + "explicit remote, search filters for match for %s", transition, info);
            // If no explicit remote, search filters until one matches
            for (int i = mFilters.size() - 1; i >= 0; --i) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Checking filter %s",
                        mFilters.get(i));
                if (mFilters.get(i).first.matches(info)) {
                    pendingRemote = mFilters.get(i).second;
                    // Add to requested list so that it can be found for merge requests.
                    mRequestedRemotes.put(transition, pendingRemote);
                    break;
                }
            }
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Delegate animation for %s to %s",
                transition, pendingRemote);

        if (pendingRemote == null) return false;

        final IRemoteTransition remote = pendingRemote;
        final IBinder.DeathRecipient remoteDied = () -> {
            Log.e(Transitions.TAG, "Remote transition died, finishing");
            mMainExecutor.execute(() -> {
                mRequestedRemotes.remove(transition);
                finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
            });
        };
        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct) {
                if (remote.asBinder() != null) {
                    remote.asBinder().unlinkToDeath(remoteDied, 0 /* flags */);
                }
                mMainExecutor.execute(() -> {
                    mRequestedRemotes.remove(transition);
                    finishCallback.onTransitionFinished(wct, null /* wctCB */);
                });
            }
        };
        try {
            if (remote.asBinder() != null) {
                remote.asBinder().linkToDeath(remoteDied, 0 /* flags */);
            }
            remote.startAnimation(transition, info, t, cb);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error running remote transition.", e);
            if (remote.asBinder() != null) {
                remote.asBinder().unlinkToDeath(remoteDied, 0 /* flags */);
            }
            mRequestedRemotes.remove(transition);
            mMainExecutor.execute(
                    () -> finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */));
        }
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final IRemoteTransition remote = mRequestedRemotes.get(mergeTarget);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Attempt merge %s into %s",
                transition, remote);
        if (remote == null) return;

        IRemoteTransitionFinishedCallback cb = new IRemoteTransitionFinishedCallback.Stub() {
            @Override
            public void onTransitionFinished(WindowContainerTransaction wct) {
                mMainExecutor.execute(() -> {
                    if (!mRequestedRemotes.containsKey(mergeTarget)) {
                        Log.e(TAG, "Merged transition finished after it's mergeTarget (the "
                                + "transition it was supposed to merge into). This usually means "
                                + "that the mergeTarget's RemoteTransition impl erroneously "
                                + "accepted/ran the merge request after finishing the mergeTarget");
                    }
                    finishCallback.onTransitionFinished(wct, null /* wctCB */);
                });
            }
        };
        try {
            remote.mergeAnimation(transition, info, t, mergeTarget, cb);
        } catch (RemoteException e) {
            Log.e(Transitions.TAG, "Error attempting to merge remote transition.", e);
        }
    }

    @Override
    @Nullable
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        IRemoteTransition remote = request.getRemoteTransition();
        if (remote == null) return null;
        mRequestedRemotes.put(transition, remote);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "RemoteTransition directly requested"
                + " for %s: %s", transition, remote);
        return new WindowContainerTransaction();
    }
}
