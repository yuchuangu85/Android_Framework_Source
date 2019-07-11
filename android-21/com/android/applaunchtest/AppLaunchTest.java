/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.applaunchtest;

import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple tests that launches a specified app, and waits for a configurable amount of time for
 * crashes and ANRs.
 * <p/>
 * If no crashes occur, test is considered passed.
 * <p/>
 * Derived from frameworks/base/tests/SmokeTests/... . TODO: consider refactoring to share code
 */
public class AppLaunchTest extends InstrumentationTestCase {

    private static final String TAG = "AppLaunchTest";

    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private String mPackageName;
    private Context mContext;
    private long mWaitTime;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getTargetContext();
        assertNotNull("failed to get context", mContext);

        mActivityManager = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = mContext.getPackageManager();
        assertNotNull("failed to get activity manager", mActivityManager);
        assertNotNull("failed to get package manager", mPackageManager);

        assertTrue("Unexpected runner: AppLaunchRunner must be used",
                getInstrumentation() instanceof AppLaunchRunner);
        AppLaunchRunner runner = (AppLaunchRunner)getInstrumentation();
        mPackageName = runner.getAppPackageName();
        mWaitTime  = runner.getAppWaitTime();
        assertNotNull("package name to launch was not provided", mPackageName);
        assertNotNull("time to wait for app launch was not provided", mWaitTime);
    }

    /**
     * A test that runs Launcher-launchable activity for given package name and verifies that no
     * ANRs or crashes happened while doing so.
     */
    public void testLaunchActivity() throws Exception {
        final Set<ProcessError> errSet = new LinkedHashSet<ProcessError>();

        ResolveInfo app = getLauncherActivity(mPackageName, mPackageManager);
        assertNotNull(String.format("Could not find launchable activity for %s", mPackageName),
                app);
        final Collection<ProcessError> errProcs = runOneActivity(app, mWaitTime);
        if (errProcs != null) {
            errSet.addAll(errProcs);
         }

        if (!errSet.isEmpty()) {
            fail(String.format("Detected %d errors on launch of app %s:\n%s", errSet.size(),
                    mPackageName, reportWrappedListContents(errSet)));
        }
    }

    /**
     * A method to run the specified Activity and return a {@link Collection} of the Activities that
     * were in an error state, as listed by {@link ActivityManager.getProcessesInErrorState()}.
     * <p />
     * The method will launch the app, wait for waitTime seconds, check for apps in the error state
     * and then return.
     */
    public Collection<ProcessError> runOneActivity(ResolveInfo app, long appLaunchWait) {

        Log.i(TAG, String.format("Running activity %s/%s", app.activityInfo.packageName,
                app.activityInfo.name));

        // We check for any Crash or ANR dialogs that are already up, and we ignore them.  This is
        // so that we don't report crashes that were caused by prior apps.
        final Collection<ProcessError> preErrProcs =
                ProcessError.fromCollection(mActivityManager.getProcessesInErrorState());

        // launch app, and waitfor it to start/settle
        final Intent intent = intentForActivity(app);
        mContext.startActivity(intent);
        try {
            Thread.sleep(appLaunchWait);
        } catch (InterruptedException e) {
            // ignore
        }

        // TODO: inject event to see if app is responding. The smoke tests press 'Home', but
        // we don't want to do that here because we want to take screenshot on app launch

        // See if there are any errors.  We wait until down here to give ANRs as much time as
        // possible to occur.
        final Collection<ProcessError> errProcs =
                ProcessError.fromCollection(mActivityManager.getProcessesInErrorState());

        // Distinguish the asynchronous crashes/ANRs from the synchronous ones by checking the
        // crash package name against the package name for {@code app}
        if (errProcs != null) {
            Iterator<ProcessError> errIter = errProcs.iterator();
            while (errIter.hasNext()) {
                ProcessError err = errIter.next();
                if (!packageMatches(app, err)) {
                    // crash in another package. Just log it for now
                    Log.w(TAG, String.format("Detected crash in %s when launching %s",
                            err.info.processName, app.activityInfo.packageName));
                    errIter.remove();
                }
            }
        }
        // Take the difference between the remaining current error processes and the ones that were
        // present when we started.  The result is guaranteed to be:
        // 1) Errors that are pertinent to this app's package
        // 2) Errors that are pertinent to this particular app invocation
        if (errProcs != null && preErrProcs != null) {
            errProcs.removeAll(preErrProcs);
        }

        return errProcs;
    }

    /**
     * A helper function that checks whether the specified error could have been caused by the
     * specified app.
     *
     * @param app The app to check against
     * @param err The error that we're considering
     */
    private static boolean packageMatches(ResolveInfo app, ProcessError err) {
        final String appPkg = app.activityInfo.packageName;
        final String errPkg = err.info.processName;
        Log.d(TAG, String.format("packageMatches(%s, %s)", appPkg, errPkg));
        return appPkg.equals(errPkg);
    }

    /**
     * A helper function to get the launchable activity for the given package name.
     */
    static ResolveInfo getLauncherActivity(String packageName, PackageManager pm) {
        final Intent launchable = new Intent(Intent.ACTION_MAIN);
        launchable.addCategory(Intent.CATEGORY_LAUNCHER);
        launchable.setPackage(packageName);
        return pm.resolveActivity(launchable, 0);
    }

    /**
     * A helper function to create an {@link Intent} to run, given a {@link ResolveInfo} specifying
     * an activity to be launched.
     */
    static Intent intentForActivity(ResolveInfo app) {
        final ComponentName component = new ComponentName(app.activityInfo.packageName,
                app.activityInfo.name);
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    /**
     * Report error reports for {@link ProcessErrorStateInfo} instances that are wrapped inside of
     * {@link ProcessError} instances.  Just unwraps and calls
     * {@see reportListContents(Collection<ProcessErrorStateInfo>)}.
     */
    static String reportWrappedListContents(Collection<ProcessError> errList) {
        List<ProcessErrorStateInfo> newList = new ArrayList<ProcessErrorStateInfo>(errList.size());
        for (ProcessError err : errList) {
            newList.add(err.info);
        }
        return reportListContents(newList);
    }

    /**
     * This helper function will dump the actual error reports.
     *
     * @param errList The error report containing one or more error records.
     * @return Returns a string containing all of the errors.
     */
    private static String reportListContents(Collection<ProcessErrorStateInfo> errList) {
        if (errList == null) return null;

        StringBuilder builder = new StringBuilder();

        Iterator<ProcessErrorStateInfo> iter = errList.iterator();
        while (iter.hasNext()) {
            ProcessErrorStateInfo entry = iter.next();

            String condition;
            switch (entry.condition) {
            case ActivityManager.ProcessErrorStateInfo.CRASHED:
                condition = "a CRASH";
                break;
            case ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING:
                condition = "an ANR";
                break;
            default:
                condition = "an unknown error";
                break;
            }

            builder.append(String.format("Process %s encountered %s (%s)", entry.processName,
                    condition, entry.shortMsg));
            if (entry.condition == ActivityManager.ProcessErrorStateInfo.CRASHED) {
                builder.append(String.format(" with stack trace:\n%s\n", entry.stackTrace));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * A {@link ProcessErrorStateInfo} wrapper class that hashes how we want (so that equivalent
     * crashes are considered equal).
     */
    static class ProcessError {
        public final ProcessErrorStateInfo info;

        public ProcessError(ProcessErrorStateInfo newInfo) {
            info = newInfo;
        }

        public static Collection<ProcessError> fromCollection(Collection<ProcessErrorStateInfo> in)
                {
            if (in == null) {
                return null;
            }

            List<ProcessError> out = new ArrayList<ProcessError>(in.size());
            for (ProcessErrorStateInfo info : in) {
                out.add(new ProcessError(info));
            }
            return out;
        }

        private boolean strEquals(String a, String b) {
            if ((a == null) && (b == null)) {
                return true;
            } else if ((a == null) || (b == null)) {
                return false;
            } else {
                return a.equals(b);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) return false;
            if (!(other instanceof ProcessError)) return false;
            ProcessError peOther = (ProcessError) other;

            return (info.condition == peOther.info.condition)
                    && strEquals(info.longMsg, peOther.info.longMsg)
                    && (info.pid == peOther.info.pid)
                    && strEquals(info.processName, peOther.info.processName)
                    && strEquals(info.shortMsg, peOther.info.shortMsg)
                    && strEquals(info.stackTrace, peOther.info.stackTrace)
                    && strEquals(info.tag, peOther.info.tag)
                    && (info.uid == peOther.info.uid);
        }

        private int hash(Object obj) {
            if (obj == null) {
                return 13;
            } else {
                return obj.hashCode();
            }
        }

        @Override
        public int hashCode() {
            int code = 17;
            code += info.condition;
            code *= hash(info.longMsg);
            code += info.pid;
            code *= hash(info.processName);
            code *= hash(info.shortMsg);
            code *= hash(info.stackTrace);
            code *= hash(info.tag);
            code += info.uid;
            return code;
        }
    }
}
