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

package android.app;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.window.IWindowContainerToken;
import android.window.WindowContainerToken;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Stores information about a particular Task.
 */
public class TaskInfo {
    private static final String TAG = "TaskInfo";

    /**
     * The value to use when the property has not a specific value.
     * @hide
     */
    public static final int PROPERTY_VALUE_UNSET = -1;

    /**
     * Self-movable state is not set.
     *
     * @hide
     */
    public static final int SELF_MOVABLE_UNSET = -1;

    /**
     * Self-movable state is not defined. WM core uses freeform windowing mode to decide.
     *
     * @hide
     */
    public static final int SELF_MOVABLE_DEFAULT = 0;

    /**
     * Self-moving is allowed. Note that there are permission checks in addition to this flag for
     * apps calling {@link android.app.ActivityManager.AppTask#moveTaskToFront}.
     *
     * @hide
     */
    public static final int SELF_MOVABLE_ALLOWED = 1;

    /**
     * Self-moving is denied.
     *
     * @hide
     */
    public static final int SELF_MOVABLE_DENIED = 2;

    /** @hide */
    @IntDef(
            prefix = {"SELF_MOVABLE_"},
            value = {
                SELF_MOVABLE_UNSET,
                SELF_MOVABLE_DEFAULT,
                SELF_MOVABLE_ALLOWED,
                SELF_MOVABLE_DENIED,
            })
    public @interface SelfMovable {}

    /**
     * The id of the user the task was running as if this is a leaf task. The id of the current
     * running user of the system otherwise.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int userId;

    /**
     * The identifier for this task.
     */
    public int taskId;

    /**
     * The current effective uid of the identity of this task.
     * @hide
     */
    public int effectiveUid;

    /**
     * Whether or not this task has any running activities.
     */
    public boolean isRunning;

    /**
     * The base intent of the task (generally the intent that launched the task). This intent can
     * be used to relaunch the task (if it is no longer running) or brought to the front if it is.
     */
    @NonNull
    public Intent baseIntent;

    /**
     * The component of the first activity in the task, can be considered the "application" of this
     * task.
     */
    @Nullable
    public ComponentName baseActivity;

    /**
     * The component of the top activity in the task, currently showing to the user.
     */
    @Nullable
    public ComponentName topActivity;

    /**
     * The component of the target activity if this task was started from an activity alias.
     * Otherwise, this is null.
     */
    @Nullable
    public ComponentName origActivity;

    /**
     * The component of the activity that started this task (may be the component of the activity
     * alias).
     * @hide
     */
    @Nullable
    public ComponentName realActivity;

    /**
     * Whether the package of {@link #realActivity} has App Lock enabled.
     * @hide
     */
    public boolean isRealActivityAppLockEnabled;

    /**
     * The number of activities in this task (including running).
     */
    public int numActivities;

    /**
     * The last time this task was active since boot (including time spent in sleep).
     * @hide
     */
    @UnsupportedAppUsage
    public long lastActiveTime;

    /**
     * The id of the display this task is associated with.
     * @hide
     */
    public int displayId;

    /**
     * The feature id of {@link com.android.server.wm.TaskDisplayArea} this task is associated with.
     * @hide
     */
    public int displayAreaFeatureId = FEATURE_UNDEFINED;

    /**
     * The recent activity values for the highest activity in the stack to have set the values.
     * {@link Activity#setTaskDescription(android.app.ActivityManager.TaskDescription)}.
     */
    @Nullable
    public ActivityManager.TaskDescription taskDescription;

    /**
     * The locusId of the task.
     * @hide
     */
    @Nullable
    public LocusId mTopActivityLocusId;

    /**
     * Whether this task supports multi windowing modes based on the device settings and the
     * root activity resizability and configuration.
     * @hide
     */
    public boolean supportsMultiWindow;

    /**
     * whether this task supports multi-window based on its resize mode,
     * ignoring appCompat overrides and minimum size constraints.
     * @hide
     */
    public boolean supportsMultiWindowWithoutConstraints;

