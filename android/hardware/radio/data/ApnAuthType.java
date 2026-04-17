/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 6acb69b7e1400ebd9d4e169667e93f778d3ec27c --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.data-V5-java-source/gen/android/hardware/radio/data/ApnAuthType.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.data-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.data/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.data/5/android/hardware/radio/data/ApnAuthType.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.data;
/** @hide */
public @interface ApnAuthType {
  public static final int NO_PAP_NO_CHAP = 0;
  public static final int PAP_NO_CHAP = 1;
  public static final int NO_PAP_CHAP = 2;
  public static final int PAP_CHAP = 3;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == NO_PAP_NO_CHAP) return "NO_PAP_NO_CHAP";
      if (_aidl_v == PAP_NO_CHAP) return "PAP_NO_CHAP";
      if (_aidl_v == NO_PAP_CHAP) return "NO_PAP_CHAP";
      if (_aidl_v == PAP_CHAP) return "PAP_CHAP";
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
