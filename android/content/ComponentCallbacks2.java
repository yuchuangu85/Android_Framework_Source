/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Callbacks for app state transitions that can be used to improve background memory management.
 * This interface is available in all application components ({@link android.app.Activity}, {@link
 * android.app.Service}, {@link ContentProvider}, and {@link android.app.Application}).
 *
 * <p>You should implement {@link #onTrimMemory} to release memory when your app goes to background
 * states. Doing so helps the system keep your app's process cached in memory for longer, such that
 * the next time that the user brings your app to the foreground, the app will perform a <a
 * href="{@docRoot}topic/performance/vitals/launch-time">warm or hot start</a>, resuming faster and
 * retaining state.
 *
 * <h2>Trim memory levels and how to handle them</h2>
 *
 * <ul>
 *   <li>{@link #TRIM_MEMORY_UI_HIDDEN} <br>
 *       Your app's UI is no longer visible. This is a good time to release large memory allocations
 *       that are used only by your UI, such as {@link android.graphics.Bitmap Bitmaps}, or
 *       resources related to video playback or animations.
 *   <li>{@link #TRIM_MEMORY_BACKGROUND} <br>
 *       Your app's process is considered to be in the background, and has become eligible to be
 *       killed in order to free memory for other processes. Releasing more memory will prolong the
 *       time that your process can remain cached in memory. An effective strategy is to release
 *       resources that can be re-built when the user returns to your app.
 * </ul>
 *
 * <p>Apps that continue to do work for the user when they're not visible can respond to the {@link
 * #TRIM_MEMORY_UI_HIDDEN} callback by changing their behavior to favor lower memory usage. For
 * example, a music player may keep full-sized album art for all tracks in the currently playing
 * playlist as Bitmaps cached in memory. When the app is backgrounded but music playback continues,
 * the app can change the caching behavior to cache fewer, or smaller, Bitmaps in memory.
 *
 * <p>The ordinal values for trim levels represent an escalating series of memory pressure events,
 * with incrementing values accordingly. More states may be added in the future. As such, it's
 * important not to check for specific values, but rather check if the level passed to {@link
 * #onTrimMemory} is greater than or equal to the levels that your application handles. For example:
 *
 * <pre>{@code
 * public void onTrimMemory(int level) {
 *     if (level >= TRIM_MEMORY_BACKGROUND) {
 *         // Release any resources that can be rebuilt
 *         // quickly when the app returns to the foreground
 *         releaseResources();
 *     } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
 *         // Release UI-related resources
 *         releaseUiResources();
 *     }
 * }
 * }</pre>
 *
 * <p class="note"><strong>Note:</strong> the runtime may invoke Garbage Collection (GC) in response
 * to application state changes. There is no need to explicitly invoke GC from your app.
 */
public interface ComponentCallbacks2 extends ComponentCallbacks {

    /** @hide */
    @IntDef(prefix = { "TRIM_MEMORY_" }, value = {
            TRIM_MEMORY_COMPLETE,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_UI_HIDDEN,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_MODERATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrimMemoryLevel {}

    /**
     * Level for {@link #onTrimMemory(int)}: the process is nearing the end
     * of the background LRU list, and if more memory isn't found soon it will
     * be killed.
     *
     * @deprecated Apps are not notified of this level since API level 34
     */
    @Deprecated
    static final int TRIM_MEMORY_COMPLETE = 80;

    /**
     * Level for {@link #onTrimMemory(int)}: the process is around the middle
     * of the background LRU list; freeing memory can help the system keep
     * other processes running later in the list for better overall performance.
     *
     * @deprecated Apps are not notified of this level since API level 34
     */
    @Deprecated
    static final int TRIM_MEMORY_MODERATE = 60;

    /**
     * Level for {@link #onTrimMemory(int)}: the process has gone on to the
     * LRU list.  This is a good opportunity to clean up resources that can
     * efficiently and quickly be re-built if the user returns to the app.
     */
    static final int TRIM_MEMORY_BACKGROUND = 40;

    /**
     * Level for {@link #onTrimMemory(int)}: the process had been showing
     * a user interface, and is no longer doing so.  Large allocations with
     * the UI should be released at this point to allow memory to be better
     * managed.
     */
    static final int TRIM_MEMORY_UI_HIDDEN = 20;

    /**
     * Level for {@link #onTrimMemory(int)}: the process is not an expendable
     * background process, but the device is running extremely low on memory
     * and is about to not be able to keep any background processes running.
     * Your running process should free up as many non-critical resources as it
     * can to allow that memory to be used elsewhere.  The next thing that
     * will happen after this is {@link #onLowMemory()} called to report that
     * nothing at all can be kept in the background, a situation that can start
     * to notably impact the user.
     *
     * @deprecated Apps are not notified of this level since API level 34
     */
    @Deprecated
    static final int TRIM_MEMORY_RUNNING_CRITICAL = 15;

    /**
     * Level for {@link #onTrimMemory(int)}: the process is not an expendable
     * background process, but the device is running low on memory.
     * Your running process should free up unneeded resources to allow that
     * memory to be used elsewhere.
     *
     * @deprecated Apps are not notified of this level since API level 34
     */
    @Deprecated
    static final int TRIM_MEMORY_RUNNING_LOW = 10;

    /**
     * Level for {@link #onTrimMemory(int)}: the process is not an expendable
     * background process, but the device is running moderately low on memory.
     * Your running process may want to release some unneeded resources for
     * use elsewhere.
     *
     * @deprecated Apps are not notified of this level since API level 34
     */
    @Deprecated
    static final int TRIM_MEMORY_RUNNING_MODERATE = 5;

    /**
     * Called when the operating system has determined that it is a good
     * time for a process to trim unneeded memory from its process.
     *
     * You should never compare to exact values of the level, since new
     * intermediate values may be added -- you will typically want to compare if
     * the value is greater or equal to a level you are interested in.
     *
     * <p>To retrieve the processes current trim level at any point, you can
     * use {@link android.app.ActivityManager#getMyMemoryState
     * ActivityManager.getMyMemoryState(RunningAppProcessInfo)}.
     *
     * @param level The context of the trim, giving a hint of the amount of
     * trimming the application may like to perform.
     */
    void onTrimMemory(@TrimMemoryLevel int level);
}
