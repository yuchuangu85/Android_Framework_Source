/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/core/java/process_info_service_aidl-java-source/gen/android/os/IProcessInfoService.java.d -o out/soong/.intermediates/frameworks/base/core/java/process_info_service_aidl-java-source/gen -Nframeworks/base/core/java frameworks/base/core/java/android/os/IProcessInfoService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.os;
/** @hide */
public interface IProcessInfoService extends android.os.IInterface
{
  /** Default implementation for IProcessInfoService. */
  public static class Default implements android.os.IProcessInfoService
  {
    /**
     * For each PID in the given input array, write the current process state
     * for that process into the output array, or ActivityManager.PROCESS_STATE_NONEXISTENT
     * to indicate that no process with the given PID exists.
     */
    @Override public void getProcessStatesFromPids(int[] pids, int[] states) throws android.os.RemoteException
    {
    }
    /**
     * For each PID in the given input array, write the current process state and OOM score
     * for that process into the output arrays, or ActivityManager.PROCESS_STATE_NONEXISTENT
     * in the states array to indicate that no process with the given PID exists.
     */
    @Override public void getProcessStatesAndOomScoresFromPids(int[] pids, int[] states, int[] scores) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.os.IProcessInfoService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.os.IProcessInfoService interface,
     * generating a proxy if needed.
     */
    public static android.os.IProcessInfoService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.os.IProcessInfoService))) {
        return ((android.os.IProcessInfoService)iin);
      }
      return new android.os.IProcessInfoService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(DESCRIPTOR);
      }
      switch (code)
      {
        case TRANSACTION_getProcessStatesFromPids:
        {
          int[] _arg0;
          _arg0 = data.createIntArray();
          int[] _arg1;
          int _arg1_length = data.readInt();
          if (_arg1_length > 1000000) {
            throw new android.os.BadParcelableException("Array too large: " + _arg1_length);
          } else if (_arg1_length < 0) {
            _arg1 = null;
          } else {
            _arg1 = new int[_arg1_length];
          }
          data.enforceNoDataAvail();
          this.getProcessStatesFromPids(_arg0, _arg1);
          reply.writeNoException();
          reply.writeIntArray(_arg1);
          break;
        }
        case TRANSACTION_getProcessStatesAndOomScoresFromPids:
        {
          int[] _arg0;
          _arg0 = data.createIntArray();
          int[] _arg1;
          int _arg1_length = data.readInt();
          if (_arg1_length > 1000000) {
            throw new android.os.BadParcelableException("Array too large: " + _arg1_length);
          } else if (_arg1_length < 0) {
            _arg1 = null;
          } else {
            _arg1 = new int[_arg1_length];
          }
          int[] _arg2;
          int _arg2_length = data.readInt();
          if (_arg2_length > 1000000) {
            throw new android.os.BadParcelableException("Array too large: " + _arg2_length);
          } else if (_arg2_length < 0) {
            _arg2 = null;
          } else {
            _arg2 = new int[_arg2_length];
          }
          data.enforceNoDataAvail();
          this.getProcessStatesAndOomScoresFromPids(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeIntArray(_arg1);
          reply.writeIntArray(_arg2);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.os.IProcessInfoService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public final java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      /**
       * For each PID in the given input array, write the current process state
       * for that process into the output array, or ActivityManager.PROCESS_STATE_NONEXISTENT
       * to indicate that no process with the given PID exists.
       */
      @Override public void getProcessStatesFromPids(int[] pids, int[] states) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeIntArray(pids);
          _data.writeInt(states.length);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getProcessStatesFromPids, _data, _reply, 0);
          _reply.readException();
          _reply.readIntArray(states);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /**
       * For each PID in the given input array, write the current process state and OOM score
       * for that process into the output arrays, or ActivityManager.PROCESS_STATE_NONEXISTENT
       * in the states array to indicate that no process with the given PID exists.
       */
      @Override public void getProcessStatesAndOomScoresFromPids(int[] pids, int[] states, int[] scores) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeIntArray(pids);
          _data.writeInt(states.length);
          _data.writeInt(scores.length);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getProcessStatesAndOomScoresFromPids, _data, _reply, 0);
          _reply.readException();
          _reply.readIntArray(states);
          _reply.readIntArray(scores);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    /** @hide */
    public static final java.lang.String DESCRIPTOR = "android.os.IProcessInfoService";
    static final int TRANSACTION_getProcessStatesFromPids = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getProcessStatesAndOomScoresFromPids = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /**
   * For each PID in the given input array, write the current process state
   * for that process into the output array, or ActivityManager.PROCESS_STATE_NONEXISTENT
   * to indicate that no process with the given PID exists.
   */
  public void getProcessStatesFromPids(int[] pids, int[] states) throws android.os.RemoteException;
  /**
   * For each PID in the given input array, write the current process state and OOM score
   * for that process into the output arrays, or ActivityManager.PROCESS_STATE_NONEXISTENT
   * in the states array to indicate that no process with the given PID exists.
   */
  public void getProcessStatesAndOomScoresFromPids(int[] pids, int[] states, int[] scores) throws android.os.RemoteException;
}
