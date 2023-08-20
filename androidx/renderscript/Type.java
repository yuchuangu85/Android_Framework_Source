/*
 * Copyright (C) 2013 The Android Open Source Project
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


import java.lang.reflect.Field;

import android.graphics.ImageFormat;
import android.util.Log;

/**
 * <p>A Type describes the {@link androidx.renderscript.Element} and
 * dimensions used for an {@link androidx.renderscript.Allocation} or
 * a parallel operation. Types are created through
 * {@link androidx.renderscript.Type.Builder}.</p>
 *
 * <p>A Type always includes an {@link androidx.renderscript.Element}
 * and an X dimension. A Type may be multidimensional, up to three dimensions.
 * A nonzero value in the Y or Z dimensions indicates that the dimension is
 * present. Note that a Type with only a given X dimension and a Type with the
 * same X dimension but Y = 1 are not equivalent.</p>
 *
 * <p>A Type also supports inclusion of level of detail (LOD) or cube map
 * faces. LOD and cube map faces are booleans to indicate present or not
 * present. </p>
 *
 * <p>A Type also supports YUV format information to support an {@link
 * androidx.renderscript.Allocation} in a YUV format. The YUV formats
 * supported are {@link android.graphics.ImageFormat#YV12} and {@link
 * android.graphics.ImageFormat#NV21}.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating an application that uses RenderScript,
 * read the
 * <a href="{@docRoot}guide/topics/renderscript/index.html">RenderScript</a>
 * developer guide.</p>
 * </div>
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 **/
@Deprecated
public class Type extends BaseObj {
    int mDimX;
    int mDimY;
    int mDimZ;
    boolean mDimMipmaps;
    boolean mDimFaces;
    int mDimYuv;
    int mElementCount;
    Element mElement;

    public enum CubemapFace {
        POSITIVE_X (0),
        NEGATIVE_X (1),
        POSITIVE_Y (2),
        NEGATIVE_Y (3),
        POSITIVE_Z (4),
        NEGATIVE_Z (5);

        int mID;
        CubemapFace(int id) {
            mID = id;
        }
    }

    /**
     * Return the element associated with this Type.
     *
     * @return Element
     */
    public Element getElement() {
        return mElement;
    }

    /**
     * Return the value of the X dimension.
     *
     * @return int
     */
    public int getX() {
        return mDimX;
    }

    /**
     * Return the value of the Y dimension or 0 for a 1D allocation.
     *
     * @return int
     */
    public int getY() {
        return mDimY;
    }

    /**
     * Return the value of the Z dimension or 0 for a 1D or 2D allocation.
     *
     * @return int
     */
    public int getZ() {
        return mDimZ;
    }

    /**
     * Get the YUV format
     *
     * @return int
     */
    public int getYuv() {
        return mDimYuv;
    }

    /**
     * Return if the Type has a mipmap chain.
     *
     * @return boolean
     */
    public boolean hasMipmaps() {
        return mDimMipmaps;
    }

    /**
     * Return if the Type is a cube map.
     *
     * @return boolean
     */
    public boolean hasFaces() {
        return mDimFaces;
    }

    /**
     * Return the total number of accessable cells in the Type.
     *
     * @return int
     */
    public int getCount() {
        return mElementCount;
    }

    void calcElementCount() {
        boolean hasLod = hasMipmaps();
        int x = getX();
        int y = getY();
        int z = getZ();
        int faces = 1;
        if (hasFaces()) {
            faces = 6;
        }
        if (x == 0) {
            x = 1;
        }
        if (y == 0) {
            y = 1;
        }
        if (z == 0) {
            z = 1;
        }

        int count = x * y * z * faces;

        while (hasLod && ((x > 1) || (y > 1) || (z > 1))) {
            if(x > 1) {
                x >>= 1;
            }
            if(y > 1) {
                y >>= 1;
            }
            if(z > 1) {
                z >>= 1;
            }

            count += x * y * z * faces;
        }
        mElementCount = count;
    }


    Type(long id, RenderScript rs) {
        super(id, rs);
    }

    /*
     * Get an identical placeholder Type for Compat Context
     *
     */
    public long getDummyType(RenderScript mRS, long eid) {
        return mRS.nIncTypeCreate(eid, mDimX, mDimY, mDimZ, mDimMipmaps, mDimFaces, mDimYuv);
    }

    /**
     * Utility function for creating basic 1D types. The type is
     * created without mipmaps enabled.
     *
     * @param rs The RenderScript context
     * @param e The Element for the Type
     * @param dimX The X dimension, must be > 0
     *
     * @return Type
     */
    static public Type createX(RenderScript rs, Element e, int dimX) {
        if (dimX < 1) {
            throw new RSInvalidStateException("Dimension must be >= 1.");
        }

        long id = rs.nTypeCreate(e.getID(rs), dimX, 0, 0, false, false, 0);
        Type t = new Type(id, rs);
        t.mElement = e;
        t.mDimX = dimX;
        t.calcElementCount();
        return t;
    }

