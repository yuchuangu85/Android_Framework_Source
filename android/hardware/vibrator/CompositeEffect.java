/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 4 --hash dec155403ea3aa5395b0226de399873712b16082 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/common/fmq/aidl/android.hardware.common.fmq_interface/1/preprocessed.aidl -pout/soong/.intermediates/system/hardware/interfaces/media/android.media.audio.common.types_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/vibrator/aidl/android.hardware.vibrator-V4-java-source/gen/android/hardware/vibrator/CompositeEffect.java.d -o out/soong/.intermediates/hardware/interfaces/vibrator/aidl/android.hardware.vibrator-V4-java-source/gen -Iframeworks/native/aidl/binder -Nhardware/interfaces/vibrator/aidl/aidl_api/android.hardware.vibrator/4 hardware/interfaces/vibrator/aidl/aidl_api/android.hardware.vibrator/4/android/hardware/vibrator/CompositeEffect.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.vibrator;
public class CompositeEffect implements android.os.Parcelable
{
  public int delayMs = 0;
  public int primitive = android.hardware.vibrator.CompositePrimitive.NOOP;
  public float scale = 0.000000f;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<CompositeEffect> CREATOR = new android.os.Parcelable.Creator<CompositeEffect>() {
    @Override
    public CompositeEffect createFromParcel(android.os.Parcel _aidl_source) {
      CompositeEffect _aidl_out = new CompositeEffect();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public CompositeEffect[] newArray(int _aidl_size) {
      return new CompositeEffect[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(delayMs);
    _aidl_parcel.writeInt(primitive);
    _aidl_parcel.writeFloat(scale);
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
      delayMs = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      primitive = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      scale = _aidl_parcel.readFloat();
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
