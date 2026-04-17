/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 0a40e5aa0a942c5a4e577ffa6ade668ac294e269 -t --stability vintf --min_sdk_version platform_apis -pout/soong/.intermediates/hardware/interfaces/biometrics/common/aidl/android.hardware.biometrics.common_interface/4/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/keymaster/aidl/android.hardware.keymaster_interface/4/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen/android/hardware/biometrics/fingerprint/SensorLocationData.java.d -o out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen -Nhardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5 hardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5/android/hardware/biometrics/fingerprint/SensorLocationData.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.biometrics.fingerprint;
/** @hide */
public final class SensorLocationData implements android.os.Parcelable {
  // tags for union fields
  public final static int underDisplayLocation = 0;  // android.hardware.biometrics.fingerprint.location.UnderDisplayLocation underDisplayLocation;
  public final static int powerButtonDisplayLocation = 1;  // android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation powerButtonDisplayLocation;
  public final static int powerButtonPhysicalLocation = 2;  // android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation powerButtonPhysicalLocation;
  public final static int standaloneLocation = 3;  // android.hardware.biometrics.fingerprint.location.StandaloneLocation standaloneLocation;
  public final static int homeButtonLocation = 4;  // android.hardware.biometrics.fingerprint.location.HomeButtonLocation homeButtonLocation;
  public final static int rearLocation = 5;  // android.hardware.biometrics.fingerprint.location.RearLocation rearLocation;

  private int _tag;
  private Object _value;

  public SensorLocationData() {
    android.hardware.biometrics.fingerprint.location.UnderDisplayLocation _value = null;
    this._tag = underDisplayLocation;
    this._value = _value;
  }

  private SensorLocationData(android.os.Parcel _aidl_parcel) {
    readFromParcel(_aidl_parcel);
  }

  private SensorLocationData(int _tag, Object _value) {
    this._tag = _tag;
    this._value = _value;
  }

  public int getTag() {
    return _tag;
  }

  // android.hardware.biometrics.fingerprint.location.UnderDisplayLocation underDisplayLocation;

  public static SensorLocationData underDisplayLocation(android.hardware.biometrics.fingerprint.location.UnderDisplayLocation _value) {
    return new SensorLocationData(underDisplayLocation, _value);
  }

  public android.hardware.biometrics.fingerprint.location.UnderDisplayLocation getUnderDisplayLocation() {
    _assertTag(underDisplayLocation);
    return (android.hardware.biometrics.fingerprint.location.UnderDisplayLocation) _value;
  }

  public void setUnderDisplayLocation(android.hardware.biometrics.fingerprint.location.UnderDisplayLocation _value) {
    _set(underDisplayLocation, _value);
  }

  // android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation powerButtonDisplayLocation;

  public static SensorLocationData powerButtonDisplayLocation(android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation _value) {
    return new SensorLocationData(powerButtonDisplayLocation, _value);
  }

  public android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation getPowerButtonDisplayLocation() {
    _assertTag(powerButtonDisplayLocation);
    return (android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation) _value;
  }

  public void setPowerButtonDisplayLocation(android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation _value) {
    _set(powerButtonDisplayLocation, _value);
  }

  // android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation powerButtonPhysicalLocation;

  public static SensorLocationData powerButtonPhysicalLocation(android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation _value) {
    return new SensorLocationData(powerButtonPhysicalLocation, _value);
  }

  public android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation getPowerButtonPhysicalLocation() {
    _assertTag(powerButtonPhysicalLocation);
    return (android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation) _value;
  }

  public void setPowerButtonPhysicalLocation(android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation _value) {
    _set(powerButtonPhysicalLocation, _value);
  }

  // android.hardware.biometrics.fingerprint.location.StandaloneLocation standaloneLocation;

  public static SensorLocationData standaloneLocation(android.hardware.biometrics.fingerprint.location.StandaloneLocation _value) {
    return new SensorLocationData(standaloneLocation, _value);
  }

  public android.hardware.biometrics.fingerprint.location.StandaloneLocation getStandaloneLocation() {
    _assertTag(standaloneLocation);
    return (android.hardware.biometrics.fingerprint.location.StandaloneLocation) _value;
  }

  public void setStandaloneLocation(android.hardware.biometrics.fingerprint.location.StandaloneLocation _value) {
    _set(standaloneLocation, _value);
  }

  // android.hardware.biometrics.fingerprint.location.HomeButtonLocation homeButtonLocation;

  public static SensorLocationData homeButtonLocation(android.hardware.biometrics.fingerprint.location.HomeButtonLocation _value) {
    return new SensorLocationData(homeButtonLocation, _value);
  }

  public android.hardware.biometrics.fingerprint.location.HomeButtonLocation getHomeButtonLocation() {
    _assertTag(homeButtonLocation);
    return (android.hardware.biometrics.fingerprint.location.HomeButtonLocation) _value;
  }

