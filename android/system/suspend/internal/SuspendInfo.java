/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version 28 --ninja -d out/soong/.intermediates/system/hardware/interfaces/suspend/aidl/android.system.suspend.control.internal-java-source/gen/android/system/suspend/internal/SuspendInfo.java.d -o out/soong/.intermediates/system/hardware/interfaces/suspend/aidl/android.system.suspend.control.internal-java-source/gen -Nsystem/hardware/interfaces/suspend/aidl system/hardware/interfaces/suspend/aidl/android/system/suspend/internal/SuspendInfo.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.system.suspend.internal;
public class SuspendInfo implements android.os.Parcelable
{
  /** Total number of times that suspend was attempted */
  public long suspendAttemptCount = 0L;
  /** Total number of times that suspend attempt failed */
  public long failedSuspendCount = 0L;
  /**
   * Total number of times that a short suspend occurred. A successful suspend is considered a
   * short suspend if the suspend duration is less than suspend.short_suspend_threshold_millis
   */
  public long shortSuspendCount = 0L;
  /** Total time, in milliseconds, spent in suspend */
  public long suspendTimeMillis = 0L;
  /** Total time, in milliseconds, spent in short suspends */
  public long shortSuspendTimeMillis = 0L;
  /** Total time, in milliseconds, spent doing suspend/resume work for successful suspends */
  public long suspendOverheadTimeMillis = 0L;
  /** Total time, in milliseconds, spent doing suspend/resume work for failed suspends */
  public long failedSuspendOverheadTimeMillis = 0L;
  /**
   * Total number of times the number of consecutive bad (short, failed) suspends
   * crossed suspend.backoff_threshold_count
   */
  public long newBackoffCount = 0L;
  /**
   * Total number of times the number of consecutive bad (short, failed) suspends
   * exceeded suspend.backoff_threshold_count
   */
  public long backoffContinueCount = 0L;
  /** Total time, in milliseconds, that system has waited between suspend attempts */
  public long sleepTimeMillis = 0L;
  /**
   * A histogram of successful suspend durations, in milliseconds.
   * Bin upper bounds: 1000, 2500, 4000, 7000, 12000. Final bin is >= 12000.
   */
  public long[] suspendDurationMillisBins = {0L, 0L, 0L, 0L, 0L, 0L};
  /**
   * A histogram of the lengths of consecutive bad suspend streaks.
   * Bin upper bounds: 2, 4, 7, 11. Final bin is >= 11.
   */
  public long[] consecutiveBadSuspendBins = {0L, 0L, 0L, 0L, 0L};
  /**
   * Number of times a backoff chain continued
   * while the delay was capped at its maximum value.
   */
  public long maxBackoffContinuations = 0L;
  /** Number of times a bad suspend occurred following a good suspend */
  public long newBadSuspends = 0L;
  /**
   * The number of bad suspends that occurred in sequences which ended in
   * a good suspend before the backoff threshold was reached.
   */
  public long earlyRecoveryBadSuspends = 0L;
  /**
   * The minimum duration the system must remain successfully suspended to
   * compensate for the energy cost of entering and exiting the suspend state,
   * beyond which net power savings are achieved.
   */
  public long breakEvenMillis = 0L;
  public static final android.os.Parcelable.Creator<SuspendInfo> CREATOR = new android.os.Parcelable.Creator<SuspendInfo>() {
    @Override
    public SuspendInfo createFromParcel(android.os.Parcel _aidl_source) {
      SuspendInfo _aidl_out = new SuspendInfo();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public SuspendInfo[] newArray(int _aidl_size) {
      return new SuspendInfo[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeLong(suspendAttemptCount);
    _aidl_parcel.writeLong(failedSuspendCount);
    _aidl_parcel.writeLong(shortSuspendCount);
    _aidl_parcel.writeLong(suspendTimeMillis);
    _aidl_parcel.writeLong(shortSuspendTimeMillis);
    _aidl_parcel.writeLong(suspendOverheadTimeMillis);
    _aidl_parcel.writeLong(failedSuspendOverheadTimeMillis);
    _aidl_parcel.writeLong(newBackoffCount);
    _aidl_parcel.writeLong(backoffContinueCount);
    _aidl_parcel.writeLong(sleepTimeMillis);
    _aidl_parcel.writeLongArray(suspendDurationMillisBins);
    _aidl_parcel.writeLongArray(consecutiveBadSuspendBins);
    _aidl_parcel.writeLong(maxBackoffContinuations);
    _aidl_parcel.writeLong(newBadSuspends);
    _aidl_parcel.writeLong(earlyRecoveryBadSuspends);
    _aidl_parcel.writeLong(breakEvenMillis);
    int _aidl_end_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.setDataPosition(_aidl_start_pos);
    _aidl_parcel.writeInt(_aidl_end_pos - _aidl_start_pos);
    _aidl_parcel.setDataPosition(_aidl_end_pos);
  }
  public final void readFromParcel(android.os.Parcel _aidl_parcel)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    int _aidl_parcelable_size = _aidl_parcel.readInt();
    try {
      if (_aidl_parcelable_size < 4) throw new android.os.BadParcelableException("Parcelable too small");;
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      suspendAttemptCount = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      failedSuspendCount = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      shortSuspendCount = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      suspendTimeMillis = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      shortSuspendTimeMillis = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      suspendOverheadTimeMillis = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      failedSuspendOverheadTimeMillis = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      newBackoffCount = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      backoffContinueCount = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sleepTimeMillis = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      suspendDurationMillisBins = _aidl_parcel.createLongArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      consecutiveBadSuspendBins = _aidl_parcel.createLongArray();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      maxBackoffContinuations = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      newBadSuspends = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      earlyRecoveryBadSuspends = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      breakEvenMillis = _aidl_parcel.readLong();
    } finally {
      if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
        throw new android.os.BadParcelableException("Overflow in the size of parcelable");
      }
      _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
    }
  }
  @Override
  public int describeContents() {
    int _mask = 0;
    return _mask;
  }
}
