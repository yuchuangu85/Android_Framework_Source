/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Activity;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.CollectionUtils;

import java.util.Collections;
import java.util.Map;

/**
 * Overall information about the contents of a package.  This corresponds
 * to all of the information collected from AndroidManifest.xml.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class PackageInfo implements Parcelable {
    private static final String TAG = PackageInfo.class.getSimpleName();

    /**
     * The name of this package.  From the &lt;manifest&gt; tag's "name"
     * attribute.
     */
    @NonNull
    public String packageName;

    /**
     * The names of any installed split APKs for this package.
     */
    @NonNull
    public String[] splitNames;

    /**
     * @deprecated Use {@link #getLongVersionCode()} instead, which includes both
     * this and the additional
     * {@link android.R.styleable#AndroidManifest_versionCodeMajor versionCodeMajor} attribute.
     * The version number of this package, as specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifest_versionCode versionCode}
     * attribute.
     * @see #getLongVersionCode()
     */
    @Deprecated
    public int versionCode;

    /**
     * @hide
     * The major version number of this package, as specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifest_versionCode versionCodeMajor}
     * attribute.
     * @see #getLongVersionCode()
     */
    public int versionCodeMajor;

    /**
     * Return {@link android.R.styleable#AndroidManifest_versionCode versionCode} and
     * {@link android.R.styleable#AndroidManifest_versionCodeMajor versionCodeMajor} combined
     * together as a single long value.  The
     * {@link android.R.styleable#AndroidManifest_versionCodeMajor versionCodeMajor} is placed in
     * the upper 32 bits.
     */
    public long getLongVersionCode() {
        return composeLongVersionCode(versionCodeMajor, versionCode);
    }

    /**
     * Set the full version code in this PackageInfo, updating {@link #versionCode}
     * with the lower bits.
     * @see #getLongVersionCode()
     */
    public void setLongVersionCode(long longVersionCode) {
        versionCodeMajor = (int) (longVersionCode>>32);
        versionCode = (int) longVersionCode;
    }

    /**
     * @hide Internal implementation for composing a minor and major version code in to
     * a single long version code.
     */
    public static long composeLongVersionCode(int major, int minor) {
        return (((long) major) << 32) | (((long) minor) & 0xffffffffL);
    }

    /**
     * The version name of this package, as specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifest_versionName versionName}
     * attribute, or null if there was none.
     */
    @Nullable
    public String versionName;

    /**
     * The revision number of the base APK for this package, as specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifest_revisionCode revisionCode}
     * attribute.
     */
    public int baseRevisionCode;

    /**
     * The revision number of any split APKs for this package, as specified by
     * the &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifest_revisionCode revisionCode}
     * attribute. Indexes are a 1:1 mapping against {@link #splitNames}.
     */
    @NonNull
    public int[] splitRevisionCodes;

    /**
     * The shared user ID name of this package, as specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifest_sharedUserId sharedUserId}
     * attribute.
     */
    @Nullable
    public String sharedUserId;

    /**
     * The shared user ID label of this package, as specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifest_sharedUserLabel sharedUserLabel}
     * attribute.
     */
    public int sharedUserLabel;

    /**
     * Information collected from the &lt;application&gt; tag, or null if
     * there was none.
     */
    @Nullable
    public ApplicationInfo applicationInfo;

    /**
     * The time at which the app was first installed.  Units are as
     * per {@link System#currentTimeMillis()}.
     */
    public long firstInstallTime;

    /**
     * The time at which the app was last updated.  Units are as
     * per {@link System#currentTimeMillis()}.
     */
    public long lastUpdateTime;

    /**
     * All kernel group-IDs that have been assigned to this package.
     * This is only filled in if the flag {@link PackageManager#GET_GIDS} was set.
     */
    @Nullable
    public int[] gids;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestActivity
     * &lt;activity&gt;} tags included under &lt;application&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_ACTIVITIES} was set.
     */
    @Nullable
    public ActivityInfo[] activities;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestReceiver
     * &lt;receiver&gt;} tags included under &lt;application&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_RECEIVERS} was set.
     */
    @Nullable
    public ActivityInfo[] receivers;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestService
     * &lt;service&gt;} tags included under &lt;application&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_SERVICES} was set.
     */
    @Nullable
    public ServiceInfo[] services;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestProvider
     * &lt;provider&gt;} tags included under &lt;application&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_PROVIDERS} was set.
     */
    @Nullable
    public ProviderInfo[] providers;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestInstrumentation
     * &lt;instrumentation&gt;} tags included under &lt;manifest&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_INSTRUMENTATION} was set.
     */
    @Nullable
    public InstrumentationInfo[] instrumentation;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestPermission
     * &lt;permission&gt;} tags included under &lt;manifest&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_PERMISSIONS} was set.
     */
    @Nullable
    public PermissionInfo[] permissions;

    /**
     * Array of all {@link android.R.styleable#AndroidManifestUsesPermission
     * &lt;uses-permission&gt;} tags included under &lt;manifest&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_PERMISSIONS} was set.  This list includes
     * all permissions requested, even those that were not granted or known
     * by the system at install time.
     */
    @Nullable
    public String[] requestedPermissions;

    /**
     * Array of flags of all {@link android.R.styleable#AndroidManifestUsesPermission
     * &lt;uses-permission&gt;} tags included under &lt;manifest&gt;,
     * or null if there were none.  This is only filled in if the flag
     * {@link PackageManager#GET_PERMISSIONS} was set.  Each value matches
     * the corresponding entry in {@link #requestedPermissions}, and will have
     * the flags {@link #REQUESTED_PERMISSION_GRANTED}, {@link #REQUESTED_PERMISSION_IMPLICIT}, and
     * {@link #REQUESTED_PERMISSION_NEVER_FOR_LOCATION} set as appropriate.
     */
    @Nullable
    public int[] requestedPermissionsFlags;

    /**
     * Map of object containing info related to purposes for all {@link
     * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;} that have purpose
     * related info. This is only filled in if the flag {@link PackageManager#GET_PERMISSIONS}
     * was set.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    @FlaggedApi(android.permission.flags.Flags.FLAG_PPD_MANIFEST_ENABLED)
    public Map<String, UsesPermissionPurposeInfo> requestedPermissionsPurposes = Collections.emptyMap();

    /**
     * Array of all {@link android.R.styleable#AndroidManifestAttribution
     * &lt;attribution&gt;} tags included under &lt;manifest&gt;, or null if there were none. This
     * is only filled if the flag {@link PackageManager#GET_ATTRIBUTIONS_LONG} was set.
     */
    @SuppressWarnings({"ArrayReturn", "NullableCollection"})
    @Nullable
    public Attribution[] attributions;

    /**
     * The time at which the app was archived for the user.  Units are as
     * per {@link System#currentTimeMillis()}.
     * @hide
     */
    @CurrentTimeMillisLong
    private long mArchiveTimeMillis;

    /**
     * Flag for {@link #requestedPermissionsFlags}: the requested permission
     * is required for the application to run; the user can not optionally
     * disable it.  Currently all permissions are required.
     *
     * @removed We do not support required permissions.
     */
    public static final int REQUESTED_PERMISSION_REQUIRED = 0x00000001;

    /**
     * Flag for {@link #requestedPermissionsFlags}: the requested permission
     * is currently granted to the application.
     */
    public static final int REQUESTED_PERMISSION_GRANTED = 0x00000002;

    /**
     * Flag for {@link #requestedPermissionsFlags}: the requested permission has
     * declared {@code neverForLocation} in their manifest as a strong assertion
     * by a developer that they will never use this permission to derive the
     * physical location of the device, regardless of
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} and/or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} being granted.
     */
    public static final int REQUESTED_PERMISSION_NEVER_FOR_LOCATION = 0x00010000;

    /**
     * Flag for {@link #requestedPermissionsFlags}: It only applies to {@link
     * android.Manifest.permission#ACCESS_FINE_LOCATION}. When this flag is set, apps cannot request
     * that permission through {@link Activity#requestPermissions(String[], int)}, and can only use
     * location button to obtain it temporarily.
     */
    @FlaggedApi(android.permission.flags.Flags.FLAG_LOCATION_BUTTON_ENABLED)
    public static final int REQUESTED_PERMISSION_ONLY_FOR_LOCATION_BUTTON = 0x00020000;

    /**
     * Flag for {@link #requestedPermissionsFlags}: the requested permission was
     * not explicitly requested via uses-permission, but was instead implicitly
     * requested (e.g., for version compatibility reasons).
     */
    public static final int REQUESTED_PERMISSION_IMPLICIT = 0x00000004;

    /**
     * Array of all signatures read from the package file. This is only filled
     * in if the flag {@link PackageManager#GET_SIGNATURES} was set. A package
     * must be signed with at least one certificate which is at position zero.
     * The package can be signed with additional certificates which appear as
     * subsequent entries.
     *
     * <strong>Note:</strong> Signature ordering is not guaranteed to be
     * stable which means that a package signed with certificates A and B is
     * equivalent to being signed with certificates B and A. This means that
     * in case multiple signatures are reported you cannot assume the one at
     * the first position to be the same across updates.
     *
     * <strong>Deprecated</strong> This has been replaced by the
     * {@link PackageInfo#signingInfo} field, which takes into
     * account signing certificate rotation.  For backwards compatibility in
     * the event of signing certificate rotation, this will return the oldest
     * reported signing certificate, so that an application will appear to
     * callers as though no rotation occurred.
     *
     * @deprecated use {@code signingInfo} instead
     */
    @Deprecated
    @Nullable
    public Signature[] signatures;

    /**
     * Signing information read from the package file, potentially
     * including past signing certificates no longer used after signing
     * certificate rotation.  This is only filled in if
     * the flag {@link PackageManager#GET_SIGNING_CERTIFICATES} was set.
     *
     * Use this field instead of the deprecated {@code signatures} field.
     * See {@link SigningInfo} for more information on its contents.
     */
    @Nullable
    public SigningInfo signingInfo;

    /**
     * Application specified preferred configuration
     * {@link android.R.styleable#AndroidManifestUsesConfiguration
     * &lt;uses-configuration&gt;} tags included under &lt;manifest&gt;,
     * or null if there were none. This is only filled in if the flag
     * {@link PackageManager#GET_CONFIGURATIONS} was set.
     */
    @Nullable
    public ConfigurationInfo[] configPreferences;

    /**
     * Features that this application has requested.
     *
     * @see FeatureInfo#FLAG_REQUIRED
     */
    @Nullable
    public FeatureInfo[] reqFeatures;

    /**
     * Groups of features that this application has requested.
     * Each group contains a set of features that are required.
     * A device must match the features listed in {@link #reqFeatures} and one
     * or more FeatureGroups in order to have satisfied the feature requirement.
     *
     * @see FeatureInfo#FLAG_REQUIRED
     */
    @Nullable
    public FeatureGroupInfo[] featureGroups;

    /**
     * Constant corresponding to <code>auto</code> in
     * the {@link android.R.attr#installLocation} attribute.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int INSTALL_LOCATION_UNSPECIFIED = -1;

    /**
     * Constant corresponding to <code>auto</code> in the
     * {@link android.R.attr#installLocation} attribute.
     */
    public static final int INSTALL_LOCATION_AUTO = 0;

    /**
     * Constant corresponding to <code>internalOnly</code> in the
     * {@link android.R.attr#installLocation} attribute.
     */
    public static final int INSTALL_LOCATION_INTERNAL_ONLY = 1;

    /**
     * Constant corresponding to <code>preferExternal</code> in the
     * {@link android.R.attr#installLocation} attribute.
     */
    public static final int INSTALL_LOCATION_PREFER_EXTERNAL = 2;

    /**
     * The install location requested by the package. From the
     * {@link android.R.attr#installLocation} attribute, one of
     * {@link #INSTALL_LOCATION_AUTO}, {@link #INSTALL_LOCATION_INTERNAL_ONLY},
     * {@link #INSTALL_LOCATION_PREFER_EXTERNAL}
     */
    public int installLocation = INSTALL_LOCATION_INTERNAL_ONLY;

    /**
     * Whether or not the package is a stub and should be replaced by a full version of the app.
     *
     * @hide
     */
    public boolean isStub;

    /**
     * Whether the app is included when the device is booted into a minimal state. Set through the
     * non-namespaced "coreApp" attribute of the manifest tag.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean coreApp;

    /**
     * Signals that this app is required for all users on the device.
     *
     * When a restricted user profile is created, the user is prompted with a list of apps to
     * install on that user. Settings uses this field to determine obligatory apps which cannot be
     * deselected.
     *
     * This restriction is not handled by the framework itself.
     * @hide
     */
    public boolean requiredForAllUsers;

    /**
     * The restricted account authenticator type that is used by this application.
     * @hide
     */
    @Nullable
    public String restrictedAccountType;

    /**
     * The required account type without which this application will not function.
     * @hide
     */
    @Nullable
    public String requiredAccountType;

    /**
     * What package, if any, this package will overlay.
     *
     * Package name of target package, or null.
     * @hide
     */
    @UnsupportedAppUsage
    @Nullable
    public String overlayTarget;

    /**
     * The name of the overlayable set of elements package, if any, this package will overlay.
     *
     * Overlayable name defined within the target package, or null.
     * @hide
     */
    @Nullable
    public String targetOverlayableName;

    /**
     * The overlay category, if any, of this package
     *
     * @hide
     */
    @Nullable
    public String overlayCategory;

    /** @hide */
    public int overlayPriority;

    /**
     * Whether the overlay is static, meaning it cannot be enabled/disabled at runtime.
     * @hide
     */
    public boolean mOverlayIsStatic;

    /**
     * The user-visible SDK version (ex. 26) of the framework against which the application claims
     * to have been compiled, or {@code 0} if not specified.
     * <p>
     * This property is the compile-time equivalent of
     * {@link android.os.Build.VERSION#SDK_INT Build.VERSION.SDK_INT}.
     *
     * @hide For platform use only; we don't expect developers to need to read this value.
     */
    public int compileSdkVersion;

    /**
     * The development codename (ex. "O", "REL") of the framework against which the application
     * claims to have been compiled, or {@code null} if not specified.
     * <p>
     * This property is the compile-time equivalent of
     * {@link android.os.Build.VERSION#CODENAME Build.VERSION.CODENAME}.
     *
     * @hide For platform use only; we don't expect developers to need to read this value.
     */
    @Nullable
    public String compileSdkVersionCodename;

    /**
     * Whether the package is an APEX package.
     */
    public boolean isApex;

    /**
     * Whether this is an active APEX package.
     * @hide
     */
    public boolean isActiveApex;

    /**
     * If the package is an APEX package (i.e. the value of {@link #isApex}
     * is true), this field is the package name of the APEX. If the package
     * is one APK-in-APEX app, this field is the package name of the parent
     * APEX that contains the app. If the package is not one of the above
     * two cases, this field is {@code null}.
     */
    @Nullable
    private String mApexPackageName;

    private boolean mIsAppMetadataVerified;

    public PackageInfo() {
    }

    /**
     * Returns true if the package is a valid Runtime Overlay package.
     * @hide
     */
    public boolean isOverlayPackage() {
        return overlayTarget != null;
    }

    /**
     * Returns true if the package is a valid static Runtime Overlay package. Static overlays
     * are not updatable outside of a system update and are safe to load in the system process.
     * @hide
     */
    public boolean isStaticOverlayPackage() {
        return overlayTarget != null && mOverlayIsStatic;
    }

    /**
     * Returns the time at which the app was archived for the user.  Units are as
     * per {@link System#currentTimeMillis()}.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public @CurrentTimeMillisLong long getArchiveTimeMillis() {
        return mArchiveTimeMillis;
    }

    /**
     * @hide
     */
    public void setArchiveTimeMillis(@CurrentTimeMillisLong long value) {
        mArchiveTimeMillis = value;
    }

    /**
     * If the package is an APEX package (i.e. the value of {@link #isApex}
     * is true), returns the package name of the APEX. If the package
     * is one APK-in-APEX app, returns the package name of the parent
     * APEX that contains the app. If the package is not one of the above
     * two cases, returns {@code null}.
     */
    @Nullable
    @FlaggedApi(android.content.pm.Flags.FLAG_PROVIDE_INFO_OF_APK_IN_APEX)
    public String getApexPackageName() {
        return mApexPackageName;
    }

    /**
     * @hide
     */
    public void setApexPackageName(@Nullable String apexPackageName) {
        mApexPackageName = apexPackageName;
    }

    /**
     * Returns the verification status of the App Metadata of the package. This status is set at the
     * package installation time by the developer verification service provider.
     */
    @FlaggedApi(android.content.pm.Flags.FLAG_VERIFICATION_SERVICE)
    public boolean isAppMetadataVerified() {
        return mIsAppMetadataVerified;
    }

    /**
     * @hide
     */
    public void setIsAppMetadataVerified(boolean isVerified) {
        mIsAppMetadataVerified = isVerified;
    }

    @Override
    public String toString() {
        return "PackageInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + packageName + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        final int preWriteSize = dest.dataSize();
        // Allow ApplicationInfo to be squashed.
        final boolean prevAllowSquashing = dest.allowSquashing();
        dest.writeString8(packageName);
        dest.writeString8Array(splitNames);
        dest.writeInt(versionCode);
        dest.writeInt(versionCodeMajor);
        dest.writeString8(versionName);
        dest.writeInt(baseRevisionCode);
        dest.writeIntArray(splitRevisionCodes);
        dest.writeString8(sharedUserId);
        dest.writeInt(sharedUserLabel);
        if (applicationInfo != null) {
            dest.writeInt(1);
            applicationInfo.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeLong(firstInstallTime);
        dest.writeLong(lastUpdateTime);
        dest.writeIntArray(gids);
        final int activitiesSize = writeAndCount(dest, activities, parcelableFlags);
        final int receiversSize = writeAndCount(dest, receivers, parcelableFlags);
        final int servicesSize = writeAndCount(dest, services, parcelableFlags);
        final int providersSize = writeAndCount(dest, providers, parcelableFlags);
        dest.writeTypedArray(instrumentation, parcelableFlags);
        final int permissionsSize = writeAndCount(dest, permissions, parcelableFlags);
        final int requestedPermissionsSize = writeAndCount(dest, requestedPermissions);
        dest.writeIntArray(requestedPermissionsFlags);
        writeRequestedPermissionsPurposes(dest);
        final int signaturesSize = writeAndCount(dest, signatures, parcelableFlags);
        dest.writeTypedArray(configPreferences, parcelableFlags);
        dest.writeTypedArray(reqFeatures, parcelableFlags);
        dest.writeTypedArray(featureGroups, parcelableFlags);
        final int attributionsSize = writeAndCount(dest, attributions, parcelableFlags);
        dest.writeInt(installLocation);
        dest.writeInt(isStub ? 1 : 0);
        dest.writeInt(coreApp ? 1 : 0);
        dest.writeInt(requiredForAllUsers ? 1 : 0);
        dest.writeString8(restrictedAccountType);
        dest.writeString8(requiredAccountType);
        dest.writeString8(overlayTarget);
        dest.writeString8(overlayCategory);
        dest.writeInt(overlayPriority);
        dest.writeBoolean(mOverlayIsStatic);
        dest.writeInt(compileSdkVersion);
        dest.writeString8(compileSdkVersionCodename);
        if (signingInfo != null) {
            dest.writeInt(1);
            signingInfo.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeBoolean(isApex);
        dest.writeBoolean(isActiveApex);
        dest.writeLong(mArchiveTimeMillis);
        if (mApexPackageName != null) {
            dest.writeInt(1);
            dest.writeString8(mApexPackageName);
        } else {
            dest.writeInt(0);
        }
        dest.writeBoolean(mIsAppMetadataVerified);
        dest.restoreAllowSquashing(prevAllowSquashing);

        final int elmSize = dest.dataSize() - preWriteSize;
        // The warning threshold is consistent with BaseParceledListSlice implementation
        if (elmSize > 16 * 1024) {
            StringBuilder sb = new StringBuilder();
            sb.append("Large parcel: size=").append(elmSize)
                    .append(" pkg=").append(packageName);

            if (activities != null) {
                sb.append(" actv=").append(activitiesSize);
                sb.append("(").append(activities.length).append(")");
            }
            if (receivers != null) {
                sb.append(" recv=").append(receiversSize);
                sb.append("(").append(receivers.length).append(")");
            }
            if (services != null) {
                sb.append(" serv=").append(servicesSize);
                sb.append("(").append(services.length).append(")");
            }
            if (providers != null) {
                sb.append(" prov=").append(providersSize);
                sb.append("(").append(providers.length).append(")");
            }
            if (permissions != null) {
                sb.append(" perm=").append(permissionsSize);
                sb.append("(").append(permissions.length).append(")");
            }
            if (requestedPermissions != null) {
                sb.append(" reqPerm=").append(requestedPermissionsSize);
                sb.append("(").append(requestedPermissions.length).append(")");
            }
            if (signatures != null) {
                sb.append(" sig=").append(signaturesSize);
                sb.append("(").append(signatures.length).append(")");
            }
            if (attributions != null) {
                sb.append(" attr=").append(attributionsSize);
                sb.append("(").append(attributions.length).append(")");
            }

            Log.w(TAG, sb.toString());
        }
    }

    private <T extends Parcelable> int writeAndCount(Parcel dest, @Nullable T[] val,
            int parcelableFlags) {
        final int preWriteSize = dest.dataSize();
        dest.writeTypedArray(val, parcelableFlags);
        return dest.dataSize() - preWriteSize;
    }

    private int writeAndCount(Parcel dest, String[] val) {
        final int preWriteSize = dest.dataSize();
        dest.writeString8Array(val);
        return dest.dataSize() - preWriteSize;
    }

    private void writeRequestedPermissionsPurposes(@NonNull Parcel dest) {
        if (requestedPermissionsPurposes.isEmpty()) {
            dest.writeBundle(null);
            return;
        }
        final Bundle bundle = new Bundle();
        requestedPermissionsPurposes.forEach(bundle::putParcelable);
        dest.writeBundle(bundle);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PackageInfo> CREATOR
            = new Parcelable.Creator<PackageInfo>() {
        @Override
        public PackageInfo createFromParcel(Parcel source) {
            return new PackageInfo(source);
        }

        @Override
        public PackageInfo[] newArray(int size) {
            return new PackageInfo[size];
        }
    };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private PackageInfo(Parcel source) {
        packageName = source.readString8();
        splitNames = source.createString8Array();
        versionCode = source.readInt();
        versionCodeMajor = source.readInt();
        versionName = source.readString8();
        baseRevisionCode = source.readInt();
        splitRevisionCodes = source.createIntArray();
        sharedUserId = source.readString8();
        sharedUserLabel = source.readInt();
        int hasApp = source.readInt();
        if (hasApp != 0) {
            applicationInfo = ApplicationInfo.CREATOR.createFromParcel(source);
        }
        firstInstallTime = source.readLong();
        lastUpdateTime = source.readLong();
        gids = source.createIntArray();
        activities = source.createTypedArray(ActivityInfo.CREATOR);
        receivers = source.createTypedArray(ActivityInfo.CREATOR);
        services = source.createTypedArray(ServiceInfo.CREATOR);
        providers = source.createTypedArray(ProviderInfo.CREATOR);
        instrumentation = source.createTypedArray(InstrumentationInfo.CREATOR);
        permissions = source.createTypedArray(PermissionInfo.CREATOR);
        requestedPermissions = source.createString8Array();
        requestedPermissionsFlags = source.createIntArray();
        readRequestedPermissionsPurposes(source);
        signatures = source.createTypedArray(Signature.CREATOR);
        configPreferences = source.createTypedArray(ConfigurationInfo.CREATOR);
        reqFeatures = source.createTypedArray(FeatureInfo.CREATOR);
        featureGroups = source.createTypedArray(FeatureGroupInfo.CREATOR);
        attributions = source.createTypedArray(Attribution.CREATOR);
        installLocation = source.readInt();
        isStub = source.readInt() != 0;
        coreApp = source.readInt() != 0;
        requiredForAllUsers = source.readInt() != 0;
        restrictedAccountType = source.readString8();
        requiredAccountType = source.readString8();
        overlayTarget = source.readString8();
        overlayCategory = source.readString8();
        overlayPriority = source.readInt();
        mOverlayIsStatic = source.readBoolean();
        compileSdkVersion = source.readInt();
        compileSdkVersionCodename = source.readString8();
        int hasSigningInfo = source.readInt();
        if (hasSigningInfo != 0) {
            signingInfo = SigningInfo.CREATOR.createFromParcel(source);
        }
        isApex = source.readBoolean();
        isActiveApex = source.readBoolean();
        mArchiveTimeMillis = source.readLong();
        int hasApexPackageName = source.readInt();
        if (hasApexPackageName != 0) {
            mApexPackageName = source.readString8();
        }
        mIsAppMetadataVerified = source.readBoolean();
    }

    private void readRequestedPermissionsPurposes(@NonNull Parcel in) {
        final Bundle bundle = in.readBundle(UsesPermissionPurposeInfo.class.getClassLoader());
        Map<String, UsesPermissionPurposeInfo> purposeInfos = Collections.emptyMap();
        if (bundle == null) {
            requestedPermissionsPurposes = purposeInfos;
            return;
        }
        for (String key : bundle.keySet()) {
            purposeInfos = CollectionUtils.add(purposeInfos, key, bundle.getParcelable(
                    key, UsesPermissionPurposeInfo.class));
        }
        requestedPermissionsPurposes = purposeInfos;
    }
}
