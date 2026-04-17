/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 5aff83e927c9aeca4d1a1d5e2114d87b7d069b52 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.config_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.sim-V5-java-source/gen/android/hardware/radio/sim/PinState.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.sim-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.sim/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.sim/5/android/hardware/radio/sim/PinState.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.sim;
/** @hide */
public @interface PinState {
  public static final int UNKNOWN = 0;
  public static final int ENABLED_NOT_VERIFIED = 1;
  public static final int ENABLED_VERIFIED = 2;
  public static final int DISABLED = 3;
  public static final int ENABLED_BLOCKED = 4;
  public static final int ENABLED_PERM_BLOCKED = 5;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == UNKNOWN) return "UNKNOWN";
      if (_aidl_v == ENABLED_NOT_VERIFIED) return "ENABLED_NOT_VERIFIED";
      if (_aidl_v == ENABLED_VERIFIED) return "ENABLED_VERIFIED";
      if (_aidl_v == DISABLED) return "DISABLED";
      if (_aidl_v == ENABLED_BLOCKED) return "ENABLED_BLOCKED";
      if (_aidl_v == ENABLED_PERM_BLOCKED) return "ENABLED_PERM_BLOCKED";
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
