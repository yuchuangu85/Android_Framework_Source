/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.credentials.metrics;

import static com.android.server.credentials.MetricUtilities.DELTA_RESPONSES_CUT;
import static com.android.server.credentials.MetricUtilities.generateMetricKey;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.CredentialEntry;
import android.util.Slog;

import com.android.server.credentials.MetricUtilities;
import com.android.server.credentials.metrics.shared.ResponseCollective;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides contextual metric collection for objects generated from
 * {@link com.android.server.credentials.ProviderSession} flows to isolate metric
 * collection from the core codebase. For any future additions to the ProviderSession subclass
 * list, metric collection should be added to this file.
 */
public class ProviderSessionMetric {

    private static final String TAG = "ProviderSessionMetric";

    // Specific candidate provider metric for the provider this session handles
    @NonNull
    protected final CandidatePhaseMetric mCandidatePhasePerProviderMetric;

    // IFF there was an authentication entry clicked, this stores all required information for
    // that event. This is for the 'get' flow. Notice these flows may be repetitive.
    // Thus each provider stores a list of authentication metrics. The time between emits
    // of these metrics should exceed 10 ms (given human reaction time is ~ 100's of ms), so emits
    // will never collide. However, for aggregation, this will store information accordingly.
    @NonNull
    protected final List<BrowsedAuthenticationMetric> mBrowsedAuthenticationMetric =
            new ArrayList<>();

    public ProviderSessionMetric(int sessionIdTrackTwo) {
        mCandidatePhasePerProviderMetric = new CandidatePhaseMetric(sessionIdTrackTwo);
        mBrowsedAuthenticationMetric.add(new BrowsedAuthenticationMetric(sessionIdTrackTwo));
    }

    /**
     * Retrieve the candidate provider phase metric and the data it contains.
     */
    public CandidatePhaseMetric getCandidatePhasePerProviderMetric() {
        return mCandidatePhasePerProviderMetric;
    }

    /**
     * Retrieves the authentication clicked metric information.
     */
    public List<BrowsedAuthenticationMetric> getBrowsedAuthenticationMetric() {
        return mBrowsedAuthenticationMetric;
    }

    /**
     * This collects for ProviderSessions, with respect to the candidate providers, whether
     * an exception occurred in the candidate call.
     *
     * @param hasException indicates if the candidate provider associated with an exception
     */
    public void collectCandidateExceptionStatus(boolean hasException) {
        try {
            mCandidatePhasePerProviderMetric.setHasException(hasException);
        } catch (Exception e) {
            Slog.i(TAG, "Error while setting candidate metric exception " + e);
        }
    }

    /**
     * This collects for ProviderSessions, with respect to the authentication entry provider,
     * if an exception occurred in the authentication entry click. It's expected that these
     * collections always occur after at least 1 authentication metric has been collected
     * for the provider associated with this metric encapsulation.
     *
     * @param hasException indicates if the candidate provider from an authentication entry
     *                     associated with an exception
     */
    public void collectAuthenticationExceptionStatus(boolean hasException) {
        try {
            BrowsedAuthenticationMetric mostRecentAuthenticationMetric =
                    getUsedAuthenticationMetric();
            mostRecentAuthenticationMetric.setHasException(hasException);
        } catch (Exception e) {
            Slog.i(TAG, "Error while setting authentication metric exception " + e);
        }
    }

    /**
     * Collects the framework only exception encountered in a candidate flow.
     * @param exceptionType the string, cut to desired length, of the exception type
     */
    public void collectCandidateFrameworkException(String exceptionType) {
        try {
            mCandidatePhasePerProviderMetric.setFrameworkException(exceptionType);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during candidate exception metric logging: " + e);
        }
    }

    private void collectAuthEntryUpdate(boolean isFailureStatus,
            boolean isCompletionStatus, int providerSessionUid) {
        BrowsedAuthenticationMetric mostRecentAuthenticationMetric =
                getUsedAuthenticationMetric();
        mostRecentAuthenticationMetric.setProviderUid(providerSessionUid);
        if (isFailureStatus) {
            mostRecentAuthenticationMetric.setAuthReturned(false);
            mostRecentAuthenticationMetric.setProviderStatus(
                    ProviderStatusForMetrics.QUERY_FAILURE
                            .getMetricCode());
        } else if (isCompletionStatus) {
            mostRecentAuthenticationMetric.setAuthReturned(true);
            mostRecentAuthenticationMetric.setProviderStatus(
                    ProviderStatusForMetrics.QUERY_SUCCESS
                            .getMetricCode());
        }
    }

