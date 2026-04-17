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

package android.content.pm;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import com.android.internal.util.DataClass;

import java.util.Collections;
import java.util.Set;

/**
 * This class contains all info related to purposes for {@link
 * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}. Used in {@link
 * android.content.pm.PackageInfo#requestedPermissionsPurposes PackageInfo}.
 *
 * @hide
 */
// Fields are initialized so codegen can have default Builder constructor.
@DataClass(genParcelable = true, genEqualsHashCode = true, genConstructor = false, genSetters = false)
@SystemApi
@FlaggedApi(android.permission.flags.Flags.FLAG_PPD_MANIFEST_ENABLED)
public final class UsesPermissionPurposeInfo implements Parcelable {
    /**
     * The name of the permission that the purpose information in this class pertains to.
     */
    private final @NonNull String mPermissionName;

    /**
     * The set of enum strings listed using {@link
     * android.R.styleable#AndroidManifestPurpose &lt;purpose&gt;} under {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     */
    @DataClass.PluralOf("Purpose")
    private @NonNull Set<String> mPurposes;

    /**
     * The set of enum strings listed using {@link
     * android.R.styleable#AndroidManifestGeneralPurpose &lt;general-purpose&gt;} under {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     */
    @DataClass.PluralOf("GeneralPurpose")
    private @NonNull Set<String> mGeneralPurposes;

    /**
     * The resource ID for the purpose string stated using {@link
     * android.R.styleable#AndroidManifestPurposeString &lt;purpose-string&gt;} under {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     */
    @StringRes
    private int mPurposeStringResource;

    /**
     * Creates a new PermissionPurposeInfo.
     *
     * @param permissionName
     *   The name of the permission that the purpose information in this class pertains to.
     * @param purposes
     *   The set of enum strings listed using {@link
     *   android.R.styleable#AndroidManifestPurpose &lt;purpose&gt;} under {@link
     *   android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     * @param generalPurposes
     *   The set of enum strings listed using {@link
     *   android.R.styleable#AndroidManifestGeneralPurpose &lt;general-purpose&gt;} under {@link
     *   android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     * @hide
     */
    @TestApi
    @FlaggedApi(android.permission.flags.Flags.FLAG_PPD_MANIFEST_ENABLED)
    public UsesPermissionPurposeInfo(
            @NonNull String permissionName,
            @NonNull Set<String> purposes,
            @NonNull Set<String> generalPurposes,
            @StringRes int purposeStringResource) {
        this.mPermissionName = permissionName;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPermissionName);
        this.mPurposes = purposes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPurposes);
        this.mGeneralPurposes = generalPurposes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mGeneralPurposes);
        this.mPurposeStringResource = purposeStringResource;

        onConstructed();
    }

    // for codegen constructors to call
    private void onConstructed() {
        mGeneralPurposes = Collections.unmodifiableSet(mGeneralPurposes);
    }

    void parcelPurposes(Parcel dest, int flags) {
        dest.writeArray(mPurposes.toArray());
    }

    static ArraySet<String> unparcelPurposes(Parcel in) {
        return new ArraySet<>(in.readArray(null, String.class));
    }

    void parcelGeneralPurposes(Parcel dest, int flags) {
        dest.writeArray(mGeneralPurposes.toArray());
    }

    static ArraySet<String> unparcelGeneralPurposes(Parcel in) {
        return new ArraySet<>(in.readArray(null, String.class));
    }





    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/UsesPermissionPurposeInfo.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * The name of the permission that the purpose information in this class pertains to.
     */
    @DataClass.Generated.Member
    public @NonNull String getPermissionName() {
        return mPermissionName;
    }

    /**
     * The set of enum strings listed using {@link
     * android.R.styleable#AndroidManifestPurpose &lt;purpose&gt;} under {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     */
    @DataClass.Generated.Member
    public @NonNull Set<String> getPurposes() {
        return mPurposes;
    }

    /**
     * The set of enum strings listed using {@link
     * android.R.styleable#AndroidManifestGeneralPurpose &lt;general-purpose&gt;} under {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     */
    @DataClass.Generated.Member
    public @NonNull Set<String> getGeneralPurposes() {
        return mGeneralPurposes;
    }

    /**
     * The resource ID for the purpose string stated using {@link
     * android.R.styleable#AndroidManifestPurposeString &lt;purpose-string&gt;} under {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
     */
    @DataClass.Generated.Member
    public @StringRes int getPurposeStringResource() {
        return mPurposeStringResource;
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(UsesPermissionPurposeInfo other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        UsesPermissionPurposeInfo that = (UsesPermissionPurposeInfo) o;
        //noinspection PointlessBooleanExpression
        return true
                && java.util.Objects.equals(mPermissionName, that.mPermissionName)
                && java.util.Objects.equals(mPurposes, that.mPurposes)
                && java.util.Objects.equals(mGeneralPurposes, that.mGeneralPurposes)
                && mPurposeStringResource == that.mPurposeStringResource;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + java.util.Objects.hashCode(mPermissionName);
        _hash = 31 * _hash + java.util.Objects.hashCode(mPurposes);
        _hash = 31 * _hash + java.util.Objects.hashCode(mGeneralPurposes);
        _hash = 31 * _hash + mPurposeStringResource;
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeString(mPermissionName);
        parcelPurposes(dest, flags);
        parcelGeneralPurposes(dest, flags);
        dest.writeInt(mPurposeStringResource);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ UsesPermissionPurposeInfo(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        String permissionName = in.readString();
        Set<String> purposes = unparcelPurposes(in);
        Set<String> generalPurposes = unparcelGeneralPurposes(in);
        int purposeStringResource = in.readInt();

        this.mPermissionName = permissionName;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPermissionName);
        this.mPurposes = purposes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPurposes);
        this.mGeneralPurposes = generalPurposes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mGeneralPurposes);
        this.mPurposeStringResource = purposeStringResource;
        com.android.internal.util.AnnotationValidations.validate(
                StringRes.class, null, mPurposeStringResource);

        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<UsesPermissionPurposeInfo> CREATOR
            = new Parcelable.Creator<UsesPermissionPurposeInfo>() {
        @Override
        public UsesPermissionPurposeInfo[] newArray(int size) {
            return new UsesPermissionPurposeInfo[size];
        }

        @Override
        public UsesPermissionPurposeInfo createFromParcel(@NonNull Parcel in) {
            return new UsesPermissionPurposeInfo(in);
        }
    };

    @DataClass.Generated(
            time = 1762202984216L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/UsesPermissionPurposeInfo.java",
            inputSignatures = "private final @android.annotation.NonNull java.lang.String mPermissionName\nprivate @com.android.internal.util.DataClass.PluralOf(\"Purpose\") @android.annotation.NonNull java.util.Set<java.lang.String> mPurposes\nprivate @com.android.internal.util.DataClass.PluralOf(\"GeneralPurpose\") @android.annotation.NonNull java.util.Set<java.lang.String> mGeneralPurposes\nprivate @android.annotation.StringRes int mPurposeStringResource\nprivate  void onConstructed()\n  void parcelPurposes(android.os.Parcel,int)\nstatic  android.util.ArraySet<java.lang.String> unparcelPurposes(android.os.Parcel)\n  void parcelGeneralPurposes(android.os.Parcel,int)\nstatic  android.util.ArraySet<java.lang.String> unparcelGeneralPurposes(android.os.Parcel)\nclass UsesPermissionPurposeInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genParcelable=true, genEqualsHashCode=true, genConstructor=false, genSetters=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
