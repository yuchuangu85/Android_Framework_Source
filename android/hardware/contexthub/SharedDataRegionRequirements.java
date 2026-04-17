/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 4a4662588b9e38b5e93c37e46353efac231f7a98 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen/android/hardware/contexthub/SharedDataRegionRequirements.java.d -o out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen -Nhardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5 hardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5/android/hardware/contexthub/SharedDataRegionRequirements.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.contexthub;
public class SharedDataRegionRequirements implements android.os.Parcelable
{
  public long sizeBytes = 0L;
  public java.lang.String[] permissions;
  public long[] targetHubIds;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<SharedDataRegionRequirements> CREATOR = new android.os.Parcelable.Creator<SharedDataRegionRequirements>() {
    @Override
    public SharedDataRegionRequirements createFromParcel(android.os.Parcel _aidl_source) {
      SharedDataRegionRequirements _aidl_out = new SharedDataRegionRequirements();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public SharedDataRegionRequirements[] newArray(int _aidl_size) {
      return new SharedDataRegionRequirements[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeLong(sizeBytes);
    _aidl_parcel.writeStringArray(permissions);
    _aidl_parcel.writeLongArray(targetHubIds);
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
      sizeBytes = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      permissions = _aidl_parcel.createStringArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      targetHubIds = _aidl_parcel.createLongArray();
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
