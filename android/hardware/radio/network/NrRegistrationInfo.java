/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash d30e321bff565b6a44f81ce4113979c4844a61a3 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen/android/hardware/radio/network/NrRegistrationInfo.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5/android/hardware/radio/network/NrRegistrationInfo.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.network;
/** @hide */
public class NrRegistrationInfo implements android.os.Parcelable
{
  public android.hardware.radio.network.NrVopsInfo nrVopsInfo;
  public int satelliteTechnology = android.hardware.radio.network.SatelliteTechnology.SAT_TECH_NONE;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<NrRegistrationInfo> CREATOR = new android.os.Parcelable.Creator<NrRegistrationInfo>() {
    @Override
    public NrRegistrationInfo createFromParcel(android.os.Parcel _aidl_source) {
      NrRegistrationInfo _aidl_out = new NrRegistrationInfo();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public NrRegistrationInfo[] newArray(int _aidl_size) {
      return new NrRegistrationInfo[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeTypedObject(nrVopsInfo, _aidl_flag);
    _aidl_parcel.writeInt(satelliteTechnology);
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
      nrVopsInfo = _aidl_parcel.readTypedObject(android.hardware.radio.network.NrVopsInfo.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      satelliteTechnology = _aidl_parcel.readInt();
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
    _aidl_sj.add("nrVopsInfo: " + (java.util.Objects.toString(nrVopsInfo)));
    _aidl_sj.add("satelliteTechnology: " + (android.hardware.radio.network.SatelliteTechnology.$.toString(satelliteTechnology)));
    return "NrRegistrationInfo" + _aidl_sj.toString()  ;
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    _mask |= describeContents(nrVopsInfo);
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
