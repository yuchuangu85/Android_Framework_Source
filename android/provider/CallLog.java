/*
 * Copyright (C) 2006 The Android Open Source Project
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


package android.provider;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UserHandleAware;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.UserHandle;
import android.os.UserManager;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import com.android.server.telecom.flags.Flags;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The CallLog provider contains information about placed and received calls.
 */
public class CallLog {
    private static final String LOG_TAG = "CallLog";
    private static final boolean VERBOSE_LOG = false; // DON'T SUBMIT WITH TRUE.

    public static final String AUTHORITY = "call_log";

    /**
     * The content:// style URL for this provider
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    /** @hide */
    public static final String CALL_COMPOSER_SEGMENT = "call_composer";

    /** @hide */
    public static final Uri CALL_COMPOSER_PICTURE_URI =
            CONTENT_URI.buildUpon().appendPath(CALL_COMPOSER_SEGMENT).build();

    /**
     * The "shadow" provider stores calllog when the real calllog provider is encrypted.  The
     * real provider will alter copy from it when it starts, and remove the entries in the shadow.
     *
     * <p>See the comment in {@link Calls#addCall} for the details.
     *
     * @hide
     */
    public static final String SHADOW_AUTHORITY = "call_log_shadow";

    /** @hide */
    public static final Uri SHADOW_CALL_COMPOSER_PICTURE_URI = CALL_COMPOSER_PICTURE_URI.buildUpon()
            .authority(SHADOW_AUTHORITY).build();