    private BrowsedAuthenticationMetric getUsedAuthenticationMetric() {
        return mBrowsedAuthenticationMetric
                .get(mBrowsedAuthenticationMetric.size() - 1);
    }

    /**
     * Used to collect metrics at the update stage when a candidate provider gives back an update.
     *
     * @param isFailureStatus indicates the candidate provider sent back a terminated response
     * @param isCompletionStatus indicates the candidate provider sent back a completion response
     * @param providerSessionUid the uid of the provider
     * @param isPrimary indicates if this candidate provider was the primary provider
     */
    public void collectCandidateMetricUpdate(boolean isFailureStatus,
            boolean isCompletionStatus, int providerSessionUid, boolean isAuthEntry,
            boolean isPrimary) {
        try {
            if (isAuthEntry) {
                collectAuthEntryUpdate(isFailureStatus, isCompletionStatus, providerSessionUid);
                return;
            }
            mCandidatePhasePerProviderMetric.setPrimary(isPrimary);
            mCandidatePhasePerProviderMetric.setCandidateUid(providerSessionUid);
            mCandidatePhasePerProviderMetric
                    .setQueryFinishTimeNanoseconds(System.nanoTime());
            if (isFailureStatus) {
                mCandidatePhasePerProviderMetric.setQueryReturned(false);
                mCandidatePhasePerProviderMetric.setProviderQueryStatus(
                        ProviderStatusForMetrics.QUERY_FAILURE
                                .getMetricCode());
            } else if (isCompletionStatus) {
                mCandidatePhasePerProviderMetric.setQueryReturned(true);
                mCandidatePhasePerProviderMetric.setProviderQueryStatus(
                        ProviderStatusForMetrics.QUERY_SUCCESS
                                .getMetricCode());
            }
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during candidate update metric logging: " + e);
        }
    }

    /**
     * Starts the collection of a single provider metric in the candidate phase of the API flow.
     * It's expected that this should be called at the start of the query phase so that session id
     * and timestamps can be shared. They can be accessed granular-ly through the underlying
     * objects, but for {@link com.android.server.credentials.ProviderSession} context metrics,
     * it's recommended to use these context-specified methods.
     *
     * @param initMetric the pre candidate phase metric collection object of type
     * {@link InitialPhaseMetric} used to transfer initial information
     */
    public void collectCandidateMetricSetupViaInitialMetric(InitialPhaseMetric initMetric) {
        try {
            mCandidatePhasePerProviderMetric.setServiceBeganTimeNanoseconds(
                    initMetric.getCredentialServiceStartedTimeNanoseconds());
            mCandidatePhasePerProviderMetric.setStartQueryTimeNanoseconds(System.nanoTime());
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during candidate setup metric logging: " + e);
        }
    }

    /**
     * Once candidate providers give back entries, this helps collect their info for metric
     * purposes.
     *
     * @param response contains entries and data from the candidate provider responses
     * @param isAuthEntry indicates if this is an auth entry collection or not
     * @param initialPhaseMetric for create flows, this helps identify the response type, which
     *                           will identify the *type* of create flow, especially important in
     *                           track 2. This is expected to be null in get flows.
     * @param <R> the response type associated with the API flow in progress
     */
    public <R> void collectCandidateEntryMetrics(R response, boolean isAuthEntry,
            @Nullable InitialPhaseMetric initialPhaseMetric) {
        try {
            if (response instanceof BeginGetCredentialResponse) {
                beginGetCredentialResponseCollectionCandidateEntryMetrics(
                        (BeginGetCredentialResponse) response, isAuthEntry);
            } else if (response instanceof BeginCreateCredentialResponse) {
                beginCreateCredentialResponseCollectionCandidateEntryMetrics(
                        (BeginCreateCredentialResponse) response, initialPhaseMetric);
            } else {
                Slog.i(TAG, "Your response type is unsupported for candidate metric logging");
            }
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during candidate entry metric logging: " + e);
        }
    }

