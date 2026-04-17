/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version 36 --ninja -d out/soong/.intermediates/packages/modules/Bluetooth/common/bluetooth_constants-java-source/gen/bluetooth/constants/Core.java.d -o out/soong/.intermediates/packages/modules/Bluetooth/common/bluetooth_constants-java-source/gen -Npackages/modules/Bluetooth/common packages/modules/Bluetooth/common/bluetooth/constants/Core.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package bluetooth.constants;
/**
 * Set of constants we can find in BT Core specification to be shared between native and Java.
 * @hide
 */
public @interface Core {
  /** GATT max attribute length (Bluetooth Core Specification 6.1 Volume 3, Part F, section 3.2.9) */
  public static final int GATT_MAX_ATTR_LEN = 512;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == GATT_MAX_ATTR_LEN) return "GATT_MAX_ATTR_LEN";
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
