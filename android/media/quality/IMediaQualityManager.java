/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java -Weverything -Wno-missing-permission-annotation --min_sdk_version current --ninja -d out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen/android/media/quality/IMediaQualityManager.java.d -o out/soong/.intermediates/frameworks/base/media/java/android/media/quality/media_quality_aidl_interface-java-source/gen -Nframeworks/base/media/java/android/media/quality/aidl frameworks/base/media/java/android/media/quality/aidl/android/media/quality/IMediaQualityManager.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.media.quality;
/**
 * Interface for Media Quality Manager
 * @hide
 */
public interface IMediaQualityManager extends android.os.IInterface
{
  /** Default implementation for IMediaQualityManager. */
  public static class Default implements android.media.quality.IMediaQualityManager
  {
    // TODO: use UserHandle
    @Override public void createPictureProfile(android.media.quality.PictureProfile pp, int userId) throws android.os.RemoteException
    {
    }
    @Override public void updatePictureProfile(java.lang.String id, android.media.quality.PictureProfile pp, int userId) throws android.os.RemoteException
    {
    }
    @Override public void removePictureProfile(java.lang.String id, int userId) throws android.os.RemoteException
    {
    }
    @Override public android.media.quality.PictureProfile getDefaultPictureProfile() throws android.os.RemoteException
    {
      return null;
    }
    @Override public android.media.quality.SoundProfile getDefaultSoundProfile() throws android.os.RemoteException
    {
      return null;
    }
    @Override public boolean setDefaultPictureProfile(java.lang.String id, int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void setMutedColor(int color, int userId) throws android.os.RemoteException
    {
    }
    @Override public void setColorMuteEnabled(boolean enable, int userId) throws android.os.RemoteException
    {
    }
    // TODO: use Bundle for includeParams
    @Override public android.media.quality.PictureProfile getPictureProfile(int type, java.lang.String name, boolean includeParams, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.PictureProfile> getPictureProfilesByPackage(java.lang.String packageName, boolean includeParams, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.PictureProfile> getAvailablePictureProfiles(boolean includeParams, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<java.lang.String> getPictureProfilePackageNames(int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<java.lang.String> getPictureProfileAllowList(int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void setPictureProfileAllowList(java.util.List<java.lang.String> packages, int userId) throws android.os.RemoteException
    {
    }
    @Override public java.util.List<android.media.quality.PictureProfileHandle> getPictureProfileHandle(java.lang.String[] id, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.PictureProfileHandle> getPictureProfileHandles(java.lang.String[] ids, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void changeStreamStatus(java.lang.String profileId, java.lang.String newStatus, int userId) throws android.os.RemoteException
    {
    }
    @Override public long getPictureProfileHandleValue(java.lang.String id, int userId) throws android.os.RemoteException
    {
      return 0L;
    }
    @Override public long getDefaultPictureProfileHandleValue(int userId) throws android.os.RemoteException
    {
      return 0L;
    }
    @Override public void notifyPictureProfileHandleSelection(long handle, int userId) throws android.os.RemoteException
    {
    }
    @Override public long getPictureProfileForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
    {
      return 0L;
    }
    @Override public android.media.quality.PictureProfileHandle getCurrentPictureProfileHandleForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public android.media.quality.PictureProfile getCurrentPictureProfileForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.PictureProfile> getAllPictureProfilesForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void createSoundProfile(android.media.quality.SoundProfile pp, int userId) throws android.os.RemoteException
    {
    }
    @Override public void updateSoundProfile(java.lang.String id, android.media.quality.SoundProfile pp, int userId) throws android.os.RemoteException
    {
    }
    @Override public void removeSoundProfile(java.lang.String id, int userId) throws android.os.RemoteException
    {
    }
    @Override public boolean setDefaultSoundProfile(java.lang.String id, int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public android.media.quality.SoundProfile getSoundProfile(int type, java.lang.String name, boolean includeParams, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.SoundProfile> getSoundProfilesByPackage(java.lang.String packageName, boolean includeParams, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.SoundProfile> getAvailableSoundProfiles(boolean includeParams, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<java.lang.String> getSoundProfilePackageNames(int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<java.lang.String> getSoundProfileAllowList(int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void setSoundProfileAllowList(java.util.List<java.lang.String> packages, int userId) throws android.os.RemoteException
    {
    }
    @Override public java.util.List<android.media.quality.SoundProfileHandle> getSoundProfileHandle(java.lang.String[] id, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<android.media.quality.SoundProfileHandle> getSoundProfileHandles(java.lang.String[] ids, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void registerPictureProfileCallback(android.media.quality.IPictureProfileCallback cb) throws android.os.RemoteException
    {
    }
    @Override public void registerSoundProfileCallback(android.media.quality.ISoundProfileCallback cb) throws android.os.RemoteException
    {
    }
    @Override public void registerAmbientBacklightCallback(android.media.quality.IAmbientBacklightCallback cb) throws android.os.RemoteException
    {
    }
    @Override public void registerActiveProcessingPictureListener(android.media.quality.IActiveProcessingPictureListener l) throws android.os.RemoteException
    {
    }
    @Override public java.util.List<android.media.quality.ParameterCapability> getParameterCapabilities(java.util.List<java.lang.String> names, int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public boolean isSupported(int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void setAutoPictureQualityEnabled(boolean enabled, int userId) throws android.os.RemoteException
    {
    }
    @Override public boolean isAutoPictureQualityEnabled(int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void setSuperResolutionEnabled(boolean enabled, int userId) throws android.os.RemoteException
    {
    }
    @Override public boolean isSuperResolutionEnabled(int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void setAutoSoundQualityEnabled(boolean enabled, int userId) throws android.os.RemoteException
    {
    }
    @Override public boolean isAutoSoundQualityEnabled(int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean usesDisplayTechnology(int panelTechnology, int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void setAmbientBacklightSettings(android.media.quality.AmbientBacklightSettings settings, int userId) throws android.os.RemoteException
    {
    }
    @Override public void setAmbientBacklightEnabled(boolean enabled, int userId) throws android.os.RemoteException
    {
    }
    @Override public boolean isAmbientBacklightEnabled(int userId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public android.media.quality.EqualizerCapabilities getEqualizerCapabilities(int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public android.media.quality.EqualizerSettings getEqualizerSettings(int userId) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void setEqualizerSettings(android.media.quality.EqualizerSettings Settings, int userId) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.media.quality.IMediaQualityManager
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.media.quality.IMediaQualityManager interface,
     * generating a proxy if needed.
     */
    public static android.media.quality.IMediaQualityManager asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.media.quality.IMediaQualityManager))) {
        return ((android.media.quality.IMediaQualityManager)iin);
      }
      return new android.media.quality.IMediaQualityManager.Stub.Proxy(obj);
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
        case TRANSACTION_createPictureProfile:
        {
          android.media.quality.PictureProfile _arg0;
          _arg0 = data.readTypedObject(android.media.quality.PictureProfile.CREATOR);
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.createPictureProfile(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_updatePictureProfile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.media.quality.PictureProfile _arg1;
          _arg1 = data.readTypedObject(android.media.quality.PictureProfile.CREATOR);
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          this.updatePictureProfile(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_removePictureProfile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.removePictureProfile(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getDefaultPictureProfile:
        {
          android.media.quality.PictureProfile _result = this.getDefaultPictureProfile();
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getDefaultSoundProfile:
        {
          android.media.quality.SoundProfile _result = this.getDefaultSoundProfile();
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_setDefaultPictureProfile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.setDefaultPictureProfile(_arg0, _arg1);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_setMutedColor:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setMutedColor(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_setColorMuteEnabled:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setColorMuteEnabled(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getPictureProfile:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          boolean _arg2;
          _arg2 = data.readBoolean();
          int _arg3;
          _arg3 = data.readInt();
          data.enforceNoDataAvail();
          android.media.quality.PictureProfile _result = this.getPictureProfile(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getPictureProfilesByPackage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _arg1;
          _arg1 = data.readBoolean();
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.PictureProfile> _result = this.getPictureProfilesByPackage(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getAvailablePictureProfiles:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.PictureProfile> _result = this.getAvailablePictureProfiles(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getPictureProfilePackageNames:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<java.lang.String> _result = this.getPictureProfilePackageNames(_arg0);
          reply.writeNoException();
          reply.writeStringList(_result);
          break;
        }
        case TRANSACTION_getPictureProfileAllowList:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<java.lang.String> _result = this.getPictureProfileAllowList(_arg0);
          reply.writeNoException();
          reply.writeStringList(_result);
          break;
        }
        case TRANSACTION_setPictureProfileAllowList:
        {
          java.util.List<java.lang.String> _arg0;
          _arg0 = data.createStringArrayList();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setPictureProfileAllowList(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getPictureProfileHandle:
        {
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.PictureProfileHandle> _result = this.getPictureProfileHandle(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getPictureProfileHandles:
        {
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.PictureProfileHandle> _result = this.getPictureProfileHandles(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_changeStreamStatus:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          this.changeStreamStatus(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getPictureProfileHandleValue:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          long _result = this.getPictureProfileHandleValue(_arg0, _arg1);
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_getDefaultPictureProfileHandleValue:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          long _result = this.getDefaultPictureProfileHandleValue(_arg0);
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_notifyPictureProfileHandleSelection:
        {
          long _arg0;
          _arg0 = data.readLong();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.notifyPictureProfileHandleSelection(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getPictureProfileForTvInput:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          long _result = this.getPictureProfileForTvInput(_arg0, _arg1);
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_getCurrentPictureProfileHandleForTvInput:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          android.media.quality.PictureProfileHandle _result = this.getCurrentPictureProfileHandleForTvInput(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getCurrentPictureProfileForTvInput:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          android.media.quality.PictureProfile _result = this.getCurrentPictureProfileForTvInput(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getAllPictureProfilesForTvInput:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.PictureProfile> _result = this.getAllPictureProfilesForTvInput(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_createSoundProfile:
        {
          android.media.quality.SoundProfile _arg0;
          _arg0 = data.readTypedObject(android.media.quality.SoundProfile.CREATOR);
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.createSoundProfile(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_updateSoundProfile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.media.quality.SoundProfile _arg1;
          _arg1 = data.readTypedObject(android.media.quality.SoundProfile.CREATOR);
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          this.updateSoundProfile(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_removeSoundProfile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.removeSoundProfile(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_setDefaultSoundProfile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.setDefaultSoundProfile(_arg0, _arg1);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_getSoundProfile:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          boolean _arg2;
          _arg2 = data.readBoolean();
          int _arg3;
          _arg3 = data.readInt();
          data.enforceNoDataAvail();
          android.media.quality.SoundProfile _result = this.getSoundProfile(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getSoundProfilesByPackage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _arg1;
          _arg1 = data.readBoolean();
          int _arg2;
          _arg2 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.SoundProfile> _result = this.getSoundProfilesByPackage(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getAvailableSoundProfiles:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.SoundProfile> _result = this.getAvailableSoundProfiles(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getSoundProfilePackageNames:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<java.lang.String> _result = this.getSoundProfilePackageNames(_arg0);
          reply.writeNoException();
          reply.writeStringList(_result);
          break;
        }
        case TRANSACTION_getSoundProfileAllowList:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<java.lang.String> _result = this.getSoundProfileAllowList(_arg0);
          reply.writeNoException();
          reply.writeStringList(_result);
          break;
        }
        case TRANSACTION_setSoundProfileAllowList:
        {
          java.util.List<java.lang.String> _arg0;
          _arg0 = data.createStringArrayList();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setSoundProfileAllowList(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getSoundProfileHandle:
        {
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.SoundProfileHandle> _result = this.getSoundProfileHandle(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getSoundProfileHandles:
        {
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.SoundProfileHandle> _result = this.getSoundProfileHandles(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_registerPictureProfileCallback:
        {
          android.media.quality.IPictureProfileCallback _arg0;
          _arg0 = android.media.quality.IPictureProfileCallback.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.registerPictureProfileCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_registerSoundProfileCallback:
        {
          android.media.quality.ISoundProfileCallback _arg0;
          _arg0 = android.media.quality.ISoundProfileCallback.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.registerSoundProfileCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_registerAmbientBacklightCallback:
        {
          android.media.quality.IAmbientBacklightCallback _arg0;
          _arg0 = android.media.quality.IAmbientBacklightCallback.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.registerAmbientBacklightCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_registerActiveProcessingPictureListener:
        {
          android.media.quality.IActiveProcessingPictureListener _arg0;
          _arg0 = android.media.quality.IActiveProcessingPictureListener.Stub.asInterface(data.readStrongBinder());
          data.enforceNoDataAvail();
          this.registerActiveProcessingPictureListener(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getParameterCapabilities:
        {
          java.util.List<java.lang.String> _arg0;
          _arg0 = data.createStringArrayList();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          java.util.List<android.media.quality.ParameterCapability> _result = this.getParameterCapabilities(_arg0, _arg1);
          reply.writeNoException();
          reply.writeTypedList(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_isSupported:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.isSupported(_arg0);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_setAutoPictureQualityEnabled:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setAutoPictureQualityEnabled(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isAutoPictureQualityEnabled:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.isAutoPictureQualityEnabled(_arg0);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_setSuperResolutionEnabled:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setSuperResolutionEnabled(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isSuperResolutionEnabled:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.isSuperResolutionEnabled(_arg0);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_setAutoSoundQualityEnabled:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setAutoSoundQualityEnabled(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isAutoSoundQualityEnabled:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.isAutoSoundQualityEnabled(_arg0);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_usesDisplayTechnology:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.usesDisplayTechnology(_arg0, _arg1);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_setAmbientBacklightSettings:
        {
          android.media.quality.AmbientBacklightSettings _arg0;
          _arg0 = data.readTypedObject(android.media.quality.AmbientBacklightSettings.CREATOR);
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setAmbientBacklightSettings(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_setAmbientBacklightEnabled:
        {
          boolean _arg0;
          _arg0 = data.readBoolean();
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setAmbientBacklightEnabled(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isAmbientBacklightEnabled:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          boolean _result = this.isAmbientBacklightEnabled(_arg0);
          reply.writeNoException();
          reply.writeBoolean(_result);
          break;
        }
        case TRANSACTION_getEqualizerCapabilities:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          android.media.quality.EqualizerCapabilities _result = this.getEqualizerCapabilities(_arg0);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getEqualizerSettings:
        {
          int _arg0;
          _arg0 = data.readInt();
          data.enforceNoDataAvail();
          android.media.quality.EqualizerSettings _result = this.getEqualizerSettings(_arg0);
          reply.writeNoException();
          reply.writeTypedObject(_result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_setEqualizerSettings:
        {
          android.media.quality.EqualizerSettings _arg0;
          _arg0 = data.readTypedObject(android.media.quality.EqualizerSettings.CREATOR);
          int _arg1;
          _arg1 = data.readInt();
          data.enforceNoDataAvail();
          this.setEqualizerSettings(_arg0, _arg1);
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
    private static final class Proxy implements android.media.quality.IMediaQualityManager
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
      // TODO: use UserHandle
      @Override public void createPictureProfile(android.media.quality.PictureProfile pp, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(pp, 0);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createPictureProfile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void updatePictureProfile(java.lang.String id, android.media.quality.PictureProfile pp, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeTypedObject(pp, 0);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_updatePictureProfile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void removePictureProfile(java.lang.String id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_removePictureProfile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public android.media.quality.PictureProfile getDefaultPictureProfile() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.PictureProfile _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getDefaultPictureProfile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.PictureProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public android.media.quality.SoundProfile getDefaultSoundProfile() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.SoundProfile _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getDefaultSoundProfile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.SoundProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean setDefaultPictureProfile(java.lang.String id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDefaultPictureProfile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setMutedColor(int color, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(color);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setMutedColor, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void setColorMuteEnabled(boolean enable, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(enable);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setColorMuteEnabled, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // TODO: use Bundle for includeParams
      @Override public android.media.quality.PictureProfile getPictureProfile(int type, java.lang.String name, boolean includeParams, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.PictureProfile _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(type);
          _data.writeString(name);
          _data.writeBoolean(includeParams);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.PictureProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.PictureProfile> getPictureProfilesByPackage(java.lang.String packageName, boolean includeParams, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.PictureProfile> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeBoolean(includeParams);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfilesByPackage, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.PictureProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.PictureProfile> getAvailablePictureProfiles(boolean includeParams, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.PictureProfile> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(includeParams);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAvailablePictureProfiles, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.PictureProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getPictureProfilePackageNames(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfilePackageNames, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getPictureProfileAllowList(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfileAllowList, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setPictureProfileAllowList(java.util.List<java.lang.String> packages, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringList(packages);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setPictureProfileAllowList, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public java.util.List<android.media.quality.PictureProfileHandle> getPictureProfileHandle(java.lang.String[] id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.PictureProfileHandle> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfileHandle, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.PictureProfileHandle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.PictureProfileHandle> getPictureProfileHandles(java.lang.String[] ids, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.PictureProfileHandle> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(ids);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfileHandles, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.PictureProfileHandle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void changeStreamStatus(java.lang.String profileId, java.lang.String newStatus, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(profileId);
          _data.writeString(newStatus);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_changeStreamStatus, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public long getPictureProfileHandleValue(java.lang.String id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfileHandleValue, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public long getDefaultPictureProfileHandleValue(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getDefaultPictureProfileHandleValue, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void notifyPictureProfileHandleSelection(long handle, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeLong(handle);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_notifyPictureProfileHandleSelection, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public long getPictureProfileForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(inputId);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPictureProfileForTvInput, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public android.media.quality.PictureProfileHandle getCurrentPictureProfileHandleForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.PictureProfileHandle _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(inputId);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCurrentPictureProfileHandleForTvInput, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.PictureProfileHandle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public android.media.quality.PictureProfile getCurrentPictureProfileForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.PictureProfile _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(inputId);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCurrentPictureProfileForTvInput, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.PictureProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.PictureProfile> getAllPictureProfilesForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.PictureProfile> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(inputId);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAllPictureProfilesForTvInput, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.PictureProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void createSoundProfile(android.media.quality.SoundProfile pp, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(pp, 0);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createSoundProfile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void updateSoundProfile(java.lang.String id, android.media.quality.SoundProfile pp, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeTypedObject(pp, 0);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_updateSoundProfile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void removeSoundProfile(java.lang.String id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_removeSoundProfile, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean setDefaultSoundProfile(java.lang.String id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setDefaultSoundProfile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public android.media.quality.SoundProfile getSoundProfile(int type, java.lang.String name, boolean includeParams, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.SoundProfile _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(type);
          _data.writeString(name);
          _data.writeBoolean(includeParams);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSoundProfile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.SoundProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.SoundProfile> getSoundProfilesByPackage(java.lang.String packageName, boolean includeParams, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.SoundProfile> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeBoolean(includeParams);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSoundProfilesByPackage, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.SoundProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.SoundProfile> getAvailableSoundProfiles(boolean includeParams, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.SoundProfile> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(includeParams);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAvailableSoundProfiles, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.SoundProfile.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getSoundProfilePackageNames(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSoundProfilePackageNames, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getSoundProfileAllowList(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSoundProfileAllowList, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setSoundProfileAllowList(java.util.List<java.lang.String> packages, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringList(packages);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setSoundProfileAllowList, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public java.util.List<android.media.quality.SoundProfileHandle> getSoundProfileHandle(java.lang.String[] id, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.SoundProfileHandle> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(id);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSoundProfileHandle, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.SoundProfileHandle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<android.media.quality.SoundProfileHandle> getSoundProfileHandles(java.lang.String[] ids, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.SoundProfileHandle> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(ids);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSoundProfileHandles, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.SoundProfileHandle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void registerPictureProfileCallback(android.media.quality.IPictureProfileCallback cb) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(cb);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerPictureProfileCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void registerSoundProfileCallback(android.media.quality.ISoundProfileCallback cb) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(cb);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerSoundProfileCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void registerAmbientBacklightCallback(android.media.quality.IAmbientBacklightCallback cb) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(cb);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerAmbientBacklightCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void registerActiveProcessingPictureListener(android.media.quality.IActiveProcessingPictureListener l) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(l);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerActiveProcessingPictureListener, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public java.util.List<android.media.quality.ParameterCapability> getParameterCapabilities(java.util.List<java.lang.String> names, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<android.media.quality.ParameterCapability> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringList(names);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getParameterCapabilities, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createTypedArrayList(android.media.quality.ParameterCapability.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean isSupported(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isSupported, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setAutoPictureQualityEnabled(boolean enabled, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(enabled);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAutoPictureQualityEnabled, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean isAutoPictureQualityEnabled(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isAutoPictureQualityEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setSuperResolutionEnabled(boolean enabled, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(enabled);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setSuperResolutionEnabled, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean isSuperResolutionEnabled(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isSuperResolutionEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setAutoSoundQualityEnabled(boolean enabled, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(enabled);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAutoSoundQualityEnabled, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean isAutoSoundQualityEnabled(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isAutoSoundQualityEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean usesDisplayTechnology(int panelTechnology, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(panelTechnology);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_usesDisplayTechnology, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setAmbientBacklightSettings(android.media.quality.AmbientBacklightSettings settings, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(settings, 0);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAmbientBacklightSettings, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void setAmbientBacklightEnabled(boolean enabled, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeBoolean(enabled);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAmbientBacklightEnabled, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean isAmbientBacklightEnabled(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isAmbientBacklightEnabled, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readBoolean();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public android.media.quality.EqualizerCapabilities getEqualizerCapabilities(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.EqualizerCapabilities _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getEqualizerCapabilities, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.EqualizerCapabilities.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public android.media.quality.EqualizerSettings getEqualizerSettings(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.media.quality.EqualizerSettings _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getEqualizerSettings, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readTypedObject(android.media.quality.EqualizerSettings.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setEqualizerSettings(android.media.quality.EqualizerSettings Settings, int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedObject(Settings, 0);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setEqualizerSettings, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_createPictureProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_updatePictureProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_removePictureProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_getDefaultPictureProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_getDefaultSoundProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_setDefaultPictureProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_setMutedColor = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_setColorMuteEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_getPictureProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_getPictureProfilesByPackage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_getAvailablePictureProfiles = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_getPictureProfilePackageNames = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_getPictureProfileAllowList = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_setPictureProfileAllowList = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_getPictureProfileHandle = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
    static final int TRANSACTION_getPictureProfileHandles = (android.os.IBinder.FIRST_CALL_TRANSACTION + 15);
    static final int TRANSACTION_changeStreamStatus = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16);
    static final int TRANSACTION_getPictureProfileHandleValue = (android.os.IBinder.FIRST_CALL_TRANSACTION + 17);
    static final int TRANSACTION_getDefaultPictureProfileHandleValue = (android.os.IBinder.FIRST_CALL_TRANSACTION + 18);
    static final int TRANSACTION_notifyPictureProfileHandleSelection = (android.os.IBinder.FIRST_CALL_TRANSACTION + 19);
    static final int TRANSACTION_getPictureProfileForTvInput = (android.os.IBinder.FIRST_CALL_TRANSACTION + 20);
    static final int TRANSACTION_getCurrentPictureProfileHandleForTvInput = (android.os.IBinder.FIRST_CALL_TRANSACTION + 21);
    static final int TRANSACTION_getCurrentPictureProfileForTvInput = (android.os.IBinder.FIRST_CALL_TRANSACTION + 22);
    static final int TRANSACTION_getAllPictureProfilesForTvInput = (android.os.IBinder.FIRST_CALL_TRANSACTION + 23);
    static final int TRANSACTION_createSoundProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 24);
    static final int TRANSACTION_updateSoundProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 25);
    static final int TRANSACTION_removeSoundProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 26);
    static final int TRANSACTION_setDefaultSoundProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 27);
    static final int TRANSACTION_getSoundProfile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 28);
    static final int TRANSACTION_getSoundProfilesByPackage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 29);
    static final int TRANSACTION_getAvailableSoundProfiles = (android.os.IBinder.FIRST_CALL_TRANSACTION + 30);
    static final int TRANSACTION_getSoundProfilePackageNames = (android.os.IBinder.FIRST_CALL_TRANSACTION + 31);
    static final int TRANSACTION_getSoundProfileAllowList = (android.os.IBinder.FIRST_CALL_TRANSACTION + 32);
    static final int TRANSACTION_setSoundProfileAllowList = (android.os.IBinder.FIRST_CALL_TRANSACTION + 33);
    static final int TRANSACTION_getSoundProfileHandle = (android.os.IBinder.FIRST_CALL_TRANSACTION + 34);
    static final int TRANSACTION_getSoundProfileHandles = (android.os.IBinder.FIRST_CALL_TRANSACTION + 35);
    static final int TRANSACTION_registerPictureProfileCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 36);
    static final int TRANSACTION_registerSoundProfileCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 37);
    static final int TRANSACTION_registerAmbientBacklightCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 38);
    static final int TRANSACTION_registerActiveProcessingPictureListener = (android.os.IBinder.FIRST_CALL_TRANSACTION + 39);
    static final int TRANSACTION_getParameterCapabilities = (android.os.IBinder.FIRST_CALL_TRANSACTION + 40);
    static final int TRANSACTION_isSupported = (android.os.IBinder.FIRST_CALL_TRANSACTION + 41);
    static final int TRANSACTION_setAutoPictureQualityEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 42);
    static final int TRANSACTION_isAutoPictureQualityEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 43);
    static final int TRANSACTION_setSuperResolutionEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 44);
    static final int TRANSACTION_isSuperResolutionEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 45);
    static final int TRANSACTION_setAutoSoundQualityEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 46);
    static final int TRANSACTION_isAutoSoundQualityEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 47);
    static final int TRANSACTION_usesDisplayTechnology = (android.os.IBinder.FIRST_CALL_TRANSACTION + 48);
    static final int TRANSACTION_setAmbientBacklightSettings = (android.os.IBinder.FIRST_CALL_TRANSACTION + 49);
    static final int TRANSACTION_setAmbientBacklightEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 50);
    static final int TRANSACTION_isAmbientBacklightEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 51);
    static final int TRANSACTION_getEqualizerCapabilities = (android.os.IBinder.FIRST_CALL_TRANSACTION + 52);
    static final int TRANSACTION_getEqualizerSettings = (android.os.IBinder.FIRST_CALL_TRANSACTION + 53);
    static final int TRANSACTION_setEqualizerSettings = (android.os.IBinder.FIRST_CALL_TRANSACTION + 54);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "android.media.quality.IMediaQualityManager";
  // TODO: use UserHandle
  public void createPictureProfile(android.media.quality.PictureProfile pp, int userId) throws android.os.RemoteException;
  public void updatePictureProfile(java.lang.String id, android.media.quality.PictureProfile pp, int userId) throws android.os.RemoteException;
  public void removePictureProfile(java.lang.String id, int userId) throws android.os.RemoteException;
  public android.media.quality.PictureProfile getDefaultPictureProfile() throws android.os.RemoteException;
  public android.media.quality.SoundProfile getDefaultSoundProfile() throws android.os.RemoteException;
  public boolean setDefaultPictureProfile(java.lang.String id, int userId) throws android.os.RemoteException;
  public void setMutedColor(int color, int userId) throws android.os.RemoteException;
  public void setColorMuteEnabled(boolean enable, int userId) throws android.os.RemoteException;
  // TODO: use Bundle for includeParams
  public android.media.quality.PictureProfile getPictureProfile(int type, java.lang.String name, boolean includeParams, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.PictureProfile> getPictureProfilesByPackage(java.lang.String packageName, boolean includeParams, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.PictureProfile> getAvailablePictureProfiles(boolean includeParams, int userId) throws android.os.RemoteException;
  public java.util.List<java.lang.String> getPictureProfilePackageNames(int userId) throws android.os.RemoteException;
  public java.util.List<java.lang.String> getPictureProfileAllowList(int userId) throws android.os.RemoteException;
  public void setPictureProfileAllowList(java.util.List<java.lang.String> packages, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.PictureProfileHandle> getPictureProfileHandle(java.lang.String[] id, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.PictureProfileHandle> getPictureProfileHandles(java.lang.String[] ids, int userId) throws android.os.RemoteException;
  public void changeStreamStatus(java.lang.String profileId, java.lang.String newStatus, int userId) throws android.os.RemoteException;
  public long getPictureProfileHandleValue(java.lang.String id, int userId) throws android.os.RemoteException;
  public long getDefaultPictureProfileHandleValue(int userId) throws android.os.RemoteException;
  public void notifyPictureProfileHandleSelection(long handle, int userId) throws android.os.RemoteException;
  public long getPictureProfileForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException;
  public android.media.quality.PictureProfileHandle getCurrentPictureProfileHandleForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException;
  public android.media.quality.PictureProfile getCurrentPictureProfileForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.PictureProfile> getAllPictureProfilesForTvInput(java.lang.String inputId, int userId) throws android.os.RemoteException;
  public void createSoundProfile(android.media.quality.SoundProfile pp, int userId) throws android.os.RemoteException;
  public void updateSoundProfile(java.lang.String id, android.media.quality.SoundProfile pp, int userId) throws android.os.RemoteException;
  public void removeSoundProfile(java.lang.String id, int userId) throws android.os.RemoteException;
  public boolean setDefaultSoundProfile(java.lang.String id, int userId) throws android.os.RemoteException;
  public android.media.quality.SoundProfile getSoundProfile(int type, java.lang.String name, boolean includeParams, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.SoundProfile> getSoundProfilesByPackage(java.lang.String packageName, boolean includeParams, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.SoundProfile> getAvailableSoundProfiles(boolean includeParams, int userId) throws android.os.RemoteException;
  public java.util.List<java.lang.String> getSoundProfilePackageNames(int userId) throws android.os.RemoteException;
  public java.util.List<java.lang.String> getSoundProfileAllowList(int userId) throws android.os.RemoteException;
  public void setSoundProfileAllowList(java.util.List<java.lang.String> packages, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.SoundProfileHandle> getSoundProfileHandle(java.lang.String[] id, int userId) throws android.os.RemoteException;
  public java.util.List<android.media.quality.SoundProfileHandle> getSoundProfileHandles(java.lang.String[] ids, int userId) throws android.os.RemoteException;
  public void registerPictureProfileCallback(android.media.quality.IPictureProfileCallback cb) throws android.os.RemoteException;
  public void registerSoundProfileCallback(android.media.quality.ISoundProfileCallback cb) throws android.os.RemoteException;
  public void registerAmbientBacklightCallback(android.media.quality.IAmbientBacklightCallback cb) throws android.os.RemoteException;
  public void registerActiveProcessingPictureListener(android.media.quality.IActiveProcessingPictureListener l) throws android.os.RemoteException;
  public java.util.List<android.media.quality.ParameterCapability> getParameterCapabilities(java.util.List<java.lang.String> names, int userId) throws android.os.RemoteException;
  public boolean isSupported(int userId) throws android.os.RemoteException;
  public void setAutoPictureQualityEnabled(boolean enabled, int userId) throws android.os.RemoteException;
  public boolean isAutoPictureQualityEnabled(int userId) throws android.os.RemoteException;
  public void setSuperResolutionEnabled(boolean enabled, int userId) throws android.os.RemoteException;
  public boolean isSuperResolutionEnabled(int userId) throws android.os.RemoteException;
  public void setAutoSoundQualityEnabled(boolean enabled, int userId) throws android.os.RemoteException;
  public boolean isAutoSoundQualityEnabled(int userId) throws android.os.RemoteException;
  public boolean usesDisplayTechnology(int panelTechnology, int userId) throws android.os.RemoteException;
  public void setAmbientBacklightSettings(android.media.quality.AmbientBacklightSettings settings, int userId) throws android.os.RemoteException;
  public void setAmbientBacklightEnabled(boolean enabled, int userId) throws android.os.RemoteException;
  public boolean isAmbientBacklightEnabled(int userId) throws android.os.RemoteException;
  public android.media.quality.EqualizerCapabilities getEqualizerCapabilities(int userId) throws android.os.RemoteException;
  public android.media.quality.EqualizerSettings getEqualizerSettings(int userId) throws android.os.RemoteException;
  public void setEqualizerSettings(android.media.quality.EqualizerSettings Settings, int userId) throws android.os.RemoteException;
}
