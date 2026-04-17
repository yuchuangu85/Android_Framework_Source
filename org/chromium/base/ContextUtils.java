package org.chromium.base;

import android.content.Context;

/**
 * A shim that delegates method calls to the static org.chromium.base.ContextUtils class. Use this
 * to wrap the static utility for dependency injection or testing purposes.
 */
public final class ContextUtils {
    /**
     * Delegates to
     * android.net.http.internal.org.chromium.base.ContextUtils.getApplicationContext().
     */
    public static Context getApplicationContext() {
        return android.net.http.internal.org.chromium.base.ContextUtils.getApplicationContext();
    }
}
