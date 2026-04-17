/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/native/libs/sensor/libsensor_aidl-java-source/gen/android/hardware/sensor/ISensorClientListener.java.d -o out/soong/.intermediates/frameworks/native/libs/sensor/libsensor_aidl-java-source/gen -Nframeworks/native/libs/sensor/include frameworks/native/libs/sensor/include/android/hardware/sensor/ISensorClientListener.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.sensor;
public interface ISensorClientListener extends android.os.IInterface
{
  /** Default implementation for ISensorClientListener. */
  public static class Default implements android.hardware.sensor.ISensorClientListener
  {
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.hardware.sensor.ISensorClientListener
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.hardware.sensor.ISensorClientListener interface,
     * generating a proxy if needed.
     */
    public static android.hardware.sensor.ISensorClientListener asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.hardware.sensor.ISensorClientListener))) {
        return ((android.hardware.sensor.ISensorClientListener)iin);
      }
      return new android.hardware.sensor.ISensorClientListener.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      switch (code)
      {
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static final class Proxy implements android.hardware.sensor.ISensorClientListener
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
    }
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.hardware.sensor.ISensorClientListener";
}