    /**
     * The resize mode of the task. See {@link ActivityInfo#resizeMode}.
     * @hide
     */
    @UnsupportedAppUsage
    public int resizeMode;

    /**
     * The current configuration of the task.
     * @hide
     */
    @NonNull
    @UnsupportedAppUsage
    public final Configuration configuration = new Configuration();

    /**
     * Used as an opaque identifier for this task.
     * @hide
     */
    @NonNull
    public WindowContainerToken token;

    /**
     * The PictureInPictureParams for the Task, if set.
     * @hide
     */
    @Nullable
    public PictureInPictureParams pictureInPictureParams;

    /**
     * @hide
     */
    public boolean shouldDockBigOverlays;

    /**
     * The task id of the host Task of the launch-into-pip Activity, i.e., it points to the Task
     * the launch-into-pip Activity is originated from.
     * @hide
     */
    public int launchIntoPipHostTaskId;

    /**
     * The task id of the parent Task of the launch-into-pip Activity, i.e., if task have more than
     * one activity it will create new task for this activity, this id is the origin task id and
     * the pip activity will be reparent to origin task when it exit pip mode.
     * @hide
     */
    public int lastParentTaskIdBeforePip;

    /**
     * The {@link Rect} copied from {@link DisplayCutout#getSafeInsets()} if the cutout is not of
     * (LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS),
     * {@code null} otherwise.
     * @hide
     */
    @Nullable
    public Rect displayCutoutInsets;

    /**
     * The activity type of the top activity in this task.
     * @hide
     */
    public @WindowConfiguration.ActivityType int topActivityType;

    /**
     * The {@link ActivityInfo} of the top activity in this task.
     * @hide
     */
    @Nullable
    public ActivityInfo topActivityInfo;

    /**
     * Whether this task is resizable. Unlike {@link #resizeMode} (which is what the top activity
     * supports), this is what the system actually uses for resizability based on other policy and
     * developer options.
     * @hide
     */
    public boolean isResizeable;

    /**
     * Minimal width of the task when it's resizeable.
     * @hide
     */
    public int minWidth;

    /**
     * Minimal height of the task when it's resizeable.
     * @hide
     */
    public int minHeight;

    /**
     * The default minimal size of the task used when a minWidth or minHeight is not specified.
     * @hide
     */
    public int defaultMinSize;

    /**
     * Relative position of the task's top left corner in the parent container.
     * @hide
     */
    public Point positionInParent;

    /**
     * The launch cookies associated with activities in this task if any.
     * @see ActivityOptions#setLaunchCookie(IBinder)
     * @hide
     */
    public ArrayList<IBinder> launchCookies = new ArrayList<>();

    /**
     * The identifier of the parent task that is created by organizer, otherwise
     * {@link ActivityTaskManager#INVALID_TASK_ID}.
     * @hide
     */
    public int parentTaskId;

    /**
     * Whether this task is focused on the display. This means the task receives input events that
     * target the display.
     * CAUTION: This can be true for multiple tasks especially when multiple displays are connected
     * in the system.
     * @hide
     */
    public boolean isFocused;

    /**
     * Represents {@link com.android.server.wm.Task}'s state where it's activity might be resumed or
     * would be resumed.
     * <p>
     * It doesn't guarantee that the topmost activity is resumed at the given point of time, but it
     * guarantees that with current task hierarchy it would be resumed when all hierarchy operations
     * are settled down.
     *
     * @hide
     */
    public boolean isInteractive;

    /**
     * Whether this task is visible.
     * @hide
     */
    public boolean isVisible;

    /**
     * Whether this task is request visible.
     * @hide
     */
    public boolean isVisibleRequested;

    /**
     * Whether the top activity is to be displayed. See {@link android.R.attr#windowNoDisplay}.
     * @hide
     */
    public boolean isTopActivityNoDisplay;

    /**
     * Whether this task is sleeping due to sleeping display.
     * @hide
     */
    public boolean isSleeping;

    /**
     * Whether the top activity fillsParent() is false.
     * @hide
     */
    public boolean isTopActivityTransparent;

    /**
     * Whether fillsParent() is false for every activity in the tasks stack.
     * @hide
     */
    public boolean isActivityStackTransparent;

