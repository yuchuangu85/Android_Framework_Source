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
 * limitations under the License
 */

package com.android.ims.internal;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.CameraCapabilities;
import android.telecom.Connection;
import android.telecom.VideoProfile;
import android.view.Surface;

import com.android.internal.os.SomeArgs;

/**
 * Subclass implementation of {@link Connection.VideoProvider}. This intermediates and
 * communicates with the actual implementation of the video call provider in the IMS service; it is
 * in essence, a wrapper around the IMS's video call provider implementation.
 *
 * This class maintains a binder by which the ImsVideoCallProvider's implementation can communicate
 * its intent to invoke callbacks. In this class, the message across this binder is handled, and
 * the superclass's methods are used to execute the callbacks.
 *
 * @hide
 */
public class ImsVideoCallProviderWrapper extends Connection.VideoProvider {
    private static final int MSG_RECEIVE_SESSION_MODIFY_REQUEST = 1;
    private static final int MSG_RECEIVE_SESSION_MODIFY_RESPONSE = 2;
    private static final int MSG_HANDLE_CALL_SESSION_EVENT = 3;
    private static final int MSG_CHANGE_PEER_DIMENSIONS = 4;
    private static final int MSG_CHANGE_CALL_DATA_USAGE = 5;
    private static final int MSG_CHANGE_CAMERA_CAPABILITIES = 6;

    private final IImsVideoCallProvider mVideoCallProvider;
    private final ImsVideoCallCallback mBinder;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mVideoCallProvider.asBinder().unlinkToDeath(this, 0);
        }
    };

    /**
     * IImsVideoCallCallback stub implementation.
     */
    private final class ImsVideoCallCallback extends IImsVideoCallCallback.Stub {
        @Override
        public void receiveSessionModifyRequest(VideoProfile VideoProfile) {
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_REQUEST,
                    VideoProfile).sendToTarget();
        }

        @Override
        public void receiveSessionModifyResponse(
                int status, VideoProfile requestProfile, VideoProfile responseProfile) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = status;
            args.arg2 = requestProfile;
            args.arg3 = responseProfile;
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_RESPONSE, args).sendToTarget();
        }

        @Override
        public void handleCallSessionEvent(int event) {
            mHandler.obtainMessage(MSG_HANDLE_CALL_SESSION_EVENT, event).sendToTarget();
        }

        @Override
        public void changePeerDimensions(int width, int height) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = width;
            args.arg2 = height;
            mHandler.obtainMessage(MSG_CHANGE_PEER_DIMENSIONS, args).sendToTarget();
        }

        @Override
        public void changeCallDataUsage(int dataUsage) {
            mHandler.obtainMessage(MSG_CHANGE_CALL_DATA_USAGE, dataUsage).sendToTarget();
        }

        @Override
        public void changeCameraCapabilities(CameraCapabilities cameraCapabilities) {
            mHandler.obtainMessage(MSG_CHANGE_CAMERA_CAPABILITIES,
                    cameraCapabilities).sendToTarget();
        }
    }

    /** Default handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            SomeArgs args;
            switch (msg.what) {
                case MSG_RECEIVE_SESSION_MODIFY_REQUEST:
                    receiveSessionModifyRequest((VideoProfile) msg.obj);
                    break;
                case MSG_RECEIVE_SESSION_MODIFY_RESPONSE:
                    args = (SomeArgs) msg.obj;
                    try {
                        int status = (int) args.arg1;
                        VideoProfile requestProfile = (VideoProfile) args.arg2;
                        VideoProfile responseProfile = (VideoProfile) args.arg3;

                        receiveSessionModifyResponse(status, requestProfile, responseProfile);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_HANDLE_CALL_SESSION_EVENT:
                    handleCallSessionEvent((int) msg.obj);
                    break;
                case MSG_CHANGE_PEER_DIMENSIONS:
                    args = (SomeArgs) msg.obj;
                    try {
                        int width = (int) args.arg1;
                        int height = (int) args.arg2;
                        changePeerDimensions(width, height);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_CHANGE_CALL_DATA_USAGE:
                    changeCallDataUsage(msg.arg1);
                    break;
                case MSG_CHANGE_CAMERA_CAPABILITIES:
                    changeCameraCapabilities((CameraCapabilities) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Instantiates an instance of the ImsVideoCallProvider, taking in the binder for IMS's video
     * call provider implementation.
     *
     * @param VideoProvider
     */
    public ImsVideoCallProviderWrapper(IImsVideoCallProvider VideoProvider)
            throws RemoteException {
        mVideoCallProvider = VideoProvider;
        mVideoCallProvider.asBinder().linkToDeath(mDeathRecipient, 0);

        mBinder = new ImsVideoCallCallback();
        mVideoCallProvider.setCallback(mBinder);
    }

    /** @inheritDoc */
    public void onSetCamera(String cameraId) {
        try {
            mVideoCallProvider.setCamera(cameraId);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSetPreviewSurface(Surface surface) {
        try {
            mVideoCallProvider.setPreviewSurface(surface);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSetDisplaySurface(Surface surface) {
        try {
            mVideoCallProvider.setDisplaySurface(surface);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSetDeviceOrientation(int rotation) {
        try {
            mVideoCallProvider.setDeviceOrientation(rotation);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSetZoom(float value) {
        try {
            mVideoCallProvider.setZoom(value);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSendSessionModifyRequest(VideoProfile requestProfile) {
        try {
            mVideoCallProvider.sendSessionModifyRequest(requestProfile);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSendSessionModifyResponse(VideoProfile responseProfile) {
        try {
            mVideoCallProvider.sendSessionModifyResponse(responseProfile);
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onRequestCameraCapabilities() {
        try {
            mVideoCallProvider.requestCameraCapabilities();
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onRequestCallDataUsage() {
        try {
            mVideoCallProvider.requestCallDataUsage();
        } catch (RemoteException e) {
        }
    }

    /** @inheritDoc */
    public void onSetPauseImage(String uri) {
        try {
            mVideoCallProvider.setPauseImage(uri);
        } catch (RemoteException e) {
        }
    }
}