/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current -pout/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationcommon_interface/preprocessed.aidl --ninja -d out/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationservice-java-source/gen/android/system/virtualizationservice/IVirtualMachine.java.d -o out/soong/.intermediates/packages/modules/Virtualization/android/virtualizationservice/aidl/android.system.virtualizationservice-java-source/gen -Npackages/modules/Virtualization/android/virtualizationservice/aidl packages/modules/Virtualization/android/virtualizationservice/aidl/android/system/virtualizationservice/IVirtualMachine.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.system.virtualizationservice;
public interface IVirtualMachine extends android.os.IInterface
{
  /** Default implementation for IVirtualMachine. */
  public static class Default implements android.system.virtualizationservice.IVirtualMachine
  {
    /** Get the CID allocated to the VM. */
    @Override public int getCid() throws android.os.RemoteException
    {
      return 0;
    }
    /** Returns the current lifecycle state of the VM. */
    @Override public int getState() throws android.os.RemoteException
    {
      return 0;
    }
    /**
     * Register a Binder object to get callbacks when the state of the VM changes, such as if it
     * dies.
     */
    @Override public void registerCallback(android.system.virtualizationservice.IVirtualMachineCallback callback) throws android.os.RemoteException
    {
    }
    /** Starts running the VM. */
    @Override public void start() throws android.os.RemoteException
    {
    }
    /**
     * Stops this virtual machine. Stopping a virtual machine is like pulling the plug on a real
     * computer; the machine halts immediately. Software running on the virtual machine is not
     * notified with the event.
     */
    @Override public void stop() throws android.os.RemoteException
    {
    }
    /** Access to the VM's memory balloon. */
    @Override public boolean isMemoryBalloonEnabled() throws android.os.RemoteException
    {
      return false;
    }
    @Override public long getActualMemoryBalloonBytes() throws android.os.RemoteException
    {
      return 0L;
    }
    @Override public void setMemoryBalloon(long num_bytes) throws android.os.RemoteException
    {
    }
    /** Open a vsock connection to the CID of the VM on the given port. */
    @Override public android.os.ParcelFileDescriptor connectVsock(int port) throws android.os.RemoteException
    {
      return null;
    }
    /**
     * Create an Accessor in libbinder that will open a vsock connection
     * to the CID of the VM on the given port.
     * 
     * \param instance name of the service that the accessor is responsible for.
     *        This is the same instance that we expect clients to use when trying
     *        to get the service with the ServiceManager APIs.
     * 
     * \return IBinder of the IAccessor on success, or throws a service specific exception
     *         on error. See the ERROR_* values above.
     */
    @Override public android.os.IBinder createAccessorBinder(java.lang.String instance, int port) throws android.os.RemoteException
    {
      return null;
    }
    /** Set the name of the peer end (ptsname) of the host console. */
    @Override public void setHostConsoleName(java.lang.String pathname) throws android.os.RemoteException
    {
    }
    /** Suspends the VM vcpus. */
    @Override public void suspend() throws android.os.RemoteException
    {
    }
    /** Resumes the suspended VM vcpus. */
    @Override public void resume() throws android.os.RemoteException
    {
    }
    /** Returns debug info for this virtual machine */
    @Override public android.system.virtualizationservice.VirtualMachineDebugInfo getDebugInfo() throws android.os.RemoteException
    {
      return null;
    }
    /**
     * Adds memory represented by the fd and offset to the guest IPA space at the given range
     * [rangeStart, rangeEnd).
     * 
     * On success returns a unique id representing the memory shared with guest. This id can be
     * used to remove the memory from the guest using the removeMemoryFromGuest API below. Returns
     * a negative value on failure.
     */
    @Override public int addMemoryToGuest(android.os.ParcelFileDescriptor fd, long offset, long rangeStart, long rangeEnd, boolean cacheable) throws android.os.RemoteException
    {
      return 0;
    }
    /**
     * Removes the memory represented by memory_id from guest IPA space.
     * NOTE: This API must be called only after guest frees the memory using the
     * ARM_SMCCC_MEM_RELIQUINSH hypercall.
     */
    @Override public void removeMemoryFromGuest(int memory_id) throws android.os.RemoteException
    {
    }
    /** Returns guest agent */
    @Override public android.system.virtualizationcommon.IGuestAgent getGuestAgent() throws android.os.RemoteException
    {
      return null;
    }
    /**
     * Adds a new display to the VM.
     * 
     * @param config The configuration for the new display.
     */
    @Override public void addDisplay(android.system.virtualizationservice.DisplayConfig config) throws android.os.RemoteException
    {
    }
    /**
     * Removes a display from the VM.
     * 
     * @param displayId The ID of the display to remove.
     */
    @Override public void removeDisplay(int displayId) throws android.os.RemoteException
    {
    }
    /** Returns the list of currently active displays. */
    @Override public android.system.virtualizationservice.VirtualMachineDisplay[] getDisplays() throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.system.virtualizationservice.IVirtualMachine
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.system.virtualizationservice.IVirtualMachine interface,
     * generating a proxy if needed.
     */
    public static android.system.virtualizationservice.IVirtualMachine asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.system.virtualizationservice.IVirtualMachine))) {
        return ((android.system.virtualizationservice.IVirtualMachine)iin);
      }
      return new android.system.virtualizationservice.IVirtualMachine.Stub.Proxy(obj);
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
        case TRANSACTION_getCid:
        {
          int _result = this.getCid();
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_getState:
        {
          int _result = this.getState();
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_registerCallback:
        {
          android.system.virtualizationservice.IVirtualMachineCallback _arg0;
          _arg0 = android.system.virtualizationservice.IVirtualMachineCallback.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.registerCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_start:
        {
          this.start();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_stop:
        {
          this.stop();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isMemoryBalloonEnabled:
        {
          boolean _result = this.isMemoryBalloonEnabled();
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_getActualMemoryBalloonBytes:
        {
          long _result = this.getActualMemoryBalloonBytes();
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_setMemoryBalloon:
        {
          long _arg0;
          _arg0 = data.readLong();
          data.enforceNoDataAvail();
          this.setMemoryBalloon(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_connectVsock:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          android.os.ParcelFileDescriptor _result = this.connectVsock(_arg0);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_createAccessorBinder:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          android.os.IBinder _result = this.createAccessorBinder(_arg0, _arg1);
          reply.writeNoException();
          reply.writeStrongBinder(_result);
          break;
        }
        case TRANSACTION_setHostConsoleName:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          data.enforceNoDataAvail();
          this.setHostConsoleName(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_suspend:
        {
          this.suspend();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_resume:
        {
          this.resume();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getDebugInfo:
        {
          android.system.virtualizationservice.VirtualMachineDebugInfo _result = this.getDebugInfo();
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_addMemoryToGuest:
        {
          android.os.ParcelFileDescriptor _arg0;
          _arg0 = data.readTypedObject(android.os.ParcelFileDescriptor.CREATOR);
          long _arg1;
          _arg1 = data.readLong();
          long _arg2;
          _arg2 = data.readLong();
          long _arg3;
          _arg3 = data.readLong();
          boolean _arg4;
          _arg4 = data.readBoolean();
          data.enforceNoDataAvail();
          int _result = this.addMemoryToGuest(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_removeMemoryFromGuest:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          this.removeMemoryFromGuest(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getGuestAgent:
        {
          android.system.virtualizationcommon.IGuestAgent _result = this.getGuestAgent();
          reply.writeNoException();
          reply.writeStrongInterface(_result);
          break;
        }
        case TRANSACTION_addDisplay:
        {
          android.system.virtualizationservice.DisplayConfig _arg0;
          _arg0 = data.readTypedObject(android.system.virtualizationservice.DisplayConfig.CREATOR);
          data.enforceNoDataAvail();
          this.addDisplay(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_removeDisplay:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          this.removeDisplay(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getDisplays:
        {
          android.system.virtualizationservice.VirtualMachineDisplay[] _result = this.getDisplays();
          reply.writeNoException();
          reply.writeTypedArray(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static final class Proxy implements android.system.virtualizationservice.IVirtualMachine
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
      /** Get the CID allocated to the VM. */
      @Override public int getCid() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCid, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Returns the current lifecycle state of the VM. */
      @Override public int getState() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getState, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Register a Binder object to get callbacks when the state of the VM changes, such as if it
       * dies.
       */
      @Override public void registerCallback(android.system.virtualizationservice.IVirtualMachineCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Starts running the VM. */
      @Override public void start() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_start, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /**
       * Stops this virtual machine. Stopping a virtual machine is like pulling the plug on a real
       * computer; the machine halts immediately. Software running on the virtual machine is not
       * notified with the event.
       */
      @Override public void stop() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_stop, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Access to the VM's memory balloon. */
      @Override public boolean isMemoryBalloonEnabled() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isMemoryBalloonEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public long getActualMemoryBalloonBytes() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getActualMemoryBalloonBytes, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setMemoryBalloon(long num_bytes) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeLong(num_bytes);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMemoryBalloon, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Open a vsock connection to the CID of the VM on the given port. */
      @Override public android.os.ParcelFileDescriptor connectVsock(int port) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.ParcelFileDescriptor _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(port);
          boolean _status = mRemote.transact(Stub.TRANSACTION_connectVsock, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.os.ParcelFileDescriptor.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Create an Accessor in libbinder that will open a vsock connection
       * to the CID of the VM on the given port.
       * 
       * \param instance name of the service that the accessor is responsible for.
       *        This is the same instance that we expect clients to use when trying
       *        to get the service with the ServiceManager APIs.
       * 
       * \return IBinder of the IAccessor on success, or throws a service specific exception
       *         on error. See the ERROR_* values above.
       */
      @Override public android.os.IBinder createAccessorBinder(java.lang.String instance, int port) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.IBinder _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(instance);
          _data.writeInt(port);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createAccessorBinder, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readStrongBinder();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Set the name of the peer end (ptsname) of the host console. */
      @Override public void setHostConsoleName(java.lang.String pathname) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(pathname);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setHostConsoleName, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Suspends the VM vcpus. */
      @Override public void suspend() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_suspend, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Resumes the suspended VM vcpus. */
      @Override public void resume() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_resume, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Returns debug info for this virtual machine */
      @Override public android.system.virtualizationservice.VirtualMachineDebugInfo getDebugInfo() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.system.virtualizationservice.VirtualMachineDebugInfo _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getDebugInfo, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.system.virtualizationservice.VirtualMachineDebugInfo.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Adds memory represented by the fd and offset to the guest IPA space at the given range
       * [rangeStart, rangeEnd).
       * 
       * On success returns a unique id representing the memory shared with guest. This id can be
       * used to remove the memory from the guest using the removeMemoryFromGuest API below. Returns
       * a negative value on failure.
       */
      @Override public int addMemoryToGuest(android.os.ParcelFileDescriptor fd, long offset, long rangeStart, long rangeEnd, boolean cacheable) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(fd, 0);
          _data.writeLong(offset);
          _data.writeLong(rangeStart);
          _data.writeLong(rangeEnd);
          _data.writeBoolean(cacheable);
          boolean _status = mRemote.transact(Stub.TRANSACTION_addMemoryToGuest, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Removes the memory represented by memory_id from guest IPA space.
       * NOTE: This API must be called only after guest frees the memory using the
       * ARM_SMCCC_MEM_RELIQUINSH hypercall.
       */
      @Override public void removeMemoryFromGuest(int memory_id) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(memory_id);
          boolean _status = mRemote.transact(Stub.TRANSACTION_removeMemoryFromGuest, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Returns guest agent */
      @Override public android.system.virtualizationcommon.IGuestAgent getGuestAgent() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.system.virtualizationcommon.IGuestAgent _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getGuestAgent, _data, _reply, 0);
          _reply.readException();
          _result = android.system.virtualizationcommon.IGuestAgent.Stub.asInterface(_reply.readStrongBinder());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
       * Adds a new display to the VM.
       * 
       * @param config The configuration for the new display.
       */
      @Override public void addDisplay(android.system.virtualizationservice.DisplayConfig config) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(config, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_addDisplay, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /**
       * Removes a display from the VM.
       * 
       * @param displayId The ID of the display to remove.
       */
      @Override public void removeDisplay(int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_removeDisplay, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      /** Returns the list of currently active displays. */
      @Override public android.system.virtualizationservice.VirtualMachineDisplay[] getDisplays() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.system.virtualizationservice.VirtualMachineDisplay[] _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getDisplays, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArray(android.system.virtualizationservice.VirtualMachineDisplay.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_getCid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getState = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_registerCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_start = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_stop = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_isMemoryBalloonEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_getActualMemoryBalloonBytes = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_setMemoryBalloon = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_connectVsock = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_createAccessorBinder = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_setHostConsoleName = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_suspend = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_resume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_getDebugInfo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_addMemoryToGuest = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
    static final int TRANSACTION_removeMemoryFromGuest = (android.os.IBinder.FIRST_CALL_TRANSACTION + 15);
    static final int TRANSACTION_getGuestAgent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16);
    static final int TRANSACTION_addDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 17);
    static final int TRANSACTION_removeDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 18);
    static final int TRANSACTION_getDisplays = (android.os.IBinder.FIRST_CALL_TRANSACTION + 19);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.system.virtualizationservice.IVirtualMachine";
  /**
   * Encountered an unexpected error. This is an implementation detail and the client
   * can do nothing about it.
   * This is used as a Service Specific Exception.
   */
  public static final int ERROR_UNEXPECTED = -1;
  /** Get the CID allocated to the VM. */
  public int getCid() throws android.os.RemoteException;
  /** Returns the current lifecycle state of the VM. */
  public int getState() throws android.os.RemoteException;
  /**
   * Register a Binder object to get callbacks when the state of the VM changes, such as if it
   * dies.
   */
  public void registerCallback(android.system.virtualizationservice.IVirtualMachineCallback callback) throws android.os.RemoteException;
  /** Starts running the VM. */
  public void start() throws android.os.RemoteException;
  /**
   * Stops this virtual machine. Stopping a virtual machine is like pulling the plug on a real
   * computer; the machine halts immediately. Software running on the virtual machine is not
   * notified with the event.
   */
  public void stop() throws android.os.RemoteException;
  /** Access to the VM's memory balloon. */
  public boolean isMemoryBalloonEnabled() throws android.os.RemoteException;
  public long getActualMemoryBalloonBytes() throws android.os.RemoteException;
  public void setMemoryBalloon(long num_bytes) throws android.os.RemoteException;
  /** Open a vsock connection to the CID of the VM on the given port. */
  public android.os.ParcelFileDescriptor connectVsock(int port) throws android.os.RemoteException;
  /**
   * Create an Accessor in libbinder that will open a vsock connection
   * to the CID of the VM on the given port.
   * 
   * \param instance name of the service that the accessor is responsible for.
   *        This is the same instance that we expect clients to use when trying
   *        to get the service with the ServiceManager APIs.
   * 
   * \return IBinder of the IAccessor on success, or throws a service specific exception
   *         on error. See the ERROR_* values above.
   */
  public android.os.IBinder createAccessorBinder(java.lang.String instance, int port) throws android.os.RemoteException;
  /** Set the name of the peer end (ptsname) of the host console. */
  public void setHostConsoleName(java.lang.String pathname) throws android.os.RemoteException;
  /** Suspends the VM vcpus. */
  public void suspend() throws android.os.RemoteException;
  /** Resumes the suspended VM vcpus. */
  public void resume() throws android.os.RemoteException;
  /** Returns debug info for this virtual machine */
  public android.system.virtualizationservice.VirtualMachineDebugInfo getDebugInfo() throws android.os.RemoteException;
  /**
   * Adds memory represented by the fd and offset to the guest IPA space at the given range
   * [rangeStart, rangeEnd).
   * 
   * On success returns a unique id representing the memory shared with guest. This id can be
   * used to remove the memory from the guest using the removeMemoryFromGuest API below. Returns
   * a negative value on failure.
   */
  public int addMemoryToGuest(android.os.ParcelFileDescriptor fd, long offset, long rangeStart, long rangeEnd, boolean cacheable) throws android.os.RemoteException;
  /**
   * Removes the memory represented by memory_id from guest IPA space.
   * NOTE: This API must be called only after guest frees the memory using the
   * ARM_SMCCC_MEM_RELIQUINSH hypercall.
   */
  public void removeMemoryFromGuest(int memory_id) throws android.os.RemoteException;
  /** Returns guest agent */
  public android.system.virtualizationcommon.IGuestAgent getGuestAgent() throws android.os.RemoteException;
  /**
   * Adds a new display to the VM.
   * 
   * @param config The configuration for the new display.
   */
  public void addDisplay(android.system.virtualizationservice.DisplayConfig config) throws android.os.RemoteException;
  /**
   * Removes a display from the VM.
   * 
   * @param displayId The ID of the display to remove.
   */
  public void removeDisplay(int displayId) throws android.os.RemoteException;
  /** Returns the list of currently active displays. */
  public android.system.virtualizationservice.VirtualMachineDisplay[] getDisplays() throws android.os.RemoteException;
}