    /**
     * The last non-fullscreen bounds the task was launched in or resized to.
     * @hide
     */
    @Nullable
    public Rect lastNonFullscreenBounds;

    /**
     * Whether the tasks bounds of a leaf task have been set from the activity options.
     * @hide
     */
    public boolean leafTaskBoundsFromOptions;

    /**
     * The URI of the intent that generated the top-most activity opened using a URL.
     * @hide
     */
    @Nullable
    public Uri capturedLink;

    /**
     * The time of the last launch of the activity opened using the {@link #capturedLink}.
     * @hide
     */
    public long capturedLinkTimestamp;

    /**
     * The requested visible types of insets.
     * @hide
     */
    @WindowInsets.Type.InsetsType
    public int requestedVisibleTypes;

    /**
     * The timestamp of the top activity's last request to show the "Open in Browser" education.
     * @hide
     */
    public long topActivityRequestOpenInBrowserEducationTimestamp;

    /**
     * Encapsulate specific App Compat information.
     * @hide
     */
    public AppCompatTaskInfo appCompatTaskInfo = AppCompatTaskInfo.create();

    /**
     * Whether the Task should be an App Bubble.
     * Please use this with caution. This is just a short-term solution which should be migrated
     * to a more generic model vs. implying the Task is an App Bubble here.
     *
     * @deprecated use {@link com.android.wm.shell.bubbles.BubbleHelper#isAppBubbleTask}
     * @hide
     *
     * TODO(b/407669465): remove it once migrated to the new approach
     */
    @Deprecated
    public boolean isAppBubble;

    /**
     * The top activity's main window frame if it doesn't match the top activity bounds.
     * {@code null}, otherwise.
     *
     * @hide
     */
    @Nullable
    public Rect topActivityMainWindowFrame;

    TaskInfo() {
        // Do nothing
    }

    /**
     * @hide
     */
    TaskInfo(@NonNull TaskInfo other) {
        userId = other.userId;
        taskId = other.taskId;
        effectiveUid = other.effectiveUid;
        displayId = other.displayId;
        isRunning = other.isRunning;
        baseIntent = other.baseIntent != null ? new Intent(other.baseIntent) : null;
        baseActivity = other.baseActivity != null ? other.baseActivity.clone() : null;
        topActivity = other.topActivity != null ? other.topActivity.clone() : null;
        origActivity = other.origActivity != null ? other.origActivity.clone() : null;
        realActivity = other.realActivity != null ? other.realActivity.clone() : null;
        isRealActivityAppLockEnabled = other.isRealActivityAppLockEnabled;
        numActivities = other.numActivities;
        lastActiveTime = other.lastActiveTime;
        taskDescription = other.taskDescription != null
                ? new ActivityManager.TaskDescription(other.taskDescription)
                : null;
        supportsMultiWindow = other.supportsMultiWindow;
        supportsMultiWindowWithoutConstraints = other.supportsMultiWindowWithoutConstraints;
        resizeMode = other.resizeMode;
        configuration.setTo(other.configuration);
        token = other.token != null
                ? new WindowContainerToken(
                        IWindowContainerToken.Stub.asInterface(other.token.asBinder()))
                : null;
        topActivityType = other.topActivityType;
        pictureInPictureParams = other.pictureInPictureParams != null
                ? new PictureInPictureParams(other.pictureInPictureParams)
                : null;
        shouldDockBigOverlays = other.shouldDockBigOverlays;
        launchIntoPipHostTaskId = other.launchIntoPipHostTaskId;
        lastParentTaskIdBeforePip = other.lastParentTaskIdBeforePip;
        displayCutoutInsets = other.displayCutoutInsets != null
                ? new Rect(other.displayCutoutInsets)
                : null;
        topActivityInfo = other.topActivityInfo != null
                ? new ActivityInfo(other.topActivityInfo)
                : null;
        isResizeable = other.isResizeable;
        minWidth = other.minWidth;
        minHeight = other.minHeight;
        defaultMinSize = other.defaultMinSize;
        launchCookies = other.launchCookies != null
                ? new ArrayList<>(other.launchCookies)
                : null;
        positionInParent = other.positionInParent != null
                ? new Point(other.positionInParent)
                : null;
        parentTaskId = other.parentTaskId;
        isFocused = other.isFocused;
        isVisible = other.isVisible;
        isVisibleRequested = other.isVisibleRequested;
        isTopActivityNoDisplay = other.isTopActivityNoDisplay;
        isSleeping = other.isSleeping;
        mTopActivityLocusId = other.mTopActivityLocusId != null
                ? new LocusId(other.mTopActivityLocusId.getId())
                : null;
        displayAreaFeatureId = other.displayAreaFeatureId;
        isTopActivityTransparent = other.isTopActivityTransparent;
        isActivityStackTransparent = other.isActivityStackTransparent;
        lastNonFullscreenBounds = other.lastNonFullscreenBounds != null
                ? new Rect(other.lastNonFullscreenBounds)
                : null;
        leafTaskBoundsFromOptions = other.leafTaskBoundsFromOptions;
        capturedLink = other.capturedLink != null ? Uri.parse(other.capturedLink.toString()) : null;
        capturedLinkTimestamp = other.capturedLinkTimestamp;
        requestedVisibleTypes = other.requestedVisibleTypes;
        topActivityRequestOpenInBrowserEducationTimestamp
                = other.topActivityRequestOpenInBrowserEducationTimestamp;
        appCompatTaskInfo = other.appCompatTaskInfo != null
                ? new AppCompatTaskInfo(other.appCompatTaskInfo)
                : null;
        topActivityMainWindowFrame = other.topActivityMainWindowFrame != null
                ? new Rect(other.topActivityMainWindowFrame)
                : null;
        isAppBubble = other.isAppBubble;
        isInteractive = other.isInteractive;
    }

