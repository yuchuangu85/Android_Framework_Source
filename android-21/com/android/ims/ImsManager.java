/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsEcbmListener;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsService;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.ImsCallSession;
import com.android.ims.internal.IImsConfig;

import java.util.HashMap;

/**
 * Provides APIs for IMS services, such as initiating IMS calls, and provides access to
 * the operator's IMS network. This class is the starting point for any IMS actions.
 * You can acquire an instance of it with {@link #getInstance getInstance()}.</p>
 * <p>The APIs in this class allows you to:</p>
 *
 * @hide
 */
public class ImsManager {
    /*
     * Shared preference constants storing the "Enhanced 4G LTE Mode" configuration
     */
    public static final String IMS_SHARED_PREFERENCES = "IMS_PREFERENCES";
    public static final String KEY_IMS_ON = "IMS";
    public static final boolean IMS_DEFAULT_SETTING = true;

    /*
     * Debug flag to override configuration flag
     */
    public static final String PROPERTY_DBG_VOLTE_VT_AVAIL_OVERRIDE = "persist.dbg.volte_avail_ovr";
    public static final int PROPERTY_DBG_VOLTE_VT_AVAIL_OVERRIDE_DEFAULT = 0;

    /**
     * For accessing the IMS related service.
     * Internal use only.
     * @hide
     */
    private static final String IMS_SERVICE = "ims";

    /**
     * The result code to be sent back with the incoming call {@link PendingIntent}.
     * @see #open(PendingIntent, ImsConnectionStateListener)
     */
    public static final int INCOMING_CALL_RESULT_CODE = 101;

    /**
     * Key to retrieve the call ID from an incoming call intent.
     * @see #open(PendingIntent, ImsConnectionStateListener)
     */
    public static final String EXTRA_CALL_ID = "android:imsCallID";

    /**
     * Action to broadcast when ImsService is up.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_UP =
            "com.android.ims.IMS_SERVICE_UP";

    /**
     * Action to broadcast when ImsService is down.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_DOWN =
            "com.android.ims.IMS_SERVICE_DOWN";

    /**
     * Part of the ACTION_IMS_SERVICE_UP or _DOWN intents.
     * A long value; the subId corresponding to the IMS service coming up or down.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_SUBID = "android:subid";

    /**
     * Action for the incoming call intent for the Phone app.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_IMS_INCOMING_CALL =
            "com.android.ims.IMS_INCOMING_CALL";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * An integer value; service identifier obtained from {@link ImsManager#open}.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_SERVICE_ID = "android:imsServiceId";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * An boolean value; Flag to indicate that the incoming call is a normal call or call for USSD.
     * The value "true" indicates that the incoming call is for USSD.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_USSD = "android:ussd";

    private static final String TAG = "ImsManager";
    private static final boolean DBG = true;

    private static HashMap<Long, ImsManager> sImsManagerInstances =
            new HashMap<Long, ImsManager>();

    private Context mContext;
    private long mSubId;
    private IImsService mImsService = null;
    private ImsServiceDeathRecipient mDeathRecipient = new ImsServiceDeathRecipient();
    // Ut interface for the supplementary service configuration
    private ImsUt mUt = null;
    // Interface to get/set ims config items
    private ImsConfig mConfig = null;

    // ECBM interface
    private ImsEcbm mEcbm = null;

    /**
     * Gets a manager instance.
     *
     * @param context application context for creating the manager object
     * @param subId the subscription ID for the IMS Service
     * @return the manager instance corresponding to the subId
     */
    public static ImsManager getInstance(Context context, long subId) {
        synchronized (sImsManagerInstances) {
            if (sImsManagerInstances.containsKey(subId))
                return sImsManagerInstances.get(subId);

            ImsManager mgr = new ImsManager(context, subId);
            sImsManagerInstances.put(subId, mgr);

            return mgr;
        }
    }

