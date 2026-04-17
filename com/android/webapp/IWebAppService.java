/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version 36 --ninja -d out/soong/.intermediates/packages/modules/WebApp/framework/framework-webapp-aidl-java-source/gen/com/android/webapp/IWebAppService.java.d -o out/soong/.intermediates/packages/modules/WebApp/framework/framework-webapp-aidl-java-source/gen -Npackages/modules/WebApp/framework/aidl packages/modules/WebApp/framework/aidl/com/android/webapp/IWebAppService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package com.android.webapp;
/**
 * Interface to interact with the Web App service.
 * @hide
 */
public interface IWebAppService extends android.os.IInterface
{
  /** Default implementation for IWebAppService. */
  public static class Default implements com.android.webapp.IWebAppService
  {
    /**
     * Installs the web app using a manifest URL. The request will be processed by the Web App
     * Service in the background. The installation result is delivered via the provided callback.
     * 
     * @param title The title of the app to install. This is used to show the install progress to
     *     the user before the installer can read the manifest. This may not be the name of the
     *     minted app as the names defined in the manifest will be prioritized.
     * @param manifestUrl The URL of the PWA manifest.
     * @param callback The callback to receive the installation result.
     */
    @Override public void install(java.lang.String title, java.lang.String manifestUrl, com.android.webapp.IWebAppInstallCallback callback) throws android.os.RemoteException
    {
    }
    /**
     * Queries if an Android app is installed by the Web App Service.
     * 
     * @param packageName Package name of the app to query.
     * @param callback The callback to receive the query result.
     */
    @Override public void queryPackage(java.lang.String packageName, com.android.webapp.IWebAppQueryCallback callback) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.android.webapp.IWebAppService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.android.webapp.IWebAppService interface,
     * generating a proxy if needed.
     */
    public static com.android.webapp.IWebAppService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.android.webapp.IWebAppService))) {
        return ((com.android.webapp.IWebAppService)iin);
      }
      return new com.android.webapp.IWebAppService.Stub.Proxy(obj);
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
        case TRANSACTION_install:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          com.android.webapp.IWebAppInstallCallback _arg2;
          _arg2 = com.android.webapp.IWebAppInstallCallback.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.install(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_queryPackage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          com.android.webapp.IWebAppQueryCallback _arg1;
          _arg1 = com.android.webapp.IWebAppQueryCallback.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.queryPackage(_arg0, _arg1);
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
    private static final class Proxy implements com.android.webapp.IWebAppService
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
       * Installs the web app using a manifest URL. The request will be processed by the Web App
       * Service in the background. The installation result is delivered via the provided callback.
       * 
       * @param title The title of the app to install. This is used to show the install progress to
       *     the user before the installer can read the manifest. This may not be the name of the
       *     minted app as the names defined in the manifest will be prioritized.
       * @param manifestUrl The URL of the PWA manifest.
       * @param callback The callback to receive the installation result.
       */
      @Override public void install(java.lang.String title, java.lang.String manifestUrl, com.android.webapp.IWebAppInstallCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(title);
          _data.writeString(manifestUrl);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_install, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /**
       * Queries if an Android app is installed by the Web App Service.
       * 
       * @param packageName Package name of the app to query.
       * @param callback The callback to receive the query result.
       */
      @Override public void queryPackage(java.lang.String packageName, com.android.webapp.IWebAppQueryCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_queryPackage, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_install = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_queryPackage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.android.webapp.IWebAppService";
  /**
   * Installs the web app using a manifest URL. The request will be processed by the Web App
   * Service in the background. The installation result is delivered via the provided callback.
   * 
   * @param title The title of the app to install. This is used to show the install progress to
   *     the user before the installer can read the manifest. This may not be the name of the
   *     minted app as the names defined in the manifest will be prioritized.
   * @param manifestUrl The URL of the PWA manifest.
   * @param callback The callback to receive the installation result.
   */
  public void install(java.lang.String title, java.lang.String manifestUrl, com.android.webapp.IWebAppInstallCallback callback) throws android.os.RemoteException;
  /**
   * Queries if an Android app is installed by the Web App Service.
   * 
   * @param packageName Package name of the app to query.
   * @param callback The callback to receive the query result.
   */
  public void queryPackage(java.lang.String packageName, com.android.webapp.IWebAppQueryCallback callback) throws android.os.RemoteException;
}
