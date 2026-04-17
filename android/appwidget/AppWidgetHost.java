/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.appwidget;

import static android.appwidget.flags.Flags.FLAG_ENGAGEMENT_METRICS;
import static android.appwidget.flags.Flags.engagementMetrics;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.TransactionTooLargeException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.widget.RemoteViews;
import android.widget.RemoteViews.InteractionHandler;

import com.android.internal.R;
import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * AppWidgetHost provides the interaction with the AppWidget service for apps,
 * like the home screen, that want to embed AppWidgets in their UI.
 */
public class AppWidgetHost {

    private static final String TAG = "AppWidgetHost";

    static final int HANDLE_UPDATE = 1;
    static final int HANDLE_PROVIDER_CHANGED = 2;
    static final int HANDLE_PROVIDERS_CHANGED = 3;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    static final int HANDLE_VIEW_DATA_CHANGED = 4;
    static final int HANDLE_APP_WIDGET_REMOVED = 5;
    static final int HANDLE_VIEW_UPDATE_DEFERRED = 6;

    @UnsupportedAppUsage
    static IAppWidgetService sService;
    private DisplayMetrics mDisplayMetrics;

    private String mContextOpPackageName;
    @UnsupportedAppUsage
    private final Handler mHandler;
    private final int mHostId;
    private final Callbacks mCallbacks;
    private final SparseArray<AppWidgetHostListener> mListeners = new SparseArray<>();
    private InteractionHandler mInteractionHandler;
    private final IAppWidgetService mService;

