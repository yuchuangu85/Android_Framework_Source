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

import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.FALLBACK_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.RCS_1_TO_1_THREAD_URI;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.ICON_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.NAME_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_JOINED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_LEFT_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.CONTENT_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.CONTENT_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.DURATION_MILLIS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_SIZE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_TRANSFER_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_TRANSFER_URI;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.HEIGHT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.PREVIEW_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.PREVIEW_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SESSION_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SUCCESSFULLY_TRANSFERRED_BYTES;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.TRANSFER_STATUS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.WIDTH_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.CONFERENCE_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_ICON_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.OWNER_PARTICIPANT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.RCS_GROUP_THREAD_URI;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.ARRIVAL_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.INCOMING_MESSAGE_URI;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.SENDER_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.GLOBAL_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.LATITUDE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.LONGITUDE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_TEXT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.ORIGINATION_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.STATUS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.SUB_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageDeliveryColumns.DELIVERED_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.CANONICAL_ADDRESS_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_URI;
import static android.provider.Telephony.RcsColumns.RcsParticipantEventColumns.NEW_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.DESTINATION_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_ICON_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.SOURCE_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_GROUP;
import static android.provider.Telephony.RcsColumns.TRANSACTION_FAILED;
import static android.telephony.ims.RcsEventQueryParams.EVENT_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsMessageQueryParams.MESSAGE_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsParticipantQueryParams.PARTICIPANT_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;
import static android.telephony.ims.RcsThreadQueryParams.THREAD_QUERY_PARAMETERS_KEY;

import static com.android.internal.telephony.ims.RcsMessageStoreUtil.getMessageTableUri;
import static com.android.internal.telephony.ims.RcsParticipantQueryHelper.getUriForParticipant;
import static com.android.internal.telephony.ims.RcsThreadQueryHelper.get1To1ThreadUri;
import static com.android.internal.telephony.ims.RcsThreadQueryHelper.getAllParticipantsInThreadUri;
import static com.android.internal.telephony.ims.RcsThreadQueryHelper.getGroupThreadUri;
import static com.android.internal.telephony.ims.RcsThreadQueryHelper.getParticipantInThreadUri;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Telephony;
import android.telephony.Rlog;
import android.telephony.ims.RcsEventQueryParams;
import android.telephony.ims.RcsEventQueryResultDescriptor;
import android.telephony.ims.RcsFileTransferCreationParams;
import android.telephony.ims.RcsIncomingMessageCreationParams;
import android.telephony.ims.RcsMessageQueryParams;
import android.telephony.ims.RcsMessageQueryResultParcelable;
import android.telephony.ims.RcsMessageSnippet;
import android.telephony.ims.RcsMessageStore;
import android.telephony.ims.RcsOutgoingMessageCreationParams;
import android.telephony.ims.RcsParticipantQueryParams;
import android.telephony.ims.RcsParticipantQueryResultParcelable;
import android.telephony.ims.RcsQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryParams;
import android.telephony.ims.RcsThreadQueryResultParcelable;
import android.telephony.ims.aidl.IRcs;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Backing implementation of {@link RcsMessageStore}.
 */
public class RcsMessageStoreController extends IRcs.Stub {

    static final String TAG = "RcsMsgStoreController";
    private static final String RCS_SERVICE_NAME = "ircs";

    private static RcsMessageStoreController sInstance;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final RcsParticipantQueryHelper mParticipantQueryHelper;
    private final RcsMessageQueryHelper mMessageQueryHelper;
    private final RcsEventQueryHelper mEventQueryHelper;
    private final RcsThreadQueryHelper mThreadQueryHelper;
    private final RcsMessageStoreUtil mMessageStoreUtil;

    /**
     * Initialize the instance. Should only be called once.
     */
    public static RcsMessageStoreController init(Context context) {
        synchronized (RcsMessageStoreController.class) {
            if (sInstance == null) {
                sInstance = new RcsMessageStoreController(context);
                if (ServiceManager.getService(RCS_SERVICE_NAME) == null) {
                    ServiceManager.addService(RCS_SERVICE_NAME, sInstance);
                }
            } else {
                Rlog.e(TAG, "init() called multiple times! sInstance = " + sInstance);
            }
        }
        return sInstance;
    }

    interface ThrowingSupplier<T> {
        T get() throws RemoteException;
    }

