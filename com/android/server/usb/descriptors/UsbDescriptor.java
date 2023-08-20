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
package com.android.server.usb.descriptors;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.android.server.usb.descriptors.report.ReportCanvas;
import com.android.server.usb.descriptors.report.Reporting;
import com.android.server.usb.descriptors.report.UsbStrings;

/*
 * Some notes about UsbDescriptor and its subclasses.
 *
 * It is assumed that the user of the UsbDescriptorParser knows what they are doing
 * so NO PROTECTION is implemented against "improper" use. Such uses are specifically:
 * allocating a UsbDescriptor (subclass) object outside of the context of parsing/reading
 * a rawdescriptor stream and perhaps accessing fields which have not been inialized (by
 * parsing/reading or course).
 */

/**
 * @hide
 * Common superclass for all USB Descriptors.
 */
public abstract class UsbDescriptor implements Reporting {
    private static final String TAG = "UsbDescriptor";

    protected int mHierarchyLevel;

    protected final int mLength;    // 0:1 bLength Number Size of the Descriptor in Bytes (18 bytes)
    // we store this as an int because Java bytes are SIGNED.
    protected final byte mType;     // 1:1 bDescriptorType Constant Device Descriptor (0x01)

    private byte[] mRawData;

    private static final int SIZE_STRINGBUFFER = 256;
    private static byte[] sStringBuffer = new byte[SIZE_STRINGBUFFER];

    // Status
    public static final int STATUS_UNPARSED = 0;
    public static final int STATUS_PARSED_OK = 1;
    public static final int STATUS_PARSED_UNDERRUN = 2;
    public static final int STATUS_PARSED_OVERRUN = 3;
    public static final int STATUS_PARSE_EXCEPTION = 4;

    private int mStatus = STATUS_UNPARSED;

    private static String[] sStatusStrings = {
            "UNPARSED", "PARSED - OK", "PARSED - UNDERRUN", "PARSED - OVERRUN"};

    private int mOverUnderRunCount;

    // Descriptor Type IDs
    public static final byte DESCRIPTORTYPE_DEVICE = 0x01;            // 1
    public static final byte DESCRIPTORTYPE_CONFIG = 0x02;            // 2
    public static final byte DESCRIPTORTYPE_STRING = 0x03;            // 3
    public static final byte DESCRIPTORTYPE_INTERFACE = 0x04;         // 4
    public static final byte DESCRIPTORTYPE_ENDPOINT = 0x05;          // 5
    public static final byte DESCRIPTORTYPE_INTERFACEASSOC = 0x0B;    // 11
    public static final byte DESCRIPTORTYPE_BOS = 0x0F;               // 15
    public static final byte DESCRIPTORTYPE_CAPABILITY = 0x10;        // 16

    public static final byte DESCRIPTORTYPE_HID = 0x21;                // 33
    public static final byte DESCRIPTORTYPE_REPORT = 0x22;             // 34
    public static final byte DESCRIPTORTYPE_PHYSICAL = 0x23;           // 35
    public static final byte DESCRIPTORTYPE_CLASSSPECIFIC_INTERFACE = 0x24;    // 36
    public static final byte DESCRIPTORTYPE_CLASSSPECIFIC_ENDPOINT = 0x25;     // 37
    public static final byte DESCRIPTORTYPE_HUB = 0x29;                // 41
    public static final byte DESCRIPTORTYPE_SUPERSPEED_HUB = 0x2A;     // 42
    public static final byte DESCRIPTORTYPE_ENDPOINT_COMPANION = 0x30; // 48

    // Class IDs
    public static final int CLASSID_DEVICE = 0x00;
    public static final int CLASSID_AUDIO = 0x01;
    public static final int CLASSID_COM = 0x02;
    public static final int CLASSID_HID = 0x03;
    // public static final int CLASSID_??? =       0x04;
    public static final int CLASSID_PHYSICAL = 0x05;
    public static final int CLASSID_IMAGE = 0x06;
    public static final int CLASSID_PRINTER = 0x07;
    public static final int CLASSID_STORAGE = 0x08;
    public static final int CLASSID_HUB = 0x09;
    public static final int CLASSID_CDC_CONTROL = 0x0A;
    public static final int CLASSID_SMART_CARD = 0x0B;
    //public static final int CLASSID_??? =        0x0C;
    public static final int CLASSID_SECURITY = 0x0D;
    public static final int CLASSID_VIDEO = 0x0E;
    public static final int CLASSID_HEALTHCARE = 0x0F;
    public static final int CLASSID_AUDIOVIDEO = 0x10;
    public static final int CLASSID_BILLBOARD = 0x11;
    public static final int CLASSID_TYPECBRIDGE = 0x12;
    public static final int CLASSID_DIAGNOSTIC = 0xDC;
    public static final int CLASSID_WIRELESS = 0xE0;
    public static final int CLASSID_MISC = 0xEF;
    public static final int CLASSID_APPSPECIFIC = 0xFE;
    public static final int CLASSID_VENDSPECIFIC = 0xFF;

    // Audio Subclass codes
    public static final int AUDIO_SUBCLASS_UNDEFINED = 0x00;
    public static final int AUDIO_AUDIOCONTROL = 0x01;
    public static final int AUDIO_AUDIOSTREAMING = 0x02;
    public static final int AUDIO_MIDISTREAMING = 0x03;