    /**
     * Supplies a call composer picture to the call log for persistent storage.
     *
     * This method is used by Telephony to store pictures selected by the user or sent from the
     * remote party as part of a voice call with call composer. The {@link Uri} supplied in the
     * callback can be used to retrieve the image via {@link ContentResolver#openFile} or stored in
     * the {@link Calls} table in the {@link Calls#COMPOSER_PHOTO_URI} column.
     *
     * The caller is responsible for closing the {@link InputStream} after the callback indicating
     * success or failure.
     *
     * @param context An instance of {@link Context}. The picture will be stored to the user
     *     corresponding to {@link Context#getUser()}.
     * @param input An input stream from which the picture to store should be read. The input
     *     data must be decodeable as either a JPEG, PNG, or GIF image.
     * @param executor The {@link Executor} on which to perform the file transfer operation and
     *     call the supplied callback.
     * @param callback Callback that's called after the picture is successfully stored or when
     *     an error occurs.
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(allOf = {
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.INTERACT_ACROSS_USERS
    })
    public static void storeCallComposerPicture(@NonNull Context context,
            @NonNull InputStream input,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<Uri, CallComposerLoggingException> callback) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(input);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        executor.execute(() -> {
            ByteArrayOutputStream tmpOut = new ByteArrayOutputStream();

            // Read the entire input into memory first in case we have to write multiple times and
            // the input isn't resettable.
            byte[] buffer = new byte[1024];
            int bytesRead;
            while (true) {
                try {
                    bytesRead = input.read(buffer);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "IOException while reading call composer pic from input: " + e);
                    callback.onError(new CallComposerLoggingException(
                            CallComposerLoggingException.ERROR_INPUT_CLOSED));
                    return;
                }
                if (bytesRead < 0) {
                    break;
                }
                tmpOut.write(buffer, 0, bytesRead);
            }
            byte[] picData = tmpOut.toByteArray();

            UserManager userManager = context.getSystemService(UserManager.class);
            UserHandle user = context.getUser();
            // Nasty casework for the shadow calllog begins...
            // First see if we're just inserting for one user. If so, insert into the shadow
            // based on whether that user is unlocked.
            UserHandle realUser = UserHandle.CURRENT.equals(user)
                    ? android.os.Process.myUserHandle() : user;
            if (realUser != UserHandle.ALL) {
                Uri baseUri = userManager.isUserUnlocked(realUser) ? CALL_COMPOSER_PICTURE_URI
                        : SHADOW_CALL_COMPOSER_PICTURE_URI;
                Uri pictureInsertionUri = ContentProvider.maybeAddUserId(baseUri,
                        realUser.getIdentifier());
                Log.i(LOG_TAG, "Inserting call composer for single user at "
                        + pictureInsertionUri);

                try {
                    Uri result = storeCallComposerPictureAtUri(
                            context, pictureInsertionUri, false, picData);
                    callback.onResult(result);
                } catch (CallComposerLoggingException e) {
                    callback.onError(e);
                }
                return;
            }

            // Next, see if the system user is locked. If so, only insert to the system shadow
            if (!userManager.isUserUnlocked(UserHandle.SYSTEM)) {
                Uri pictureInsertionUri = ContentProvider.maybeAddUserId(
                        SHADOW_CALL_COMPOSER_PICTURE_URI,
                        UserHandle.SYSTEM.getIdentifier());
                Log.i(LOG_TAG, "Inserting call composer for all users, but system locked at "
                        + pictureInsertionUri);
                try {
                    Uri result = storeCallComposerPictureAtUri(
                            context, pictureInsertionUri, true, picData);
                    callback.onResult(result);
                } catch (CallComposerLoggingException e) {
                    callback.onError(e);
                }
                return;
            }

            // If we're inserting to all users and the system user is unlocked, then insert to all
            // running users. Non running/still locked users will copy from the system when they
            // start.
            // First, insert to the system calllog to get the basename to use for the rest of the
            // users.
            Uri systemPictureInsertionUri = ContentProvider.maybeAddUserId(
                    CALL_COMPOSER_PICTURE_URI, UserHandle.SYSTEM.getIdentifier());
            Uri systemInsertedPicture;
            try {
                systemInsertedPicture = storeCallComposerPictureAtUri(
                        context, systemPictureInsertionUri, true, picData);
                Log.i(LOG_TAG, "Inserting call composer for all users, succeeded with system,"
                        + " result is " + systemInsertedPicture);
            } catch (CallComposerLoggingException e) {
                callback.onError(e);
                return;
            }

            // Next, insert into all users that have call log access AND are running AND are
            // decrypted.
            Uri strippedInsertionUri = ContentProvider.getUriWithoutUserId(systemInsertedPicture);
            for (UserInfo u : userManager.getAliveUsers()) {
                UserHandle userHandle = u.getUserHandle();
                if (userHandle.isSystem()) {
                    // Already written.
                    continue;
                }

                if (!Calls.shouldHaveSharedCallLogEntries(
                        context, userManager, userHandle.getIdentifier())) {
                    // Shouldn't have calllog entries.
                    continue;
                }

                if (userManager.isUserRunning(userHandle)
                        && userManager.isUserUnlocked(userHandle)) {
                    Uri insertionUri = ContentProvider.maybeAddUserId(strippedInsertionUri,
                            userHandle.getIdentifier());
                    Log.i(LOG_TAG, "Inserting call composer for all users, now on user "
                            + userHandle + " inserting at " + insertionUri);
                    try {
                        storeCallComposerPictureAtUri(context, insertionUri, false, picData);
                    } catch (CallComposerLoggingException e) {
                        Log.e(LOG_TAG, "Error writing for user " + userHandle.getIdentifier()
                                + ": " + e);
                        // If one or more users failed but the system user succeeded, don't return
                        // an error -- the image is still around somewhere, and we'll be able to
                        // find it in the system user's call log if needed.
                    }
                }
            }
            callback.onResult(strippedInsertionUri);
        });
    }

    private static Uri storeCallComposerPictureAtUri(Context context, Uri insertionUri,
            boolean forAllUsers, byte[] picData) throws CallComposerLoggingException {
        Uri pictureFileUri;
        try {
            ContentValues cv = new ContentValues();
            cv.put(Calls.ADD_FOR_ALL_USERS, forAllUsers ? 1 : 0);
            pictureFileUri = context.getContentResolver().insert(insertionUri, cv);
        } catch (ParcelableException e) {
            // Most likely an IOException. We don't have a good way of distinguishing them so
            // just return an unknown error.
            throw new CallComposerLoggingException(CallComposerLoggingException.ERROR_UNKNOWN);
        }
        if (pictureFileUri == null) {
            // If the call log provider returns null, it means that there's not enough space
            // left to store the maximum-sized call composer image.
            throw new CallComposerLoggingException(CallComposerLoggingException.ERROR_STORAGE_FULL);
        }

        try (ParcelFileDescriptor pfd = context.getContentResolver()
                .openFileDescriptor(pictureFileUri, "w")) {
            FileOutputStream output = new FileOutputStream(pfd.getFileDescriptor());
            try {
                output.write(picData);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Got IOException writing to remote end: " + e);
                // Clean up our mess if we didn't successfully write the file.
                context.getContentResolver().delete(pictureFileUri, null);
                throw new CallComposerLoggingException(
                    CallComposerLoggingException.ERROR_REMOTE_END_CLOSED);
            }
        } catch (FileNotFoundException e) {
            throw new CallComposerLoggingException(CallComposerLoggingException.ERROR_UNKNOWN);
        } catch (IOException e) {
            // Ignore, this is only thrown upon closing.
            Log.e(LOG_TAG, "Got IOException closing remote descriptor: " + e);
        }
        return pictureFileUri;
    }

    // Only call on the correct executor.
    private static void sendCallComposerError(
            OutcomeReceiver<?, CallComposerLoggingException> cb, int error) {
        cb.onError(new CallComposerLoggingException(error));
    }

    /**
     * Describes an error encountered while storing a call composer picture in the call log.
     *
     * @hide
     */
    @SystemApi
    public static class CallComposerLoggingException extends Throwable {

        /**
         * Indicates an unknown error.
         */
        public static final int ERROR_UNKNOWN = 0;

        /**
         * Indicates that the process hosting the call log died or otherwise encountered an
         * unrecoverable error while storing the picture.
         *
         * The caller should retry if this error is encountered.
         */
        public static final int ERROR_REMOTE_END_CLOSED = 1;

        /**
         * Indicates that the device has insufficient space to store this picture.
         *
         * The caller should not retry if this error is encountered.
         */
        public static final int ERROR_STORAGE_FULL = 2;

        /**
         * Indicates that the {@link InputStream} passed to {@link #storeCallComposerPicture} was
         * closed.
         *
         * The caller should retry if this error is encountered, and be sure to not close the stream
         * before the callback is called this time.
         */
        public static final int ERROR_INPUT_CLOSED = 3;
        private final int mErrorCode;

        public CallComposerLoggingException(@CallComposerLoggingError int errorCode) {
            mErrorCode = errorCode;
        }