    interface ThrowingRunnable {
        void run() throws RemoteException;
    }

    private void performWriteOperation(String callingPackage, ThrowingRunnable fn) {
        RcsPermissions.checkWritePermissions(mContext, callingPackage);

        try {
            fn.run();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T performCreateOperation(String callingPackage, ThrowingSupplier<T> fn) {
        RcsPermissions.checkWritePermissions(mContext, callingPackage);

        try {
            return fn.get();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T performReadOperation(String callingPackage, ThrowingSupplier<T> fn) {
        RcsPermissions.checkReadPermissions(mContext, callingPackage);

        try {
            return fn.get();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    public RcsMessageStoreController(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mParticipantQueryHelper = new RcsParticipantQueryHelper(mContentResolver);
        mMessageQueryHelper = new RcsMessageQueryHelper(mContentResolver);
        mThreadQueryHelper = new RcsThreadQueryHelper(mContentResolver, mParticipantQueryHelper);
        mEventQueryHelper = new RcsEventQueryHelper(mContentResolver);
        mMessageStoreUtil = new RcsMessageStoreUtil(mContentResolver);
    }

    @Override
    public boolean deleteThread(int threadId, int threadType, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            int deletionCount = mContentResolver.delete(
                    threadType == THREAD_TYPE_GROUP ? RCS_GROUP_THREAD_URI : RCS_1_TO_1_THREAD_URI,
                    RCS_THREAD_ID_COLUMN + "=?",
                    new String[]{Integer.toString(threadId)});

            return deletionCount > 0;
        });
    }

    @Override
    public RcsMessageSnippet getMessageSnippet(int threadId, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            // TODO - implement
            return null;
        });
    }

    @Override
    public RcsThreadQueryResultParcelable getRcsThreads(RcsThreadQueryParams queryParameters,
            String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(THREAD_QUERY_PARAMETERS_KEY, queryParameters);
            return mThreadQueryHelper.performThreadQuery(bundle);
        });
    }

