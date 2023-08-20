/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.usb;

import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Converts between raw MIDI packets and USB MIDI 1.0 packets.
 * This is NOT thread-safe. Please handle locking outside this function for multiple threads.
 * For data mapping to an invalid cable number, this converter will use the first cable.
 */
public class UsbMidiPacketConverter {
    private static final String TAG = "UsbMidiPacketConverter";

    // Refer to Table 4-1 in USB MIDI 1.0 spec.
    private static final int[] PAYLOAD_SIZE = new int[]{
            /* 0x00 */ -1, // Miscellaneous function codes. Reserved for future extensions.
            /* 0x01 */ -1, // Cable events. Reserved for future expansion.
            /* 0x02 */  2, // Two-byte System Common messages like MTC, SongSelect, etc
            /* 0x03 */  3, // Three-byte System Common messages like SPP, etc.
            /* 0x04 */  3, // SysEx starts or continues
            /* 0x05 */  1, // Single-byte System Common Message or single-byte SysEx ends.
            /* 0x06 */  2, // SysEx ends with following two bytes.
            /* 0x07 */  3, // SysEx ends with following three bytes.
            /* 0x08 */  3, // Note-off
            /* 0x09 */  3, // Note-on
            /* 0x0a */  3, // Poly-KeyPress
            /* 0x0b */  3, // Control Change
            /* 0x0c */  2, // Program Change
            /* 0x0d */  2, // Channel Pressure
            /* 0x0e */  3, // PitchBend Change
            /* 0x0f */  1  // Single Byte
    };

    // Each System MIDI message is a certain size. These can be mapped to a
    // Code Index number defined in Table 4-1 of USB MIDI 1.0.
    private static final int[] CODE_INDEX_NUMBER_FROM_SYSTEM_TYPE = new int[]{
            /* 0x00 */ -1, // Start of Exclusive. Special case.
            /* 0x01 */  2, // MIDI Time Code. Two byte message
            /* 0x02 */  3, // Song Point Pointer. Three byte message
            /* 0x03 */  2, // Song Select. Two byte message
            /* 0x04 */ -1, // Undefined MIDI System Common
            /* 0x05 */ -1, // Undefined MIDI System Common
            /* 0x06 */  5, // Tune Request. One byte message
            /* 0x07 */ -1, // End of Exclusive. Special case.
            /* 0x08 */  5, // Timing clock. One byte message
            /* 0x09 */ -1, // Undefined MIDI System Real-time
            /* 0x0a */  5, // Start. One byte message
            /* 0x0b */  5, // Continue. One byte message
            /* 0x0c */  5, // Stop. One byte message
            /* 0x0d */ -1, // Undefined MIDI System Real-time
            /* 0x0e */  5, // Active Sensing. One byte message
            /* 0x0f */  5  // System Reset. One byte message
    };

    // These code index numbers also come from Table 4-1 in USB MIDI 1.0 spec.
    private static final byte CODE_INDEX_NUMBER_SYSEX_STARTS_OR_CONTINUES = 0x4;
    private static final byte CODE_INDEX_NUMBER_SINGLE_BYTE = 0xF;
    private static final byte CODE_INDEX_NUMBER_SYSEX_END_SINGLE_BYTE = (byte) 0x5;

    // System messages are defined in MIDI.
    private static final byte FIRST_SYSTEM_MESSAGE_VALUE = (byte) 0xF0;
    private static final byte SYSEX_START_EXCLUSIVE = (byte) 0xF0;
    private static final byte SYSEX_END_EXCLUSIVE = (byte) 0xF7;

    private UsbMidiEncoder[] mUsbMidiEncoders;
    private ByteArrayOutputStream mEncoderOutputStream = new ByteArrayOutputStream();

    private UsbMidiDecoder mUsbMidiDecoder;

    /**
     * Creates encoders.
     *
     * createEncoders() must be called before raw MIDI can be converted to USB MIDI.
     *
     * @param size the number of encoders to create
     */
    public void createEncoders(int size) {
        mUsbMidiEncoders = new UsbMidiEncoder[size];
        for (int i = 0; i < size; i++) {
            mUsbMidiEncoders[i] = new UsbMidiEncoder(i);
        }
    }

    /**
     * Converts a raw MIDI array into a USB MIDI array.
     *
     * Call pullEncodedMidiPackets to retrieve the byte array.
     *
     * @param midiBytes the raw MIDI bytes to convert
     * @param size the size of usbMidiBytes
     * @param encoderId which encoder to use
     */
    public void encodeMidiPackets(byte[] midiBytes, int size, int encoderId) {
        // Use the first encoder if the encoderId is invalid.
        if (encoderId >= mUsbMidiEncoders.length) {
            Log.w(TAG, "encoderId " + encoderId + " invalid");
            encoderId = 0;
        }
        byte[] encodedPacket = mUsbMidiEncoders[encoderId].encode(midiBytes, size);
        mEncoderOutputStream.write(encodedPacket, 0, encodedPacket.length);
    }

