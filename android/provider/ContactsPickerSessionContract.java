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

package android.provider;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.content.flags.Flags;
import android.net.Uri;
import android.provider.ContactsContract.Data;

/**
 * <p>
 * The contract between the Contacts Picker session provider and client applications.
 * This contract defines the intent actions, extras, and URI structure used to interact
 * with the Contacts Picker to select contacts and retrieve their data.
 * </p>
 * <p>
 * It allows apps to select one or more contacts without requiring
 * {@link android.Manifest.permission#READ_CONTACTS}, enhancing user privacy and security.
 * The client app receives one-time access to the user-selected contacts data.
 * </p>
 */
@FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
public final class ContactsPickerSessionContract {

    private ContactsPickerSessionContract() {}

    /**
     * Intent action to launch the system Contacts Picker to select one or more contacts. This
     * action provides a modern way for applications to obtain contact information, differing from
     * {@link android.content.Intent#ACTION_PICK} and
     * {@link android.content.Intent#ACTION_GET_CONTENT} in several key ways:
     * <ul>
     *     <li>It is specifically designed for picking contacts, offering a streamlined user
     *     experience.</li>
     *     <li>The calling application gains one-time read access to the user-selected contact data,
     *     without requiring {@link android.Manifest.permission#READ_CONTACTS}.</li>
     *     <li>Users benefit from a consistent UI to grant temporary access to their
     *     contact data.</li>
     *     <li>Client applications can specify and receive multiple data fields (MIME types)
     *     corresponding to the selected contacts.</li>
     * </ul>
     * <p>
     * To use this intent, create an {@link android.content.Intent} with this action and launch it
     * using {@link android.app.Activity#startActivityForResult(android.content.Intent, int)}.
     * The system Contacts Picker UI will be displayed, allowing the user to select one or more
     * contacts. This contacts picker UI will:
     * <ul>
     *     <li>Have eligible contacts displayed as a scrollable list to the user.</li>
     *     <li>Enable users to select single/multiple contacts.</li>
     *     <li>Enable users to search for contacts using display name.</li>
     * </ul>
     *
     * <p>
     * The display and selection behavior can be customized using the following extras:
     * <ul>
     *     <li>{@link #EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS}: (Required) Specifies the MIME
     *     types of contact data to be displayed in the picker and returned to the calling app.
     *     Contacts must have at least one of the specified MIME types, to be shown as available for
     *     selection.</li>
     *     <li>{@link #EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS}: (Optional) If set to
     *     {@code true}, only contacts possessing data for *all* MIME types specified in
     *     {@link #EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS} will be shown as available for
     *     selection. Defaults to {@code false}.</li>
     *     <li>{@link #EXTRA_PICK_CONTACTS_SELECTION_LIMIT}: (Optional) Defines the maximum number
     *     of contacts the user can select. Defaults to 50, with a maximum allowed value of 100.
     *     </li>
     *     <li>{@link android.content.Intent#EXTRA_ALLOW_MULTIPLE}: (Optional) If set to
     *     {@code true}, the user can select multiple contacts. The default behavior is
     *     single-select.</li>
     * </ul>
     *
     * <p>
     * Upon successful contact(s) selection, the {@link android.app.Activity#onActivityResult(int,
     * int, android.content.Intent)} callback will be invoked with
     * {@link android.app.Activity#RESULT_OK}. The returned {@link android.content.Intent}
     * will contain a session URI in its data field (see {@link Session#CONTENT_URI}), which
     * should be used to query the selected contact data. For example:
     * <pre>
     *     Uri sessionUri = data.getData();
     *     Cursor cursor = getContentResolver().query(sessionUri, projection, null, null, null);
     *     // Process cursor data
     * </pre>
     *
     * <p>
     * Starting from Android 17, this intent is handled by a system application by default.
     * Third-party applications should not handle this intent as they will be ignored
     * when the system attempts to resolve it.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public static final String ACTION_PICK_CONTACTS = "android.provider.action.PICK_CONTACTS";

    /**
     * A {@code List<String>} extra that specifies the types of contact data the client
     * application is requesting. This extra serves two primary purposes:
     * <ol>
     *     <li>**Filtering:** Only contacts possessing data corresponding to at least one of the
     *     specified MIME types will be displayed in the Contacts Picker.</li>
     *     <li>**Data Return:** For selected contacts, only data corresponding to these specified
     *     MIME types will be returned to the calling application.</li>
     * </ol>
     * This extra must be populated with one or more of the following
     * MIME types:
     *
     * <ul>
     *  <li>{@link ContactsContract.CommonDataKinds.StructuredName#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Email#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Phone#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.StructuredPostal#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Organization#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Relation#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Event#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Photo#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.GroupMembership#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Website#CONTENT_ITEM_TYPE}</li>
     *  <li>{@link ContactsContract.CommonDataKinds.Nickname#CONTENT_ITEM_TYPE}</li>
     * </ul>
     *
     * <p>If {@link #EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS} is set to
     * {@code true}, only contacts having data corresponding to *all* of the MIME types specified
     * in this extra will be displayed.
     * <p>The Contacts Picker will throw an {@link IllegalArgumentException} if any of the MIME
     * types provided in this extra are not among the allowed types listed above.
     * <p>Clients are required to set this extra to ensure the picker can determine which
     * information should be made available for selection. Example usage:
     * <pre>
     *     List{@literal <String>} requestedMimeTypes = new ArrayList<>();
     *     requestedMimeTypes.add(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
     *     requestedMimeTypes.add(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
     *     intent.putStringArrayListExtra(
     *             ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
     *             (ArrayList{@literal <String>}) requestedMimeTypes);
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public static final String EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS =
            "android.provider.extra.PICK_CONTACTS_REQUESTED_DATA_FIELDS";

    /**
     * A boolean extra that, when set to {@code true}, instructs the system Contacts Picker to only
     * display contacts that possess data fields corresponding to *all* MIME type specified in
     * {@link #EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS}.
     *
     * <p>If {@code false} (the default value), contacts will be displayed if they have data for
     * *at least one* of the MIME types specified in
     * {@link #EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS}.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public static final String EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS =
            "android.provider.extra.PICK_CONTACTS_MATCH_ALL_DATA_FIELDS";

    /**
     * An integer extra that defines the maximum number of contacts a user can select in a single
     * session. The Contacts Picker uses this value to configure its UI.
     *
     * <p>If this extra is not set, the default selection limit is 50. The absolute maximum allowed
     * value is 100.
     *
     * <p>Clients should not set this value higher than the documented maximum limit. The
     * application handling {@link #ACTION_PICK_CONTACTS} will throw an
     * {@link IllegalArgumentException} if the provided value exceeds the maximum allowed limit.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public static final String EXTRA_PICK_CONTACTS_SELECTION_LIMIT =
            "android.provider.extra.PICK_CONTACTS_SELECTION_LIMIT";

    /**
     * The authority for the Contacts Picker session provider. This string is used to construct
     * content URIs for interacting with the Contacts Picker session data.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public static final String AUTHORITY = "com.android.contacts.picker.sessions";

    /**
     * The base {@code content://} style {@link Uri} for the Contacts Picker session provider.
     * All session-specific URIs are built upon this base URI.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    @NonNull
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * Defines the contract for a Contacts Picker session, which represents the set of contacts
     * selected by the user in a single picking operation.
     *
     * <p>Each row in this table corresponds to a single picker session and acts as a pointer to
     * the underlying contact data. Querying a session URI effectively projects rows from the
     * {@link ContactsContract.Data} table, providing secure, one-time access to the
     * {@link #EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS} for the contacts that the user
     * selected.
     *
     * <p>Access to session data is strictly controlled for privacy and security. A client
     * application can only access the session it initiated using the specific session URI
     * returned by the picker. This access is enforced through
     * {@link android.content.Intent#FLAG_GRANT_READ_URI_PERMISSION}. Privileged system
     * applications with {@code MANAGE_CONTACTS_PICKER_SESSION} permission can still access all
     * session data.
     *
     * <p>Because a session URI projects data from {@link ContactsContract.Data}, clients can use
     * the columns from {@link ContactsContract.Data} in their query projection, selection, and
     * sort order, just as they would when querying {@link ContactsContract.Data} directly.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public static final class Session implements BaseColumns {

        private Session() {}

        /**
         * The base {@code content://} style {@link Uri} for this table.
         *
         * <p>This URI represents the collection of all picker sessions and cannot be queried
         * directly. To access data for a specific session, clients must use the unique session
         * URI returned by the Contacts Picker in
         * {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}.
         * This returned URI will have the format
         * {@code content://<AUTHORITY>/sessions/<session_id>}, where {@code <session_id>} is a
         * unique identifier for the picking operation.
        */
        @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
        @NonNull
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "sessions");

        /**
         * The MIME type for a directory of contact data items within a specific session.
         * A session URI, whether for a single session (e.g., {@code CONTENT_URI/<session_id>})
         * or for the base URI, will have this MIME type.
         */
        @FlaggedApi(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
        public static final String CONTENT_TYPE = Data.CONTENT_TYPE;

        /**
         * Comma-separated list of contact data IDs, representing the specific contact fields
         * selected by the user within a session.
         *
         * <p>This column is for internal use by the system Contacts Picker when creating a
         * picker session and should not be directly accessed by client applications.
         * @hide
         */
        public static final String CONTACT_DATA_IDS = "data_ids";

        /**
         * The UID of the application that invoked the system Contacts Picker for a given session.
         * This value is used internally to verify that the application attempting to retrieve
         * session data is the same one that initiated the picker request.
         *
         * <p>This column is for internal use by the system Contacts Picker when creating a
         * picker session and should not be directly accessed by client applications.
         * @hide
         */
        public static final String SESSION_REQUESTER_UID = "requester_uid";
    }
}
