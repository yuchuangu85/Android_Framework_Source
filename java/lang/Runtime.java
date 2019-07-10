/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright (C) 2008 The Android Open Source Project
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

package java.lang;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.VMDebug;
import dalvik.system.VMRuntime;
import dalvik.system.VMStack;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.FinalizerReference;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import libcore.io.IoUtils;
import libcore.io.Libcore;
import libcore.util.EmptyArray;
import static android.system.OsConstants._SC_NPROCESSORS_CONF;

/**
 * Allows Java applications to interface with the environment in which they are
 * running. Applications can not create an instance of this class, but they can
 * get a singleton instance by invoking {@link #getRuntime()}.
 *
 * @see System
 */
public class Runtime {

    /**
     * Holds the Singleton global instance of Runtime.
     */
    private static final Runtime mRuntime = new Runtime();

    /**
     * Holds the library paths, used for native library lookup.
     */
    private final String[] mLibPaths = initLibPaths();

    private static String[] initLibPaths() {
        String javaLibraryPath = System.getProperty("java.library.path");
        if (javaLibraryPath == null) {
            return EmptyArray.STRING;
        }
        String[] paths = javaLibraryPath.split(":");
        // Add a '/' to the end of each directory so we don't have to do it every time.
        for (int i = 0; i < paths.length; ++i) {
            if (!paths[i].endsWith("/")) {
                paths[i] += "/";
            }
        }
        return paths;
    }

    /**
     * Holds the list of threads to run when the VM terminates
     */
    private List<Thread> shutdownHooks = new ArrayList<Thread>();

    /**
     * Reflects whether finalization should be run for all objects
     * when the VM terminates.
     */
    private static boolean finalizeOnExit;

    /**
     * Reflects whether we are already shutting down the VM.
     */
    private boolean shuttingDown;

    /**
     * Reflects whether we are tracing method calls.
     */
    private boolean tracingMethods;

    /**
     * Prevent this class from being instantiated.
     */
    private Runtime() {
    }

    /**
     * Executes the specified command and its arguments in a separate native
     * process. The new process inherits the environment of the caller. Calling
     * this method is equivalent to calling {@code exec(progArray, null, null)}.
     *
     * @param progArray
     *            the array containing the program to execute as well as any
     *            arguments to the program.
     * @return the new {@code Process} object that represents the native
     *         process.
     * @throws IOException
     *             if the requested program can not be executed.
     */
    public Process exec(String[] progArray) throws java.io.IOException {
        return exec(progArray, null, null);
    }

    /**
     * Executes the specified command and its arguments in a separate native
     * process. The new process uses the environment provided in {@code envp}.
     * Calling this method is equivalent to calling
     * {@code exec(progArray, envp, null)}.
     *
     * @param progArray
     *            the array containing the program to execute as well as any
     *            arguments to the program.
     * @param envp
     *            the array containing the environment to start the new process
     *            in.
     * @return the new {@code Process} object that represents the native
     *         process.
     * @throws IOException
     *             if the requested program can not be executed.
     */
    public Process exec(String[] progArray, String[] envp) throws java.io.IOException {
        return exec(progArray, envp, null);
    }

    /**
     * Executes the specified command and its arguments in a separate native
     * process. The new process uses the environment provided in {@code envp}
     * and the working directory specified by {@code directory}.
     *
     * @param progArray
     *            the array containing the program to execute as well as any
     *            arguments to the program.
     * @param envp
     *            the array containing the environment to start the new process
     *            in.
     * @param directory
     *            the directory in which to execute the program. If {@code null},
     *            execute if in the same directory as the parent process.
     * @return the new {@code Process} object that represents the native
     *         process.
     * @throws IOException
     *             if the requested program can not be executed.
     */
    public Process exec(String[] progArray, String[] envp, File directory) throws IOException {
        // ProcessManager is responsible for all argument checking.
        return ProcessManager.getInstance().exec(progArray, envp, directory, false);
    }

