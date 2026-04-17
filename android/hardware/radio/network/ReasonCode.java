/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash d30e321bff565b6a44f81ce4113979c4844a61a3 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio_interface/5/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen/android/hardware/radio/network/ReasonCode.java.d -o out/soong/.intermediates/hardware/interfaces/radio/aidl/android.hardware.radio.network-V5-java-source/gen -Nhardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5 hardware/interfaces/radio/aidl/aidl_api/android.hardware.radio.network/5/android/hardware/radio/network/ReasonCode.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.radio.network;
/** @hide */
public @interface ReasonCode {
  public static final int UNSPECIFIED = 0;
  public static final int DOWNGRADE_WEAK_CIPHER_SUITES_OFFERED = 1;
  public static final int DOWNGRADE_HIGHER_RAT_REJECTED = 2;
  public static final int DOWNGRADE_SIGNAL_STRENGTH_ANOMALY = 3;
  public static final int DOWNGRADE_FORCED_HANDOVER = 4;
  public static final int IMPRISONMENT_CELL_RESELECTION_FAILURE = 5;
  public static final int IMPRISONMENT_NEIGHBOR_LIST_EMPTY_OR_INVALID = 6;
  public static final int IMPRISONMENT_BARRING_OF_OTHER_CELLS = 7;
  public static final int IMPRISONMENT_REJECTED_FROM_NEIGHBORS = 8;
  public static final int DOS_EXCESSIVE_PAGING_RATE = 9;
  public static final int DOS_CONNECTION_SETUP_FAIL_LOOP = 10;
  public static final int DOS_AUTHENTICATION_REQUEST_FLOOD = 11;
  public static final int DOS_DETACH_ATTACH_CYCLE = 12;
  public static final int ATTRACTIVE_CELL_VERY_HIGH_RX_LEVEL = 13;
  public static final int ATTRACTIVE_CELL_UNEXPECTED_PLMN_ID = 14;
  public static final int ATTRACTIVE_CELL_MISSING_NEIGHBOR_INFO = 15;
  public static final int ATTRACTIVE_CELL_IMSI_CATCHER_PARAMETERS = 16;
  public static final int JAMMING_WIDEBAND_INTERFERENCE = 17;
  public static final int JAMMING_NARROWBAND_INTERFERENCE = 18;
  public static final int JAMMING_SNR_DEGRADATION = 19;
  public static final int LOCATION_FREQUENT_TRACKING_AREA_UPDATES = 20;
  public static final int LOCATION_SILENT_SMS_DETECTED = 21;
  public static final int LOCATION_PAGING_WITHOUT_FOLLOWUP = 22;
  public static final int UNAUTH_SMS_INTEGRITY_CHECK_FAILED = 23;
  public static final int UNAUTH_SMS_MISSING_SECURITY_HEADERS = 24;
  public static final int UNAUTH_SMS_UNTRUSTED_SME = 25;
  public static final int UNAUTH_SMS_KNOWN_SPOOFING_METHOD = 26;
  public static final int UNAUTH_EMERGENCY_SOURCE_CELL_NOT_AUTHENTICATED = 27;
  interface $ {
    static String toString(int _aidl_v) {
      if (_aidl_v == UNSPECIFIED) return "UNSPECIFIED";
      if (_aidl_v == DOWNGRADE_WEAK_CIPHER_SUITES_OFFERED) return "DOWNGRADE_WEAK_CIPHER_SUITES_OFFERED";
      if (_aidl_v == DOWNGRADE_HIGHER_RAT_REJECTED) return "DOWNGRADE_HIGHER_RAT_REJECTED";
      if (_aidl_v == DOWNGRADE_SIGNAL_STRENGTH_ANOMALY) return "DOWNGRADE_SIGNAL_STRENGTH_ANOMALY";
      if (_aidl_v == DOWNGRADE_FORCED_HANDOVER) return "DOWNGRADE_FORCED_HANDOVER";
      if (_aidl_v == IMPRISONMENT_CELL_RESELECTION_FAILURE) return "IMPRISONMENT_CELL_RESELECTION_FAILURE";
      if (_aidl_v == IMPRISONMENT_NEIGHBOR_LIST_EMPTY_OR_INVALID) return "IMPRISONMENT_NEIGHBOR_LIST_EMPTY_OR_INVALID";
      if (_aidl_v == IMPRISONMENT_BARRING_OF_OTHER_CELLS) return "IMPRISONMENT_BARRING_OF_OTHER_CELLS";
      if (_aidl_v == IMPRISONMENT_REJECTED_FROM_NEIGHBORS) return "IMPRISONMENT_REJECTED_FROM_NEIGHBORS";
      if (_aidl_v == DOS_EXCESSIVE_PAGING_RATE) return "DOS_EXCESSIVE_PAGING_RATE";
      if (_aidl_v == DOS_CONNECTION_SETUP_FAIL_LOOP) return "DOS_CONNECTION_SETUP_FAIL_LOOP";
      if (_aidl_v == DOS_AUTHENTICATION_REQUEST_FLOOD) return "DOS_AUTHENTICATION_REQUEST_FLOOD";
      if (_aidl_v == DOS_DETACH_ATTACH_CYCLE) return "DOS_DETACH_ATTACH_CYCLE";
      if (_aidl_v == ATTRACTIVE_CELL_VERY_HIGH_RX_LEVEL) return "ATTRACTIVE_CELL_VERY_HIGH_RX_LEVEL";
      if (_aidl_v == ATTRACTIVE_CELL_UNEXPECTED_PLMN_ID) return "ATTRACTIVE_CELL_UNEXPECTED_PLMN_ID";
      if (_aidl_v == ATTRACTIVE_CELL_MISSING_NEIGHBOR_INFO) return "ATTRACTIVE_CELL_MISSING_NEIGHBOR_INFO";
      if (_aidl_v == ATTRACTIVE_CELL_IMSI_CATCHER_PARAMETERS) return "ATTRACTIVE_CELL_IMSI_CATCHER_PARAMETERS";
      if (_aidl_v == JAMMING_WIDEBAND_INTERFERENCE) return "JAMMING_WIDEBAND_INTERFERENCE";
      if (_aidl_v == JAMMING_NARROWBAND_INTERFERENCE) return "JAMMING_NARROWBAND_INTERFERENCE";
      if (_aidl_v == JAMMING_SNR_DEGRADATION) return "JAMMING_SNR_DEGRADATION";
      if (_aidl_v == LOCATION_FREQUENT_TRACKING_AREA_UPDATES) return "LOCATION_FREQUENT_TRACKING_AREA_UPDATES";
      if (_aidl_v == LOCATION_SILENT_SMS_DETECTED) return "LOCATION_SILENT_SMS_DETECTED";
      if (_aidl_v == LOCATION_PAGING_WITHOUT_FOLLOWUP) return "LOCATION_PAGING_WITHOUT_FOLLOWUP";
      if (_aidl_v == UNAUTH_SMS_INTEGRITY_CHECK_FAILED) return "UNAUTH_SMS_INTEGRITY_CHECK_FAILED";
      if (_aidl_v == UNAUTH_SMS_MISSING_SECURITY_HEADERS) return "UNAUTH_SMS_MISSING_SECURITY_HEADERS";
      if (_aidl_v == UNAUTH_SMS_UNTRUSTED_SME) return "UNAUTH_SMS_UNTRUSTED_SME";
      if (_aidl_v == UNAUTH_SMS_KNOWN_SPOOFING_METHOD) return "UNAUTH_SMS_KNOWN_SPOOFING_METHOD";
      if (_aidl_v == UNAUTH_EMERGENCY_SOURCE_CELL_NOT_AUTHENTICATED) return "UNAUTH_EMERGENCY_SOURCE_CELL_NOT_AUTHENTICATED";
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