    /**
     * Returns the encoded MIDI packets from encodeMidiPackets
     *
     * @return byte array of USB MIDI packets
     */
    public byte[] pullEncodedMidiPackets() {
        byte[] output = mEncoderOutputStream.toByteArray();
        mEncoderOutputStream.reset();
        return output;
    }

    /**
     * Creates decoders.
     *
     * createDecoders() must be called before USB MIDI can be converted to raw MIDI.
     *
     * @param size the number of decoders to create
     */
    public void createDecoders(int size) {
        mUsbMidiDecoder = new UsbMidiDecoder(size);
    }

    /**
     * Converts a USB MIDI array into a multiple MIDI arrays, one per cable.
     *
     * Call pullDecodedMidiPackets to retrieve the byte array.
     *
     * @param usbMidiBytes the USB MIDI bytes to convert
     * @param size the size of usbMidiBytes
     */
    public void decodeMidiPackets(byte[] usbMidiBytes, int size) {
        mUsbMidiDecoder.decode(usbMidiBytes, size);
    }

    /**
     * Returns the decoded MIDI packets from decodeMidiPackets
     *
     * @param cableNumber the cable to pull data from
     * @return byte array of raw MIDI packets
     */
    public byte[] pullDecodedMidiPackets(int cableNumber) {
        return mUsbMidiDecoder.pullBytes(cableNumber);
    }

    private class UsbMidiDecoder {
        int mNumJacks;
        ByteArrayOutputStream[] mDecodedByteArrays;

        UsbMidiDecoder(int numJacks) {
            mNumJacks = numJacks;
            mDecodedByteArrays = new ByteArrayOutputStream[numJacks];
            for (int i = 0; i < numJacks; i++) {
                mDecodedByteArrays[i] = new ByteArrayOutputStream();
            }
        }

        // Decodes the data from USB MIDI to raw MIDI.
        // Each valid 4 byte input maps to a 1-3 byte output.
        // Reference the USB MIDI 1.0 spec for more info.
        public void decode(byte[] usbMidiBytes, int size) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            if (size % 4 != 0) {
                Log.w(TAG, "size " + size + " not multiple of 4");
            }
            for (int i = 0; i + 3 < size; i += 4) {
                int cableNumber = (usbMidiBytes[i] >> 4) & 0x0f;
                int codeIndex = usbMidiBytes[i] & 0x0f;
                int numPayloadBytes = PAYLOAD_SIZE[codeIndex];
                if (numPayloadBytes < 0) {
                    continue;
                }
                // Use the first cable if the cable number is invalid.
                if (cableNumber >= mNumJacks) {
                    Log.w(TAG, "cableNumber " + cableNumber + " invalid");
                    cableNumber = 0;
                }
                mDecodedByteArrays[cableNumber].write(usbMidiBytes, i + 1, numPayloadBytes);
            }
        }

