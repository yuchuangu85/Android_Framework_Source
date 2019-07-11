/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.support.test.runner;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue.IdleHandler;
import android.support.test.internal.runner.InstrumentationArgumentsRegistry;
import android.support.test.internal.runner.InstrumentationRegistry;
import android.support.test.internal.runner.lifecycle.ActivityLifecycleMonitorImpl;
import android.support.test.internal.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import android.support.test.runner.lifecycle.Stage;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An instrumentation that enables several advanced features and makes some hard guarantees about
 * the state of the application under instrumentation.
 * <p/>
 * A short list of these capabilities:
 * <ul>
 * <li>Forces Application.onCreate() to happen before Instrumentation.onStart() runs (ensuring your
 * code always runs in a sane state).</li>
 * <li>Logs application death due to exceptions.</li>
 * <li>Allows tracking of activity lifecycle states.</li>
 * <li>Registers instrumentation arguments in an easy to access place.</li>
 * <li>Ensures your activities are creating themselves in reasonable amounts of time.</li>
 * <li>Provides facilities to dump current app threads to test outputs.</li>
 * <li>Ensures all activities finish before instrumentation exits.</li>
 * </ul>
 *
 * This Instrumentation is *NOT* a test instrumentation (some of its subclasses are). It makes no
 * assumptions about what the subclass wants to do.
 */
public class MonitoringInstrumentation extends Instrumentation {

    private static final long MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP = TimeUnit.SECONDS.toMillis(2);
    private static final long MILLIS_TO_POLL_FOR_ACTIVITY_STOP =
            MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP / 40;

    private static final String LOG_TAG = "MonitoringInstrumentation";

    private static final int START_ACTIVITY_TIMEOUT_SECONDS = 45;
    private ActivityLifecycleMonitorImpl mLifecycleMonitor = new ActivityLifecycleMonitorImpl();
    private ExecutorService mExecutorService;
    private Handler mHandlerForMainLooper;
    private AtomicBoolean mAnActivityHasBeenLaunched = new AtomicBoolean(false);
    private Thread mMainThread;
    private AtomicLong mLastIdleTime = new AtomicLong(0);
    private AtomicInteger mStartedActivityCounter = new AtomicInteger(0);

    private IdleHandler mIdleHandler = new IdleHandler() {
        @Override
        public boolean queueIdle() {
            mLastIdleTime.set(System.currentTimeMillis());
            return true;
        }
    };

    private volatile boolean mFinished = false;

    /**
     * Sets up lifecycle monitoring, and argument registry.
     * <p>
     * Subclasses must call up to onCreate(). This onCreate method does not call start()
     * it is the subclasses responsibility to call start if it desires.
     * </p>
     */
    @Override
    public void onCreate(Bundle arguments) {
        Log.i(LOG_TAG, "Instrumentation Started!");
        logUncaughtExceptions();

        InstrumentationRegistry.registerInstance(this);
        ActivityLifecycleMonitorRegistry.registerInstance(mLifecycleMonitor);

        InstrumentationArgumentsRegistry.registerInstance(arguments);

        mHandlerForMainLooper = new Handler(Looper.getMainLooper());
        mMainThread = Thread.currentThread();
        mExecutorService = Executors.newCachedThreadPool();
        Looper.myQueue().addIdleHandler(mIdleHandler);
        super.onCreate(arguments);
    }

    protected final void specifyDexMakerCacheProperty() {
        // DexMaker uses heuristics to figure out where to store its temporary dex files
        // these heuristics may break (eg - they no longer work on JB MR2). So we create
        // our own cache dir to be used if the app doesnt specify a cache dir, rather then
        // relying on heuristics.
        //
        File dexCache = getTargetContext().getDir("dxmaker_cache", Context.MODE_PRIVATE);
        System.getProperties().put("dexmaker.dexcache", dexCache.getAbsolutePath());
    }