    /**
     * Executes the specified program in a separate native process. The new
     * process inherits the environment of the caller. Calling this method is
     * equivalent to calling {@code exec(prog, null, null)}.
     *
     * @param prog
     *            the name of the program to execute.
     * @return the new {@code Process} object that represents the native
     *         process.
     * @throws IOException
     *             if the requested program can not be executed.
     */
    public Process exec(String prog) throws java.io.IOException {
        return exec(prog, null, null);
    }

    /**
     * Executes the specified program in a separate native process. The new
     * process uses the environment provided in {@code envp}. Calling this
     * method is equivalent to calling {@code exec(prog, envp, null)}.
     *
     * @param prog
     *            the name of the program to execute.
     * @param envp
     *            the array containing the environment to start the new process
     *            in.
     * @return the new {@code Process} object that represents the native
     *         process.
     * @throws IOException
     *             if the requested program can not be executed.
     */
    public Process exec(String prog, String[] envp) throws java.io.IOException {
        return exec(prog, envp, null);
    }

    /**
     * Executes the specified program in a separate native process. The new
     * process uses the environment provided in {@code envp} and the working
     * directory specified by {@code directory}.
     *
     * @param prog
     *            the name of the program to execute.
     * @param envp
     *            the array containing the environment to start the new process
     *            in.
     * @param directory
     *            the directory in which to execute the program. If {@code null},
     *            execute if in the same directory as the parent process.
     * @return the new {@code Process} object that represents the native
     *         process.
     * @throws IOException
     *             if the requested program can not be executed.
     */
    public Process exec(String prog, String[] envp, File directory) throws java.io.IOException {
        // Sanity checks
        if (prog == null) {
            throw new NullPointerException("prog == null");
        } else if (prog.isEmpty()) {
            throw new IllegalArgumentException("prog is empty");
        }

        // Break down into tokens, as described in Java docs
        StringTokenizer tokenizer = new StringTokenizer(prog);
        int length = tokenizer.countTokens();
        String[] progArray = new String[length];
        for (int i = 0; i < length; i++) {
            progArray[i] = tokenizer.nextToken();
        }

        // Delegate
        return exec(progArray, envp, directory);
    }

    /**
     * Causes the VM to stop running and the program to exit.
     * If {@link #runFinalizersOnExit(boolean)} has been previously invoked with a
     * {@code true} argument, then all objects will be properly
     * garbage-collected and finalized first.
     * Use 0 to signal success to the calling process and 1 to signal failure.
     * This method is unlikely to be useful to an Android application.
     */
    public void exit(int code) {
        // Make sure we don't try this several times
        synchronized(this) {
            if (!shuttingDown) {
                shuttingDown = true;

                Thread[] hooks;
                synchronized (shutdownHooks) {
                    // create a copy of the hooks
                    hooks = new Thread[shutdownHooks.size()];
                    shutdownHooks.toArray(hooks);
                }

                // Start all shutdown hooks concurrently
                for (Thread hook : hooks) {
                    hook.start();
                }

                // Wait for all shutdown hooks to finish
                for (Thread hook : hooks) {
                    try {
                        hook.join();
                    } catch (InterruptedException ex) {
                        // Ignore, since we are at VM shutdown.
                    }
                }

                // Ensure finalization on exit, if requested
                if (finalizeOnExit) {
                    runFinalization();
                }

                // Get out of here finally...
                nativeExit(code);
            }
        }
    }

    /**
     * Indicates to the VM that it would be a good time to run the
     * garbage collector. Note that this is a hint only. There is no guarantee
     * that the garbage collector will actually be run.
     */
    public native void gc();

    /**
     * Returns the single {@code Runtime} instance for the current application.
     */
    public static Runtime getRuntime() {
        return mRuntime;
    }

