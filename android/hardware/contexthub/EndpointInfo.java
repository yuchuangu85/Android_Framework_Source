/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 4a4662588b9e38b5e93c37e46353efac231f7a98 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen/android/hardware/contexthub/EndpointInfo.java.d -o out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen -Nhardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5 hardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5/android/hardware/contexthub/EndpointInfo.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.contexthub;
public class EndpointInfo implements android.os.Parcelable
{
  public android.hardware.contexthub.EndpointId id;
  public int type;
  public java.lang.String name;
  public int version = 0;
  public java.lang.String tag;
  public java.lang.String[] requiredPermissions;
  public android.hardware.contexthub.Service[] services;
  public android.hardware.contexthub.EndpointInfo.SharedDataSupportVersion sharedDataSupportVersion;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<EndpointInfo> CREATOR = new android.os.Parcelable.Creator<EndpointInfo>() {
    @Override
    public EndpointInfo createFromParcel(android.os.Parcel _aidl_source) {
      EndpointInfo _aidl_out = new EndpointInfo();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public EndpointInfo[] newArray(int _aidl_size) {
      return new EndpointInfo[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeTypedObject(id, _aidl_flag);
    _aidl_parcel.writeInt(type);
    _aidl_parcel.writeString(name);
    _aidl_parcel.writeInt(version);
    _aidl_parcel.writeString(tag);
    _aidl_parcel.writeStringArray(requiredPermissions);
    _aidl_parcel.writeTypedArray(services, _aidl_flag);
    _aidl_parcel.writeTypedObject(sharedDataSupportVersion, _aidl_flag);
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
      id = _aidl_parcel.readTypedObject(android.hardware.contexthub.EndpointId.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      type = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      name = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      version = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      tag = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      requiredPermissions = _aidl_parcel.createStringArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      services = _aidl_parcel.createTypedArray(android.hardware.contexthub.Service.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sharedDataSupportVersion = _aidl_parcel.readTypedObject(android.hardware.contexthub.EndpointInfo.SharedDataSupportVersion.CREATOR);
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
    _mask |= describeContents(id);
    _mask |= describeContents(services);
    _mask |= describeContents(sharedDataSupportVersion);
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof Object[]) {
      int _mask = 0;
      for (Object o : (Object[]) _v) {
        _mask |= describeContents(o);
      }
      return _mask;
    }
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }
  public static @interface EndpointType {
    public static final int FRAMEWORK = 1;
    public static final int APP = 2;
    public static final int NATIVE = 3;
    public static final int NANOAPP = 4;
    public static final int GENERIC = 5;
  }
  public static class SharedDataSupportVersion implements android.os.Parcelable
  {
    public android.hardware.contexthub.SharedDataRegion.Version version;
    public byte minimumCompatibleMajorVersion = 0;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<SharedDataSupportVersion> CREATOR = new android.os.Parcelable.Creator<SharedDataSupportVersion>() {
      @Override
      public SharedDataSupportVersion createFromParcel(android.os.Parcel _aidl_source) {
        SharedDataSupportVersion _aidl_out = new SharedDataSupportVersion();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public SharedDataSupportVersion[] newArray(int _aidl_size) {
        return new SharedDataSupportVersion[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeTypedObject(version, _aidl_flag);
      _aidl_parcel.writeByte(minimumCompatibleMajorVersion);
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
        version = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.Version.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        minimumCompatibleMajorVersion = _aidl_parcel.readByte();
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
      _mask |= describeContents(version);
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
}
