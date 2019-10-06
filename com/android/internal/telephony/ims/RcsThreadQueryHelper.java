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
package com.android.internal.telephony.ims;

import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.RCS_1_TO_1_THREAD_URI;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_ICON_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.RCS_GROUP_THREAD_URI;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_URI;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_1_TO_1;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_GROUP;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.ims.RcsQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryResultParcelable;

import com.android.ims.RcsTypeIdPair;

import java.util.ArrayList;
import java.util.List;

// TODO optimize considering returned threads do not contain query information

/**
 * A helper class focused on querying RCS threads from the
 * {@link com.android.providers.telephony.RcsProvider}
 */
class RcsThreadQueryHelper {
    private static final int THREAD_ID_INDEX_IN_INSERTION_URI = 1;

    private final ContentResolver mContentResolver;
    private final RcsParticipantQueryHelper mParticipantQueryHelper;

    RcsThreadQueryHelper(ContentResolver contentResolver,
            RcsParticipantQueryHelper participantQueryHelper) {
        mContentResolver = contentResolver;
        mParticipantQueryHelper = participantQueryHelper;
    }

    RcsThreadQueryResultParcelable performThreadQuery(Bundle bundle) throws RemoteException {
        RcsQueryContinuationToken continuationToken = null;
        List<RcsTypeIdPair> rcsThreadIdList = new ArrayList<>();
        try (Cursor cursor = mContentResolver.query(RCS_THREAD_URI, null, bundle, null)) {
            if (cursor == null) {
                throw new RemoteException("Could not perform thread query, bundle: " + bundle);
            }

            while (cursor.moveToNext()) {
                boolean isGroup = cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))
                        == THREAD_TYPE_GROUP;

                if (isGroup) {
                    int threadId = cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN));
                    rcsThreadIdList.add(new RcsTypeIdPair(THREAD_TYPE_GROUP, threadId));
                } else {
                    int threadId = cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN));
                    rcsThreadIdList.add(new RcsTypeIdPair(THREAD_TYPE_1_TO_1, threadId));
                }
            }

            // If there is a continuation token, add it to the query result.
            Bundle cursorExtras = cursor.getExtras();
            if (cursorExtras != null) {
                continuationToken = cursorExtras.getParcelable(QUERY_CONTINUATION_TOKEN);
            }
        }
        return new RcsThreadQueryResultParcelable(continuationToken, rcsThreadIdList);
    }

    int create1To1Thread(int participantId) throws RemoteException {
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(RCS_PARTICIPANT_ID_COLUMN, participantId);
        Uri insertionUri = mContentResolver.insert(RCS_1_TO_1_THREAD_URI, contentValues);

        if (insertionUri == null) {
            throw new RemoteException("Rcs1To1Thread creation failed");
        }

        String threadIdAsString = insertionUri.getLastPathSegment();
        int threadId = Integer.parseInt(threadIdAsString);

        if (threadId <= 0) {
            throw new RemoteException("Rcs1To1Thread creation failed");
        }

        return threadId;
    }

    int createGroupThread(String groupName, Uri groupIcon) throws RemoteException {
        // Create the group
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(GROUP_NAME_COLUMN, groupName);
        if (groupIcon != null) {
            contentValues.put(GROUP_ICON_COLUMN, groupIcon.toString());
        }

        Uri groupUri = mContentResolver.insert(RCS_GROUP_THREAD_URI,
                contentValues);
        if (groupUri == null) {
            throw new RemoteException("RcsGroupThread creation failed");
        }

        // get the thread id from group URI
        String threadIdAsString = groupUri.getPathSegments().get(THREAD_ID_INDEX_IN_INSERTION_URI);
        int threadId = Integer.parseInt(threadIdAsString);

        return threadId;
    }

    static Uri get1To1ThreadUri(int rcsThreadId) {
        return Uri.withAppendedPath(RCS_1_TO_1_THREAD_URI, Integer.toString(rcsThreadId));
    }

    static Uri getGroupThreadUri(int rcsThreadId) {
        return Uri.withAppendedPath(RCS_GROUP_THREAD_URI, Integer.toString(rcsThreadId));
    }

    static Uri getAllParticipantsInThreadUri(int rcsThreadId) {
        return RCS_GROUP_THREAD_URI.buildUpon().appendPath(Integer.toString(rcsThreadId))
                .appendPath(RCS_PARTICIPANT_URI_PART).build();
    }

    static Uri getParticipantInThreadUri(int rcsThreadId, int participantId) {
        return RCS_GROUP_THREAD_URI.buildUpon().appendPath(Integer.toString(rcsThreadId))
                .appendPath(RCS_PARTICIPANT_URI_PART).appendPath(Integer.toString(
                        participantId)).build();
    }
}
