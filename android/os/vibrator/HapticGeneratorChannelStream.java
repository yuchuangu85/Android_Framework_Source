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

package android.os.vibrator;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.os.VibrationEffect;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;

/**
 * A stream-like class for reading haptic PCM data generated from a {@link VibrationEffect}.
 *
 * <p>This stream is obtained from {@link HapticGeneratorSession#generateHapticChannelStream}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_HAPTIC_PCM_GENERATION)
@SystemApi
public final class HapticGeneratorChannelStream implements Closeable {

    private final IHapticChannelStream mStream;

    /** @hide */
    HapticGeneratorChannelStream(@NonNull IHapticChannelStream stream) {
        this.mStream = stream;
    }

    /**
     * Reads haptic PCM data from the stream into the given byte array.
     *
     * <p>This method blocks until data becomes available, the end of the stream is
     * reached, or an error occurs. It attempts to read as many bytes as the buffer's
     * capacity. The number of bytes read is returned by the method.
     *
     * <p>If the stream is closed while this method is blocking, it will throw a
     * {@link ClosedChannelException}.
     *
     * @param buffer The byte array into which the PCM data is read.
     * @return The total number of bytes read into the buffer, or -1 if the end of
     *         the stream has been reached.
     * @throws ClosedChannelException if this stream has been closed.
     * @throws IOException if an I/O error occurs during the read operation.
     */
    @RequiresPermission(android.Manifest.permission.USE_VIBRATOR_HAPTIC_GENERATOR)
    public int read(@NonNull byte[] buffer) throws IOException {
        try {
            int bytesRead = mStream.read(buffer);
            if (bytesRead >= 0 || bytesRead == IHapticChannelStream.READ_STATUS_EOF) {
                return bytesRead;
            }

            // Handle error codes
            switch (bytesRead) {
                case IHapticChannelStream.READ_STATUS_ERROR_CLOSED:
                    // The stream or session was already closed
                    throw new ClosedChannelException();
                case IHapticChannelStream.READ_STATUS_ERROR_IO:
                    // An I/O error occurred in the native layer
                    throw new IOException(
                            "An I/O error occurred while reading from the haptic generator stream"
                                    + ".");
                default:
                    throw new IOException(
                            "An unknown error occurred while reading from the haptic generator "
                                    + "stream: " + bytesRead);
            }
        } catch (RemoteException e) {
            throw new IOException("Haptic generator stream read failed", e);
        }
    }

    /**
     * Closes this stream and releases any underlying system resources associated with it.
     *
     * <p>Calling this method on a stream that has already been closed has no effect.
     *
     * <p>Once the stream is closed, any further {@link #read(byte[])} operations will
     * throw a {@link ClosedChannelException}.
     *
     * @throws IOException if an I/O error occurs during the close operation.
     */
    @Override
    @RequiresPermission(android.Manifest.permission.USE_VIBRATOR_HAPTIC_GENERATOR)
    public void close() throws IOException {
        try {
            mStream.close();
        } catch (RemoteException e) {
            throw new IOException("Haptic generator stream close failed", e);
        }
    }
}
