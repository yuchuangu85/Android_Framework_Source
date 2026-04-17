/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current -pout/soong/.intermediates/frameworks/native/libs/permission/framework-permission-aidl_interface/preprocessed.aidl --ninja -d out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen/android/media/audio/AudioModeSessionRequest.java.d -o out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen -Nframeworks/base/media/aidl frameworks/base/media/aidl/android/media/audio/AudioModeSessionRequest.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.audio;
/** {@hide} */
public class AudioModeSessionRequest implements android.os.Parcelable
{
  public int mode = 0;
  public boolean isDisplayActiveUseCase = false;
  public android.content.AttributionSourceState attributionSource;
  public android.content.AttributionSourceState clientAttribution;
  public int[] noFocusModes;
  public static final android.os.Parcelable.Creator<AudioModeSessionRequest> CREATOR = new android.os.Parcelable.Creator<AudioModeSessionRequest>() {
    @Override
    public AudioModeSessionRequest createFromParcel(android.os.Parcel _aidl_source) {
      AudioModeSessionRequest _aidl_out = new AudioModeSessionRequest();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public AudioModeSessionRequest[] newArray(int _aidl_size) {
      return new AudioModeSessionRequest[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(mode);
    _aidl_parcel.writeBoolean(isDisplayActiveUseCase);
    _aidl_parcel.writeTypedObject(attributionSource, _aidl_flag);
    _aidl_parcel.writeTypedObject(clientAttribution, _aidl_flag);
    _aidl_parcel.writeIntArray(noFocusModes);
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
      mode = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      isDisplayActiveUseCase = _aidl_parcel.readBoolean();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      attributionSource = _aidl_parcel.readTypedObject(android.content.AttributionSourceState.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      clientAttribution = _aidl_parcel.readTypedObject(android.content.AttributionSourceState.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      noFocusModes = _aidl_parcel.createIntArray();
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
    _mask |= describeContents(attributionSource);
    _mask |= describeContents(clientAttribution);
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
