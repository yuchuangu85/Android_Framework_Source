package android.support.test.internal.runner.listener;

import static android.support.test.internal.util.Checks.checkNotNull;

import android.app.Activity;
import android.app.Instrumentation;
import android.support.test.runner.lifecycle.ActivityLifecycleMonitor;
import android.support.test.runner.lifecycle.Stage;
import android.util.Log;

import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Ensures that no activities are running when a test method starts and that no activities are still
 * running when it ends.
 */
public class ActivityFinisherRunListener extends RunListener {
    private static final String TAG = "ActivityFinisher";
    private final Instrumentation instrumentation;
    private final ActivityLifecycleMonitor activityLifecycleMonitor;
    public ActivityFinisherRunListener(Instrumentation instrumentation, ActivityLifecycleMonitor activityLifecycleMonitor) {
        this.instrumentation = checkNotNull(instrumentation);
        this.activityLifecycleMonitor = checkNotNull(activityLifecycleMonitor);
    }

    @Override
    public void testStarted(Description description) throws Exception {
        instrumentation.runOnMainSync(makeActivityFinisher());
    }

    @Override
    public void testFinished(Description description) throws Exception {
        instrumentation.runOnMainSync(makeActivityFinisher());
    }

    private Runnable makeActivityFinisher() {
        return new Runnable() {
            @Override
            public void run() {

                List<Activity> activities = new ArrayList<Activity>();
                for (Stage s : EnumSet.range(Stage.CREATED, Stage.PAUSED)) {
                    activities.addAll(activityLifecycleMonitor.getActivitiesInStage(s));
                }
                for (Activity activity : activities) {
                    if (!activity.isFinishing()) {
                        Log.i(TAG, "Finishing: " + activity);
                        try {
                            activity.finish();
                        } catch (RuntimeException re) {
                            Log.e(TAG, "Failed to finish: " + activity, re);
                        }
                    }
                }
            }

        };
    }
}