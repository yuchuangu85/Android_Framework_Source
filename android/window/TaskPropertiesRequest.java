/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.DataClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Data object for the request Task properties.
 *
 * Note: this should only include properties that are not depending on the applying order.
 * @hide
 */
@DataClass(genEqualsHashCode = true, genParcelable = true, genToString = true,
        genConstructor = false, genBuilder = false, genSetters = false, genConstDefs = false)
public final class TaskPropertiesRequest implements Parcelable {

    public static final int REQUEST_NONE = 0;
    public static final int REQUEST_REPARENT_ON_DISPLAY_REMOVAL = 1;
    public static final int REQUEST_FORCE_OPAQUE = 1 << 1;
    public static final int REQUEST_IGNORE_INSETS = 1 << 2;
    public static final int REQUEST_DISABLE_APP_COMPAT_ROUNDED_CORNERS = 1 << 3;
    public static final int REQUEST_FORCE_LEAF_TASKS_NON_OCCLUDING = 1 << 4;

    @IntDef(flag = true, prefix = { "REQUEST_" }, value = {
            REQUEST_NONE,
            REQUEST_REPARENT_ON_DISPLAY_REMOVAL,
            REQUEST_FORCE_OPAQUE,
            REQUEST_IGNORE_INSETS,
            REQUEST_DISABLE_APP_COMPAT_ROUNDED_CORNERS,
            REQUEST_FORCE_LEAF_TASKS_NON_OCCLUDING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestMask {}

    /** Request mask to indicate which properties have been requested to change. */
    @RequestMask
    private int mRequestMask = REQUEST_NONE;

    /**
     * Whether the Task should be reparented to the default display when its current display is
     * removed.
     */
    private boolean mReparentOnDisplayRemoval;

    /**
     * Whether the Task should be treated as opaque when there is any running activity child.
     */
    private boolean mForceOpaque;

    /**
     * Whether the Task should report task bounds without checking insets, such as for metrics like
     * smallestScreenWidthDp. This should be used when the Task can float on top of insets.
     */
    private boolean mIgnoreInsets;

    /**
     * Whether the Task should disable showing rounded corners for app compat purposes (e.g. when a
     * landscape app is letterboxed). Tasks can set this for better UX since sharp corners may look
     * better in some cases like in a Bubble.
     */
    private boolean mDisableAppCompatRoundedCorners;

    /**
     * Whether all leaf Tasks of this Task should be treated as non-occluding when calculating
     * visibility, unless the leaf Task is {@link #isForceOpaque()}.
     * Note: leaf Tasks below {@link TaskCreationParams#isVisibilityBarrier()} Task will still be
     * treated as invisible.
     */
    private boolean mForceLeafTasksNonOccluding;

    public TaskPropertiesRequest() {}

    /**
     * Sets whether the Task should be reparented to the default display when its current display
     * is removed.
     */
    public TaskPropertiesRequest setReparentOnDisplayRemoval(boolean reparentOnDisplayRemoval) {
        mRequestMask |= REQUEST_REPARENT_ON_DISPLAY_REMOVAL;
        mReparentOnDisplayRemoval = reparentOnDisplayRemoval;
        return this;
    }

    /**
     * Sets whether the Task should be treated as opaque when there is any running activity child.
     */
    public TaskPropertiesRequest setForceOpaque(boolean forceOpaque) {
        mRequestMask |= REQUEST_FORCE_OPAQUE;
        mForceOpaque = forceOpaque;
        return this;
    }

    /**
     * Sets whether the Task should report task bounds without checking insets, such as for metrics
     * like smallestScreenWidthDp. This should be used when the Task can float on top of insets.
     */
    public TaskPropertiesRequest setIgnoreInsets(boolean ignoreInsets) {
        mRequestMask |= REQUEST_IGNORE_INSETS;
        mIgnoreInsets = ignoreInsets;
        return this;
    }

    /**
     * Sets whether the Task should disable showing rounded corners for app compat purposes (e.g.
     * when a landscape app is letterboxed). Tasks can set this for better UX since sharp corners
     * may look better in some cases like in a Bubble.
     */
    public TaskPropertiesRequest setDisableAppCompatRoundedCorners(
            boolean disableAppCompatRoundedCorners) {
        mRequestMask |= REQUEST_DISABLE_APP_COMPAT_ROUNDED_CORNERS;
        mDisableAppCompatRoundedCorners = disableAppCompatRoundedCorners;
        return this;
    }

    /**
     * Sets whether all leaf Tasks of this Task should be treated as non-occluding when calculating
     * visibility.
     * Note: leaf Tasks below {@link TaskCreationParams#isVisibilityBarrier()} Task will still be
     * treated as invisible.
     */
    public TaskPropertiesRequest setForceLeafTasksNonOccluding(
            boolean forceLeafTasksNonOccluding) {
        mRequestMask |= REQUEST_FORCE_LEAF_TASKS_NON_OCCLUDING;
        mForceLeafTasksNonOccluding = forceLeafTasksNonOccluding;
        return this;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TaskPropertiesRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Request mask to indicate which properties have been requested to change.
     */
    @DataClass.Generated.Member
    public @RequestMask int getRequestMask() {
        return mRequestMask;
    }

    /**
     * Whether the Task should be reparented to the default display when its current display is
     * removed.
     */
    @DataClass.Generated.Member
    public boolean isReparentOnDisplayRemoval() {
        return mReparentOnDisplayRemoval;
    }

    /**
     * Whether the Task should be treated as opaque when there is any running activity child.
     */
    @DataClass.Generated.Member
    public boolean isForceOpaque() {
        return mForceOpaque;
    }

    /**
     * Whether the Task should report task bounds without checking insets, such as for metrics like
     * smallestScreenWidthDp. This should be used when the Task can float on top of insets.
     */
    @DataClass.Generated.Member
    public boolean isIgnoreInsets() {
        return mIgnoreInsets;
    }

    /**
     * Whether the Task should disable showing rounded corners for app compat purposes (e.g. when a
     * landscape app is letterboxed). Tasks can set this for better UX since sharp corners may look
     * better in some cases like in a Bubble.
     */
    @DataClass.Generated.Member
    public boolean isDisableAppCompatRoundedCorners() {
        return mDisableAppCompatRoundedCorners;
    }

    /**
     * Whether all leaf Tasks of this Task should be treated as non-occluding when calculating
     * visibility, unless the leaf Task is {@link #isForceOpaque()}.
     * Note: leaf Tasks below {@link TaskCreationParams#isVisibilityBarrier()} Task will still be
     * treated as invisible.
     */
    @DataClass.Generated.Member
    public boolean isForceLeafTasksNonOccluding() {
        return mForceLeafTasksNonOccluding;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "TaskPropertiesRequest { " +
                "requestMask = " + mRequestMask + ", " +
                "reparentOnDisplayRemoval = " + mReparentOnDisplayRemoval + ", " +
                "forceOpaque = " + mForceOpaque + ", " +
                "ignoreInsets = " + mIgnoreInsets + ", " +
                "disableAppCompatRoundedCorners = " + mDisableAppCompatRoundedCorners + ", " +
                "forceLeafTasksNonOccluding = " + mForceLeafTasksNonOccluding +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@android.annotation.Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(TaskPropertiesRequest other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        TaskPropertiesRequest that = (TaskPropertiesRequest) o;
        //noinspection PointlessBooleanExpression
        return true
                && mRequestMask == that.mRequestMask
                && mReparentOnDisplayRemoval == that.mReparentOnDisplayRemoval
                && mForceOpaque == that.mForceOpaque
                && mIgnoreInsets == that.mIgnoreInsets
                && mDisableAppCompatRoundedCorners == that.mDisableAppCompatRoundedCorners
                && mForceLeafTasksNonOccluding == that.mForceLeafTasksNonOccluding;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mRequestMask;
        _hash = 31 * _hash + Boolean.hashCode(mReparentOnDisplayRemoval);
        _hash = 31 * _hash + Boolean.hashCode(mForceOpaque);
        _hash = 31 * _hash + Boolean.hashCode(mIgnoreInsets);
        _hash = 31 * _hash + Boolean.hashCode(mDisableAppCompatRoundedCorners);
        _hash = 31 * _hash + Boolean.hashCode(mForceLeafTasksNonOccluding);
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mReparentOnDisplayRemoval) flg |= 0x2;
        if (mForceOpaque) flg |= 0x4;
        if (mIgnoreInsets) flg |= 0x8;
        if (mDisableAppCompatRoundedCorners) flg |= 0x10;
        if (mForceLeafTasksNonOccluding) flg |= 0x20;
        dest.writeByte(flg);
        dest.writeInt(mRequestMask);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ TaskPropertiesRequest(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean reparentOnDisplayRemoval = (flg & 0x2) != 0;
        boolean forceOpaque = (flg & 0x4) != 0;
        boolean ignoreInsets = (flg & 0x8) != 0;
        boolean disableAppCompatRoundedCorners = (flg & 0x10) != 0;
        boolean forceLeafTasksNonOccluding = (flg & 0x20) != 0;
        int requestMask = in.readInt();

        this.mRequestMask = requestMask;
        com.android.internal.util.AnnotationValidations.validate(
                RequestMask.class, null, mRequestMask);
        this.mReparentOnDisplayRemoval = reparentOnDisplayRemoval;
        this.mForceOpaque = forceOpaque;
        this.mIgnoreInsets = ignoreInsets;
        this.mDisableAppCompatRoundedCorners = disableAppCompatRoundedCorners;
        this.mForceLeafTasksNonOccluding = forceLeafTasksNonOccluding;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<TaskPropertiesRequest> CREATOR
            = new Parcelable.Creator<TaskPropertiesRequest>() {
        @Override
        public TaskPropertiesRequest[] newArray(int size) {
            return new TaskPropertiesRequest[size];
        }

        @Override
        public TaskPropertiesRequest createFromParcel(@NonNull Parcel in) {
            return new TaskPropertiesRequest(in);
        }
    };

    @DataClass.Generated(
            time = 1766656586757L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/window/TaskPropertiesRequest.java",
            inputSignatures = "public static final  int REQUEST_NONE\npublic static final  int REQUEST_REPARENT_ON_DISPLAY_REMOVAL\npublic static final  int REQUEST_FORCE_OPAQUE\npublic static final  int REQUEST_IGNORE_INSETS\npublic static final  int REQUEST_DISABLE_APP_COMPAT_ROUNDED_CORNERS\npublic static final  int REQUEST_FORCE_LEAF_TASKS_NON_OCCLUDING\nprivate @android.window.TaskPropertiesRequest.RequestMask int mRequestMask\nprivate  boolean mReparentOnDisplayRemoval\nprivate  boolean mForceOpaque\nprivate  boolean mIgnoreInsets\nprivate  boolean mDisableAppCompatRoundedCorners\nprivate  boolean mForceLeafTasksNonOccluding\npublic  android.window.TaskPropertiesRequest setReparentOnDisplayRemoval(boolean)\npublic  android.window.TaskPropertiesRequest setForceOpaque(boolean)\npublic  android.window.TaskPropertiesRequest setIgnoreInsets(boolean)\npublic  android.window.TaskPropertiesRequest setDisableAppCompatRoundedCorners(boolean)\npublic  android.window.TaskPropertiesRequest setForceLeafTasksNonOccluding(boolean)\nclass TaskPropertiesRequest extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genEqualsHashCode=true, genParcelable=true, genToString=true, genConstructor=false, genBuilder=false, genSetters=false, genConstDefs=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
