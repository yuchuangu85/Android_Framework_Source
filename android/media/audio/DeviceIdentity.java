/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current -pout/soong/.intermediates/frameworks/native/libs/permission/framework-permission-aidl_interface/preprocessed.aidl --ninja -d out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen/android/media/audio/DeviceIdentity.java.d -o out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen -Nframeworks/base/media/aidl frameworks/base/media/aidl/android/media/audio/DeviceIdentity.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.audio;
/**
 * Corresponds to the identity (from the perspective of the audio framework) of a audio device,
 * whose live state is expressed via AudioDeviceInfo.
 * @hide
 */
public class DeviceIdentity implements android.os.Parcelable
{
  // Defaults invalid type/role
  public int role = 0;
  public int type = 0;
  public java.lang.String address;
  public static final android.os.Parcelable.Creator<DeviceIdentity> CREATOR = new android.os.Parcelable.Creator<DeviceIdentity>() {
    @Override
    public DeviceIdentity createFromParcel(android.os.Parcel _aidl_source) {
      DeviceIdentity _aidl_out = new DeviceIdentity();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public DeviceIdentity[] newArray(int _aidl_size) {
      return new DeviceIdentity[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(role);
    _aidl_parcel.writeInt(type);
    _aidl_parcel.writeString(address);
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
      role = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      type = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      address = _aidl_parcel.readString();
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
    _aidl_sj.add("role: " + (role));
    _aidl_sj.add("type: " + (type));
    _aidl_sj.add("address: " + (java.util.Objects.toString(address)));
    return "DeviceIdentity" + _aidl_sj.toString()  ;
  }
  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    if (!(other instanceof DeviceIdentity)) return false;
    DeviceIdentity that = (DeviceIdentity)other;
    if (!java.util.Objects.deepEquals(role, that.role)) return false;
    if (!java.util.Objects.deepEquals(type, that.type)) return false;
    if (!java.util.Objects.deepEquals(address, that.address)) return false;
    return true;
  }

  @Override
  public int hashCode() {
    return java.util.Arrays.deepHashCode(java.util.Arrays.asList(role, type, address).toArray());
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    return _mask;
  }
}