    /**
     * Returns the user configuration of Enhanced 4G LTE Mode setting
     */
    public static boolean isEnhanced4gLteModeSettingEnabledByUser(Context context) {
        return context.getSharedPreferences(IMS_SHARED_PREFERENCES,
                Context.MODE_WORLD_READABLE).getBoolean(KEY_IMS_ON,
                IMS_DEFAULT_SETTING);
    }

    /**
     * Returns a platform configuration which may override the user setting.
     */
    public static boolean isEnhanced4gLteModeSettingEnabledByPlatform(Context context) {
        if (SystemProperties.getInt(PROPERTY_DBG_VOLTE_VT_AVAIL_OVERRIDE,
                PROPERTY_DBG_VOLTE_VT_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return
                context.getResources().getBoolean(
                        com.android.internal.R.bool.config_device_volte_vt_available) &&
                context.getResources().getBoolean(
                        com.android.internal.R.bool.config_carrier_volte_vt_available);
    }

    private ImsManager(Context context, long subId) {
        mContext = context;
        mSubId = subId;
        createImsService(true);
    }

    /**
     * Opens the IMS service for making calls and/or receiving generic IMS calls.
     * The caller may make subsquent calls through {@link #makeCall}.
     * The IMS service will register the device to the operator's network with the credentials
     * (from ISIM) periodically in order to receive calls from the operator's network.
     * When the IMS service receives a new call, it will send out an intent with
     * the provided action string.
     * The intent contains a call ID extra {@link getCallId} and it can be used to take a call.
     *
     * @param serviceClass a service class specified in {@link ImsServiceClass}
     *      For VoLTE service, it MUST be a {@link ImsServiceClass#MMTEL}.
     * @param incomingCallPendingIntent When an incoming call is received,
     *        the IMS service will call {@link PendingIntent#send(Context, int, Intent)} to
     *        send back the intent to the caller with {@link #INCOMING_CALL_RESULT_CODE}
     *        as the result code and the intent to fill in the call ID; It cannot be null
     * @param listener To listen to IMS registration events; It cannot be null
     * @return identifier (greater than 0) for the specified service
     * @throws NullPointerException if {@code incomingCallPendingIntent}
     *      or {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     * @see #getCallId
     * @see #getServiceId
     */
    public int open(int serviceClass, PendingIntent incomingCallPendingIntent,
            ImsConnectionStateListener listener) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        if (incomingCallPendingIntent == null) {
            throw new NullPointerException("incomingCallPendingIntent can't be null");
        }

        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }

        int result = 0;

        try {
            result = mImsService.open(serviceClass, incomingCallPendingIntent,
                    createRegistrationListenerProxy(serviceClass, listener));
        } catch (RemoteException e) {
            throw new ImsException("open()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }

        if (result <= 0) {
            // If the return value is a minus value,
            // it means that an error occurred in the service.
            // So, it needs to convert to the reason code specified in ImsReasonInfo.
            throw new ImsException("open()", (result * (-1)));
        }

        return result;
    }

    /**
     * Closes the specified service ({@link ImsServiceClass}) not to make/receive calls.
     * All the resources that were allocated to the service are also released.
     *
     * @param serviceId a service id to be closed which is obtained from {@link ImsManager#open}
     * @throws ImsException if calling the IMS service results in an error
     */
    public void close(int serviceId) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsService.close(serviceId);
        } catch (RemoteException e) {
            throw new ImsException("close()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        } finally {
            mUt = null;
            mConfig = null;
            mEcbm = null;
        }
    }

    /**
     * Gets the configuration interface to provision / withdraw the supplementary service settings.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @return the Ut interface instance
     * @throws ImsException if getting the Ut interface results in an error
     */
    public ImsUtInterface getSupplementaryServiceConfiguration(int serviceId)
            throws ImsException {
        // FIXME: manage the multiple Ut interfaces based on the service id
        if (mUt == null) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsUt iUt = mImsService.getUtInterface(serviceId);

                if (iUt == null) {
                    throw new ImsException("getSupplementaryServiceConfiguration()",
                            ImsReasonInfo.CODE_UT_NOT_SUPPORTED);
                }

                mUt = new ImsUt(iUt);
            } catch (RemoteException e) {
                throw new ImsException("getSupplementaryServiceConfiguration()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }

        return mUt;
    }

    /**
     * Checks if the IMS service has successfully registered to the IMS network
     * with the specified service & call type.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @param serviceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE_N_VIDEO}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     * @return true if the specified service id is connected to the IMS network;
     *        false otherwise
     * @throws ImsException if calling the IMS service results in an error
     */
    public boolean isConnected(int serviceId, int serviceType, int callType)
            throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mImsService.isConnected(serviceId, serviceType, callType);
        } catch (RemoteException e) {
            throw new ImsException("isServiceConnected()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Checks if the specified IMS service is opend.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @return true if the specified service id is opened; false otherwise
     * @throws ImsException if calling the IMS service results in an error
     */
    public boolean isOpened(int serviceId) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mImsService.isOpened(serviceId);
        } catch (RemoteException e) {
            throw new ImsException("isOpened()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Creates a {@link ImsCallProfile} from the service capabilities & IMS registration state.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @param serviceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NONE}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VT_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_RX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_NODIR}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     *        {@link ImsCallProfile#CALL_TYPE_VS_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VS_RX}
     * @return a {@link ImsCallProfile} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCallProfile createCallProfile(int serviceId,
            int serviceType, int callType) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mImsService.createCallProfile(serviceId, serviceType, callType);
        } catch (RemoteException e) {
            throw new ImsException("createCallProfile()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Creates a {@link ImsCall} to make a call.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @param profile a call profile to make the call
     *      (it contains service type, call type, media information, etc.)
     * @param participants participants to invite the conference call
     * @param listener listen to the call events from {@link ImsCall}
     * @return a {@link ImsCall} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCall makeCall(int serviceId, ImsCallProfile profile, String[] callees,
            ImsCall.Listener listener) throws ImsException {
        if (DBG) {
            log("makeCall :: serviceId=" + serviceId
                    + ", profile=" + profile + ", callees=" + callees);
        }

        checkAndThrowExceptionIfServiceUnavailable();

        ImsCall call = new ImsCall(mContext, profile);

        call.setListener(listener);
        ImsCallSession session = createCallSession(serviceId, profile);

        if ((callees != null) && (callees.length == 1)) {
            call.start(session, callees[0]);
        } else {
            call.start(session, callees);
        }

        return call;
    }

    /**
     * Creates a {@link ImsCall} to take an incoming call.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @param incomingCallIntent the incoming call broadcast intent
     * @param listener to listen to the call events from {@link ImsCall}
     * @return a {@link ImsCall} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCall takeCall(int serviceId, Intent incomingCallIntent,
            ImsCall.Listener listener) throws ImsException {
        if (DBG) {
            log("takeCall :: serviceId=" + serviceId
                    + ", incomingCall=" + incomingCallIntent);
        }

        checkAndThrowExceptionIfServiceUnavailable();

        if (incomingCallIntent == null) {
            throw new ImsException("Can't retrieve session with null intent",
                    ImsReasonInfo.CODE_LOCAL_ILLEGAL_ARGUMENT);
        }

        int incomingServiceId = getServiceId(incomingCallIntent);

        if (serviceId != incomingServiceId) {
            throw new ImsException("Service id is mismatched in the incoming call intent",
                    ImsReasonInfo.CODE_LOCAL_ILLEGAL_ARGUMENT);
        }

        String callId = getCallId(incomingCallIntent);

        if (callId == null) {
            throw new ImsException("Call ID missing in the incoming call intent",
                    ImsReasonInfo.CODE_LOCAL_ILLEGAL_ARGUMENT);
        }

        try {
            IImsCallSession session = mImsService.getPendingCallSession(serviceId, callId);

            if (session == null) {
                throw new ImsException("No pending session for the call",
                        ImsReasonInfo.CODE_LOCAL_NO_PENDING_CALL);
            }

            ImsCall call = new ImsCall(mContext, session.getCallProfile());

            call.attachSession(new ImsCallSession(session));
            call.setListener(listener);

            return call;
        } catch (Throwable t) {
            throw new ImsException("takeCall()", t, ImsReasonInfo.CODE_UNSPECIFIED);
        }
    }

    /**
     * Gets the config interface to get/set service/capability parameters.
     *
     * @return the ImsConfig instance.
     * @throws ImsException if getting the setting interface results in an error.
     */
    public ImsConfig getConfigInterface() throws ImsException {

        if (mConfig == null) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsConfig config = mImsService.getConfigInterface();
                if (config == null) {
                    throw new ImsException("getConfigInterface()",
                            ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
                }
                mConfig = new ImsConfig(config);
            } catch (RemoteException e) {
                throw new ImsException("getConfigInterface()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
        if (DBG) log("getConfigInterface(), mConfig= " + mConfig);
        return mConfig;
    }

    /**
     * Gets the call ID from the specified incoming call broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the call ID or null if the intent does not contain it
     */
    private static String getCallId(Intent incomingCallIntent) {
        if (incomingCallIntent == null) {
            return null;
        }

        return incomingCallIntent.getStringExtra(EXTRA_CALL_ID);
    }

    /**
     * Gets the service type from the specified incoming call broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the service identifier or -1 if the intent does not contain it
     */
    private static int getServiceId(Intent incomingCallIntent) {
        if (incomingCallIntent == null) {
            return (-1);
        }

        return incomingCallIntent.getIntExtra(EXTRA_SERVICE_ID, -1);
    }

    /**
     * Binds the IMS service only if the service is not created.
     */
    private void checkAndThrowExceptionIfServiceUnavailable()
            throws ImsException {
        if (mImsService == null) {
            createImsService(true);

            if (mImsService == null) {
                throw new ImsException("Service is unavailable",
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
    }

    private static String getImsServiceName(long subId) {
        // TODO: MSIM implementation needs to decide on service name as a function of subId
        // or value derived from subId (slot ID?)
        return IMS_SERVICE;
    }

    /**
     * Binds the IMS service to make/receive the call.
     */
    private void createImsService(boolean checkService) {
        if (checkService) {
            IBinder binder = ServiceManager.checkService(getImsServiceName(mSubId));

            if (binder == null) {
                return;
            }
        }

        IBinder b = ServiceManager.getService(getImsServiceName(mSubId));

        if (b != null) {
            try {
                b.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
            }
        }

        mImsService = IImsService.Stub.asInterface(b);
    }

    /**
     * Creates a {@link ImsCallSession} with the specified call profile.
     * Use other methods, if applicable, instead of interacting with
     * {@link ImsCallSession} directly.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @param profile a call profile to make the call
     */
    private ImsCallSession createCallSession(int serviceId,
            ImsCallProfile profile) throws ImsException {
        try {
            return new ImsCallSession(mImsService.createCallSession(serviceId, profile, null));
        } catch (RemoteException e) {
            return null;
        }
    }

    private ImsRegistrationListenerProxy createRegistrationListenerProxy(int serviceClass,
            ImsConnectionStateListener listener) {
        ImsRegistrationListenerProxy proxy =
                new ImsRegistrationListenerProxy(serviceClass, listener);
        return proxy;
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }

    private void loge(String s, Throwable t) {
        Rlog.e(TAG, s, t);
    }

    /**
     * Used for turning on IMS.if its off already
     */
    public void turnOnIms() throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsService.turnOnIms();
        } catch (RemoteException e) {
            throw new ImsException("turnOnIms() ", e, ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    public void setAdvanced4GMode(boolean turnOn) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        ImsConfig config = getConfigInterface();
        if (config != null) {
            config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE,
                    TelephonyManager.NETWORK_TYPE_LTE, turnOn ? 1 : 0, null);
            config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE,
                    TelephonyManager.NETWORK_TYPE_LTE, turnOn ? 1 : 0, null);
        }

        if (turnOn) {
            turnOnIms();
        } else if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.imsServiceAllowTurnOff)) {
            log("setAdvanced4GMode() : imsServiceAllowTurnOff -> turnOffIms");
            turnOffIms();
        }
    }

    /**
     * Used for turning off IMS completely in order to make the device CSFB'ed.
     * Once turned off, all calls will be over CS.
     */
    public void turnOffIms() throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsService.turnOffIms();
        } catch (RemoteException e) {
            throw new ImsException("turnOffIms() ", e, ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Death recipient class for monitoring IMS service.
     */
    private class ImsServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mImsService = null;
            mUt = null;
            mConfig = null;
            mEcbm = null;

            if (mContext != null) {
                Intent intent = new Intent(ACTION_IMS_SERVICE_DOWN);
                intent.putExtra(EXTRA_SUBID, mSubId);
                mContext.sendBroadcast(new Intent(intent));
            }
        }
    }

    /**
     * Adapter class for {@link IImsRegistrationListener}.
     */
    private class ImsRegistrationListenerProxy extends IImsRegistrationListener.Stub {
        private int mServiceClass;
        private ImsConnectionStateListener mListener;

        public ImsRegistrationListenerProxy(int serviceClass,
                ImsConnectionStateListener listener) {
            mServiceClass = serviceClass;
            mListener = listener;
        }

        public boolean isSameProxy(int serviceClass) {
            return (mServiceClass == serviceClass);
        }

        @Override
        public void registrationConnected() {
            if (DBG) {
                log("registrationConnected ::");
            }

            if (mListener != null) {
                mListener.onImsConnected();
            }
        }

        @Override
        public void registrationDisconnected() {
            if (DBG) {
                log("registrationDisconnected ::");
            }

            if (mListener != null) {
                mListener.onImsDisconnected();
            }
        }

        @Override
        public void registrationResumed() {
            if (DBG) {
                log("registrationResumed ::");
            }

            if (mListener != null) {
                mListener.onImsResumed();
            }
        }

        @Override
        public void registrationSuspended() {
            if (DBG) {
                log("registrationSuspended ::");
            }

            if (mListener != null) {
                mListener.onImsSuspended();
            }
        }

        @Override
        public void registrationServiceCapabilityChanged(int serviceClass, int event) {
            log("registrationServiceCapabilityChanged :: serviceClass=" +
                    serviceClass + ", event=" + event);

            if (mListener != null) {
                mListener.onImsConnected();
            }
        }

        @Override
        public void registrationFeatureCapabilityChanged(int serviceClass,
                int[] enabledFeatures, int[] disabledFeatures) {
            log("registrationFeatureCapabilityChanged :: serviceClass=" +
                    serviceClass);
            if (mListener != null) {
                mListener.onFeatureCapabilityChanged(serviceClass,
                        enabledFeatures, disabledFeatures);
            }
        }

    }
    /**
     * Gets the ECBM interface to request ECBM exit.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @return the ECBM interface instance
     * @throws ImsException if getting the ECBM interface results in an error
     */
    public ImsEcbm getEcbmInterface(int serviceId) throws ImsException {
        if (mEcbm == null) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsEcbm iEcbm = mImsService.getEcbmInterface(serviceId);

                if (iEcbm == null) {
                    throw new ImsException("getEcbmInterface()",
                            ImsReasonInfo.CODE_ECBM_NOT_SUPPORTED);
                }
                mEcbm = new ImsEcbm(iEcbm);
            } catch (RemoteException e) {
                throw new ImsException("getEcbmInterface()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
        return mEcbm;
    }
}
