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


/**
 * Intrinsic for converting an Android YUV buffer to RGB.
 *
 * The input allocation is supplied in NV21 format as a U8
 * element type. The output is RGBA, the alpha channel will be
 * set to 255.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class ScriptIntrinsicYuvToRGB extends ScriptIntrinsic {
    private Allocation mInput;
    // API level for the intrinsic
    private static final int INTRINSIC_API_LEVEL = 19;

    ScriptIntrinsicYuvToRGB(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Create an intrinsic for converting YUV to RGB.
     *
     * Supported elements types are {@link Element#U8_4}
     *
     * @param rs The RenderScript context
     * @param e Element type for output
     *
     * @return ScriptIntrinsicYuvToRGB
     */
    public static ScriptIntrinsicYuvToRGB create(RenderScript rs, Element e) {
        // 6 comes from RS_SCRIPT_INTRINSIC_YUV_TO_RGB in rsDefines.h
        long id;
        boolean mUseIncSupp = rs.isUseNative() &&
                              android.os.Build.VERSION.SDK_INT < INTRINSIC_API_LEVEL;

        id = rs.nScriptIntrinsicCreate(6, e.getID(rs), mUseIncSupp);

        ScriptIntrinsicYuvToRGB si = new ScriptIntrinsicYuvToRGB(id, rs);
        si.setIncSupp(mUseIncSupp);
        return si;
    }


    /**
     * Set the input yuv allocation, must be {@link Element#U8}.
     *
     * @param ain The input allocation.
     */
    public void setInput(Allocation ain) {
        mInput = ain;
        setVar(0, ain);
    }

    /**
     * Convert the image to RGB.
     *
     * @param aout Output allocation. Must match creation element
     *             type.
     */
    public void forEach(Allocation aout) {
        forEach(0, (Allocation) null, aout, null);
    }

    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID() {
        return createKernelID(0, 2, null, null);
    }

    /**
     * Get a FieldID for the input field of this intrinsic.
     *
     * @return Script.FieldID The FieldID object.
     */
    public Script.FieldID getFieldID_Input() {
        return createFieldID(0, null);
    }
}
