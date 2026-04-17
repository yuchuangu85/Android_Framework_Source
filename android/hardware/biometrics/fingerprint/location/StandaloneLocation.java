/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 0a40e5aa0a942c5a4e577ffa6ade668ac294e269 -t --stability vintf --min_sdk_version platform_apis -pout/soong/.intermediates/hardware/interfaces/biometrics/common/aidl/android.hardware.biometrics.common_interface/4/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/keymaster/aidl/android.hardware.keymaster_interface/4/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen/android/hardware/biometrics/fingerprint/location/StandaloneLocation.java.d -o out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen -Nhardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5 hardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5/android/hardware/biometrics/fingerprint/location/StandaloneLocation.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.biometrics.fingerprint.location;
/** @hide */
public class StandaloneLocation implements android.os.Parcelable
{
  public byte location;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<StandaloneLocation> CREATOR = new android.os.Parcelable.Creator<StandaloneLocation>() {
    @Override
    public StandaloneLocation createFromParcel(android.os.Parcel _aidl_source) {
      StandaloneLocation _aidl_out = new StandaloneLocation();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public StandaloneLocation[] newArray(int _aidl_size) {
      return new StandaloneLocation[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeByte(location);
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
      location = _aidl_parcel.readByte();
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
    return _mask;
  }
}
