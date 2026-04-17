/*
 * Copyright 2024 The Android Open Source Project
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


import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import static java.util.Objects.requireNonNull;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.os.Handler;
import android.os.Parcel;
import android.view.IWindow;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.view.WindowClientTransactionHandler;

import java.util.Objects;

/**
 * {@link ClientTransactionItem} to report changes to a window.
 *
 * @hide
 */
public abstract class WindowStateTransactionItem extends ClientTransactionItem {

    /** Target window. */
    @NonNull
    private final IWindow mWindow;

    public WindowStateTransactionItem(@NonNull IWindow window) {
        mWindow = requireNonNull(window);
    }

    @NonNull
    private WindowClientTransactionHandler getClient() {
        if (mWindow instanceof WindowClientTransactionHandler client) {
            return client;
        }
        throw new ClassCastException(
                "Client window must implement " + WindowClientTransactionHandler.class.getName());
    }

    @Override
    public final void preExecute(@NonNull ClientTransactionHandler appClient) {
        if (!com.android.window.flags.Flags.improveFluidResizingPerformance()) {
            return;
        }
        preExecute(getClient());
    }

    @Override
    public final void execute(@NonNull ClientTransactionHandler appClient,
            @NonNull PendingTransactionActions pendingActions) {
        if (!com.android.window.flags.Flags.improveFluidResizingPerformance()) {
            if (mWindow instanceof WindowClientTransactionHandler listener) {
                listener.onExecutingWindowStateTransactionItem();
            }
            execute(appClient, mWindow, pendingActions);
            return;
        }
        final WindowClientTransactionHandler client = getClient();
        final Handler handler = client.getHandler();
        if (handler != null) {
            if (handler.getLooper() == ActivityThread.currentActivityThread().getLooper()) {
                execute(client);
            } else {
                handler.post(() -> execute(client));
            }
        }
    }

    /**
     * Like {@link #preExecute(ClientTransactionHandler)},
     * but take non-null {@link WindowClientTransactionHandler} as a parameter.
     */
    public void preExecute(@NonNull WindowClientTransactionHandler client) {
    }

    /**
     * Like {@link #execute(ClientTransactionHandler, PendingTransactionActions)},
     * but take non-null {@link WindowClientTransactionHandler} as the only parameter.
     * This must be called from the thread used by the {@code client}.
     */
    public abstract void execute(@NonNull WindowClientTransactionHandler client);

    /**
     * Like {@link #execute(ClientTransactionHandler, PendingTransactionActions)},
     * but take non-null {@link IWindow} as a parameter.
     * @deprecated Use {@link #execute(WindowClientTransactionHandler)} instead.
     */
    @Deprecated
    @VisibleForTesting(visibility = PACKAGE)
    public abstract void execute(@NonNull ClientTransactionHandler appClient,
            @NonNull IWindow window, @NonNull PendingTransactionActions pendingActions);

    // Parcelable implementation

    /** Writes to Parcel. */
    @CallSuper
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mWindow.asBinder());
    }

    /** Reads from Parcel. */
    WindowStateTransactionItem(@NonNull Parcel in) {
        mWindow = requireNonNull(IWindow.Stub.asInterface(in.readStrongBinder()));
    }

    // Subclass must override and call super.equals to compare the mActivityToken.
    @SuppressWarnings("EqualsGetClass")
    @CallSuper
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WindowStateTransactionItem other = (WindowStateTransactionItem) o;
        return Objects.equals(mWindow, other.mWindow);
    }

    @CallSuper
    @Override
    public int hashCode() {
        return Objects.hashCode(mWindow);
    }

    @CallSuper
    @Override
    public String toString() {
        return "mWindow=" + mWindow;
    }
}