  public void setHomeButtonLocation(android.hardware.biometrics.fingerprint.location.HomeButtonLocation _value) {
    _set(homeButtonLocation, _value);
  }

  // android.hardware.biometrics.fingerprint.location.RearLocation rearLocation;

  public static SensorLocationData rearLocation(android.hardware.biometrics.fingerprint.location.RearLocation _value) {
    return new SensorLocationData(rearLocation, _value);
  }

  public android.hardware.biometrics.fingerprint.location.RearLocation getRearLocation() {
    _assertTag(rearLocation);
    return (android.hardware.biometrics.fingerprint.location.RearLocation) _value;
  }

  public void setRearLocation(android.hardware.biometrics.fingerprint.location.RearLocation _value) {
    _set(rearLocation, _value);
  }

  @Override
  public final int getStability() {
    return android.os.Parcelable.PARCELABLE_STABILITY_VINTF;
  }

  public static final android.os.Parcelable.Creator<SensorLocationData> CREATOR = new android.os.Parcelable.Creator<SensorLocationData>() {
    @Override
    public SensorLocationData createFromParcel(android.os.Parcel _aidl_source) {
      return new SensorLocationData(_aidl_source);
    }
    @Override
    public SensorLocationData[] newArray(int _aidl_size) {
      return new SensorLocationData[_aidl_size];
    }
  };

  @Override
  public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag) {
    _aidl_parcel.writeInt(_tag);
    switch (_tag) {
    case underDisplayLocation:
      _aidl_parcel.writeTypedObject(getUnderDisplayLocation(), _aidl_flag);
      break;
    case powerButtonDisplayLocation:
      _aidl_parcel.writeTypedObject(getPowerButtonDisplayLocation(), _aidl_flag);
      break;
    case powerButtonPhysicalLocation:
      _aidl_parcel.writeTypedObject(getPowerButtonPhysicalLocation(), _aidl_flag);
      break;
    case standaloneLocation:
      _aidl_parcel.writeTypedObject(getStandaloneLocation(), _aidl_flag);
      break;
    case homeButtonLocation:
      _aidl_parcel.writeTypedObject(getHomeButtonLocation(), _aidl_flag);
      break;
    case rearLocation:
      _aidl_parcel.writeTypedObject(getRearLocation(), _aidl_flag);
      break;
    }
  }

  public void readFromParcel(android.os.Parcel _aidl_parcel) {
    int _aidl_tag;
    _aidl_tag = _aidl_parcel.readInt();
    switch (_aidl_tag) {
    case underDisplayLocation: {
      android.hardware.biometrics.fingerprint.location.UnderDisplayLocation _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.location.UnderDisplayLocation.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case powerButtonDisplayLocation: {
      android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case powerButtonPhysicalLocation: {
      android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.location.PowerButtonPhysicalLocation.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case standaloneLocation: {
      android.hardware.biometrics.fingerprint.location.StandaloneLocation _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.location.StandaloneLocation.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case homeButtonLocation: {
      android.hardware.biometrics.fingerprint.location.HomeButtonLocation _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.location.HomeButtonLocation.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    case rearLocation: {
      android.hardware.biometrics.fingerprint.location.RearLocation _aidl_value;
      _aidl_value = _aidl_parcel.readTypedObject(android.hardware.biometrics.fingerprint.location.RearLocation.CREATOR);
      _set(_aidl_tag, _aidl_value);
      return; }
    }
    throw new IllegalArgumentException("union: unknown tag: " + _aidl_tag);
  }

  @Override
  public int describeContents() {
    int _mask = 0;
    switch (getTag()) {
    case underDisplayLocation:
      _mask |= describeContents(getUnderDisplayLocation());
      break;
    case powerButtonDisplayLocation:
      _mask |= describeContents(getPowerButtonDisplayLocation());
      break;
    case powerButtonPhysicalLocation:
      _mask |= describeContents(getPowerButtonPhysicalLocation());
      break;
    case standaloneLocation:
      _mask |= describeContents(getStandaloneLocation());
      break;
    case homeButtonLocation:
      _mask |= describeContents(getHomeButtonLocation());
      break;
    case rearLocation:
      _mask |= describeContents(getRearLocation());
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
    case underDisplayLocation: return "underDisplayLocation";
    case powerButtonDisplayLocation: return "powerButtonDisplayLocation";
    case powerButtonPhysicalLocation: return "powerButtonPhysicalLocation";
    case standaloneLocation: return "standaloneLocation";
    case homeButtonLocation: return "homeButtonLocation";
    case rearLocation: return "rearLocation";
    }
    throw new IllegalStateException("unknown field: " + _tag);
  }

  private void _set(int _tag, Object _value) {
    this._tag = _tag;
    this._value = _value;
  }
  public static @interface Tag {
    public static final int underDisplayLocation = 0;
    public static final int powerButtonDisplayLocation = 1;
    public static final int powerButtonPhysicalLocation = 2;
    public static final int standaloneLocation = 3;
    public static final int homeButtonLocation = 4;
    public static final int rearLocation = 5;
  }
}