    /**
     * Loads the shared library found at the given absolute path.
     * This should be of the form {@code /path/to/library/libMyLibrary.so}.
     * Most callers should use {@link #loadLibrary(String)} instead, and
     * let the system find the correct file to load.
     *
     * @throws UnsatisfiedLinkError if the library can not be loaded,
     * either because it's not found or because there is something wrong with it.
     */
    public void load(String absolutePath) {
        load(absolutePath, VMStack.getCallingClassLoader());
    }

    /*
     * Loads the given shared library using the given ClassLoader.
     */
    void load(String absolutePath, ClassLoader loader) {
        if (absolutePath == null) {
            throw new NullPointerException("absolutePath == null");
        }
        String error = doLoad(absolutePath, loader);
        if (error != null) {
            throw new UnsatisfiedLinkError(error);
        }
    }

    /**
     * Loads a shared library. Class loaders have some influence over this
     * process, but for a typical Android app, it works as follows:
     *
     * <p>Given the name {@code "MyLibrary"}, that string will be passed to
     * {@link System#mapLibraryName}. That means it would be a mistake
     * for the caller to include the usual {@code "lib"} prefix and {@code ".so"}
     * suffix.
     *
     * <p>That file will then be searched for on the application's native library
     * search path. This consists of the application's own native library directory
     * followed by the system's native library directories.
     *
     * @throws UnsatisfiedLinkError if the library can not be loaded,
     * either because it's not found or because there is something wrong with it.
     */
    public void loadLibrary(String nickname) {
        loadLibrary(nickname, VMStack.getCallingClassLoader());
    }

    /*
     * Searches for and loads the given shared library using the given ClassLoader.
     */
    void loadLibrary(String libraryName, ClassLoader loader) {
        if (loader != null) {
            String filename = loader.findLibrary(libraryName);
            if (filename == null) {
                // It's not necessarily true that the ClassLoader used
                // System.mapLibraryName, but the default setup does, and it's
                // misleading to say we didn't find "libMyLibrary.so" when we
                // actually searched for "liblibMyLibrary.so.so".
                throw new UnsatisfiedLinkError(loader + " couldn't find \"" +
                                               System.mapLibraryName(libraryName) + "\"");
            }
            String error = doLoad(filename, loader);
            if (error != null) {
                throw new UnsatisfiedLinkError(error);
            }
            return;
        }

        String filename = System.mapLibraryName(libraryName);
        List<String> candidates = new ArrayList<String>();
        String lastError = null;
        for (String directory : mLibPaths) {
            String candidate = directory + filename;
            candidates.add(candidate);

            if (IoUtils.canOpenReadOnly(candidate)) {
                String error = doLoad(candidate, loader);
                if (error == null) {
                    return; // We successfully loaded the library. Job done.
                }
                lastError = error;
            }
        }

        if (lastError != null) {
            throw new UnsatisfiedLinkError(lastError);
        }
        throw new UnsatisfiedLinkError("Library " + libraryName + " not found; tried " + candidates);
    }

    private static native void nativeExit(int code);

