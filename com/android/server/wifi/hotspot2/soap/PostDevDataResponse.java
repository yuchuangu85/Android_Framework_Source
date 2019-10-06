/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2.soap;

import android.annotation.NonNull;
import android.util.Log;

import com.android.server.wifi.hotspot2.soap.command.SppCommand;

import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;

import java.util.Objects;

/**
 * Represents the sppPostDevDataResponse message sent by the server.
 * For the details, refer to A.3.2 section in Hotspot2.0 rel2 specification.
 */
public class PostDevDataResponse extends SppResponseMessage {
    private static final String TAG = "PasspointPostDevDataResponse";
    private static final int MAX_COMMAND_COUNT = 1;
    private final SppCommand mSppCommand;

    private PostDevDataResponse(@NonNull SoapObject response) throws IllegalArgumentException {
        super(response, MessageType.POST_DEV_DATA_RESPONSE);
        if (getStatus() == SppConstants.SppStatus.ERROR) {
            mSppCommand = null;
            return;
        }

        PropertyInfo propertyInfo = new PropertyInfo();
        response.getPropertyInfo(0, propertyInfo);
        // Get SPP(Subscription Provisioning Protocol) command from the original message.
        mSppCommand = SppCommand.createInstance(propertyInfo);
    }

    /**
     * create an instance of {@link PostDevDataResponse}
     *
     * @param response SOAP response message received from server.
     * @return Instance of {@link PostDevDataResponse}, {@code null} in any failure.
     */
    public static PostDevDataResponse createInstance(@NonNull SoapObject response) {
        if (response.getPropertyCount() != MAX_COMMAND_COUNT) {
            Log.e(TAG, "max command count exceeds: " + response.getPropertyCount());
            return null;
        }

        PostDevDataResponse postDevDataResponse;
        try {
            postDevDataResponse = new PostDevDataResponse(response);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "fails to create an Instance: " + e);
            return null;
        }

        return postDevDataResponse;
    }

    /**
     * Get a SppCommand for the current {@code PostDevDataResponse} instance.
     *
     * @return {@link SppCommand}
     */
    public SppCommand getSppCommand() {
        return mSppCommand;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSppCommand);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) return true;
        if (!(thatObject instanceof PostDevDataResponse)) return false;
        if (!super.equals(thatObject)) return false;
        PostDevDataResponse that = (PostDevDataResponse) thatObject;
        return Objects.equals(mSppCommand, that.getSppCommand());
    }

    @Override
    public String toString() {
        return super.toString() + ", commands " + mSppCommand;
    }
}
