/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 4 --hash dec155403ea3aa5395b0226de399873712b16082 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/common/fmq/aidl/android.hardware.common.fmq_interface/1/preprocessed.aidl -pout/soong/.intermediates/system/hardware/interfaces/media/android.media.audio.common.types_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/vibrator/aidl/android.hardware.vibrator-V4-java-source/gen/android/hardware/vibrator/VibrationEffectContent.java.d -o out/soong/.intermediates/hardware/interfaces/vibrator/aidl/android.hardware.vibrator-V4-java-source/gen -Iframeworks/native/aidl/binder -Nhardware/interfaces/vibrator/aidl/aidl_api/android.hardware.vibrator/4 hardware/interfaces/vibrator/aidl/aidl_api/android.hardware.vibrator/4/android/hardware/vibrator/VibrationEffectContent.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.vibrator;
public final class VibrationEffectContent implements android.os.Parcelable {
  // tags for union fields
  public final static int reserved = 0;  // byte[32] reserved;
  public final static int composite = 1;  // android.hardware.vibrator.CompositeEffect composite;
  public final static int oneShotPrimitive = 2;  // android.hardware.vibrator.OneShotPrimitive oneShotPrimitive;
  public final static int predefined = 3;  // android.hardware.vibrator.PredefinedEffect predefined;
  public final static int pwleV2Primitive = 4;  // android.hardware.vibrator.PwleV2Primitive pwleV2Primitive;

  private int _tag;
  private Object _value;

  public VibrationEffectContent() {
    byte[] _value = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    this._tag = reserved;
    this._value = _value;
  }

  private VibrationEffectContent(android.os.Parcel _aidl_parcel) {
    readFromParcel(_aidl_parcel);
  }

  private VibrationEffectContent(int _tag, Object _value) {
    this._tag = _tag;
    this._value = _value;
  }

  public int getTag() {
    return _tag;
  }

  // byte[32] reserved;

  public static VibrationEffectContent reserved(byte[] _value) {
    return new VibrationEffectContent(reserved, _value);
  }

  public byte[] getReserved() {
    _assertTag(reserved);
    return (byte[]) _value;
  }

  public void setReserved(byte[] _value) {
    _set(reserved, _value);
  }

  // android.hardware.vibrator.CompositeEffect composite;

  public static VibrationEffectContent composite(android.hardware.vibrator.CompositeEffect _value) {
    return new VibrationEffectContent(composite, _value);
  }

  public android.hardware.vibrator.CompositeEffect getComposite() {
    _assertTag(composite);
    return (android.hardware.vibrator.CompositeEffect) _value;
  }

  public void setComposite(android.hardware.vibrator.CompositeEffect _value) {
    _set(composite, _value);
  }

  // android.hardware.vibrator.OneShotPrimitive oneShotPrimitive;

  public static VibrationEffectContent oneShotPrimitive(android.hardware.vibrator.OneShotPrimitive _value) {
    return new VibrationEffectContent(oneShotPrimitive, _value);
  }

  public android.hardware.vibrator.OneShotPrimitive getOneShotPrimitive() {
    _assertTag(oneShotPrimitive);
    return (android.hardware.vibrator.OneShotPrimitive) _value;
  }

  public void setOneShotPrimitive(android.hardware.vibrator.OneShotPrimitive _value) {
    _set(oneShotPrimitive, _value);
  }

  // android.hardware.vibrator.PredefinedEffect predefined;

  public static VibrationEffectContent predefined(android.hardware.vibrator.PredefinedEffect _value) {
    return new VibrationEffectContent(predefined, _value);
  }

  public android.hardware.vibrator.PredefinedEffect getPredefined() {
    _assertTag(predefined);
    return (android.hardware.vibrator.PredefinedEffect) _value;
  }

  public void setPredefined(android.hardware.vibrator.PredefinedEffect _value) {
    _set(predefined, _value);
  }

  // android.hardware.vibrator.PwleV2Primitive pwleV2Primitive;

  public static VibrationEffectContent pwleV2Primitive(android.hardware.vibrator.PwleV2Primitive _value) {
    return new VibrationEffectContent(pwleV2Primitive, _value);
  }

