/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.client.pbap;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Public API to control Phone Book Profile (PCE role only).
 * <p>
 * This class defines methods that shall be used by application for the
 * retrieval of phone book objects from remote device.
 * <p>
 * How to connect to remote device which is acting in PSE role:
 * <ul>
 * <li>Create a <code>BluetoothDevice</code> object which corresponds to remote
 * device in PSE role;
 * <li>Create an instance of <code>BluetoothPbapClient</code> class, passing
 * <code>BluetothDevice</code> object along with a <code>Handler</code> to it;
 * <li>Use {@link #setPhoneBookFolderRoot}, {@link #setPhoneBookFolderUp} and
 * {@link #setPhoneBookFolderDown} to navigate in virtual phone book folder
 * structure
 * <li>Use {@link #pullPhoneBookSize} or {@link #pullVcardListingSize} to
 * retrieve the size of selected phone book
 * <li>Use {@link #pullPhoneBook} to retrieve phone book entries
 * <li>Use {@link #pullVcardListing} to retrieve list of entries in the phone
 * book
 * <li>Use {@link #pullVcardEntry} to pull single entry from the phone book
 * </ul>
 * Upon completion of each call above PCE will notify application if operation
 * completed successfully (along with results) or failed.
 * <p>
 * Therefore, application should handle following events in its message queue
 * handler:
 * <ul>
 * <li><code>EVENT_PULL_PHONE_BOOK_SIZE_DONE</code>
 * <li><code>EVENT_PULL_VCARD_LISTING_SIZE_DONE</code>
 * <li><code>EVENT_PULL_PHONE_BOOK_DONE</code>
 * <li><code>EVENT_PULL_VCARD_LISTING_DONE</code>
 * <li><code>EVENT_PULL_VCARD_ENTRY_DONE</code>
 * <li><code>EVENT_SET_PHONE_BOOK_DONE</code>
 * </ul>
 * and
 * <ul>
 * <li><code>EVENT_PULL_PHONE_BOOK_SIZE_ERROR</code>
 * <li><code>EVENT_PULL_VCARD_LISTING_SIZE_ERROR</code>
 * <li><code>EVENT_PULL_PHONE_BOOK_ERROR</code>
 * <li><code>EVENT_PULL_VCARD_LISTING_ERROR</code>
 * <li><code>EVENT_PULL_VCARD_ENTRY_ERROR</code>
 * <li><code>EVENT_SET_PHONE_BOOK_ERROR</code>
 * </ul>
 * <code>connect</code> and <code>disconnect</code> methods are introduced for
 * testing purposes. An application does not need to use them as the session
 * connection and disconnection happens automatically internally.
 */
public class BluetoothPbapClient {
    private static final String TAG = "BluetoothPbapClient";

    /**
     * Path to local incoming calls history object
     */
    public static final String ICH_PATH = "telecom/ich.vcf";

    /**
     * Path to local outgoing calls history object
     */
    public static final String OCH_PATH = "telecom/och.vcf";

    /**
     * Path to local missed calls history object
     */
    public static final String MCH_PATH = "telecom/mch.vcf";

    /**
     * Path to local combined calls history object
     */
    public static final String CCH_PATH = "telecom/cch.vcf";

    /**
     * Path to local main phone book object
     */
    public static final String PB_PATH = "telecom/pb.vcf";

    /**
     * Path to incoming calls history object stored on the phone's SIM card
     */
    public static final String SIM_ICH_PATH = "SIM1/telecom/ich.vcf";

    /**
     * Path to outgoing calls history object stored on the phone's SIM card
     */
    public static final String SIM_OCH_PATH = "SIM1/telecom/och.vcf";

    /**
     * Path to missed calls history object stored on the phone's SIM card
     */
    public static final String SIM_MCH_PATH = "SIM1/telecom/mch.vcf";

    /**
     * Path to combined calls history object stored on the phone's SIM card
     */
    public static final String SIM_CCH_PATH = "SIM1/telecom/cch.vcf";

    /**
     * Path to main phone book object stored on the phone's SIM card
     */
    public static final String SIM_PB_PATH = "SIM1/telecom/pb.vcf";

    /**
     * Indicates to server that default sorting order shall be used for vCard
     * listing.
     */
    public static final byte ORDER_BY_DEFAULT = -1;

    /**
     * Indicates to server that indexed sorting order shall be used for vCard
     * listing.
     */
    public static final byte ORDER_BY_INDEXED = 0;

    /**
     * Indicates to server that alphabetical sorting order shall be used for the
     * vCard listing.
     */
    public static final byte ORDER_BY_ALPHABETICAL = 1;

    /**
     * Indicates to server that phonetical (based on sound attribute) sorting
     * order shall be used for the vCard listing.
     */
    public static final byte ORDER_BY_PHONETIC = 2;

    /**
     * Indicates to server that Name attribute of vCard shall be used to carry
     * out the search operation on
     */
    public static final byte SEARCH_ATTR_NAME = 0;

    /**
     * Indicates to server that Number attribute of vCard shall be used to carry
     * out the search operation on
     */
    public static final byte SEARCH_ATTR_NUMBER = 1;

    /**
     * Indicates to server that Sound attribute of vCard shall be used to carry
     * out the search operation
     */
    public static final byte SEARCH_ATTR_SOUND = 2;

    /**
     * VCard format version 2.1
     */
    public static final byte VCARD_TYPE_21 = 0;

    /**
     * VCard format version 3.0
     */
    public static final byte VCARD_TYPE_30 = 1;

    /* 64-bit mask used to filter out VCard fields */
    // TODO: Think of extracting to separate class
    public static final long VCARD_ATTR_VERSION = 0x000000000000000001;
    public static final long VCARD_ATTR_FN = 0x000000000000000002;
    public static final long VCARD_ATTR_N = 0x000000000000000004;
    public static final long VCARD_ATTR_PHOTO = 0x000000000000000008;
    public static final long VCARD_ATTR_BDAY = 0x000000000000000010;
    public static final long VCARD_ATTR_ADDR = 0x000000000000000020;
    public static final long VCARD_ATTR_LABEL = 0x000000000000000040;
    public static final long VCARD_ATTR_TEL = 0x000000000000000080;
    public static final long VCARD_ATTR_EMAIL = 0x000000000000000100;
    public static final long VCARD_ATTR_MAILER = 0x000000000000000200;
    public static final long VCARD_ATTR_TZ = 0x000000000000000400;
    public static final long VCARD_ATTR_GEO = 0x000000000000000800;
    public static final long VCARD_ATTR_TITLE = 0x000000000000001000;
    public static final long VCARD_ATTR_ROLE = 0x000000000000002000;
    public static final long VCARD_ATTR_LOGO = 0x000000000000004000;
    public static final long VCARD_ATTR_AGENT = 0x000000000000008000;
    public static final long VCARD_ATTR_ORG = 0x000000000000010000;
    public static final long VCARD_ATTR_NOTE = 0x000000000000020000;
    public static final long VCARD_ATTR_REV = 0x000000000000040000;
    public static final long VCARD_ATTR_SOUND = 0x000000000000080000;
    public static final long VCARD_ATTR_URL = 0x000000000000100000;
    public static final long VCARD_ATTR_UID = 0x000000000000200000;
    public static final long VCARD_ATTR_KEY = 0x000000000000400000;
    public static final long VCARD_ATTR_NICKNAME = 0x000000000000800000;
    public static final long VCARD_ATTR_CATEGORIES = 0x000000000001000000;
    public static final long VCARD_ATTR_PROID = 0x000000000002000000;
    public static final long VCARD_ATTR_CLASS = 0x000000000004000000;
    public static final long VCARD_ATTR_SORT_STRING = 0x000000000008000000;
    public static final long VCARD_ATTR_X_IRMC_CALL_DATETIME =
            0x000000000010000000;

    /**
     * Maximal number of entries of the phone book that PCE can handle
     */
    public static final short MAX_LIST_COUNT = (short) 0xFFFF;

    /**
     * Event propagated upon completion of <code>setPhoneBookFolderRoot</code>,
     * <code>setPhoneBookFolderUp</code> or <code>setPhoneBookFolderDown</code>
     * request.
     * <p>
     * This event indicates that request completed successfully.
     * @see #setPhoneBookFolderRoot
     * @see #setPhoneBookFolderUp
     * @see #setPhoneBookFolderDown
     */
    public static final int EVENT_SET_PHONE_BOOK_DONE = 1;

    /**
     * Event propagated upon completion of <code>pullPhoneBook</code> request.
     * <p>
     * This event carry on results of the request.
     * <p>
     * The resulting message contains:
     * <table>
     * <tr>
     * <td><code>msg.arg1</code></td>
     * <td>newMissedCalls parameter (only in case of missed calls history object
     * request)</td>
     * </tr>
     * <tr>
     * <td><code>msg.obj</code></td>
     * <td>which is a list of <code>VCardEntry</code> objects</td>
     * </tr>
     * </table>
     * @see #pullPhoneBook
     */
    public static final int EVENT_PULL_PHONE_BOOK_DONE = 2;

    /**
     * Event propagated upon completion of <code>pullVcardListing</code>
     * request.
     * <p>
     * This event carry on results of the request.
     * <p>
     * The resulting message contains:
     * <table>
     * <tr>
     * <td><code>msg.arg1</code></td>
     * <td>newMissedCalls parameter (only in case of missed calls history object
     * request)</td>
     * </tr>
     * <tr>
     * <td><code>msg.obj</code></td>
     * <td>which is a list of <code>BluetoothPbapCard</code> objects</td>
     * </tr>
     * </table>
     * @see #pullVcardListing
     */
    public static final int EVENT_PULL_VCARD_LISTING_DONE = 3;

    /**
     * Event propagated upon completion of <code>pullVcardEntry</code> request.
     * <p>
     * This event carry on results of the request.
     * <p>
     * The resulting message contains:
     * <table>
     * <tr>
     * <td><code>msg.obj</code></td>
     * <td>vCard as and object of type <code>VCardEntry</code></td>
     * </tr>
     * </table>
     * @see #pullVcardEntry
     */
    public static final int EVENT_PULL_VCARD_ENTRY_DONE = 4;

    /**
     * Event propagated upon completion of <code>pullPhoneBookSize</code>
     * request.
     * <p>
     * This event carry on results of the request.
     * <p>
     * The resulting message contains:
     * <table>
     * <tr>
     * <td><code>msg.arg1</code></td>
     * <td>size of the phone book</td>
     * </tr>
     * </table>
     * @see #pullPhoneBookSize
     */
    public static final int EVENT_PULL_PHONE_BOOK_SIZE_DONE = 5;

    /**
     * Event propagated upon completion of <code>pullVcardListingSize</code>
     * request.
     * <p>
     * This event carry on results of the request.
     * <p>
     * The resulting message contains:
     * <table>
     * <tr>
     * <td><code>msg.arg1</code></td>
     * <td>size of the phone book listing</td>
     * </tr>
     * </table>
     * @see #pullVcardListingSize
     */
    public static final int EVENT_PULL_VCARD_LISTING_SIZE_DONE = 6;

    /**
     * Event propagated upon completion of <code>setPhoneBookFolderRoot</code>,
     * <code>setPhoneBookFolderUp</code> or <code>setPhoneBookFolderDown</code>
     * request. This event indicates an error during operation.
     */
    public static final int EVENT_SET_PHONE_BOOK_ERROR = 101;

    /**
     * Event propagated upon completion of <code>pullPhoneBook</code> request.
     * This event indicates an error during operation.
     */
    public static final int EVENT_PULL_PHONE_BOOK_ERROR = 102;

    /**
     * Event propagated upon completion of <code>pullVcardListing</code>
     * request. This event indicates an error during operation.
     */
    public static final int EVENT_PULL_VCARD_LISTING_ERROR = 103;

    /**
     * Event propagated upon completion of <code>pullVcardEntry</code> request.
     * This event indicates an error during operation.
     */
    public static final int EVENT_PULL_VCARD_ENTRY_ERROR = 104;

    /**
     * Event propagated upon completion of <code>pullPhoneBookSize</code>
     * request. This event indicates an error during operation.
     */
    public static final int EVENT_PULL_PHONE_BOOK_SIZE_ERROR = 105;

    /**
     * Event propagated upon completion of <code>pullVcardListingSize</code>
     * request. This event indicates an error during operation.
     */
    public static final int EVENT_PULL_VCARD_LISTING_SIZE_ERROR = 106;

    /**
     * Event propagated when PCE has been connected to PSE
     */
    public static final int EVENT_SESSION_CONNECTED = 201;

    /**
     * Event propagated when PCE has been disconnected from PSE
     */
    public static final int EVENT_SESSION_DISCONNECTED = 202;
    public static final int EVENT_SESSION_AUTH_REQUESTED = 203;
    public static final int EVENT_SESSION_AUTH_TIMEOUT = 204;

    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING;
    }

    private final Handler mClientHandler;
    private final BluetoothPbapSession mSession;
    private ConnectionState mConnectionState = ConnectionState.DISCONNECTED;

    private SessionHandler mSessionHandler;

    private static class SessionHandler extends Handler {

        private final WeakReference<BluetoothPbapClient> mClient;

        SessionHandler(BluetoothPbapClient client) {
            mClient = new WeakReference<BluetoothPbapClient>(client);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage: what=" + msg.what);

            BluetoothPbapClient client = mClient.get();
            if (client == null) {
                return;
            }

            switch (msg.what) {
                case BluetoothPbapSession.REQUEST_FAILED:
                {
                    BluetoothPbapRequest req = (BluetoothPbapRequest) msg.obj;

                    if (req instanceof BluetoothPbapRequestPullPhoneBookSize) {
                        client.sendToClient(EVENT_PULL_PHONE_BOOK_SIZE_ERROR);
                    } else if (req instanceof BluetoothPbapRequestPullVcardListingSize) {
                        client.sendToClient(EVENT_PULL_VCARD_LISTING_SIZE_ERROR);
                    } else if (req instanceof BluetoothPbapRequestPullPhoneBook) {
                        client.sendToClient(EVENT_PULL_PHONE_BOOK_ERROR);
                    } else if (req instanceof BluetoothPbapRequestPullVcardListing) {
                        client.sendToClient(EVENT_PULL_VCARD_LISTING_ERROR);
                    } else if (req instanceof BluetoothPbapRequestPullVcardEntry) {
                        client.sendToClient(EVENT_PULL_VCARD_ENTRY_ERROR);
                    } else if (req instanceof BluetoothPbapRequestSetPath) {
                        client.sendToClient(EVENT_SET_PHONE_BOOK_ERROR);
                    }

                    break;
                }

                case BluetoothPbapSession.REQUEST_COMPLETED:
                {
                    BluetoothPbapRequest req = (BluetoothPbapRequest) msg.obj;

                    if (req instanceof BluetoothPbapRequestPullPhoneBookSize) {
                        int size = ((BluetoothPbapRequestPullPhoneBookSize) req).getSize();
                        client.sendToClient(EVENT_PULL_PHONE_BOOK_SIZE_DONE, size);

                    } else if (req instanceof BluetoothPbapRequestPullVcardListingSize) {
                        int size = ((BluetoothPbapRequestPullVcardListingSize) req).getSize();
                        client.sendToClient(EVENT_PULL_VCARD_LISTING_SIZE_DONE, size);

                    } else if (req instanceof BluetoothPbapRequestPullPhoneBook) {
                        BluetoothPbapRequestPullPhoneBook r = (BluetoothPbapRequestPullPhoneBook) req;
                        client.sendToClient(EVENT_PULL_PHONE_BOOK_DONE, r.getNewMissedCalls(),
                                r.getList());

                    } else if (req instanceof BluetoothPbapRequestPullVcardListing) {
                        BluetoothPbapRequestPullVcardListing r = (BluetoothPbapRequestPullVcardListing) req;
                        client.sendToClient(EVENT_PULL_VCARD_LISTING_DONE, r.getNewMissedCalls(),
                                r.getList());

                    } else if (req instanceof BluetoothPbapRequestPullVcardEntry) {
                        BluetoothPbapRequestPullVcardEntry r = (BluetoothPbapRequestPullVcardEntry) req;
                        client.sendToClient(EVENT_PULL_VCARD_ENTRY_DONE, r.getVcard());

                    } else if (req instanceof BluetoothPbapRequestSetPath) {
                        client.sendToClient(EVENT_SET_PHONE_BOOK_DONE);
                    }

                    break;
                }

                case BluetoothPbapSession.AUTH_REQUESTED:
                    client.sendToClient(EVENT_SESSION_AUTH_REQUESTED);
                    break;

                case BluetoothPbapSession.AUTH_TIMEOUT:
                    client.sendToClient(EVENT_SESSION_AUTH_TIMEOUT);
                    break;

                /*
                 * app does not need to know when session is connected since
                 * OBEX session is managed inside BluetoothPbapSession
                 * automatically - we add this only so app can visualize PBAP
                 * connection status in case it wants to
                 */

                case BluetoothPbapSession.SESSION_CONNECTING:
                    client.mConnectionState = ConnectionState.CONNECTING;
                    break;

                case BluetoothPbapSession.SESSION_CONNECTED:
                    client.mConnectionState = ConnectionState.CONNECTED;
                    client.sendToClient(EVENT_SESSION_CONNECTED);
                    break;

                case BluetoothPbapSession.SESSION_DISCONNECTED:
                    client.mConnectionState = ConnectionState.DISCONNECTED;
                    client.sendToClient(EVENT_SESSION_DISCONNECTED);
                    break;
            }
        }
    };

    private void sendToClient(int eventId) {
        sendToClient(eventId, 0, null);
    }

    private void sendToClient(int eventId, int param) {
        sendToClient(eventId, param, null);
    }

    private void sendToClient(int eventId, Object param) {
        sendToClient(eventId, 0, param);
    }

    private void sendToClient(int eventId, int param1, Object param2) {
        mClientHandler.obtainMessage(eventId, param1, 0, param2).sendToTarget();
    }

    /**
     * Constructs PCE object
     *
     * @param device BluetoothDevice that corresponds to remote acting in PSE
     *            role
     * @param handler the handle that will be used by PCE to notify events and
     *            results to application
     * @throws NullPointerException
     */
    public BluetoothPbapClient(BluetoothDevice device, Handler handler) {
        if (device == null) {
            throw new NullPointerException("BluetothDevice is null");
        }

        mClientHandler = handler;

        mSessionHandler = new SessionHandler(this);

        mSession = new BluetoothPbapSession(device, mSessionHandler);
    }

    /**
     * Starts a pbap session. <pb> This method set up rfcomm session, obex
     * session and waits for requests to be transfered to PSE.
     */
    public void connect() {
        mSession.start();
    }

    @Override
    public void finalize() {
        if (mSession != null) {
            mSession.stop();
        }
    }

    /**
     * Stops all the active transactions and disconnects from the server.
     */
    public void disconnect() {
        mSession.stop();
    }

    /**
     * Aborts current request, if any
     */
    public void abort() {
        mSession.abort();
    }

    public ConnectionState getState() {
        return mConnectionState;
    }

    /**
     * Sets current folder to root
     *
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_SET_PHONE_BOOK_DONE} or
     *         {@link #EVENT_SET_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean setPhoneBookFolderRoot() {
        BluetoothPbapRequest req = new BluetoothPbapRequestSetPath(false);
        return mSession.makeRequest(req);
    }

    /**
     * Sets current folder to parent
     *
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_SET_PHONE_BOOK_DONE} or
     *         {@link #EVENT_SET_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean setPhoneBookFolderUp() {
        BluetoothPbapRequest req = new BluetoothPbapRequestSetPath(true);
        return mSession.makeRequest(req);
    }

    /**
     * Sets current folder to selected sub-folder
     *
     * @param folder the name of the sub-folder
     * @return @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_SET_PHONE_BOOK_DONE} or
     *         {@link #EVENT_SET_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean setPhoneBookFolderDown(String folder) {
        BluetoothPbapRequest req = new BluetoothPbapRequestSetPath(folder);
        return mSession.makeRequest(req);
    }

    /**
     * Requests for the number of entries in the phone book.
     *
     * @param pbName absolute path to the phone book
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_PHONE_BOOK_SIZE_DONE} or
     *         {@link #EVENT_PULL_PHONE_BOOK_SIZE_ERROR} in case of failure
     */
    public boolean pullPhoneBookSize(String pbName) {
        BluetoothPbapRequestPullPhoneBookSize req = new BluetoothPbapRequestPullPhoneBookSize(
                pbName);

        return mSession.makeRequest(req);
    }

    /**
     * Requests for the number of entries in the phone book listing.
     *
     * @param folder the name of the folder to be retrieved
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_SIZE_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_SIZE_ERROR} in case of failure
     */
    public boolean pullVcardListingSize(String folder) {
        BluetoothPbapRequestPullVcardListingSize req = new BluetoothPbapRequestPullVcardListingSize(
                folder);

        return mSession.makeRequest(req);
    }

    /**
     * Pulls complete phone book. This method pulls phone book which entries are
     * of <code>VCARD_TYPE_21</code> type and each single vCard contains minimal
     * required set of fields and the number of entries in response is not
     * limited.
     *
     * @param pbName absolute path to the phone book
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_PHONE_BOOK_DONE} or
     *         {@link #EVENT_PULL_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean pullPhoneBook(String pbName) {
        return pullPhoneBook(pbName, 0, VCARD_TYPE_21, 0, 0);
    }

    /**
     * Pulls complete phone book. This method pulls all entries from the phone
     * book.
     *
     * @param pbName absolute path to the phone book
     * @param filter bit mask which indicates which fields of the vCard shall be
     *            included in each entry of the resulting list
     * @param format vCard format of entries in the resulting list
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_PHONE_BOOK_DONE} or
     *         {@link #EVENT_PULL_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean pullPhoneBook(String pbName, long filter, byte format) {
        return pullPhoneBook(pbName, filter, format, 0, 0);
    }

    /**
     * Pulls complete phone book. This method pulls entries from the phone book
     * limited to the number of <code>maxListCount</code> starting from the
     * position of <code>listStartOffset</code>.
     * <p>
     * The resulting list contains vCard objects in version
     * <code>VCARD_TYPE_21</code> which in turns contain minimal required set of
     * vCard fields.
     *
     * @param pbName absolute path to the phone book
     * @param maxListCount limits number of entries in the response
     * @param listStartOffset offset to the first entry of the list that would
     *            be returned
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_PHONE_BOOK_DONE} or
     *         {@link #EVENT_PULL_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean pullPhoneBook(String pbName, int maxListCount, int listStartOffset) {
        return pullPhoneBook(pbName, 0, VCARD_TYPE_21, maxListCount, listStartOffset);
    }

    /**
     * Pulls complete phone book.
     *
     * @param pbName absolute path to the phone book
     * @param filter bit mask which indicates which fields of the vCard hall be
     *            included in each entry of the resulting list
     * @param format vCard format of entries in the resulting list
     * @param maxListCount limits number of entries in the response
     * @param listStartOffset offset to the first entry of the list that would
     *            be returned
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_PHONE_BOOK_DONE} or
     *         {@link #EVENT_PULL_PHONE_BOOK_ERROR} in case of failure
     */
    public boolean pullPhoneBook(String pbName, long filter, byte format, int maxListCount,
            int listStartOffset) {
        BluetoothPbapRequest req = new BluetoothPbapRequestPullPhoneBook(pbName, filter, format,
                maxListCount, listStartOffset);
        return mSession.makeRequest(req);
    }

    /**
     * Pulls list of entries in the phone book.
     * <p>
     * This method pulls the list of entries in the <code>folder</code>.
     *
     * @param folder the name of the folder to be retrieved
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_ERROR} in case of failure
     */
    public boolean pullVcardListing(String folder) {
        return pullVcardListing(folder, ORDER_BY_DEFAULT, SEARCH_ATTR_NAME, null, 0, 0);
    }

    /**
     * Pulls list of entries in the <code>folder</code>.
     *
     * @param folder the name of the folder to be retrieved
     * @param order the sorting order of the resulting list of entries
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_ERROR} in case of failure
     */
    public boolean pullVcardListing(String folder, byte order) {
        return pullVcardListing(folder, order, SEARCH_ATTR_NAME, null, 0, 0);
    }

    /**
     * Pulls list of entries in the <code>folder</code>. Only entries where
     * <code>searchAttr</code> attribute of vCard matches <code>searchVal</code>
     * will be listed.
     *
     * @param folder the name of the folder to be retrieved
     * @param searchAttr vCard attribute which shall be used to carry out search
     *            operation on
     * @param searchVal text string used by matching routine to match the value
     *            of the attribute indicated by SearchAttr
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_ERROR} in case of failure
     */
    public boolean pullVcardListing(String folder, byte searchAttr, String searchVal) {
        return pullVcardListing(folder, ORDER_BY_DEFAULT, searchAttr, searchVal, 0, 0);
    }

    /**
     * Pulls list of entries in the <code>folder</code>.
     *
     * @param folder the name of the folder to be retrieved
     * @param order the sorting order of the resulting list of entries
     * @param maxListCount limits number of entries in the response
     * @param listStartOffset offset to the first entry of the list that would
     *            be returned
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_ERROR} in case of failure
     */
    public boolean pullVcardListing(String folder, byte order, int maxListCount,
            int listStartOffset) {
        return pullVcardListing(folder, order, SEARCH_ATTR_NAME, null, maxListCount,
                listStartOffset);
    }

    /**
     * Pulls list of entries in the <code>folder</code>.
     *
     * @param folder the name of the folder to be retrieved
     * @param maxListCount limits number of entries in the response
     * @param listStartOffset offset to the first entry of the list that would
     *            be returned
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_ERROR} in case of failure
     */
    public boolean pullVcardListing(String folder, int maxListCount, int listStartOffset) {
        return pullVcardListing(folder, ORDER_BY_DEFAULT, SEARCH_ATTR_NAME, null, maxListCount,
                listStartOffset);
    }

    /**
     * Pulls list of entries in the <code>folder</code>.
     *
     * @param folder the name of the folder to be retrieved
     * @param order the sorting order of the resulting list of entries
     * @param searchAttr vCard attribute which shall be used to carry out search
     *            operation on
     * @param searchVal text string used by matching routine to match the value
     *            of the attribute indicated by SearchAttr
     * @param maxListCount limits number of entries in the response
     * @param listStartOffset offset to the first entry of the list that would
     *            be returned
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_LISTING_DONE} or
     *         {@link #EVENT_PULL_VCARD_LISTING_ERROR} in case of failure
     */
    public boolean pullVcardListing(String folder, byte order, byte searchAttr,
            String searchVal, int maxListCount, int listStartOffset) {
        BluetoothPbapRequest req = new BluetoothPbapRequestPullVcardListing(folder, order,
                searchAttr, searchVal, maxListCount, listStartOffset);
        return mSession.makeRequest(req);
    }

    /**
     * Pulls single vCard object
     *
     * @param handle handle to the vCard which shall be pulled
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_DONE} or
     * @link #EVENT_PULL_VCARD_ERROR} in case of failure
     */
    public boolean pullVcardEntry(String handle) {
        return pullVcardEntry(handle, (byte) 0, VCARD_TYPE_21);
    }

    /**
     * Pulls single vCard object
     *
     * @param handle handle to the vCard which shall be pulled
     * @param filter bit mask of the vCard fields that shall be included in the
     *            resulting vCard
     * @param format resulting vCard version
     * @return <code>true</code> if request has been sent successfully;
     *         <code>false</code> otherwise; upon completion PCE sends
     *         {@link #EVENT_PULL_VCARD_DONE}
     * @link #EVENT_PULL_VCARD_ERROR} in case of failure
     */
    public boolean pullVcardEntry(String handle, long filter, byte format) {
        BluetoothPbapRequest req = new BluetoothPbapRequestPullVcardEntry(handle, filter, format);
        return mSession.makeRequest(req);
    }

    public boolean setAuthResponse(String key) {
        Log.d(TAG, " setAuthResponse key=" + key);
        return mSession.setAuthResponse(key);
    }
}
