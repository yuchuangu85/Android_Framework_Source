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

package android.provider.mediaprocessingservice;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility class to serialize and deserialize a list of EmbeddingVector objects.
 *
 * @hide
 */
@SystemApi
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
public final class EmbeddingVectorSerializer {
    private EmbeddingVectorSerializer() {}

    /**
     * Serializes a list of {@link EmbeddingVector} objects into a byte array.
     * If the list is {@code null} or empty, this method returns {@code null}
     *
     * <p>The serialization format is as follows:
     * <ol>
     *     <li>{@code int}: Number of EmbeddingVector objects in the list.</li>
     *     <li>For each {@link EmbeddingVector}:
     *         <ol>
     *             <li>{@code int}: Length of the model signature UTF-8 byte array.</li>
     *             <li>{@code byte[]}: Model signature encoded in UTF-8.</li>
     *             <li>{@code int}: Number of float values in the embedding vector.</li>
     *             <li>{@code float[]}: The float values of the embedding vector.</li>
     *         </ol>
     *     </li>
     * </ol>
     *
     * @param embeddingVectorList The list of {@link EmbeddingVector} objects to serialize.
     * @return A byte array containing the serialized data, or {@code null}
     *
     * @throws IOException if an I/O error occurs during serialization.
     */
    @NonNull
    public static byte[] serializeList(@NonNull List<EmbeddingVector> embeddingVectorList)
            throws IOException {
        if (embeddingVectorList.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(outputStream)) {
            dos.writeInt(embeddingVectorList.size());
            for (EmbeddingVector vec : embeddingVectorList) {
                Objects.requireNonNull(vec);
                byte[] signatureBytes = vec.getModelSignature().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(signatureBytes.length);
                dos.write(signatureBytes);

                float[] values = vec.getValues();
                dos.writeInt(values.length);
                for (float f : values) {
                    dos.writeFloat(f);
                }
            }
        }

        return outputStream.toByteArray();
    }

    /**
     * Deserializes a byte array back into a list of {@link EmbeddingVector} objects.
     * <p>
     * If the byte array is {@code null} or empty, an empty list is returned.
     * This method expects the byte array to be in the format produced by
     * {@link #serializeList(List)}.
     *
     * @param bytes The byte array containing the serialized data.
     * @return A list of {@link EmbeddingVector} objects.
     * @throws IOException if an I/O error occurs, or if the data format is invalid
     */
    @NonNull
    public static List<EmbeddingVector> deserializeList(@NonNull byte[] bytes) throws IOException {
        if (bytes.length == 0) {
            return new ArrayList<>();
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        try (DataInputStream dis = new DataInputStream(inputStream)) {
            int listSize = dis.readInt();
            if (listSize < 0) {
                throw new IOException("Invalid list size: " + listSize);
            }
            List<EmbeddingVector> embeddingVectorList = new ArrayList<>(listSize);
            for (int i = 0; i < listSize; i++) {
                int signatureLen = dis.readInt();
                if (signatureLen < 0) {
                    throw new IOException("Invalid signature length: " + signatureLen);
                }
                byte[] signatureBytes = new byte[signatureLen];
                dis.readFully(signatureBytes);
                String modelSignature = new String(signatureBytes, StandardCharsets.UTF_8);

                int valuesCount = dis.readInt();
                if (valuesCount < 0) {
                    throw new IOException("Invalid values count: " + valuesCount);
                }
                float[] values = new float[valuesCount];
                for (int j = 0; j < valuesCount; j++) {
                    values[j] = dis.readFloat();
                }
                embeddingVectorList.add(new EmbeddingVector(values, modelSignature));
            }

            return embeddingVectorList;
        }
    }
}
