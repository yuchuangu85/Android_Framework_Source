/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.util.Log;

/**
 * Intrinsic for applying a per-channel lookup table. Each
 * channel of the input has an independant lookup table. The
 * tables are 256 entries in size and can cover the full value
 * range of {@link Element#U8_4}.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 **/
@Deprecated
public class ScriptIntrinsicLUT extends ScriptIntrinsic {
    private final Matrix4f mMatrix = new Matrix4f();
    private Allocation mTables;
    private final byte mCache[] = new byte[1024];
    private boolean mDirty = true;
    // API level for the intrinsic
    private static final int INTRINSIC_API_LEVEL = 19;

    protected ScriptIntrinsicLUT(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are {@link Element#U8_4}
     *
     * The defaults tables are identity.
     *
     * @param rs The RenderScript context
     * @param e Element type for intputs and outputs
     *
     * @return ScriptIntrinsicLUT
     */
    public static ScriptIntrinsicLUT create(RenderScript rs, Element e) {
        long id;
        boolean mUseIncSupp = rs.isUseNative() &&
                              android.os.Build.VERSION.SDK_INT < INTRINSIC_API_LEVEL;

        id = rs.nScriptIntrinsicCreate(3, e.getID(rs), mUseIncSupp);

        ScriptIntrinsicLUT si = new ScriptIntrinsicLUT(id, rs);
        si.setIncSupp(mUseIncSupp);
        si.mTables = Allocation.createSized(rs, Element.U8(rs), 1024);
        for (int ct=0; ct < 256; ct++) {
            si.mCache[ct] = (byte)ct;
            si.mCache[ct + 256] = (byte)ct;
            si.mCache[ct + 512] = (byte)ct;
            si.mCache[ct + 768] = (byte)ct;
        }
        si.setVar(0, si.mTables);
        return si;
    }


    private void validate(int index, int value) {
        if (index < 0 || index > 255) {
            throw new RSIllegalArgumentException("Index out of range (0-255).");
        }
        if (value < 0 || value > 255) {
            throw new RSIllegalArgumentException("Value out of range (0-255).");
        }
    }

    /**
     * Set an entry in the red channel lookup table
     *
     * @param index Must be 0-255
     * @param value Must be 0-255
     */
    public void setRed(int index, int value) {
        validate(index, value);
        mCache[index] = (byte)value;
        mDirty = true;
    }

    /**
     * Set an entry in the green channel lookup table
     *
     * @param index Must be 0-255
     * @param value Must be 0-255
     */
    public void setGreen(int index, int value) {
        validate(index, value);
        mCache[index+256] = (byte)value;
        mDirty = true;
    }

    /**
     * Set an entry in the blue channel lookup table
     *
     * @param index Must be 0-255
     * @param value Must be 0-255
     */
    public void setBlue(int index, int value) {
        validate(index, value);
        mCache[index+512] = (byte)value;
        mDirty = true;
    }

    /**
     * Set an entry in the alpha channel lookup table
     *
     * @param index Must be 0-255
     * @param value Must be 0-255
     */
    public void setAlpha(int index, int value) {
        validate(index, value);
        mCache[index+768] = (byte)value;
        mDirty = true;
    }


    /**
     * Invoke the kernel and apply the lookup to each cell of ain
     * and copy to aout.
     *
     * @param ain Input allocation
     * @param aout Output allocation
     */
    public void forEach(Allocation ain, Allocation aout) {
        if (mDirty) {
            mDirty = false;
            mTables.copyFromUnchecked(mCache);
        }
        forEach(0, ain, aout, null);
    }

    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID() {
        return createKernelID(0, 3, null, null);
    }
}

