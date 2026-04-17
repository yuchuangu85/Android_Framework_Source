/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/core/java/dropboxmanager_aidl-java-source/gen/com/android/internal/os/IDropBoxManagerService.java.d -o out/soong/.intermediates/frameworks/base/core/java/dropboxmanager_aidl-java-source/gen -Nframeworks/base/core/java frameworks/base/core/java/com/android/internal/os/IDropBoxManagerService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package com.android.internal.os;
/**
 * "Backend" interface used by {@link android.os.DropBoxManager} to talk to the
 * DropBoxManagerService that actually implements the drop box functionality.
 * 
 * @see DropBoxManager
 * @hide
 */
public interface IDropBoxManagerService extends android.os.IInterface
{
  /** Default implementation for IDropBoxManagerService. */
  public static class Default implements com.android.internal.os.IDropBoxManagerService
  {
    @Override public void addData(java.lang.String tag, byte[] data, int flags) throws android.os.RemoteException
    {
    }
    @Override public void addFile(java.lang.String tag, android.os.ParcelFileDescriptor fd, int flags) throws android.os.RemoteException
    {
    }
    /** @see DropBoxManager#getNextEntry */
    @Override public boolean isTagEnabled(java.lang.String tag) throws android.os.RemoteException
    {
      return false;
    }
    /** @see DropBoxManager#getNextEntry */
    @Override public com.android.internal.os.IDropBoxManagerService.Entry getNextEntry(java.lang.String tag, long millis, java.lang.String packageName) throws android.os.RemoteException
    {
      return null;
    }
    @Override public com.android.internal.os.IDropBoxManagerService.Entry getNextEntryWithAttribution(java.lang.String tag, long millis, java.lang.String packageName, java.lang.String attributionTag) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.android.internal.os.IDropBoxManagerService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.android.internal.os.IDropBoxManagerService interface,
     * generating a proxy if needed.
     */
    public static com.android.internal.os.IDropBoxManagerService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.android.internal.os.IDropBoxManagerService))) {
        return ((com.android.internal.os.IDropBoxManagerService)iin);
      }
      return new com.android.internal.os.IDropBoxManagerService.Stub.Proxy(obj);
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
        case TRANSACTION_addData:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          byte[] _arg1;
          _arg1 = data.createByteArray();
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          this.addData(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_addFile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.os.ParcelFileDescriptor _arg1;
          _arg1 = data.readTypedObject(android.os.ParcelFileDescriptor.CREATOR);
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          this.addFile(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isTagEnabled:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          data.enforceNoDataAvail();
          boolean _result = this.isTagEnabled(_arg0);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_getNextEntry:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          long _arg1;
          _arg1 = data.readLong();
          java.lang.String _arg2;
          _arg2 = data.readString();
          data.enforceNoDataAvail();
          com.android.internal.os.IDropBoxManagerService.Entry _result = this.getNextEntry(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getNextEntryWithAttribution:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          long _arg1;
          _arg1 = data.readLong();
          java.lang.String _arg2;
          _arg2 = data.readString();
          java.lang.String _arg3;
          _arg3 = data.readString();
          data.enforceNoDataAvail();
          com.android.internal.os.IDropBoxManagerService.Entry _result = this.getNextEntryWithAttribution(_arg0, _arg1, _arg2, _arg3);
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
    private static final class Proxy implements com.android.internal.os.IDropBoxManagerService
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
      @Override public void addData(java.lang.String tag, byte[] data, int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(tag);
          _data.writeByteArray(data);
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_addData, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void addFile(java.lang.String tag, android.os.ParcelFileDescriptor fd, int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(tag);
          _data.writeTypedObject(fd, 0);
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_addFile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** @see DropBoxManager#getNextEntry */
      @Override public boolean isTagEnabled(java.lang.String tag) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(tag);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isTagEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** @see DropBoxManager#getNextEntry */
      @Override public com.android.internal.os.IDropBoxManagerService.Entry getNextEntry(java.lang.String tag, long millis, java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.android.internal.os.IDropBoxManagerService.Entry _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(tag);
          _data.writeLong(millis);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getNextEntry, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(com.android.internal.os.IDropBoxManagerService.Entry.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public com.android.internal.os.IDropBoxManagerService.Entry getNextEntryWithAttribution(java.lang.String tag, long millis, java.lang.String packageName, java.lang.String attributionTag) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.android.internal.os.IDropBoxManagerService.Entry _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(tag);
          _data.writeLong(millis);
          _data.writeString(packageName);
          _data.writeString(attributionTag);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getNextEntryWithAttribution, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(com.android.internal.os.IDropBoxManagerService.Entry.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    /** @hide */
    public static final java.lang.String DESCRIPTOR = "com.android.internal.os.IDropBoxManagerService";
    static final int TRANSACTION_addData = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_addFile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_isTagEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_getNextEntry = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_getNextEntryWithAttribution = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
  }
  public void addData(java.lang.String tag, byte[] data, int flags) throws android.os.RemoteException;
  public void addFile(java.lang.String tag, android.os.ParcelFileDescriptor fd, int flags) throws android.os.RemoteException;
  /** @see DropBoxManager#getNextEntry */
  public boolean isTagEnabled(java.lang.String tag) throws android.os.RemoteException;
  /** @see DropBoxManager#getNextEntry */
  @android.compat.annotation.UnsupportedAppUsage(maxTargetSdk = 30, publicAlternatives = "Use {@link android.os.DropBoxManager#getNextEntry} instead", overrideSourcePosition="frameworks/base/core/java/com/android/internal/os/IDropBoxManagerService.aidl:36:1:37:93")
  public com.android.internal.os.IDropBoxManagerService.Entry getNextEntry(java.lang.String tag, long millis, java.lang.String packageName) throws android.os.RemoteException;
  public com.android.internal.os.IDropBoxManagerService.Entry getNextEntryWithAttribution(java.lang.String tag, long millis, java.lang.String packageName, java.lang.String attributionTag) throws android.os.RemoteException;
  /**
   * An entry maintained by drop box, including contents and metadata.
   * @hide
   */
  public static class Entry implements android.os.Parcelable
  {
    public java.lang.String tag;
    public long timestampMillis = 0L;
    public int flags = 0;
    public android.os.ParcelFileDescriptor fd;
    public byte[] data;
    public static final android.os.Parcelable.Creator<Entry> CREATOR = new android.os.Parcelable.Creator<Entry>() {
      @Override
      public Entry createFromParcel(android.os.Parcel _aidl_source) {
        Entry _aidl_out = new Entry();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public Entry[] newArray(int _aidl_size) {
        return new Entry[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeString(tag);
      _aidl_parcel.writeLong(timestampMillis);
      _aidl_parcel.writeInt(flags);
      _aidl_parcel.writeTypedObject(fd, _aidl_flag);
      _aidl_parcel.writeByteArray(data);
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
        tag = _aidl_parcel.readString();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        timestampMillis = _aidl_parcel.readLong();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        flags = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        fd = _aidl_parcel.readTypedObject(android.os.ParcelFileDescriptor.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        data = _aidl_parcel.createByteArray();
      } finally {
        if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
          throw new android.os.BadParcelableException("Overflow in the size of parcelable");
        }
        _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
      }
    }
    @Override
    public int describeContents() {
      int _mask = 0;
      _mask |= describeContents(fd);
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
