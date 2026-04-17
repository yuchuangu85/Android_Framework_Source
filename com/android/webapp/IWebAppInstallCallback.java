/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version 36 --ninja -d out/soong/.intermediates/packages/modules/WebApp/framework/framework-webapp-aidl-java-source/gen/com/android/webapp/IWebAppInstallCallback.java.d -o out/soong/.intermediates/packages/modules/WebApp/framework/framework-webapp-aidl-java-source/gen -Npackages/modules/WebApp/framework/aidl packages/modules/WebApp/framework/aidl/com/android/webapp/IWebAppInstallCallback.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package com.android.webapp;
/**
 * Callback for the result of a web app installation.
 * @hide
 */
public interface IWebAppInstallCallback extends android.os.IInterface
{
  /** Default implementation for IWebAppInstallCallback. */
  public static class Default implements com.android.webapp.IWebAppInstallCallback
  {
    /** Called when the installation result is available. */
    @Override public void onInstallResult(int resultCode, java.lang.String packageName) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.android.webapp.IWebAppInstallCallback
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.android.webapp.IWebAppInstallCallback interface,
     * generating a proxy if needed.
     */
    public static com.android.webapp.IWebAppInstallCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.android.webapp.IWebAppInstallCallback))) {
        return ((com.android.webapp.IWebAppInstallCallback)iin);
      }
      return new com.android.webapp.IWebAppInstallCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onInstallResult:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          data.enforceNoDataAvail();
          this.onInstallResult(_arg0, _arg1);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements com.android.webapp.IWebAppInstallCallback
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
      /** Called when the installation result is available. */
      @Override public void onInstallResult(int resultCode, java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(resultCode);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onInstallResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onInstallResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.android.webapp.IWebAppInstallCallback";
  /** Called when the installation result is available. */
  public void onInstallResult(int resultCode, java.lang.String packageName) throws android.os.RemoteException;
}
