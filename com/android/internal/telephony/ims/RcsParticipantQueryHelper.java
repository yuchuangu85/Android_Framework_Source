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

import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_URI;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.ims.RcsParticipantQueryResultParcelable;
import android.telephony.ims.RcsQueryContinuationToken;

import java.util.ArrayList;
import java.util.List;

class RcsParticipantQueryHelper {
    private final ContentResolver mContentResolver;

    RcsParticipantQueryHelper(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    RcsParticipantQueryResultParcelable performParticipantQuery(Bundle bundle)
            throws RemoteException {
        RcsQueryContinuationToken continuationToken = null;
        List<Integer> participantList = new ArrayList<>();

        try (Cursor cursor = mContentResolver.query(RCS_PARTICIPANT_URI, null, bundle, null)) {
            if (cursor == null) {
                throw new RemoteException("Could not perform participant query, bundle: " + bundle);
            }

            while (cursor.moveToNext()) {
                participantList.add(
                        cursor.getInt(cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN)));
            }

            Bundle cursorExtras = cursor.getExtras();
            if (cursorExtras != null) {
                continuationToken = cursorExtras.getParcelable(QUERY_CONTINUATION_TOKEN);
            }
        }

        return new RcsParticipantQueryResultParcelable(continuationToken, participantList);
    }

    static Uri getUriForParticipant(int participantId) {
        return Uri.withAppendedPath(RCS_PARTICIPANT_URI, Integer.toString(participantId));
    }
}
