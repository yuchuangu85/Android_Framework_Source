/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/av/media/aidl/audio-permission-aidl-java-source/gen/com/android/media/permission/UidPackageState.java.d -o out/soong/.intermediates/frameworks/av/media/aidl/audio-permission-aidl-java-source/gen -Nframeworks/av/media/aidl frameworks/av/media/aidl/com/android/media/permission/UidPackageState.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package com.android.media.permission;
/**
 * Entity representing the packages associated with a particular app-id. Multiple packages can be
 * assigned a specific app-id.
 * @hide
 */
public class UidPackageState implements android.os.Parcelable
{
  // Technically, an app-id for real packages, since the package is associated with an appId,
  // which is associated with a uid per user.
  public int uid = 0;
  public java.util.List<com.android.media.permission.UidPackageState.PackageState> packageStates;
  public static final android.os.Parcelable.Creator<UidPackageState> CREATOR = new android.os.Parcelable.Creator<UidPackageState>() {
    @Override
    public UidPackageState createFromParcel(android.os.Parcel _aidl_source) {
      UidPackageState _aidl_out = new UidPackageState();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public UidPackageState[] newArray(int _aidl_size) {
      return new UidPackageState[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(uid);
    _aidl_parcel.writeTypedList(packageStates, _aidl_flag);
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
      uid = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      packageStates = _aidl_parcel.createTypedArrayList(com.android.media.permission.UidPackageState.PackageState.CREATOR);
    } finally {
      if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
        throw new android.os.BadParcelableException("Overflow in the size of parcelable");
      }
      _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
    }
  }
  @Override
  public String toString() {
    java.util.StringJoiner _aidl_sj = new java.util.StringJoiner(", ", "{", "}");
    _aidl_sj.add("uid: " + (uid));
    _aidl_sj.add("packageStates: " + (java.util.Objects.toString(packageStates)));
    return "UidPackageState" + _aidl_sj.toString()  ;
  }
  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    if (!(other instanceof UidPackageState)) return false;
    UidPackageState that = (UidPackageState)other;
    if (!java.util.Objects.deepEquals(uid, that.uid)) return false;
    if (!java.util.Objects.deepEquals(packageStates, that.packageStates)) return false;
    return true;
  }

  @Override
  public int hashCode() {
    return java.util.Arrays.deepHashCode(java.util.Arrays.asList(uid, packageStates).toArray());
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    _mask |= describeContents(packageStates);
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof java.util.Collection) {
      int _mask = 0;
      for (Object o : (java.util.Collection) _v) {
        _mask |= describeContents(o);
      }
      return _mask;
    }
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }
  /**
   * State we retain for an individual package
   * @hide
   */
  public static class PackageState implements android.os.Parcelable
  {
    public java.lang.String packageName;
    public int targetSdk = 0;
    public boolean isPlaybackCaptureAllowed = false;
    public static final android.os.Parcelable.Creator<PackageState> CREATOR = new android.os.Parcelable.Creator<PackageState>() {
      @Override
      public PackageState createFromParcel(android.os.Parcel _aidl_source) {
        PackageState _aidl_out = new PackageState();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public PackageState[] newArray(int _aidl_size) {
        return new PackageState[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeString(packageName);
      _aidl_parcel.writeInt(targetSdk);
      _aidl_parcel.writeBoolean(isPlaybackCaptureAllowed);
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
        targetSdk = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        isPlaybackCaptureAllowed = _aidl_parcel.readBoolean();
      } finally {
        if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
          throw new android.os.BadParcelableException("Overflow in the size of parcelable");
        }
        _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
      }
    }
    @Override
    public String toString() {
      java.util.StringJoiner _aidl_sj = new java.util.StringJoiner(", ", "{", "}");
      _aidl_sj.add("packageName: " + (java.util.Objects.toString(packageName)));
      _aidl_sj.add("targetSdk: " + (targetSdk));
      _aidl_sj.add("isPlaybackCaptureAllowed: " + (isPlaybackCaptureAllowed));
      return "PackageState" + _aidl_sj.toString()  ;
    }
    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (other == null) return false;
      if (!(other instanceof PackageState)) return false;
      PackageState that = (PackageState)other;
      if (!java.util.Objects.deepEquals(packageName, that.packageName)) return false;
      if (!java.util.Objects.deepEquals(targetSdk, that.targetSdk)) return false;
      if (!java.util.Objects.deepEquals(isPlaybackCaptureAllowed, that.isPlaybackCaptureAllowed)) return false;
      return true;
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.deepHashCode(java.util.Arrays.asList(packageName, targetSdk, isPlaybackCaptureAllowed).toArray());
    }
    @Override
    public int describeContents() {
      int _mask = 0;
      return _mask;
    }
  }
}