    /**
     * Default IAppWidgetService factory that does one-time initialization and returns a static
     * reference.
     */
    private static final Function<Context, IAppWidgetService> sServiceFactory = new Function<>() {
        private final Object mServiceLock = new Object();
        private boolean mServiceInitialized = false;

        @Override
        public IAppWidgetService apply(Context context) {
            synchronized (mServiceLock) {
                if (mServiceInitialized) {
                    return sService;
                }
                mServiceInitialized = true;
                PackageManager packageManager = context.getPackageManager();
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)
                        && !context.getResources().getBoolean(
                        R.bool.config_enableAppWidgetService)) {
                    return null;
                }
                IBinder b = ServiceManager.getService(Context.APPWIDGET_SERVICE);
                sService = IAppWidgetService.Stub.asInterface(b);
                return sService;
            }
        }
    };

    static class Callbacks extends IAppWidgetHost.Stub {
        private final WeakReference<Handler> mWeakHandler;

        public Callbacks(Handler handler) {
            mWeakHandler = new WeakReference<>(handler);
        }

        public void updateAppWidget(int appWidgetId, RemoteViews views) {
            if (isLocalBinder() && views != null) {
                views = views.clone();
            }
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_UPDATE, appWidgetId, 0, views);
            msg.sendToTarget();
        }

        public void providerChanged(int appWidgetId, AppWidgetProviderInfo info) {
            if (isLocalBinder() && info != null) {
                info = info.clone();
            }
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_PROVIDER_CHANGED,
                    appWidgetId, 0, info);
            msg.sendToTarget();
        }

        public void appWidgetRemoved(int appWidgetId) {
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            handler.obtainMessage(HANDLE_APP_WIDGET_REMOVED, appWidgetId, 0).sendToTarget();
        }

        public void providersChanged() {
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            handler.obtainMessage(HANDLE_PROVIDERS_CHANGED).sendToTarget();
        }

        public void viewDataChanged(int appWidgetId, int viewId) {
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_VIEW_DATA_CHANGED,
                    appWidgetId, viewId);
            msg.sendToTarget();
        }

        public void updateAppWidgetDeferred(int appWidgetId) {
            Handler handler = mWeakHandler.get();
            if (handler == null) {
                return;
            }
            Message msg = handler.obtainMessage(HANDLE_VIEW_UPDATE_DEFERRED, appWidgetId, 0, null);
            msg.sendToTarget();
        }

        private static boolean isLocalBinder() {
            return Process.myPid() == Binder.getCallingPid();
        }
    }

    class UpdateHandler extends Handler {
        public UpdateHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case HANDLE_UPDATE: {
                    updateAppWidgetView(msg.arg1, (RemoteViews)msg.obj);
                    break;
                }
                case HANDLE_APP_WIDGET_REMOVED: {
                    dispatchOnAppWidgetRemoved(msg.arg1);
                    break;
                }
                case HANDLE_PROVIDER_CHANGED: {
                    onProviderChanged(msg.arg1, (AppWidgetProviderInfo)msg.obj);
                    break;
                }
                case HANDLE_PROVIDERS_CHANGED: {
                    onProvidersChanged();
                    break;
                }
                case HANDLE_VIEW_DATA_CHANGED: {
                    viewDataChanged(msg.arg1, msg.arg2);
                    break;
                }
                case HANDLE_VIEW_UPDATE_DEFERRED: {
                    updateAppWidgetDeferred(msg.arg1);
                    break;
                }
            }
        }
    }

    public AppWidgetHost(Context context, int hostId) {
        this(context, hostId, null, context.getMainLooper());
    }

    @Nullable
    private AppWidgetHostListener getListener(final int appWidgetId) {
        AppWidgetHostListener tempListener = null;
        synchronized (mListeners) {
            tempListener = mListeners.get(appWidgetId);
        }
        return tempListener;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public AppWidgetHost(Context context, int hostId, InteractionHandler handler, Looper looper) {
        this(context, hostId, handler, looper, sServiceFactory);
    }

    /**
     * @hide
     */
    public AppWidgetHost(Context context, int hostId, InteractionHandler handler, Looper looper,
            Function<Context, IAppWidgetService> serviceFactory) {
        mContextOpPackageName = context.getOpPackageName();
        mHostId = hostId;
        mInteractionHandler = handler;
        mHandler = new UpdateHandler(looper);
        mCallbacks = new Callbacks(mHandler);
        mDisplayMetrics = context.getResources().getDisplayMetrics();
        mService = serviceFactory.apply(context);
    }

    /**
     * Start receiving onAppWidgetChanged calls for your AppWidgets.  Call this when your activity
     * becomes visible, i.e. from onStart() in your Activity.
     */
    public void startListening() {
        if (mService == null) {
            return;
        }
        final int[] idsToUpdate;
        synchronized (mListeners) {
            int n = mListeners.size();
            idsToUpdate = new int[n];
            for (int i = 0; i < n; i++) {
                idsToUpdate[i] = mListeners.keyAt(i);
            }
        }
        List<PendingHostUpdate> updates;
        try {
            updates = mService.startListening(
                    mCallbacks, mContextOpPackageName, mHostId, idsToUpdate).getList();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }

        int N = updates.size();
        for (int i = 0; i < N; i++) {
            PendingHostUpdate update = updates.get(i);
            switch (update.type) {
                case PendingHostUpdate.TYPE_VIEWS_UPDATE:
                    updateAppWidgetView(update.appWidgetId, update.views);
                    break;
                case PendingHostUpdate.TYPE_PROVIDER_CHANGED:
                    onProviderChanged(update.appWidgetId, update.widgetInfo);
                    break;
                case PendingHostUpdate.TYPE_VIEW_DATA_CHANGED:
                    viewDataChanged(update.appWidgetId, update.viewId);
                    break;
                case PendingHostUpdate.TYPE_APP_WIDGET_REMOVED:
                    dispatchOnAppWidgetRemoved(update.appWidgetId);
                    break;
            }
        }
    }

    /**
     * Stop receiving onAppWidgetChanged calls for your AppWidgets.  Call this when your activity is
     * no longer visible, i.e. from onStop() in your Activity.
     */
    public void stopListening() {
        if (mService == null) {
            return;
        }
        try {
            mService.stopListening(mContextOpPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
        reportAllWidgetEvents();
    }

    /**
     * Get a appWidgetId for a host in the calling process.
     *
     * @return a appWidgetId
     */
    public int allocateAppWidgetId() {
        if (mService == null) {
            return -1;
        }
        try {
            return mService.allocateAppWidgetId(mContextOpPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Returns an {@link IntentSender} for starting the configuration activity for the widget.
     *
     * @return The {@link IntentSender} or null if service is currently unavailable
     *
     * @throws android.content.ActivityNotFoundException If configuration activity is not found.
     *
     * @see #startAppWidgetConfigureActivityForResult
     *
     * @hide
     */
    @Nullable
    public final IntentSender getIntentSenderForConfigureActivity(int appWidgetId,
            int intentFlags)  {
        if (mService == null) {
            return null;
        }

        IntentSender intentSender;
        try {
            intentSender = mService.createAppWidgetConfigIntentSender(mContextOpPackageName,
                    appWidgetId, intentFlags);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }

        if (intentSender == null) {
            throw new ActivityNotFoundException();
        }
        return intentSender;
    }

    /**
     * Starts an app widget provider configure activity for result on behalf of the caller.
     * Use this method if the provider is in another profile as you are not allowed to start
     * an activity in another profile. You can optionally provide a request code that is
     * returned in {@link Activity#onActivityResult(int, int, android.content.Intent)} and
     * an options bundle to be passed to the started activity.
     * <p>
     * Note that the provided app widget has to be bound for this method to work.
     * </p>
     *
     * @param activity The activity from which to start the configure one.
     * @param appWidgetId The bound app widget whose provider's config activity to start.
     * @param requestCode Optional request code retuned with the result.
     * @param intentFlags Optional intent flags.
     *
     * @throws android.content.ActivityNotFoundException If the activity is not found.
     *
     * @see AppWidgetProviderInfo#getProfile()
     */
    public final void startAppWidgetConfigureActivityForResult(@NonNull Activity activity,
            int appWidgetId, int intentFlags, int requestCode, @Nullable Bundle options) {
        if (mService == null) {
            return;
        }
        try {
            IntentSender intentSender = getIntentSenderForConfigureActivity(appWidgetId,
                    intentFlags);
            activity.startIntentSenderForResult(intentSender, requestCode, null, 0, 0, 0, options);
        } catch (IntentSender.SendIntentException e) {
            throw new ActivityNotFoundException();
        }
    }

    /**
     * Set the visibiity of all widgets associated with this host to hidden
     *
     * @hide
     */
    public void setAppWidgetHidden() {
        if (mService == null) {
            return;
        }
        try {
            mService.setAppWidgetHidden(mContextOpPackageName, mHostId);
        } catch (RemoteException e) {
            throw new RuntimeException("System server dead?", e);
        }
        reportAllWidgetEvents();
    }

    /**
     * Set the host's interaction handler.
     *
     * @hide
     */
    public void setInteractionHandler(InteractionHandler interactionHandler) {
        mInteractionHandler = interactionHandler;
    }

    /**
     * Gets a list of all the appWidgetIds that are bound to the current host
     */
    public int[] getAppWidgetIds() {
        if (mService == null) {
            return new int[0];
        }
        try {
            return mService.getAppWidgetIdsForHost(mContextOpPackageName, mHostId);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Stop listening to changes for this AppWidget.
     */
    public void deleteAppWidgetId(int appWidgetId) {
        if (mService == null) {
            return;
        }
        removeListener(appWidgetId);
        try {
            mService.deleteAppWidgetId(mContextOpPackageName, appWidgetId);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Remove all records about this host from the AppWidget manager.
     * <ul>
     *   <li>Call this when initializing your database, as it might be because of a data wipe.</li>
     *   <li>Call this to have the AppWidget manager release all resources associated with your
     *   host.  Any future calls about this host will cause the records to be re-allocated.</li>
     * </ul>
     */
    public void deleteHost() {
        if (mService == null) {
            return;
        }
        try {
            mService.deleteHost(mContextOpPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Remove all records about all hosts for your package.
     * <ul>
     *   <li>Call this when initializing your database, as it might be because of a data wipe.</li>
     *   <li>Call this to have the AppWidget manager release all resources associated with your
     *   host.  Any future calls about this host will cause the records to be re-allocated.</li>
     * </ul>
     */
    public static void deleteAllHosts() {
        if (sService == null) {
            return;
        }
        try {
            sService.deleteAllHosts();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Create the AppWidgetHostView for the given widget.
     * The AppWidgetHost retains a pointer to the newly-created View.
     */
    public final AppWidgetHostView createView(Context context, int appWidgetId,
            AppWidgetProviderInfo appWidget) {
        if (mService == null) {
            return null;
        }
        AppWidgetHostView view = onCreateView(context, appWidgetId, appWidget);
        view.setInteractionHandler(mInteractionHandler);
        view.setAppWidget(appWidgetId, appWidget);
        setListener(appWidgetId, view);

        return view;
    }

    /**
     * Called to create the AppWidgetHostView.  Override to return a custom subclass if you
     * need it.  {@more}
     */
    protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
            AppWidgetProviderInfo appWidget) {
        return new AppWidgetHostView(context, mInteractionHandler);
    }

    /**
     * Called when the AppWidget provider for a AppWidget has been upgraded to a new apk.
     */
    protected void onProviderChanged(int appWidgetId, AppWidgetProviderInfo appWidget) {
        AppWidgetHostListener v = getListener(appWidgetId);

        // Convert complex to dp -- we are getting the AppWidgetProviderInfo from the
        // AppWidgetService, which doesn't have our context, hence we need to do the
        // conversion here.
        appWidget.updateDimensions(mDisplayMetrics);
        if (v != null) {
            v.onUpdateProviderInfo(appWidget);
        }
    }

    /**
     * This interface specifies the actions to be performed on the app widget based on the calls
     * from the service
     *
     * @hide
     */
    public interface AppWidgetHostListener {

        /**
         * This function is called when the service want to reset the app widget provider info
         * @param appWidget The new app widget provider info
         *
         * @hide
         */
        void onUpdateProviderInfo(@Nullable AppWidgetProviderInfo appWidget);

        /**
         * This function is called when the {@code RemoteViews} of the app widget is updated
         * @param views The new {@code RemoteViews} to be set for the app widget
         *
         * @hide
         */
        void updateAppWidget(@Nullable RemoteViews views);

        /**
         * Called for the listener to handle deferred {@code RemoteViews} updates. Default
         * implementation is to update the widget directly.
         * @param packageName The package name used for uid verification on the service side
         * @param appWidgetId The widget id of the listener
         *
         * @hide
         */
        default void updateAppWidgetDeferred(String packageName, int appWidgetId) {
            RemoteViews latestViews = null;
            try {
                latestViews = sService.getAppWidgetViews(packageName,
                        appWidgetId);
            } catch (Exception e) {
                Log.e(TAG, "updateAppWidgetDeferred: ", e);
            }
            updateAppWidget(latestViews);
        }

        /**
         * This function is called when the view ID is changed for the app widget
         * @param viewId The new view ID to be be set for the widget
         *
         * @hide
         */
        void onViewDataChanged(int viewId);

        /**
         * This function returns the current set of widget event data being tracked by this widget.
         *
         * @hide
         */
        @FlaggedApi(FLAG_ENGAGEMENT_METRICS)
        @Nullable
        default AppWidgetEvent collectWidgetEvent() {
            return null;
        }
    }

    void dispatchOnAppWidgetRemoved(int appWidgetId) {
        removeListener(appWidgetId);
        onAppWidgetRemoved(appWidgetId);
    }

    /**
     * Called when the app widget is removed for appWidgetId
     * @param appWidgetId
     */
    public void onAppWidgetRemoved(int appWidgetId) {
        // Does nothing
    }

    /**
     * Called when the set of available widgets changes (ie. widget containing packages
     * are added, updated or removed, or widget components are enabled or disabled.)
     */
    protected void onProvidersChanged() {
        // Does nothing
    }

    /**
     * Create an AppWidgetHostListener for the given widget.
     * The AppWidgetHost retains a pointer to the newly-created listener.
     * @param appWidgetId The ID of the app widget for which to add the listener
     * @param listener The listener interface that deals with actions towards the widget view
     * @hide
     */
    public void setListener(int appWidgetId, @NonNull AppWidgetHostListener listener) {
        synchronized (mListeners) {
            mListeners.put(appWidgetId, listener);
        }
        RemoteViews views = null;
        try {
            views = mService.getAppWidgetViews(mContextOpPackageName, appWidgetId);
        } catch (TransactionTooLargeException e) {
            Log.e(TAG, "setListener: Transaction too large for appWidgetId=" + appWidgetId, e);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        listener.updateAppWidget(views);
    }

    /**
     * Delete the listener for the given widget
     * @param appWidgetId The ID of the app widget for which the listener is to be deleted

     * @hide
     */
    public void removeListener(int appWidgetId) {
        reportEventForWidget(appWidgetId);
        synchronized (mListeners) {
            mListeners.remove(appWidgetId);
        }
    }

    void updateAppWidgetView(int appWidgetId, RemoteViews views) {
        AppWidgetHostListener v = getListener(appWidgetId);
        if (v != null) {
            v.updateAppWidget(views);
        }
    }

    void viewDataChanged(int appWidgetId, int viewId) {
        AppWidgetHostListener v = getListener(appWidgetId);
        if (v != null) {
            v.onViewDataChanged(viewId);
        }
    }

    private void updateAppWidgetDeferred(int appWidgetId) {
        AppWidgetHostListener v = getListener(appWidgetId);
        if (v == null) {
            Log.e(TAG, "updateAppWidgetDeferred: null listener for id: " + appWidgetId);
            return;
        }
        v.updateAppWidgetDeferred(mContextOpPackageName, appWidgetId);
    }

    /**
     * Clear the list of Views that have been created by this AppWidgetHost.
     */
    protected void clearViews() {
        reportAllWidgetEvents();
        synchronized (mListeners) {
            mListeners.clear();
        }
    }

    /**
     * Report any pending widget event data to the AppWidgetService.
     *
     * @hide
     */
    public void reportAllWidgetEvents() {
        if (mService == null || !engagementMetrics()) {
            return;
        }

        SparseArray<AppWidgetHostListener> listeners;
        synchronized (mListeners) {
            listeners = mListeners.clone();
        }
        List<AppWidgetEvent> eventList = new ArrayList<>();
        for (int i = 0; i < listeners.size(); i++) {
            AppWidgetEvent event = listeners.valueAt(i).collectWidgetEvent();
            if (event != null) {
                eventList.add(event);
            }
        }
        if (eventList.isEmpty()) {
            return;
        }
        AppWidgetEvent[] events = new AppWidgetEvent[eventList.size()];
        for (int i = 0; i < events.length; i++) {
            events[i] = eventList.get(i);
        }

        try {
            mService.reportWidgetEvents(mContextOpPackageName, events);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report a pending widget event to the AppWidgetService.
     *
     * @hide
     */
    public void reportEventForWidget(int appWidgetId) {
        if (mService == null || !engagementMetrics()) {
            return;
        }
        AppWidgetHostListener listener = getListener(appWidgetId);
        if (listener == null) {
            return;
        }
        AppWidgetEvent event = listener.collectWidgetEvent();
        if (event == null) {
            return;
        }
        AppWidgetEvent[] events = {event};

        try {
            mService.reportWidgetEvents(mContextOpPackageName, events);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}