        /**
         * @return The error code for this exception.
         */
        public @CallComposerLoggingError int getErrorCode() {
            return mErrorCode;
        }

        @Override
        public String toString() {
            String errorString;
            switch (mErrorCode) {
                case ERROR_UNKNOWN:
                    errorString = "UNKNOWN";
                    break;
                case ERROR_REMOTE_END_CLOSED:
                    errorString = "REMOTE_END_CLOSED";
                    break;
                case ERROR_STORAGE_FULL:
                    errorString = "STORAGE_FULL";
                    break;
                case ERROR_INPUT_CLOSED:
                    errorString = "INPUT_CLOSED";
                    break;
                default:
                    errorString = "[[" + mErrorCode + "]]";
                    break;
            }
            return "CallComposerLoggingException: " + errorString;
        }

        /**
         * @hide
         */
        @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_UNKNOWN,
            ERROR_REMOTE_END_CLOSED,
            ERROR_STORAGE_FULL,
            ERROR_INPUT_CLOSED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface CallComposerLoggingError {

        }
    }

    /**
     * Contains the recent calls.
     * <p>
     * Note: If you want to query the call log and limit the results to a single value, you should
     * append the {@link #LIMIT_PARAM_KEY} parameter to the content URI.  For example:
     * <pre>
     * {@code
     * getContentResolver().query(
     *                 Calls.CONTENT_URI.buildUpon().appendQueryParameter(LIMIT_PARAM_KEY, "1")
     *                 .build(),
     *                 null, null, null, null);
     * }
     * </pre>
     * <p>
     * The call log provider enforces strict SQL grammar, so you CANNOT append "LIMIT" to the SQL
     * query as below:
     * <pre>
     * {@code
     * getContentResolver().query(Calls.CONTENT_URI, null, "LIMIT 1", null, null);
     * }
     * </pre>
     */
    public static class Calls implements BaseColumns {

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://call_log/calls");

        /**
         * @hide
         */
        public static final Uri SHADOW_CONTENT_URI = Uri.parse("content://call_log_shadow/calls");

        /**
         * The content:// style URL for filtering this table on phone numbers
         */
        public static final Uri CONTENT_FILTER_URI = Uri.parse("content://call_log/calls/filter");

        /**
         * The content:// style URL for VoIP applications to query the data belongs to them.
         * <p>
         * Applications granted the MANAGE_OWN_CALLS permission can access their own logs.
         */
        @FlaggedApi(Flags.FLAG_INTEGRATED_CALL_LOGS)
        @NonNull
        public static final Uri CONTENT_VOIP_URI = Uri.parse(CONTENT_URI + "/voip");