    /**
     * Utility function for creating basic 2D types. The type is
     * created without mipmaps or cubemaps.
     *
     * @param rs The RenderScript context
     * @param e The Element for the Type
     * @param dimX The X dimension, must be > 0
     * @param dimY The Y dimension, must be > 0
     *
     * @return Type
     */
    static public Type createXY(RenderScript rs, Element e, int dimX, int dimY) {
        if ((dimX < 1) || (dimY < 1)) {
            throw new RSInvalidStateException("Dimension must be >= 1.");
        }

        long id = rs.nTypeCreate(e.getID(rs), dimX, dimY, 0, false, false, 0);
        Type t = new Type(id, rs);
        t.mElement = e;
        t.mDimX = dimX;
        t.mDimY = dimY;
        t.calcElementCount();
        return t;
    }

    /**
     * Utility function for creating basic 3D types. The type is
     * created without mipmaps.
     *
     * @param rs The RenderScript context
     * @param e The Element for the Type
     * @param dimX The X dimension, must be > 0
     * @param dimY The Y dimension, must be > 0
     * @param dimZ The Z dimension, must be > 0
     *
     * @return Type
     */
    static public Type createXYZ(RenderScript rs, Element e, int dimX, int dimY, int dimZ) {
        if ((dimX < 1) || (dimY < 1) || (dimZ < 1)) {
            throw new RSInvalidStateException("Dimension must be >= 1.");
        }

        long id = rs.nTypeCreate(e.getID(rs), dimX, dimY, dimZ, false, false, 0);
        Type t = new Type(id, rs);
        t.mElement = e;
        t.mDimX = dimX;
        t.mDimY = dimY;
        t.mDimZ = dimZ;
        t.calcElementCount();
        return t;
    }

    /**
     * Builder class for Type.
     *
     */
    public static class Builder {
        RenderScript mRS;
        int mDimX = 1;
        int mDimY;
        int mDimZ;
        boolean mDimMipmaps;
        boolean mDimFaces;
        int mYuv;

        Element mElement;

        /**
         * Create a new builder object.
         *
         * @param rs
         * @param e The element for the type to be created.
         */
        public Builder(RenderScript rs, Element e) {
            e.checkValid();
            mRS = rs;
            mElement = e;
        }

        /**
         * Add a dimension to the Type.
         *
         *
         * @param value
         */
        public Builder setX(int value) {
            if(value < 1) {
                throw new RSIllegalArgumentException("Values of less than 1 for Dimension X are not valid.");
            }
            mDimX = value;
            return this;
        }

        public Builder setY(int value) {
            if(value < 1) {
                throw new RSIllegalArgumentException("Values of less than 1 for Dimension Y are not valid.");
            }
            mDimY = value;
            return this;
        }

        public Builder setZ(int value) {
            if(value < 1) {
                throw new RSIllegalArgumentException("Values of less than 1 for Dimension Z are not valid.");
            }
            mDimZ = value;
            return this;
        }

        public Builder setMipmaps(boolean value) {
            mDimMipmaps = value;
            return this;
        }

        public Builder setFaces(boolean value) {
            mDimFaces = value;
            return this;
        }

        /**
         * Set the YUV layout for a Type.
         *
         * @param yuvFormat {@link android.graphics.ImageFormat#YV12} or {@link android.graphics.ImageFormat#NV21}
         */
        public Builder setYuvFormat(int yuvFormat) {
            switch (yuvFormat) {
            case android.graphics.ImageFormat.NV21:
            case android.graphics.ImageFormat.YV12:
                break;

            default:
                throw new RSIllegalArgumentException("Only NV21 and YV12 are supported..");
            }

            mYuv = yuvFormat;
            return this;
        }


        /**
         * Validate structure and create a new Type.
         *
         * @return Type
         */
        public Type create() {
            if (mDimZ > 0) {
                if ((mDimX < 1) || (mDimY < 1)) {
                    throw new RSInvalidStateException("Both X and Y dimension required when Z is present.");
                }
                if (mDimFaces) {
                    throw new RSInvalidStateException("Cube maps not supported with 3D types.");
                }
            }
            if (mDimY > 0) {
                if (mDimX < 1) {
                    throw new RSInvalidStateException("X dimension required when Y is present.");
                }
            }
            if (mDimFaces) {
                if (mDimY < 1) {
                    throw new RSInvalidStateException("Cube maps require 2D Types.");
                }
            }

            if (mYuv != 0) {
                if ((mDimZ != 0) || mDimFaces || mDimMipmaps) {
                    throw new RSInvalidStateException("YUV only supports basic 2D.");
                }
            }

            Type t;
            long id = mRS.nTypeCreate(mElement.getID(mRS),
                                     mDimX, mDimY, mDimZ, mDimMipmaps, mDimFaces, mYuv);
            t = new Type(id, mRS);

            t.mElement = mElement;
            t.mDimX = mDimX;
            t.mDimY = mDimY;
            t.mDimZ = mDimZ;
            t.mDimMipmaps = mDimMipmaps;
            t.mDimFaces = mDimFaces;
            t.mDimYuv = mYuv;

            t.calcElementCount();
            return t;
        }
    }

}
