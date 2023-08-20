/*
 * Copyright (C) 2008-2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.renderscript;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Log;
import android.view.Surface;

/**
 * <p> This class provides the primary method through which data is passed to
 * and from RenderScript kernels.  An Allocation provides the backing store for
 * a given {@link androidx.renderscript.Type}.  </p>
 *
 * <p>An Allocation also contains a set of usage flags that denote how the
 * Allocation could be used. For example, an Allocation may have usage flags
 * specifying that it can be used from a script as well as input to a {@link
 * androidx.renderscript.Sampler}. A developer must synchronize
 * across these different usages using
 * {@link androidx.renderscript.Allocation#syncAll} in
 * order to ensure that different users of the Allocation have a consistent view
 * of memory. For example, in the case where an Allocation is used as the output
 * of one kernel and as Sampler input in a later kernel, a developer must call
 * {@link #syncAll syncAll(Allocation.USAGE_SCRIPT)} prior to launching the
 * second kernel to ensure correctness.
 *
 * <p>An Allocation can be populated with the {@link #copyFrom} routines. For
 * more complex Element types, the {@link #copyFromUnchecked} methods can be
 * used to copy from byte arrays or similar constructs.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating an application that uses
 * RenderScript, read the
 * <a href="{@docRoot}guide/topics/renderscript/index.html">RenderScript</a>
 * developer guide.</p>
 * </div>
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 **/
@Deprecated
public class Allocation extends BaseObj {
    Type mType;
    Bitmap mBitmap;
    int mUsage;
    int mSize;
    Allocation mAdaptedAllocation;
    ByteBuffer mByteBuffer = null;
    long mByteBufferStride = 0;

    boolean mConstrainedLOD;
    boolean mConstrainedFace;
    boolean mConstrainedY;
    boolean mConstrainedZ;
    boolean mReadAllowed = true;
    boolean mWriteAllowed = true;
    boolean mAutoPadding = false;
    int mSelectedY;
    int mSelectedZ;
    int mSelectedLOD;
    Type.CubemapFace mSelectedFace = Type.CubemapFace.POSITIVE_X;

    int mCurrentDimX;
    int mCurrentDimY;
    int mCurrentDimZ;
    int mCurrentCount;

