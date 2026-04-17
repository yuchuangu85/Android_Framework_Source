/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current -pout/soong/.intermediates/frameworks/native/libs/permission/framework-permission-aidl_interface/preprocessed.aidl --ninja -d out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen/android/media/audio/IAudioModeSessionCallback.java.d -o out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen -Nframeworks/base/media/aidl frameworks/base/media/aidl/android/media/audio/IAudioModeSessionCallback.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.audio;
/**
 * Callback interface for {@link IAudioModeSession}.
 * @hide
 */
public interface IAudioModeSessionCallback extends android.os.IInterface
{
  /** Default implementation for IAudioModeSessionCallback. */
  public static class Default implements android.media.audio.IAudioModeSessionCallback
  {
    @Override public void onAvailableRoutesChanged(java.util.List<android.media.audio.IAudioModeSession.Route> availableRoutes) throws android.os.RemoteException
    {
    }
    @Override public void onExternalRequestedRouteChanged(android.media.audio.IAudioModeSession.Route newRoute, int requestId) throws android.os.RemoteException
    {
    }
    @Override public void onPaused() throws android.os.RemoteException
    {
    }
    @Override public void onResumed(int requestId) throws android.os.RemoteException
    {
    }
    @Override public void onClosed() throws android.os.RemoteException
    {
    }
    @Override public void onRoutingResult(int requestId, android.media.audio.IAudioModeSession.Route route, int status) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.media.audio.IAudioModeSessionCallback
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.media.audio.IAudioModeSessionCallback interface,
     * generating a proxy if needed.
     */
    public static android.media.audio.IAudioModeSessionCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.media.audio.IAudioModeSessionCallback))) {
        return ((android.media.audio.IAudioModeSessionCallback)iin);
      }
      return new android.media.audio.IAudioModeSessionCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onAvailableRoutesChanged:
        {
          java.util.List<android.media.audio.IAudioModeSession.Route> _arg0;
          _arg0 = data.createTypedArrayList(android.media.audio.IAudioModeSession.Route.CREATOR);
          data.enforceNoDataAvail();
          this.onAvailableRoutesChanged(_arg0);
          break;
        }
        case TRANSACTION_onExternalRequestedRouteChanged:
        {
          android.media.audio.IAudioModeSession.Route _arg0;
          _arg0 = data.readTypedObject(android.media.audio.IAudioModeSession.Route.CREATOR);
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.onExternalRequestedRouteChanged(_arg0, _arg1);
          break;
        }
        case TRANSACTION_onPaused:
        {
          this.onPaused();
          break;
        }
        case TRANSACTION_onResumed:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          this.onResumed(_arg0);
          break;
        }
        case TRANSACTION_onClosed:
        {
          this.onClosed();
          break;
        }
        case TRANSACTION_onRoutingResult:
        {
          int _arg0;
          _arg0 = data.readInt();
          android.media.audio.IAudioModeSession.Route _arg1;
          _arg1 = data.readTypedObject(android.media.audio.IAudioModeSession.Route.CREATOR);
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          this.onRoutingResult(_arg0, _arg1, _arg2);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.media.audio.IAudioModeSessionCallback
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
      @Override public void onAvailableRoutesChanged(java.util.List<android.media.audio.IAudioModeSession.Route> availableRoutes) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedList(availableRoutes, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onAvailableRoutesChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onExternalRequestedRouteChanged(android.media.audio.IAudioModeSession.Route newRoute, int requestId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(newRoute, 0);
          _data.writeInt(requestId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onExternalRequestedRouteChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onPaused() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPaused, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onResumed(int requestId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(requestId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onResumed, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onClosed() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onClosed, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onRoutingResult(int requestId, android.media.audio.IAudioModeSession.Route route, int status) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(requestId);
          _data.writeTypedObject(route, 0);
          _data.writeInt(status);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onRoutingResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onAvailableRoutesChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onExternalRequestedRouteChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_onPaused = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_onResumed = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_onClosed = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_onRoutingResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.media.audio.IAudioModeSessionCallback";
  public void onAvailableRoutesChanged(java.util.List<android.media.audio.IAudioModeSession.Route> availableRoutes) throws android.os.RemoteException;
  public void onExternalRequestedRouteChanged(android.media.audio.IAudioModeSession.Route newRoute, int requestId) throws android.os.RemoteException;
  public void onPaused() throws android.os.RemoteException;
  public void onResumed(int requestId) throws android.os.RemoteException;
  public void onClosed() throws android.os.RemoteException;
  public void onRoutingResult(int requestId, android.media.audio.IAudioModeSession.Route route, int status) throws android.os.RemoteException;
}