    @Override
    public RcsThreadQueryResultParcelable getRcsThreadsWithToken(
            RcsQueryContinuationToken continuationToken, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
            return mThreadQueryHelper.performThreadQuery(bundle);
        });
    }

    @Override
    public RcsParticipantQueryResultParcelable getParticipants(
            RcsParticipantQueryParams queryParameters, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(PARTICIPANT_QUERY_PARAMETERS_KEY, queryParameters);
            return mParticipantQueryHelper.performParticipantQuery(bundle);
        });
    }

    @Override
    public RcsParticipantQueryResultParcelable getParticipantsWithToken(
            RcsQueryContinuationToken continuationToken, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
            return mParticipantQueryHelper.performParticipantQuery(bundle);
        });
    }

    @Override
    public RcsMessageQueryResultParcelable getMessages(RcsMessageQueryParams queryParams,
            String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(MESSAGE_QUERY_PARAMETERS_KEY, queryParams);
            return mMessageQueryHelper.performMessageQuery(bundle);
        });
    }

    @Override
    public RcsMessageQueryResultParcelable getMessagesWithToken(
            RcsQueryContinuationToken continuationToken, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
            return mMessageQueryHelper.performMessageQuery(bundle);
        });
    }

    @Override
    public RcsEventQueryResultDescriptor getEvents(RcsEventQueryParams queryParameters,
            String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(EVENT_QUERY_PARAMETERS_KEY, queryParameters);
            return mEventQueryHelper.performEventQuery(bundle);
        });
    }

    @Override
    public RcsEventQueryResultDescriptor getEventsWithToken(
            RcsQueryContinuationToken continuationToken, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
            return mEventQueryHelper.performEventQuery(bundle);
        });
    }

    @Override
    public int createRcs1To1Thread(int recipientId, String callingPackage) {
        return performCreateOperation(callingPackage,
                () -> mThreadQueryHelper.create1To1Thread(recipientId));
    }

    @Override
    public int createGroupThread(int[] participantIds, String groupName, Uri groupIcon,
            String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            int groupThreadId = mThreadQueryHelper.createGroupThread(groupName, groupIcon);
            if (groupThreadId <= 0) {
                throw new RemoteException("Could not create RcsGroupThread.");
            }

            // Insert participants
            // TODO(123718879): Instead of adding participants here, add them under RcsProvider
            //  under one transaction
            if (participantIds != null) {
                for (int participantId : participantIds) {
                    addParticipantToGroupThread(groupThreadId, participantId, callingPackage);
                }
            }

            return groupThreadId;
        });
    }

    /**
     * TODO(109759350) Instead of sending the update query directly to RcsProvider, this function
     * orchestrates between RcsProvider and MmsSmsProvider. This is because we are not fully decided
     * on whether we should have RCS storage in a separate database file.
     */
    @Override
    public int createRcsParticipant(String canonicalAddress, String alias, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues contentValues = new ContentValues();

            long canonicalAddressId = Telephony.RcsColumns.RcsCanonicalAddressHelper
                    .getOrCreateCanonicalAddressId(mContentResolver, canonicalAddress);

            if (canonicalAddressId == TRANSACTION_FAILED) {
                throw new RemoteException("Could not create or make canonical address entry");
            }

            contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, canonicalAddressId);
            contentValues.put(RCS_ALIAS_COLUMN, alias);

            // TODO (123719857) - Disallow creation of duplicate participants
            Uri newParticipantUri = mContentResolver.insert(RCS_PARTICIPANT_URI, contentValues);
            int newParticipantRowId;

            if (newParticipantUri == null) {
                throw new RemoteException("Error inserting new participant into RcsProvider");
            }

            try {
                newParticipantRowId = Integer.parseInt(newParticipantUri.getLastPathSegment());
            } catch (NumberFormatException e) {
                throw new RemoteException(
                        "Uri returned after creating a participant is malformed: "
                                + newParticipantUri);
            }

            return newParticipantRowId;
        });
    }

    @Override
    public String getRcsParticipantCanonicalAddress(int participantId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(RCS_PARTICIPANT_URI,
                        Telephony.CanonicalAddressesColumns.ADDRESS, RCS_PARTICIPANT_ID_COLUMN,
                        participantId));
    }

    @Override
    public String getRcsParticipantAlias(int participantId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(RCS_PARTICIPANT_URI,
                        RCS_ALIAS_COLUMN,
                        RCS_PARTICIPANT_ID_COLUMN, participantId));
    }

    @Override
    public void setRcsParticipantAlias(int id, String alias, String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mMessageStoreUtil.updateValueOfProviderUri(getUriForParticipant(id),
                        RCS_ALIAS_COLUMN,
                        alias, "Could not update RCS participant alias"));
    }

    @Override
    public String getRcsParticipantContactId(int participantId, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            // TODO - implement
            return null;
        });
    }

    @Override
    public void setRcsParticipantContactId(int participantId, String contactId,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> {
            // TODO - implement
        });
    }

    @Override
    public void set1To1ThreadFallbackThreadId(int rcsThreadId, long fallbackId,
            String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mMessageStoreUtil.updateValueOfProviderUri(get1To1ThreadUri(rcsThreadId),
                        FALLBACK_THREAD_ID_COLUMN, fallbackId, "Could not set fallback thread ID"));
    }

    @Override
    public long get1To1ThreadFallbackThreadId(int rcsThreadId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(RCS_1_TO_1_THREAD_URI,
                        FALLBACK_THREAD_ID_COLUMN,
                        RCS_THREAD_ID_COLUMN, rcsThreadId));
    }

    @Override
    public int get1To1ThreadOtherParticipantId(int rcsThreadId, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            Uri uri = get1To1ThreadUri(rcsThreadId);
            String[] projection = new String[]{RCS_PARTICIPANT_ID_COLUMN};
            try (Cursor cursor = mContentResolver.query(uri, projection, null, null)) {
                if (cursor == null || cursor.getCount() != 1) {
                    throw new RemoteException("Could not get the thread recipient");
                }
                cursor.moveToNext();
                return cursor.getInt(
                        cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN));
            }
        });
    }

    @Override
    public void setGroupThreadName(int rcsThreadId, String groupName, String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mMessageStoreUtil.updateValueOfProviderUri(getGroupThreadUri(rcsThreadId),
                        GROUP_NAME_COLUMN, groupName, "Could not update group name"));
    }

    @Override
    public String getGroupThreadName(int rcsThreadId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(RCS_GROUP_THREAD_URI,
                        GROUP_NAME_COLUMN,
                        RCS_THREAD_ID_COLUMN, rcsThreadId));
    }

    @Override
    public void setGroupThreadIcon(int rcsThreadId, Uri groupIcon, String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mMessageStoreUtil.updateValueOfProviderUri(getGroupThreadUri(rcsThreadId),
                        GROUP_ICON_COLUMN, groupIcon, "Could not update group icon"));
    }

    @Override
    public Uri getGroupThreadIcon(int rcsThreadId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getUriValueFromTableRow(RCS_GROUP_THREAD_URI,
                        GROUP_ICON_COLUMN,
                        RCS_THREAD_ID_COLUMN, rcsThreadId));
    }

    @Override
    public void setGroupThreadOwner(int rcsThreadId, int participantId, String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mMessageStoreUtil.updateValueOfProviderUri(getGroupThreadUri(rcsThreadId),
                        OWNER_PARTICIPANT_COLUMN, participantId, "Could not set the group owner"));
    }

    @Override
    public int getGroupThreadOwner(int rcsThreadId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(RCS_GROUP_THREAD_URI,
                        OWNER_PARTICIPANT_COLUMN,
                        RCS_THREAD_ID_COLUMN, rcsThreadId));
    }

    @Override
    public void setGroupThreadConferenceUri(int rcsThreadId, Uri uri, String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mMessageStoreUtil.updateValueOfProviderUri(getGroupThreadUri(rcsThreadId),
                        CONFERENCE_URI_COLUMN, uri, "Could not set the conference URI for group"));
    }

    @Override
    public Uri getGroupThreadConferenceUri(int rcsThreadId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getUriValueFromTableRow(RCS_GROUP_THREAD_URI,
                        CONFERENCE_URI_COLUMN, RCS_THREAD_ID_COLUMN, rcsThreadId));
    }

    @Override
    public void addParticipantToGroupThread(int rcsThreadId, int participantId,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> {
            ContentValues contentValues = new ContentValues(2);
            contentValues.put(RCS_THREAD_ID_COLUMN, rcsThreadId);
            contentValues.put(RCS_PARTICIPANT_ID_COLUMN, participantId);

            mContentResolver.insert(getAllParticipantsInThreadUri(rcsThreadId), contentValues);
        });
    }

    @Override
    public void removeParticipantFromGroupThread(int rcsThreadId, int participantId,
            String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mContentResolver.delete(getParticipantInThreadUri(rcsThreadId, participantId),
                        null,
                        null));
    }

    @Override
    public int addIncomingMessage(int rcsThreadId,
            RcsIncomingMessageCreationParams rcsIncomingMessageCreationParams,
            String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues contentValues = new ContentValues();

            contentValues.put(ARRIVAL_TIMESTAMP_COLUMN,
                    rcsIncomingMessageCreationParams.getArrivalTimestamp());
            contentValues.put(SEEN_TIMESTAMP_COLUMN,
                    rcsIncomingMessageCreationParams.getSeenTimestamp());
            contentValues.put(SENDER_PARTICIPANT_ID_COLUMN,
                    rcsIncomingMessageCreationParams.getSenderParticipantId());

            mMessageQueryHelper.createContentValuesForGenericMessage(contentValues, rcsThreadId,
                    rcsIncomingMessageCreationParams);

            return addMessage(rcsThreadId, true, contentValues);
        });
    }

    @Override
    public int addOutgoingMessage(int rcsThreadId,
            RcsOutgoingMessageCreationParams rcsOutgoingMessageCreationParameters,
            String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues contentValues = new ContentValues();

            mMessageQueryHelper.createContentValuesForGenericMessage(contentValues, rcsThreadId,
                    rcsOutgoingMessageCreationParameters);

            return addMessage(rcsThreadId, false, contentValues);
        });
    }

    private int addMessage(int rcsThreadId, boolean isIncoming, ContentValues contentValues)
            throws RemoteException {
        Uri uri = mContentResolver.insert(mMessageQueryHelper.getMessageInsertionUri(isIncoming),
                contentValues);

        if (uri == null) {
            throw new RemoteException(
                    "Could not create message on thread, threadId: " + rcsThreadId);
        }

        return Integer.parseInt(uri.getLastPathSegment());
    }

    @Override
    public void deleteMessage(int messageId, boolean isIncoming, int rcsThreadId, boolean isGroup,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mContentResolver.delete(
                mMessageQueryHelper.getMessageDeletionUri(messageId, isIncoming, rcsThreadId,
                        isGroup),
                null, null));
    }

    @Override
    public void setMessageSubId(int messageId, boolean isIncoming, int subId,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming), SUB_ID_COLUMN,
                subId, "Could not set subscription ID for message"));
    }

    @Override
    public int getMessageSubId(int messageId, boolean isIncoming, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(getMessageTableUri(isIncoming),
                        SUB_ID_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setMessageStatus(int messageId, boolean isIncoming, int status,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming), STATUS_COLUMN,
                status, "Could not set the status for message"));
    }

    @Override
    public int getMessageStatus(int messageId, boolean isIncoming, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(getMessageTableUri(isIncoming),
                        STATUS_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setMessageOriginationTimestamp(int messageId, boolean isIncoming,
            long originationTimestamp, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming),
                ORIGINATION_TIMESTAMP_COLUMN, originationTimestamp,
                "Could not set the origination timestamp for message"));
    }

    @Override
    public long getMessageOriginationTimestamp(int messageId, boolean isIncoming,
            String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(getMessageTableUri(isIncoming),
                        ORIGINATION_TIMESTAMP_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setGlobalMessageIdForMessage(int messageId, boolean isIncoming, String globalId,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming),
                GLOBAL_ID_COLUMN,
                globalId, "Could not set the global ID for message"));
    }

    @Override
    public String getGlobalMessageIdForMessage(int messageId, boolean isIncoming,
            String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(getMessageTableUri(isIncoming),
                        GLOBAL_ID_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setMessageArrivalTimestamp(int messageId, boolean isIncoming,
            long arrivalTimestamp, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming),
                ARRIVAL_TIMESTAMP_COLUMN, arrivalTimestamp,
                "Could not update the arrival timestamp for message"));
    }

    @Override
    public long getMessageArrivalTimestamp(int messageId, boolean isIncoming,
            String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(getMessageTableUri(isIncoming),
                        ARRIVAL_TIMESTAMP_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setMessageSeenTimestamp(int messageId, boolean isIncoming,
            long notifiedTimestamp, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming),
                SEEN_TIMESTAMP_COLUMN, notifiedTimestamp,
                "Could not set the notified timestamp for message"));
    }

    @Override
    public long getMessageSeenTimestamp(int messageId, boolean isIncoming, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(getMessageTableUri(isIncoming),
                        SEEN_TIMESTAMP_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public int[] getMessageRecipients(int messageId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageQueryHelper.getDeliveryParticipantsForMessage(messageId));
    }

    @Override
    public long getOutgoingDeliveryDeliveredTimestamp(int messageId, int participantId,
            String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageQueryHelper.getLongValueFromDelivery(messageId, participantId,
                        DELIVERED_TIMESTAMP_COLUMN));
    }

    @Override
    public void setOutgoingDeliveryDeliveredTimestamp(int messageId, int participantId,
            long deliveredTimestamp, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageDeliveryUri(messageId, participantId),
                DELIVERED_TIMESTAMP_COLUMN, deliveredTimestamp,
                "Could not update the delivered timestamp for outgoing delivery"));
    }

    @Override
    public long getOutgoingDeliverySeenTimestamp(int messageId, int participantId,
            String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageQueryHelper.getLongValueFromDelivery(messageId, participantId,
                        SEEN_TIMESTAMP_COLUMN));
    }

    @Override
    public void setOutgoingDeliverySeenTimestamp(int messageId, int participantId,
            long seenTimestamp, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageDeliveryUri(messageId, participantId),
                SEEN_TIMESTAMP_COLUMN, seenTimestamp,
                "Could not update the seen timestamp for outgoing delivery"));
    }

    @Override
    public int getOutgoingDeliveryStatus(int messageId, int participantId, String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            // TODO - implement
            return 0;
        });
    }

    @Override
    public void setOutgoingDeliveryStatus(int messageId, int participantId, int status,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> {
            // TODO - implement
        });
    }

    @Override
    public void setTextForMessage(int messageId, boolean isIncoming, String text,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming),
                MESSAGE_TEXT_COLUMN,
                text, "Could not set the text for message"));
    }

    @Override
    public String getTextForMessage(int messageId, boolean isIncoming, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(getMessageTableUri(isIncoming),
                        MESSAGE_TEXT_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setLatitudeForMessage(int messageId, boolean isIncoming, double latitude,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming), LATITUDE_COLUMN,
                latitude, "Could not update latitude for message"));
    }

    @Override
    public double getLatitudeForMessage(int messageId, boolean isIncoming, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getDoubleValueFromTableRow(getMessageTableUri(isIncoming),
                        LATITUDE_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void setLongitudeForMessage(int messageId, boolean isIncoming, double longitude,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getMessageUpdateUri(messageId, isIncoming),
                LONGITUDE_COLUMN,
                longitude, "Could not set longitude for message"));
    }

    @Override
    public double getLongitudeForMessage(int messageId, boolean isIncoming, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getDoubleValueFromTableRow(getMessageTableUri(isIncoming),
                        LONGITUDE_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public int[] getFileTransfersAttachedToMessage(int messageId, boolean isIncoming,
            String callingPackage) {
        return performReadOperation(callingPackage, () -> {
            // TODO - implement
            return new int[0];
        });
    }

    @Override
    public int getSenderParticipant(int messageId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(INCOMING_MESSAGE_URI,
                        SENDER_PARTICIPANT_ID_COLUMN, MESSAGE_ID_COLUMN, messageId));
    }

    @Override
    public void deleteFileTransfer(int partId, String callingPackage) {
        performWriteOperation(callingPackage,
                () -> mContentResolver.delete(mMessageQueryHelper.getFileTransferUpdateUri(partId),
                        null,
                        null));
    }

    @Override
    public int storeFileTransfer(int messageId, boolean isIncoming,
            RcsFileTransferCreationParams fileTransferCreationParameters, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues contentValues = mMessageQueryHelper.getContentValuesForFileTransfer(
                    fileTransferCreationParameters);
            Uri uri = mContentResolver.insert(
                    mMessageQueryHelper.getFileTransferInsertionUri(messageId), contentValues);

            if (uri != null) {
                return Integer.parseInt(uri.getLastPathSegment());
            }

            return TRANSACTION_FAILED;
        });
    }

    @Override
    public void setFileTransferSessionId(int partId, String sessionId, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), SESSION_ID_COLUMN,
                sessionId,
                "Could not set session ID for file transfer"));
    }

    @Override
    public String getFileTransferSessionId(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(FILE_TRANSFER_URI,
                        SESSION_ID_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferContentUri(int partId, Uri contentUri, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), CONTENT_URI_COLUMN,
                contentUri, "Could not set content URI for file transfer"));
    }

    @Override
    public Uri getFileTransferContentUri(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getUriValueFromTableRow(FILE_TRANSFER_URI,
                        CONTENT_URI_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferContentType(int partId, String contentType, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), CONTENT_TYPE_COLUMN,
                contentType, "Could not set content type for file transfer"));
    }

    @Override
    public String getFileTransferContentType(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(FILE_TRANSFER_URI,
                        CONTENT_TYPE_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferFileSize(int partId, long fileSize, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), FILE_SIZE_COLUMN,
                fileSize,
                "Could not set file size for file transfer"));
    }

    @Override
    public long getFileTransferFileSize(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(FILE_TRANSFER_URI,
                        FILE_SIZE_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferTransferOffset(int partId, long transferOffset,
            String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId),
                SUCCESSFULLY_TRANSFERRED_BYTES,
                transferOffset, "Could not set transfer offset for file transfer"));
    }

    @Override
    public long getFileTransferTransferOffset(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(FILE_TRANSFER_URI,
                        SUCCESSFULLY_TRANSFERRED_BYTES,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferStatus(int partId, int transferStatus, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), TRANSFER_STATUS_COLUMN,
                transferStatus, "Could not set transfer status for file transfer"));
    }

    @Override
    public int getFileTransferStatus(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(FILE_TRANSFER_URI, STATUS_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferWidth(int partId, int width, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), WIDTH_COLUMN, width,
                "Could not set width of file transfer"));
    }

    @Override
    public int getFileTransferWidth(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(FILE_TRANSFER_URI, WIDTH_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferHeight(int partId, int height, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), HEIGHT_COLUMN, height,
                "Could not set height of file transfer"));
    }

    @Override
    public int getFileTransferHeight(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getIntValueFromTableRow(FILE_TRANSFER_URI, HEIGHT_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferLength(int partId, long length, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), DURATION_MILLIS_COLUMN,
                length,
                "Could not set length of file transfer"));
    }

    @Override
    public long getFileTransferLength(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getLongValueFromTableRow(FILE_TRANSFER_URI,
                        DURATION_MILLIS_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferPreviewUri(int partId, Uri uri, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), PREVIEW_URI_COLUMN, uri,
                "Could not set preview URI of file transfer"));
    }

    @Override
    public Uri getFileTransferPreviewUri(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getUriValueFromTableRow(FILE_TRANSFER_URI,
                        DURATION_MILLIS_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public void setFileTransferPreviewType(int partId, String type, String callingPackage) {
        performWriteOperation(callingPackage, () -> mMessageStoreUtil.updateValueOfProviderUri(
                mMessageQueryHelper.getFileTransferUpdateUri(partId), PREVIEW_TYPE_COLUMN, type,
                "Could not set preview type of file transfer"));
    }

    @Override
    public String getFileTransferPreviewType(int partId, String callingPackage) {
        return performReadOperation(callingPackage,
                () -> mMessageStoreUtil.getStringValueFromTableRow(FILE_TRANSFER_URI,
                        PREVIEW_TYPE_COLUMN,
                        FILE_TRANSFER_ID_COLUMN, partId));
    }

    @Override
    public int createGroupThreadNameChangedEvent(long timestamp, int threadId,
            int originationParticipantId, String newName, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues eventSpecificValues = new ContentValues();
            eventSpecificValues.put(NEW_NAME_COLUMN, newName);

            return mEventQueryHelper.createGroupThreadEvent(NAME_CHANGED_EVENT_TYPE, timestamp,
                    threadId, originationParticipantId, eventSpecificValues);
        });
    }

    @Override
    public int createGroupThreadIconChangedEvent(long timestamp, int threadId,
            int originationParticipantId, Uri newIcon, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues eventSpecificValues = new ContentValues();
            eventSpecificValues.put(NEW_ICON_URI_COLUMN,
                    newIcon == null ? null : newIcon.toString());

            return mEventQueryHelper.createGroupThreadEvent(ICON_CHANGED_EVENT_TYPE, timestamp,
                    threadId, originationParticipantId, eventSpecificValues);
        });
    }

    @Override
    public int createGroupThreadParticipantJoinedEvent(long timestamp, int threadId,
            int originationParticipantId, int participantId, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues eventSpecificValues = new ContentValues();
            eventSpecificValues.put(DESTINATION_PARTICIPANT_ID_COLUMN, participantId);

            return mEventQueryHelper.createGroupThreadEvent(PARTICIPANT_JOINED_EVENT_TYPE,
                    timestamp,
                    threadId, originationParticipantId, eventSpecificValues);
        });
    }

    @Override
    public int createGroupThreadParticipantLeftEvent(long timestamp, int threadId,
            int originationParticipantId, int participantId, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues eventSpecificValues = new ContentValues();
            eventSpecificValues.put(DESTINATION_PARTICIPANT_ID_COLUMN, participantId);

            return mEventQueryHelper.createGroupThreadEvent(PARTICIPANT_LEFT_EVENT_TYPE, timestamp,
                    threadId, originationParticipantId, eventSpecificValues);
        });
    }

    @Override
    public int createParticipantAliasChangedEvent(long timestamp, int participantId,
            String newAlias, String callingPackage) {
        return performCreateOperation(callingPackage, () -> {
            ContentValues contentValues = new ContentValues(4);
            contentValues.put(TIMESTAMP_COLUMN, timestamp);
            contentValues.put(SOURCE_PARTICIPANT_ID_COLUMN, participantId);
            contentValues.put(NEW_ALIAS_COLUMN, newAlias);

            Uri uri = mContentResolver.insert(
                    mEventQueryHelper.getParticipantEventInsertionUri(participantId),
                    contentValues);

            if (uri == null) {
                throw new RemoteException(
                        "Could not create RcsParticipantAliasChangedEvent with participant id: "
                                + participantId);
            }

            return Integer.parseInt(uri.getLastPathSegment());
        });
    }
}