    private String doLoad(String name, ClassLoader loader) {
        // Android apps are forked from the zygote, so they can't have a custom LD_LIBRARY_PATH,
        // which means that by default an app's shared library directory isn't on LD_LIBRARY_PATH.

        // The PathClassLoader set up by frameworks/base knows the appropriate path, so we can load
        // libraries with no dependencies just fine, but an app that has multiple libraries that
        // depend on each other needed to load them in most-dependent-first order.

        // We added API to Android's dynamic linker so we can update the library path used for
        // the currently-running process. We pull the desired path out of the ClassLoader here
        // and pass it to nativeLoad so that it can call the private dynamic linker API.

        // We didn't just change frameworks/base to update the LD_LIBRARY_PATH once at the
        // beginning because multiple apks can run in the same process and third party code can
        // use its own BaseDexClassLoader.

        // We didn't just add a dlopen_with_custom_LD_LIBRARY_PATH call because we wanted any
        // dlopen(3) calls made from a .so's JNI_OnLoad to work too.

        // So, find out what the native library search path is for the ClassLoader in question...
        String ldLibraryPath = null;
        String dexPath = null;
        if (loader == null) {
            // We use the given library path for the boot class loader. This is the path
            // also used in loadLibraryName if loader is null.
            ldLibraryPath = System.getProperty("java.library.path");
        } else if (loader instanceof BaseDexClassLoader) {
            BaseDexClassLoader dexClassLoader = (BaseDexClassLoader) loader;
            ldLibraryPath = dexClassLoader.getLdLibraryPath();
        }
        // nativeLoad should be synchronized so there's only one LD_LIBRARY_PATH in use regardless
        // of how many ClassLoaders are in the system, but dalvik doesn't support synchronized
        // internal natives.
        synchronized (this) {
            return nativeLoad(name, loader, ldLibraryPath);
        }
    }

    // TODO: should be synchronized, but dalvik doesn't support synchronized internal natives.
    private static native String nativeLoad(String filename, ClassLoader loader,
            String ldLibraryPath);

    /**
     * Provides a hint to the runtime that it would be useful to attempt
     * to perform any outstanding object finalization.
     */
    public void runFinalization() {
        // 0 for no timeout.
        VMRuntime.runFinalization(0);
    }

    /**
     * Sets the flag that indicates whether all objects are finalized when the
     * runtime is about to exit. Note that all finalization which occurs
     * when the system is exiting is performed after all running threads have
     * been terminated.
     *
     * @param run
     *            {@code true} to enable finalization on exit, {@code false} to
     *            disable it.
     * @deprecated This method is unsafe.
     */
    @Deprecated
    public static void runFinalizersOnExit(boolean run) {
        finalizeOnExit = run;
    }

    /**
     * Switches the output of debug information for instructions on or off.
     * On Android, this method does nothing.
     */
    public void traceInstructions(boolean enable) {
    }

    /**
     * Switches the output of debug information for methods on or off.
     */
    public void traceMethodCalls(boolean enable) {
        if (enable != tracingMethods) {
            if (enable) {
                VMDebug.startMethodTracing();
            } else {
                VMDebug.stopMethodTracing();
            }
            tracingMethods = enable;
        }
    }

    /**
     * Returns the localized version of the specified input stream. The input
     * stream that is returned automatically converts all characters from the
     * local character set to Unicode after reading them from the underlying
     * stream.
     *
     * @param stream
     *            the input stream to localize.
     * @return the localized input stream.
     * @deprecated Use {@link InputStreamReader} instead.
     */
    @Deprecated
    public InputStream getLocalizedInputStream(InputStream stream) {
        String encoding = System.getProperty("file.encoding", "UTF-8");
        if (!encoding.equals("UTF-8")) {
            throw new UnsupportedOperationException("Cannot localize " + encoding);
        }
        return stream;
    }

    /**
     * Returns the localized version of the specified output stream. The output
     * stream that is returned automatically converts all characters from
     * Unicode to the local character set before writing them to the underlying
     * stream.
     *
     * @param stream
     *            the output stream to localize.
     * @return the localized output stream.
     * @deprecated Use {@link OutputStreamWriter} instead.
     */
    @Deprecated
    public OutputStream getLocalizedOutputStream(OutputStream stream) {
        String encoding = System.getProperty("file.encoding", "UTF-8");
        if (!encoding.equals("UTF-8")) {
            throw new UnsupportedOperationException("Cannot localize " + encoding);
        }
        return stream;
    }

