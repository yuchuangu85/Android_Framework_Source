/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 0a40e5aa0a942c5a4e577ffa6ade668ac294e269 -t --stability vintf --min_sdk_version platform_apis -pout/soong/.intermediates/hardware/interfaces/biometrics/common/aidl/android.hardware.biometrics.common_interface/4/preprocessed.aidl -pout/soong/.intermediates/hardware/interfaces/keymaster/aidl/android.hardware.keymaster_interface/4/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen/android/hardware/biometrics/fingerprint/AcquiredInfo.java.d -o out/soong/.intermediates/hardware/interfaces/biometrics/fingerprint/aidl/android.hardware.biometrics.fingerprint-V5-java-source/gen -Nhardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5 hardware/interfaces/biometrics/fingerprint/aidl/aidl_api/android.hardware.biometrics.fingerprint/5/android/hardware/biometrics/fingerprint/AcquiredInfo.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.biometrics.fingerprint;
/** @hide */
public @interface AcquiredInfo {
  public static final byte UNKNOWN = 0;
  public static final byte GOOD = 1;
  public static final byte PARTIAL = 2;
  public static final byte INSUFFICIENT = 3;
  public static final byte SENSOR_DIRTY = 4;
  public static final byte TOO_SLOW = 5;
  public static final byte TOO_FAST = 6;
  public static final byte VENDOR = 7;
  public static final byte START = 8;
  public static final byte TOO_DARK = 9;
  public static final byte TOO_BRIGHT = 10;
  public static final byte IMMOBILE = 11;
  public static final byte RETRYING_CAPTURE = 12;
  public static final byte LIFT_TOO_SOON = 13;
  public static final byte POWER_PRESS = 14;
}
