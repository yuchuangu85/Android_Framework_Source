/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: out/host/linux-x86/bin/aidl --lang=java --structured --version 5 --hash 4a4662588b9e38b5e93c37e46353efac231f7a98 --stability vintf --min_sdk_version current -pout/soong/.intermediates/hardware/interfaces/common/aidl/android.hardware.common_interface/2/preprocessed.aidl --ninja -d out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen/android/hardware/contexthub/SharedDataRegion.java.d -o out/soong/.intermediates/hardware/interfaces/contexthub/aidl/android.hardware.contexthub-V5-java-source/gen -Nhardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5 hardware/interfaces/contexthub/aidl/aidl_api/android.hardware.contexthub/5/android/hardware/contexthub/SharedDataRegion.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package android.hardware.contexthub;
public class SharedDataRegion implements android.os.Parcelable
{
  public int id = 0;
  public android.os.ParcelFileDescriptor sharedMemory;
  public long sizeBytes = 0L;
  public java.lang.String[] permissions;
  @Override
   public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
  public static final android.os.Parcelable.Creator<SharedDataRegion> CREATOR = new android.os.Parcelable.Creator<SharedDataRegion>() {
    @Override
    public SharedDataRegion createFromParcel(android.os.Parcel _aidl_source) {
      SharedDataRegion _aidl_out = new SharedDataRegion();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public SharedDataRegion[] newArray(int _aidl_size) {
      return new SharedDataRegion[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeInt(id);
    _aidl_parcel.writeTypedObject(sharedMemory, _aidl_flag);
    _aidl_parcel.writeLong(sizeBytes);
    _aidl_parcel.writeStringArray(permissions);
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
      id = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sharedMemory = _aidl_parcel.readTypedObject(android.os.ParcelFileDescriptor.CREATOR);
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      sizeBytes = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      permissions = _aidl_parcel.createStringArray();
    } finally {
      if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
        throw new android.os.BadParcelableException("Overflow in the size of parcelable");
      }
      _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
    }
  }
  public static final int OFFSET_INVALID = -1;
  @Override
  public int describeContents() {
    int _mask = 0;
    _mask |= describeContents(sharedMemory);
    return _mask;
  }
  private int describeContents(Object _v) {
    if (_v == null) return 0;
    if (_v instanceof android.os.Parcelable) {
      return ((android.os.Parcelable) _v).describeContents();
    }
    return 0;
  }
  public static class DataFlowMetadata implements android.os.Parcelable
  {
    public android.hardware.contexthub.SharedDataRegion.Version version;
    public int sourceMetadataOffsetBytes = 0;
    public android.hardware.contexthub.SharedDataRegion.EndpointIdFixedSize sourceId;
    public int blockListEpoch = 0;
    public int blockCapacityBytes = 0;
    public android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig elementConfig;
    public byte localNotify = 0;
    public byte[] reserved;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<DataFlowMetadata> CREATOR = new android.os.Parcelable.Creator<DataFlowMetadata>() {
      @Override
      public DataFlowMetadata createFromParcel(android.os.Parcel _aidl_source) {
        DataFlowMetadata _aidl_out = new DataFlowMetadata();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public DataFlowMetadata[] newArray(int _aidl_size) {
        return new DataFlowMetadata[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeTypedObject(version, _aidl_flag);
      _aidl_parcel.writeInt(sourceMetadataOffsetBytes);
      _aidl_parcel.writeTypedObject(sourceId, _aidl_flag);
      _aidl_parcel.writeInt(blockListEpoch);
      _aidl_parcel.writeInt(blockCapacityBytes);
      _aidl_parcel.writeTypedObject(elementConfig, _aidl_flag);
      _aidl_parcel.writeByte(localNotify);
      _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 11);
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
        version = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.Version.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        sourceMetadataOffsetBytes = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        sourceId = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.EndpointIdFixedSize.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        blockListEpoch = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        blockCapacityBytes = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        elementConfig = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        localNotify = _aidl_parcel.readByte();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        reserved = _aidl_parcel.createFixedArray(byte[].class, 11);
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
      _mask |= describeContents(version);
      _mask |= describeContents(sourceId);
      _mask |= describeContents(elementConfig);
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
  public static final class DataFlowElementConfig implements android.os.Parcelable {
    // tags for union fields
    public final static int fixedSize = 0;  // android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize fixedSize;
    public final static int variableSize = 1;  // android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize variableSize;

    private int _tag;
    private Object _value;

    public DataFlowElementConfig() {
      android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize _value = null;
      this._tag = fixedSize;
      this._value = _value;
    }

    private DataFlowElementConfig(android.os.Parcel _aidl_parcel) {
      readFromParcel(_aidl_parcel);
    }

    private DataFlowElementConfig(int _tag, Object _value) {
      this._tag = _tag;
      this._value = _value;
    }

    public int getTag() {
      return _tag;
    }

    // android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize fixedSize;

    public static DataFlowElementConfig fixedSize(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize _value) {
      return new DataFlowElementConfig(fixedSize, _value);
    }

    public android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize getFixedSize() {
      _assertTag(fixedSize);
      return (android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize) _value;
    }

    public void setFixedSize(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize _value) {
      _set(fixedSize, _value);
    }

    // android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize variableSize;

    public static DataFlowElementConfig variableSize(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize _value) {
      return new DataFlowElementConfig(variableSize, _value);
    }

    public android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize getVariableSize() {
      _assertTag(variableSize);
      return (android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize) _value;
    }

    public void setVariableSize(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize _value) {
      _set(variableSize, _value);
    }

    @Override
    public final int getStability() {
      return android.os.Parcelable.PARCELABLE_STABILITY_VINTF;
    }

    public static final android.os.Parcelable.Creator<DataFlowElementConfig> CREATOR = new android.os.Parcelable.Creator<DataFlowElementConfig>() {
      @Override
      public DataFlowElementConfig createFromParcel(android.os.Parcel _aidl_source) {
        return new DataFlowElementConfig(_aidl_source);
      }
      @Override
      public DataFlowElementConfig[] newArray(int _aidl_size) {
        return new DataFlowElementConfig[_aidl_size];
      }
    };

    @Override
    public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag) {
      _aidl_parcel.writeInt(_tag);
      switch (_tag) {
      case fixedSize:
        _aidl_parcel.writeTypedObject(getFixedSize(), _aidl_flag);
        break;
      case variableSize:
        _aidl_parcel.writeTypedObject(getVariableSize(), _aidl_flag);
        break;
      }
    }

    public void readFromParcel(android.os.Parcel _aidl_parcel) {
      int _aidl_tag;
      _aidl_tag = _aidl_parcel.readInt();
      switch (_aidl_tag) {
      case fixedSize: {
        android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize _aidl_value;
        _aidl_value = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.FixedSize.CREATOR);
        _set(_aidl_tag, _aidl_value);
        return; }
      case variableSize: {
        android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize _aidl_value;
        _aidl_value = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.DataFlowElementConfig.VariableSize.CREATOR);
        _set(_aidl_tag, _aidl_value);
        return; }
      }
      throw new IllegalArgumentException("union: unknown tag: " + _aidl_tag);
    }

    @Override
    public int describeContents() {
      int _mask = 0;
      switch (getTag()) {
      case fixedSize:
        _mask |= describeContents(getFixedSize());
        break;
      case variableSize:
        _mask |= describeContents(getVariableSize());
        break;
      }
      return _mask;
    }
    private int describeContents(Object _v) {
      if (_v == null) return 0;
      if (_v instanceof android.os.Parcelable) {
        return ((android.os.Parcelable) _v).describeContents();
      }
      return 0;
    }

    private void _assertTag(int tag) {
      if (getTag() != tag) {
        throw new IllegalStateException("bad access: " + _tagString(tag) + ", " + _tagString(getTag()) + " is available.");
      }
    }

    private String _tagString(int _tag) {
      switch (_tag) {
      case fixedSize: return "fixedSize";
      case variableSize: return "variableSize";
      }
      throw new IllegalStateException("unknown field: " + _tag);
    }

    private void _set(int _tag, Object _value) {
      this._tag = _tag;
      this._value = _value;
    }
    public static class FixedSize implements android.os.Parcelable
    {
      public int elementSizeBytes = 0;
      public char elementAlignmentBytes = '\0';
      public byte[] reserved;
      @Override
       public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
      public static final android.os.Parcelable.Creator<FixedSize> CREATOR = new android.os.Parcelable.Creator<FixedSize>() {
        @Override
        public FixedSize createFromParcel(android.os.Parcel _aidl_source) {
          FixedSize _aidl_out = new FixedSize();
          _aidl_out.readFromParcel(_aidl_source);
          return _aidl_out;
        }
        @Override
        public FixedSize[] newArray(int _aidl_size) {
          return new FixedSize[_aidl_size];
        }
      };
      @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
      {
        int _aidl_start_pos = _aidl_parcel.dataPosition();
        _aidl_parcel.writeInt(0);
        _aidl_parcel.writeInt(elementSizeBytes);
        _aidl_parcel.writeInt(((int)elementAlignmentBytes));
        _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 2);
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
          elementSizeBytes = _aidl_parcel.readInt();
          if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
          elementAlignmentBytes = (char)_aidl_parcel.readInt();
          if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
          reserved = _aidl_parcel.createFixedArray(byte[].class, 2);
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
        return _mask;
      }
    }
    public static class VariableSize implements android.os.Parcelable
    {
      public char elementAlignmentBytes = '\0';
      public byte[] reserved;
      @Override
       public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
      public static final android.os.Parcelable.Creator<VariableSize> CREATOR = new android.os.Parcelable.Creator<VariableSize>() {
        @Override
        public VariableSize createFromParcel(android.os.Parcel _aidl_source) {
          VariableSize _aidl_out = new VariableSize();
          _aidl_out.readFromParcel(_aidl_source);
          return _aidl_out;
        }
        @Override
        public VariableSize[] newArray(int _aidl_size) {
          return new VariableSize[_aidl_size];
        }
      };
      @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
      {
        int _aidl_start_pos = _aidl_parcel.dataPosition();
        _aidl_parcel.writeInt(0);
        _aidl_parcel.writeInt(((int)elementAlignmentBytes));
        _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 6);
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
          elementAlignmentBytes = (char)_aidl_parcel.readInt();
          if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
          reserved = _aidl_parcel.createFixedArray(byte[].class, 6);
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
        return _mask;
      }
    }
    public static @interface Tag {
      public static final byte fixedSize = 0;
      public static final byte variableSize = 1;
    }
  }
  public static class DataFlowSourceMetadata implements android.os.Parcelable
  {
    public int writeIndex = 0;
    public int indexCorrection = 0;
    public int tailBlockOffsetBytes = 0;
    public byte[] reserved;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<DataFlowSourceMetadata> CREATOR = new android.os.Parcelable.Creator<DataFlowSourceMetadata>() {
      @Override
      public DataFlowSourceMetadata createFromParcel(android.os.Parcel _aidl_source) {
        DataFlowSourceMetadata _aidl_out = new DataFlowSourceMetadata();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public DataFlowSourceMetadata[] newArray(int _aidl_size) {
        return new DataFlowSourceMetadata[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeInt(writeIndex);
      _aidl_parcel.writeInt(indexCorrection);
      _aidl_parcel.writeInt(tailBlockOffsetBytes);
      _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 12);
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
        writeIndex = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        indexCorrection = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        tailBlockOffsetBytes = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        reserved = _aidl_parcel.createFixedArray(byte[].class, 12);
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
      return _mask;
    }
  }
  public static class DataFlowSinkMetadata implements android.os.Parcelable
  {
    public android.hardware.contexthub.SharedDataRegion.Version version;
    public int readIndex = 0;
    public int indexCorrection = 0;
    public int sourceFlags = 0;
    public android.hardware.contexthub.SharedDataRegion.EndpointIdFixedSize id;
    public int sinkFlags = 0;
    public int initialHeadBlockOffsetBytes = 0;
    public int initialBlockListEpoch = 0;
    public boolean isOverwritable = false;
    public byte[] reserved;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<DataFlowSinkMetadata> CREATOR = new android.os.Parcelable.Creator<DataFlowSinkMetadata>() {
      @Override
      public DataFlowSinkMetadata createFromParcel(android.os.Parcel _aidl_source) {
        DataFlowSinkMetadata _aidl_out = new DataFlowSinkMetadata();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public DataFlowSinkMetadata[] newArray(int _aidl_size) {
        return new DataFlowSinkMetadata[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeTypedObject(version, _aidl_flag);
      _aidl_parcel.writeInt(readIndex);
      _aidl_parcel.writeInt(indexCorrection);
      _aidl_parcel.writeInt(sourceFlags);
      _aidl_parcel.writeTypedObject(id, _aidl_flag);
      _aidl_parcel.writeInt(sinkFlags);
      _aidl_parcel.writeInt(initialHeadBlockOffsetBytes);
      _aidl_parcel.writeInt(initialBlockListEpoch);
      _aidl_parcel.writeBoolean(isOverwritable);
      _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 11);
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
        version = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.Version.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        readIndex = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        indexCorrection = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        sourceFlags = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        id = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.EndpointIdFixedSize.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        sinkFlags = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        initialHeadBlockOffsetBytes = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        initialBlockListEpoch = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        isOverwritable = _aidl_parcel.readBoolean();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        reserved = _aidl_parcel.createFixedArray(byte[].class, 11);
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
      _mask |= describeContents(version);
      _mask |= describeContents(id);
      return _mask;
    }
    private int describeContents(Object _v) {
      if (_v == null) return 0;
      if (_v instanceof android.os.Parcelable) {
        return ((android.os.Parcelable) _v).describeContents();
      }
      return 0;
    }
    public static @interface SourceFlags {
      public static final int NONE = 0;
      public static final int PENDING_INIT = 1;
      public static final int BLOCKING = 2;
      public static final int OVERWRITE = 4;
      public static final int FINISHED = 8;
      public static final int DISCONNECTED = 16;
    }
    public static @interface SinkFlags {
      public static final int CLEARED = 0;
      public static final int FINISHED = 1;
    }
  }
  public static class DataFlowBlockHeader implements android.os.Parcelable
  {
    public android.hardware.contexthub.SharedDataRegion.DataFlowSourceMetadata sourceMetadata;
    public int nextBlockOffsetBytes = 0;
    public int baseIndex = 0;
    public int skipIndex = 0;
    public byte[] reserved;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<DataFlowBlockHeader> CREATOR = new android.os.Parcelable.Creator<DataFlowBlockHeader>() {
      @Override
      public DataFlowBlockHeader createFromParcel(android.os.Parcel _aidl_source) {
        DataFlowBlockHeader _aidl_out = new DataFlowBlockHeader();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public DataFlowBlockHeader[] newArray(int _aidl_size) {
        return new DataFlowBlockHeader[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeTypedObject(sourceMetadata, _aidl_flag);
      _aidl_parcel.writeInt(nextBlockOffsetBytes);
      _aidl_parcel.writeInt(baseIndex);
      _aidl_parcel.writeInt(skipIndex);
      _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 12);
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
        sourceMetadata = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.DataFlowSourceMetadata.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        nextBlockOffsetBytes = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        baseIndex = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        skipIndex = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        reserved = _aidl_parcel.createFixedArray(byte[].class, 12);
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
      _mask |= describeContents(sourceMetadata);
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
  public static class DataFlowVariableSizeBlockHeader implements android.os.Parcelable
  {
    public android.hardware.contexthub.SharedDataRegion.DataFlowBlockHeader blockHeader;
    public int firstElementIndex = 0;
    public byte[] reserved;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<DataFlowVariableSizeBlockHeader> CREATOR = new android.os.Parcelable.Creator<DataFlowVariableSizeBlockHeader>() {
      @Override
      public DataFlowVariableSizeBlockHeader createFromParcel(android.os.Parcel _aidl_source) {
        DataFlowVariableSizeBlockHeader _aidl_out = new DataFlowVariableSizeBlockHeader();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public DataFlowVariableSizeBlockHeader[] newArray(int _aidl_size) {
        return new DataFlowVariableSizeBlockHeader[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeTypedObject(blockHeader, _aidl_flag);
      _aidl_parcel.writeInt(firstElementIndex);
      _aidl_parcel.writeFixedArray(reserved, _aidl_flag, 12);
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
        blockHeader = _aidl_parcel.readTypedObject(android.hardware.contexthub.SharedDataRegion.DataFlowBlockHeader.CREATOR);
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        firstElementIndex = _aidl_parcel.readInt();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        reserved = _aidl_parcel.createFixedArray(byte[].class, 12);
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
      _mask |= describeContents(blockHeader);
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
  public static class DataFlowVariableSizeElementHeader implements android.os.Parcelable
  {
    public int sizeBytes = 0;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<DataFlowVariableSizeElementHeader> CREATOR = new android.os.Parcelable.Creator<DataFlowVariableSizeElementHeader>() {
      @Override
      public DataFlowVariableSizeElementHeader createFromParcel(android.os.Parcel _aidl_source) {
        DataFlowVariableSizeElementHeader _aidl_out = new DataFlowVariableSizeElementHeader();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public DataFlowVariableSizeElementHeader[] newArray(int _aidl_size) {
        return new DataFlowVariableSizeElementHeader[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeInt(sizeBytes);
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
        sizeBytes = _aidl_parcel.readInt();
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
      return _mask;
    }
  }
  public static class EndpointIdFixedSize implements android.os.Parcelable
  {
    public long hubId = 0L;
    public long endpointId = 0L;
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<EndpointIdFixedSize> CREATOR = new android.os.Parcelable.Creator<EndpointIdFixedSize>() {
      @Override
      public EndpointIdFixedSize createFromParcel(android.os.Parcel _aidl_source) {
        EndpointIdFixedSize _aidl_out = new EndpointIdFixedSize();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public EndpointIdFixedSize[] newArray(int _aidl_size) {
        return new EndpointIdFixedSize[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeLong(hubId);
      _aidl_parcel.writeLong(endpointId);
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
        hubId = _aidl_parcel.readLong();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        endpointId = _aidl_parcel.readLong();
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
      return _mask;
    }
  }
  public static class Version implements android.os.Parcelable
  {
    public byte major = 0;
    public byte minor = 0;
    public char patch = '\0';
    @Override
     public final int getStability() { return android.os.Parcelable.PARCELABLE_STABILITY_VINTF; }
    public static final android.os.Parcelable.Creator<Version> CREATOR = new android.os.Parcelable.Creator<Version>() {
      @Override
      public Version createFromParcel(android.os.Parcel _aidl_source) {
        Version _aidl_out = new Version();
        _aidl_out.readFromParcel(_aidl_source);
        return _aidl_out;
      }
      @Override
      public Version[] newArray(int _aidl_size) {
        return new Version[_aidl_size];
      }
    };
    @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
    {
      int _aidl_start_pos = _aidl_parcel.dataPosition();
      _aidl_parcel.writeInt(0);
      _aidl_parcel.writeByte(major);
      _aidl_parcel.writeByte(minor);
      _aidl_parcel.writeInt(((int)patch));
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
        major = _aidl_parcel.readByte();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        minor = _aidl_parcel.readByte();
        if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
        patch = (char)_aidl_parcel.readInt();
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
      return _mask;
    }
  }
}