    // Request IDs
    public static final int REQUEST_GET_STATUS = 0x00;
    public static final int REQUEST_CLEAR_FEATURE = 0x01;
    public static final int REQUEST_SET_FEATURE = 0x03;
    public static final int REQUEST_GET_ADDRESS = 0x05;
    public static final int REQUEST_GET_DESCRIPTOR = 0x06;
    public static final int REQUEST_SET_DESCRIPTOR = 0x07;
    public static final int REQUEST_GET_CONFIGURATION = 0x08;
    public static final int REQUEST_SET_CONFIGURATION = 0x09;

    // USB control transfer timeout
    public static final int USB_CONTROL_TRANSFER_TIMEOUT_MS = 200;

    /**
     * @throws IllegalArgumentException
     */
    UsbDescriptor(int length, byte type) {
        // a descriptor has at least a length byte and type byte
        // one could imagine an empty one otherwise
        if (length < 2) {
            // huh?
            throw new IllegalArgumentException();
        }

        mLength = length;
        mType = type;
    }

    public int getLength() {
        return mLength;
    }

    public byte getType() {
        return mType;
    }

    public int getStatus() {
        return mStatus;
    }

    public void setStatus(int status) {
        mStatus = status;
    }

    public int getOverUnderRunCount() {
        return mOverUnderRunCount;
    }
    public String getStatusString() {
        return sStatusStrings[mStatus];
    }

    public byte[] getRawData() {
        return mRawData;
    }

    /**
     * Called by the parser for any necessary cleanup.
     */
    public void postParse(ByteStream stream) {
        // Status
        int bytesRead = stream.getReadCount();
        if (bytesRead < mLength) {
            // Too cold...
            stream.advance(mLength - bytesRead);
            mStatus = STATUS_PARSED_UNDERRUN;
            mOverUnderRunCount = mLength - bytesRead;
            Log.w(TAG, "UNDERRUN t:0x" + Integer.toHexString(mType)
                    + " r: " + bytesRead + " < l: " + mLength);
        } else if (bytesRead > mLength) {
            // Too hot...
            stream.reverse(bytesRead - mLength);
            mStatus = STATUS_PARSED_OVERRUN;
            mOverUnderRunCount = bytesRead - mLength;
            Log.w(TAG, "OVERRRUN t:0x" + Integer.toHexString(mType)
                    + " r: " + bytesRead + " > l: " + mLength);
        } else {
            // Just right!
            mStatus = STATUS_PARSED_OK;
        }
    }

    /**
     * Reads data fields from specified raw-data stream.
     */
    public int parseRawDescriptors(ByteStream stream) {
        int numRead = stream.getReadCount();
        int dataLen = mLength - numRead;
        if (dataLen > 0) {
            mRawData = new byte[dataLen];
            for (int index = 0; index < dataLen; index++) {
                mRawData[index] = stream.getByte();
            }
        }
        return mLength;
    }

    /**
     * Gets a string for the specified index from the USB Device's string descriptors.
     */
    public static String getUsbDescriptorString(UsbDeviceConnection connection, byte strIndex) {
        String usbStr = "";
        if (strIndex != 0) {
            try {
                int rdo = connection.controlTransfer(
                        UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD,
                        REQUEST_GET_DESCRIPTOR,
                        (DESCRIPTORTYPE_STRING << 8) | strIndex,
                        0,
                        sStringBuffer,
                        0xFF,
                        USB_CONTROL_TRANSFER_TIMEOUT_MS);
                if (rdo >= 0) {
                    usbStr = new String(sStringBuffer, 2, rdo - 2, "UTF-16LE");
                } else {
                    usbStr = "?";
                }
            } catch (Exception e) {
                Log.e(TAG, "Can not communicate with USB device", e);
            }
        }
        return usbStr;
    }

    private void reportParseStatus(ReportCanvas canvas) {
        int status = getStatus();
        switch (status) {
            case UsbDescriptor.STATUS_PARSED_OK:
                break;  // no need to report

            case UsbDescriptor.STATUS_UNPARSED:
            case UsbDescriptor.STATUS_PARSED_UNDERRUN:
            case UsbDescriptor.STATUS_PARSED_OVERRUN:
                canvas.writeParagraph("status: " + getStatusString()
                        + " [" + getOverUnderRunCount() + "]", true);
                break;
        }
    }

    @Override
    public void report(ReportCanvas canvas) {
        String descTypeStr = UsbStrings.getDescriptorName(getType());
        String text = descTypeStr + ": " + ReportCanvas.getHexString(getType())
                + " Len: " + getLength();
        if (mHierarchyLevel != 0) {
            canvas.writeHeader(mHierarchyLevel, text);
        } else {
            canvas.writeParagraph(text, false);
        }

        if (getStatus() != STATUS_PARSED_OK) {
            reportParseStatus(canvas);
        }
    }

    @Override
    public void shortReport(ReportCanvas canvas) {
        String descTypeStr = UsbStrings.getDescriptorName(getType());
        String text = descTypeStr + ": " + ReportCanvas.getHexString(getType())
                + " Len: " + getLength();
        canvas.writeParagraph(text, false);
    }

    /*
     * Logging Helpers
     */
    static String getDescriptorName(byte descriptorType, int descriptorLength) {
        String name = UsbStrings.getDescriptorName(descriptorType);
        if (name != null) {
            return name;
        } else {
            return "Unknown Descriptor Type " + descriptorType
                + " 0x" + Integer.toHexString(descriptorType)
                + " length:" + descriptorLength;
        }
    }

    static void logDescriptorName(byte descriptorType, int descriptorLength) {
        if (UsbDescriptorParser.DEBUG) {
            Log.d(TAG, "----> " + getDescriptorName(descriptorType, descriptorLength));
        }
    }
}