    /**
     * Once entries are received from the registry, this helps collect their info for metric
     * purposes.
     *
     * @param entries contains matching entries from the Credential Registry.
     */
    public void collectCandidateEntryMetrics(List<CredentialEntry> entries) {
        int numCredEntries = entries.size();
        int numRemoteEntry = MetricUtilities.ZERO;
        int numActionEntries = MetricUtilities.ZERO;
        int numAuthEntries = MetricUtilities.ZERO;
        Map<EntryEnum, Integer> entryCounts = new LinkedHashMap<>();
        Map<String, Integer> responseCounts = new LinkedHashMap<>();
        entryCounts.put(EntryEnum.REMOTE_ENTRY, numRemoteEntry);
        entryCounts.put(EntryEnum.CREDENTIAL_ENTRY, numCredEntries);
        entryCounts.put(EntryEnum.ACTION_ENTRY, numActionEntries);
        entryCounts.put(EntryEnum.AUTHENTICATION_ENTRY, numAuthEntries);

        entries.forEach(entry -> {
            String entryKey = generateMetricKey(entry.getType(), DELTA_RESPONSES_CUT);
            responseCounts.put(entryKey, responseCounts.getOrDefault(entryKey, 0) + 1);
        });
        ResponseCollective responseCollective = new ResponseCollective(responseCounts, entryCounts);
        mCandidatePhasePerProviderMetric.setResponseCollective(responseCollective);
    }

    /**
     * This sets up an authentication metric collector to the flow. This must be called before
     * any logical edits are done in a new authentication entry metric collection.
     */
    public void createAuthenticationBrowsingMetric() {
        BrowsedAuthenticationMetric browsedAuthenticationMetric =
                new BrowsedAuthenticationMetric(mCandidatePhasePerProviderMetric
                        .getSessionIdProvider());
        mBrowsedAuthenticationMetric.add(browsedAuthenticationMetric);
    }

    private void beginCreateCredentialResponseCollectionCandidateEntryMetrics(
            BeginCreateCredentialResponse response, InitialPhaseMetric initialPhaseMetric) {
        Map<EntryEnum, Integer> entryCounts = new LinkedHashMap<>();
        var createEntries = response.getCreateEntries();
        int numRemoteEntry = response.getRemoteCreateEntry() == null ? MetricUtilities.ZERO :
                MetricUtilities.UNIT;
        int numCreateEntries = createEntries.size();
        entryCounts.put(EntryEnum.REMOTE_ENTRY, numRemoteEntry);
        entryCounts.put(EntryEnum.CREDENTIAL_ENTRY, numCreateEntries);

        Map<String, Integer> responseCounts = new LinkedHashMap<>();
        String[] requestStrings = initialPhaseMetric == null ? new String[0] :
                initialPhaseMetric.getUniqueRequestStrings();
        if (requestStrings.length > 0) {
            responseCounts.put(requestStrings[0], initialPhaseMetric.getUniqueRequestCounts()[0]);
        }

        ResponseCollective responseCollective = new ResponseCollective(responseCounts, entryCounts);
        mCandidatePhasePerProviderMetric.setResponseCollective(responseCollective);
    }

    private void beginGetCredentialResponseCollectionCandidateEntryMetrics(
            BeginGetCredentialResponse response, boolean isAuthEntry) {
        Map<EntryEnum, Integer> entryCounts = new LinkedHashMap<>();
        Map<String, Integer> responseCounts = new LinkedHashMap<>();
        int numCredEntries = response.getCredentialEntries().size();
        int numActionEntries = response.getActions().size();
        int numAuthEntries = response.getAuthenticationActions().size();
        int numRemoteEntry = response.getRemoteCredentialEntry() != null ? MetricUtilities.ZERO :
                MetricUtilities.UNIT;
        entryCounts.put(EntryEnum.REMOTE_ENTRY, numRemoteEntry);
        entryCounts.put(EntryEnum.CREDENTIAL_ENTRY, numCredEntries);
        entryCounts.put(EntryEnum.ACTION_ENTRY, numActionEntries);
        entryCounts.put(EntryEnum.AUTHENTICATION_ENTRY, numAuthEntries);

        response.getCredentialEntries().forEach(entry -> {
            String entryKey = generateMetricKey(entry.getType(), DELTA_RESPONSES_CUT);
            responseCounts.put(entryKey, responseCounts.getOrDefault(entryKey, 0) + 1);
        });

        ResponseCollective responseCollective = new ResponseCollective(responseCounts, entryCounts);

        if (!isAuthEntry) {
            mCandidatePhasePerProviderMetric.setResponseCollective(responseCollective);
        } else {
            // The most recent auth entry must be created already
            var browsedAuthenticationMetric =
                    mBrowsedAuthenticationMetric.get(mBrowsedAuthenticationMetric.size() - 1);
            browsedAuthenticationMetric.setAuthEntryCollective(responseCollective);
        }
    }
}
