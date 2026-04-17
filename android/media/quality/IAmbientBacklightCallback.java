/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen/android/media/quality/IAmbientBacklightCallback.java.d -o out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen -Nframeworks/base/media/java/android/media/quality/aidl frameworks/base/media/java/android/media/quality/aidl/android/media/quality/IAmbientBacklightCallback.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.quality;
/** @hide */
public interface IAmbientBacklightCallback extends android.os.IInterface
{
  /** Default implementation for IAmbientBacklightCallback. */
  public static class Default implements android.media.quality.IAmbientBacklightCallback
  {
    @Override public void onAmbientBacklightEvent(android.media.quality.AmbientBacklightEvent event) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.media.quality.IAmbientBacklightCallback
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.media.quality.IAmbientBacklightCallback interface,
     * generating a proxy if needed.
     */
    public static android.media.quality.IAmbientBacklightCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.media.quality.IAmbientBacklightCallback))) {
        return ((android.media.quality.IAmbientBacklightCallback)iin);
      }
      return new android.media.quality.IAmbientBacklightCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onAmbientBacklightEvent:
        {
          android.media.quality.AmbientBacklightEvent _arg0;
          _arg0 = data.readTypedObject(android.media.quality.AmbientBacklightEvent.CREATOR);
          data.enforceNoDataAvail();
          this.onAmbientBacklightEvent(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.media.quality.IAmbientBacklightCallback
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
      @Override public void onAmbientBacklightEvent(android.media.quality.AmbientBacklightEvent event) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(event, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onAmbientBacklightEvent, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onAmbientBacklightEvent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.media.quality.IAmbientBacklightCallback";
  public void onAmbientBacklightEvent(android.media.quality.AmbientBacklightEvent event) throws android.os.RemoteException;
}
