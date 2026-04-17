/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash d30e321bff565b6a44f81ce4113979c4844a61a3 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen/android/hardware/radio/network/IndicationFilter.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5/android/hardware/radio/network/IndicationFilter.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.network;
/** @hide */
public @interface IndicationFilter {
  public static final int NONE = 0;
  public static final int ALL = -1;
  public static final int SIGNAL_STRENGTH = 1;
  public static final int FULL_NETWORK_STATE = 2;
  public static final int DATA_CALL_DORMANCY_CHANGED = 4;
  public static final int LINK_CAPACITY_ESTIMATE = 8;
  public static final int PHYSICAL_CHANNEL_CONFIG = 16;
  public static final int REGISTRATION_FAILURE = 32;
  public static final int BARRING_INFO = 64;
  public static final int DISPLAY_NETWORK_TYPE_CHANGED = 128;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == NONE) return "NONE";
      if (_aidl_v == ALL) return "ALL";
      if (_aidl_v == SIGNAL_STRENGTH) return "SIGNAL_STRENGTH";
      if (_aidl_v == FULL_NETWORK_STATE) return "FULL_NETWORK_STATE";
      if (_aidl_v == DATA_CALL_DORMANCY_CHANGED) return "DATA_CALL_DORMANCY_CHANGED";
      if (_aidl_v == LINK_CAPACITY_ESTIMATE) return "LINK_CAPACITY_ESTIMATE";
      if (_aidl_v == PHYSICAL_CHANNEL_CONFIG) return "PHYSICAL_CHANNEL_CONFIG";
      if (_aidl_v == REGISTRATION_FAILURE) return "REGISTRATION_FAILURE";
      if (_aidl_v == BARRING_INFO) return "BARRING_INFO";
      if (_aidl_v == DISPLAY_NETWORK_TYPE_CHANGED) return "DISPLAY_NETWORK_TYPE_CHANGED";
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
