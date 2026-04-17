/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen/android/media/quality/IActiveProcessingPictureListener.java.d -o out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen -Nframeworks/base/media/java/android/media/quality/aidl frameworks/base/media/java/android/media/quality/aidl/android/media/quality/IActiveProcessingPictureListener.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.quality;
/**
 * Interface to receive event from media quality service.
 * @hide
 */
public interface IActiveProcessingPictureListener extends android.os.IInterface
{
  /** Default implementation for IActiveProcessingPictureListener. */
  public static class Default implements android.media.quality.IActiveProcessingPictureListener
  {
    @Override public void onActiveProcessingPicturesChanged(java.util.List<android.media.quality.ActiveProcessingPicture> ap) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.media.quality.IActiveProcessingPictureListener
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.media.quality.IActiveProcessingPictureListener interface,
     * generating a proxy if needed.
     */
    public static android.media.quality.IActiveProcessingPictureListener asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.media.quality.IActiveProcessingPictureListener))) {
        return ((android.media.quality.IActiveProcessingPictureListener)iin);
      }
      return new android.media.quality.IActiveProcessingPictureListener.Stub.Proxy(obj);
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
        case TRANSACTION_onActiveProcessingPicturesChanged:
        {
          java.util.List<android.media.quality.ActiveProcessingPicture> _arg0;
          _arg0 = data.createTypedArrayList(android.media.quality.ActiveProcessingPicture.CREATOR);
          data.enforceNoDataAvail();
          this.onActiveProcessingPicturesChanged(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.media.quality.IActiveProcessingPictureListener
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
      @Override public void onActiveProcessingPicturesChanged(java.util.List<android.media.quality.ActiveProcessingPicture> ap) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedList(ap, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onActiveProcessingPicturesChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onActiveProcessingPicturesChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.media.quality.IActiveProcessingPictureListener";
  public void onActiveProcessingPicturesChanged(java.util.List<android.media.quality.ActiveProcessingPicture> ap) throws android.os.RemoteException;
}
