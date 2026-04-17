/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 0a40e5aa0a942c5a4e577ffa6ade668ac294e269 -t --stability vintf --min_sdk_version platform_apis -pout/soong/.intermediates/hardware/interfaces/biometrics/common/aidl/android.hardware.biometrics.common_interface/4/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/keymaster/aidl/android.hardware.keymaster_interface/4/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen/android/hardware/biometrics/fingerprint/SensorLocation.java.d -o out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen -Nhardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5 hardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5/android/hardware/biometrics/fingerprint/SensorLocation.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.biometrics.fingerprint;
/** @hide */
public class SensorLocation implements android.os.Parcelable
{
  /** @deprecated use the display field instead. This field was never used. */
  @Deprecated
  public int displayId = 0;
  /** @deprecated use sensorLocationData with a specific struct (e.g., UnderDisplayLocation) instead. This value will be ignored if sensorLocationData is set. */
  @Deprecated
  public int sensorLocationX = 0;
  /** @deprecated use sensorLocationData with a specific struct (e.g., UnderDisplayLocation) instead. This value will be ignored if sensorLocationData is set. */
  @Deprecated
  public int sensorLocationY = 0;
  /** @deprecated use sensorLocationData with a specific struct (e.g., UnderDisplayLocation) instead. This value will be ignored if sensorLocationData is set. */
  @Deprecated
  public int sensorRadius = 0;
  public java.lang.String display = "";
  public byte sensorShape = android.hardware.biometrics.fingerprint.SensorShape.CIRCLE;
  public android.hardware.biometrics.fingerprint.SensorLocationData sensorLocationData;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<SensorLocation> CREATOR = new android.os.Parcelable.Creator<SensorLocation>() {
    @Override
    public SensorLocation createFromParcel(android.os.Parcel _aidl_source) {
      SensorLocation _aidl_out = new SensorLocation();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public SensorLocation[] newArray(int _aidl_size) {
      return new SensorLocation[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(displayId);
    _aidl_parcel.writeInt(sensorLocationX);
    _aidl_parcel.writeInt(sensorLocationY);
    _aidl_parcel.writeInt(sensorRadius);
    _aidl_parcel.writeString(display);
    _aidl_parcel.writeByte(sensorShape);
    _aidl_parcel.writeTypedObject(sensorLocationData, _aidl_flag);
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
      displayId = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sensorLocationX = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sensorLocationY = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sensorRadius = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      display = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sensorShape = _aidl_parcel.readByte();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sensorLocationData = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.SensorLocationData.CREATOR);
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
    _mask |= describeContents(sensorLocationData);
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
