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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Represents data which may be written to a data flow via a {@link DataFlowSource} or read from a
 * {@link DataFlowSink}. The contents of a {@link DataFlowData} depend on the data flow's {@link
 * DataFlowDataConfig}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_FMCQ_API)
public final class DataFlowData {
    /**
     * One or more buffers containing the data. The order of the buffers is the order of the data as
     * it should be written or was read. For a fixed-size element data flow, each buffer contains
     * one or more elements and is a multiple of the element size. For a variable-size element data
     * flow, each buffer contains a single element, which may be size 0.
     */
    private final List<ByteBuffer> mBuffers;

    /**
     * Constructs a new {@link DataFlowData} from a single buffer.
     *
     * @param buffer The data to be written or that was read.
     * @param dataConfig The configuration of elements in the data flow.
     * @return A {@link DataFlowData} object encapsulating the data.
     * @throws IllegalArgumentException if the data flow is a fixed-size element data flow and the
     *     buffer is not a multiple of the element size or is empty.
     */
    public DataFlowData(@NonNull ByteBuffer buffer, @NonNull DataFlowDataConfig dataConfig) {
        if (dataConfig.getFormat() == DataFlowDataConfig.FORMAT_FIXED_SIZE) {
            checkFixedSizeElementBuffer(buffer, dataConfig.getElementSize());
        }
        mBuffers = List.of(buffer);
    }

    /**
     * Creates a new {@link DataFlowData} for writing a series of buffers to a data flow.
     *
     * <p>For fixed-size element data flows, each buffer must be a multiple of the element size and
     * contain one or more elements. For variable-size element data flows, each buffer is treated as
     * an individual element.
     *
     * @param buffers The buffers containing data in the order it should be written
     * @param dataConfig The configuration of elements in the data flow.
     * @return A {@link DataFlowData} object encapsulating the data.
     * @throws IllegalArgumentException if the data flow is a fixed-size element data flow and any
     *     of the buffers either are not a multiple of the element size or have a capacity of 0.
     */
    public DataFlowData(
            @NonNull @Size(min = 1) List<ByteBuffer> buffers,
            @NonNull DataFlowDataConfig dataConfig) {
        if (dataConfig.getFormat() == DataFlowDataConfig.FORMAT_FIXED_SIZE) {
            for (ByteBuffer buffer : buffers) {
                checkFixedSizeElementBuffer(buffer, dataConfig.getElementSize());
            }
        }
        mBuffers = buffers;
    }

    /**
     * Returns the buffers containing the data.
     *
     * @return The buffers containing the data.
     */
    @NonNull
    public List<ByteBuffer> getBuffers() {
        return mBuffers;
    }

    private static void checkFixedSizeElementBuffer(
            @NonNull ByteBuffer buffer, @IntRange(from = 1) int elementSize) {
        if (buffer.capacity() % elementSize != 0) {
            throw new IllegalArgumentException(
                    "Buffer size must be a multiple of the element size.");
        } else if (buffer.capacity() == 0) {
            throw new IllegalArgumentException("Buffer capacity must be non-zero.");
        }
    }
}