    /** @hide */
    public TaskInfo(Parcel source) {
        readTaskFromParcel(source);
    }

    /**
     * Returns the task id.
     * @hide
     */
    public int getTaskId() {
        return taskId;
    }

    /**
     * Whether this task is visible.
     */
    public boolean isVisible() {
        return isVisible;
    }

    /** @hide */
    @NonNull
    @TestApi
    public WindowContainerToken getToken() {
        return token;
    }

    /** @hide */
    @NonNull
    @TestApi
    public Configuration getConfiguration() {
        return configuration;
    }

    /** @hide */
    @Nullable
    @TestApi
    public PictureInPictureParams getPictureInPictureParams() {
        return pictureInPictureParams;
    }

    /** @hide */
    @TestApi
    public boolean shouldDockBigOverlays() {
        return shouldDockBigOverlays;
    }

    /** @hide */
    @WindowConfiguration.WindowingMode
    public int getWindowingMode() {
        return configuration.windowConfiguration.getWindowingMode();
    }

    /** @hide */
    public boolean isFreeform() {
        return configuration.windowConfiguration.getWindowingMode()
                == WindowConfiguration.WINDOWING_MODE_FREEFORM;
    }

    /** @hide */
    @WindowConfiguration.ActivityType
    public int getActivityType() {
        return configuration.windowConfiguration.getActivityType();
    }

    /** @hide */
    public void addLaunchCookie(IBinder cookie) {
        if (cookie == null || launchCookies.contains(cookie)) return;
        launchCookies.add(cookie);
    }

    /**
     * @return {@code true} if this task contains the launch cookie.
     * @hide
     */
    @TestApi
    public boolean containsLaunchCookie(@NonNull IBinder cookie) {
        return launchCookies.contains(cookie);
    }

    /**
     * @return The parent task id of this task.
     * @hide
     */
    @TestApi
    public int getParentTaskId() {
        return parentTaskId;
    }

    /** @hide */
    @TestApi
    public boolean hasParentTask() {
        return parentTaskId != INVALID_TASK_ID;
    }

    /**
     * @return The id of the display this task is associated with.
     * @hide
     */
    @TestApi
    public int getDisplayId() {
        return displayId;
    }

