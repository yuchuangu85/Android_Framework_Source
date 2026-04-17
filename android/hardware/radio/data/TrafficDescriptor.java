/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 6acb69b7e1400ebd9d4e169667e93f778d3ec27c --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.data-V5-java-source/gen/android/hardware/radio/data/TrafficDescriptor.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.data-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.data/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.data/5/android/hardware/radio/data/TrafficDescriptor.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.data;
/** @hide */
public class TrafficDescriptor implements android.os.Parcelable
{
  public java.lang.String dnn;
  public android.hardware.radio.data.OsAppId osAppId;
  public byte connectionCapability = android.hardware.radio.data.TrafficDescriptor.ConnectionCapability.UNKNOWN;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<TrafficDescriptor> CREATOR = new android.os.Parcelable.Creator<TrafficDescriptor>() {
    @Override
    public TrafficDescriptor createFromParcel(android.os.Parcel _aidl_source) {
      TrafficDescriptor _aidl_out = new TrafficDescriptor();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public TrafficDescriptor[] newArray(int _aidl_size) {
      return new TrafficDescriptor[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeString(dnn);
    _aidl_parcel.writeTypedObject(osAppId, _aidl_flag);
    _aidl_parcel.writeByte(connectionCapability);
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
      dnn = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      osAppId = _aidl_parcel.readTypedObject(android.hardware.radio.data.OsAppId.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      connectionCapability = _aidl_parcel.readByte();
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
    _aidl_sj.add("dnn: " + (java.util.Objects.toString(dnn)));
    _aidl_sj.add("osAppId: " + (java.util.Objects.toString(osAppId)));
    _aidl_sj.add("connectionCapability: " + (connectionCapability));
    return "TrafficDescriptor" + _aidl_sj.toString()  ;
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    _mask |= describeContents(osAppId);
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }
  public static @interface ConnectionCapability {
    public static final byte UNKNOWN = 0;
    public static final byte IMS = 1;
    public static final byte MMS = 2;
    public static final byte SUPL = 4;
    public static final byte INTERNET = 8;
    public static final byte LCS_USER_PLANE_POSITIONING = 16;
    public static final byte IOT_DELAY_TOLERANT = -95;
    public static final byte IOT_NON_DELAY_TOLERANT = -94;
    public static final byte DOWNLINK_STREAMING = -93;
    public static final byte UPLINK_STREAMING = -92;
    public static final byte VEHICULAR_COMMUNICATIONS = -91;
    public static final byte REAL_TIME_INTERACTIVE = -90;
    public static final byte UNIFIED_COMMUNICATIONS = -89;
    public static final byte BACKGROUND = -88;
    public static final byte MISSION_CRITICAL_COMMUNICATIONS = -87;
    public static final byte TIME_CRITICAL_COMMUNICATIONS = -86;
    public static final byte LOW_LATENCY_LOSS_TOLERANT_UNACK = -85;
  }
}
