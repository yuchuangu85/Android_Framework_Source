/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a connection to a child-zygote process. A child-zygote is spawend from another
 * zygote process using {@link startChildZygote()}.
 *
 * @hide
 */
public class ChildZygoteProcess {
    /**
     * The underlying ZygoteProcess.
     */
    private final IZygoteProcess mZygoteProcess;

    /**
     * The PID of the child zygote process.
     */
    private final int mPid;

    /**
     * The UID of the child zygote process.
     */
    private final int mUid;


    /**
     * If this zygote process was dead;
     */
    private AtomicBoolean mDead;

    private ChildZygoteProcess(IZygoteProcess zygoteProcess, int pid, int uid) {
        mPid = pid;
        mUid = uid;
        mDead = new AtomicBoolean(false);
        mZygoteProcess = zygoteProcess;
    }

    /**
     * Create the managed variant of ChildZygoteProcess
     */
    public static ChildZygoteProcess createManagedChildZygoteProcess(
            LocalSocketAddress socketAddress, int pid, int uid) {
        return new ChildZygoteProcess(new ZygoteProcess(socketAddress, null), pid, uid);
    }

    /**
     * Create the native variant of ChildZygoteProcess
     */
    public static ChildZygoteProcess createNativeChildZygoteProcess(
            LocalSocketAddress socketAddress, int pid, int uid) {
        return new ChildZygoteProcess(new NativeZygoteProcess(socketAddress), pid, uid);
    }

    /**
     * Return the underlying IZygoteProcess.
     */
    public IZygoteProcess getZygoteProcess() {
        return mZygoteProcess;
    }

    /**
     * Return the underlying ZygoteProcess. {@code ClassCastException} will be thrown if
     * {@code mZygoteProcess} is not a ZygoteProcess.
     */
    public ZygoteProcess getZygoteProcessAsManaged() {
        return (ZygoteProcess) mZygoteProcess;
    }

    /**
     * Returns the PID of the child-zygote process.
     */
    public int getPid() {
        return mPid;
    }

    /**
     * Check if child-zygote process is dead
     */
    public boolean isDead() {
        if (mDead.get()) {
            return true;
        }
        StrictMode.ThreadPolicy oldStrictModeThreadPolicy = StrictMode.allowThreadDiskReads();
        try {
            if (Os.stat("/proc/" + mPid).st_uid == mUid) {
                return false;
            }
        } catch (ErrnoException e) {
            // Do nothing, it's dead.
        } finally {
            StrictMode.setThreadPolicy(oldStrictModeThreadPolicy);
        }
        mDead.set(true);
        return true;
    }
}
