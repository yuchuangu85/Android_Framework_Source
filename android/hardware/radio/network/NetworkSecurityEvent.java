/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash d30e321bff565b6a44f81ce4113979c4844a61a3 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen/android/hardware/radio/network/NetworkSecurityEvent.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5/android/hardware/radio/network/NetworkSecurityEvent.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.network;
/** @hide */
public class NetworkSecurityEvent implements android.os.Parcelable
{
  public int alertCategory = android.hardware.radio.network.AlertCategory.UNSPECIFIED;
  public int alertStatus = android.hardware.radio.network.AlertStatus.UNSPECIFIED;
  public int[] reasonCodes;
  public long cellId = 0L;
  public int physicalCellId = 0;
  public int arfcn = 0;
  public java.lang.String plmn;
  public int rat = android.hardware.radio.RadioTechnology.UNKNOWN;
  public boolean isEmergency = false;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<NetworkSecurityEvent> CREATOR = new android.os.Parcelable.Creator<NetworkSecurityEvent>() {
    @Override
    public NetworkSecurityEvent createFromParcel(android.os.Parcel _aidl_source) {
      NetworkSecurityEvent _aidl_out = new NetworkSecurityEvent();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public NetworkSecurityEvent[] newArray(int _aidl_size) {
      return new NetworkSecurityEvent[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(alertCategory);
    _aidl_parcel.writeInt(alertStatus);
    _aidl_parcel.writeIntArray(reasonCodes);
    _aidl_parcel.writeLong(cellId);
    _aidl_parcel.writeInt(physicalCellId);
    _aidl_parcel.writeInt(arfcn);
    _aidl_parcel.writeString(plmn);
    _aidl_parcel.writeInt(rat);
    _aidl_parcel.writeBoolean(isEmergency);
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
      alertCategory = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      alertStatus = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      reasonCodes = _aidl_parcel.createIntArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      cellId = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      physicalCellId = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      arfcn = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      plmn = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      rat = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      isEmergency = _aidl_parcel.readBoolean();
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
    _aidl_sj.add("alertCategory: " + (android.hardware.radio.network.AlertCategory.$.toString(alertCategory)));
    _aidl_sj.add("alertStatus: " + (android.hardware.radio.network.AlertStatus.$.toString(alertStatus)));
    _aidl_sj.add("reasonCodes: " + (android.hardware.radio.network.ReasonCode.$.arrayToString(reasonCodes)));
    _aidl_sj.add("cellId: " + (cellId));
    _aidl_sj.add("physicalCellId: " + (physicalCellId));
    _aidl_sj.add("arfcn: " + (arfcn));
    _aidl_sj.add("plmn: " + (java.util.Objects.toString(plmn)));
    _aidl_sj.add("rat: " + (android.hardware.radio.RadioTechnology.$.toString(rat)));
    _aidl_sj.add("isEmergency: " + (isEmergency));
    return "NetworkSecurityEvent" + _aidl_sj.toString()  ;
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    return _mask;
  }
}
