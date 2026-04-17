/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationcommon-java-source/gen/android/system/virtualizationcommon/IGuestAgent.java.d -o out/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationcommon-java-source/gen -Npackages/modules/Virtualization/android/virtualizationservice/aidl packages/modules/Virtualization/android/virtualizationservice/aidl/android/system/virtualizationcommon/IGuestAgent.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.system.virtualizationcommon;
public interface IGuestAgent extends android.os.IInterface
{
  /** Default implementation for IGuestAgent. */
  public static class Default implements android.system.virtualizationcommon.IGuestAgent
  {
    /** Shuts the VM down gracefully. */
    @Override public void shutdownAsync() throws android.os.RemoteException
    {
    }
    // TODO(b/469712830): Move these Microdroid specific APIs to an extension.
    /**
     * Starts a vsock server to dump the VM's state, and return a port number for the listening
     * vsock. The guest agent must open a vsock server which accepts one client, and then sends VM's
     * dump to the client. Writing to the client vsock must be done within 5 seconds. Otherwise, the
     * requester may regard it as a timeout.
     * 
     * TODO(b/395205629): Use IBinder::Interface::dump().
     */
    @Override public int startDumpVsockServer(java.lang.String[] args) throws android.os.RemoteException
    {
      return 0;
    }
    /** Requests the VM to trim its memory usage. */
    @Override public void trimAsync() throws android.os.RemoteException
    {
    }
    /** Called when a user is unlocked. */
    @Override public void userUnlocked(int user_id, android.system.virtualizationcommon.ICEStoreKEK per_user_kek) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.system.virtualizationcommon.IGuestAgent
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.system.virtualizationcommon.IGuestAgent interface,
     * generating a proxy if needed.
     */
    public static android.system.virtualizationcommon.IGuestAgent asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.system.virtualizationcommon.IGuestAgent))) {
        return ((android.system.virtualizationcommon.IGuestAgent)iin);
      }
      return new android.system.virtualizationcommon.IGuestAgent.Stub.Proxy(obj);
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
        case TRANSACTION_shutdownAsync:
        {
          this.shutdownAsync();
          break;
        }
        case TRANSACTION_startDumpVsockServer:
        {
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          data.enforceNoDataAvail();
          int _result = this.startDumpVsockServer(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_trimAsync:
        {
          this.trimAsync();
          break;
        }
        case TRANSACTION_userUnlocked:
        {
          int _arg0;
          _arg0 = data.readInt();
          android.system.virtualizationcommon.ICEStoreKEK _arg1;
          _arg1 = android.system.virtualizationcommon.ICEStoreKEK.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.userUnlocked(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.system.virtualizationcommon.IGuestAgent
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
      /** Shuts the VM down gracefully. */
      @Override public void shutdownAsync() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_shutdownAsync, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      // TODO(b/469712830): Move these Microdroid specific APIs to an extension.
      /**
       * Starts a vsock server to dump the VM's state, and return a port number for the listening
       * vsock. The guest agent must open a vsock server which accepts one client, and then sends VM's
       * dump to the client. Writing to the client vsock must be done within 5 seconds. Otherwise, the
       * requester may regard it as a timeout.
       * 
       * TODO(b/395205629): Use IBinder::Interface::dump().
       */
      @Override public int startDumpVsockServer(java.lang.String[] args) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(args);
          boolean _status = mRemote.transact(Stub.TRANSACTION_startDumpVsockServer, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Requests the VM to trim its memory usage. */
      @Override public void trimAsync() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_trimAsync, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      /** Called when a user is unlocked. */
      @Override public void userUnlocked(int user_id, android.system.virtualizationcommon.ICEStoreKEK per_user_kek) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(user_id);
          _data.writeStrongInterface(per_user_kek);
          boolean _status = mRemote.transact(Stub.TRANSACTION_userUnlocked, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_shutdownAsync = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_startDumpVsockServer = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_trimAsync = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_userUnlocked = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.system.virtualizationcommon.IGuestAgent";
  /** Shuts the VM down gracefully. */
  public void shutdownAsync() throws android.os.RemoteException;
  // TODO(b/469712830): Move these Microdroid specific APIs to an extension.
  /**
   * Starts a vsock server to dump the VM's state, and return a port number for the listening
   * vsock. The guest agent must open a vsock server which accepts one client, and then sends VM's
   * dump to the client. Writing to the client vsock must be done within 5 seconds. Otherwise, the
   * requester may regard it as a timeout.
   * 
   * TODO(b/395205629): Use IBinder::Interface::dump().
   */
  public int startDumpVsockServer(java.lang.String[] args) throws android.os.RemoteException;
  /** Requests the VM to trim its memory usage. */
  public void trimAsync() throws android.os.RemoteException;
  /** Called when a user is unlocked. */
  public void userUnlocked(int user_id, android.system.virtualizationcommon.ICEStoreKEK per_user_kek) throws android.os.RemoteException;
}
