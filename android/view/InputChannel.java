/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

import libcore.util.NativeAllocationRegistry;

/**
 * An input channel specifies the file descriptors used to send input events to
 * a window in another process.  It is Parcelable so that it can be sent
 * to the process that is to receive events.  Only one thread should be reading
 * from an InputChannel at a time.
 *
 * The InputChannel object follows 'move' semantics - there should only be a single owner of the
 * InputChannel object at a time. This is done by transferring ownership of the native object.
 * Typically, the InputEventReceiver is the class that takes over the ownership of InputChannel.
 *
 * Incorrect handling of InputChannel objects will cause hard-to-detect bugs like ANRs and
 * unresponsive UI.
 *
 * If in doubt, consult with the Android Framework Input team about your InputChannel usage.
 * @hide
 */
public final class InputChannel implements Parcelable {
    private static final String TAG = "InputChannel";

    private static final boolean DEBUG = false;

    // To allow the JNI code to find this class on a hostside JVM,
    // we need a nested class here.
    private static class RegistryHolder {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        InputChannel.class.getClassLoader(),
                        nativeGetFinalizer());
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<InputChannel> CREATOR
            = new Parcelable.Creator<InputChannel>() {
        public InputChannel createFromParcel(Parcel source) {
            InputChannel result = new InputChannel();
            result.readFromParcel(source);
            return result;
        }

        public InputChannel[] newArray(int size) {
            return new InputChannel[size];
        }
    };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    // The address of the native object.
    private long mPtr; // used by native code

    private static native long[] nativeOpenInputChannelPair(String name);

    private static native long nativeGetFinalizer();
    private native void nativeDispose(long channel);
    private native long nativeReadFromParcel(Parcel parcel);
    private native void nativeWriteToParcel(Parcel parcel, long channel);
    private native long nativeDup(long channel);
    private native IBinder nativeGetToken(long channel);

    private native String nativeGetName(long channel);
    private native boolean nativeIsValid(long channel);

    public static class UninitializedException extends IllegalStateException {
        public UninitializedException(String message) {
            super(message);
        }
    }

    private void checkValid() {
        if (mPtr == 0 || !nativeIsValid(mPtr)) {
            throw new UninitializedException(
                "InputChannel is not initialized or has already been disposed");
        }
    }

    /**
     * Creates an uninitialized input channel.
     * It can be initialized by reading from a Parcel or by transferring the state of
     * another input channel into this one.
     */
    @UnsupportedAppUsage
    public InputChannel() {
    }

    /**
     *  Set Native input channel object from native space.
     *  @param nativeChannel the native channel object.
     */
    private void setNativeInputChannel(long nativeChannel) {
        if (nativeChannel == 0) {
            throw new IllegalArgumentException("Attempting to set native input channel to null.");
        }
        if (mPtr != 0) {
            throw new IllegalArgumentException("Already has native input channel.");
        }
        if (DEBUG) {
            Slog.d(TAG, "setNativeInputChannel : " +  String.format("%x", nativeChannel));
        }
        RegistryHolder.sRegistry.registerNativeAllocation(this, nativeChannel);
        mPtr = nativeChannel;
    }

    /**
     * Creates a new input channel pair.  One channel should be provided to the input
     * dispatcher and the other to the application's input queue.
     * @param name The descriptive (non-unique) name of the channel pair.
     * @return A pair of input channels.  The first channel is designated as the
     * server channel and should be used to publish input events.  The second channel
     * is designated as the client channel and should be used to consume input events.
     */
    public static InputChannel[] openInputChannelPair(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        if (DEBUG) {
            Slog.d(TAG, "Opening input channel pair '" + name + "'");
        }
        InputChannel channels[] = new InputChannel[2];
        long[] nativeChannels = nativeOpenInputChannelPair(name);
        for (int i = 0; i< 2; i++) {
            channels[i] = new InputChannel();
            channels[i].setNativeInputChannel(nativeChannels[i]);
        }
        return channels;
    }

    /**
     * Gets the name of the input channel.
     * @return The input channel name.
     */
    public String getName() {
        checkValid();
        String name = nativeGetName(mPtr);
        return name != null ? name : "uninitialized";
    }

    /**
     * Disposes the input channel.
     * Explicitly releases the reference this object is holding on the input channel.
     * When all references are released, the input channel will be closed.
     */
    public void dispose() {
        nativeDispose(mPtr);
    }

    /**
     * Creates a copy of this instance to the outParameter. This is used to pass an input channel
     * as an out parameter in a binder call.
     *
     * This function should be avoided. You almost never want to actually make a copy of the
     * channel. Incorrectly storing InputChannel will result in difficult-to-track ANRs in your
     * process. Long-term, the input team is looking into removing this capability altogether.
     *
     * @param other The other input channel instance.
     */
    public void copyTo(InputChannel outParameter) {
        checkValid();
        if (outParameter == null) {
            throw new IllegalArgumentException("outParameter must not be null");
        }
        if (outParameter.mPtr != 0) {
            throw new IllegalArgumentException("Other object already has a native input channel.");
        }
        outParameter.setNativeInputChannel(nativeDup(mPtr));
    }

    /**
     * Duplicates the input channel.
     * This function should be avoided. You almost never want to actually make a copy of the
     * channel. Incorrectly storing InputChannel will result in difficult-to-track ANRs in your
     * process. Long-term, the input team is looking into removing this capability altogether.
     */
    public InputChannel dup() {
        checkValid();
        InputChannel target = new InputChannel();
        target.setNativeInputChannel(nativeDup(mPtr));
        return target;
    }

    @Override
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    public void readFromParcel(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        long nativeIn = nativeReadFromParcel(in);
        if (nativeIn != 0) {
            setNativeInputChannel(nativeIn);
        }
    }

    /**
     * This is a one-way, destructive operation. Sending the InputChannel across the binder
     * interface will cause the ownership of the channel to be transferred to the recipient.
     * The InputChannel object will be invalidated after calling this method.
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }

        nativeWriteToParcel(out, mPtr);

        if ((flags & PARCELABLE_WRITE_RETURN_VALUE) != 0) {
            dispose();
        }
    }

    @Override
    public String toString() {
        return getName();
    }

    public IBinder getToken() {
        checkValid();
        return nativeGetToken(mPtr);
    }
}
