/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version 36 --ninja -d out/soong/.intermediates/packages/modules/Bluetooth/common/bluetooth_constants-java-source/gen/bluetooth/constants/aics/Mute.java.d -o out/soong/.intermediates/packages/modules/Bluetooth/common/bluetooth_constants-java-source/gen -Npackages/modules/Bluetooth/common packages/modules/Bluetooth/common/bluetooth/constants/aics/Mute.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package bluetooth.constants.aics;
/**
 * See Audio Input Control Service 1.0 - 2.2.1.2. Mute field
 * @hide
 */
public @interface Mute {
  public static final byte NOT_MUTED = 0;
  public static final byte MUTED = 1;
  public static final byte DISABLED = 2;
  interface $ {
    static String toString(byte _aidl_v) {
      if (_aidl_v == NOT_MUTED) return "NOT_MUTED";
      if (_aidl_v == MUTED) return "MUTED";
      if (_aidl_v == DISABLED) return "DISABLED";
      return Byte.toString(_aidl_v);
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
        if (_aidl_cls != byte[].class) throw new IllegalArgumentException("wrong type: " + _aidl_cls);
        for (byte e : (byte[]) _aidl_v) {
          _aidl_sj.add(toString(e));
        }
      }
      return _aidl_sj.toString();
    }
  }
}
