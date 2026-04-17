/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 4a4662588b9e38b5e93c37e46353efac231f7a98 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen/android/hardware/contexthub/Reason.java.d -o out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen -Nhardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5 hardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5/android/hardware/contexthub/Reason.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.contexthub;
public @interface Reason {
  public static final byte UNSPECIFIED = 0;
  public static final byte OUT_OF_MEMORY = 1;
  public static final byte TIMEOUT = 2;
  public static final byte OPEN_ENDPOINT_SESSION_REQUEST_REJECTED = 3;
  public static final byte CLOSE_ENDPOINT_SESSION_REQUESTED = 4;
  public static final byte ENDPOINT_INVALID = 5;
  public static final byte ENDPOINT_GONE = 6;
  public static final byte ENDPOINT_CRASHED = 7;
  public static final byte HUB_RESET = 8;
  public static final byte PERMISSION_DENIED = 9;
}
