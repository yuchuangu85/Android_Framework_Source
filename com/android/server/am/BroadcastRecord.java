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

package com.android.server.am;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.PrintWriterPrinter;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An active intent broadcast.
 */
final class BroadcastRecord extends Binder {
    final Intent intent;    // the original intent that generated us
    final ComponentName targetComp; // original component name set on the intent
    final ProcessRecord callerApp; // process that sent this
    final String callerPackage; // who sent this
    final @Nullable String callerFeatureId; // which feature in the package sent this
    final int callingPid;   // the pid of who sent this
    final int callingUid;   // the uid of who sent this
    final boolean callerInstantApp; // caller is an Instant App?
    final boolean ordered;  // serialize the send to receivers?
    final boolean sticky;   // originated from existing sticky data?
    final boolean initialSticky; // initial broadcast from register to sticky?
    final int userId;       // user id this broadcast was for
    final String resolvedType; // the resolved data type
    final String[] requiredPermissions; // permissions the caller has required
    final String[] excludedPermissions; // permissions to exclude
    final int appOp;        // an app op that is associated with this broadcast
    final BroadcastOptions options; // BroadcastOptions supplied by caller
    final List receivers;   // contains BroadcastFilter and ResolveInfo
    final int[] delivery;   // delivery state of each receiver
    final long[] duration;   // duration a receiver took to process broadcast
    IIntentReceiver resultTo; // who receives final result if non-null
    boolean deferred;
    int splitCount;         // refcount for result callback, when split
    int splitToken;         // identifier for cross-BroadcastRecord refcount
    long enqueueClockTime;  // the clock time the broadcast was enqueued
    long dispatchTime;      // when dispatch started on this set of receivers
    long dispatchClockTime; // the clock time the dispatch started
    long receiverTime;      // when current receiver started for timeouts.
    long finishTime;        // when we finished the broadcast.
    boolean timeoutExempt;  // true if this broadcast is not subject to receiver timeouts
    int resultCode;         // current result code value.
    String resultData;      // current result data value.
    Bundle resultExtras;    // current result extra data values.
    boolean resultAbort;    // current result abortBroadcast value.
    int nextReceiver;       // next receiver to be executed.
    IBinder receiver;       // who is currently running, null if none.
    int state;
    int anrCount;           // has this broadcast record hit any ANRs?
    int manifestCount;      // number of manifest receivers dispatched.
    int manifestSkipCount;  // number of manifest receivers skipped.
    BroadcastQueue queue;   // the outbound queue handling this broadcast

    // if set to true, app's process will be temporarily allowed to start activities from background
    // for the duration of the broadcast dispatch
    final boolean allowBackgroundActivityStarts;
    // token used to trace back the grant for activity starts, optional
    @Nullable
    final IBinder mBackgroundActivityStartsToken;

    static final int IDLE = 0;
    static final int APP_RECEIVE = 1;
    static final int CALL_IN_RECEIVE = 2;
    static final int CALL_DONE_RECEIVE = 3;
    static final int WAITING_SERVICES = 4;

    static final int DELIVERY_PENDING = 0;
    static final int DELIVERY_DELIVERED = 1;
    static final int DELIVERY_SKIPPED = 2;
    static final int DELIVERY_TIMEOUT = 3;

    // The following are set when we are calling a receiver (one that
    // was found in our list of registered receivers).
    BroadcastFilter curFilter;

    // The following are set only when we are launching a receiver (one
    // that was found by querying the package manager).
    ProcessRecord curApp;       // hosting application of current receiver.
    ComponentName curComponent; // the receiver class that is currently running.
    ActivityInfo curReceiver;   // info about the receiver that is currently running.

    // Private refcount-management bookkeeping; start > 0
    static AtomicInteger sNextToken = new AtomicInteger(1);

