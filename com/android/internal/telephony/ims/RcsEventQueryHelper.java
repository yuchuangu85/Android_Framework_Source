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

import static android.provider.Telephony.RcsColumns.RcsEventTypes.ICON_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.NAME_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_JOINED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_LEFT_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.RCS_GROUP_THREAD_URI;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_URI;
import static android.provider.Telephony.RcsColumns.RcsParticipantEventColumns.ALIAS_CHANGE_EVENT_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsParticipantEventColumns.NEW_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.DESTINATION_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.EVENT_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.ICON_CHANGED_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NAME_CHANGED_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_ICON_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.PARTICIPANT_JOINED_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.PARTICIPANT_LEFT_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.SOURCE_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedEventHelper.RCS_EVENT_QUERY_URI;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.Rlog;
import android.telephony.ims.RcsEventDescriptor;
import android.telephony.ims.RcsEventQueryResultDescriptor;
import android.telephony.ims.RcsGroupThreadIconChangedEventDescriptor;
import android.telephony.ims.RcsGroupThreadNameChangedEventDescriptor;
import android.telephony.ims.RcsGroupThreadParticipantJoinedEventDescriptor;
import android.telephony.ims.RcsGroupThreadParticipantLeftEventDescriptor;
import android.telephony.ims.RcsParticipantAliasChangedEventDescriptor;
import android.telephony.ims.RcsQueryContinuationToken;

import java.util.ArrayList;
import java.util.List;

class RcsEventQueryHelper {
    private final ContentResolver mContentResolver;

    RcsEventQueryHelper(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    Uri getParticipantEventInsertionUri(int participantId) {
        return RCS_PARTICIPANT_URI.buildUpon().appendPath(Integer.toString(participantId))
                .appendPath(ALIAS_CHANGE_EVENT_URI_PART).build();
    }

    RcsEventQueryResultDescriptor performEventQuery(Bundle bundle) throws RemoteException {
        RcsQueryContinuationToken continuationToken = null;
        List<RcsEventDescriptor> eventList = new ArrayList<>();

        try (Cursor cursor = mContentResolver.query(RCS_EVENT_QUERY_URI, null, bundle, null)) {
            if (cursor == null) {
                throw new RemoteException("Event query failed, bundle: " + bundle);
            }

            while (cursor.moveToNext()) {
                int eventType = cursor.getInt(cursor.getColumnIndex(EVENT_TYPE_COLUMN));
                switch (eventType) {
                    case PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE:
                        eventList.add(createNewParticipantAliasChangedEvent(cursor));
                        break;
                    case PARTICIPANT_JOINED_EVENT_TYPE:
                        eventList.add(createNewParticipantJoinedEvent(cursor));
                        break;
                    case PARTICIPANT_LEFT_EVENT_TYPE:
                        eventList.add(createNewParticipantLeftEvent(cursor));
                        break;
                    case NAME_CHANGED_EVENT_TYPE:
                        eventList.add(createNewGroupNameChangedEvent(cursor));
                        break;
                    case ICON_CHANGED_EVENT_TYPE:
                        eventList.add(createNewGroupIconChangedEvent(cursor));
                        break;
                    default:
                        Rlog.e(RcsMessageStoreController.TAG,
                                "RcsEventQueryHelper: invalid event type: " + eventType);
                }
            }

            Bundle cursorExtras = cursor.getExtras();
            if (cursorExtras != null) {
                continuationToken = cursorExtras.getParcelable(QUERY_CONTINUATION_TOKEN);
            }
        }

        return new RcsEventQueryResultDescriptor(continuationToken, eventList);
    }

    int createGroupThreadEvent(int eventType, long timestamp, int threadId,
            int originationParticipantId, ContentValues eventSpecificValues)
            throws RemoteException {
        ContentValues values = new ContentValues(eventSpecificValues);
        values.put(EVENT_TYPE_COLUMN, eventType);
        values.put(TIMESTAMP_COLUMN, timestamp);
        values.put(SOURCE_PARTICIPANT_ID_COLUMN, originationParticipantId);

        Uri eventUri = RCS_GROUP_THREAD_URI.buildUpon().appendPath(
                Integer.toString(threadId)).appendPath(getPathForEventType(eventType)).build();
        Uri insertionUri = mContentResolver.insert(eventUri, values);

        int eventId = 0;
        if (insertionUri != null) {
            eventId = Integer.parseInt(insertionUri.getLastPathSegment());
        }

        if (eventId <= 0) {
            throw new RemoteException(
                "Could not create event with type: " + eventType + " on thread: " + threadId);
        }
        return eventId;
    }

    private String getPathForEventType(int eventType) throws RemoteException {
        switch (eventType) {
            case PARTICIPANT_JOINED_EVENT_TYPE:
                return PARTICIPANT_JOINED_URI_PART;
            case PARTICIPANT_LEFT_EVENT_TYPE:
                return PARTICIPANT_LEFT_URI_PART;
            case NAME_CHANGED_EVENT_TYPE:
                return NAME_CHANGED_URI_PART;
            case ICON_CHANGED_EVENT_TYPE:
                return ICON_CHANGED_URI_PART;
            default:
                throw new RemoteException("Event type unrecognized: " + eventType);
        }
    }

    private RcsGroupThreadIconChangedEventDescriptor createNewGroupIconChangedEvent(Cursor cursor) {
        String newIcon = cursor.getString(cursor.getColumnIndex(NEW_ICON_URI_COLUMN));

        return new RcsGroupThreadIconChangedEventDescriptor(
                cursor.getLong(cursor.getColumnIndex(TIMESTAMP_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(SOURCE_PARTICIPANT_ID_COLUMN)),
                newIcon == null ? null : Uri.parse(newIcon));
    }

    private RcsGroupThreadNameChangedEventDescriptor createNewGroupNameChangedEvent(Cursor cursor) {
        return new RcsGroupThreadNameChangedEventDescriptor(
                cursor.getLong(cursor.getColumnIndex(TIMESTAMP_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(SOURCE_PARTICIPANT_ID_COLUMN)),
                cursor.getString(cursor.getColumnIndex(NEW_NAME_COLUMN)));
    }

    private RcsGroupThreadParticipantLeftEventDescriptor
            createNewParticipantLeftEvent(Cursor cursor) {
        return new RcsGroupThreadParticipantLeftEventDescriptor(
                cursor.getLong(cursor.getColumnIndex(TIMESTAMP_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(SOURCE_PARTICIPANT_ID_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(DESTINATION_PARTICIPANT_ID_COLUMN)));
    }

    private RcsGroupThreadParticipantJoinedEventDescriptor
            createNewParticipantJoinedEvent(Cursor cursor) {
        return new RcsGroupThreadParticipantJoinedEventDescriptor(
                cursor.getLong(cursor.getColumnIndex(TIMESTAMP_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(SOURCE_PARTICIPANT_ID_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(DESTINATION_PARTICIPANT_ID_COLUMN)));
    }

    private RcsParticipantAliasChangedEventDescriptor
            createNewParticipantAliasChangedEvent(Cursor cursor) {
        return new RcsParticipantAliasChangedEventDescriptor(
                cursor.getLong(cursor.getColumnIndex(TIMESTAMP_COLUMN)),
                cursor.getInt(cursor.getColumnIndex(SOURCE_PARTICIPANT_ID_COLUMN)),
                cursor.getString(cursor.getColumnIndex(NEW_ALIAS_COLUMN)));
    }
}
