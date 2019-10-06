/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.telephony.ims;

import static android.provider.Telephony.RcsColumns.CONTENT_AND_AUTHORITY;
import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.RCS_1_TO_1_THREAD_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.CONTENT_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.CONTENT_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.DURATION_MILLIS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_SIZE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_TRANSFER_URI;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_TRANSFER_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.HEIGHT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.PREVIEW_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.PREVIEW_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SESSION_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SUCCESSFULLY_TRANSFERRED_BYTES;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.TRANSFER_STATUS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.WIDTH_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.RCS_GROUP_THREAD_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.INCOMING_MESSAGE_URI;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.INCOMING_MESSAGE_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.GLOBAL_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.ORIGINATION_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.STATUS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.SUB_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageDeliveryColumns.DELIVERY_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsOutgoingMessageColumns.OUTGOING_MESSAGE_URI;
import static android.provider.Telephony.RcsColumns.RcsOutgoingMessageColumns.OUTGOING_MESSAGE_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_INCOMING;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_OUTGOING;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.UNIFIED_MESSAGE_URI;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.ims.RcsFileTransferCreationParams;
import android.telephony.ims.RcsMessageCreationParams;
import android.telephony.ims.RcsMessageQueryResultParcelable;
import android.telephony.ims.RcsQueryContinuationToken;

import com.android.ims.RcsTypeIdPair;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class focused on querying RCS messages from the
 * {@link com.android.providers.telephony.RcsProvider}
 */
class RcsMessageQueryHelper {

    private final ContentResolver mContentResolver;

    RcsMessageQueryHelper(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    RcsMessageQueryResultParcelable performMessageQuery(Bundle bundle) throws RemoteException {
        RcsQueryContinuationToken continuationToken = null;
        List<RcsTypeIdPair> messageTypeIdPairs = new ArrayList<>();

        try (Cursor cursor = mContentResolver.query(UNIFIED_MESSAGE_URI, null, bundle, null)) {
            if (cursor == null) {
                throw new RemoteException("Could not perform message query, bundle: " + bundle);
            }

            while (cursor != null && cursor.moveToNext()) {
                boolean isIncoming = cursor.getInt(cursor.getColumnIndex(MESSAGE_TYPE_COLUMN))
                        == MESSAGE_TYPE_INCOMING;
                int messageId = cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN));

                messageTypeIdPairs.add(new RcsTypeIdPair(
                        isIncoming ? MESSAGE_TYPE_INCOMING : MESSAGE_TYPE_OUTGOING, messageId));
            }

            if (cursor != null) {
                Bundle cursorExtras = cursor.getExtras();
                if (cursorExtras != null) {
                    continuationToken =
                            cursorExtras.getParcelable(QUERY_CONTINUATION_TOKEN);
                }
            }
        }

