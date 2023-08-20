/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.mtp_perf;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class AppFusePerfTest {
    static final int SIZE = 10 * 1024 * 1024; // 10MB

    @Test
    public void testReadWriteFile() throws IOException {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final StorageManager storageManager = context.getSystemService(StorageManager.class);

        final byte[] bytes = new byte[SIZE];
        final int samples = 100;
        final double[] readTime = new double[samples];
        final double[] writeTime = new double[samples];

        for (int i = 0; i < samples; i++) {
            final ParcelFileDescriptor fd = storageManager.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY, new TestCallback());
            try (final ParcelFileDescriptor.AutoCloseInputStream stream =
                    new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                final long startTime = System.nanoTime();
                stream.read(bytes);
                readTime[i] = (System.nanoTime() - startTime) / 1000.0 / 1000.0;
            }
        }

        for (int i = 0; i < samples; i++) {
            final ParcelFileDescriptor fd = storageManager.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE,
                    new TestCallback());
            try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                    new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                final long startTime = System.nanoTime();
                stream.write(bytes);
                writeTime[i] = (System.nanoTime() - startTime) / 1000.0 / 1000.0;
            }
        }

        double readAverage = 0;
        double writeAverage = 0;
        double readSquaredAverage = 0;
        double writeSquaredAverage = 0;
        for (int i = 0; i < samples; i++) {
            readAverage += readTime[i];
            writeAverage += writeTime[i];
            readSquaredAverage += readTime[i] * readTime[i];
            writeSquaredAverage += writeTime[i] * writeTime[i];
        }

        readAverage /= samples;
        writeAverage /= samples;
        readSquaredAverage /= samples;
        writeSquaredAverage /= samples;

        final Bundle results = new Bundle();
        results.putDouble("readAverage", readAverage);
        results.putDouble("readStandardDeviation",
                Math.sqrt(readSquaredAverage - readAverage * readAverage));
        results.putDouble("writeAverage", writeAverage);
        results.putDouble("writeStandardDeviation",
                Math.sqrt(writeSquaredAverage - writeAverage * writeAverage));
        InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, results);
    }

    private static class TestCallback extends ProxyFileDescriptorCallback {
        @Override
        public long onGetSize() throws ErrnoException {
            return SIZE;
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            return size;
        }

        @Override
        public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            return size;
        }

        @Override
        public void onFsync() throws ErrnoException {}

        @Override
        public void onRelease() {}
    }
}