        public byte[] pullBytes(int cableNumber) {
            // Use the first cable if the cable number is invalid.
            if (cableNumber >= mNumJacks) {
                Log.w(TAG, "cableNumber " + cableNumber + " invalid");
                cableNumber = 0;
            }
            byte[] output = mDecodedByteArrays[cableNumber].toByteArray();
            mDecodedByteArrays[cableNumber].reset();
            return output;
        }
    }

    private class UsbMidiEncoder {
        // In order to facilitate large scale transfers, SysEx can be sent in multiple packets.
        // If encode() is called without an SysEx end, we must continue SysEx for the next packet.
        // All other packets should be 3 bytes or less and must be not be broken between packets.
        private byte[] mStoredSystemExclusiveBytes = new byte[3];
        private int mNumStoredSystemExclusiveBytes = 0;
        private boolean mHasSystemExclusiveStarted = false;

        private byte[] mEmptyBytes = new byte[3]; // Used to fill out extra data

        private byte mShiftedCableNumber;

        UsbMidiEncoder(int cableNumber) {
            // Jack Id is always the left nibble of every byte so shift this now.
            mShiftedCableNumber = (byte) (cableNumber << 4);
        }

        // Encodes the data from raw MIDI to USB MIDI.
        // Each valid 1-3 byte input maps to a 4 byte output.
        // Reference the USB MIDI 1.0 spec for more info.
        // MidiFramer is not needed here as this code handles partial packets.
        // Long SysEx messages split between packets will encode and return a
        // byte stream even if the SysEx end has not been sent.
        // If there are less than 3 remaining data bytes in a SysEx message left,
        // these bytes will be combined with the next set of packets.
        public byte[] encode(byte[] midiBytes, int size) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            int curLocation = 0;
            while (curLocation < size) {
                if (midiBytes[curLocation] >= 0) { // Data byte
                    if (mHasSystemExclusiveStarted) {
                        mStoredSystemExclusiveBytes[mNumStoredSystemExclusiveBytes] =
                                midiBytes[curLocation];
                        mNumStoredSystemExclusiveBytes++;
                        if (mNumStoredSystemExclusiveBytes == 3) {
                            outputStream.write(CODE_INDEX_NUMBER_SYSEX_STARTS_OR_CONTINUES
                                    | mShiftedCableNumber);
                            outputStream.write(mStoredSystemExclusiveBytes, 0, 3);
                            mNumStoredSystemExclusiveBytes = 0;
                        }
                    } else {
                        writeSingleByte(outputStream, midiBytes[curLocation]);
                    }
                    curLocation++;
                    continue;
                } else if (midiBytes[curLocation] != SYSEX_END_EXCLUSIVE) {
                    // SysEx operation was interrupted. Pass the data directly down.
                    if (mHasSystemExclusiveStarted) {
                        int index = 0;
                        while (index < mNumStoredSystemExclusiveBytes) {
                            writeSingleByte(outputStream, mStoredSystemExclusiveBytes[index]);
                            index++;
                        }
                        mNumStoredSystemExclusiveBytes = 0;
                        mHasSystemExclusiveStarted = false;
                    }
                }

                if (midiBytes[curLocation] < FIRST_SYSTEM_MESSAGE_VALUE) { // Channel message
                    byte codeIndexNumber = (byte) ((midiBytes[curLocation] >> 4) & 0x0f);
                    int channelMessageSize = PAYLOAD_SIZE[codeIndexNumber];
                    if (curLocation + channelMessageSize <= size) {
                        outputStream.write(codeIndexNumber | mShiftedCableNumber);
                        outputStream.write(midiBytes, curLocation, channelMessageSize);
                        // Fill in the rest of the bytes with 0.
                        outputStream.write(mEmptyBytes, 0, 3 - channelMessageSize);
                        curLocation += channelMessageSize;
                    } else { // The packet is missing data. Use single byte messages.
                        while (curLocation < size) {
                            writeSingleByte(outputStream, midiBytes[curLocation]);
                            curLocation++;
                        }
                    }
                } else if (midiBytes[curLocation] == SYSEX_START_EXCLUSIVE) {
                    mHasSystemExclusiveStarted = true;
                    mStoredSystemExclusiveBytes[0] = midiBytes[curLocation];
                    mNumStoredSystemExclusiveBytes = 1;
                    curLocation++;
                } else if (midiBytes[curLocation] == SYSEX_END_EXCLUSIVE) {
                    // 1 byte is 0x05, 2 bytes is 0x06, and 3 bytes is 0x07
                    outputStream.write((CODE_INDEX_NUMBER_SYSEX_END_SINGLE_BYTE
                            + mNumStoredSystemExclusiveBytes) | mShiftedCableNumber);
                    mStoredSystemExclusiveBytes[mNumStoredSystemExclusiveBytes] =
                            midiBytes[curLocation];
                    mNumStoredSystemExclusiveBytes++;
                    outputStream.write(mStoredSystemExclusiveBytes, 0,
                             mNumStoredSystemExclusiveBytes);
                    // Fill in the rest of the bytes with 0.
                    outputStream.write(mEmptyBytes, 0, 3 - mNumStoredSystemExclusiveBytes);
                    mHasSystemExclusiveStarted = false;
                    mNumStoredSystemExclusiveBytes = 0;
                    curLocation++;
                } else {
                    int systemType = midiBytes[curLocation] & 0x0f;
                    int codeIndexNumber = CODE_INDEX_NUMBER_FROM_SYSTEM_TYPE[systemType];
                    if (codeIndexNumber < 0) { // Unknown type. Use single byte messages.
                        writeSingleByte(outputStream, midiBytes[curLocation]);
                        curLocation++;
                    } else {
                        int systemMessageSize = PAYLOAD_SIZE[codeIndexNumber];
                        if (curLocation + systemMessageSize <= size) {
                            outputStream.write(codeIndexNumber | mShiftedCableNumber);
                            outputStream.write(midiBytes, curLocation, systemMessageSize);
                            // Fill in the rest of the bytes with 0.
                            outputStream.write(mEmptyBytes, 0, 3 - systemMessageSize);
                            curLocation += systemMessageSize;
                        } else { // The packet is missing data. Use single byte messages.
                            while (curLocation < size) {
                                writeSingleByte(outputStream, midiBytes[curLocation]);
                                curLocation++;
                            }
                        }
                    }
                }
            }
            return outputStream.toByteArray();
        }

        private void writeSingleByte(ByteArrayOutputStream outputStream, byte byteToWrite) {
            outputStream.write(CODE_INDEX_NUMBER_SINGLE_BYTE | mShiftedCableNumber);
            outputStream.write(byteToWrite);
            outputStream.write(0);
            outputStream.write(0);
        }
    }
}
