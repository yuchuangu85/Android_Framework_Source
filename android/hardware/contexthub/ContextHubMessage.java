/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 4a4662588b9e38b5e93c37e46353efac231f7a98 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen/android/hardware/contexthub/ContextHubMessage.java.d -o out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen -Nhardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5 hardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5/android/hardware/contexthub/ContextHubMessage.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.contexthub;
public class ContextHubMessage implements android.os.Parcelable
{
  public long nanoappId = 0L;
  public char hostEndPoint = '\0';
  public int messageType = 0;
  public byte[] messageBody;
  public java.lang.String[] permissions;
  public boolean isReliable = false;
  public int messageSequenceNumber = 0;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<ContextHubMessage> CREATOR = new android.os.Parcelable.Creator<ContextHubMessage>() {
    @Override
    public ContextHubMessage createFromParcel(android.os.Parcel _aidl_source) {
      ContextHubMessage _aidl_out = new ContextHubMessage();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public ContextHubMessage[] newArray(int _aidl_size) {
      return new ContextHubMessage[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeLong(nanoappId);
    _aidl_parcel.writeInt(((int)hostEndPoint));
    _aidl_parcel.writeInt(messageType);
    _aidl_parcel.writeByteArray(messageBody);
    _aidl_parcel.writeStringArray(permissions);
    _aidl_parcel.writeBoolean(isReliable);
    _aidl_parcel.writeInt(messageSequenceNumber);
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
      nanoappId = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      hostEndPoint = (char)_aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      messageType = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      messageBody = _aidl_parcel.createByteArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      permissions = _aidl_parcel.createStringArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      isReliable = _aidl_parcel.readBoolean();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      messageSequenceNumber = _aidl_parcel.readInt();
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