    private Element.DataType validateObjectIsPrimitiveArray(Object d, boolean checkType) {
        final Class c = d.getClass();
        if (!c.isArray()) {
            throw new RSIllegalArgumentException("Object passed is not an array of primitives.");
        }
        final Class cmp = c.getComponentType();
        if (!cmp.isPrimitive()) {
            throw new RSIllegalArgumentException("Object passed is not an Array of primitives.");
        }

        if (cmp == Long.TYPE) {
            if (checkType) {
                validateIsInt64();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_64;
        }

        if (cmp == Integer.TYPE) {
            if (checkType) {
                validateIsInt32();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_32;
        }

        if (cmp == Short.TYPE) {
            if (checkType) {
                validateIsInt16();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_16;
        }

        if (cmp == Byte.TYPE) {
            if (checkType) {
                validateIsInt8();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_8;
        }

        if (cmp == Float.TYPE) {
            if (checkType) {
                validateIsFloat32();
            }
            return Element.DataType.FLOAT_32;
        }

        if (cmp == Double.TYPE) {
            if (checkType) {
                validateIsFloat64();
            }
            return Element.DataType.FLOAT_64;
        }
        return null;
    }

    /*
     * Hold reference to the shared allocation in compat context
     * for Incremental Support Lib.
     */
    long mIncCompatAllocation;
    boolean mIncAllocDestroyed;
    /**
     * The usage of the Allocation.  These signal to RenderScript where to place
     * the Allocation in memory.
     *
     */

    /**
     * The Allocation will be bound to and accessed by scripts.
     */
    public static final int USAGE_SCRIPT = 0x0001;

    /**
     * The Allocation will be used as a texture source by one or more graphics
     * programs.
     *
     */
    public static final int USAGE_GRAPHICS_TEXTURE = 0x0002;

    /**
     * The Allocation will be used as a {@link android.graphics.SurfaceTexture}
     * consumer.  This usage will cause the Allocation to be created as
     * read-only.
     *
     */
    public static final int USAGE_IO_INPUT = 0x0020;

    /**
     * The Allocation will be used as a {@link android.graphics.SurfaceTexture}
     * producer.  The dimensions and format of the {@link
     * android.graphics.SurfaceTexture} will be forced to those of the
     * Allocation.
     *
     */
    public static final int USAGE_IO_OUTPUT = 0x0040;

    /**
     * The Allocation's backing store will be inherited from another object
     * (usually a {@link android.graphics.Bitmap}); copying to or from the
     * original source Bitmap will cause a synchronization rather than a full
     * copy.  {@link #syncAll} may also be used to synchronize the Allocation
     * and the source Bitmap.
     *
     * <p>This is set by default for allocations created with {@link
     * #createFromBitmap} in API version 18 and higher.</p>
     *
     */
    public static final int USAGE_SHARED = 0x0080;

    /**
     * Controls mipmap behavior when using the bitmap creation and update
     * functions.
     */
    public enum MipmapControl {
        /**
         * No mipmaps will be generated and the type generated from the incoming
         * bitmap will not contain additional LODs.
         */
        MIPMAP_NONE(0),

        /**
         * A full mipmap chain will be created in script memory.  The Type of
         * the Allocation will contain a full mipmap chain.  On upload, the full
         * chain will be transferred.
         */
        MIPMAP_FULL(1),

        /**
         * The Type of the Allocation will be the same as MIPMAP_NONE.  It will
         * not contain mipmaps.  On upload, the allocation data will contain a
         * full mipmap chain generated from the top level in script memory.
         */
        MIPMAP_ON_SYNC_TO_TEXTURE(2);

        int mID;
        MipmapControl(int id) {
            mID = id;
        }
    }

    /**
     * Getter & Setter for the placeholder allocation for Inc Support Lib.
     *
     */
    public long getIncAllocID() {
        return mIncCompatAllocation;
    }
    public void setIncAllocID(long id) {
        mIncCompatAllocation = id;
    }

    private long getIDSafe() {
        if (mAdaptedAllocation != null) {
            return mAdaptedAllocation.getID(mRS);
        }
        return getID(mRS);
    }


   /**
     * Get the {@link androidx.renderscript.Element} of the {@link
     * androidx.renderscript.Type} of the Allocation.
     *
     * @return Element
     *
     */
    public Element getElement() {
        return mType.getElement();
    }

    /**
     * Get the usage flags of the Allocation.
     *
     * @return usage this Allocation's set of the USAGE_* flags OR'd together
     *
     */
    public int getUsage() {
        return mUsage;
    }

    /**
     * Specifies the mapping between the Allocation's cells and an array's elements
     * when data is copied from the Allocation to the array, or vice-versa.
     *
     * Only applies to an Allocation whose Element is a vector of length 3 (such as
     * {@link Element#U8_3} or {@link Element#RGB_888}). Enabling this feature may make
     * copying data from the Allocation to an array or vice-versa less efficient.
     *
     * <p> Vec3 Element cells are stored in an Allocation as Vec4 Element cells with
     * the same {@link androidx.renderscript.Element.DataType}, with the fourth vector
     * component treated as padding. When this feature is enabled, only the data components,
     * i.e. the first 3 vector components of each cell, will be mapped between the array
     * and the Allocation. When disabled, explicit mapping of the padding components
     * is required, as described in the following example.
     *
     * <p> For example, when copying an integer array to an Allocation of two {@link
     * Element#I32_3} cells using {@link #copyFrom(int[])}:
     * <p> When disabled:
     *     The array must have at least 8 integers, with the first 4 integers copied
     *     to the first cell of the Allocation, and the next 4 integers copied to
     *     the second cell. The 4th and 8th integers are mapped as the padding components.
     *
     * <p> When enabled:
     *     The array just needs to have at least 6 integers, with the first 3 integers
     *     copied to the the first cell as data components, and the next 3 copied to
     *     the second cell. There is no mapping for the padding components.
     *
     * <p> Similarly, when copying a byte array to an Allocation of two {@link
     * Element#I32_3} cells, using {@link #copyFromUnchecked(int[])}:
     * <p> When disabled:
     *     The array must have at least 32 bytes, with the first 16 bytes copied
     *     to the first cell of the Allocation, and the next 16 bytes copied to
     *     the second cell. The 13th-16th and 29th-32nd bytes are mapped as padding
     *     components.
     *
     * <p> When enabled:
     *     The array just needs to have at least 24 bytes, with the first 12 bytes copied
     *     to the first cell of the Allocation, and the next 12 bytes copied to
     *     the second cell. There is no mapping for the padding components.
     *
     * <p> Similar to copying data to an Allocation from an array, when copying data from an
     * Allocation to an array, the padding components for Vec3 Element cells will not be
     * copied/mapped to the array if AutoPadding is enabled.
     *
     * <p> Default: Disabled.
     *
     * @param useAutoPadding True: enable AutoPadding; False: disable AutoPadding
     *
     */
    public void setAutoPadding(boolean useAutoPadding) {
        mAutoPadding = useAutoPadding;
    }

    /**
     * Get the size of the Allocation in bytes.
     *
     * @return size of the Allocation in bytes.
     *
     */
    public int getBytesSize() {
        if (mType.mDimYuv != 0) {
            return (int)Math.ceil(mType.getCount() * mType.getElement().getBytesSize() * 1.5);
        }
        return mType.getCount() * mType.getElement().getBytesSize();
    }

    private void updateCacheInfo(Type t) {
        mCurrentDimX = t.getX();
        mCurrentDimY = t.getY();
        mCurrentDimZ = t.getZ();
        mCurrentCount = mCurrentDimX;
        if (mCurrentDimY > 1) {
            mCurrentCount *= mCurrentDimY;
        }
        if (mCurrentDimZ > 1) {
            mCurrentCount *= mCurrentDimZ;
        }
    }

    private void setBitmap(Bitmap b) {
        mBitmap = b;
    }

    Allocation(long id, RenderScript rs, Type t, int usage) {
        super(id, rs);
        if ((usage & ~(USAGE_SCRIPT |
                       USAGE_GRAPHICS_TEXTURE |
                       USAGE_IO_INPUT |
                       USAGE_IO_OUTPUT |
                       USAGE_SHARED)) != 0) {
            throw new RSIllegalArgumentException("Unknown usage specified.");
        }

        if ((usage & USAGE_IO_INPUT) != 0) {
            mWriteAllowed = false;

            if ((usage & ~(USAGE_IO_INPUT |
                           USAGE_GRAPHICS_TEXTURE |
                           USAGE_SCRIPT)) != 0) {
                throw new RSIllegalArgumentException("Invalid usage combination.");
            }
        }

        mType = t;
        mUsage = usage;
        mIncCompatAllocation = 0;
        mIncAllocDestroyed = false;

        if (t != null) {
            // TODO: A3D doesn't have Type info during creation, so we can't
            // calculate the size ahead of time. We can possibly add a method
            // to update the size in the future if it seems reasonable.
            mSize = mType.getCount() * mType.getElement().getBytesSize();
            updateCacheInfo(t);
        }
        if (RenderScript.sUseGCHooks == true) {
            try {
                RenderScript.registerNativeAllocation.invoke(RenderScript.sRuntime, mSize);
            } catch (Exception e) {
                Log.e(RenderScript.LOG_TAG, "Couldn't invoke registerNativeAllocation:" + e);
                throw new RSRuntimeException("Couldn't invoke registerNativeAllocation:" + e);
            }
        }
    }

    protected void finalize() throws Throwable {
        if (RenderScript.sUseGCHooks == true) {
            RenderScript.registerNativeFree.invoke(RenderScript.sRuntime, mSize);
        }
        super.finalize();
    }

    private void validateIsInt64() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_64) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_64)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "64 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsInt32() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_32) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_32)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "32 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsInt16() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_16) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_16)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "16 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsInt8() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_8) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_8)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "8 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsFloat32() {
        if (mType.mElement.mType == Element.DataType.FLOAT_32) {
            return;
        }
        throw new RSIllegalArgumentException(
            "32 bit float source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsFloat64() {
        if (mType.mElement.mType == Element.DataType.FLOAT_64) {
            return;
        }
        throw new RSIllegalArgumentException(
            "64 bit float source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsObject() {
        if ((mType.mElement.mType == Element.DataType.RS_ELEMENT) ||
            (mType.mElement.mType == Element.DataType.RS_TYPE) ||
            (mType.mElement.mType == Element.DataType.RS_ALLOCATION) ||
            (mType.mElement.mType == Element.DataType.RS_SAMPLER) ||
            (mType.mElement.mType == Element.DataType.RS_SCRIPT)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "Object source does not match allocation type " + mType.mElement.mType);
    }

    /**
     * Get the {@link androidx.renderscript.Type} of the Allocation.
     *
     * @return Type
     *
     */
    public Type getType() {
        return mType;
    }

    /**
     * Propagate changes from one usage of the Allocation to the
     * other usages of the Allocation.
     *
     */
    public void syncAll(int srcLocation) {
        switch (srcLocation) {
        case USAGE_SCRIPT:
        case USAGE_GRAPHICS_TEXTURE:
            break;
        default:
            throw new RSIllegalArgumentException("Source must be exactly one usage type.");
        }
        mRS.validate();
        mRS.nAllocationSyncAll(getIDSafe(), srcLocation);
    }

    /**
     * Send a buffer to the output stream.  The contents of the Allocation will
     * be undefined after this operation. This operation is only valid if {@link
     * #USAGE_IO_OUTPUT} is set on the Allocation.
     *
     *
     */
    public void ioSend() {
        if ((mUsage & USAGE_IO_OUTPUT) == 0) {
            throw new RSIllegalArgumentException(
                "Can only send buffer if IO_OUTPUT usage specified.");
        }
        mRS.validate();
        mRS.nAllocationIoSend(getID(mRS));
    }

    /**
     * Delete once code is updated.
     */
    public void ioSendOutput() {
        ioSend();
    }
    /**
     * Gets or creates a ByteBuffer that contains the raw data of the current Allocation.
     * <p> If the Allocation is created with USAGE_IO_INPUT, the returned ByteBuffer
     * would contain the up-to-date data as READ ONLY.
     * For a 2D or 3D Allocation, the raw data maybe padded so that each row of
     * the Allocation has certain alignment. The size of each row including padding,
     * called stride, can be queried using the {@link #getStride()} method.
     *
     * Note: Operating on the ByteBuffer of a destroyed Allocation will triger errors.
     *       The ByteBuffer will be Read-Only for devices before Lollopop (API 21).
     *
     * @return ByteBuffer The ByteBuffer associated with raw data pointer of the Allocation.
     */
    public ByteBuffer getByteBuffer() {
        int xBytesSize = mType.getX() * mType.getElement().getBytesSize();
        // When running on devices before L, we need to construct the ByteBuffer
        // and explicitly copy the data from the allocation to it.
        if (mRS.getDispatchAPILevel() < 21) {
            byte[] data = null;
            if (mType.getZ() > 0) {
                // TODO: add support for 3D allocations.
                return null;
            } else if (mType.getY() > 0) {
                // 2D Allocation
                data = new byte[xBytesSize * mType.getY()];
                copy2DRangeToUnchecked(0, 0, mType.getX(), mType.getY(), data,
                                       Element.DataType.SIGNED_8, xBytesSize * mType.getY());
            } else {
                // 1D Allocation
                data = new byte[xBytesSize];
                copy1DRangeToUnchecked(0, mType.getX(), data);
            }
            ByteBuffer bBuffer = ByteBuffer.wrap(data).asReadOnlyBuffer();
            mByteBufferStride = xBytesSize;
            return bBuffer;
        }
        // Create a new ByteBuffer if it is not initialized or using IO_INPUT.
        if (mByteBuffer == null || (mUsage & USAGE_IO_INPUT) != 0) {
            mByteBuffer = mRS.nAllocationGetByteBuffer(getID(mRS), xBytesSize, mType.getY(), mType.getZ());
        }
        return mByteBuffer;
    }

    /**
     * Gets the stride of the Allocation.
     * For a 2D or 3D Allocation, the raw data maybe padded so that each row of
     * the Allocation has certain alignment. The size of each row including such
     * padding is called stride.
     *
     * @return the stride. For 1D Allocation, the stride will be the number of
     *         bytes of this Allocation. For 2D and 3D Allocations, the stride
     *         will be the stride in X dimension measuring in bytes.
     */
    public long getStride() {
        if (mByteBufferStride ==0) {
            if (mRS.getDispatchAPILevel() > 21) {
                mByteBufferStride = mRS.nAllocationGetStride(getID(mRS));
            } else {
                mByteBufferStride = mType.getX() * mType.getElement().getBytesSize();
            }
        }
        return mByteBufferStride;
    }

    /**
     * Receive the latest input into the Allocation. This operation
     * is only valid if {@link #USAGE_IO_INPUT} is set on the Allocation.
     *
     */
    public void ioReceive() {
        if ((mUsage & USAGE_IO_INPUT) == 0) {
            throw new RSIllegalArgumentException(
                "Can only receive if IO_INPUT usage specified.");
        }
        mRS.validate();
        mRS.nAllocationIoReceive(getID(mRS));
    }

    /**
     * Copy an array of RS objects to the Allocation.
     *
     * @param d Source array.
     */
    public void copyFrom(BaseObj[] d) {
        mRS.validate();
        validateIsObject();
        if (d.length != mCurrentCount) {
            throw new RSIllegalArgumentException("Array size mismatch, allocation sizeX = " +
                                                 mCurrentCount + ", array length = " + d.length);
        }

        if (RenderScript.sPointerSize == 8) {
            long i[] = new long[d.length * 4];
            for (int ct=0; ct < d.length; ct++) {
                i[ct * 4] = d[ct].getID(mRS);
            }
            copy1DRangeFromUnchecked(0, mCurrentCount, i);
        } else {
            int i[] = new int[d.length];
            for (int ct=0; ct < d.length; ct++) {
                i[ct] = (int)d[ct].getID(mRS);
            }
            copy1DRangeFromUnchecked(0, mCurrentCount, i);
        }
    }

    private void validateBitmapFormat(Bitmap b) {
        Bitmap.Config bc = b.getConfig();
        if (bc == null) {
            throw new RSIllegalArgumentException("Bitmap has an unsupported format for this operation");
        }
        switch (bc) {
        case ALPHA_8:
            if (mType.getElement().mKind != Element.DataKind.PIXEL_A) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;
        case ARGB_8888:
            if ((mType.getElement().mKind != Element.DataKind.PIXEL_RGBA) ||
                (mType.getElement().getBytesSize() != 4)) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;
        case RGB_565:
            if ((mType.getElement().mKind != Element.DataKind.PIXEL_RGB) ||
                (mType.getElement().getBytesSize() != 2)) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;
        case ARGB_4444:
            if ((mType.getElement().mKind != Element.DataKind.PIXEL_RGBA) ||
                (mType.getElement().getBytesSize() != 2)) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;

        }
    }

    private void validateBitmapSize(Bitmap b) {
        if((mCurrentDimX != b.getWidth()) || (mCurrentDimY != b.getHeight())) {
            throw new RSIllegalArgumentException("Cannot update allocation from bitmap, sizes mismatch");
        }
    }

    private void copyFromUnchecked(Object array, Element.DataType dt, int arrayLen) {
        mRS.validate();
        if (mCurrentDimZ > 0) {
            copy3DRangeFromUnchecked(0, 0, 0, mCurrentDimX, mCurrentDimY, mCurrentDimZ, array, dt, arrayLen);
        } else if (mCurrentDimY > 0) {
            copy2DRangeFromUnchecked(0, 0, mCurrentDimX, mCurrentDimY, array, dt, arrayLen);
        } else {
            copy1DRangeFromUnchecked(0, mCurrentCount, array, dt, arrayLen);
        }
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param array The source array
     */
    public void copyFromUnchecked(Object array) {
        copyFromUnchecked(array, validateObjectIsPrimitiveArray(array, false),
                          java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFromUnchecked(int[] d) {
        copyFromUnchecked(d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFromUnchecked(short[] d) {
        copyFromUnchecked(d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFromUnchecked(byte[] d) {
        copyFromUnchecked(d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFromUnchecked(float[] d) {
        copyFromUnchecked(d, Element.DataType.FLOAT_32, d.length);
    }


    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} does not match the array's
     * primitive type.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param array The source array
     */
    public void copyFrom(Object array) {
        copyFromUnchecked(array, validateObjectIsPrimitiveArray(array, true),
                          java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not a 32 bit integer nor a vector of 32 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFrom(int[] d) {
        validateIsInt32();
        copyFromUnchecked(d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not a 16 bit integer nor a vector of 16 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFrom(short[] d) {
        validateIsInt16();
        copyFromUnchecked(d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not an 8 bit integer nor a vector of 8 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFrom(byte[] d) {
        validateIsInt8();
        copyFromUnchecked(d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 32 bit float nor a vector of
     * 32 bit floats {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d the source array
     */
    public void copyFrom(float[] d) {
        validateIsFloat32();
        copyFromUnchecked(d, Element.DataType.FLOAT_32, d.length);
    }

    /**
     * Copy into an Allocation from a {@link android.graphics.Bitmap}.  The
     * height, width, and format of the bitmap must match the existing
     * allocation.
     *
     * <p>If the {@link android.graphics.Bitmap} is the same as the {@link
     * android.graphics.Bitmap} used to create the Allocation with {@link
     * #createFromBitmap} and {@link #USAGE_SHARED} is set on the Allocation,
     * this will synchronize the Allocation with the latest data from the {@link
     * android.graphics.Bitmap}, potentially avoiding the actual copy.</p>
     *
     * @param b the source bitmap
     */
    public void copyFrom(Bitmap b) {
        mRS.validate();
        if (b.getConfig() == null) {
            Bitmap newBitmap = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(newBitmap);
            c.drawBitmap(b, 0, 0, null);
            copyFrom(newBitmap);
            return;
        }
        validateBitmapSize(b);
        validateBitmapFormat(b);
        mRS.nAllocationCopyFromBitmap(getID(mRS), b);
    }

    /**
     * Copy an Allocation from an Allocation.  The types of both allocations
     * must be identical.
     *
     * @param a the source allocation
     */
    public void copyFrom(Allocation a) {
        mRS.validate();
        if (!mType.equals(a.getType())) {
            throw new RSIllegalArgumentException("Types of allocations must match.");
        }
        copy2DRangeFrom(0, 0, mCurrentDimX, mCurrentDimY, a, 0, 0);
    }


    /**
     * This is only intended to be used by auto-generated code reflected from
     * the RenderScript script files and should not be used by developers.
     *
     * @param xoff
     * @param fp
     */
    public void setFromFieldPacker(int xoff, FieldPacker fp) {
        mRS.validate();
        int eSize = mType.mElement.getBytesSize();
        final byte[] data = fp.getData();
        int data_length = fp.getPos();

        int count = data_length / eSize;
        if ((eSize * count) != data_length) {
            throw new RSIllegalArgumentException("Field packer length " + data_length +
                                               " not divisible by element size " + eSize + ".");
        }
        copy1DRangeFromUnchecked(xoff, count, data);
    }

    /**
     * This is only intended to be used by auto-generated code reflected from
     * the RenderScript script files.
     *
     * @param xoff
     * @param component_number
     * @param fp
     */
    public void setFromFieldPacker(int xoff, int component_number, FieldPacker fp) {
        mRS.validate();
        if (component_number >= mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }

        final byte[] data = fp.getData();
        int data_length = fp.getPos();
        int eSize = mType.mElement.mElements[component_number].getBytesSize();
        eSize *= mType.mElement.mArraySizes[component_number];

        if (data_length != eSize) {
            throw new RSIllegalArgumentException("Field packer sizelength " + data_length +
                                               " does not match component size " + eSize + ".");
        }

        mRS.nAllocationElementData1D(getIDSafe(), xoff, mSelectedLOD,
                                     component_number, data, data_length);
    }

    /**
     * @hide
     * This is only intended to be used by auto-generated code reflected from
     * the RenderScript script files.
     *
     * @param xoff
     * @param yoff
     * @param zoff
     * @param component_number
     * @param fp
     */
    /*
    public void setFromFieldPacker(int xoff, int yoff, int zoff, int component_number, FieldPacker fp) {
        mRS.validate();
        if (component_number >= mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset x must be >= 0.");
        }
        if(yoff < 0) {
            throw new RSIllegalArgumentException("Offset y must be >= 0.");
        }
        if(zoff < 0) {
            throw new RSIllegalArgumentException("Offset z must be >= 0.");
        }

        final byte[] data = fp.getData();
        int data_length = fp.getPos();
        int eSize = mType.mElement.mElements[component_number].getBytesSize();
        eSize *= mType.mElement.mArraySizes[component_number];

        if (data_length != eSize) {
            throw new RSIllegalArgumentException("Field packer sizelength " + data_length +
                                               " does not match component size " + eSize + ".");
        }

        mRS.nAllocationElementData(getIDSafe(), xoff, yoff, zoff, mSelectedLOD,
                                   component_number, data, data_length);
    }
    */

    private void data1DChecks(int off, int count, int len, int dataSize, boolean usePadding) {
        mRS.validate();
        if(off < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }
        if(count < 1) {
            throw new RSIllegalArgumentException("Count must be >= 1.");
        }
        if((off + count) > mCurrentCount) {
            throw new RSIllegalArgumentException("Overflow, Available count " + mCurrentCount +
                                               ", got " + count + " at offset " + off + ".");
        }
        if(usePadding) {
            if(len < dataSize / 4 * 3) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
        } else {
            if(len < dataSize) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
        }
    }

    /**
     * Generate a mipmap chain. This is only valid if the Type of the Allocation
     * includes mipmaps.
     *
     * <p>This function will generate a complete set of mipmaps from the top
     * level LOD and place them into the script memory space.</p>
     *
     * <p>If the Allocation is also using other memory spaces, a call to {@link
     * #syncAll syncAll(Allocation.USAGE_SCRIPT)} is required.</p>
     */
    public void generateMipmaps() {
        mRS.nAllocationGenerateMipmaps(getID(mRS));
    }

    private void copy1DRangeFromUnchecked(int off, int count, Object array,
                                          Element.DataType dt, int arrayLen) {
        final int dataSize = mType.mElement.getBytesSize() * count;
        // AutoPadding for Vec3 Element
        boolean usePadding = false;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            usePadding = true;
        }
        data1DChecks(off, count, arrayLen * dt.mSize, dataSize, usePadding);
        mRS.nAllocationData1D(getIDSafe(), off, mSelectedLOD, count, array, dataSize, dt,
                              mType.mElement.mType.mSize, usePadding);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param array The source array
     */
    public void copy1DRangeFromUnchecked(int off, int count, Object array) {
        copy1DRangeFromUnchecked(off, count, array,
                                 validateObjectIsPrimitiveArray(array, false),
                                 java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFromUnchecked(int off, int count, int[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFromUnchecked(int off, int count, short[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFromUnchecked(int off, int count, byte[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFromUnchecked(int off, int count, float[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.FLOAT_32, d.length);
    }


    /**
     * Copy an array into a 1D region of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} does not match the component type
     * of the array passed in.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param array The source array.
     */
    public void copy1DRangeFrom(int off, int count, Object array) {
        copy1DRangeFromUnchecked(off, count, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not an 32 bit integer nor a vector of 32 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFrom(int off, int count, int[] d) {
        validateIsInt32();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not an 16 bit integer nor a vector of 16 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFrom(int off, int count, short[] d) {
        validateIsInt16();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not an 8 bit integer nor a vector of 8 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeFrom(int off, int count, byte[] d) {
        validateIsInt8();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy an array into a 1D region of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 32 bit float nor a vector of
     * 32 bit floats {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array.
     */
    public void copy1DRangeFrom(int off, int count, float[] d) {
        validateIsFloat32();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.FLOAT_32, d.length);
    }

     /**
     * Copy part of an Allocation into this Allocation.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param data the source data allocation.
     * @param dataOff off The offset of the first element in data to
     *          be copied.
     */
    public void copy1DRangeFrom(int off, int count, Allocation data, int dataOff) {
        mRS.nAllocationData2D(getIDSafe(), off, 0,
                              mSelectedLOD, mSelectedFace.mID,
                              count, 1, data.getID(mRS), dataOff, 0,
                              data.mSelectedLOD, data.mSelectedFace.mID);
    }

    private void validate2DRange(int xoff, int yoff, int w, int h) {
        if (mAdaptedAllocation != null) {

        } else {

            if (xoff < 0 || yoff < 0) {
                throw new RSIllegalArgumentException("Offset cannot be negative.");
            }
            if (h < 0 || w < 0) {
                throw new RSIllegalArgumentException("Height or width cannot be negative.");
            }
            if (((xoff + w) > mCurrentDimX) || ((yoff + h) > mCurrentDimY)) {
                throw new RSIllegalArgumentException("Updated region larger than allocation.");
            }
        }
    }

    void copy2DRangeFromUnchecked(int xoff, int yoff, int w, int h, Object array,
                                  Element.DataType dt, int arrayLen) {
        mRS.validate();
        validate2DRange(xoff, yoff, w, h);
        final int dataSize = mType.mElement.getBytesSize() * w * h;
        // AutoPadding for Vec3 Element
        boolean usePadding = false;
        int sizeBytes = arrayLen * dt.mSize;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            if (dataSize / 4 * 3 > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
            usePadding = true;
            sizeBytes = dataSize;
        } else {
            if (dataSize > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
        }
        mRS.nAllocationData2D(getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, w, h,
                              array, sizeBytes, dt,
                              mType.mElement.mType.mSize, usePadding);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} does not match the input data type.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param array Data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, Object array) {
        copy2DRangeFromUnchecked(xoff, yoff, w, h, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not an 8 bit integer nor a vector of 8 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, byte[] data) {
        validateIsInt8();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.SIGNED_8, data.length);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not a 16 bit integer nor a vector of 16 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, short[] data) {
        validateIsInt16();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.SIGNED_16, data.length);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not a 32 bit integer nor a vector of 32 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, int[] data) {
        validateIsInt32();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.SIGNED_32, data.length);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 32 bit float nor a vector of
     * 32 bit floats {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, float[] data) {
        validateIsFloat32();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.FLOAT_32, data.length);
    }

    /**
     * Copy a rectangular region from an Allocation into a rectangular region in
     * this Allocation.
     *
     * @param xoff X offset of the region in this Allocation
     * @param yoff Y offset of the region in this Allocation
     * @param w Width of the region to update.
     * @param h Height of the region to update.
     * @param data source Allocation.
     * @param dataXoff X offset in source Allocation
     * @param dataYoff Y offset in source Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h,
                                Allocation data, int dataXoff, int dataYoff) {
        mRS.validate();
        validate2DRange(xoff, yoff, w, h);
        mRS.nAllocationData2D(getIDSafe(), xoff, yoff,
                              mSelectedLOD, mSelectedFace.mID,
                              w, h, data.getID(mRS), dataXoff, dataYoff,
                              data.mSelectedLOD, data.mSelectedFace.mID);
    }

    /**
     * Copy a {@link android.graphics.Bitmap} into an Allocation.  The height
     * and width of the update will use the height and width of the {@link
     * android.graphics.Bitmap}.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param data the Bitmap to be copied
     */
    public void copy2DRangeFrom(int xoff, int yoff, Bitmap data) {
        mRS.validate();
        if (data.getConfig() == null) {
            Bitmap newBitmap = Bitmap.createBitmap(data.getWidth(), data.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(newBitmap);
            c.drawBitmap(data, 0, 0, null);
            copy2DRangeFrom(xoff, yoff, newBitmap);
            return;
        }
        validateBitmapFormat(data);
        validate2DRange(xoff, yoff, data.getWidth(), data.getHeight());
        mRS.nAllocationData2D(getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, data);
    }

    private void validate3DRange(int xoff, int yoff, int zoff, int w, int h, int d) {
        if (mAdaptedAllocation != null) {

        } else {

            if (xoff < 0 || yoff < 0 || zoff < 0) {
                throw new RSIllegalArgumentException("Offset cannot be negative.");
            }
            if (h < 0 || w < 0 || d < 0) {
                throw new RSIllegalArgumentException("Height or width cannot be negative.");
            }
            if (((xoff + w) > mCurrentDimX) || ((yoff + h) > mCurrentDimY) || ((zoff + d) > mCurrentDimZ)) {
                throw new RSIllegalArgumentException("Updated region larger than allocation.");
            }
        }
    }

    /**
     * Copy a rectangular region from the array into the allocation.
     * The array is assumed to be tightly packed.
     *
     * The data type of the array is not required to be the same as
     * the element data type.
     */
    private void copy3DRangeFromUnchecked(int xoff, int yoff, int zoff, int w, int h, int d,
                                          Object array, Element.DataType dt, int arrayLen) {
        mRS.validate();
        validate3DRange(xoff, yoff, zoff, w, h, d);
        final int dataSize = mType.mElement.getBytesSize() * w * h * d;
        // AutoPadding for Vec3 Element
        boolean usePadding = false;
        int sizeBytes = arrayLen * dt.mSize;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            if (dataSize / 4 * 3 > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
            usePadding = true;
            sizeBytes = dataSize;
        } else {
            if (dataSize > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
        }
        mRS.nAllocationData3D(getIDSafe(), xoff, yoff, zoff, mSelectedLOD, w, h, d,
                              array, sizeBytes, dt,
                              mType.mElement.mType.mSize, usePadding);
    }

    /**
     * Copy from an array into a 3D region in this Allocation.  The
     * array is assumed to be tightly packed. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} does not match the input data type.
     *
     * <p> The size of the region is: w * h * d * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param zoff Z offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param d Depth of the region to update
     * @param array to be placed into the allocation
     */
    public void copy3DRangeFrom(int xoff, int yoff, int zoff, int w, int h, int d, Object array) {
        copy3DRangeFromUnchecked(xoff, yoff, zoff, w, h, d, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy a rectangular region into the allocation from another
     * allocation.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param zoff Z offset of the region to update in this Allocation
     * @param w Width of the region to update.
     * @param h Height of the region to update.
     * @param d Depth of the region to update.
     * @param data source allocation.
     * @param dataXoff X offset of the region in the source Allocation
     * @param dataYoff Y offset of the region in the source Allocation
     * @param dataZoff Z offset of the region in the source Allocation
     */
    public void copy3DRangeFrom(int xoff, int yoff, int zoff, int w, int h, int d,
                                Allocation data, int dataXoff, int dataYoff, int dataZoff) {
        mRS.validate();
        validate3DRange(xoff, yoff, zoff, w, h, d);
        mRS.nAllocationData3D(getIDSafe(), xoff, yoff, zoff, mSelectedLOD,
                              w, h, d, data.getID(mRS), dataXoff, dataYoff, dataZoff,
                              data.mSelectedLOD);
    }


    /**
     * Copy from the Allocation into a {@link android.graphics.Bitmap}.  The
     * bitmap must match the dimensions of the Allocation.
     *
     * @param b The bitmap to be set from the Allocation.
     */
    public void copyTo(Bitmap b) {
        mRS.validate();
        validateBitmapFormat(b);
        validateBitmapSize(b);
        mRS.nAllocationCopyToBitmap(getID(mRS), b);
    }

    private void copyTo(Object array, Element.DataType dt, int arrayLen) {
        mRS.validate();
        boolean usePadding = false;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            usePadding = true;
        }
        if (usePadding) {
            if (dt.mSize * arrayLen < mSize / 4 * 3) {
                throw new RSIllegalArgumentException(
                    "Size of output array cannot be smaller than size of allocation.");
            }
        } else {
            if (dt.mSize * arrayLen < mSize) {
                throw new RSIllegalArgumentException(
                    "Size of output array cannot be smaller than size of allocation.");
            }
        }
        mRS.nAllocationRead(getID(mRS), array, dt, mType.mElement.mType.mSize, usePadding);
    }

    /**
     * Copy from the Allocation into an array. The method is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} does not match the input data type.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells will be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param array The array to be set from the Allocation.
     */
    public void copyTo(Object array) {
        copyTo(array, validateObjectIsPrimitiveArray(array, true),
               java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy from the Allocation into a byte array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither an 8 bit integer nor a vector of 8 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells will be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(byte[] d) {
        validateIsInt8();
        copyTo(d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy from the Allocation into a short array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not a 16 bit integer nor a vector of 16 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells will be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(short[] d) {
        validateIsInt16();
        copyTo(d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy from the Allocation into a int array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is not a 32 bit integer nor a vector of 32 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells will be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(int[] d) {
        validateIsInt32();
        copyTo(d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy from the Allocation into a float array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 32 bit float nor a vector of
     * 32 bit floats {@link androidx.renderscript.Element.DataType}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the Allocation {@link
     * #getBytesSize getBytesSize()}.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells will be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the Allocation {@link #getBytesSize getBytesSize()}. The padding bytes for
     * the cells must not be part of the array.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(float[] d) {
        validateIsFloat32();
        copyTo(d, Element.DataType.FLOAT_32, d.length);
    }

    /**
     * @hide
     * This is only intended to be used by auto-generated code reflected from
     * the RenderScript script files and should not be used by developers.
     *
     * @param xoff
     * @param yoff
     * @param zoff
     * @param component_number
     * @param fp
     */
    /*
    public void copyToFieldPacker(int xoff, int yoff, int zoff, int component_number, FieldPacker fp) {
        mRS.validate();
        if (component_number >= mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset x must be >= 0.");
        }
        if(yoff < 0) {
            throw new RSIllegalArgumentException("Offset y must be >= 0.");
        }
        if(zoff < 0) {
            throw new RSIllegalArgumentException("Offset z must be >= 0.");
        }

        final byte[] data = fp.getData();
        int data_length = data.length;
        int eSize = mType.mElement.mElements[component_number].getBytesSize();
        eSize *= mType.mElement.mArraySizes[component_number];

        if (data_length != eSize) {
            throw new RSIllegalArgumentException("Field packer sizelength " + data_length +
                                               " does not match component size " + eSize + ".");
        }

        mRS.nAllocationElementRead(getIDSafe(), xoff, yoff, zoff, mSelectedLOD,
                                   component_number, data, data_length);
    }
    */

    private void copy1DRangeToUnchecked(int off, int count, Object array,
                                        Element.DataType dt, int arrayLen) {
        final int dataSize = mType.mElement.getBytesSize() * count;
        // AutoPadding for Vec3 Element
        boolean usePadding = false;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            usePadding = true;
        }
        data1DChecks(off, count, arrayLen * dt.mSize, dataSize, usePadding);
        mRS.nAllocationRead1D(getIDSafe(), off, mSelectedLOD, count, array, dataSize, dt,
                              mType.mElement.mType.mSize, usePadding);
    }

    /**
     * Copy a 1D region of this Allocation into an array.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param array The dest array
     */
    public void copy1DRangeToUnchecked(int off, int count, Object array) {
        copy1DRangeToUnchecked(off, count, array,
                               validateObjectIsPrimitiveArray(array, false),
                               java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy a 1D region of this Allocation into an array.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeToUnchecked(int off, int count, int[] d) {
        copy1DRangeToUnchecked(off, count, (Object)d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy a 1D region of this Allocation into an array.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeToUnchecked(int off, int count, short[] d) {
        copy1DRangeToUnchecked(off, count, (Object)d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy a 1D region of this Allocation into an array.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeToUnchecked(int off, int count, byte[] d) {
        copy1DRangeToUnchecked(off, count, (Object)d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy a 1D region of this Allocation into an array.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeToUnchecked(int off, int count, float[] d) {
        copy1DRangeToUnchecked(off, count, (Object)d, Element.DataType.FLOAT_32, d.length);
    }


    /**
     * Copy a 1D region of this Allocation into an array.  This method is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} does not match the component type
     * of the array passed in.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param array The source array.
     */
    public void copy1DRangeTo(int off, int count, Object array) {
        copy1DRangeToUnchecked(off, count, array,
                               validateObjectIsPrimitiveArray(array, true),
                               java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy a 1D region of this Allocation into an array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 32 bit integer nor a vector of 32 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeTo(int off, int count, int[] d) {
        validateIsInt32();
        copy1DRangeToUnchecked(off, count, d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy a 1D region of this Allocation into an array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 16 bit integer nor a vector of 16 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeTo(int off, int count, short[] d) {
        validateIsInt16();
        copy1DRangeToUnchecked(off, count, d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy a 1D region of this Allocation into an array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither an 8 bit integer nor a vector of 8 bit
     * integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array
     */
    public void copy1DRangeTo(int off, int count, byte[] d) {
        validateIsInt8();
        copy1DRangeToUnchecked(off, count, d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy a 1D region of this Allocation into an array. This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * androidx.renderscript.Element} is neither a 32 bit float nor a vector of
     * 32 bit floats {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: count * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source array.
     */
    public void copy1DRangeTo(int off, int count, float[] d) {
        validateIsFloat32();
        copy1DRangeToUnchecked(off, count, d, Element.DataType.FLOAT_32, d.length);
    }


    void copy2DRangeToUnchecked(int xoff, int yoff, int w, int h, Object array,
                                Element.DataType dt, int arrayLen) {
        mRS.validate();
        validate2DRange(xoff, yoff, w, h);
        final int dataSize = mType.mElement.getBytesSize() * w * h;
        // AutoPadding for Vec3 Element
        boolean usePadding = false;
        int sizeBytes = arrayLen * dt.mSize;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            if (dataSize / 4 * 3 > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
            usePadding = true;
            sizeBytes = dataSize;
        } else {
            if (dataSize > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
        }
        mRS.nAllocationRead2D(getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, w, h,
                              array, sizeBytes, dt, mType.mElement.mType.mSize, usePadding);
    }

    /**
     * Copy from a rectangular region in this Allocation into an array. This
     * method is type checked and will generate exceptions if the Allocation's
     * {@link androidx.renderscript.Element} does not match the component type
     * of the array passed in.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to copy in this Allocation
     * @param yoff Y offset of the region to copy in this Allocation
     * @param w Width of the region to copy
     * @param h Height of the region to copy
     * @param array Dest Array to be copied into
     */
    public void copy2DRangeTo(int xoff, int yoff, int w, int h, Object array) {
        copy2DRangeToUnchecked(xoff, yoff, w, h, array,
                               validateObjectIsPrimitiveArray(array, true),
                               java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy from a rectangular region in this Allocation into an array. This
     * variant is type checked and will generate exceptions if the Allocation's
     * {@link androidx.renderscript.Element} is neither an 8 bit integer nor a vector
     * of 8 bit integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to copy in this Allocation
     * @param yoff Y offset of the region to copy in this Allocation
     * @param w Width of the region to copy
     * @param h Height of the region to copy
     * @param data Dest Array to be copied into
     */
    public void copy2DRangeTo(int xoff, int yoff, int w, int h, byte[] data) {
        validateIsInt8();
        copy2DRangeToUnchecked(xoff, yoff, w, h, data,
                               Element.DataType.SIGNED_8, data.length);
    }

    /**
     * Copy from a rectangular region in this Allocation into an array. This
     * variant is type checked and will generate exceptions if the Allocation's
     * {@link androidx.renderscript.Element} is neither a 16 bit integer nor a vector
     * of 16 bit integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to copy in this Allocation
     * @param yoff Y offset of the region to copy in this Allocation
     * @param w Width of the region to copy
     * @param h Height of the region to copy
     * @param data Dest Array to be copied into
     */
    public void copy2DRangeTo(int xoff, int yoff, int w, int h, short[] data) {
        validateIsInt16();
        copy2DRangeToUnchecked(xoff, yoff, w, h, data,
                               Element.DataType.SIGNED_16, data.length);
    }

    /**
     * Copy from a rectangular region in this Allocation into an array. This
     * variant is type checked and will generate exceptions if the Allocation's
     * {@link androidx.renderscript.Element} is neither a 32 bit integer nor a vector
     * of 32 bit integers {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to copy in this Allocation
     * @param yoff Y offset of the region to copy in this Allocation
     * @param w Width of the region to copy
     * @param h Height of the region to copy
     * @param data Dest Array to be copied into
     */
    public void copy2DRangeTo(int xoff, int yoff, int w, int h, int[] data) {
        validateIsInt32();
        copy2DRangeToUnchecked(xoff, yoff, w, h, data,
                               Element.DataType.SIGNED_32, data.length);
    }

    /**
     * Copy from a rectangular region in this Allocation into an array. This
     * variant is type checked and will generate exceptions if the Allocation's
     * {@link androidx.renderscript.Element} is neither a 32 bit float nor a vector
     * of 32 bit floats {@link androidx.renderscript.Element.DataType}.
     *
     * <p> The size of the region is: w * h * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to copy in this Allocation
     * @param yoff Y offset of the region to copy in this Allocation
     * @param w Width of the region to copy
     * @param h Height of the region to copy
     * @param data Dest Array to be copied into
     */
    public void copy2DRangeTo(int xoff, int yoff, int w, int h, float[] data) {
        validateIsFloat32();
        copy2DRangeToUnchecked(xoff, yoff, w, h, data,
                               Element.DataType.FLOAT_32, data.length);
    }


    /**
     * Copy from a 3D region in this Allocation into an array. This method does
     * not guarantee that the Allocation is compatible with the input buffer.
     * The array is assumed to be tightly packed.
     *
     * The data type of the array is not required to be the same as
     * the element data type.
     */
    /*
    private void copy3DRangeToUnchecked(int xoff, int yoff, int zoff, int w, int h, int d,
                                        Object array, Element.DataType dt, int arrayLen) {
        mRS.validate();
        validate3DRange(xoff, yoff, zoff, w, h, d);
        final int dataSize = mType.mElement.getBytesSize() * w * h * d;
        // AutoPadding for Vec3 Element
        boolean usePadding = false;
        int sizeBytes = arrayLen * dt.mSize;
        if (mAutoPadding && (mType.getElement().getVectorSize() == 3)) {
            if (dataSize / 4 * 3 > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
            usePadding = true;
            sizeBytes = dataSize;
        } else {
            if (dataSize > sizeBytes) {
                throw new RSIllegalArgumentException("Array too small for allocation type.");
            }
        }
        mRS.nAllocationRead3D(getIDSafe(), xoff, yoff, zoff, mSelectedLOD, w, h, d,
                              array, sizeBytes, dt, mType.mElement.mType.mSize, usePadding);
    }
    */

    /**
     * @hide
     * Copy from a 3D region in this Allocation into an array. This
     * method is type checked and will generate exceptions if the Allocation's
     * {@link androidx.renderscript.Element} does not match the component type
     * of the array passed in.
     *
     * <p> The size of the region is: w * h * d * {@link #getElement}.{@link
     * Element#getBytesSize}.
     *
     * <p> If the Allocation does not have Vec3 Elements, then the size of the
     * array in bytes must be at least the size of the region.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is disabled, then the size of the array in bytes must be at least the size
     * of the region. The padding bytes for the cells must be part of the array.
     *
     * <p> If the Allocation has Vec3 Elements and {@link #setAutoPadding AutoPadding}
     * is enabled, then the size of the array in bytes must be at least 3/4 the size
     * of the region. The padding bytes for the cells must not be part of the array.
     *
     * @param xoff X offset of the region to copy in this Allocation
     * @param yoff Y offset of the region to copy in this Allocation
     * @param zoff Z offset of the region to copy in this Allocation
     * @param w Width of the region to copy
     * @param h Height of the region to copy
     * @param d Depth of the region to copy
     * @param array Dest Array to be copied into
     */
    /*
    public void copy3DRangeTo(int xoff, int yoff, int zoff, int w, int h, int d, Object array) {
        copy3DRangeToUnchecked(xoff, yoff, zoff, w, h, d, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
    }
    */

    // creation

    static BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
    static {
        mBitmapOptions.inScaled = false;
    }

    /**
     * Creates a new Allocation with the given {@link
     * androidx.renderscript.Type}, mipmap flag, and usage flags.
     *
     * @param type RenderScript type describing data layout
     * @param mips specifies desired mipmap behaviour for the
     *             allocation
     * @param usage bit field specifying how the Allocation is
     *              utilized
     */
    static public Allocation createTyped(RenderScript rs, Type type, MipmapControl mips, int usage) {
        rs.validate();
        if (type.getID(rs) == 0) {
            throw new RSInvalidStateException("Bad Type");
        }

        if(!rs.usingIO() && (usage & USAGE_IO_INPUT) != 0) {
            throw new RSRuntimeException("USAGE_IO not supported, Allocation creation failed.");
        }

        long id = rs.nAllocationCreateTyped(type.getID(rs), mips.mID, usage, 0);
        if (id == 0) {
            throw new RSRuntimeException("Allocation creation failed.");
        }
        return new Allocation(id, rs, type, usage);
    }

    /**
     * Creates an Allocation with the size specified by the type and no mipmaps
     * generated by default
     *
     * @param rs Context to which the allocation will belong.
     * @param type renderscript type describing data layout
     * @param usage bit field specifying how the allocation is
     *              utilized
     *
     * @return allocation
     */
    static public Allocation createTyped(RenderScript rs, Type type, int usage) {
        return createTyped(rs, type, MipmapControl.MIPMAP_NONE, usage);
    }

    /**
     * Creates an Allocation for use by scripts with a given {@link
     * androidx.renderscript.Type} and no mipmaps
     *
     * @param rs Context to which the Allocation will belong.
     * @param type RenderScript Type describing data layout
     *
     * @return allocation
     */
    static public Allocation createTyped(RenderScript rs, Type type) {
        return createTyped(rs, type, MipmapControl.MIPMAP_NONE, USAGE_SCRIPT);
    }

    /**
     * Creates an Allocation with a specified number of given elements
     *
     * @param rs Context to which the Allocation will belong.
     * @param e Element to use in the Allocation
     * @param count the number of Elements in the Allocation
     * @param usage bit field specifying how the Allocation is
     *              utilized
     *
     * @return allocation
     */
    static public Allocation createSized(RenderScript rs, Element e,
                                         int count, int usage) {
        rs.validate();
        Type.Builder b = new Type.Builder(rs, e);
        b.setX(count);
        Type t = b.create();

        long id = rs.nAllocationCreateTyped(t.getID(rs), MipmapControl.MIPMAP_NONE.mID, usage, 0);
        if (id == 0) {
            throw new RSRuntimeException("Allocation creation failed.");
        }
        return new Allocation(id, rs, t, usage);
    }

    /**
     * Creates an Allocation with a specified number of given elements
     *
     * @param rs Context to which the Allocation will belong.
     * @param e Element to use in the Allocation
     * @param count the number of Elements in the Allocation
     *
     * @return allocation
     */
    static public Allocation createSized(RenderScript rs, Element e, int count) {
        return createSized(rs, e, count, USAGE_SCRIPT);
    }

    static Element elementFromBitmap(RenderScript rs, Bitmap b) {
        final Bitmap.Config bc = b.getConfig();
        if (bc == Bitmap.Config.ALPHA_8) {
            return Element.A_8(rs);
        }
        if (bc == Bitmap.Config.ARGB_4444) {
            return Element.RGBA_4444(rs);
        }
        if (bc == Bitmap.Config.ARGB_8888) {
            return Element.RGBA_8888(rs);
        }
        if (bc == Bitmap.Config.RGB_565) {
            return Element.RGB_565(rs);
        }
        throw new RSInvalidStateException("Bad bitmap type: " + bc);
    }

    static Type typeFromBitmap(RenderScript rs, Bitmap b,
                                       MipmapControl mip) {
        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.setX(b.getWidth());
        tb.setY(b.getHeight());
        tb.setMipmaps(mip == MipmapControl.MIPMAP_FULL);
        return tb.create();
    }

    /**
     * Creates an Allocation from a {@link android.graphics.Bitmap}.
     *
     * @param rs Context to which the allocation will belong.
     * @param b Bitmap source for the allocation data
     * @param mips specifies desired mipmap behaviour for the
     *             allocation
     * @param usage bit field specifying how the allocation is
     *              utilized
     *
     * @return Allocation containing bitmap data
     *
     */
    static public Allocation createFromBitmap(RenderScript rs, Bitmap b,
                                              MipmapControl mips,
                                              int usage) {
        rs.validate();

        // WAR undocumented color formats
        if (b.getConfig() == null) {
            if ((usage & USAGE_SHARED) != 0) {
                throw new RSIllegalArgumentException("USAGE_SHARED cannot be used with a Bitmap that has a null config.");
            }
            Bitmap newBitmap = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(newBitmap);
            c.drawBitmap(b, 0, 0, null);
            return createFromBitmap(rs, newBitmap, mips, usage);
        }

        Type t = typeFromBitmap(rs, b, mips);

        // enable optimized bitmap path only with no mipmap and script-only usage
        if (mips == MipmapControl.MIPMAP_NONE &&
            t.getElement().isCompatible(Element.RGBA_8888(rs)) &&
            usage == (USAGE_SHARED | USAGE_SCRIPT | USAGE_GRAPHICS_TEXTURE)) {
            long id = rs.nAllocationCreateBitmapBackedAllocation(t.getID(rs), mips.mID, b, usage);
            if (id == 0) {
                throw new RSRuntimeException("Load failed.");
            }

            // keep a reference to the Bitmap around to prevent GC
            Allocation alloc = new Allocation(id, rs, t, usage);
            alloc.setBitmap(b);
            return alloc;
        }


        long id = rs.nAllocationCreateFromBitmap(t.getID(rs), mips.mID, b, usage);
        if (id == 0) {
            throw new RSRuntimeException("Load failed.");
        }
        return new Allocation(id, rs, t, usage);
    }

    /**
     * Associate a {@link android.view.Surface} with this Allocation. This
     * operation is only valid for Allocations with {@link #USAGE_IO_OUTPUT}.
     *
     * @param sur Surface to associate with allocation
     */
    public void setSurface(Surface sur) {
        mRS.validate();
        if ((mUsage & USAGE_IO_OUTPUT) == 0) {
            throw new RSInvalidStateException("Allocation is not USAGE_IO_OUTPUT.");
        }

        mRS.nAllocationSetSurface(getID(mRS), sur);
    }

    /**
     * Creates an Allocation from a {@link android.graphics.Bitmap}.
     *
     * <p>This Allocation will be created with {@link #USAGE_SHARED}, and
     * {@link #USAGE_SCRIPT}.</p>
     *
     * @param rs Context to which the allocation will belong.
     * @param b bitmap source for the allocation data
     *
     * @return Allocation containing bitmap data
     *
     */
    static public Allocation createFromBitmap(RenderScript rs, Bitmap b) {
        return createFromBitmap(rs, b, MipmapControl.MIPMAP_NONE,
                                USAGE_SHARED | USAGE_SCRIPT | USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates a cubemap Allocation from a {@link android.graphics.Bitmap}
     * containing the horizontal list of cube faces. Each face must be a square,
     * have the same size as all other faces, and have a width that is a power
     * of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param b Bitmap with cubemap faces layed out in the following
     *          format: right, left, top, bottom, front, back
     * @param mips specifies desired mipmap behaviour for the cubemap
     * @param usage bit field specifying how the cubemap is utilized
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromBitmap(RenderScript rs, Bitmap b,
                                                     MipmapControl mips,
                                                     int usage) {
        rs.validate();

        int height = b.getHeight();
        int width = b.getWidth();

        if (width % 6 != 0) {
            throw new RSIllegalArgumentException("Cubemap height must be multiple of 6");
        }
        if (width / 6 != height) {
            throw new RSIllegalArgumentException("Only square cube map faces supported");
        }
        boolean isPow2 = (height & (height - 1)) == 0;
        if (!isPow2) {
            throw new RSIllegalArgumentException("Only power of 2 cube faces supported");
        }

        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.setX(height);
        tb.setY(height);
        tb.setFaces(true);
        tb.setMipmaps(mips == MipmapControl.MIPMAP_FULL);
        Type t = tb.create();

        long id = rs.nAllocationCubeCreateFromBitmap(t.getID(rs), mips.mID, b, usage);
        if(id == 0) {
            throw new RSRuntimeException("Load failed for bitmap " + b + " element " + e);
        }
        return new Allocation(id, rs, t, usage);
    }

    /**
     * Creates a non-mipmapped cubemap Allocation for use as a graphics texture
     * from a {@link android.graphics.Bitmap} containing the horizontal list of
     * cube faces. Each face must be a square, have the same size as all other
     * faces, and have a width that is a power of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param b bitmap with cubemap faces layed out in the following
     *          format: right, left, top, bottom, front, back
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromBitmap(RenderScript rs,
                                                     Bitmap b) {
        return createCubemapFromBitmap(rs, b, MipmapControl.MIPMAP_NONE,
                                       USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates a cubemap Allocation from 6 {@link android.graphics.Bitmap}
     * objects containing the cube faces. Each face must be a square, have the
     * same size as all other faces, and have a width that is a power of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param xpos cubemap face in the positive x direction
     * @param xneg cubemap face in the negative x direction
     * @param ypos cubemap face in the positive y direction
     * @param yneg cubemap face in the negative y direction
     * @param zpos cubemap face in the positive z direction
     * @param zneg cubemap face in the negative z direction
     * @param mips specifies desired mipmap behaviour for the cubemap
     * @param usage bit field specifying how the cubemap is utilized
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromCubeFaces(RenderScript rs,
                                                        Bitmap xpos,
                                                        Bitmap xneg,
                                                        Bitmap ypos,
                                                        Bitmap yneg,
                                                        Bitmap zpos,
                                                        Bitmap zneg,
                                                        MipmapControl mips,
                                                        int usage) {
        /*
        int height = xpos.getHeight();
        if (xpos.getWidth() != height ||
            xneg.getWidth() != height || xneg.getHeight() != height ||
            ypos.getWidth() != height || ypos.getHeight() != height ||
            yneg.getWidth() != height || yneg.getHeight() != height ||
            zpos.getWidth() != height || zpos.getHeight() != height ||
            zneg.getWidth() != height || zneg.getHeight() != height) {
            throw new RSIllegalArgumentException("Only square cube map faces supported");
        }
        boolean isPow2 = (height & (height - 1)) == 0;
        if (!isPow2) {
            throw new RSIllegalArgumentException("Only power of 2 cube faces supported");
        }

        Element e = elementFromBitmap(rs, xpos);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.setX(height);
        tb.setY(height);
        tb.setFaces(true);
        tb.setMipmaps(mips == MipmapControl.MIPMAP_FULL);
        Type t = tb.create();
        Allocation cubemap = Allocation.createTyped(rs, t, mips, usage);

        AllocationAdapter adapter = AllocationAdapter.create2D(rs, cubemap);
        adapter.setFace(Type.CubemapFace.POSITIVE_X);
        adapter.copyFrom(xpos);
        adapter.setFace(Type.CubemapFace.NEGATIVE_X);
        adapter.copyFrom(xneg);
        adapter.setFace(Type.CubemapFace.POSITIVE_Y);
        adapter.copyFrom(ypos);
        adapter.setFace(Type.CubemapFace.NEGATIVE_Y);
        adapter.copyFrom(yneg);
        adapter.setFace(Type.CubemapFace.POSITIVE_Z);
        adapter.copyFrom(zpos);
        adapter.setFace(Type.CubemapFace.NEGATIVE_Z);
        adapter.copyFrom(zneg);

        return cubemap;
        */
        return null;
    }

    /**
     * Creates a non-mipmapped cubemap Allocation for use as a sampler input
     * from 6 {@link android.graphics.Bitmap} objects containing the cube
     * faces. Each face must be a square, have the same size as all other faces,
     * and have a width that is a power of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param xpos cubemap face in the positive x direction
     * @param xneg cubemap face in the negative x direction
     * @param ypos cubemap face in the positive y direction
     * @param yneg cubemap face in the negative y direction
     * @param zpos cubemap face in the positive z direction
     * @param zneg cubemap face in the negative z direction
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromCubeFaces(RenderScript rs,
                                                        Bitmap xpos,
                                                        Bitmap xneg,
                                                        Bitmap ypos,
                                                        Bitmap yneg,
                                                        Bitmap zpos,
                                                        Bitmap zneg) {
        return createCubemapFromCubeFaces(rs, xpos, xneg, ypos, yneg,
                                          zpos, zneg, MipmapControl.MIPMAP_NONE,
                                          USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates an Allocation from the Bitmap referenced
     * by resource ID.
     *
     * @param rs Context to which the allocation will belong.
     * @param res application resources
     * @param id resource id to load the data from
     * @param mips specifies desired mipmap behaviour for the
     *             allocation
     * @param usage bit field specifying how the allocation is
     *              utilized
     *
     * @return Allocation containing resource data
     *
     */
    static public Allocation createFromBitmapResource(RenderScript rs,
                                                      Resources res,
                                                      int id,
                                                      MipmapControl mips,
                                                      int usage) {

        rs.validate();
        if ((usage & (USAGE_SHARED | USAGE_IO_INPUT | USAGE_IO_OUTPUT)) != 0) {
            throw new RSIllegalArgumentException("Unsupported usage specified.");
        }
        Bitmap b = BitmapFactory.decodeResource(res, id);
        Allocation alloc = createFromBitmap(rs, b, mips, usage);
        b.recycle();
        return alloc;
    }

    /**
     * Creates a non-mipmapped Allocation to use as a graphics texture from the
     * {@link android.graphics.Bitmap} referenced by resource ID.
     *
     * <p>This allocation will be created with {@link #USAGE_SCRIPT} and
     * {@link #USAGE_GRAPHICS_TEXTURE}.</p>
     *
     * @param rs Context to which the allocation will belong.
     * @param res application resources
     * @param id resource id to load the data from
     *
     * @return Allocation containing resource data
     *
     */
    static public Allocation createFromBitmapResource(RenderScript rs,
                                                      Resources res,
                                                      int id) {
        return createFromBitmapResource(rs, res, id,
                                        MipmapControl.MIPMAP_NONE,
                                        USAGE_SCRIPT | USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates an Allocation containing string data encoded in UTF-8 format.
     *
     * @param rs Context to which the allocation will belong.
     * @param str string to create the allocation from
     * @param usage bit field specifying how the allocaiton is
     *              utilized
     *
     */
    static public Allocation createFromString(RenderScript rs,
                                              String str,
                                              int usage) {
        rs.validate();
        byte[] allocArray = null;
        try {
            allocArray = str.getBytes("UTF-8");
            Allocation alloc = Allocation.createSized(rs, Element.U8(rs), allocArray.length, usage);
            alloc.copyFrom(allocArray);
            return alloc;
        }
        catch (Exception e) {
            throw new RSRuntimeException("Could not convert string to utf-8.");
        }
    }

    /**
     * Frees any native resources associated with this object.  The
     * primary use is to force immediate cleanup of resources when it is
     * believed the GC will not respond quickly enough.
     * For USAGE_IO_OUTPUT, destroy() implies setSurface(null).
     */
    @Override
    public void destroy() {
        if (mIncCompatAllocation != 0) {
            boolean shouldDestroy = false;
            synchronized(this) {
                if (!mIncAllocDestroyed) {
                    shouldDestroy = true;
                    mIncAllocDestroyed = true;
                }
            }

            if (shouldDestroy) {
                // must include nObjDestroy in the critical section
                ReentrantReadWriteLock.ReadLock rlock = mRS.mRWLock.readLock();
                rlock.lock();
                if(mRS.isAlive()) {
                    mRS.nIncObjDestroy(mIncCompatAllocation);
                }
                rlock.unlock();
                mIncCompatAllocation = 0;
            }
        }
        if ((mUsage & (USAGE_IO_INPUT | USAGE_IO_OUTPUT)) != 0) {
            setSurface(null);
        }
        super.destroy();
    }

}