    /**
     * Returns {@code true} if the parameters that are important for task organizers are equal
     * between this {@link TaskInfo} and {@code that}.
     * @hide
     */
    public boolean equalsForTaskOrganizer(@Nullable TaskInfo that) {
        if (that == null) {
            return false;
        }
        return topActivityType == that.topActivityType
                && isResizeable == that.isResizeable
                && supportsMultiWindow == that.supportsMultiWindow
                && supportsMultiWindowWithoutConstraints
                == that.supportsMultiWindowWithoutConstraints
                && displayAreaFeatureId == that.displayAreaFeatureId
                && Objects.equals(positionInParent, that.positionInParent)
                && Objects.equals(pictureInPictureParams, that.pictureInPictureParams)
                && Objects.equals(shouldDockBigOverlays, that.shouldDockBigOverlays)
                && Objects.equals(displayCutoutInsets, that.displayCutoutInsets)
                && getWindowingMode() == that.getWindowingMode()
                && configuration.uiMode == that.configuration.uiMode
                && configuration.assetsSeq == that.configuration.assetsSeq
                && Objects.equals(taskDescription, that.taskDescription)
                && isFocused == that.isFocused
                && isVisible == that.isVisible
                && isVisibleRequested == that.isVisibleRequested
                && isTopActivityNoDisplay == that.isTopActivityNoDisplay
                && isSleeping == that.isSleeping
                && Objects.equals(mTopActivityLocusId, that.mTopActivityLocusId)
                && parentTaskId == that.parentTaskId
                && Objects.equals(topActivity, that.topActivity)
                && isTopActivityTransparent == that.isTopActivityTransparent
                && isActivityStackTransparent == that.isActivityStackTransparent
                && Objects.equals(lastNonFullscreenBounds, that.lastNonFullscreenBounds)
                && leafTaskBoundsFromOptions == that.leafTaskBoundsFromOptions
                && Objects.equals(capturedLink, that.capturedLink)
                && capturedLinkTimestamp == that.capturedLinkTimestamp
                && requestedVisibleTypes == that.requestedVisibleTypes
                && topActivityRequestOpenInBrowserEducationTimestamp
                    == that.topActivityRequestOpenInBrowserEducationTimestamp
                && appCompatTaskInfo.equalsForTaskOrganizer(that.appCompatTaskInfo)
                && Objects.equals(topActivityMainWindowFrame, that.topActivityMainWindowFrame)
                && isAppBubble == that.isAppBubble
                && minWidth == that.minWidth && minHeight == that.minHeight;
    }

    /**
     * @return {@code true} if parameters that are important for size compat have changed.
     * @hide
     */
    public boolean equalsForCompatUi(@Nullable TaskInfo that) {
        if (that == null) {
            return false;
        }
        final boolean hasCompatUI = appCompatTaskInfo.hasCompatUI();
        return displayId == that.displayId
                && taskId == that.taskId
                && isFocused == that.isFocused
                && isTopActivityTransparent == that.isTopActivityTransparent
                && appCompatTaskInfo.equalsForCompatUi(that.appCompatTaskInfo)
                // Bounds are important if top activity has compat controls.
                && (!hasCompatUI || configuration.windowConfiguration.getBounds()
                    .equals(that.configuration.windowConfiguration.getBounds()))
                && (!hasCompatUI || configuration.getLayoutDirection()
                    == that.configuration.getLayoutDirection())
                && (!hasCompatUI || configuration.uiMode == that.configuration.uiMode)
                && (!hasCompatUI || isVisible == that.isVisible);
    }

