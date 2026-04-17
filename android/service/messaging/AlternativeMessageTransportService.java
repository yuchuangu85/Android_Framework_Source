/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.service.messaging;

import static com.android.internal.telephony.flags.Flags.FLAG_MESSAGE_PROMOTION;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Consumer;

/**
 * AlternativeMessageTransportService (AMTS) allows the default SMS app to handle the sending of a
 * message over a different transport (e.g. RCS etc.) other than the SMS or MMS. When an SMS or MMS
 * is being sent, the Telephony framework checks if the default SMS app has implemented this
 * service. If so, it binds to the service and requests the SMS app to send it over it's preferred
 * transport.
 *
 * <p>If the service accepts the upgrade request, the default SMS app is solely responsible for
 * sending the message to the recipient and moving it from the OUTBOX to SENT or FAILED folder.</p>
 *
 * <p>To extend this class, you must declare the service in your manifest file to require the
 * {@link android.Manifest.permission#BIND_ALTERNATIVE_MESSAGE_TRANSPORT_SERVICE} permission and
 * include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".MyAlternativeMessageTransportService"
 *          android:permission="android.permission.BIND_ALTERNATIVE_MESSAGE_TRANSPORT_SERVICE"
 *          android:exported="true"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.service.messaging.AlternativeMessageTransportService"
 *         /&gt;
 *     &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 */
@FlaggedApi(FLAG_MESSAGE_PROMOTION)
public abstract class AlternativeMessageTransportService extends Service {
    private static final String TAG =
            AlternativeMessageTransportService.class.getSimpleName();

    /**
     * The {@link android.content.Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.messaging.AlternativeMessageTransportService";

    /**
     * Status code when the default SMS app has accepted the message upgrade request and will now
     * attempt to send it as per it's supported transport.
     */
    public static final int UPGRADE_STATUS_ACCEPTED = 1;

    /**
     * Status code when the default SMS app could not accept the message upgrade request.
     */
    public static final int UPGRADE_STATUS_REJECTED = 2;

    /** @hide */
    @IntDef(prefix = { "UPGRADE_STATUS_" }, value = {
            UPGRADE_STATUS_ACCEPTED,
            UPGRADE_STATUS_REJECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE})
    public @interface UpgradeStatus {}

    private final IAlternativeMessageTransportServiceImpl mImpl =
            new IAlternativeMessageTransportServiceImpl();

    /**
     * Called by the service upon receiving a new message upgrade request. SMS app will have
     * 10 seconds to respond to the upgrade request with either
     * {@link #UPGRADE_STATUS_ACCEPTED} or {@link #UPGRADE_STATUS_REJECTED}. It's not expected
     * to keep running while the message is being sent.
     *
     * @param contentUri the content URI of the MMS message.
     * @param upgradeStatus callback to notify about the upgrade status.
     */
    public abstract void onMessageUpgradeRequested(
            @NonNull Uri contentUri,
            @NonNull Consumer<@UpgradeStatus Integer> upgradeStatus);

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        if (intent == null || !SERVICE_INTERFACE.equals(intent.getAction())) {
            return null;
        }
        return mImpl;
    }

    /**
     * A wrapper around IMessageUpgradeService to enable the default messaging app to implement
     * methods it cares about in the {@link IAlternativeMessageTransportService} interface.
     */
    private final class IAlternativeMessageTransportServiceImpl
            extends IAlternativeMessageTransportService.Stub {
        @Override
        public void upgradeMessage(
                @NonNull Uri contentUri,
                @NonNull final IMessageUpgradeCallback callback) {
            AlternativeMessageTransportService.this.onMessageUpgradeRequested(
                    contentUri,
                    status -> {
                        try {
                            callback.onUpgradeStatusAvailable(status);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error sending result back to the system.", e);
                        }
                    });
        }
    }
}
