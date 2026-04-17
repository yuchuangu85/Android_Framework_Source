/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/native/android/packagemanager_aidl-java-source/gen/android/content/pm/SigningInfoNative.java.d -o out/soong/.intermediates/frameworks/base/native/android/packagemanager_aidl-java-source/gen -Nframeworks/base/native/android/aidl frameworks/base/native/android/aidl/android/content/pm/SigningInfoNative.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.content.pm;
/**
 * Information pertaining to the signing certificates used to sign a package.
 * 
 * At present it's a small subset because it includes only items that have been required by
 * native code, but it uses the same structure and naming as the full SigningInfo in order to
 * ensure other elements can be cleanly added as necessary.
 * 
 * See frameworks/base/core/java/android/content/pm/SigningInfo.java.
 */
public class SigningInfoNative implements android.os.Parcelable
{
  /**
   * APK content signers.  Includes the content of `SigningInfo#apkContentSigners()` if
   * `SigningInfo#hasMultipleSigners()` returns true, or the content of
   * `SigningInfo#getSigningCertificateHistory` otherwise.  Empty array if not set (i.e. not
   * nullable).
   */
  public android.content.pm.SignatureNative[] apkContentSigners;
  public static final android.os.Parcelable.Creator<SigningInfoNative> CREATOR = new android.os.Parcelable.Creator<SigningInfoNative>() {
    @Override
    public SigningInfoNative createFromParcel(android.os.Parcel _aidl_source) {
      SigningInfoNative _aidl_out = new SigningInfoNative();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public SigningInfoNative[] newArray(int _aidl_size) {
      return new SigningInfoNative[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeTypedArray(apkContentSigners, _aidl_flag);
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
      apkContentSigners = _aidl_parcel.createTypedArray(android.content.pm.SignatureNative.CREATOR);
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
    _mask |= describeContents(apkContentSigners);
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof Object[]) {
      int _mask = 0;
      for (Object o : (Object[]) _v) {
        _mask |= describeContents(o);
      }
      return _mask;
    }
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }
}
