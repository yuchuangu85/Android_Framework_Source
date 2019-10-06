package com.android.clockwork.power;

import android.content.Context;
import android.content.ContentResolver;
import android.os.Binder;
import android.os.PowerManager;

import com.android.clockwork.flags.BooleanFlag;
import com.android.clockwork.flags.ClockworkFlags;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A {@link SystemService} to coordinate keeping track of and controlling the devices and modules
 * that influence the use of Wear's precious power resources.
 *
 * <p>This services responsiblities include:
 *
 * <ul>
 *   <li>starting the {@link WearTouchMediator}
 *   <li>providing access to {@link PowerTracker} & {@link TimeOnlyMode} to other Wear {@link
 *       SystemService}s
 * </ul>
 */
public class WearPowerService extends SystemService {
    public static final String SERVICE_NAME = WearPowerService.class.getSimpleName();

    private static final String TAG = WearPowerConstants.LOG_TAG;

    private AmbientConfig mAmbientConfig;
    private PowerTracker mPowerTracker;
    private TimeOnlyMode mTimeOnlyMode;

    private WearTouchMediator mWearTouchMediator;

    public WearPowerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishLocalService(WearPowerServiceInternal.class, new LocalService());
        publishBinderService(SERVICE_NAME, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            onSystemServicesReady();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            onBootCompleted();
        }
    }

    private void onSystemServicesReady() {
        Context context = getContext();
        ContentResolver contentResolver = context.getContentResolver();

        // Set up flags
        BooleanFlag userAbsentTouchOff = ClockworkFlags.userAbsentTouchOff(contentResolver);

        mAmbientConfig = new AmbientConfig(contentResolver);
        mPowerTracker = new PowerTracker(
                context, context.getSystemService(PowerManager.class));
        mTimeOnlyMode = new TimeOnlyMode(contentResolver, mPowerTracker);

        mWearTouchMediator = new WearTouchMediator(
                context, mAmbientConfig, mPowerTracker, mTimeOnlyMode, userAbsentTouchOff);
    }

    private void onBootCompleted() {
        mAmbientConfig.register();

        mPowerTracker.onBootCompleted();
        mWearTouchMediator.onBootCompleted();
    }

    private final class LocalService extends WearPowerServiceInternal {
        @Override
        public AmbientConfig getAmbientConfig() {
            return mAmbientConfig;
        }

        @Override
        public PowerTracker getPowerTracker() {
            return mPowerTracker;
        }

        @Override
        public TimeOnlyMode getTimeOnlyMode() {
            return mTimeOnlyMode;
        }
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(writer, "  " /* singleIndent */);

            ipw.println("================ WearPowerService ================");
            ipw.println();
            ipw.increaseIndent();

            mPowerTracker.dump(ipw);
            mTimeOnlyMode.dump(ipw);
            ipw.println();

            mWearTouchMediator.dump(ipw);

            ipw.decreaseIndent();
            ipw.println();
        }
    }
}
