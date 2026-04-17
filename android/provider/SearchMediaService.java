/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.provider;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

/**
 * <p> Base class for a service which can be implemented by privileged APKs.
 * This service gets request from {@link com.android.providers.media.MediaProvider} or privileged
 * apps to get media search results based on search text. </p>
 *
 * <p>
 * <h3>Manifest entry</h3>
 * <p>SearchMediaService must require the permission
 * "com.android.providers.media.permission.BIND_SEARCH_MEDIA_SERVICE". Service will be ignored for
 * binding if permission is missing. </p>
 *
 * <p>Note that the calling app would still require relevant read or write permission to access the
 * files. The "com.android.providers.media.permission.BIND_SEARCH_MEDIA_SERVICE" permission only
 * allows apps to get search results in form of {@link SearchMediaResultPage}</p>
 *
 * <pre class="prettyprint">
 * {@literal
 * <service
 *     android:name=".MySearchMediaService"
 *     android:exported="true"
 *     android:permission="com.android.providers.media.permission.BIND_SEARCH_MEDIA_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.provider.SearchMediaService" />
 *         <category android:name="android.intent.category.DEFAULT"/>
 *     </intent-filter>
 * </service>}
 * </pre>
 * </p>
 *
 * Only one instance of SearchMediaService will be in respected at a time.
 * OEMs can specify the default behavior through runtime resource overlay,
 * by setting value of the resource {@code config_default_media_search_media_service_package}.
 * The overlayable subset which has this resource is {@code MediaProviderConfig}
 *
 * <p> Usage of this class is only supported on devices running Android T (API 33) or higher.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_SEARCH)
public abstract class SearchMediaService extends Service {

    /**
     * @hide
     */
    private static final String TAG = SearchMediaService.class.getSimpleName();

    /**
     * Permission required to protect {@link SearchMediaService} instances. Implementation should
     * require this in the {@code permission} attribute in their {@code <service>} tag.
     */
    public static final String BIND_SEARCH_MEDIA_SERVICE_PERMISSION =
            "com.android.providers.media.permission.BIND_SEARCH_MEDIA_SERVICE";

    /**
     * Specifies an array of media types to search for. Should be included in search param.
     */
    public static final String EXTRA_MEDIA_TYPE_FILTER = "android.provider.extra.MEDIA_TYPE_FILTER";

    /**
     * Specifies a page size for search results. Should be included in search param.
     * The default value for search results is 100 and maximum possible size is 500.
     */
    public static final String EXTRA_SEARCH_RESULTS_PAGE_SIZE =
            "android.provider.extra.SEARCH_RESULTS_PAGE_SIZE";

    /**
     * Specifies sort order of search results. Should be included in search param.
     * The sort order can be {@link SearchMediaService#EXTRA_SORT_BY_RELEVANCE} or
     * {@link SearchMediaService#EXTRA_SORT_BY_TIME}. The default order is
     * {@link SearchMediaService#EXTRA_SORT_BY_RELEVANCE}.
     */
    public static final String EXTRA_SEARCH_RESULTS_SORT_ORDER =
            "android.provider.extra.SEARCH_RESULTS_SORT_ORDER";

    /**
     * Each search result would have a relevance score associated with it. The search results
     * would be sorted in descending order by relevance score. This relevance score will be part
     * of search results returned by the {@code callback}.
     * <p>
     * To be passed as value alongside {@link SearchMediaService#EXTRA_SEARCH_RESULTS_SORT_ORDER}
     * </p>
     */
    public static final String EXTRA_SORT_BY_RELEVANCE = "android.provider.extra.SORT_BY_RELEVANCE";

    /**
     * Each search result would have a date taken associated with it. If sorted this way,
     * the search results would be sorted in descending order by date taken.
     * <p>
     * To be passed as value alongside {@link SearchMediaService#EXTRA_SEARCH_RESULTS_SORT_ORDER}
     * </p>
     * <p>
     * Note that the search results will be the ones having maximum relevance score same as the
     * {@link SearchMediaService#EXTRA_SORT_BY_RELEVANCE}. Only the ordering of search results
     * will be based on date taken.
     * </p>
     */
    public static final String EXTRA_SORT_BY_TIME = "android.provider.extra.SORT_BY_TIME";

    /**
     * A {@code String} that needs to be passed with {@code searchParams} while querying for next
     * page. Note that caller should receive this bundle from the previous {@code searchMedia} call
     * and should pass the same bundle for fetching next page.
     */
    public static final String EXTRA_NEXT_PAGE_TOKEN = "android.provider.extra.NEXT_PAGE_TOKEN";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link SearchMediaService#BIND_SEARCH_MEDIA_SERVICE_PERMISSION}.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.provider.SearchMediaService";

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        if (intent == null) {
            Log.w(TAG, "Null intent received");
            return null;
        }

        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.w(TAG, "Unexpected action:" + intent.getAction());
            return null;
        }

        return mInterface.asBinder();
    }

    /**
     * Called when a media search is requested based on the given text.
     *
     * <p>
     * This method may be invoked on any thread and the results will be received on the
     * calling app's thread which requested for search results.
     * </p>
     *
     * <p>
     * Search results or error message are returned asynchronously via the provided
     * {@code callback}. This method may be invoked by framework on any thread.
     * </p>
     *
     * <p>
     * The callback would be invoked exactly once per query to deliver search results or error.
     * If {@link SearchMediaService#onCancelSearch(String)} is called before search results or error
     * is sent by callback, then the search will be considered cancelled and callback would not be
     * invoked in this case.
     * </p>
     *
     * <p>
     * The {@code searchId} must be unique for every call to correctly identify the response.
     * </p>
     *
     * <p>
     * <b>Expected keys for the {@code searchParams} Bundle:</b>
     * <ul>
     * <li><b>{@code EXTRA_MEDIA_TYPE_FILTER}</b> ({@code String[]}): Specifies a set of
     * media types to search for.</li>
     * <li><b>{@code EXTRA_SEARCH_RESULTS_PAGE_SIZE}</b> ({@code long}): Specifies the size
     * of a single page of search results.</li>
     * <li><b>{@code EXTRA_SEARCH_RESULTS_SORT_ORDER}</b> ({@code String}, Optional):
     * Specifies the sort order of results. Possible values are
     * {@code EXTRA_SORT_BY_RELEVANCE} (default) and {@code EXTRA_SORT_BY_TIME}.</li>
     * <li><b>{@code EXTRA_NEXT_PAGE_TOKEN}</b> ({@code String}, Optional): The token
     * required to query the next page, received from a previous search callback. This
     * should not be set when querying the first page.</li>
     * </ul>
     * </p>
     *
     * @param searchText         the text string to search for within media metadata
     * @param searchId           a unique ID to identify the search request
     * @param searchParams       a {@code Bundle} containing additional search parameters
     * @param outcomeReceiver    the {@code OutcomeReceiver} to send search results or errors
     */
    @AnyThread
    public abstract void onSearchMedia(@NonNull String searchText, @NonNull String searchId,
            @NonNull Bundle searchParams, @NonNull OutcomeReceiver<SearchMediaResultPage,
                    SearchMediaException> outcomeReceiver);

    /**
     * Called to check if semantic search is supported by this service.
     *
     * <p>Implementors should return {@code true} if their service is capable of performing
     * searches based on the meaning and context of the provided search textusing AI/ML models,
     * rather than just simple keyword matching.</p>
     *
     * <p>This method is useful for calling app as they may decide whether or not to fetch search
     * results using {@link SearchMediaService#onSearchMedia(String, String, Bundle,
     * OutcomeReceiver)} depending upon whether semantic search is supported or not.<p/>
     *
     * @return {@code true} if semantic search is supported, {@code false} otherwise.
     */
    public abstract boolean onCheckSemanticSearchSupport();


    /**
     * Called when a request is made to cancel an ongoing search.
     *
     * <p>
     * The {@code searchId} must be unique for every call to properly identify the response.
     * </p>
     *
     * @param searchId an ID to uniquely identify the search request.
     */
    @AnyThread
    public abstract void onCancelSearch(@NonNull String searchId);


    private final ISearchMediaService mInterface = new ISearchMediaService.Stub() {
        @Override
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        public void searchMedia(String searchText, String searchId, Bundle searchParams,
                ISearchMediaCallback callback) {
            OutcomeReceiver<SearchMediaResultPage, SearchMediaException> receiver = new
                    OutcomeReceiver<SearchMediaResultPage, SearchMediaException>() {
                @Override
                public void onResult(@NonNull SearchMediaResultPage result) {
                    try {
                        callback.onSearchResultsSuccess(result);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to send back search results for searchId "
                                + searchId, ex);
                    } finally {
                        Trace.endAsyncSection("SearchMediaService.onSearchMedia",
                                searchId.hashCode());
                    }
                }

                @Override
                public void onError(@NonNull SearchMediaException searchMediaException) {
                    try {
                        callback.onSearchResultsFailure(searchMediaException);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to send back search error for searchId "
                                + searchId, ex);
                    } finally {
                        Trace.endAsyncSection("SearchMediaService.onSearchMedia",
                                searchId.hashCode());
                    }
                }
            };

            try {
                Trace.beginAsyncSection("SearchMediaService.onSearchMedia", searchId.hashCode());
                onSearchMedia(searchText, searchId, searchParams, receiver);
            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                receiver.onError(new SearchMediaException(searchId, errorMessage,
                        SearchMediaException.ERROR_UNKNOWN, /* isRetryable */ false));
            }
        }

        @Override
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        public void cancelSearch(String searchId) {
            onCancelSearch(searchId);
        }

        @Override
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        public boolean isSemanticSearchSupported() {
            try {
                Trace.beginSection("SearchMediaService.isSemanticSearchSupported");
                return onCheckSemanticSearchSupport();
            } finally {
                Trace.endSection();
            }
        }
    };

}
