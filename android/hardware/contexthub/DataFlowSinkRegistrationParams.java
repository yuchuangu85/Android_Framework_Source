/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 4a4662588b9e38b5e93c37e46353efac231f7a98 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen/android/hardware/contexthub/DataFlowSinkRegistrationParams.java.d -o out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen -Nhardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5 hardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5/android/hardware/contexthub/DataFlowSinkRegistrationParams.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.contexthub;
public class DataFlowSinkRegistrationParams implements android.os.Parcelable
{
  public android.hardware.contexthub.DataFlowSinkContext context;
  public android.hardware.contexthub.EndpointId sourceId;
  public android.hardware.contexthub.EndpointId sinkId;
  public android.hardware.contexthub.Message msg;
  public int sessionId = 0;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<DataFlowSinkRegistrationParams> CREATOR = new android.os.Parcelable.Creator<DataFlowSinkRegistrationParams>() {
    @Override
    public DataFlowSinkRegistrationParams createFromParcel(android.os.Parcel _aidl_source) {
      DataFlowSinkRegistrationParams _aidl_out = new DataFlowSinkRegistrationParams();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public DataFlowSinkRegistrationParams[] newArray(int _aidl_size) {
      return new DataFlowSinkRegistrationParams[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeTypedObject(context, _aidl_flag);
    _aidl_parcel.writeTypedObject(sourceId, _aidl_flag);
    _aidl_parcel.writeTypedObject(sinkId, _aidl_flag);
    _aidl_parcel.writeTypedObject(msg, _aidl_flag);
    _aidl_parcel.writeInt(sessionId);
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
      context = _aidl_parcel.readTypedObject(android.hardware.contexthub.DataFlowSinkContext.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sourceId = _aidl_parcel.readTypedObject(android.hardware.contexthub.EndpointId.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sinkId = _aidl_parcel.readTypedObject(android.hardware.contexthub.EndpointId.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      msg = _aidl_parcel.readTypedObject(android.hardware.contexthub.Message.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sessionId = _aidl_parcel.readInt();
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
    _mask |= describeContents(context);
    _mask |= describeContents(sourceId);
    _mask |= describeContents(sinkId);
    _mask |= describeContents(msg);
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
