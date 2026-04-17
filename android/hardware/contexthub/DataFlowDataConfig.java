/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Encapsulates information about the data format transferred over a data flow.
 *
 * <p>This includes data element size and alignment requirements, as well as whether elements in the
 * data flow are of variable or fixed size.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_FMCQ_API)
public final class DataFlowDataConfig {
    /** Format for data flows with elements of fixed size and alignment. */
    public static final int FORMAT_FIXED_SIZE = 0;

    /** Format for data flows with elements of variable size with no alignment requirements. */
    public static final int FORMAT_VARIABLE_SIZE = 1;

    /** Format for data flows with elements of variable size with alignment requirements. */
    public static final int FORMAT_VARIABLE_SIZE_ALIGNED = 2;

    /** Element size indicating variable size element. */
    private static final int ELEMENT_SIZE_VARIABLE = -1;

    /**
     * Format of data sent over a data flow.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "FORMAT_",
            value = {FORMAT_FIXED_SIZE, FORMAT_VARIABLE_SIZE, FORMAT_VARIABLE_SIZE_ALIGNED})
    /* package */ @interface DataFormat {}

    private final @DataFormat int mFormat;
    private final int mElementSize;
    private final int mElementAlignment;

    /**
     * Creates a new fixed-size element data flow configuration.
     *
     * @param elementSize The size in bytes of each data element.
     * @param elementAlignment The power-of-2 alignment in bytes of each data element.
     * @return The data flow configuration.
     */
    @NonNull
    public static DataFlowDataConfig createFixedSize(
            @IntRange(from = 1, to = Short.MAX_VALUE) int elementSize,
            @IntRange(from = 1, to = Short.MAX_VALUE) int elementAlignment) {
        if (elementSize < 1) {
            throw new IllegalArgumentException("Element size must be greater than 0.");
        }
        if (elementAlignment < 1 || (elementAlignment & (elementAlignment - 1)) != 0) {
            throw new IllegalArgumentException("Element alignment must be a power of 2.");
        }
        return new DataFlowDataConfig(FORMAT_FIXED_SIZE, elementSize, elementAlignment);
    }

    /** Creates a configuration for an unaligned variable-size element data flow. */
    @NonNull
    public static DataFlowDataConfig createVariableSize() {
        return new DataFlowDataConfig(
                FORMAT_VARIABLE_SIZE, ELEMENT_SIZE_VARIABLE, /* elementAlignment= */ 1);
    }

    /**
     * Creates a new aligned variable-size element data flow configuration.
     *
     * @param elementAlignment The power-of-2 alignment in bytes of each data element.
     * @return The data flow configuration.
     */
    @NonNull
    public static DataFlowDataConfig createVariableSizeAligned(
            @IntRange(from = 1, to = Short.MAX_VALUE) int elementAlignment) {
        if (elementAlignment < 1 || (elementAlignment & (elementAlignment - 1)) != 0) {
            throw new IllegalArgumentException("Element alignment must be a power of 2.");
        }
        return new DataFlowDataConfig(
                FORMAT_VARIABLE_SIZE_ALIGNED, ELEMENT_SIZE_VARIABLE, elementAlignment);
    }

    /**
     * Returns one of {@link #FORMAT_FIXED_SIZE}, {@link #FORMAT_VARIABLE_SIZE}, or {@link
     * #FORMAT_VARIABLE_SIZE_ALIGNED}
     */
    public @DataFormat int getFormat() {
        return mFormat;
    }

    /**
     * Returns the size of each data element in bytes.
     *
     * @throws IllegalStateException if the data flow is not a fixed-size element data flow.
     */
    public int getElementSize() {
        if (mFormat != FORMAT_FIXED_SIZE) {
            throw new IllegalStateException("Data flow is not a fixed-size element data flow.");
        }
        return mElementSize;
    }

    /** Returns the power-of-2 alignment in bytes of each data element. */
    public int getElementAlignment() {
        return mElementAlignment;
    }

    private DataFlowDataConfig(@DataFormat int format, int elementSize, int elementAlignment) {
        mFormat = format;
        mElementSize = elementSize;
        mElementAlignment = elementAlignment;
    }
}
