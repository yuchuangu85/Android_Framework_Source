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

package android.telephony;

import static android.service.messaging.AlternativeMessageTransportService.UPGRADE_STATUS_REJECTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.service.messaging.AlternativeMessageTransportService;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class exposes the message upgrade capabilities to the platform code i.e.
 * Checks if message upgrade is supported and enables android system to bind with the
 * available {@link android.service.messaging.AlternativeMessageTransportService} in the default
 * SMS app to upgrade SMS/MMS messages to richer protocols like RCS etc.
 *
 * @hide
 */
public final class MessageUpgradeController {
    private static final String TAG = MessageUpgradeController.class.getSimpleName();
    private static final SparseArray<MessageUpgradeWorker> sUpgradeWorkers = new SparseArray<>();
    private static volatile MessageUpgradeController sInstance;

    private static MessageUpgradeController getInstance(Context context) {
        if (sInstance == null) {
            synchronized (MessageUpgradeController.class) {
                if (sInstance == null) {
                    sInstance = new MessageUpgradeController(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * Checks if the calling package is not the default messaging app (DMA) and has a valid
     * {@link AlternativeMessageTransportService} to upgrade messages.
     *
     * @param context The calling app's context.
     * @param callingUser The calling app's user id.
     * @param callingPkg The calling app's package.
     * @return {@code true} if the calling package is not the default messaging app (DMA) and if the
     *         DMA has a valid {@link AlternativeMessageTransportService}, returns false otherise.
     */
    public static boolean isMessageUpgradeSupportedForPackage(
            @NonNull Context context,
            int callingUser,
            @NonNull String callingPkg) {
        Objects.requireNonNull(context, "context cannot be null");
        if (TextUtils.isEmpty(callingPkg)) {
            throw new IllegalArgumentException("callingPkg cannot be null or empty");
        }

        MessageUpgradeWorker upgradeWorker = getInstance(context).getUpgradeWorkerForUser(
                context, callingUser);
        if (upgradeWorker == null) {
            Log.e(TAG, "Could not get upgrade worker for user " + callingUser);
            return false;
        }

        return upgradeWorker.isMessageUpgradeSupportedForPackage(callingPkg);
    }

    /**
     * Enables sending SMS/MMS message by the default messaging app using richer protocols like RCS.
     *
     * @param context The calling app's context.
     * @param callingUser The calling app's user id.
     * @param messageUri The uri of the message in the telephony db.
     * @param sentIntents A list of {@link PendingIntent}s to broadcast when the message is
     * successfully sent or failed. For multipart messages, these intents
     * are all broadcast at once after the last part is sent or failed.
     * @param deliveryIntents A list of {@link PendingIntent}s to broadcast when the message is
     * delivered to the recipient. For multipart messages, these intents
     * are all broadcast at once after the last part is delivered or failed.
     * @param clientCallbackExecutor The executor to run the callback on.
     * @param clientCallback The callback to report the upgrade status.
     */
    public static void upgradeMessage(
            @NonNull Context context,
            int callingUser,
            @NonNull Uri messageUri,
            @Nullable List<PendingIntent> sentIntents,
            @Nullable List<PendingIntent> deliveryIntents,
            @NonNull Executor clientCallbackExecutor,
            @NonNull Consumer<Integer> clientCallback) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(messageUri, "messageUri cannot be null");
        Objects.requireNonNull(clientCallbackExecutor, "clientCallbackExecutor cannot be null");
        Objects.requireNonNull(clientCallback, "clientCallback cannot be null");

        MessageUpgradeWorker upgradeWorker = getInstance(context).getUpgradeWorkerForUser(
                context, callingUser);
        if (upgradeWorker != null) {
            upgradeWorker.upgradeMessage(messageUri,
                    sentIntents,
                    deliveryIntents,
                    clientCallbackExecutor,
                    clientCallback);
        } else {
            Log.e(TAG, "Upgrade message failed, no upgrade worker for user " + callingUser);
            clientCallbackExecutor.execute(() -> clientCallback.accept(UPGRADE_STATUS_REJECTED));
        }
    }

    /**
     * Called when an SMS message is updated in the Telephony provider.
     *
     * <p>Checks if the updated SMS corresponds to an upgraded message and dispatches the associated
     * PendingIntents (sent/delivery) if the status or type has changed.
     *
     * @param context The calling app's context.
     * @param callingUser The calling app's user id.
     * @param messageUri The URI of the updated SMS message.
     * @param values     The updated values of the SMS message.
     */
    public static void dispatchSmsPendingIntentsIfUpgraded(@NonNull Context context,
            int callingUser, Uri messageUri, @NonNull ContentValues values) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(messageUri, "messageUri cannot be null");
        Objects.requireNonNull(values, "values cannot be null");

        MessageUpgradeWorker upgradeWorker = getInstance(context).getUpgradeWorkerForUser(
                context, callingUser);
        if (upgradeWorker != null) {
            upgradeWorker.dispatchSmsPendingIntentsIfUpgraded(messageUri, values);
        } else {
            Log.e(TAG, "dispatchSmsPendingIntentsIfUpgraded, no upgrade worker for user "
                    + callingUser);
        }
    }

    /**
     * Called when an MMS message is updated in the Telephony provider.
     *
     * <p>Checks if the updated MMS corresponds to an upgraded message and dispatches the associated
     * PendingIntents (sent) if the message box has changed.
     *
     * @param context The calling app's context.
     * @param callingUser The calling app's user id.
     * @param messageUri The URI of the updated MMS message.
     * @param values     The updated values of the MMS message.
     */
    public static void dispatchMmsPendingIntentsIfUpgraded(@NonNull Context context,
            int callingUser, Uri messageUri, @NonNull ContentValues values) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(messageUri, "messageUri cannot be null");
        Objects.requireNonNull(values, "values cannot be null");

        MessageUpgradeWorker upgradeWorker = getInstance(context).getUpgradeWorkerForUser(
                context, callingUser);
        if (upgradeWorker != null) {
            upgradeWorker.dispatchMmsPendingIntentsIfUpgraded(messageUri, values);
        } else {
            Log.e(TAG, "dispatchSmsPendingIntentsIfUpgraded, no upgrade worker for user "
                    + callingUser);
        }
    }

    private MessageUpgradeController(Context context) {
        registerUserRemovedReceiver(context);
    }

    private void registerUserRemovedReceiver(Context context) {
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        BroadcastReceiver userRemovedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                    if (userId != UserHandle.USER_NULL) {
                        Log.i(TAG, "User " + userId + " removed, cleaning up resources.");
                        onUserRemoved(UserHandle.of(userId));
                    }
                }
            }
        };
        context.registerReceiverAsUser(userRemovedReceiver, UserHandle.ALL, intentFilter,
                null, null);
    }

    private synchronized void onUserRemoved(@NonNull UserHandle user) {
        MessageUpgradeWorker upgradeWorker = sUpgradeWorkers.get(user.getIdentifier());
        if (upgradeWorker != null) {
            sUpgradeWorkers.remove(user.getIdentifier());
            upgradeWorker.close();
            Log.i(TAG, "Cleaned up MessageUpgradeWorker for removed user "
                    + user.getIdentifier());
        }
    }

    @Nullable
    private synchronized MessageUpgradeWorker getUpgradeWorkerForUser(
            @NonNull Context context, int userId) {
        MessageUpgradeWorker upgradeWorker = sUpgradeWorkers.get(userId);
        if (upgradeWorker == null) {
            try {
                // Create a context for the specific user.
                Context userContext = context.createPackageContextAsUser(
                        context.getPackageName(), 0, UserHandle.of(userId));
                upgradeWorker = new MessageUpgradeWorker(userContext);
                sUpgradeWorkers.put(userId, upgradeWorker);
                Log.i(TAG, "Created new MessageUpgradeWorker for user "
                        + userId);
            } catch (PackageManager.NameNotFoundException e) {
                // This should not happen as we are using our own package.
                Log.e(TAG, "Could not create context for user " + userId, e);
                return null;
            }
        }
        return upgradeWorker;
    }
}
