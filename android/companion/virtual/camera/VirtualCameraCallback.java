/*
 * Copyright 2023 The Android Open Source Project
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

package android.companion.virtual.camera;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.SessionConfiguration;
import android.view.Surface;

import java.util.concurrent.Executor;
import java.util.function.ObjLongConsumer;

/**
 * Interface to be provided when creating a new {@link VirtualCamera} in order to receive callbacks
 * from the framework and the camera system.
 *
 * @see VirtualCameraConfig.Builder#setVirtualCameraCallback(Executor, VirtualCameraCallback)
 * @hide
 */
@SystemApi
public interface VirtualCameraCallback {

    /**
     * Called when the client application calls
     * {@link android.hardware.camera2.CameraManager#openCamera}. This is the earliest signal that
     * this camera will be used. At this point, no stream is opened yet, nor any configuration took
     * place. The owner of the virtual camera can use this as signal to prepare the camera and
     * reduce latency for when
     * {@link android.hardware.camera2.CameraDevice#createCaptureSession(SessionConfiguration)} is
     * called and before
     * {@link CameraCaptureSession.StateCallback#onConfigured(CameraCaptureSession)}
     * is called.
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA_ON_OPEN)
    default void onOpenCamera() {}

    /**
     * Called when the app using the {@link VirtualCamera} creates a new
     * {@link CameraCaptureSession}. This callback is sent when clients open and configure the
     * capture session for the virtual camera.
     *
     * @param virtualCameraSessionConfig The virtual camera session configuration with the session
     *      parameters provided by the app in association with the requested capture session.
     * @param captureResultConsumer The consumer interface through which the virtual camera
     *      owner can pass {@link android.hardware.camera2.CaptureResult}s for each timestamp of
     *      the streams associated with this session. The timestamp must match the timestamp of the
     *      buffer posted on the corresponding {@link Surface}. This is {@code null} if the per
     *      frame metadata is not enabled in the {@link VirtualCameraConfig} of the virtual camera.
     *
     * @see VirtualCameraConfig.Builder#setPerFrameCameraMetadataEnabled(boolean)
     * @see CameraCaptureSession
     */
    default void onConfigureSession(
            @NonNull VirtualCameraSessionConfig virtualCameraSessionConfig,
            @Nullable ObjLongConsumer<CaptureResult> captureResultConsumer) {}

    /**
     * Called when one of the requested stream has been configured by the virtual camera service and
     * is ready to receive data onto its {@link Surface}
     * <p>
     * This corresponds to the client calling
     * {@link android.hardware.camera2.CameraDevice#createCaptureSession(SessionConfiguration)}
     * <p>
     * The {@code streamId} corresponds to the index at which the stream was added when calling
     * {@link VirtualCameraConfig.Builder#addStreamConfig(int, int, int, int)} (e.g. first added
     * stream has id 0, second added stream has id 1, etc.).
     *
     * @param streamId The id of the configured stream
     * @param surface The surface to write data into for this stream
     * @param width The width of the surface
     * @param height The height of the surface
     * @param format The {@link ImageFormat} of the surface
     */
    void onStreamConfigured(int streamId, @NonNull Surface surface,
            @IntRange(from = 1) int width, @IntRange(from = 1) int height,
            @ImageFormat.Format int format);

    /**
     * The client application is requesting a camera frame for the given streamId and frameId.
     *
     * <p>The virtual camera needs to write the frame data in the {@link Surface} corresponding to
     * this stream that was provided during the
     * {@link #onStreamConfigured(int, Surface, int, int, int)} call.
     *
     * <p>This callback is called <b>only</b> when the support for per frame camera metadata is
     * <b>disabled</b> (default value).
     *
     * @param streamId The streamId for which the frame is requested. This corresponds to the
     *     streamId that was given in {@link #onStreamConfigured(int, Surface, int, int, int)}
     * @param frameId The frameId that is being requested. Each request will have a different
     *     frameId, that will be increasing for each call with a particular streamId.
     *
     * @see VirtualCameraConfig.Builder#setPerFrameCameraMetadataEnabled(boolean)
     * @see #onProcessCaptureRequest(int, long, CaptureRequest)
     */
    default void onProcessCaptureRequest(int streamId, long frameId) {}

    /**
     * The client application is requesting a camera frame for the given streamId and frameId with
     * the provided {@link CaptureRequest} metadata.
     *
     * <p>The virtual camera needs to write the frame data in the {@link Surface} corresponding to
     * this stream that was provided during the
     * {@link #onStreamConfigured(int, Surface, int, int, int)} call.
     *
     * <p>This callback is called <b>instead</b> of the {@link #onProcessCaptureRequest(int, long)}
     * when support for per frame camera metadata is <b>enabled</b> with
     * {@link VirtualCameraConfig.Builder#setPerFrameCameraMetadataEnabled(boolean)}.
     *
     * @param streamId The streamId for which the frame is requested. This corresponds to the
     *      streamId that was given in {@link #onStreamConfigured(int, Surface, int, int, int)}.
     * @param frameId The frameId that is being requested. Each request will have a different
     *      frameId, that will be increasing for each call with a particular streamId.
     * @param captureRequest The {@link CaptureRequest} metadata provided by the app in association
     *      with the requested frameId. It is {@code null} if there is no change from the previous
     *      {@link CaptureRequest}.
     *
     * @see VirtualCameraConfig.Builder#setPerFrameCameraMetadataEnabled(boolean)
     * @see #onProcessCaptureRequest(int, long)
     */
    default void onProcessCaptureRequest(int streamId, long frameId,
            @Nullable CaptureRequest captureRequest) {}

    /**
     * The stream previously configured when
     * {@link #onStreamConfigured(int, Surface, int, int, int)} was called is now being closed and
     * associated resources can be freed. The Surface corresponding to that streamId was disposed on
     * the client side and should not be used anymore by the virtual camera owner.
     *
     * @param streamId The id of the stream that was closed.
     */
    void onStreamClosed(int streamId);
}
