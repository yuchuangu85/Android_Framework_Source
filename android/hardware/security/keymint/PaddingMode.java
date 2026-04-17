/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 177877c3782ff5543c231b8616f1ee6a300f810d -t --stability vintf --min_sdk_version platform_apis -pout/soong/.intermediates/hardware/interfaces/security/secureclock/aidl/android.hardware.security.secureclock_interface/1/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/security/keymint/aidl/android.hardware.security.keymint-V5-java-source/gen/android/hardware/security/keymint/PaddingMode.java.d -o out/soong/.intermediates/hardware/interfaces/security/keymint/aidl/android.hardware.security.keymint-V5-java-source/gen -Nhardware/interfaces/security/keymint/aidl/aidl_api/android.hardware.security.keymint/5 hardware/interfaces/security/keymint/aidl/aidl_api/android.hardware.security.keymint/5/android/hardware/security/keymint/PaddingMode.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.security.keymint;
/** @hide */
public @interface PaddingMode {
  public static final int NONE = 1;
  public static final int RSA_OAEP = 2;
  public static final int RSA_PSS = 3;
  public static final int RSA_PKCS1_1_5_ENCRYPT = 4;
  public static final int RSA_PKCS1_1_5_SIGN = 5;
  public static final int PKCS7 = 64;
}