    /**
     * Reads the TaskInfo from a parcel.
     */
    void readTaskFromParcel(Parcel source) {
        userId = source.readInt();
        taskId = source.readInt();
        effectiveUid = source.readInt();
        displayId = source.readInt();
        isRunning = source.readBoolean();
        baseIntent = source.readTypedObject(Intent.CREATOR);
        baseActivity = ComponentName.readFromParcel(source);
        topActivity = ComponentName.readFromParcel(source);
        origActivity = ComponentName.readFromParcel(source);
        realActivity = ComponentName.readFromParcel(source);
        isRealActivityAppLockEnabled = source.readBoolean();
        numActivities = source.readInt();
        lastActiveTime = source.readLong();
        taskDescription = source.readTypedObject(ActivityManager.TaskDescription.CREATOR);
        supportsMultiWindow = source.readBoolean();
        supportsMultiWindowWithoutConstraints = source.readBoolean();
        resizeMode = source.readInt();
        configuration.readFromParcel(source);
        token = WindowContainerToken.CREATOR.createFromParcel(source);
        topActivityType = source.readInt();
        pictureInPictureParams = source.readTypedObject(PictureInPictureParams.CREATOR);
        shouldDockBigOverlays = source.readBoolean();
        launchIntoPipHostTaskId = source.readInt();
        lastParentTaskIdBeforePip = source.readInt();
        displayCutoutInsets = source.readTypedObject(Rect.CREATOR);
        topActivityInfo = source.readTypedObject(ActivityInfo.CREATOR);
        isResizeable = source.readBoolean();
        minWidth = source.readInt();
        minHeight = source.readInt();
        defaultMinSize = source.readInt();
        source.readBinderList(launchCookies);
        positionInParent = source.readTypedObject(Point.CREATOR);
        parentTaskId = source.readInt();
        isFocused = source.readBoolean();
        isVisible = source.readBoolean();
        isVisibleRequested = source.readBoolean();
        isTopActivityNoDisplay = source.readBoolean();
        isSleeping = source.readBoolean();
        mTopActivityLocusId = source.readTypedObject(LocusId.CREATOR);
        displayAreaFeatureId = source.readInt();
        isTopActivityTransparent = source.readBoolean();
        isActivityStackTransparent = source.readBoolean();
        lastNonFullscreenBounds = source.readTypedObject(Rect.CREATOR);
        leafTaskBoundsFromOptions = source.readBoolean();
        capturedLink = source.readTypedObject(Uri.CREATOR);
        capturedLinkTimestamp = source.readLong();
        requestedVisibleTypes = source.readInt();
        topActivityRequestOpenInBrowserEducationTimestamp = source.readLong();
        appCompatTaskInfo = source.readTypedObject(AppCompatTaskInfo.CREATOR);
        topActivityMainWindowFrame = source.readTypedObject(Rect.CREATOR);
        isAppBubble = source.readBoolean();
        isInteractive = source.readBoolean();
    }

    /**
     * Writes the TaskInfo to a parcel.
     * @hide
     */
    public void writeTaskToParcel(Parcel dest, int flags) {
        dest.writeInt(userId);
        dest.writeInt(taskId);
        dest.writeInt(effectiveUid);
        dest.writeInt(displayId);
        dest.writeBoolean(isRunning);
        dest.writeTypedObject(baseIntent, 0);

        ComponentName.writeToParcel(baseActivity, dest);
        ComponentName.writeToParcel(topActivity, dest);
        ComponentName.writeToParcel(origActivity, dest);
        ComponentName.writeToParcel(realActivity, dest);
        dest.writeBoolean(isRealActivityAppLockEnabled);

        dest.writeInt(numActivities);
        dest.writeLong(lastActiveTime);

        dest.writeTypedObject(taskDescription, flags);
        dest.writeBoolean(supportsMultiWindow);
        dest.writeBoolean(supportsMultiWindowWithoutConstraints);
        dest.writeInt(resizeMode);
        configuration.writeToParcel(dest, flags);
        token.writeToParcel(dest, flags);
        dest.writeInt(topActivityType);
        dest.writeTypedObject(pictureInPictureParams, flags);
        dest.writeBoolean(shouldDockBigOverlays);
        dest.writeInt(launchIntoPipHostTaskId);
        dest.writeInt(lastParentTaskIdBeforePip);
        dest.writeTypedObject(displayCutoutInsets, flags);
        dest.writeTypedObject(topActivityInfo, flags);
        dest.writeBoolean(isResizeable);
        dest.writeInt(minWidth);
        dest.writeInt(minHeight);
        dest.writeInt(defaultMinSize);
        dest.writeBinderList(launchCookies);
        dest.writeTypedObject(positionInParent, flags);
        dest.writeInt(parentTaskId);
        dest.writeBoolean(isFocused);
        dest.writeBoolean(isVisible);
        dest.writeBoolean(isVisibleRequested);
        dest.writeBoolean(isTopActivityNoDisplay);
        dest.writeBoolean(isSleeping);
        dest.writeTypedObject(mTopActivityLocusId, flags);
        dest.writeInt(displayAreaFeatureId);
        dest.writeBoolean(isTopActivityTransparent);
        dest.writeBoolean(isActivityStackTransparent);
        dest.writeTypedObject(lastNonFullscreenBounds, flags);
        dest.writeBoolean(leafTaskBoundsFromOptions);
        dest.writeTypedObject(capturedLink, flags);
        dest.writeLong(capturedLinkTimestamp);
        dest.writeInt(requestedVisibleTypes);
        dest.writeLong(topActivityRequestOpenInBrowserEducationTimestamp);
        dest.writeTypedObject(appCompatTaskInfo, flags);
        dest.writeTypedObject(topActivityMainWindowFrame, flags);
        dest.writeBoolean(isAppBubble);
        dest.writeBoolean(isInteractive);
    }

