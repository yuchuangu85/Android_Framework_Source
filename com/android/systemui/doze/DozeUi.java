/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.wakelock.WakeLock;

import java.util.Calendar;

import javax.inject.Inject;

/**
 * The policy controlling doze.
 */
@DozeScope
public class DozeUi implements DozeMachine.Part {
    private static final long TIME_TICK_DEADLINE_MILLIS = 90 * 1000; // 1.5min
    private final Context mContext;
    private final DozeHost mHost;
    private final Handler mHandler;
    private final WakeLock mWakeLock;
    private DozeMachine mMachine;
    private final AlarmTimeout mTimeTicker;
    private final boolean mCanAnimateTransition;
    private final DozeParameters mDozeParameters;
    private final DozeLog mDozeLog;
    private final StatusBarStateController mStatusBarStateController;

    private long mLastTimeTickElapsed = 0;

    @Inject
    public DozeUi(Context context, AlarmManager alarmManager,
            WakeLock wakeLock, DozeHost host, @Main Handler handler,
            DozeParameters params,
            StatusBarStateController statusBarStateController,
            DozeLog dozeLog) {
        mContext = context;
        mWakeLock = wakeLock;
        mHost = host;
        mHandler = handler;
        mCanAnimateTransition = !params.getDisplayNeedsBlanking();
        mDozeParameters = params;
        mTimeTicker = new AlarmTimeout(alarmManager, this::onTimeTick, "doze_time_tick", handler);
        mDozeLog = dozeLog;
        mStatusBarStateController = statusBarStateController;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    private void pulseWhileDozing(int reason) {
        mHost.pulseWhileDozing(
                new DozeHost.PulseCallback() {
                    @Override
                    public void onPulseStarted() {
                        try {
                            mMachine.requestState(
                                    reason == DozeLog.PULSE_REASON_SENSOR_WAKE_REACH
                                            ? DozeMachine.State.DOZE_PULSING_BRIGHT
                                            : DozeMachine.State.DOZE_PULSING);
                        } catch (IllegalStateException e) {
                            // It's possible that the pulse was asynchronously cancelled while
                            // we were waiting for it to start (under stress conditions.)
                            // In those cases we should just ignore it. b/127657926
                        }
                    }

                    @Override
                    public void onPulseFinished() {
                        mMachine.requestState(DozeMachine.State.DOZE_PULSE_DONE);
                    }
                }, reason);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case DOZE_AOD:
            case DOZE_AOD_DOCKED:
                if (oldState == DOZE_AOD_PAUSED || oldState == DOZE) {
                    // Whenever turning on the display, it's necessary to push a new frame.
                    // The display buffers will be empty and need to be filled.
                    mHost.dozeTimeTick();
                    // The first frame may arrive when the display isn't ready yet.
                    mHandler.postDelayed(mWakeLock.wrap(mHost::dozeTimeTick), 500);
                }
                scheduleTimeTick();
                break;
            case DOZE_AOD_PAUSING:
                scheduleTimeTick();
                break;
            case DOZE:
            case DOZE_AOD_PAUSED:
            case DOZE_SUSPEND_TRIGGERS:
                unscheduleTimeTick();
                break;
            case DOZE_REQUEST_PULSE:
                scheduleTimeTick();
                pulseWhileDozing(mMachine.getPulseReason());
                break;
            case INITIALIZED:
                mHost.startDozing();
                break;
            case FINISH:
                mHost.stopDozing();
                unscheduleTimeTick();
                break;
        }
        updateAnimateWakeup(newState);
    }

    private void updateAnimateWakeup(DozeMachine.State state) {
        switch (state) {
            case DOZE_REQUEST_PULSE:
            case DOZE_PULSING:
            case DOZE_PULSING_BRIGHT:
            case DOZE_PULSE_DONE:
                mHost.setAnimateWakeup(true);
                break;
            case FINISH:
                // Keep current state.
                break;
            default:
                mHost.setAnimateWakeup(mCanAnimateTransition && mDozeParameters.getAlwaysOn());
                break;
        }
    }

    private void scheduleTimeTick() {
        if (mTimeTicker.isScheduled()) {
            return;
        }

        long time = System.currentTimeMillis();
        long delta = roundToNextMinute(time) - System.currentTimeMillis();
        boolean scheduled = mTimeTicker.schedule(delta, AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
        if (scheduled) {
            mDozeLog.traceTimeTickScheduled(time, time + delta);
        }
        mLastTimeTickElapsed = SystemClock.elapsedRealtime();
    }

    private void unscheduleTimeTick() {
        if (!mTimeTicker.isScheduled()) {
            return;
        }
        verifyLastTimeTick();
        mTimeTicker.cancel();
    }

    private void verifyLastTimeTick() {
        long millisSinceLastTick = SystemClock.elapsedRealtime() - mLastTimeTickElapsed;
        if (millisSinceLastTick > TIME_TICK_DEADLINE_MILLIS) {
            String delay = Formatter.formatShortElapsedTime(mContext, millisSinceLastTick);
            mDozeLog.traceMissedTick(delay);
            Log.e(DozeMachine.TAG, "Missed AOD time tick by " + delay);
        }
    }

    private long roundToNextMinute(long timeInMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeInMillis);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.add(Calendar.MINUTE, 1);

        return calendar.getTimeInMillis();
    }

    private void onTimeTick() {
        verifyLastTimeTick();

        mHost.dozeTimeTick();

        // Keep wakelock until a frame has been pushed.
        mHandler.post(mWakeLock.wrap(() -> {}));

        scheduleTimeTick();
    }
}
