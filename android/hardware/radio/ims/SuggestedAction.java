/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 4 --hash 2f451a440a404c0eda2001bd542876005bd3a5ac --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.ims-V4-java-source/gen/android/hardware/radio/ims/SuggestedAction.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.ims-V4-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.ims/4 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.ims/4/android/hardware/radio/ims/SuggestedAction.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.ims;
/** @hide */
public @interface SuggestedAction {
  public static final int NONE = 0;
  public static final int TRIGGER_PLMN_BLOCK = 1;
  public static final int TRIGGER_PLMN_BLOCK_WITH_TIMEOUT = 2;
  public static final int TRIGGER_RAT_BLOCK = 3;
  public static final int TRIGGER_CLEAR_RAT_BLOCKS = 4;
  public static final int TRIGGER_THROTTLE_TIME = 5;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == NONE) return "NONE";
      if (_aidl_v == TRIGGER_PLMN_BLOCK) return "TRIGGER_PLMN_BLOCK";
      if (_aidl_v == TRIGGER_PLMN_BLOCK_WITH_TIMEOUT) return "TRIGGER_PLMN_BLOCK_WITH_TIMEOUT";
      if (_aidl_v == TRIGGER_RAT_BLOCK) return "TRIGGER_RAT_BLOCK";
      if (_aidl_v == TRIGGER_CLEAR_RAT_BLOCKS) return "TRIGGER_CLEAR_RAT_BLOCKS";
      if (_aidl_v == TRIGGER_THROTTLE_TIME) return "TRIGGER_THROTTLE_TIME";
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