    /**
     * Registers a VM shutdown hook. A shutdown hook is a
     * {@code Thread} that is ready to run, but has not yet been started. All
     * registered shutdown hooks will be executed when the VM
     * terminates normally (typically when the {@link #exit(int)} method is called).
     *
     * <p><i>Note that on Android, the application lifecycle does not include VM termination,
     * so calling this method will not ensure that your code is run</i>. Instead, you should
     * use the most appropriate lifecycle notification ({@code Activity.onPause}, say).
     *
     * <p>Shutdown hooks are run concurrently and in an unspecified order. Hooks
     * failing due to an unhandled exception are not a problem, but the stack
     * trace might be printed to the console. Once initiated, the whole shutdown
     * process can only be terminated by calling {@code halt()}.
     *
     * <p>If {@link #runFinalizersOnExit(boolean)} has been called with a {@code
     * true} argument, garbage collection and finalization will take place after
     * all hooks are either finished or have failed. Then the VM
     * terminates.
     *
     * <p>It is recommended that shutdown hooks do not do any time-consuming
     * activities, in order to not hold up the shutdown process longer than
     * necessary.
     *
     * @param hook
     *            the shutdown hook to register.
     * @throws IllegalArgumentException
     *             if the hook has already been started or if it has already
     *             been registered.
     * @throws IllegalStateException
     *             if the VM is already shutting down.
     */
    public void addShutdownHook(Thread hook) {
        // Sanity checks
        if (hook == null) {
            throw new NullPointerException("hook == null");
        }

        if (shuttingDown) {
            throw new IllegalStateException("VM already shutting down");
        }

        if (hook.hasBeenStarted) {
            throw new IllegalArgumentException("Hook has already been started");
        }

        synchronized (shutdownHooks) {
            if (shutdownHooks.contains(hook)) {
                throw new IllegalArgumentException("Hook already registered.");
            }

            shutdownHooks.add(hook);
        }
    }

    /**
     * Unregisters a previously registered VM shutdown hook.
     *
     * @param hook
     *            the shutdown hook to remove.
     * @return {@code true} if the hook has been removed successfully; {@code
     *         false} otherwise.
     * @throws IllegalStateException
     *             if the VM is already shutting down.
     */
    public boolean removeShutdownHook(Thread hook) {
        // Sanity checks
        if (hook == null) {
            throw new NullPointerException("hook == null");
        }

        if (shuttingDown) {
            throw new IllegalStateException("VM already shutting down");
        }

        synchronized (shutdownHooks) {
            return shutdownHooks.remove(hook);
        }
    }

    /**
     * Causes the VM to stop running, and the program to exit with the given return code.
     * Use 0 to signal success to the calling process and 1 to signal failure.
     * Neither shutdown hooks nor finalizers are run before exiting.
     * This method is unlikely to be useful to an Android application.
     */
    public void halt(int code) {
        // Get out of here...
        nativeExit(code);
    }

    /**
     * Returns the number of processor cores available to the VM, at least 1.
     * Traditionally this returned the number currently online,
     * but many mobile devices are able to take unused cores offline to
     * save power, so releases newer than Android 4.2 (Jelly Bean) return the maximum number of
     * cores that could be made available if there were no power or heat
     * constraints.
     */
    public int availableProcessors() {
        return (int) Libcore.os.sysconf(_SC_NPROCESSORS_CONF);
    }

    /**
     * Returns the number of bytes currently available on the heap without expanding the heap. See
     * {@link #totalMemory} for the heap's current size. When these bytes are exhausted, the heap
     * may expand. See {@link #maxMemory} for that limit.
     */
    public native long freeMemory();

    /**
     * Returns the number of bytes taken by the heap at its current size. The heap may expand or
     * contract over time, as the number of live objects increases or decreases. See
     * {@link #maxMemory} for the maximum heap size, and {@link #freeMemory} for an idea of how much
     * the heap could currently contract.
     */
    public native long totalMemory();

    /**
     * Returns the maximum number of bytes the heap can expand to. See {@link #totalMemory} for the
     * current number of bytes taken by the heap, and {@link #freeMemory} for the current number of
     * those bytes actually used by live objects.
     */
    public native long maxMemory();
}