        /**
         * Query parameter used to limit the number of call logs returned.
         * <p>
         * TYPE: integer
         */
        public static final String LIMIT_PARAM_KEY = "limit";
        /**
         * Query parameter used to specify the starting record to return.
         * <p>
         * TYPE: integer
         */
        public static final String OFFSET_PARAM_KEY = "offset";
        /**
         * An optional URI parameter which instructs the provider to allow the operation to be
         * applied to voicemail records as well.
         * <p>
         * TYPE: Boolean
         * <p>
         * Using this parameter with a value of {@code true} will result in a security error if the
         * calling package does not have appropriate permissions to access voicemails.
         *
         * @hide
         */
        public static final String ALLOW_VOICEMAILS_PARAM_KEY = "allow_voicemails";
        /**
         * An optional URI parameter which instructs the provider to allow the operation to be
         * applied to VOIP call records as well.
         * <p>
         * TYPE: Boolean
         *
         */
        @FlaggedApi(Flags.FLAG_FILTER_VOIP_CALL_LOGS)
        @NonNull
        public static final String INCLUDE_VOIP_CALLS_PARAM_KEY = "include_voip_calls";
        /**
         * An optional extra used with {@link #CONTENT_TYPE Calls.CONTENT_TYPE} and
         * {@link Intent#ACTION_VIEW} to specify that the presented list of calls should be filtered
         * for a particular call type.
         *
         * Applications implementing a call log UI should check for this extra, and display a
         * filtered list of calls based on the specified call type. If not applicable within the
         * application's UI, it should be silently ignored.
         *
         * <p>
         * The following example brings up the call log, showing only missed calls.
         * <pre>
         * Intent intent = new Intent(Intent.ACTION_VIEW);
         * intent.setType(CallLog.Calls.CONTENT_TYPE);
         * intent.putExtra(CallLog.Calls.EXTRA_CALL_TYPE_FILTER, CallLog.Calls.MISSED_TYPE);
         * startActivity(intent);
         * </pre>
         * </p>
         */
        public static final String EXTRA_CALL_TYPE_FILTER =
                "android.provider.extra.CALL_TYPE_FILTER";
        /**
         * Content uri used to access call log entries, including voicemail records. You must have
         * the READ_CALL_LOG and WRITE_CALL_LOG permissions to read and write to the call log, as
         * well as READ_VOICEMAIL and WRITE_VOICEMAIL permissions to read and write voicemails.
         */
        public static final Uri CONTENT_URI_WITH_VOICEMAIL = CONTENT_URI.buildUpon()
                .appendQueryParameter(ALLOW_VOICEMAILS_PARAM_KEY, "true")
                .build();
        /**
         * Content uri used to access call log entries, including VOIP call records. You must have
         * the READ_CALL_LOG and WRITE_CALL_LOG permissions to read and write to the call log.
         */
        @FlaggedApi(Flags.FLAG_FILTER_VOIP_CALL_LOGS)
        @NonNull
        public static final Uri CONTENT_URI_WITH_VOIP_CALLS = CONTENT_URI.buildUpon()
                .appendQueryParameter(INCLUDE_VOIP_CALLS_PARAM_KEY, "true")
                .build();
        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "date DESC";
        /**
         * The MIME type of {@link #CONTENT_URI} and {@link #CONTENT_FILTER_URI} providing a
         * directory of calls.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/calls";
        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single call.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/calls";
        /**
         * The type of the call.
         * <P>Type: INTEGER (int)</P>
         *
         * <p>
         * Allowed values:
         * <ul>
         * <li>{@link #INCOMING_TYPE} - an incoming call which the user answered.</li>
         * <li>{@link #OUTGOING_TYPE} - an outgoing call which was placed by the user.</li>
         * <li>{@link #MISSED_TYPE} - a call that rang without the user answering it.</li>
         * <li>{@link #VOICEMAIL_TYPE} - a call log entry associated with voicemail.</li>
         * <li>{@link #REJECTED_TYPE} - an incoming call rejected by the user.</li>
         * <li>{@link #BLOCKED_TYPE} - the call was blocked by a
         * {@link android.telecom.CallScreeningService}</li> or the system block list.
         * <li>{@link #ANSWERED_EXTERNALLY_TYPE} - used for SIM twinning scenarios where a call
         * may have been answered on another device.</li>
         * </ul>
         * </p>
         */
        public static final String TYPE = "type";
        /**
         * Call log type for incoming calls.
         */
        public static final int INCOMING_TYPE = 1;
        /**
         * Call log type for outgoing calls.
         */
        public static final int OUTGOING_TYPE = 2;
        /**
         * Call log type for missed calls.
         */
        public static final int MISSED_TYPE = 3;
        /**
         * Call log type for voicemails.
         */
        public static final int VOICEMAIL_TYPE = 4;
        /**
         * Call log type for calls rejected by direct user action.
         */
        public static final int REJECTED_TYPE = 5;
        /**
         * Call log type for calls blocked automatically.
         */
        public static final int BLOCKED_TYPE = 6;
        /**
         * Call log type for a call which was answered on another device.  Used in situations where
         * a call rings on multiple devices simultaneously and it ended up being answered on a
         * device other than the current one.
         */
        public static final int ANSWERED_EXTERNALLY_TYPE = 7;
        /**
         * Bit-mask describing features of the call (e.g. video).
         *
         * <P>Type: INTEGER (int)</P>
         */
        public static final String FEATURES = "features";
        /**
         * Call had video.
         */
        public static final int FEATURES_VIDEO = 1 << 0;
        /**
         * Call was pulled externally.
         */
        public static final int FEATURES_PULLED_EXTERNALLY = 1 << 1;
        /**
         * Call was HD.
         */
        public static final int FEATURES_HD_CALL = 1 << 2;
        /**
         * Call was WIFI call.
         */
        public static final int FEATURES_WIFI = 1 << 3;
        /**
         * Indicates the call underwent Assisted Dialing.
         *
         * @see TelecomManager#EXTRA_USE_ASSISTED_DIALING
         */
        public static final int FEATURES_ASSISTED_DIALING_USED = 1 << 4;
        /**
         * Call was on RTT at some point
         */
        public static final int FEATURES_RTT = 1 << 5;
        /**
         * Call was VoLTE
         */
        public static final int FEATURES_VOLTE = 1 << 6;
        /**
         * Call was HD+
         */
        @FlaggedApi(Flags.FLAG_HD_PLUS_CALL)
        public static final int FEATURES_HD_PLUS_CALL = 1 << 7;

        /**
         * Call used VoNR (Voice Over New Radio; aka 5G)
         * (ie {@link android.telephony.TelephonyManager#NETWORK_TYPE_NR}).
         */
        @FlaggedApi(Flags.FLAG_HD_PLUS_CALL)
        public static final int FEATURES_VONR = 1 << 8;

