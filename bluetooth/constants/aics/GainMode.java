/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version 36 --ninja -d out/soong/.intermediates/packages/modules/Bluetooth/common/bluetooth_constants-java-source/gen/bluetooth/constants/aics/GainMode.java.d -o out/soong/.intermediates/packages/modules/Bluetooth/common/bluetooth_constants-java-source/gen -Npackages/modules/Bluetooth/common packages/modules/Bluetooth/common/bluetooth/constants/aics/GainMode.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package bluetooth.constants.aics;
/**
 * See Audio Input Control Service 1.0 - 2.2.1.3. Gain_Mode field
 * The Gain_Mode field shall be set to a value that reflects whether gain modes are manual
 * or automatic.
 * - Manual Only, the server allows only manual gain.
 * - Automatic Only, the server allows only automatic gain.
 * 
 * For all other Gain_Mode field values, the server allows switchable automatic/manual gain.
 * @hide
 */
public @interface GainMode {
  public static final byte MANUAL_ONLY = 0;
  public static final byte AUTOMATIC_ONLY = 1;
  public static final byte MANUAL = 2;
  public static final byte AUTOMATIC = 3;
  interface $ {
    static String toString(byte _aidl_v) {
      if (_aidl_v == MANUAL_ONLY) return "MANUAL_ONLY";
      if (_aidl_v == AUTOMATIC_ONLY) return "AUTOMATIC_ONLY";
      if (_aidl_v == MANUAL) return "MANUAL";
      if (_aidl_v == AUTOMATIC) return "AUTOMATIC";
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
