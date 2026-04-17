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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocketAddress;
import android.util.Pair;

import java.io.IOException;
import java.util.Map;

/** @hide */
interface IZygoteProcess {
    /**
     * Start a new process.
     *
     * <p>A new process or a thread is created, depending on whether processes are enabled.
     *
     * <p>The niceName parameter, if not an empty string, is a custom name to
     * give to the process instead of using processClass.  This allows you to
     * make easily identifyable processes even if you are using the same base
     * <var>processClass</var> to start them.
     *
     * When invokeWith is not null, the process will be started as a fresh app
     * and not a zygote fork. Note that this is only allowed for uid 0 or when
     * runtimeFlags contains DEBUG_ENABLE_DEBUGGER.
     *
     * @param processClass The class to use as the process's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the process will run.
     * @param gid The group-id under which the process will run.
     * @param gids Additional group-ids associated with the process.
     * @param runtimeFlags Additional flags.
     * @param targetSdkVersion The target SDK version for the app.
     * @param seInfo null-ok SELinux information for the new process.
     * @param abi non-null the ABI this app should be started with.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param invokeWith null-ok the command to invoke with.
     * @param packageName null-ok the name of the package this process belongs to.
     * @param zygotePolicyFlags Flags used to determine how to launch the application.
     * @param isTopApp Whether the process starts for high priority application.
     * @param disabledCompatChanges null-ok list of disabled compat changes for the process being
     *                             started.
     * @param pkgDataInfoMap Map from related package names to private data directory
     *                       volume UUID and inode number.
     * @param allowlistedDataInfoList Map from allowlisted package names to private data directory
     *                       volume UUID and inode number.
     * @param bindMountAppsData whether zygote needs to mount CE and DE data.
     * @param bindMountAppStorageDirs whether zygote needs to mount Android/obb and Android/data.
     *
     * @param zygoteArgs Additional arguments to supply to the Zygote process.
     * @return An object that describes the result of the attempt to start the process.
     * @throws RuntimeException on fatal start failure
     */
    Process.ProcessStartResult start(@NonNull String processClass,
                                     String niceName,
                                     int uid, int gid, @Nullable int[] gids,
                                     int runtimeFlags, int mountExternal,
                                     int targetSdkVersion,
                                     @Nullable String seInfo,
                                     @NonNull String abi,
                                     @Nullable String instructionSet,
                                     @Nullable String appDataDir,
                                     @Nullable String invokeWith,
                                     @Nullable String packageName,
                                     int zygotePolicyFlags,
                                     boolean isTopApp,
                                     @Nullable long[] disabledCompatChanges,
                                     @Nullable long[] enabledCompatChanges,
                                     boolean useDeliQueue,
                                     @Nullable Map<String, Pair<String, Long>>
                                             pkgDataInfoMap,
                                     @Nullable Map<String, Pair<String, Long>>
                                             allowlistedDataInfoList,
                                     boolean bindMountAppsData,
                                     boolean bindMountAppStorageDirs,
                                     boolean bindOverrideSysprops,
                                     long startSeq,
                                     @Nullable String[] zygoteArgs);

    /**
     * Starts a new zygote process as a child of this zygote. This is used to create
     * secondary zygotes that inherit data from the zygote that this object
     * communicates with. This returns a new ZygoteProcess representing a connection
     * to the newly created zygote. Throws an exception if the zygote cannot be started.
     *
     * @param processClass The class to use as the child zygote's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the child zygote will run.
     * @param gid The group-id under which the child zygote will run.
     * @param gids Additional group-ids associated with the child zygote process.
     * @param runtimeFlags Additional flags.
     * @param seInfo null-ok SELinux information for the child zygote process.
     * @param abi non-null the ABI of the child zygote
     * @param acceptedAbiList ABIs this child zygote will accept connections for; this
     *                        may be different from <code>abi</code> in case the children
     *                        spawned from this Zygote only communicate using ABI-safe methods.
     * @param instructionSet null-ok the instruction set to use.
     * @param uidRangeStart The first UID in the range the child zygote may setuid()/setgid() to
     * @param uidRangeEnd The last UID in the range the child zygote may setuid()/setgid() to
     * @param appInfo The ApplicationInfo used to derive the linker namespace parameters, the target
     *                SDK version, and zygotePreloadNativeLib/zygotePreloadNativeFunc from.
     */
    ChildZygoteProcess startChildZygote(String processClass,
                                        String niceName,
                                        int uid, int gid, int[] gids,
                                        int runtimeFlags,
                                        String seInfo,
                                        String abi,
                                        String acceptedAbiList,
                                        String instructionSet,
                                        int uidRangeStart,
                                        int uidRangeEnd,
                                        ApplicationInfo appInfo);

    /**
     * Return the socket address for the primary zygote.
     */
    LocalSocketAddress getPrimarySocketAddress();

    /**
     * Instructs the zygote to pre-load the application code for the given Application.
     * Only the app zygote supports this function.
     */
    boolean preloadApp(ApplicationInfo appInfo, String abi)
                throws ZygoteStartFailedEx, IOException;

    /**
     * Closes the connections to the zygote, if they exist.
     */
    void close();
}
