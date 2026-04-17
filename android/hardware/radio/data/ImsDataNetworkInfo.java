/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 6acb69b7e1400ebd9d4e169667e93f778d3ec27c --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.data-V5-java-source/gen/android/hardware/radio/data/ImsDataNetworkInfo.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.data-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.data/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.data/5/android/hardware/radio/data/ImsDataNetworkInfo.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.data;
/** @hide */
public class ImsDataNetworkInfo implements android.os.Parcelable
{
  public int accessNetwork = android.hardware.radio.AccessNetwork.UNKNOWN;
  public int dataNetworkState = android.hardware.radio.data.DataNetworkState.UNKNOWN;
  public int physicalTransportType = android.hardware.radio.data.TransportType.WWAN;
  public int physicalNetworkModemId = 0;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<ImsDataNetworkInfo> CREATOR = new android.os.Parcelable.Creator<ImsDataNetworkInfo>() {
    @Override
    public ImsDataNetworkInfo createFromParcel(android.os.Parcel _aidl_source) {
      ImsDataNetworkInfo _aidl_out = new ImsDataNetworkInfo();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public ImsDataNetworkInfo[] newArray(int _aidl_size) {
      return new ImsDataNetworkInfo[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(accessNetwork);
    _aidl_parcel.writeInt(dataNetworkState);
    _aidl_parcel.writeInt(physicalTransportType);
    _aidl_parcel.writeInt(physicalNetworkModemId);
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
      accessNetwork = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      dataNetworkState = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      physicalTransportType = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      physicalNetworkModemId = _aidl_parcel.readInt();
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
    _aidl_sj.add("accessNetwork: " + (android.hardware.radio.AccessNetwork.$.toString(accessNetwork)));
    _aidl_sj.add("dataNetworkState: " + (android.hardware.radio.data.DataNetworkState.$.toString(dataNetworkState)));
    _aidl_sj.add("physicalTransportType: " + (android.hardware.radio.data.TransportType.$.toString(physicalTransportType)));
    _aidl_sj.add("physicalNetworkModemId: " + (physicalNetworkModemId));
    return "ImsDataNetworkInfo" + _aidl_sj.toString()  ;
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    return _mask;
  }
}
