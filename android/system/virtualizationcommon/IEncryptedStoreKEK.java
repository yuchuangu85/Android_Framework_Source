/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationcommon-java-source/gen/android/system/virtualizationcommon/IEncryptedStoreKEK.java.d -o out/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationcommon-java-source/gen -Npackages/modules/Virtualization/android/virtualizationservice/aidl packages/modules/Virtualization/android/virtualizationservice/aidl/android/system/virtualizationcommon/IEncryptedStoreKEK.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.system.virtualizationcommon;
/** @hide */
public interface IEncryptedStoreKEK extends android.os.IInterface
{
  /** Default implementation for IEncryptedStoreKEK. */
  public static class Default implements android.system.virtualizationcommon.IEncryptedStoreKEK
  {
    /**
     * Returns a KEK used to set up the encrypted store, or {@code null} if encrypted store
     * hasn't been set up yet.
     * 
     * <p>If {@code null} is returned, then {@code microdroid_manager} should:
     *  1. Use the obtained salt to create a new key to set up encrypted store with.
     *  2. Encrypt it with a different key.
     *  3. Send resulting KEK back to the Android host by calling {@code onKEKCreated} callback.
     */
    @Override public byte[] getKEK() throws android.os.RemoteException
    {
      return null;
    }
    /**
     * A callback {@code microdroid_manager} should call when new KEK is created.
     * 
     * <p>Android host is expected to store the resulting kek on disk (e.g. in app's private CE
     * directory). On subsequent VM boots {@code microdroid_manager} will request the KEK from
     * the Android host, and then use it to set up the encrypted store.
     */
    @Override public void onKEKCreated(byte[] kek) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.system.virtualizationcommon.IEncryptedStoreKEK
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.system.virtualizationcommon.IEncryptedStoreKEK interface,
     * generating a proxy if needed.
     */
    public static android.system.virtualizationcommon.IEncryptedStoreKEK asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.system.virtualizationcommon.IEncryptedStoreKEK))) {
        return ((android.system.virtualizationcommon.IEncryptedStoreKEK)iin);
      }
      return new android.system.virtualizationcommon.IEncryptedStoreKEK.Stub.Proxy(obj);
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
        case TRANSACTION_getKEK:
        {
          byte[] _result = this.getKEK();
          reply.writeNoException();
          reply.writeByteArray(_result);
          break;
        }
        case TRANSACTION_onKEKCreated:
        {
          byte[] _arg0;
          _arg0 = data.createByteArray();
          data.enforceNoDataAvail();
          this.onKEKCreated(_arg0);
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
    private static final class Proxy implements android.system.virtualizationcommon.IEncryptedStoreKEK
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
       * Returns a KEK used to set up the encrypted store, or {@code null} if encrypted store
       * hasn't been set up yet.
       * 
       * <p>If {@code null} is returned, then {@code microdroid_manager} should:
       *  1. Use the obtained salt to create a new key to set up encrypted store with.
       *  2. Encrypt it with a different key.
       *  3. Send resulting KEK back to the Android host by calling {@code onKEKCreated} callback.
       */
      @Override public byte[] getKEK() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        byte[] _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getKEK, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createByteArray();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * A callback {@code microdroid_manager} should call when new KEK is created.
       * 
       * <p>Android host is expected to store the resulting kek on disk (e.g. in app's private CE
       * directory). On subsequent VM boots {@code microdroid_manager} will request the KEK from
       * the Android host, and then use it to set up the encrypted store.
       */
      @Override public void onKEKCreated(byte[] kek) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeByteArray(kek);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onKEKCreated, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_getKEK = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onKEKCreated = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.system.virtualizationcommon.IEncryptedStoreKEK";
  /**
   * Returns a KEK used to set up the encrypted store, or {@code null} if encrypted store
   * hasn't been set up yet.
   * 
   * <p>If {@code null} is returned, then {@code microdroid_manager} should:
   *  1. Use the obtained salt to create a new key to set up encrypted store with.
   *  2. Encrypt it with a different key.
   *  3. Send resulting KEK back to the Android host by calling {@code onKEKCreated} callback.
   */
  public byte[] getKEK() throws android.os.RemoteException;
  /**
   * A callback {@code microdroid_manager} should call when new KEK is created.
   * 
   * <p>Android host is expected to store the resulting kek on disk (e.g. in app's private CE
   * directory). On subsequent VM boots {@code microdroid_manager} will request the KEK from
   * the Android host, and then use it to set up the encrypted store.
   */
  public void onKEKCreated(byte[] kek) throws android.os.RemoteException;
}