    private void logUncaughtExceptions() {
        final Thread.UncaughtExceptionHandler standardHandler =
                Thread.currentThread().getUncaughtExceptionHandler();
        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                onException(t, e);
                if (null != standardHandler) {
                    standardHandler.uncaughtException(t, e);
                }
            }
        });
    }

    /**
     * This implementation of onStart() will guarantee that the Application's onCreate method
     * has completed when it returns.
     * <p>
     * Subclasses should call super.onStart() before executing any code that touches the application
     * and it's state.
     * </p>
     */
    @Override
    public void onStart() {
        super.onStart();

        // Due to the way Android initializes instrumentation - all instrumentations have the
        // possibility of seeing the Application and its classes in an inconsistent state.
        // Specifically ActivityThread creates Instrumentation first, initializes it, and calls
        // instrumentation.onCreate(). After it does that, it calls
        // instrumentation.callApplicationOnCreate() which ends up calling the application's
        // onCreateMethod.
        //
        // So, Android's InstrumentationTestRunner's onCreate method() spawns a separate thread to
        // execute tests. This causes tests to start accessing the application and its classes while
        // the ActivityThread is calling callApplicationOnCreate() in its own thread.
        //
        // This makes it possible for tests to see the application in a state that is normally never
        // visible: pre-application.onCreate() and during application.onCreate()).
        //
        // *phew* that sucks! Here we waitForOnIdleSync() to ensure onCreate has completed before we
        // start executing tests.
        waitForIdleSync();
    }

    /**
     * Ensures all activities launched in this instrumentation are finished before the
     * instrumentation exits.
     * <p>
     * Subclasses who override this method should do their finish processing and then call
     * super.finish to invoke this logic. Not waiting for all activities to finish() before exiting
     * can cause device wide instability.
     * </p>
     */
    @Override
    public void finish(int resultCode, Bundle results) {
        if (mFinished) {
            Log.w(LOG_TAG, "finish called 2x!");
            return;
        } else {
            mFinished = true;
        }

        mHandlerForMainLooper.post(new ActivityFinisher());

        long startTime = System.currentTimeMillis();
        waitForActivitiesToComplete();
        long endTime = System.currentTimeMillis();
        Log.i(LOG_TAG, String.format("waitForActivitiesToComplete() took: %sms", endTime - startTime));
        ActivityLifecycleMonitorRegistry.registerInstance(null);
        super.finish(resultCode, results);
    }

    /**
     * Ensures we've onStopped() all activities which were onStarted().
     * <p>
     * According to Activity's contract, the process is not killable between onStart and onStop.
     * Breaking this contract (which finish() will if you let it) can cause bad behaviour (including
     * a full restart of system_server).
     * </p>
     * <p>
     * We give the app 2 seconds to stop all its activities, then we proceed.
     * </p>
     */
    protected void waitForActivitiesToComplete() {
        long endTime = System.currentTimeMillis() + MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP;
        int currentActivityCount = mStartedActivityCounter.get();

        while (currentActivityCount > 0 && System.currentTimeMillis() < endTime) {
            try {
                Log.i(LOG_TAG, "Unstopped activity count: " + currentActivityCount);
                Thread.sleep(MILLIS_TO_POLL_FOR_ACTIVITY_STOP);
                currentActivityCount = mStartedActivityCounter.get();
            } catch (InterruptedException ie) {
                Log.i(LOG_TAG, "Abandoning activity wait due to interruption.", ie);
                break;
            }
        }

        if (currentActivityCount > 0) {
            dumpThreadStateToOutputs("ThreadState-unstopped.txt");
            Log.w(LOG_TAG, String.format("Still %s activities active after waiting %s ms.",
                    currentActivityCount, MILLIS_TO_WAIT_FOR_ACTIVITY_TO_STOP));
        }
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "Instrumentation Finished!");
        Looper.myQueue().removeIdleHandler(mIdleHandler);
        super.onDestroy();
    }

    @Override
    public Activity startActivitySync(final Intent intent) {
        validateNotAppThread();
        long lastIdleTimeBeforeLaunch = mLastIdleTime.get();

        if (mAnActivityHasBeenLaunched.compareAndSet(false, true)) {
            // All activities launched from InstrumentationTestCase.launchActivityWithIntent get
            // started with FLAG_ACTIVITY_NEW_TASK. This includes calls to
            // ActivityInstrumentationTestcase2.getActivity().
            //
            // This gives us a pristine environment - MOST OF THE TIME.
            //
            // However IF we've run a test method previously and that has launched an activity
            // outside of our process our old task is still lingering around. By launching a new
            // activity android will place our activity at the bottom of the stack and bring the
            // previous external activity to the front of the screen.
            //
            // To wipe out the old task and execute within a pristine environment for each test
            // we tell android to CLEAR_TOP the very first activity we see, no matter what.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        Future<Activity> startedActivity = mExecutorService.submit(new Callable<Activity>() {
            @Override
            public Activity call() {
                return MonitoringInstrumentation.super.startActivitySync(intent);
            }
        });

        try {
            return startedActivity.get(START_ACTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            startedActivity.cancel(true);
            dumpThreadStateToOutputs("ThreadState-startActivityTimeout.txt");
            throw new RuntimeException(String.format("Could not launch intent %s within %s seconds."
                    + " Perhaps the main thread has not gone idle within a reasonable amount of "
                    + "time? There could be an animation or something constantly repainting the "
                    + "screen. Or the activity is doing network calls on creation? See the "
                    + "threaddump logs. For your reference the last time the event queue was idle "
                    + "before your activity launch request was %s and now the last time the queue "
                    + "went idle was: %s. If these numbers are the same your activity might be "
                    +"hogging the event queue.",
                    intent, START_ACTIVITY_TIMEOUT_SECONDS, lastIdleTimeBeforeLaunch,
                    mLastIdleTime.get()));
        } catch (ExecutionException ee) {
            throw new RuntimeException("Could not launch activity", ee.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", ie);
        }
    }

    private void validateNotAppThread() {
        if (mMainThread.equals(Thread.currentThread())) {
            throw new RuntimeException(
                    "this method cannot be called from the main application thread");
        }
    }

    @Override
    public boolean onException(Object obj, Throwable e) {
        String error = String.format("Exception encountered by: %s. Dumping thread state to "
                + "outputs and pining for the fjords.", obj);
        Log.e(LOG_TAG, error, e);
        dumpThreadStateToOutputs("ThreadState-onException.txt");
        Log.e(LOG_TAG, "Dying now...");
        return super.onException(obj, e);
    }

    protected final void dumpThreadStateToOutputs(String outputFileName) {
        String threadState = getThreadState();
        Log.e("THREAD_STATE", threadState);
    }

    private static String getThreadState() {
        Set<Map.Entry<Thread, StackTraceElement[]>> threads = Thread.getAllStackTraces().entrySet();
        StringBuilder threadState = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> threadAndStack : threads) {
            StringBuilder threadMessage = new StringBuilder("  ").append(threadAndStack.getKey());
            threadMessage.append("\n");
            for (StackTraceElement ste : threadAndStack.getValue()) {
                threadMessage.append("    ");
                threadMessage.append(ste.toString());
                threadMessage.append("\n");
            }
            threadMessage.append("\n");
            threadState.append(threadMessage.toString());
        }
        return threadState.toString();
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        super.callActivityOnDestroy(activity);
        mLifecycleMonitor.signalLifecycleChange(Stage.DESTROYED, activity);
    }

    @Override
    public void callActivityOnRestart(Activity activity) {
        super.callActivityOnRestart(activity);
        mLifecycleMonitor.signalLifecycleChange(Stage.RESTARTED, activity);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle bundle) {
        mLifecycleMonitor.signalLifecycleChange(Stage.PRE_ON_CREATE, activity);
        super.callActivityOnCreate(activity, bundle);
        mLifecycleMonitor.signalLifecycleChange(Stage.CREATED, activity);
    }

    // NOTE: we need to keep a count of activities between the start
    // and stop lifecycle internal to our instrumentation. Exiting the test
    // process with activities in this state can cause crashes/flakiness
    // that would impact a subsequent test run.
    @Override
    public void callActivityOnStart(Activity activity) {
        mStartedActivityCounter.incrementAndGet();
        try {
            super.callActivityOnStart(activity);
            mLifecycleMonitor.signalLifecycleChange(Stage.STARTED, activity);
        } catch (RuntimeException re) {
            mStartedActivityCounter.decrementAndGet();
            throw re;
        }
    }

    @Override
    public void callActivityOnStop(Activity activity) {
        try {
            super.callActivityOnStop(activity);
            mLifecycleMonitor.signalLifecycleChange(Stage.STOPPED, activity);
        } finally {
            mStartedActivityCounter.decrementAndGet();
        }
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        mLifecycleMonitor.signalLifecycleChange(Stage.RESUMED, activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        super.callActivityOnPause(activity);
        mLifecycleMonitor.signalLifecycleChange(Stage.PAUSED, activity);
    }

    /**
     * Loops through all the activities that have not yet finished and explicitly calls finish
     * on them.
     */
    public class ActivityFinisher implements Runnable {
        @Override
        public void run() {
            List<Activity> activities = new ArrayList<Activity>();

            for (Stage s : EnumSet.range(Stage.CREATED, Stage.PAUSED)) {
                activities.addAll(mLifecycleMonitor.getActivitiesInStage(s));
            }

            Log.i(LOG_TAG, "Activities that are still in CREATED to PAUSED: " + activities.size());

            for (Activity activity : activities) {
                if (!activity.isFinishing()) {
                    try {
                        Log.i(LOG_TAG, "Stopping activity: " + activity);
                        activity.finish();
                    } catch (RuntimeException e) {
                        Log.e(LOG_TAG, "Failed to stop activity.", e);
                    }
                }
            }
        }
    };
}