        return new RcsMessageQueryResultParcelable(continuationToken, messageTypeIdPairs);
    }

    void createContentValuesForGenericMessage(ContentValues contentValues, int threadId,
            RcsMessageCreationParams rcsMessageCreationParams) {
        contentValues.put(GLOBAL_ID_COLUMN, rcsMessageCreationParams.getRcsMessageGlobalId());
        contentValues.put(SUB_ID_COLUMN, rcsMessageCreationParams.getSubId());
        contentValues.put(STATUS_COLUMN, rcsMessageCreationParams.getMessageStatus());
        contentValues.put(ORIGINATION_TIMESTAMP_COLUMN,
                rcsMessageCreationParams.getOriginationTimestamp());
        contentValues.put(RCS_THREAD_ID_COLUMN, threadId);
    }

    Uri getMessageInsertionUri(boolean isIncoming) {
        return isIncoming ? INCOMING_MESSAGE_URI : OUTGOING_MESSAGE_URI;
    }

    Uri getMessageDeletionUri(int messageId, boolean isIncoming, int rcsThreadId, boolean isGroup) {
        return CONTENT_AND_AUTHORITY.buildUpon().appendPath(
                isGroup ? RCS_GROUP_THREAD_URI_PART : RCS_1_TO_1_THREAD_URI_PART).appendPath(
                Integer.toString(rcsThreadId)).appendPath(
                isIncoming ? INCOMING_MESSAGE_URI_PART : OUTGOING_MESSAGE_URI_PART).appendPath(
                Integer.toString(messageId)).build();
    }

    Uri getMessageUpdateUri(int messageId, boolean isIncoming) {
        return getMessageInsertionUri(isIncoming).buildUpon().appendPath(
                Integer.toString(messageId)).build();
    }

    int[] getDeliveryParticipantsForMessage(int messageId) throws RemoteException {
        int[] participantIds;

        try (Cursor cursor = mContentResolver.query(getMessageDeliveryQueryUri(messageId), null,
                null, null)) {
            if (cursor == null) {
                throw new RemoteException(
                        "Could not query deliveries for message, messageId: " + messageId);
            }

            participantIds = new int[cursor.getCount()];

            for (int i = 0; cursor.moveToNext(); i++) {
                participantIds[i] = cursor.getInt(cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN));
            }
        }

        return participantIds;
    }

    Uri getMessageDeliveryUri(int messageId, int participantId) {
        return Uri.withAppendedPath(getMessageDeliveryQueryUri(messageId),
                Integer.toString(participantId));
    }

    long getLongValueFromDelivery(int messageId, int participantId,
            String columnName) throws RemoteException {
        try (Cursor cursor = mContentResolver.query(getMessageDeliveryUri(messageId, participantId),
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new RemoteException(
                        "Could not read delivery for message: " + messageId + ", participant: "
                                + participantId);
            }

            return cursor.getLong(cursor.getColumnIndex(columnName));
        }
    }

    private Uri getMessageDeliveryQueryUri(int messageId) {
        return getMessageInsertionUri(false).buildUpon().appendPath(
                Integer.toString(messageId)).appendPath(DELIVERY_URI_PART).build();
    }

    ContentValues getContentValuesForFileTransfer(
            RcsFileTransferCreationParams fileTransferCreationParameters) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(SESSION_ID_COLUMN,
                fileTransferCreationParameters.getRcsFileTransferSessionId());
        contentValues.put(CONTENT_URI_COLUMN,
                fileTransferCreationParameters.getContentUri().toString());
        contentValues.put(CONTENT_TYPE_COLUMN, fileTransferCreationParameters.getContentMimeType());
        contentValues.put(FILE_SIZE_COLUMN, fileTransferCreationParameters.getFileSize());
        contentValues.put(SUCCESSFULLY_TRANSFERRED_BYTES,
                fileTransferCreationParameters.getTransferOffset());
        contentValues.put(TRANSFER_STATUS_COLUMN,
                fileTransferCreationParameters.getFileTransferStatus());
        contentValues.put(WIDTH_COLUMN, fileTransferCreationParameters.getWidth());
        contentValues.put(HEIGHT_COLUMN, fileTransferCreationParameters.getHeight());
        contentValues.put(DURATION_MILLIS_COLUMN,
                fileTransferCreationParameters.getMediaDuration());
        contentValues.put(PREVIEW_URI_COLUMN,
                fileTransferCreationParameters.getPreviewUri().toString());
        contentValues.put(PREVIEW_TYPE_COLUMN, fileTransferCreationParameters.getPreviewMimeType());

        return contentValues;
    }

    Uri getFileTransferInsertionUri(int messageId) {
        return UNIFIED_MESSAGE_URI.buildUpon().appendPath(Integer.toString(messageId)).appendPath(
                FILE_TRANSFER_URI_PART).build();
    }

    Uri getFileTransferUpdateUri(int partId) {
        return Uri.withAppendedPath(FILE_TRANSFER_URI, Integer.toString(partId));
    }
}
