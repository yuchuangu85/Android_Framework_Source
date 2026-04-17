/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/core/java/aisealhostservice_aidl-java-source/gen/android/aiseal/IAiSealHostService.java.d -o out/soong/.intermediates/frameworks/base/core/java/aisealhostservice_aidl-java-source/gen -Nframeworks/base/core/java frameworks/base/core/java/android/aiseal/IAiSealHostService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.aiseal;
/**
 * The service exposed by the AiSeal host service.
 * @hide
 */
public interface IAiSealHostService extends android.os.IInterface
{
  /** Default implementation for IAiSealHostService. */
  public static class Default implements android.aiseal.IAiSealHostService
  {
    /** Open a connection to the service hosted inside the VM */
    @Override public android.os.ParcelFileDescriptor connectService(java.lang.String name) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.aiseal.IAiSealHostService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.aiseal.IAiSealHostService interface,
     * generating a proxy if needed.
     */
    public static android.aiseal.IAiSealHostService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.aiseal.IAiSealHostService))) {
        return ((android.aiseal.IAiSealHostService)iin);
      }
      return new android.aiseal.IAiSealHostService.Stub.Proxy(obj);
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
        case TRANSACTION_connectService:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          data.enforceNoDataAvail();
          android.os.ParcelFileDescriptor _result = this.connectService(_arg0);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.aiseal.IAiSealHostService
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
      /** Open a connection to the service hosted inside the VM */
      @Override public android.os.ParcelFileDescriptor connectService(java.lang.String name) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.ParcelFileDescriptor _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(name);
          boolean _status = mRemote.transact(Stub.TRANSACTION_connectService, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.os.ParcelFileDescriptor.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_connectService = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.aiseal.IAiSealHostService";
  /** Open a connection to the service hosted inside the VM */
  public android.os.ParcelFileDescriptor connectService(java.lang.String name) throws android.os.RemoteException;
}
