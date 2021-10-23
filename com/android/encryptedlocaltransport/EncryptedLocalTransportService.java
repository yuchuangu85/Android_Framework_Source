/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.encryptedlocaltransport;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.android.localtransport.LocalTransportParameters;

public class EncryptedLocalTransportService extends Service {
    private static EncryptedLocalTransport sTransport = null;

    @Override
    public void onCreate() {
        if (sTransport == null) {
            LocalTransportParameters parameters =
                    new LocalTransportParameters(getMainThreadHandler(), getContentResolver());
            sTransport = new EncryptedLocalTransport(this, parameters);
        }
        sTransport.getParameters().start();
    }

    @Override
    public void onDestroy() {
        sTransport.getParameters().stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return sTransport.getBinder();
    }
}
