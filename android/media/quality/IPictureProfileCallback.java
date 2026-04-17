/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen/android/media/quality/IPictureProfileCallback.java.d -o out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen -Nframeworks/base/media/java/android/media/quality/aidl frameworks/base/media/java/android/media/quality/aidl/android/media/quality/IPictureProfileCallback.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.quality;
/**
 * Interface to receive callbacks from IMediaQuality.
 * @hide
 */
public interface IPictureProfileCallback extends android.os.IInterface
{
  /** Default implementation for IPictureProfileCallback. */
  public static class Default implements android.media.quality.IPictureProfileCallback
  {
    @Override public void onPictureProfileAdded(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException
    {
    }
    @Override public void onPictureProfileUpdated(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException
    {
    }
    @Override public void onPictureProfileRemoved(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException
    {
    }
    @Override public void onParameterCapabilitiesChanged(java.lang.String id, java.util.List<android.media.quality.ParameterCapability> caps) throws android.os.RemoteException
    {
    }
    @Override public void onError(java.lang.String id, int err) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.media.quality.IPictureProfileCallback
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.media.quality.IPictureProfileCallback interface,
     * generating a proxy if needed.
     */
    public static android.media.quality.IPictureProfileCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.media.quality.IPictureProfileCallback))) {
        return ((android.media.quality.IPictureProfileCallback)iin);
      }
      return new android.media.quality.IPictureProfileCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onPictureProfileAdded:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.media.quality.PictureProfile _arg1;
          _arg1 = data.readTypedObject(android.media.quality.PictureProfile.CREATOR);
          data.enforceNoDataAvail();
          this.onPictureProfileAdded(_arg0, _arg1);
          break;
        }
        case TRANSACTION_onPictureProfileUpdated:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.media.quality.PictureProfile _arg1;
          _arg1 = data.readTypedObject(android.media.quality.PictureProfile.CREATOR);
          data.enforceNoDataAvail();
          this.onPictureProfileUpdated(_arg0, _arg1);
          break;
        }
        case TRANSACTION_onPictureProfileRemoved:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.media.quality.PictureProfile _arg1;
          _arg1 = data.readTypedObject(android.media.quality.PictureProfile.CREATOR);
          data.enforceNoDataAvail();
          this.onPictureProfileRemoved(_arg0, _arg1);
          break;
        }
        case TRANSACTION_onParameterCapabilitiesChanged:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.util.List<android.media.quality.ParameterCapability> _arg1;
          _arg1 = data.createTypedArrayList(android.media.quality.ParameterCapability.CREATOR);
          data.enforceNoDataAvail();
          this.onParameterCapabilitiesChanged(_arg0, _arg1);
          break;
        }
        case TRANSACTION_onError:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.onError(_arg0, _arg1);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.media.quality.IPictureProfileCallback
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
      @Override public void onPictureProfileAdded(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeTypedObject(p, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPictureProfileAdded, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onPictureProfileUpdated(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeTypedObject(p, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPictureProfileUpdated, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onPictureProfileRemoved(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeTypedObject(p, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPictureProfileRemoved, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onParameterCapabilitiesChanged(java.lang.String id, java.util.List<android.media.quality.ParameterCapability> caps) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeTypedList(caps, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onParameterCapabilitiesChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onError(java.lang.String id, int err) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeInt(err);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onError, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onPictureProfileAdded = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onPictureProfileUpdated = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_onPictureProfileRemoved = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_onParameterCapabilitiesChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_onError = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.media.quality.IPictureProfileCallback";
  public void onPictureProfileAdded(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException;
  public void onPictureProfileUpdated(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException;
  public void onPictureProfileRemoved(java.lang.String id, android.media.quality.PictureProfile p) throws android.os.RemoteException;
  public void onParameterCapabilitiesChanged(java.lang.String id, java.util.List<android.media.quality.ParameterCapability> caps) throws android.os.RemoteException;
  public void onError(java.lang.String id, int err) throws android.os.RemoteException;
}
