/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash d30e321bff565b6a44f81ce4113979c4844a61a3 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen/android/hardware/radio/network/AlertCategory.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5/android/hardware/radio/network/AlertCategory.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.network;
/** @hide */
public @interface AlertCategory {
  public static final int UNSPECIFIED = 0;
  public static final int DOWNGRADE = 1;
  public static final int DOWNGRADE_2G = 2;
  public static final int DOWNGRADE_3G = 3;
  public static final int DOWNGRADE_4G = 4;
  public static final int IMPRISONMENT = 5;
  public static final int DOS_NETWORK = 6;
  public static final int ATTRACTIVE_CELL = 7;
  public static final int JAMMING = 8;
  public static final int LOCATION_TRACKING = 9;
  public static final int AUTH_PASSED = 10;
  public static final int UNAUTH_SMS = 11;
  public static final int UNAUTH_EMERGENCY_MSG = 12;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == UNSPECIFIED) return "UNSPECIFIED";
      if (_aidl_v == DOWNGRADE) return "DOWNGRADE";
      if (_aidl_v == DOWNGRADE_2G) return "DOWNGRADE_2G";
      if (_aidl_v == DOWNGRADE_3G) return "DOWNGRADE_3G";
      if (_aidl_v == DOWNGRADE_4G) return "DOWNGRADE_4G";
      if (_aidl_v == IMPRISONMENT) return "IMPRISONMENT";
      if (_aidl_v == DOS_NETWORK) return "DOS_NETWORK";
      if (_aidl_v == ATTRACTIVE_CELL) return "ATTRACTIVE_CELL";
      if (_aidl_v == JAMMING) return "JAMMING";
      if (_aidl_v == LOCATION_TRACKING) return "LOCATION_TRACKING";
      if (_aidl_v == AUTH_PASSED) return "AUTH_PASSED";
      if (_aidl_v == UNAUTH_SMS) return "UNAUTH_SMS";
      if (_aidl_v == UNAUTH_EMERGENCY_MSG) return "UNAUTH_EMERGENCY_MSG";
      return Integer.toString(_aidl_v);
    }
    static String arrayToString(Object _aidl_v) {
      if (_aidl_v == null) return "null";
      Class<?> _aidl_cls = _aidl_v.getClass();
      if (!_aidl_cls.isArray()) throw new IllegalArgumentException("not an array: " + _aidl_v);
      Class<?> comp = _aidl_cls.getComponentType();
      java.util.StringJoiner _aidl_sj = new java.util.StringJoiner(", ", "[", "]");
      if (comp.isArray()) {
        for (int _aidl_i = 0; _aidl_i < java.lang.reflect.Array.getLength(_aidl_v); _aidl_i++) {
          _aidl_sj.add(arrayToString(java.lang.reflect.Array.get(_aidl_v, _aidl_i)));
        }
      } else {
        if (_aidl_cls != int[].class) throw new IllegalArgumentException("wrong type: " + _aidl_cls);
        for (int e : (int[]) _aidl_v) {
          _aidl_sj.add(toString(e));
        }
      }
      return _aidl_sj.toString();
    }
  }
}
