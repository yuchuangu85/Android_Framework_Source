/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.window;

import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.transitTypeToString;

import android.annotation.Nullable;
import android.app.Activity.FullscreenModeRequest;
import android.app.ActivityManager;
import android.app.ActivityManager.AppTask.WindowingLayer;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.IRemoteCallback;
import android.os.Parcelable;
import android.view.InsetsState;
import android.view.WindowManager;

import com.android.internal.util.DataClass;

/**
 * Used to communicate information about what is changing during a transition to a TransitionPlayer.
 * @hide
 */
@DataClass(genToString = true, genSetters = true, genAidl = true)
public final class TransitionRequestInfo implements Parcelable {

    /** The type of the transition being requested. */
    private final @WindowManager.TransitionType int mType;

    /**
     * If non-null, the task containing the activity whose lifecycle change (start or
     * finish) has caused this transition to occur.
     */
    private @Nullable ActivityManager.RunningTaskInfo mTriggerTask;

    /**
     * If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     * {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     */
    private @Nullable TransitionRequestInfo.PipChange mPipChange;

    /** If non-null, a remote-transition associated with the source of this transition. */
    private @Nullable TransitionRequestInfo.RemoteTransitionInfo mRemoteTransitionInfo;

    /**
     * If non-null, this request was triggered by this display change. This will not be complete:
     * The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     * (if size is changing).
     */
    private @Nullable TransitionRequestInfo.DisplayChange mDisplayChange;

    /**
     * If non-null, this request was triggered by an app's request to move the trigger task.
     */
    private @Nullable TransitionRequestInfo.RequestedLocation mRequestedLocation;

    /**
     * If non-null, this request was triggered by a request to change the current user.
     */
    private @Nullable TransitionRequestInfo.UserChange mUserChange;

    /**
     * If non-null, this request was triggered by an app's request to change trigger task's
     * windowing layer.
     */
    private @Nullable TransitionRequestInfo.WindowingLayerChange mWindowingLayerChange;

    /**
     * If non-null, this request was triggered by an app's request to enter/exit fullscreen mode.
     */
    private @Nullable TransitionRequestInfo.FullscreenRequestChange mFullscreenRequestChange;

    /** The transition flags known at the time of the request. These may not be complete. */
    private final int mFlags;