  public android.hardware.vibrator.PwleV2Primitive getPwleV2Primitive() {
    _assertTag(pwleV2Primitive);
    return (android.hardware.vibrator.PwleV2Primitive) _value;
  }

  public void setPwleV2Primitive(android.hardware.vibrator.PwleV2Primitive _value) {
    _set(pwleV2Primitive, _value);
  }

  @Override
  public final int getStability() {
    return android.os.Parcelable.PARCELABLE_STABILITY_VINTF;
  }

  public static final android.os.Parcelable.Creator<VibrationEffectContent> CREATOR = new android.os.Parcelable.Creator<VibrationEffectContent>() {
    @Override
    public VibrationEffectContent createFromParcel(android.os.Parcel _aidl_source) {
      return new VibrationEffectContent(_aidl_source);
    }
    @Override
    public VibrationEffectContent[] newArray(int _aidl_size) {
      return new VibrationEffectContent[_aidl_size];
    }
  };

  @Override
  public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag) {
    _aidl_parcel.writeInt(_tag);
    switch (_tag) {
    case reserved:
      _aidl_parcel.writeFixedArray(getReserved(), _aidl_flag, 32);
      break;
    case composite:
      _aidl_parcel.writeTypedObject(getComposite(), _aidl_flag);
      break;
    case oneShotPrimitive:
      _aidl_parcel.writeTypedObject(getOneShotPrimitive(), _aidl_flag);
      break;
    case predefined:
      _aidl_parcel.writeTypedObject(getPredefined(), _aidl_flag);
      break;
    case pwleV2Primitive:
      _aidl_parcel.writeTypedObject(getPwleV2Primitive(), _aidl_flag);
      break;
    }
  }

  public void readFromParcel(android.os.Parcel _aidl_parcel) {
    int _aidl_tag;
    _aidl_tag = _aidl_parcel.readInt();
    switch (_aidl_tag) {
    case reserved: {
      byte[] _aidl_value;
      _aidl_value = _aidl_parcel.createFixedArray(byte[].class, 32);
      _set(_aidl_tag, _aidl_value);
      return; }
    case composite: {
      android.hardware.vibrator.CompositeEffect _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.vibrator.CompositeEffect.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case oneShotPrimitive: {
      android.hardware.vibrator.OneShotPrimitive _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.vibrator.OneShotPrimitive.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case predefined: {
      android.hardware.vibrator.PredefinedEffect _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.vibrator.PredefinedEffect.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case pwleV2Primitive: {
      android.hardware.vibrator.PwleV2Primitive _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.vibrator.PwleV2Primitive.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    }
    throw new IllegalArgumentException("union: unknown tag: " + _aidl_tag);
  }

  @Override
  public int describeContents() {
    int _mask = 0;
    switch (getTag()) {
    case composite:
      _mask |= describeContents(getComposite());
      break;
    case oneShotPrimitive:
      _mask |= describeContents(getOneShotPrimitive());
      break;
    case predefined:
      _mask |= describeContents(getPredefined());
      break;
    case pwleV2Primitive:
      _mask |= describeContents(getPwleV2Primitive());
      break;
    }
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }

  private void _assertTag(int tag) {
    if (getTag() != tag) {
      throw new IllegalStateException("bad access: " + _tagString(tag) + ", " + _tagString(getTag()) + " is available.");
    }
  }

  private String _tagString(int _tag) {
    switch (_tag) {
    case reserved: return "reserved";
    case composite: return "composite";
    case oneShotPrimitive: return "oneShotPrimitive";
    case predefined: return "predefined";
    case pwleV2Primitive: return "pwleV2Primitive";
    }
    throw new IllegalStateException("unknown field: " + _tag);
  }

  private void _set(int _tag, Object _value) {
    this._tag = _tag;
    this._value = _value;
  }
  public static @interface Tag {
    public static final byte reserved = 0;
    public static final byte composite = 1;
    public static final byte oneShotPrimitive = 2;
    public static final byte predefined = 3;
    public static final byte pwleV2Primitive = 4;
  }
}
