/**
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

package android.telecom;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable version of {@link CallScreeningService.CallResponse} used to do IPC.
 * @hide
 */
public class ParcelableCallResponse implements Parcelable {
    private final boolean mShouldDisallowCall;
    private final boolean mShouldRejectCall;
    private final boolean mShouldSilenceCall;
    private final boolean mShouldSkipCallLog;
    private final boolean mShouldSkipNotification;
    private final boolean mShouldScreenCallViaAudioProcessing;

    private final int mCallComposerAttachmentsToShow;

    public ParcelableCallResponse(
            boolean shouldDisallowCall,
            boolean shouldRejectCall,
            boolean shouldSilenceCall,
            boolean shouldSkipCallLog,
            boolean shouldSkipNotification,
            boolean shouldScreenCallViaAudioProcessing,
            int callComposerAttachmentsToShow) {
        mShouldDisallowCall = shouldDisallowCall;
        mShouldRejectCall = shouldRejectCall;
        mShouldSilenceCall = shouldSilenceCall;
        mShouldSkipCallLog = shouldSkipCallLog;
        mShouldSkipNotification = shouldSkipNotification;
        mShouldScreenCallViaAudioProcessing = shouldScreenCallViaAudioProcessing;
        mCallComposerAttachmentsToShow = callComposerAttachmentsToShow;
    }

    protected ParcelableCallResponse(Parcel in) {
        mShouldDisallowCall = in.readBoolean();
        mShouldRejectCall = in.readBoolean();
        mShouldSilenceCall = in.readBoolean();
        mShouldSkipCallLog = in.readBoolean();
        mShouldSkipNotification = in.readBoolean();
        mShouldScreenCallViaAudioProcessing = in.readBoolean();
        mCallComposerAttachmentsToShow = in.readInt();
    }

    public CallScreeningService.CallResponse toCallResponse() {
        return new CallScreeningService.CallResponse.Builder()
                .setDisallowCall(mShouldDisallowCall)
                .setRejectCall(mShouldRejectCall)
                .setSilenceCall(mShouldSilenceCall)
                .setSkipCallLog(mShouldSkipCallLog)
                .setSkipNotification(mShouldSkipNotification)
                .setShouldScreenCallViaAudioProcessing(mShouldScreenCallViaAudioProcessing)
                .setCallComposerAttachmentsToShow(mCallComposerAttachmentsToShow)
                .build();
    }

    public boolean shouldDisallowCall() {
        return mShouldDisallowCall;
    }

    public boolean shouldRejectCall() {
        return mShouldRejectCall;
    }

    public boolean shouldSilenceCall() {
        return mShouldSilenceCall;
    }

    public boolean shouldSkipCallLog() {
        return mShouldSkipCallLog;
    }

    public boolean shouldSkipNotification() {
        return mShouldSkipNotification;
    }

    public boolean shouldScreenCallViaAudioProcessing() {
        return mShouldScreenCallViaAudioProcessing;
    }

    public int getCallComposerAttachmentsToShow() {
        return mCallComposerAttachmentsToShow;
    }

    public static final Creator<ParcelableCallResponse> CREATOR =
            new Creator<ParcelableCallResponse>() {
                @Override
                public ParcelableCallResponse createFromParcel(Parcel in) {
                    return new ParcelableCallResponse(in);
                }

                @Override
                public ParcelableCallResponse[] newArray(int size) {
                    return new ParcelableCallResponse[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mShouldDisallowCall);
        dest.writeBoolean(mShouldRejectCall);
        dest.writeBoolean(mShouldSilenceCall);
        dest.writeBoolean(mShouldSkipCallLog);
        dest.writeBoolean(mShouldSkipNotification);
        dest.writeBoolean(mShouldScreenCallViaAudioProcessing);
        dest.writeInt(mCallComposerAttachmentsToShow);
    }
}
