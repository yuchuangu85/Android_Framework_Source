/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/native/android/packagemanager_aidl-java-source/gen/android/content/pm/PackageInfoNative.java.d -o out/soong/.intermediates/frameworks/base/native/android/packagemanager_aidl-java-source/gen -Nframeworks/base/native/android/aidl frameworks/base/native/android/aidl/android/content/pm/PackageInfoNative.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.content.pm;
/**
 * Overall information about the contents of a package.  This corresponds to a subset of the
 * information collected from AndroidManifest.xml
 * 
 * At present it's a very small subset, because it includes only items that have been required
 * by native code, but it uses the same structure and naming as the full PackageInfo in order
 * to ensure other elements can be cleanly added as necessary.
 * 
 * See frameworks/base/core/java/android/content/pm/PackageInfo.java.
 */
public class PackageInfoNative implements android.os.Parcelable
{
  public java.lang.String packageName;
  /**
   * Signing information read from the package file, potentially including past signing
   * certificates no longer used after signing certificate rotation.
   */
  public android.content.pm.SigningInfoNative signingInfo;
  /** Full path to the base APK for this application. */
  public java.lang.String sourceDir;
  /**
   * Full paths to split APKs.
   * May be null if no splits are installed.
   */
  public java.lang.String[] splitSourceDirs;
  public static final android.os.Parcelable.Creator<PackageInfoNative> CREATOR = new android.os.Parcelable.Creator<PackageInfoNative>() {
    @Override
    public PackageInfoNative createFromParcel(android.os.Parcel _aidl_source) {
      PackageInfoNative _aidl_out = new PackageInfoNative();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public PackageInfoNative[] newArray(int _aidl_size) {
      return new PackageInfoNative[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeString(packageName);
    _aidl_parcel.writeTypedObject(signingInfo, _aidl_flag);
    _aidl_parcel.writeString(sourceDir);
    _aidl_parcel.writeStringArray(splitSourceDirs);
    int _aidl_end_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.setDataPosition(_aidl_start_pos);
    _aidl_parcel.writeInt(_aidl_end_pos - _aidl_start_pos);
    _aidl_parcel.setDataPosition(_aidl_end_pos);
  }
  public final void readFromParcel(android.os.Parcel _aidl_parcel)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    int _aidl_parcelable_size = _aidl_parcel.readInt();
    try {
      if (_aidl_parcelable_size < 4) throw new android.os.BadParcelableException("Parcelable too small");;
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      packageName = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      signingInfo = _aidl_parcel.readTypedObject(android.content.pm.SigningInfoNative.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sourceDir = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      splitSourceDirs = _aidl_parcel.createStringArray();
    } finally {
      if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
        throw new android.os.BadParcelableException("Overflow in the size of parcelable");
      }
      _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
    }
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    _mask |= describeContents(signingInfo);
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }
}
