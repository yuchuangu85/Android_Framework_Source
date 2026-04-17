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

package android.hardware.serial;

import android.os.OutcomeReceiver;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;

/**
 * The {@link OutcomeReceiver} used to provide backward compatibility to requests sent through
 * {@link android.hardware.SerialManager}.
 *
 * @hide
 */
class CompatibleOutcomeReceiver implements OutcomeReceiver<SerialPortResponse, Exception> {
    private final CountDownLatch mLatch = new CountDownLatch(1);
    private SerialPortResponse mResponse;
    private Exception mException;

    SerialPortResponse waitForResult() throws Exception {
        try {
            mLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (mResponse != null) {
            return mResponse;
        }
        throw mException;
    }

    @Override
    public void onResult(SerialPortResponse result) {
        mResponse = result;
        mLatch.countDown();
    }

    @Override
    public void onError(@NonNull Exception error) {
        mException = error;
        mLatch.countDown();
    }
}
