package com.android.internal.telephony;

import android.content.Context;
import android.os.Bundle;
import android.provider.BlockedNumberContract;
import android.telephony.Rlog;

/**
 * {@hide} Checks for blocked phone numbers against {@link BlockedNumberContract}
 */
public class BlockChecker {
    private static final String TAG = "BlockChecker";
    private static final boolean VDBG = false; // STOPSHIP if true.

    /**
     * Returns {@code true} if {@code phoneNumber} is blocked according to {@code extras}.
     * <p>
     * This method catches all underlying exceptions to ensure that this method never throws any
     * exception.
     * <p>
     * @deprecated use {@link #isBlocked(Context, String, Bundle)} instead.
     *
     * @param context the context of the caller.
     * @param phoneNumber the number to check.
     * @return {@code true} if the number is blocked. {@code false} otherwise.
     */
    @Deprecated
    public static boolean isBlocked(Context context, String phoneNumber) {
        return isBlocked(context, phoneNumber, null /* extras */);
    }

    /**
     * Returns {@code true} if {@code phoneNumber} is blocked according to {@code extras}.
     * <p>
     * This method catches all underlying exceptions to ensure that this method never throws any
     * exception.
     *
     * @param context the context of the caller.
     * @param phoneNumber the number to check.
     * @param extras the extra attribute of the number.
     * @return {@code true} if the number is blocked. {@code false} otherwise.
     */
    public static boolean isBlocked(Context context, String phoneNumber, Bundle extras) {
        boolean isBlocked = false;
        long startTimeNano = System.nanoTime();

        try {
            if (BlockedNumberContract.SystemContract.shouldSystemBlockNumber(
                    context, phoneNumber, extras)) {
                Rlog.d(TAG, phoneNumber + " is blocked.");
                isBlocked = true;
            }
        } catch (Exception e) {
            Rlog.e(TAG, "Exception checking for blocked number: " + e);
        }

        int durationMillis = (int) ((System.nanoTime() - startTimeNano) / 1000000);
        if (durationMillis > 500 || VDBG) {
            Rlog.d(TAG, "Blocked number lookup took: " + durationMillis + " ms.");
        }
        return isBlocked;
    }
}
