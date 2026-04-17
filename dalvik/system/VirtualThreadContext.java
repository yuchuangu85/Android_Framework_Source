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
package dalvik.system;

import android.system.SystemCleaner;

import java.lang.ref.Cleaner;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jdk.internal.misc.Unsafe;

/**
 * {@link Thread} holds this object to indicate that the thread is running a virtual
 * thread.
 *
 * @hide
 */
public final class VirtualThreadContext implements Runnable {

    private final static Unsafe UNSAFE = Unsafe.getUnsafe();

    /**
     * Currently, id is only used for debugging purpose, and the carrier thread name.
     * When {@link Thread} represents a virtual thread in a future implementation,
     * this id can be accessed via {@link Thread#threadId()}.
     */
    public final long id;

    public final int monitorThreadId;

    private final Cleaner.Cleanable monitorThreadIdCleanable;

    /**
     * The name of the carrier thread. The name is cached here and re-used for all carrier threads.
     */
    public final String carrierName;
    /**
     * The object whose run() method gets called
     */
    public final Runnable target;
    /**
     * parkedStates stores the stack frames when a virtual thread is parked.
     * For simplicity, other platform threads read this field to determine if a virtual thread
     * is parked or unparked, and thus we use volatile to ensure the memory order here.
     */
    public volatile VirtualThreadParkedStates parkedStates;

    /**
     * If the virtual thread is parked and pinned, this field references the carrier thread object.
     * For simplicity, other platform thread reads this field to determine if a virtual thread
     * is pinned or not, and thus we use volatile to ensure the memory order here.
     * A single state machine will likely be used instead in the future implementation to keep track
     * the status of a virtual thread.
     */
    public volatile Thread pinnedCarrierThread;

    public VirtualThreadContext(Runnable target, long id) {
        Objects.requireNonNull(target);
        this.id = id;
        this.target = target;
        this.carrierName = "VirtualThread-" + id;
        // A thin lock id is needed for synchronizing a virtual thread.
        int lockId = Thread.acquireThinLockId();
        this.monitorThreadId = lockId;
        // TODO(http://b/460438903): If Virtual Threads are GC roots, we can simply
        // release the thin lock id when a virtual thread terminates, rather than using
        // system cleaner to release.
        this.monitorThreadIdCleanable = SystemCleaner.cleaner().register(this,
                () -> Thread.releaseThinLockId(lockId));
    }

    @Override
    public void run() {
        target.run();
    }

    public boolean isParked() {
        return parkedStates != null || pinnedCarrierThread != null;
    }

    public boolean isPinned() {
        return pinnedCarrierThread != null;
    }

    /**
     * @return true if the thread started, and is parked, and unmounted, i.e. not pinned to a
     *   carrier thread.
     */
    public boolean isUnmounted() {
        return parkedStates != null;
    }


    public void releaseThinLockId() {
        monitorThreadIdCleanable.clean();
    }

    public void parkOnCarrierThreadIfPinned() {
        if (!isPinned()) {
            return;
        }

        UNSAFE.park(false, 0L);
    }

    public Thread unparkOnCarrierThread() {
        Thread t = pinnedCarrierThread;
        pinnedCarrierThread = null;
        UNSAFE.unpark(t);
        return t;
    }
}