    /** This is only a BEST-EFFORT id used for log correlation. DO NOT USE for any real work! */
    private final int mDebugId;

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable RemoteTransitionInfo remoteTransition) {
        this(type, triggerTask, null /* pipChange */, remoteTransition, null /* displayChange */,
                null /* requestedLocation */, null /* userChange */,
                null /* windowingLayerChange */, null /* fullscreenRequestChange */,
                0 /* flags */, -1 /* debugId */);
    }

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable RemoteTransitionInfo remoteTransition,
            int flags) {
        this(type, triggerTask, null /* pipChange */, remoteTransition, null /* displayChange */,
                null /* requestedLocation */, null /* userChange */,
                null /* windowingLayerChange */, null /* fullscreenRequestChange */,
                flags, -1 /* debugId */);
    }

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable RemoteTransitionInfo remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange,
            int flags) {
        this(type, triggerTask, null /* pipChange */, remoteTransition, displayChange,
                null /* requestedLocation */, null /* userChange */,
                null /* windowingLayerChange */, null /* fullscreenRequestChange */,
                flags, -1 /* debugId */);
    }

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable ActivityManager.RunningTaskInfo pipTask,
            @Nullable RemoteTransitionInfo remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange,
            int flags) {
        this(type, triggerTask,
                pipTask != null ? new TransitionRequestInfo.PipChange(pipTask) : null,
                remoteTransition, displayChange, null /* requestedLocation */,
                null /* userChange */, null /* windowingLayerChange */,
                null /* fullscreenRequestChange */, flags, -1 /* debugId */);
    }

    /** @hide */
    String typeToString() {
        return transitTypeToString(mType);
    }

    /** Get RemoteTransition info as a RemoteTransition object */
    public RemoteTransition getRemoteTransition() {
        if (mRemoteTransitionInfo == null) return null;
        return new RemoteTransition(mRemoteTransitionInfo.getRemoteTransition(),
                null /* appThread */, mRemoteTransitionInfo.getDebugName(),
                mRemoteTransitionInfo.getFilter());
    }

    /** Set RemoteTransition info from a RemoteTransition object */
    public void setRemoteTransition(RemoteTransition remoteTransition) {
        if (remoteTransition == null) {
            mRemoteTransitionInfo = null;
            return;
        }
        mRemoteTransitionInfo = new RemoteTransitionInfo(remoteTransition);
    }

    /** Requested change to a display. */
    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class DisplayChange implements Parcelable {
        private final int mDisplayId;

        /** If non-null, these bounds changes should ignore any potential rotation changes. */
        @Nullable private Rect mStartAbsBounds = null;
        @Nullable private Rect mEndAbsBounds = null;

        private int mStartRotation = WindowConfiguration.ROTATION_UNDEFINED;
        private int mEndRotation = WindowConfiguration.ROTATION_UNDEFINED;
        private boolean mPhysicalDisplayChanged = false;
        /** The display to reparent to on disconnect; if invalid, this isn't a disconnect change. */
        private int mDisconnectReparentDisplay = INVALID_DISPLAY;

        @Nullable private InsetsState mEndInsetsState = null;

        /** Create empty display-change. */
        public DisplayChange(int displayId) {
            mDisplayId = displayId;
        }

        /** Create a display-change representing a rotation. */
        public DisplayChange(int displayId, int startRotation, int endRotation,
                @Nullable InsetsState endInsetsState) {
            mDisplayId = displayId;
            mStartRotation = startRotation;
            mEndRotation = endRotation;
            mEndInsetsState = endInsetsState;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public int getDisplayId() {
            return mDisplayId;
        }

        /**
         * If non-null, these bounds changes should ignore any potential rotation changes.
         */
        @DataClass.Generated.Member
        public @Nullable Rect getStartAbsBounds() {
            return mStartAbsBounds;
        }

        @DataClass.Generated.Member
        public @Nullable Rect getEndAbsBounds() {
            return mEndAbsBounds;
        }

        @DataClass.Generated.Member
        public int getStartRotation() {
            return mStartRotation;
        }

        @DataClass.Generated.Member
        public int getEndRotation() {
            return mEndRotation;
        }

        @DataClass.Generated.Member
        public boolean isPhysicalDisplayChanged() {
            return mPhysicalDisplayChanged;
        }

        /**
         * The display to reparent to on disconnect; if invalid, this isn't a disconnect change.
         */
        @DataClass.Generated.Member
        public int getDisconnectReparentDisplay() {
            return mDisconnectReparentDisplay;
        }

        @DataClass.Generated.Member
        public @Nullable InsetsState getEndInsetsState() {
            return mEndInsetsState;
        }

        /**
         * If non-null, these bounds changes should ignore any potential rotation changes.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setStartAbsBounds(@android.annotation.NonNull Rect value) {
            mStartAbsBounds = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setEndAbsBounds(@android.annotation.NonNull Rect value) {
            mEndAbsBounds = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setStartRotation( int value) {
            mStartRotation = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setEndRotation( int value) {
            mEndRotation = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setPhysicalDisplayChanged( boolean value) {
            mPhysicalDisplayChanged = value;
            return this;
        }

        /**
         * The display to reparent to on disconnect; if invalid, this isn't a disconnect change.
         */
        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setDisconnectReparentDisplay( int value) {
            mDisconnectReparentDisplay = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setEndInsetsState(@android.annotation.NonNull InsetsState value) {
            mEndInsetsState = value;
            return this;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "DisplayChange { " +
                    "displayId = " + mDisplayId + ", " +
                    "startAbsBounds = " + mStartAbsBounds + ", " +
                    "endAbsBounds = " + mEndAbsBounds + ", " +
                    "startRotation = " + mStartRotation + ", " +
                    "endRotation = " + mEndRotation + ", " +
                    "physicalDisplayChanged = " + mPhysicalDisplayChanged + ", " +
                    "disconnectReparentDisplay = " + mDisconnectReparentDisplay + ", " +
                    "endInsetsState = " + mEndInsetsState +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            int flg = 0;
            if (mPhysicalDisplayChanged) flg |= 0x20;
            if (mStartAbsBounds != null) flg |= 0x2;
            if (mEndAbsBounds != null) flg |= 0x4;
            if (mEndInsetsState != null) flg |= 0x80;
            dest.writeInt(flg);
            dest.writeInt(mDisplayId);
            if (mStartAbsBounds != null) dest.writeTypedObject(mStartAbsBounds, flags);
            if (mEndAbsBounds != null) dest.writeTypedObject(mEndAbsBounds, flags);
            dest.writeInt(mStartRotation);
            dest.writeInt(mEndRotation);
            dest.writeInt(mDisconnectReparentDisplay);
            if (mEndInsetsState != null) dest.writeTypedObject(mEndInsetsState, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ DisplayChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            int flg = in.readInt();
            boolean physicalDisplayChanged = (flg & 0x20) != 0;
            int displayId = in.readInt();
            Rect startAbsBounds = (flg & 0x2) == 0 ? null : (Rect) in.readTypedObject(Rect.CREATOR);
            Rect endAbsBounds = (flg & 0x4) == 0 ? null : (Rect) in.readTypedObject(Rect.CREATOR);
            int startRotation = in.readInt();
            int endRotation = in.readInt();
            int disconnectReparentDisplay = in.readInt();
            InsetsState endInsetsState = (flg & 0x80) == 0 ? null : (InsetsState) in.readTypedObject(InsetsState.CREATOR);

            this.mDisplayId = displayId;
            this.mStartAbsBounds = startAbsBounds;
            this.mEndAbsBounds = endAbsBounds;
            this.mStartRotation = startRotation;
            this.mEndRotation = endRotation;
            this.mPhysicalDisplayChanged = physicalDisplayChanged;
            this.mDisconnectReparentDisplay = disconnectReparentDisplay;
            this.mEndInsetsState = endInsetsState;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<DisplayChange> CREATOR
                = new Parcelable.Creator<DisplayChange>() {
            @Override
            public DisplayChange[] newArray(int size) {
                return new DisplayChange[size];
            }

            @Override
            public DisplayChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new DisplayChange(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341597L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private final  int mDisplayId\nprivate @android.annotation.Nullable android.graphics.Rect mStartAbsBounds\nprivate @android.annotation.Nullable android.graphics.Rect mEndAbsBounds\nprivate  int mStartRotation\nprivate  int mEndRotation\nprivate  boolean mPhysicalDisplayChanged\nprivate  int mDisconnectReparentDisplay\nprivate @android.annotation.Nullable android.view.InsetsState mEndInsetsState\nclass DisplayChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class PipChange implements Parcelable {
        // In AE case, we might care about the TF token instead of the task token.
        @android.annotation.NonNull
        private WindowContainerToken mTaskFragmentToken;

        @android.annotation.NonNull
        private ActivityManager.RunningTaskInfo mTaskInfo;

        /** Create empty display-change. */
        public PipChange(ActivityManager.RunningTaskInfo taskInfo) {
            mTaskFragmentToken = taskInfo.token;
            mTaskInfo = taskInfo;
        }

        /** Create a display-change representing a rotation. */
        public PipChange(WindowContainerToken taskFragmentToken,
                ActivityManager.RunningTaskInfo taskInfo) {
            mTaskFragmentToken = taskFragmentToken;
            mTaskInfo = taskInfo;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public @android.annotation.NonNull WindowContainerToken getTaskFragmentToken() {
            return mTaskFragmentToken;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull ActivityManager.RunningTaskInfo getTaskInfo() {
            return mTaskInfo;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull PipChange setTaskFragmentToken(@android.annotation.NonNull WindowContainerToken value) {
            mTaskFragmentToken = value;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskFragmentToken);
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull PipChange setTaskInfo(@android.annotation.NonNull ActivityManager.RunningTaskInfo value) {
            mTaskInfo = value;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskInfo);
            return this;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "PipChange { " +
                    "taskFragmentToken = " + mTaskFragmentToken + ", " +
                    "taskInfo = " + mTaskInfo +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            dest.writeTypedObject(mTaskFragmentToken, flags);
            dest.writeTypedObject(mTaskInfo, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ PipChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            WindowContainerToken taskFragmentToken = (WindowContainerToken) in.readTypedObject(WindowContainerToken.CREATOR);
            ActivityManager.RunningTaskInfo taskInfo = (ActivityManager.RunningTaskInfo) in.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);

            this.mTaskFragmentToken = taskFragmentToken;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskFragmentToken);
            this.mTaskInfo = taskInfo;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskInfo);

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<PipChange> CREATOR
                = new Parcelable.Creator<PipChange>() {
            @Override
            public PipChange[] newArray(int size) {
                return new PipChange[size];
            }

            @Override
            public PipChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new PipChange(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341652L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private @android.annotation.NonNull android.window.WindowContainerToken mTaskFragmentToken\nprivate @android.annotation.NonNull android.app.ActivityManager.RunningTaskInfo mTaskInfo\nclass PipChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class RequestedLocation implements Parcelable {
        private int mDisplayId;
        @android.annotation.NonNull private Rect mBounds = null;

        public RequestedLocation(int displayId, Rect bounds) {
            mDisplayId = displayId;
            mBounds = bounds;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public int getDisplayId() {
            return mDisplayId;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull Rect getBounds() {
            return mBounds;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull RequestedLocation setDisplayId( int value) {
            mDisplayId = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull RequestedLocation setBounds(@android.annotation.NonNull Rect value) {
            mBounds = value;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mBounds);
            return this;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "RequestedLocation { " +
                    "displayId = " + mDisplayId + ", " +
                    "bounds = " + mBounds +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            dest.writeInt(mDisplayId);
            dest.writeTypedObject(mBounds, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ RequestedLocation(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            int displayId = in.readInt();
            Rect bounds = (Rect) in.readTypedObject(Rect.CREATOR);

            this.mDisplayId = displayId;
            this.mBounds = bounds;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mBounds);

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<RequestedLocation> CREATOR
                = new Parcelable.Creator<RequestedLocation>() {
            @Override
            public RequestedLocation[] newArray(int size) {
                return new RequestedLocation[size];
            }

            @Override
            public RequestedLocation createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new RequestedLocation(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341669L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private  int mDisplayId\nprivate @android.annotation.NonNull android.graphics.Rect mBounds\nclass RequestedLocation extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class UserChange implements Parcelable {
        private final int mPreviousUserId;
        private final int mNewUserId;

        public UserChange(int previousUserId, int newUserId) {
            mPreviousUserId = previousUserId;
            mNewUserId = newUserId;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public int getPreviousUserId() {
            return mPreviousUserId;
        }

        @DataClass.Generated.Member
        public int getNewUserId() {
            return mNewUserId;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "UserChange { " +
                    "previousUserId = " + mPreviousUserId + ", " +
                    "newUserId = " + mNewUserId +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            dest.writeInt(mPreviousUserId);
            dest.writeInt(mNewUserId);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ UserChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            int previousUserId = in.readInt();
            int newUserId = in.readInt();

            this.mPreviousUserId = previousUserId;
            this.mNewUserId = newUserId;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<UserChange> CREATOR
                = new Parcelable.Creator<UserChange>() {
            @Override
            public UserChange[] newArray(int size) {
                return new UserChange[size];
            }

            @Override
            public UserChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new UserChange(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341679L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private final  int mPreviousUserId\nprivate final  int mNewUserId\nclass UserChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class WindowingLayerChange implements Parcelable {
        private final @WindowingLayer int mWindowingLayer;
        private final @Nullable IRemoteCallback mRemoteCallback;

        public WindowingLayerChange(@WindowingLayer int windowingLayer, IRemoteCallback remoteCallback) {
            mWindowingLayer = windowingLayer;
            mRemoteCallback = remoteCallback;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public @WindowingLayer int getWindowingLayer() {
            return mWindowingLayer;
        }

        @DataClass.Generated.Member
        public @Nullable IRemoteCallback getRemoteCallback() {
            return mRemoteCallback;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "WindowingLayerChange { " +
                    "windowingLayer = " + mWindowingLayer + ", " +
                    "remoteCallback = " + mRemoteCallback +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mRemoteCallback != null) flg |= 0x2;
            dest.writeByte(flg);
            dest.writeInt(mWindowingLayer);
            if (mRemoteCallback != null) dest.writeStrongInterface(mRemoteCallback);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ WindowingLayerChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            int windowingLayer = in.readInt();
            IRemoteCallback remoteCallback = (flg & 0x2) == 0 ? null : IRemoteCallback.Stub.asInterface(in.readStrongBinder());

            this.mWindowingLayer = windowingLayer;
            com.android.internal.util.AnnotationValidations.validate(
                    WindowingLayer.class, null, mWindowingLayer);
            this.mRemoteCallback = remoteCallback;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<WindowingLayerChange> CREATOR
                = new Parcelable.Creator<WindowingLayerChange>() {
            @Override
            public WindowingLayerChange[] newArray(int size) {
                return new WindowingLayerChange[size];
            }

            @Override
            public WindowingLayerChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new WindowingLayerChange(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341706L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private final @android.app.ActivityManager.AppTask.WindowingLayer int mWindowingLayer\nprivate final @android.annotation.Nullable android.os.IRemoteCallback mRemoteCallback\nclass WindowingLayerChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    /**
     * Information about the relevant RemoteTransition. Separate from RemoteTransition to prevent
     * accidentally sending IApplicationThread out of system process.
     *
     * @see RemoteTransition
     */
    @DataClass(genToString = true, genConstructor = false)
    public static final class RemoteTransitionInfo implements Parcelable {
        @android.annotation.NonNull
        private final IRemoteTransition mRemoteTransition;
        @Nullable
        private final String mDebugName;
        @Nullable
        private final TransitionFilter mFilter;

        public RemoteTransitionInfo(RemoteTransition remoteTransition) {
            mRemoteTransition = remoteTransition.getRemoteTransition();
            mDebugName = remoteTransition.getDebugName();
            mFilter = remoteTransition.getFilter();
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public @android.annotation.NonNull IRemoteTransition getRemoteTransition() {
            return mRemoteTransition;
        }

        @DataClass.Generated.Member
        public @Nullable String getDebugName() {
            return mDebugName;
        }

        @DataClass.Generated.Member
        public @Nullable TransitionFilter getFilter() {
            return mFilter;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "RemoteTransitionInfo { " +
                    "remoteTransition = " + mRemoteTransition + ", " +
                    "debugName = " + mDebugName + ", " +
                    "filter = " + mFilter +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mDebugName != null) flg |= 0x2;
            if (mFilter != null) flg |= 0x4;
            dest.writeByte(flg);
            dest.writeStrongInterface(mRemoteTransition);
            if (mDebugName != null) dest.writeString(mDebugName);
            if (mFilter != null) dest.writeTypedObject(mFilter, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ RemoteTransitionInfo(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            IRemoteTransition remoteTransition = IRemoteTransition.Stub.asInterface(in.readStrongBinder());
            String debugName = (flg & 0x2) == 0 ? null : in.readString();
            TransitionFilter filter = (flg & 0x4) == 0 ? null : (TransitionFilter) in.readTypedObject(TransitionFilter.CREATOR);

            this.mRemoteTransition = remoteTransition;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mRemoteTransition);
            this.mDebugName = debugName;
            this.mFilter = filter;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<RemoteTransitionInfo> CREATOR
                = new Parcelable.Creator<RemoteTransitionInfo>() {
            @Override
            public RemoteTransitionInfo[] newArray(int size) {
                return new RemoteTransitionInfo[size];
            }

            @Override
            public RemoteTransitionInfo createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new RemoteTransitionInfo(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341720L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private final @android.annotation.NonNull android.window.IRemoteTransition mRemoteTransition\nprivate final @android.annotation.Nullable java.lang.String mDebugName\nprivate final @android.annotation.Nullable android.window.TransitionFilter mFilter\nclass RemoteTransitionInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class FullscreenRequestChange implements Parcelable {
        private final @FullscreenModeRequest int mModeRequest;
        private final @Nullable IRemoteCallback mRemoteCallback;

        public FullscreenRequestChange(@FullscreenModeRequest int modeRequest,
                @Nullable IRemoteCallback remoteCallback) {
            mModeRequest = modeRequest;
            mRemoteCallback = remoteCallback;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public @FullscreenModeRequest int getModeRequest() {
            return mModeRequest;
        }

        @DataClass.Generated.Member
        public @Nullable IRemoteCallback getRemoteCallback() {
            return mRemoteCallback;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "FullscreenRequestChange { " +
                    "modeRequest = " + mModeRequest + ", " +
                    "remoteCallback = " + mRemoteCallback +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mRemoteCallback != null) flg |= 0x2;
            dest.writeByte(flg);
            dest.writeInt(mModeRequest);
            if (mRemoteCallback != null) dest.writeStrongInterface(mRemoteCallback);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ FullscreenRequestChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            int modeRequest = in.readInt();
            IRemoteCallback remoteCallback = (flg & 0x2) == 0 ? null : IRemoteCallback.Stub.asInterface(in.readStrongBinder());

            this.mModeRequest = modeRequest;
            com.android.internal.util.AnnotationValidations.validate(
                    FullscreenModeRequest.class, null, mModeRequest);
            this.mRemoteCallback = remoteCallback;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<FullscreenRequestChange> CREATOR
                = new Parcelable.Creator<FullscreenRequestChange>() {
            @Override
            public FullscreenRequestChange[] newArray(int size) {
                return new FullscreenRequestChange[size];
            }

            @Override
            public FullscreenRequestChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new FullscreenRequestChange(in);
            }
        };

        @DataClass.Generated(
                time = 1771341341732L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private final @android.app.Activity.FullscreenModeRequest int mModeRequest\nprivate final @android.annotation.Nullable android.os.IRemoteCallback mRemoteCallback\nclass FullscreenRequestChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new TransitionRequestInfo.
     *
     * @param type
     *   The type of the transition being requested.
     * @param triggerTask
     *   If non-null, the task containing the activity whose lifecycle change (start or
     *   finish) has caused this transition to occur.
     * @param pipChange
     *   If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     *   {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     * @param remoteTransitionInfo
     *   If non-null, a remote-transition associated with the source of this transition.
     * @param displayChange
     *   If non-null, this request was triggered by this display change. This will not be complete:
     *   The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     *   (if size is changing).
     * @param requestedLocation
     *   If non-null, this request was triggered by an app's request to move the trigger task.
     * @param userChange
     *   If non-null, this request was triggered by a request to change the current user.
     * @param windowingLayerChange
     *   If non-null, this request was triggered by an app's request to change trigger task's
     *   windowing layer.
     * @param fullscreenRequestChange
     *   If non-null, this request was triggered by an app's request to enter/exit fullscreen mode.
     * @param flags
     *   The transition flags known at the time of the request. These may not be complete.
     * @param debugId
     *   This is only a BEST-EFFORT id used for log correlation. DO NOT USE for any real work!
     */
    @DataClass.Generated.Member
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable TransitionRequestInfo.PipChange pipChange,
            @Nullable TransitionRequestInfo.RemoteTransitionInfo remoteTransitionInfo,
            @Nullable TransitionRequestInfo.DisplayChange displayChange,
            @Nullable TransitionRequestInfo.RequestedLocation requestedLocation,
            @Nullable TransitionRequestInfo.UserChange userChange,
            @Nullable TransitionRequestInfo.WindowingLayerChange windowingLayerChange,
            @Nullable TransitionRequestInfo.FullscreenRequestChange fullscreenRequestChange,
            int flags,
            int debugId) {
        this.mType = type;
        com.android.internal.util.AnnotationValidations.validate(
                WindowManager.TransitionType.class, null, mType);
        this.mTriggerTask = triggerTask;
        this.mPipChange = pipChange;
        this.mRemoteTransitionInfo = remoteTransitionInfo;
        this.mDisplayChange = displayChange;
        this.mRequestedLocation = requestedLocation;
        this.mUserChange = userChange;
        this.mWindowingLayerChange = windowingLayerChange;
        this.mFullscreenRequestChange = fullscreenRequestChange;
        this.mFlags = flags;
        this.mDebugId = debugId;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The type of the transition being requested.
     */
    @DataClass.Generated.Member
    public @WindowManager.TransitionType int getType() {
        return mType;
    }

    /**
     * If non-null, the task containing the activity whose lifecycle change (start or
     * finish) has caused this transition to occur.
     */
    @DataClass.Generated.Member
    public @Nullable ActivityManager.RunningTaskInfo getTriggerTask() {
        return mTriggerTask;
    }

    /**
     * If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     * {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.PipChange getPipChange() {
        return mPipChange;
    }

    /**
     * If non-null, a remote-transition associated with the source of this transition.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.RemoteTransitionInfo getRemoteTransitionInfo() {
        return mRemoteTransitionInfo;
    }

    /**
     * If non-null, this request was triggered by this display change. This will not be complete:
     * The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     * (if size is changing).
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.DisplayChange getDisplayChange() {
        return mDisplayChange;
    }

    /**
     * If non-null, this request was triggered by an app's request to move the trigger task.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.RequestedLocation getRequestedLocation() {
        return mRequestedLocation;
    }

    /**
     * If non-null, this request was triggered by a request to change the current user.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.UserChange getUserChange() {
        return mUserChange;
    }

    /**
     * If non-null, this request was triggered by an app's request to change trigger task's
     * windowing layer.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.WindowingLayerChange getWindowingLayerChange() {
        return mWindowingLayerChange;
    }

    /**
     * If non-null, this request was triggered by an app's request to enter/exit fullscreen mode.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.FullscreenRequestChange getFullscreenRequestChange() {
        return mFullscreenRequestChange;
    }

    /**
     * The transition flags known at the time of the request. These may not be complete.
     */
    @DataClass.Generated.Member
    public int getFlags() {
        return mFlags;
    }

    /**
     * This is only a BEST-EFFORT id used for log correlation. DO NOT USE for any real work!
     */
    @DataClass.Generated.Member
    public int getDebugId() {
        return mDebugId;
    }

    /**
     * If non-null, the task containing the activity whose lifecycle change (start or
     * finish) has caused this transition to occur.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setTriggerTask(@android.annotation.NonNull ActivityManager.RunningTaskInfo value) {
        mTriggerTask = value;
        return this;
    }

    /**
     * If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     * {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setPipChange(@android.annotation.NonNull TransitionRequestInfo.PipChange value) {
        mPipChange = value;
        return this;
    }

    /**
     * If non-null, a remote-transition associated with the source of this transition.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setRemoteTransitionInfo(@android.annotation.NonNull TransitionRequestInfo.RemoteTransitionInfo value) {
        mRemoteTransitionInfo = value;
        return this;
    }

    /**
     * If non-null, this request was triggered by this display change. This will not be complete:
     * The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     * (if size is changing).
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setDisplayChange(@android.annotation.NonNull TransitionRequestInfo.DisplayChange value) {
        mDisplayChange = value;
        return this;
    }

    /**
     * If non-null, this request was triggered by an app's request to move the trigger task.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setRequestedLocation(@android.annotation.NonNull TransitionRequestInfo.RequestedLocation value) {
        mRequestedLocation = value;
        return this;
    }

    /**
     * If non-null, this request was triggered by a request to change the current user.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setUserChange(@android.annotation.NonNull TransitionRequestInfo.UserChange value) {
        mUserChange = value;
        return this;
    }

    /**
     * If non-null, this request was triggered by an app's request to change trigger task's
     * windowing layer.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setWindowingLayerChange(@android.annotation.NonNull TransitionRequestInfo.WindowingLayerChange value) {
        mWindowingLayerChange = value;
        return this;
    }

    /**
     * If non-null, this request was triggered by an app's request to enter/exit fullscreen mode.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setFullscreenRequestChange(@android.annotation.NonNull TransitionRequestInfo.FullscreenRequestChange value) {
        mFullscreenRequestChange = value;
        return this;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "TransitionRequestInfo { " +
                "type = " + typeToString() + ", " +
                "triggerTask = " + mTriggerTask + ", " +
                "pipChange = " + mPipChange + ", " +
                "remoteTransitionInfo = " + mRemoteTransitionInfo + ", " +
                "displayChange = " + mDisplayChange + ", " +
                "requestedLocation = " + mRequestedLocation + ", " +
                "userChange = " + mUserChange + ", " +
                "windowingLayerChange = " + mWindowingLayerChange + ", " +
                "fullscreenRequestChange = " + mFullscreenRequestChange + ", " +
                "flags = " + mFlags + ", " +
                "debugId = " + mDebugId +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        int flg = 0;
        if (mTriggerTask != null) flg |= 0x2;
        if (mPipChange != null) flg |= 0x4;
        if (mRemoteTransitionInfo != null) flg |= 0x8;
        if (mDisplayChange != null) flg |= 0x10;
        if (mRequestedLocation != null) flg |= 0x20;
        if (mUserChange != null) flg |= 0x40;
        if (mWindowingLayerChange != null) flg |= 0x80;
        if (mFullscreenRequestChange != null) flg |= 0x100;
        dest.writeInt(flg);
        dest.writeInt(mType);
        if (mTriggerTask != null) dest.writeTypedObject(mTriggerTask, flags);
        if (mPipChange != null) dest.writeTypedObject(mPipChange, flags);
        if (mRemoteTransitionInfo != null) dest.writeTypedObject(mRemoteTransitionInfo, flags);
        if (mDisplayChange != null) dest.writeTypedObject(mDisplayChange, flags);
        if (mRequestedLocation != null) dest.writeTypedObject(mRequestedLocation, flags);
        if (mUserChange != null) dest.writeTypedObject(mUserChange, flags);
        if (mWindowingLayerChange != null) dest.writeTypedObject(mWindowingLayerChange, flags);
        if (mFullscreenRequestChange != null) dest.writeTypedObject(mFullscreenRequestChange, flags);
        dest.writeInt(mFlags);
        dest.writeInt(mDebugId);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ TransitionRequestInfo(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int flg = in.readInt();
        int type = in.readInt();
        ActivityManager.RunningTaskInfo triggerTask = (flg & 0x2) == 0 ? null : (ActivityManager.RunningTaskInfo) in.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
        TransitionRequestInfo.PipChange pipChange = (flg & 0x4) == 0 ? null : (TransitionRequestInfo.PipChange) in.readTypedObject(TransitionRequestInfo.PipChange.CREATOR);
        TransitionRequestInfo.RemoteTransitionInfo remoteTransitionInfo = (flg & 0x8) == 0 ? null : (TransitionRequestInfo.RemoteTransitionInfo) in.readTypedObject(TransitionRequestInfo.RemoteTransitionInfo.CREATOR);
        TransitionRequestInfo.DisplayChange displayChange = (flg & 0x10) == 0 ? null : (TransitionRequestInfo.DisplayChange) in.readTypedObject(TransitionRequestInfo.DisplayChange.CREATOR);
        TransitionRequestInfo.RequestedLocation requestedLocation = (flg & 0x20) == 0 ? null : (TransitionRequestInfo.RequestedLocation) in.readTypedObject(TransitionRequestInfo.RequestedLocation.CREATOR);
        TransitionRequestInfo.UserChange userChange = (flg & 0x40) == 0 ? null : (TransitionRequestInfo.UserChange) in.readTypedObject(TransitionRequestInfo.UserChange.CREATOR);
        TransitionRequestInfo.WindowingLayerChange windowingLayerChange = (flg & 0x80) == 0 ? null : (TransitionRequestInfo.WindowingLayerChange) in.readTypedObject(TransitionRequestInfo.WindowingLayerChange.CREATOR);
        TransitionRequestInfo.FullscreenRequestChange fullscreenRequestChange = (flg & 0x100) == 0 ? null : (TransitionRequestInfo.FullscreenRequestChange) in.readTypedObject(TransitionRequestInfo.FullscreenRequestChange.CREATOR);
        int flags = in.readInt();
        int debugId = in.readInt();

        this.mType = type;
        com.android.internal.util.AnnotationValidations.validate(
                WindowManager.TransitionType.class, null, mType);
        this.mTriggerTask = triggerTask;
        this.mPipChange = pipChange;
        this.mRemoteTransitionInfo = remoteTransitionInfo;
        this.mDisplayChange = displayChange;
        this.mRequestedLocation = requestedLocation;
        this.mUserChange = userChange;
        this.mWindowingLayerChange = windowingLayerChange;
        this.mFullscreenRequestChange = fullscreenRequestChange;
        this.mFlags = flags;
        this.mDebugId = debugId;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<TransitionRequestInfo> CREATOR
            = new Parcelable.Creator<TransitionRequestInfo>() {
        @Override
        public TransitionRequestInfo[] newArray(int size) {
            return new TransitionRequestInfo[size];
        }

        @Override
        public TransitionRequestInfo createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new TransitionRequestInfo(in);
        }
    };

    @DataClass.Generated(
            time = 1771341341818L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
            inputSignatures = "private final @android.view.WindowManager.TransitionType int mType\nprivate @android.annotation.Nullable android.app.ActivityManager.RunningTaskInfo mTriggerTask\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.PipChange mPipChange\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.RemoteTransitionInfo mRemoteTransitionInfo\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.DisplayChange mDisplayChange\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.RequestedLocation mRequestedLocation\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.UserChange mUserChange\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.WindowingLayerChange mWindowingLayerChange\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.FullscreenRequestChange mFullscreenRequestChange\nprivate final  int mFlags\nprivate final  int mDebugId\n  java.lang.String typeToString()\npublic  android.window.RemoteTransition getRemoteTransition()\npublic  void setRemoteTransition(android.window.RemoteTransition)\nclass TransitionRequestInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genAidl=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
