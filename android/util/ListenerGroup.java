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

package android.util;

import android.annotation.NonNull;
import android.os.Handler;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A utility class to manage a list of {@link Consumer} and {@link Executor} pairs. This class
 * is thread safe because all the effects are dispatched through the handler.
 * @param <T> the type of the value to be reported.
 * @hide
 */
@RavenwoodKeepWholeClass
public class ListenerGroup<T> {

    /**
     * The set of listeners to be managed. All modifications should be done on {@link #mHandler}.
     */
    private final ArrayMap<Consumer<T>, Executor> mListeners =
            new ArrayMap<>();
    @NonNull
    private T mLastValue;
    @NonNull
    private final Handler mHandler;

    /**
     * Constructs a {@link ListenerGroup} that will replay the last reported value whenever a new
     * listener is registered.
     * @param value the initial value
     * @param handler a handler to synchronize access to shared resources.
     */
    public ListenerGroup(@NonNull T value, @NonNull Handler handler) {
        mLastValue = Objects.requireNonNull(value);
        mHandler = Objects.requireNonNull(handler);
    }

    /**
     * Relays the value to all the registered {@link java.util.function.Consumer}. The relay is
     * initiated on the {@link Handler} provided in the constructor and then switched to the
     * {@link Executor} that was registered with the {@link Consumer}.
     */
    public void accept(@NonNull T value) {
        Objects.requireNonNull(value);
        mHandler.post(() -> {
            mLastValue = value;
            for (int i = 0; i < mListeners.size(); i++) {
                final Consumer<T> consumer = mListeners.keyAt(i);
                final Executor executor = mListeners.get(consumer);
                executor.execute(() -> consumer.accept(value));
            }
        });
    }

    /**
     * Adds a {@link Consumer} to the group and replays the last reported value. The replay is
     * initiated from the {@link Handler} provided in the constructor and run on the
     * {@link Executor}. If the {@link Consumer} is already present then this is a no op.
     */
    public void addListener(@NonNull Executor executor, @NonNull Consumer<T> consumer) {
        Objects.requireNonNull(executor, "Executor must not be null.");
        Objects.requireNonNull(consumer, "Consumer must not be null.");
        mHandler.post(() -> {
            if (mListeners.containsKey(consumer)) {
                return;
            }
            mListeners.put(consumer, executor);
            executor.execute(() -> consumer.accept(mLastValue));
        });
    }

    /**
     * Removes a {@link Consumer} from the group. If the {@link Consumer} was not present then this
     * is a no op.
     */
    public void removeListener(@NonNull Consumer<T> consumer) {
        mHandler.post(() -> {
            mListeners.remove(consumer);
        });
    }
}
