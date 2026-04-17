/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.adservices.common;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;

import com.android.adservices.LoggerFactory;
import com.android.adservices.flags.Flags;

import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Utility class used to set up the read and write pipes for the usage of reading pointers from
 * shared memory.
 *
 * @hide The Rubidium (Rb) Relevance APIs, including those in android.adservices.common, are being
 *     deprecated. Relevance APIs have no direct replacement. Developers should stop using them, as
 *     calls will be rejected in future Android releases. Please refer to official Privacy Sandbox
 *     documentation for deprecation and roadmap details:
 *     https://privacysandbox.com/news/update-on-plans-for-privacy-sandbox-technologies/
 */
@FlaggedApi(Flags.FLAG_ADSERVICES_DEPRECATED)
public class AssetFileDescriptorUtil {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();

    private AssetFileDescriptorUtil() {}

    /**
     * Creates a read and write pipe, writes the data in {@code buffer} into the write end, and
     * returns the read end in the form of a {@link AssetFileDescriptor}.
     *
     * @throws IOException if an exception is encountered while creating or writing to the pipe
     */
    public static AssetFileDescriptor setupAssetFileDescriptorResponse(
            @NonNull byte[] buffer, @NonNull ExecutorService executorService) throws IOException {
        Objects.requireNonNull(buffer);
        Objects.requireNonNull(executorService);

        ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor writeDescriptor = descriptors[1];

        executorService.execute(
                () -> {
                    try (FileOutputStream outputStream =
                            new FileOutputStream(writeDescriptor.getFileDescriptor())) {
                        outputStream.write(buffer);
                    } catch (IOException e) {
                        sLogger.e(
                                e, "Encountered IO Exception while writing byte array to stream.");
                    }
                });
        return new AssetFileDescriptor(descriptors[0], 0, buffer.length);
    }

    /**
     * Reads the content the {@link AssetFileDescriptor} points to into a buffer and returns the
     * number of bytes read.
     *
     * @throws IOException if an exception is encountered while reading the content.
     */
    public static byte[] readAssetFileDescriptorIntoBuffer(
            @NonNull AssetFileDescriptor assetFileDescriptor) throws IOException {
        Objects.requireNonNull(assetFileDescriptor);

        byte[] result = new byte[(int) assetFileDescriptor.getLength()];

        try (DataInputStream inputStream =
                new DataInputStream(assetFileDescriptor.createInputStream())) {
            inputStream.readFully(result);
        }

        return result;
    }
}
