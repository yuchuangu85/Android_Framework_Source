/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony;

import com.android.internal.telephony.RILConstants;

import android.telephony.Rlog;

/**
 * {@hide}
 */
public class CommandException extends RuntimeException {
    private Error mError;

    public enum Error {
        INVALID_RESPONSE,
        RADIO_NOT_AVAILABLE,
        GENERIC_FAILURE,
        PASSWORD_INCORRECT,
        SIM_PIN2,
        SIM_PUK2,
        REQUEST_NOT_SUPPORTED,
        OP_NOT_ALLOWED_DURING_VOICE_CALL,
        OP_NOT_ALLOWED_BEFORE_REG_NW,
        SMS_FAIL_RETRY,
        SIM_ABSENT,
        SUBSCRIPTION_NOT_AVAILABLE,
        MODE_NOT_SUPPORTED,
        FDN_CHECK_FAILURE,
        ILLEGAL_SIM_OR_ME,
        MISSING_RESOURCE,
        NO_SUCH_ELEMENT,
        SUBSCRIPTION_NOT_SUPPORTED,
        DIAL_MODIFIED_TO_USSD,
        DIAL_MODIFIED_TO_SS,
        DIAL_MODIFIED_TO_DIAL,
        USSD_MODIFIED_TO_DIAL,
        USSD_MODIFIED_TO_SS,
        USSD_MODIFIED_TO_USSD,
        SS_MODIFIED_TO_DIAL,
        SS_MODIFIED_TO_USSD,
        SS_MODIFIED_TO_SS,
    }

    public CommandException(Error e) {
        super(e.toString());
        mError = e;
    }

    public static CommandException
    fromRilErrno(int ril_errno) {
        switch(ril_errno) {
            case RILConstants.SUCCESS:                       return null;
            case RILConstants.RIL_ERRNO_INVALID_RESPONSE:
                return new CommandException(Error.INVALID_RESPONSE);
            case RILConstants.RADIO_NOT_AVAILABLE:
                return new CommandException(Error.RADIO_NOT_AVAILABLE);
            case RILConstants.GENERIC_FAILURE:
                return new CommandException(Error.GENERIC_FAILURE);
            case RILConstants.PASSWORD_INCORRECT:
                return new CommandException(Error.PASSWORD_INCORRECT);
            case RILConstants.SIM_PIN2:
                return new CommandException(Error.SIM_PIN2);
            case RILConstants.SIM_PUK2:
                return new CommandException(Error.SIM_PUK2);
            case RILConstants.REQUEST_NOT_SUPPORTED:
                return new CommandException(Error.REQUEST_NOT_SUPPORTED);
            case RILConstants.OP_NOT_ALLOWED_DURING_VOICE_CALL:
                return new CommandException(Error.OP_NOT_ALLOWED_DURING_VOICE_CALL);
            case RILConstants.OP_NOT_ALLOWED_BEFORE_REG_NW:
                return new CommandException(Error.OP_NOT_ALLOWED_BEFORE_REG_NW);
            case RILConstants.SMS_SEND_FAIL_RETRY:
                return new CommandException(Error.SMS_FAIL_RETRY);
            case RILConstants.SIM_ABSENT:
                return new CommandException(Error.SIM_ABSENT);
            case RILConstants.SUBSCRIPTION_NOT_AVAILABLE:
                return new CommandException(Error.SUBSCRIPTION_NOT_AVAILABLE);
            case RILConstants.MODE_NOT_SUPPORTED:
                return new CommandException(Error.MODE_NOT_SUPPORTED);
            case RILConstants.FDN_CHECK_FAILURE:
                return new CommandException(Error.FDN_CHECK_FAILURE);
            case RILConstants.ILLEGAL_SIM_OR_ME:
                return new CommandException(Error.ILLEGAL_SIM_OR_ME);
            case RILConstants.MISSING_RESOURCE:
                return new CommandException(Error.MISSING_RESOURCE);
            case RILConstants.NO_SUCH_ELEMENT:
                return new CommandException(Error.NO_SUCH_ELEMENT);
            case RILConstants.SUBSCRIPTION_NOT_SUPPORTED:
                return new CommandException(Error.SUBSCRIPTION_NOT_SUPPORTED);
            case RILConstants.DIAL_MODIFIED_TO_USSD:
                return new CommandException(Error.DIAL_MODIFIED_TO_USSD);
            case RILConstants.DIAL_MODIFIED_TO_SS:
                return new CommandException(Error.DIAL_MODIFIED_TO_SS);
            case RILConstants.DIAL_MODIFIED_TO_DIAL:
                return new CommandException(Error.DIAL_MODIFIED_TO_DIAL);
            case RILConstants.USSD_MODIFIED_TO_DIAL:
                return new CommandException(Error.USSD_MODIFIED_TO_DIAL);
            case RILConstants.USSD_MODIFIED_TO_SS:
                return new CommandException(Error.USSD_MODIFIED_TO_SS);
            case RILConstants.USSD_MODIFIED_TO_USSD:
                return new CommandException(Error.USSD_MODIFIED_TO_USSD);
            case RILConstants.SS_MODIFIED_TO_DIAL:
                return new CommandException(Error.SS_MODIFIED_TO_DIAL);
            case RILConstants.SS_MODIFIED_TO_USSD:
                return new CommandException(Error.SS_MODIFIED_TO_USSD);
            case RILConstants.SS_MODIFIED_TO_SS:
                return new CommandException(Error.SS_MODIFIED_TO_SS);
            default:
                Rlog.e("GSM", "Unrecognized RIL errno " + ril_errno);
                return new CommandException(Error.INVALID_RESPONSE);
        }
    }

    public Error getCommandError() {
        return mError;
    }



}