    void dump(PrintWriter pw, String prefix, SimpleDateFormat sdf) {
        final long now = SystemClock.uptimeMillis();

        pw.print(prefix); pw.print(this); pw.print(" to user "); pw.println(userId);
        pw.print(prefix); pw.println(intent.toInsecureString());
        if (targetComp != null && targetComp != intent.getComponent()) {
            pw.print(prefix); pw.print("  targetComp: "); pw.println(targetComp.toShortString());
        }
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            pw.print(prefix); pw.print("  extras: "); pw.println(bundle.toString());
        }
        pw.print(prefix); pw.print("caller="); pw.print(callerPackage); pw.print(" ");
                pw.print(callerApp != null ? callerApp.toShortString() : "null");
                pw.print(" pid="); pw.print(callingPid);
                pw.print(" uid="); pw.println(callingUid);
        if ((requiredPermissions != null && requiredPermissions.length > 0)
                || appOp != AppOpsManager.OP_NONE) {
            pw.print(prefix); pw.print("requiredPermissions=");
            pw.print(Arrays.toString(requiredPermissions));
            pw.print("  appOp="); pw.println(appOp);
        }
        if (excludedPermissions != null && excludedPermissions.length > 0) {
            pw.print(prefix); pw.print("excludedPermissions=");
            pw.print(Arrays.toString(excludedPermissions));
        }
        if (options != null) {
            pw.print(prefix); pw.print("options="); pw.println(options.toBundle());
        }
        pw.print(prefix); pw.print("enqueueClockTime=");
                pw.print(sdf.format(new Date(enqueueClockTime)));
                pw.print(" dispatchClockTime=");
                pw.println(sdf.format(new Date(dispatchClockTime)));
        pw.print(prefix); pw.print("dispatchTime=");
                TimeUtils.formatDuration(dispatchTime, now, pw);
                pw.print(" (");
                TimeUtils.formatDuration(dispatchClockTime-enqueueClockTime, pw);
                pw.print(" since enq)");
        if (finishTime != 0) {
            pw.print(" finishTime="); TimeUtils.formatDuration(finishTime, now, pw);
            pw.print(" (");
            TimeUtils.formatDuration(finishTime-dispatchTime, pw);
            pw.print(" since disp)");
        } else {
            pw.print(" receiverTime="); TimeUtils.formatDuration(receiverTime, now, pw);
        }
        pw.println("");
        if (anrCount != 0) {
            pw.print(prefix); pw.print("anrCount="); pw.println(anrCount);
        }
        if (resultTo != null || resultCode != -1 || resultData != null) {
            pw.print(prefix); pw.print("resultTo="); pw.print(resultTo);
                    pw.print(" resultCode="); pw.print(resultCode);
                    pw.print(" resultData="); pw.println(resultData);
        }
        if (resultExtras != null) {
            pw.print(prefix); pw.print("resultExtras="); pw.println(resultExtras);
        }
        if (resultAbort || ordered || sticky || initialSticky) {
            pw.print(prefix); pw.print("resultAbort="); pw.print(resultAbort);
                    pw.print(" ordered="); pw.print(ordered);
                    pw.print(" sticky="); pw.print(sticky);
                    pw.print(" initialSticky="); pw.println(initialSticky);
        }
        if (nextReceiver != 0 || receiver != null) {
            pw.print(prefix); pw.print("nextReceiver="); pw.print(nextReceiver);
                    pw.print(" receiver="); pw.println(receiver);
        }
        if (curFilter != null) {
            pw.print(prefix); pw.print("curFilter="); pw.println(curFilter);
        }
        if (curReceiver != null) {
            pw.print(prefix); pw.print("curReceiver="); pw.println(curReceiver);
        }
        if (curApp != null) {
            pw.print(prefix); pw.print("curApp="); pw.println(curApp);
            pw.print(prefix); pw.print("curComponent=");
                    pw.println((curComponent != null ? curComponent.toShortString() : "--"));
            if (curReceiver != null && curReceiver.applicationInfo != null) {
                pw.print(prefix); pw.print("curSourceDir=");
                        pw.println(curReceiver.applicationInfo.sourceDir);
            }
        }
        if (state != IDLE) {
            String stateStr = " (?)";
            switch (state) {
                case APP_RECEIVE:       stateStr=" (APP_RECEIVE)"; break;
                case CALL_IN_RECEIVE:   stateStr=" (CALL_IN_RECEIVE)"; break;
                case CALL_DONE_RECEIVE: stateStr=" (CALL_DONE_RECEIVE)"; break;
                case WAITING_SERVICES:  stateStr=" (WAITING_SERVICES)"; break;
            }
            pw.print(prefix); pw.print("state="); pw.print(state); pw.println(stateStr);
        }
        final int N = receivers != null ? receivers.size() : 0;
        String p2 = prefix + "  ";
        PrintWriterPrinter printer = new PrintWriterPrinter(pw);
        for (int i = 0; i < N; i++) {
            Object o = receivers.get(i);
            pw.print(prefix);
            switch (delivery[i]) {
                case DELIVERY_PENDING:   pw.print("Pending"); break;
                case DELIVERY_DELIVERED: pw.print("Deliver"); break;
                case DELIVERY_SKIPPED:   pw.print("Skipped"); break;
                case DELIVERY_TIMEOUT:   pw.print("Timeout"); break;
                default:                 pw.print("???????"); break;
            }
            pw.print(" "); TimeUtils.formatDuration(duration[i], pw);
            pw.print(" #"); pw.print(i); pw.print(": ");
            if (o instanceof BroadcastFilter) {
                pw.println(o);
                ((BroadcastFilter) o).dumpBrief(pw, p2);
            } else if (o instanceof ResolveInfo) {
                pw.println("(manifest)");
                ((ResolveInfo) o).dump(printer, p2, 0);
            } else {
                pw.println(o);
            }
        }
    }

    BroadcastRecord(BroadcastQueue _queue,
            Intent _intent, ProcessRecord _callerApp, String _callerPackage,
            @Nullable String _callerFeatureId, int _callingPid, int _callingUid,
            boolean _callerInstantApp, String _resolvedType,
            String[] _requiredPermissions, String[] _excludedPermissions, int _appOp,
            BroadcastOptions _options, List _receivers, IIntentReceiver _resultTo, int _resultCode,
            String _resultData, Bundle _resultExtras, boolean _serialized, boolean _sticky,
            boolean _initialSticky, int _userId, boolean allowBackgroundActivityStarts,
            @Nullable IBinder backgroundActivityStartsToken, boolean timeoutExempt) {
        if (_intent == null) {
            throw new NullPointerException("Can't construct with a null intent");
        }
        queue = _queue;
        intent = _intent;
        targetComp = _intent.getComponent();
        callerApp = _callerApp;
        callerPackage = _callerPackage;
        callerFeatureId = _callerFeatureId;
        callingPid = _callingPid;
        callingUid = _callingUid;
        callerInstantApp = _callerInstantApp;
        resolvedType = _resolvedType;
        requiredPermissions = _requiredPermissions;
        excludedPermissions = _excludedPermissions;
        appOp = _appOp;
        options = _options;
        receivers = _receivers;
        delivery = new int[_receivers != null ? _receivers.size() : 0];
        duration = new long[delivery.length];
        resultTo = _resultTo;
        resultCode = _resultCode;
        resultData = _resultData;
        resultExtras = _resultExtras;
        ordered = _serialized;
        sticky = _sticky;
        initialSticky = _initialSticky;
        userId = _userId;
        nextReceiver = 0;
        state = IDLE;
        this.allowBackgroundActivityStarts = allowBackgroundActivityStarts;
        mBackgroundActivityStartsToken = backgroundActivityStartsToken;
        this.timeoutExempt = timeoutExempt;
    }

    /**
     * Copy constructor which takes a different intent.
     * Only used by {@link #maybeStripForHistory}.
     */
    private BroadcastRecord(BroadcastRecord from, Intent newIntent) {
        intent = newIntent;
        targetComp = newIntent.getComponent();

        callerApp = from.callerApp;
        callerPackage = from.callerPackage;
        callerFeatureId = from.callerFeatureId;
        callingPid = from.callingPid;
        callingUid = from.callingUid;
        callerInstantApp = from.callerInstantApp;
        ordered = from.ordered;
        sticky = from.sticky;
        initialSticky = from.initialSticky;
        userId = from.userId;
        resolvedType = from.resolvedType;
        requiredPermissions = from.requiredPermissions;
        excludedPermissions = from.excludedPermissions;
        appOp = from.appOp;
        options = from.options;
        receivers = from.receivers;
        delivery = from.delivery;
        duration = from.duration;
        resultTo = from.resultTo;
        enqueueClockTime = from.enqueueClockTime;
        dispatchTime = from.dispatchTime;
        dispatchClockTime = from.dispatchClockTime;
        receiverTime = from.receiverTime;
        finishTime = from.finishTime;
        resultCode = from.resultCode;
        resultData = from.resultData;
        resultExtras = from.resultExtras;
        resultAbort = from.resultAbort;
        nextReceiver = from.nextReceiver;
        receiver = from.receiver;
        state = from.state;
        anrCount = from.anrCount;
        manifestCount = from.manifestCount;
        manifestSkipCount = from.manifestSkipCount;
        queue = from.queue;
        allowBackgroundActivityStarts = from.allowBackgroundActivityStarts;
        mBackgroundActivityStartsToken = from.mBackgroundActivityStartsToken;
        timeoutExempt = from.timeoutExempt;
    }

    /**
     * Split off a new BroadcastRecord that clones this one, but contains only the
     * recipient records for the current (just-finished) receiver's app, starting
     * after the just-finished receiver [i.e. at r.nextReceiver].  Returns null
     * if there are no matching subsequent receivers in this BroadcastRecord.
     */
    BroadcastRecord splitRecipientsLocked(int slowAppUid, int startingAt) {
        // Do we actually have any matching receivers down the line...?
        ArrayList splitReceivers = null;
        for (int i = startingAt; i < receivers.size(); ) {
            Object o = receivers.get(i);
            if (getReceiverUid(o) == slowAppUid) {
                if (splitReceivers == null) {
                    splitReceivers = new ArrayList<>();
                }
                splitReceivers.add(o);
                receivers.remove(i);
            } else {
                i++;
            }
        }

        // No later receivers in the same app, so we have no more to do
        if (splitReceivers == null) {
            return null;
        }

        // build a new BroadcastRecord around that single-target list
        BroadcastRecord split = new BroadcastRecord(queue, intent, callerApp, callerPackage,
                callerFeatureId, callingPid, callingUid, callerInstantApp, resolvedType,
                requiredPermissions, excludedPermissions, appOp, options, splitReceivers, resultTo,
                resultCode, resultData, resultExtras, ordered, sticky, initialSticky, userId,
                allowBackgroundActivityStarts, mBackgroundActivityStartsToken, timeoutExempt);

        split.splitToken = this.splitToken;
        return split;
    }

    int getReceiverUid(Object receiver) {
        if (receiver instanceof BroadcastFilter) {
            return ((BroadcastFilter) receiver).owningUid;
        } else /* if (receiver instanceof ResolveInfo) */ {
            return ((ResolveInfo) receiver).activityInfo.applicationInfo.uid;
        }
    }

    public BroadcastRecord maybeStripForHistory() {
        if (!intent.canStripForHistory()) {
            return this;
        }
        return new BroadcastRecord(this, intent.maybeStripForHistory());
    }

    @VisibleForTesting
    boolean cleanupDisabledPackageReceiversLocked(
            String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        if (receivers == null) {
            return false;
        }

        final boolean cleanupAllUsers = userId == UserHandle.USER_ALL;
        final boolean sendToAllUsers = this.userId == UserHandle.USER_ALL;
        if (this.userId != userId && !cleanupAllUsers && !sendToAllUsers) {
            return false;
        }

        boolean didSomething = false;
        Object o;
        for (int i = receivers.size() - 1; i >= 0; i--) {
            o = receivers.get(i);
            if (!(o instanceof ResolveInfo)) {
                continue;
            }
            ActivityInfo info = ((ResolveInfo)o).activityInfo;

            final boolean sameComponent = packageName == null
                    || (info.applicationInfo.packageName.equals(packageName)
                    && (filterByClasses == null || filterByClasses.contains(info.name)));
            if (sameComponent && (cleanupAllUsers
                    || UserHandle.getUserId(info.applicationInfo.uid) == userId)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                receivers.remove(i);
                if (i < nextReceiver) {
                    nextReceiver--;
                }
            }
        }
        nextReceiver = Math.min(nextReceiver, receivers.size());

        return didSomething;
    }

    @Override
    public String toString() {
        return "BroadcastRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " u" + userId + " " + intent.getAction() + "}";
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(BroadcastRecordProto.USER_ID, userId);
        proto.write(BroadcastRecordProto.INTENT_ACTION, intent.getAction());
        proto.end(token);
    }
}