    @Override
    public String toString() {
        return "TaskInfo{userId=" + userId + " taskId=" + taskId
                + " effectiveUid=" + effectiveUid
                + " displayId=" + displayId
                + " isRunning=" + isRunning
                + " baseIntent=" + baseIntent
                + " baseActivity=" + baseActivity
                + " topActivity=" + topActivity
                + " origActivity=" + origActivity
                + " realActivity=" + realActivity
                + " realActivityIsAppLockEnabled=" + isRealActivityAppLockEnabled
                + " numActivities=" + numActivities
                + " lastActiveTime=" + lastActiveTime
                + " supportsMultiWindow=" + supportsMultiWindow
                + " supportsMultiWindowWithoutConstraints=" + supportsMultiWindowWithoutConstraints
                + " resizeMode=" + resizeMode
                + " isResizeable=" + isResizeable
                + " minWidth=" + minWidth
                + " minHeight=" + minHeight
                + " defaultMinSize=" + defaultMinSize
                + " token=" + token
                + " topActivityType=" + topActivityType
                + " pictureInPictureParams=" + pictureInPictureParams
                + " shouldDockBigOverlays=" + shouldDockBigOverlays
                + " launchIntoPipHostTaskId=" + launchIntoPipHostTaskId
                + " lastParentTaskIdBeforePip=" + lastParentTaskIdBeforePip
                + " displayCutoutSafeInsets=" + displayCutoutInsets
                + " topActivityInfo=" + topActivityInfo
                + " launchCookies=" + launchCookies
                + " positionInParent=" + positionInParent
                + " parentTaskId=" + parentTaskId
                + " isFocused=" + isFocused
                + " isInteractive=" + isInteractive
                + " isVisible=" + isVisible
                + " isVisibleRequested=" + isVisibleRequested
                + " isTopActivityNoDisplay=" + isTopActivityNoDisplay
                + " isSleeping=" + isSleeping
                + " locusId=" + mTopActivityLocusId
                + " displayAreaFeatureId=" + displayAreaFeatureId
                + " isTopActivityTransparent=" + isTopActivityTransparent
                + " isActivityStackTransparent=" + isActivityStackTransparent
                + " lastNonFullscreenBounds=" + lastNonFullscreenBounds
                + " leafTaskBoundsFromOptions= " + leafTaskBoundsFromOptions
                + " capturedLink=" + capturedLink
                + " capturedLinkTimestamp=" + capturedLinkTimestamp
                + " requestedVisibleTypes=" + requestedVisibleTypes
                + " topActivityRequestOpenInBrowserEducationTimestamp="
                + topActivityRequestOpenInBrowserEducationTimestamp
                + " appCompatTaskInfo=" + appCompatTaskInfo
                + " topActivityMainWindowFrame=" + topActivityMainWindowFrame
                + " isAppBubble=" + isAppBubble
                + "}";
    }
}