        /**
         * Indicates that this was a group call.
         */
        @FlaggedApi(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
        public static final int FEATURES_GROUP_CALL = 1 << 9;
        /**
         * The phone number as the user entered it.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = "number";
        /**
         * Boolean indicating whether the call is a business call.
         */
        @FlaggedApi(Flags.FLAG_BUSINESS_CALL_COMPOSER)
        public static final String IS_BUSINESS_CALL = "is_business_call";
        /**
         * String that stores the asserted display name associated with business call.
         */
        @FlaggedApi(Flags.FLAG_BUSINESS_CALL_COMPOSER)
        public static final String ASSERTED_DISPLAY_NAME = "asserted_display_name";
        /**
         * The number presenting rules set by the network.
         *
         * <p>
         * Allowed values:
         * <ul>
         * <li>{@link #PRESENTATION_ALLOWED}</li>
         * <li>{@link #PRESENTATION_RESTRICTED}</li>
         * <li>{@link #PRESENTATION_UNKNOWN}</li>
         * <li>{@link #PRESENTATION_PAYPHONE}</li>
         * <li>{@link #PRESENTATION_UNAVAILABLE}</li>
         * </ul>
         * </p>
         *
         * <P>Type: INTEGER</P>
         */
        public static final String NUMBER_PRESENTATION = "presentation";
        /**
         * Number is allowed to display for caller id.
         */
        public static final int PRESENTATION_ALLOWED = 1;
        /**
         * Number is blocked by user.
         */
        public static final int PRESENTATION_RESTRICTED = 2;
        /**
         * Number is not specified or unknown by network.
         */
        public static final int PRESENTATION_UNKNOWN = 3;
        /**
         * Number is a pay phone.
         */
        public static final int PRESENTATION_PAYPHONE = 4;
        /**
         * Number is unavailable.
         */
        public static final int PRESENTATION_UNAVAILABLE = 5;
        /**
         * The ISO 3166-1 two letters country code of the country where the user received or made
         * the call.
         * <P>
         * Type: TEXT
         * </P>
         */
        public static final String COUNTRY_ISO = "countryiso";
        /**
         * The date the call occured, in milliseconds since the epoch
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";
        /**
         * The duration of the call in seconds
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DURATION = "duration";
        /**
         * The data usage of the call in bytes.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATA_USAGE = "data_usage";
        /**
         * Whether or not the call has been acknowledged
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String NEW = "new";
        /**
         * The cached name associated with the phone number, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NAME = "name";
        /**
         * The preferred name associated with the phone number, if it exists.
         *
         * <p>This value is typically filled in with the caller name presentation (CNAP) name to be
         * used as the preferred name that should be displayed in the call log. This is notified
         * from the network and propagated into
         * {@link com.android.server.telecom.Call#getCallerDisplayName()}. However, this field can
         * be extended to account for other use cases as well.
         * <P>Type: TEXT</P>
         */
        @FlaggedApi(Flags.FLAG_SUPPORT_DISPLAY_NAME_CALL_LOG)
        public static final String PREFERRED_DISPLAY_NAME = "preferred_display_name";
        /**
         * The cached number type (Home, Work, etc) associated with the phone number, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: INTEGER</P>
         */
        public static final String CACHED_NUMBER_TYPE = "numbertype";
        /**
         * The cached number label, for a custom number type, associated with the phone number, if
         * it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NUMBER_LABEL = "numberlabel";
        /**
         * URI of the voicemail entry. Populated only for {@link #VOICEMAIL_TYPE}.
         * <P>Type: TEXT</P>
         */
        public static final String VOICEMAIL_URI = "voicemail_uri";
        /**
         * Transcription of the call or voicemail entry. This will only be populated for call log
         * entries of type {@link #VOICEMAIL_TYPE} that have valid transcriptions.
         */
        public static final String TRANSCRIPTION = "transcription";
        /**
         * State of voicemail transcription entry. This will only be populated for call log entries
         * of type {@link #VOICEMAIL_TYPE}.
         *
         * @hide
         */
        public static final String TRANSCRIPTION_STATE = "transcription_state";
        /**
         * Whether this item has been read or otherwise consumed by the user.
         * <p>
         * Unlike the {@link #NEW} field, which requires the user to have acknowledged the existence
         * of the entry, this implies the user has interacted with the entry.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String IS_READ = "is_read";
        /**
         * A geocoded location for the number associated with this call.
         * <p>
         * The string represents a city, state, or country associated with the number.
         * <P>Type: TEXT</P>
         */
        public static final String GEOCODED_LOCATION = "geocoded_location";
        /**
         * The cached URI to look up the contact associated with the phone number, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_LOOKUP_URI = "lookup_uri";
        /**
         * The cached phone number of the contact which matches this entry, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_MATCHED_NUMBER = "matched_number";
        /**
         * The cached normalized(E164) version of the phone number, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NORMALIZED_NUMBER = "normalized_number";
        /**
         * The cached photo id of the picture associated with the phone number, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String CACHED_PHOTO_ID = "photo_id";
        /**
         * The cached photo URI of the picture associated with the phone number, if it exists.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT (URI)</P>
         */
        public static final String CACHED_PHOTO_URI = "photo_uri";
        /**
         * The cached phone number, formatted with formatting rules based on the country the user
         * was in when the call was made or received.
         *
         * <p>This value is typically filled in by the dialer app for the caching purpose,
         * so it's not guaranteed to be present, and may not be current if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_FORMATTED_NUMBER = "formatted_number";
        /**
         * The component name of the account used to place or receive the call; in string form.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_COMPONENT_NAME = "subscription_component_name";

        // Note: PHONE_ACCOUNT_* constant values are "subscription_*" due to a historic naming
        // that was encoded into call log databases.
        /**
         * The identifier for the account used to place or receive the call.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_ID = "subscription_id";
        /**
         * The address associated with the account used to place or receive the call; in string
         * form. For SIM-based calls, this is the user's own phone number.
         * <P>Type: TEXT</P>
         *
         * @hide
         */
        // TODO(b/469123257) - make this a system API.
        public static final String PHONE_ACCOUNT_ADDRESS = "phone_account_address";
        /**
         * Indicates that the entry will be hidden from all queries until the associated
         * {@link android.telecom.PhoneAccount} is registered with the system.
         * <P>Type: INTEGER</P>
         *
         * @hide
         */
        public static final String PHONE_ACCOUNT_HIDDEN = "phone_account_hidden";
        /**
         * The subscription ID used to place this call.  This is no longer used and has been
         * replaced with PHONE_ACCOUNT_COMPONENT_NAME/PHONE_ACCOUNT_ID. For ContactsProvider
         * internal use only.
         * <P>Type: INTEGER</P>
         *
         * @Deprecated
         * @hide
         */
        public static final String SUB_ID = "sub_id";
        /**
         * The post-dial portion of a dialed number, including any digits dialed after a
         * {@link TelecomManager#DTMF_CHARACTER_PAUSE} or a
         * {@link TelecomManager#DTMF_CHARACTER_WAIT} and these characters themselves.
         * <P>Type: TEXT</P>
         */
        public static final String POST_DIAL_DIGITS = "post_dial_digits";
        /**
         * For an incoming call, the secondary line number the call was received via. When a SIM
         * card has multiple phone numbers associated with it, the via number indicates which of the
         * numbers associated with the SIM was called.
         */
        public static final String VIA_NUMBER = "via_number";
        /**
         * Indicates that the entry will be copied from primary user to other users.
         * <P>Type: INTEGER</P>
         *
         * @hide
         */
        // TODO(b/469123257) - make this a system API.
        public static final String ADD_FOR_ALL_USERS = "add_for_all_users";
        /**
         * The date the row is last inserted, updated, or marked as deleted, in milliseconds since
         * the epoch. Read only.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String LAST_MODIFIED = "last_modified";
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set as the default value when a call was
         * not blocked by a CallScreeningService or any other system call blocking method.
         */
        public static final int BLOCK_REASON_NOT_BLOCKED = 0;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked by a
         * CallScreeningService. The {@link CallLog.Calls#CALL_SCREENING_COMPONENT_NAME} and
         * {@link CallLog.Calls#CALL_SCREENING_APP_NAME} columns will indicate which call screening
         * service was responsible for blocking the call.
         */
        public static final int BLOCK_REASON_CALL_SCREENING_SERVICE = 1;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked because the user
         * configured a contact to be sent directly to voicemail.
         */
        public static final int BLOCK_REASON_DIRECT_TO_VOICEMAIL = 2;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked because it is in
         * the BlockedNumbers provider.
         */
        public static final int BLOCK_REASON_BLOCKED_NUMBER = 3;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked because the user
         * has chosen to block all calls from unknown numbers.
         */
        public static final int BLOCK_REASON_UNKNOWN_NUMBER = 4;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked because the user
         * has chosen to block all calls from restricted numbers.
         */
        public static final int BLOCK_REASON_RESTRICTED_NUMBER = 5;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked because the user
         * has chosen to block all calls from pay phones.
         */
        public static final int BLOCK_REASON_PAY_PHONE = 6;
        /**
         * Value for {@link CallLog.Calls#BLOCK_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#BLOCKED_TYPE} to indicate that a call was blocked because the user
         * has chosen to block all calls from numbers not in their contacts.
         */
        public static final int BLOCK_REASON_NOT_IN_CONTACTS = 7;
        /**
         * The ComponentName of the CallScreeningService which blocked this call. Will be populated
         * when the {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#BLOCKED_TYPE}.
         * <P>Type: TEXT</P>
         */
        public static final String CALL_SCREENING_COMPONENT_NAME = "call_screening_component_name";
        /**
         * The name of the app which blocked a call. Will be populated when the
         * {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#BLOCKED_TYPE}. Provided as a
         * convenience so that the call log can still indicate which app blocked a call, even if
         * that app is no longer installed.
         * <P>Type: TEXT</P>
         */
        public static final String CALL_SCREENING_APP_NAME = "call_screening_app_name";
        /**
         * Where the {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#BLOCKED_TYPE}, indicates the
         * reason why a call is blocked.
         * <P>Type: INTEGER</P>
         *
         * <p>
         * Allowed values:
         * <ul>
         * <li>{@link CallLog.Calls#BLOCK_REASON_NOT_BLOCKED}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_CALL_SCREENING_SERVICE}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_DIRECT_TO_VOICEMAIL}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_BLOCKED_NUMBER}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_UNKNOWN_NUMBER}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_RESTRICTED_NUMBER}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_PAY_PHONE}</li>
         * <li>{@link CallLog.Calls#BLOCK_REASON_NOT_IN_CONTACTS}</li>
         * </ul>
         * </p>
         */
        public static final String BLOCK_REASON = "block_reason";
        /**
         * Value for {@link CallLog.Calls#MISSED_REASON}, set as the default value when a call was
         * not missed.
         */
        public static final long MISSED_REASON_NOT_MISSED = 0;
        /**
         * Value for {@link CallLog.Calls#MISSED_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#MISSED_TYPE} to indicate that a call was automatically rejected by
         * system because an ongoing emergency call.
         */
        public static final long AUTO_MISSED_EMERGENCY_CALL = 1 << 0;
        /**
         * Value for {@link CallLog.Calls#MISSED_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#MISSED_TYPE} to indicate that a call was automatically rejected by
         * system because the system cannot support any more ringing calls.
         */
        public static final long AUTO_MISSED_MAXIMUM_RINGING = 1 << 1;
        /**
         * Value for {@link CallLog.Calls#MISSED_REASON}, set when {@link CallLog.Calls#TYPE} is
         * {@link CallLog.Calls#MISSED_TYPE} to indicate that a call was automatically rejected by
         * system because the system cannot support any more dialing calls.
         */
        public static final long AUTO_MISSED_MAXIMUM_DIALING = 1 << 2;
        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * the call was missed just because user didn't answer it.
         */
        public static final long USER_MISSED_NO_ANSWER = 1 << 16;
        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * this call rang for a short period of time.
         */
        public static final long USER_MISSED_SHORT_RING = 1 << 17;

        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * this call is silenced because the phone is in 'do not disturb mode'.
         */
        public static final long USER_MISSED_DND_MODE = 1 << 18;
        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * this call rings with a low ring volume.
         */
        public static final long USER_MISSED_LOW_RING_VOLUME = 1 << 19;

        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE} set this bit when
         * this call rings without vibration.
         */
        public static final long USER_MISSED_NO_VIBRATE = 1 << 20;
        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * this call is silenced by the call screening service.
         */
        public static final long USER_MISSED_CALL_SCREENING_SERVICE_SILENCED = 1 << 21;
        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * the call filters timed out.
         */
        public static final long USER_MISSED_CALL_FILTERS_TIMEOUT = 1 << 22;
        /**
         * When {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, set this bit when
         * the call ended before ringing.
         *
         * @hide
         */
        // TODO(b/469123257) - expose as a public API.
        public static final long USER_MISSED_NEVER_RANG = 1 << 23;

        /**
         * Where the {@link CallLog.Calls#TYPE} is {@link CallLog.Calls#MISSED_TYPE}, indicates
         * factors which may have lead the user to miss the call.
         * <P>Type: INTEGER</P>
         *
         * <p>
         * There are two main cases. Auto missed cases and user missed cases. Default value is:
         * <ul>
         * <li>{@link CallLog.Calls#MISSED_REASON_NOT_MISSED}</li>
         * </ul>
         * </p>
         * <P>
         * Auto missed cases are those where a call was missed because it was not possible for the
         * incoming call to be presented to the user at all. Possible values are:
         * <ul>
         * <li>{@link CallLog.Calls#AUTO_MISSED_EMERGENCY_CALL}</li>
         * <li>{@link CallLog.Calls#AUTO_MISSED_MAXIMUM_RINGING}</li>
         * <li>{@link CallLog.Calls#AUTO_MISSED_MAXIMUM_DIALING}</li>
         * </ul>
         * </P>
         * <P>
         * User missed cases are those where the incoming call was presented to the user, but
         * factors such as a low ringing volume may have contributed to the call being missed.
         * Following bits can be set to indicate possible reasons for this:
         * <ul>
         * <li>{@link CallLog.Calls#USER_MISSED_SHORT_RING}</li>
         * <li>{@link CallLog.Calls#USER_MISSED_DND_MODE}</li>
         * <li>{@link CallLog.Calls#USER_MISSED_LOW_RING_VOLUME}</li>
         * <li>{@link CallLog.Calls#USER_MISSED_NO_VIBRATE}</li>
         * <li>{@link CallLog.Calls#USER_MISSED_CALL_SCREENING_SERVICE_SILENCED}</li>
         * <li>{@link CallLog.Calls#USER_MISSED_CALL_FILTERS_TIMEOUT}</li>
         * </ul>
         * </P>
         */
        public static final String MISSED_REASON = "missed_reason";
        /**
         * The subject of the call, as delivered via call composer.
         *
         * For outgoing calls, contains the subject set by the local user. For incoming calls,
         * contains the subject set by the remote caller. May be null if no subject was set.
         * <p>Type: TEXT</p>
         */
        public static final String SUBJECT = "subject";
        /**
         * Used as a value in the {@link #PRIORITY} column.
         *
         * Indicates that the call is of normal priority. This is also the default value for calls
         * that did not include call composer elements.
         */
        public static final int PRIORITY_NORMAL = 0;
        /**
         * Used as a value in the {@link #PRIORITY} column.
         *
         * Indicates that the call is of urgent priority.
         */
        public static final int PRIORITY_URGENT = 1;
        /**
         * The priority of the call, as delivered via call composer.
         *
         * For outgoing calls, contains the priority set by the local user. For incoming calls,
         * contains the priority set by the remote caller. If no priority was set or the call did
         * not include call composer elements, defaults to {@link #PRIORITY_NORMAL}. Valid values
         * are {@link #PRIORITY_NORMAL} and {@link #PRIORITY_URGENT}.
         * <p>Type: INTEGER</p>
         */
        public static final String PRIORITY = "priority";
        /**
         * A reference to the picture that was sent via call composer.
         *
         * The string contained in this field should be converted to an {@link Uri} via
         * {@link Uri#parse(String)}, then passed to {@link ContentResolver#openFileDescriptor} in
         * order to obtain a file descriptor to access the picture data.
         *
         * The user may choose to delete the picture associated with a call independently of the
         * call log entry, in which case {@link ContentResolver#openFileDescriptor} may throw a
         * {@link FileNotFoundException}.
         *
         * Note that pictures sent or received via call composer will not be included in any backups
         * of the call log.
         *
         * <p>Type: TEXT</p>
         */
        public static final String COMPOSER_PHOTO_URI = "composer_photo_uri";
        /**
         * A reference to the location that was sent via call composer.
         *
         * This column contains the content URI of the corresponding entry in {@link Locations}
         * table, which contains the actual location data. The
         * {@link Manifest.permission#ACCESS_FINE_LOCATION} permission is required to access that
         * table.
         *
         * If your app has the appropriate permissions, the location data may be obtained by
         * converting the value of this column to an {@link Uri} via {@link Uri#parse}, then passing
         * the result to {@link ContentResolver#query}.
         *
         * The user may choose to delete the location associated with a call independently of the
         * call log entry, in which case the {@link Cursor} returned from
         * {@link ContentResolver#query} will either be {@code null} or empty, with
         * {@link Cursor#getCount()} returning {@code 0}.
         *
         * This column will not be populated when a call is received while the device is locked, and
         * it will not be part of any backups.
         *
         * <p>Type: TEXT</p>
         */
        public static final String LOCATION = "location";
        /**
         * A reference to indicate whether phone account migration process is pending.
         *
         * Before Android 13, {@link PhoneAccountHandle#getId()} returns the ICCID for Telephony
         * PhoneAccountHandle. Starting from Android 13, {@link PhoneAccountHandle#getId()} returns
         * the Subscription ID for Telephony PhoneAccountHandle. A phone account migration process
         * is to ensure this PhoneAccountHandle migration process cross the Android versions in the
         * CallLog database.
         *
         * <p>Type: INTEGER</p>
         *
         * @hide
         */
        // TODO(b/469123257) - expose as a system API.
        public static final String IS_PHONE_ACCOUNT_MIGRATION_PENDING =
                "is_call_log_phone_account_migration_pending";
        /**
         * Constant for the UUID column.
         *
         * This column contains the UUID generated by Telecom when adding the call, which is
         * a unique identifier associated with a call. Applications can retrieve the value
         * using {@link android.telecom.CallControl#getCallId()} during the call session.
         *
         * <p>Type: TEXT</p>
         */
        @FlaggedApi(Flags.FLAG_INTEGRATED_CALL_LOGS)
        public static final String UUID = "uuid";

        /**
         * Form of {@link #CONTENT_URI} which limits the query results to a single result.
         */
        private static final Uri CONTENT_URI_LIMIT_1 = CONTENT_URI.buildUpon()
                .appendQueryParameter(LIMIT_PARAM_KEY, "1")
                .build();

        /**
         * @hide
         */
        public static boolean shouldHaveSharedCallLogEntries(Context context,
                UserManager userManager, int userId) {
            if (userManager.hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS,
                    UserHandle.of(userId))) {
                return false;
            }
            final UserInfo userInfo = userManager.getUserInfo(userId);
            return userInfo != null && !userInfo.isProfile();
        }

        /**
         * Query the call log database for the last dialed number.
         *
         * @param context Used to get the content resolver.
         * @return The last phone number dialed (outgoing) or an empty string if none exist yet.
         */
        public static String getLastOutgoingCall(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            Cursor c = null;
            try {
                c = resolver.query(
                    CONTENT_URI_LIMIT_1,
                    new String[]{NUMBER},
                    TYPE + " = " + OUTGOING_TYPE,
                    null,
                    DEFAULT_SORT_ORDER);
                if (c == null || !c.moveToFirst()) {
                    return "";
                }
                return c.getString(0);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }

        /**
         * Check if the missedReason code indicate that the call was user missed or automatically
         * rejected by system.
         *
         * @param missedReason The result is true if the call was user missed, false if the call
         *     was automatically rejected by system.
         * @hide
         */
        public static boolean isUserMissed(long missedReason) {
            return missedReason >= (USER_MISSED_NO_ANSWER);
        }

        /**
         * @hide
         */
        @LongDef(flag = true, value = {
            MISSED_REASON_NOT_MISSED,
            AUTO_MISSED_EMERGENCY_CALL,
            AUTO_MISSED_MAXIMUM_RINGING,
            AUTO_MISSED_MAXIMUM_DIALING,
            USER_MISSED_NO_ANSWER,
            USER_MISSED_SHORT_RING,
            USER_MISSED_DND_MODE,
            USER_MISSED_LOW_RING_VOLUME,
            USER_MISSED_NO_VIBRATE,
            USER_MISSED_CALL_SCREENING_SERVICE_SILENCED,
            USER_MISSED_CALL_FILTERS_TIMEOUT,
            USER_MISSED_NEVER_RANG,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface MissedReason {

        }
    }

    /**
     * Table that contains information on location data sent via call composer.
     *
     * All fields in this table require the {@link Manifest.permission#ACCESS_FINE_LOCATION}
     * permission for access.
     */
    public static class Locations implements BaseColumns {

        /**
         * Authority for the locations content provider.
         */
        public static final String AUTHORITY = "call_composer_locations";
        /**
         * Content type for the location table.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/call_composer_location";
        /**
         * Content type for the location entries.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/call_composer_location";
        /**
         * The content URI for this table
         */
        @NonNull
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
        /**
         * Latitude in degrees. See {@link android.location.Location#setLatitude(double)}.
         * <p>Type: REAL</p>
         */
        public static final String LATITUDE = "latitude";
        /**
         * Longitude in degrees. See {@link android.location.Location#setLongitude(double)}.
         * <p>Type: REAL</p>
         */
        public static final String LONGITUDE = "longitude";

        private Locations() {
        }
    }
}
