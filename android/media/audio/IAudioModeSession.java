/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current -pout/soong/.intermediates/frameworks/native/libs/permission/framework-permission-aidl_interface/preprocessed.aidl --ninja -d out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen/android/media/audio/IAudioModeSession.java.d -o out/soong/.intermediates/frameworks/base/media/audio-aidl-java-source/gen -Nframeworks/base/media/aidl frameworks/base/media/aidl/android/media/audio/IAudioModeSession.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.audio;
/**
 * {@see android.media.AudioModeSession}.
 * @hide
 */
public interface IAudioModeSession extends android.os.IInterface
{
  /** Default implementation for IAudioModeSession. */
  public static class Default implements android.media.audio.IAudioModeSession
  {
    @Override public void setMode(int mode) throws android.os.RemoteException
    {
    }
    @Override public void setDisplayActiveUseCase(boolean isDisplayActiveUseCase) throws android.os.RemoteException
    {
    }
    @Override public int setRequestedRoute(android.media.audio.IAudioModeSession.Route route) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public java.util.List<android.media.audio.IAudioModeSession.Route> getAvailableRoutes() throws android.os.RemoteException
    {
      return null;
    }
    @Override public void setClientPaused(boolean isPaused) throws android.os.RemoteException
    {
    }
    @Override public void close() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.media.audio.IAudioModeSession
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.media.audio.IAudioModeSession interface,
     * generating a proxy if needed.
     */
    public static android.media.audio.IAudioModeSession asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.media.audio.IAudioModeSession))) {
        return ((android.media.audio.IAudioModeSession)iin);
      }
      return new android.media.audio.IAudioModeSession.Stub.Proxy(obj);
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
        case TRANSACTION_setMode:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          this.setMode(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_setDisplayActiveUseCase:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          data.enforceNoDataAvail();
          this.setDisplayActiveUseCase(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_setRequestedRoute:
        {
          android.media.audio.IAudioModeSession.Route _arg0;
          _arg0 = data.readTypedObject(android.media.audio.IAudioModeSession.Route.CREATOR);
          data.enforceNoDataAvail();
          int _result = this.setRequestedRoute(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_getAvailableRoutes:
        {
          java.util.List<android.media.audio.IAudioModeSession.Route> _result = this.getAvailableRoutes();
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_setClientPaused:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          data.enforceNoDataAvail();
          this.setClientPaused(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_close:
        {
          this.close();
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
    private static final class Proxy implements android.media.audio.IAudioModeSession
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
      @Override public void setMode(int mode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(mode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMode, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void setDisplayActiveUseCase(boolean isDisplayActiveUseCase) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(isDisplayActiveUseCase);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDisplayActiveUseCase, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public int setRequestedRoute(android.media.audio.IAudioModeSession.Route route) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(route, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setRequestedRoute, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.audio.IAudioModeSession.Route> getAvailableRoutes() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.audio.IAudioModeSession.Route> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAvailableRoutes, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.audio.IAudioModeSession.Route.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setClientPaused(boolean isPaused) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(isPaused);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setClientPaused, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void close() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_close, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_setMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_setDisplayActiveUseCase = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_setRequestedRoute = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_getAvailableRoutes = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_setClientPaused = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_close = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.media.audio.IAudioModeSession";
  public void setMode(int mode) throws android.os.RemoteException;
  public void setDisplayActiveUseCase(boolean isDisplayActiveUseCase) throws android.os.RemoteException;
  public int setRequestedRoute(android.media.audio.IAudioModeSession.Route route) throws android.os.RemoteException;
  public java.util.List<android.media.audio.IAudioModeSession.Route> getAvailableRoutes() throws android.os.RemoteException;
  public void setClientPaused(boolean isPaused) throws android.os.RemoteException;
  public void close() throws android.os.RemoteException;
  /**
   * Minimal route identity type.
   * @hide
   */
  public static class Route implements android.os.Parcelable
  {
    public android.media.audio.DeviceIdentity output;
    public android.media.audio.DeviceIdentity input;
    public static final android.os.Parcelable.Creator<Route> CREATOR = new android.os.Parcelable.Creator<Route>() {
      @Override
      public Route createFromParcel(android.os.Parcel _aidl_source) {
        Route _aidl_out = new Route();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public Route[] newArray(int _aidl_size) {
        return new Route[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeTypedObject(output, _aidl_flag);
      _aidl_parcel.writeTypedObject(input, _aidl_flag);
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
        output = _aidl_parcel.readTypedObject(android.media.audio.DeviceIdentity.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        input = _aidl_parcel.readTypedObject(android.media.audio.DeviceIdentity.CREATOR);
      } finally {
        if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
          throw new android.os.BadParcelableException("Overflow in the size of parcelable");
        }
        _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
      }
    }
    @Override
    public String toString() {
      java.util.StringJoiner _aidl_sj = new java.util.StringJoiner(", ", "{", "}");
      _aidl_sj.add("output: " + (java.util.Objects.toString(output)));
      _aidl_sj.add("input: " + (java.util.Objects.toString(input)));
      return "Route" + _aidl_sj.toString()  ;
    }
    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (other == null) return false;
      if (!(other instanceof Route)) return false;
      Route that = (Route)other;
      if (!java.util.Objects.deepEquals(output, that.output)) return false;
      if (!java.util.Objects.deepEquals(input, that.input)) return false;
      return true;
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.deepHashCode(java.util.Arrays.asList(output, input).toArray());
    }
    @Override
    public int describeContents() {
      int _mask = 0;
      _mask |= describeContents(output);
      _mask |= describeContents(input);
      return _mask;
    }
    private int describeContents(Object _v) {
      if (_v == null) return 0;
      if (_v instanceof android.os.Parcelable) {
        return ((android.os.Parcelable) _v).describeContents();
      }
      return 0;
    }
  }
}
