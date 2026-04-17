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

package android.health.connect.ratelimiter;

import android.annotation.IntDef;
import android.health.connect.HealthConnectException;
import android.os.SystemClock;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Basic rate limiter that assigns a fixed request rate quota. If no quota has previously been noted
 * (e.g. first request scenario), the full quota for each window will be immediately granted.
 *
 * @hide
 */
public final class RateLimiter {
    // The maximum number of bytes a client can insert in one go.
    public static final String CHUNK_SIZE_LIMIT_IN_BYTES = "chunk_size_limit_in_bytes";
    // The maximum size in bytes of a single record a client can insert in one go.
    public static final String RECORD_SIZE_LIMIT_IN_BYTES = "record_size_limit_in_bytes";
    private static final int DEFAULT_API_CALL_COST = 1;

    public static final int QUOTA_BUCKET_READS_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE = 2000;
    public static final int QUOTA_BUCKET_READS_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE = 16000;
    public static final int QUOTA_BUCKET_READS_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE = 1000;
    public static final int QUOTA_BUCKET_READS_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE = 8000;
    public static final int QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE = 1000;
    public static final int QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE = 8000;
    public static final int QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE = 1000;
    public static final int QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE = 8000;
    public static final int CHUNK_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE = 5000000;
    public static final int RECORD_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE = 1000000;
    public static final int DATA_PUSH_LIMIT_PER_APP_15M_DEFAULT_FLAG_VALUE = 35000000;
    public static final int DATA_PUSH_LIMIT_ACROSS_APPS_15M_DEFAULT_FLAG_VALUE = 100000000;

    private final Map<Integer, Integer> mQuotaBucketToMaxRollingQuota = new HashMap<>();
    private final Map<String, Integer> mQuotaBucketToMaxMemoryQuota = new HashMap<>();

    private final ReentrantReadWriteLock mLockAcrossAppQuota = new ReentrantReadWriteLock();
    private final Map<Integer, Quota> mQuotaBucketToAcrossAppsRemainingMemoryQuota =
            new HashMap<>();

    private final Map<Integer, Map<Integer, Quota>> mUserIdToQuotasMap = new HashMap<>();

    private final ConcurrentMap<Integer, Integer> mLocks = new ConcurrentHashMap<>();

    private void initQuotaBuckets() {
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_24H_FOREGROUND,
                QUOTA_BUCKET_READS_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_24H_BACKGROUND,
                QUOTA_BUCKET_READS_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_15M_FOREGROUND,
                QUOTA_BUCKET_READS_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_15M_BACKGROUND,
                QUOTA_BUCKET_READS_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND,
                QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND,
                QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND,
                QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND,
                QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_PER_APP_15M,
                DATA_PUSH_LIMIT_PER_APP_15M_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxRollingQuota.put(
                QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M,
                DATA_PUSH_LIMIT_ACROSS_APPS_15M_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxMemoryQuota.put(
                RateLimiter.CHUNK_SIZE_LIMIT_IN_BYTES,
                CHUNK_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE);
        mQuotaBucketToMaxMemoryQuota.put(
                RateLimiter.RECORD_SIZE_LIMIT_IN_BYTES,
                RECORD_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE);
    }

    public RateLimiter() {
        initQuotaBuckets();
    }

    /** Allows setting lower rate limits in tests. */
    public void setLowerRateLimitsForTesting(boolean enabled) {
        initQuotaBuckets();

        if (enabled) {
            mQuotaBucketToMaxRollingQuota.replaceAll((k, v) -> v / 10);
            mQuotaBucketToMaxMemoryQuota.replaceAll((k, v) -> v / 10);
        }
    }

    /**
     * Acquire api call quota for the given category.
     *
     * @param uid the uid for the calling app
     * @param quotaCategory the category for which to request quota
     * @param isInForeground true if the app is in the foreground, false otherwise
     * @throws RateLimiterException if insufficient quota is available
     * @throws IllegalArgumentException if quota category is invalid
     */
    public void tryAcquireApiCallQuota(
            int uid, @QuotaCategory.Type int quotaCategory, boolean isInForeground) {
        if (quotaCategory == QuotaCategory.QUOTA_CATEGORY_UNDEFINED) {
            throw new IllegalArgumentException("Quota category not defined.");
        }

        // Rate limiting not applicable.
        if (quotaCategory == QuotaCategory.QUOTA_CATEGORY_UNMETERED) {
            return;
        }

        synchronized (getLockObject(uid)) {
            spendApiCallResourcesIfAvailable(
                    uid,
                    getAffectedAPIQuotaBuckets(quotaCategory, isInForeground),
                    DEFAULT_API_CALL_COST);
        }
    }

    /**
     * Acquire api call quota for the given category.
     *
     * @param uid the uid for the calling app
     * @param quotaCategory the category for which to request quota
     * @param isInForeground true if the app is in the foreground, false otherwise
     * @param memoryCost how much memory cost is required for this call
     * @throws RateLimiterException if insufficient quota is available
     * @throws IllegalArgumentException if quota category is invalid
     */
    public void tryAcquireApiCallQuota(
            int uid,
            @QuotaCategory.Type int quotaCategory,
            boolean isInForeground,
            long memoryCost) {
        if (quotaCategory == QuotaCategory.QUOTA_CATEGORY_UNDEFINED) {
            throw new IllegalArgumentException("Quota category not defined.");
        }

        // Rate limiting not applicable.
        if (quotaCategory == QuotaCategory.QUOTA_CATEGORY_UNMETERED) {
            return;
        }
        if (quotaCategory != QuotaCategory.QUOTA_CATEGORY_WRITE) {
            throw new IllegalArgumentException("Quota category must be QUOTA_CATEGORY_WRITE.");
        }
        mLockAcrossAppQuota.writeLock().lock();
        try {
            synchronized (getLockObject(uid)) {
                spendApiAndMemoryResourcesIfAvailable(
                        uid,
                        getAffectedAPIQuotaBuckets(quotaCategory, isInForeground),
                        getAffectedMemoryQuotaBuckets(quotaCategory, isInForeground),
                        DEFAULT_API_CALL_COST,
                        memoryCost,
                        isInForeground);
            }
        } finally {
            mLockAcrossAppQuota.writeLock().unlock();
        }
    }

    /**
     * Checks if the given cost is higher than the memory limit per chunk.
     *
     * @param memoryCost the cost to check
     * @throws HealthConnectException if the cost is higher than the memory limit per chunk
     */
    public void checkMaxChunkMemoryUsage(long memoryCost) {
        long memoryLimit = getConfiguredMaxApiMemoryQuota(CHUNK_SIZE_LIMIT_IN_BYTES);
        if (memoryCost > memoryLimit) {
            throw new HealthConnectException(
                    HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED,
                    "Records chunk size exceeded the max chunk limit: "
                            + memoryLimit
                            + ", was: "
                            + memoryCost);
        }
    }

    /**
     * Checks if the given cost is higher than the memory limit byte size per record.
     *
     * @param memoryCost the cost to check
     * @throws HealthConnectException if the cost is higher than the memory limit per record
     */
    public void checkMaxRecordMemoryUsage(long memoryCost) {
        long memoryLimit = getConfiguredMaxApiMemoryQuota(RECORD_SIZE_LIMIT_IN_BYTES);
        if (memoryCost > memoryLimit) {
            throw new HealthConnectException(
                    HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED,
                    "Record size exceeded the single record size limit: "
                            + memoryLimit
                            + ", was: "
                            + memoryCost);
        }
    }

    /** Clears the cached quota usage for all apps. */
    public void clearCache() {
        mUserIdToQuotasMap.clear();
        mQuotaBucketToAcrossAppsRemainingMemoryQuota.clear();
    }

    private Object getLockObject(int uid) {
        Integer previous = mLocks.putIfAbsent(uid, uid);
        return previous == null ? uid : previous;
    }

    private void spendApiCallResourcesIfAvailable(int uid, List<Integer> quotaBuckets, int cost) {
        Map<Integer, Float> quotaBucketToAvailableQuotaMap =
                getQuotaBucketToAvailableQuotaMap(uid, quotaBuckets);
        checkIfResourcesAreAvailable(quotaBucketToAvailableQuotaMap, cost);
        spendAvailableResources(uid, quotaBucketToAvailableQuotaMap, cost);
    }

    private void spendApiAndMemoryResourcesIfAvailable(
            int uid,
            List<Integer> apiQuotaBuckets,
            List<Integer> memoryQuotaBuckets,
            int cost,
            long memoryCost,
            boolean isInForeground) {
        Map<Integer, Float> apiQuotaBucketToAvailableQuotaMap =
                getQuotaBucketToAvailableQuotaMap(uid, apiQuotaBuckets);
        Map<Integer, Float> memoryQuotaBucketToAvailableQuotaMap =
                getQuotaBucketToAvailableQuotaMap(uid, memoryQuotaBuckets);
        if (!isInForeground) {
            hasSufficientQuota(
                    getAvailableQuota(
                            QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M,
                            getQuota(QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M)),
                    memoryCost,
                    QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M);
        }
        checkIfResourcesAreAvailable(apiQuotaBucketToAvailableQuotaMap, cost);
        checkIfResourcesAreAvailable(memoryQuotaBucketToAvailableQuotaMap, memoryCost);
        if (!isInForeground) {
            spendAvailableResources(
                    getQuota(QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M),
                    QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M,
                    memoryCost);
        }
        spendAvailableResources(uid, apiQuotaBucketToAvailableQuotaMap, cost);
        spendAvailableResources(uid, memoryQuotaBucketToAvailableQuotaMap, memoryCost);
    }

    private void checkIfResourcesAreAvailable(
            Map<Integer, Float> quotaBucketToAvailableQuotaMap, long cost) {
        quotaBucketToAvailableQuotaMap.forEach(
                (Integer quotaBucket, Float available) ->
                        hasSufficientQuota(available, cost, quotaBucket));
    }

    private void spendAvailableResources(Quota quota, Integer quotaBucket, long memoryCost) {
        quota.setRemainingQuota(getAvailableQuota(quotaBucket, quota) - memoryCost);
        quota.setLastUpdatedTimeMillis(SystemClock.elapsedRealtime());
    }

    private void spendAvailableResources(
            int uid, Map<Integer, Float> quotaBucketToAvailableQuotaMap, long cost) {
        quotaBucketToAvailableQuotaMap.forEach(
                (Integer quotaBucket, Float available) ->
                        spendResources(uid, quotaBucket, available, cost));
    }

    private void spendResources(
            int uid, @QuotaBucket.Type int quotaBucket, float availableQuota, long cost) {
        Map<Integer, Quota> bucketToQuotaMap = mUserIdToQuotasMap.get(uid);
        if (bucketToQuotaMap != null) {
            // Null should only happen if the cache is cleared mid update.
            bucketToQuotaMap.put(
                    quotaBucket,
                    new Quota(
                            /* lastUpdatedTimeMillis= */ SystemClock.elapsedRealtime(),
                            availableQuota - cost));
        }
    }

    private Map<Integer, Float> getQuotaBucketToAvailableQuotaMap(
            int uid, List<Integer> quotaBuckets) {
        return quotaBuckets.stream()
                .collect(
                        Collectors.toMap(
                                quotaBucket -> quotaBucket,
                                quotaBucket ->
                                        getAvailableQuota(
                                                quotaBucket, getQuota(uid, quotaBucket))));
    }

    private void hasSufficientQuota(
            float availableQuota, long cost, @QuotaBucket.Type int quotaBucket) {
        if (availableQuota < cost) {
            throw new RateLimiterException(
                    "API call quota exceeded, availableQuota: "
                            + availableQuota
                            + " requested: "
                            + cost,
                    quotaBucket,
                    getConfiguredMaxRollingQuota(quotaBucket));
        }
    }

    private float getAvailableQuota(@QuotaBucket.Type int quotaBucket, Quota quota) {
        long lastUpdatedTimeMillis = quota.getLastUpdatedTimeMillis();
        long currentTimeMillis = SystemClock.elapsedRealtime();
        long timeSinceLastQuotaSpendMillis = currentTimeMillis - lastUpdatedTimeMillis;
        Duration window = getWindowDuration(quotaBucket);
        float accumulated =
                timeSinceLastQuotaSpendMillis
                        * (getConfiguredMaxRollingQuota(quotaBucket) / (float) window.toMillis());
        // Cannot accumulate more than the configured max quota.
        return Math.min(
                quota.getRemainingQuota() + accumulated, getConfiguredMaxRollingQuota(quotaBucket));
    }

    private Quota getQuota(int uid, @QuotaBucket.Type int quotaBucket) {
        // Handles first request scenario.
        if (!mUserIdToQuotasMap.containsKey(uid)) {
            mUserIdToQuotasMap.put(uid, new HashMap<>());
        }
        Map<Integer, Quota> packageQuotas = mUserIdToQuotasMap.get(uid);
        Quota quota = packageQuotas.get(quotaBucket);
        if (quota == null) {
            quota = getInitialQuota(quotaBucket);
        }
        return quota;
    }

    private Quota getQuota(@QuotaBucket.Type int quotaBucket) {
        // Handles first request scenario.
        if (!mQuotaBucketToAcrossAppsRemainingMemoryQuota.containsKey(quotaBucket)) {
            mQuotaBucketToAcrossAppsRemainingMemoryQuota.put(
                    quotaBucket,
                    new Quota(
                            /* lastUpdatedTimeMillis= */ SystemClock.elapsedRealtime(),
                            getConfiguredMaxRollingQuota(quotaBucket)));
        }
        return mQuotaBucketToAcrossAppsRemainingMemoryQuota.get(quotaBucket);
    }

    private Quota getInitialQuota(@QuotaBucket.Type int bucket) {
        return new Quota(
                /* lastUpdatedTimeMillis= */ SystemClock.elapsedRealtime(),
                getConfiguredMaxRollingQuota(bucket));
    }

    private static Duration getWindowDuration(@QuotaBucket.Type int quotaBucket) {
        switch (quotaBucket) {
            case QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND:
            case QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND:
            case QuotaBucket.QUOTA_BUCKET_READS_PER_24H_BACKGROUND:
            case QuotaBucket.QUOTA_BUCKET_READS_PER_24H_FOREGROUND:
                return Duration.ofHours(24);
            case QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND:
            case QuotaBucket.QUOTA_BUCKET_READS_PER_15M_FOREGROUND:
            case QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND:
            case QuotaBucket.QUOTA_BUCKET_READS_PER_15M_BACKGROUND:
            case QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M:
            case QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_PER_APP_15M:
                return Duration.ofMinutes(15);
            case QuotaBucket.QUOTA_BUCKET_UNDEFINED:
                throw new IllegalArgumentException("Invalid quota bucket.");
        }
        throw new IllegalArgumentException("Invalid quota bucket.");
    }

    private float getConfiguredMaxRollingQuota(@QuotaBucket.Type int quotaBucket) {
        if (!mQuotaBucketToMaxRollingQuota.containsKey(quotaBucket)) {
            throw new IllegalArgumentException(
                    "Max quota not found for quotaBucket: " + quotaBucket);
        }
        return mQuotaBucketToMaxRollingQuota.get(quotaBucket);
    }

    private int getConfiguredMaxApiMemoryQuota(String quotaBucket) {
        if (!mQuotaBucketToMaxMemoryQuota.containsKey(quotaBucket)) {
            throw new IllegalArgumentException(
                    "Max quota not found for quotaBucket: " + quotaBucket);
        }
        return mQuotaBucketToMaxMemoryQuota.get(quotaBucket);
    }

    private static List<Integer> getAffectedAPIQuotaBuckets(
            @QuotaCategory.Type int quotaCategory, boolean isInForeground) {
        switch (quotaCategory) {
            case QuotaCategory.QUOTA_CATEGORY_READ:
                if (isInForeground) {
                    return List.of(
                            QuotaBucket.QUOTA_BUCKET_READS_PER_15M_FOREGROUND,
                            QuotaBucket.QUOTA_BUCKET_READS_PER_24H_FOREGROUND);
                } else {
                    return List.of(
                            QuotaBucket.QUOTA_BUCKET_READS_PER_15M_BACKGROUND,
                            QuotaBucket.QUOTA_BUCKET_READS_PER_24H_BACKGROUND);
                }
            case QuotaCategory.QUOTA_CATEGORY_WRITE:
                if (isInForeground) {
                    return List.of(
                            QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND,
                            QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND);
                } else {
                    return List.of(
                            QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND,
                            QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND);
                }
            case QuotaCategory.QUOTA_CATEGORY_UNDEFINED:
            case QuotaCategory.QUOTA_CATEGORY_UNMETERED:
                throw new IllegalArgumentException("Invalid quota category.");
        }
        throw new IllegalArgumentException("Invalid quota category.");
    }

    private static List<Integer> getAffectedMemoryQuotaBuckets(
            @QuotaCategory.Type int quotaCategory, boolean isInForeground) {
        switch (quotaCategory) {
            case QuotaCategory.QUOTA_CATEGORY_WRITE:
                if (isInForeground) {
                    return List.of();
                } else {
                    return List.of(QuotaBucket.QUOTA_BUCKET_DATA_PUSH_LIMIT_PER_APP_15M);
                }
            case QuotaCategory.QUOTA_CATEGORY_READ:
            case QuotaCategory.QUOTA_CATEGORY_UNDEFINED:
            case QuotaCategory.QUOTA_CATEGORY_UNMETERED:
                throw new IllegalArgumentException("Invalid quota category.");
        }
        throw new IllegalArgumentException("Invalid quota category.");
    }

    public static final class QuotaBucket {
        public static final int QUOTA_BUCKET_UNDEFINED = 0;
        public static final int QUOTA_BUCKET_READS_PER_15M_FOREGROUND = 1;
        public static final int QUOTA_BUCKET_READS_PER_24H_FOREGROUND = 2;
        public static final int QUOTA_BUCKET_READS_PER_15M_BACKGROUND = 3;
        public static final int QUOTA_BUCKET_READS_PER_24H_BACKGROUND = 4;
        public static final int QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND = 5;
        public static final int QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND = 6;
        public static final int QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND = 7;
        public static final int QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND = 8;
        public static final int QUOTA_BUCKET_DATA_PUSH_LIMIT_PER_APP_15M = 9;
        public static final int QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M = 10;

        private QuotaBucket() {}

        /** @hide */
        @IntDef({
            QUOTA_BUCKET_UNDEFINED,
            QUOTA_BUCKET_READS_PER_15M_FOREGROUND,
            QUOTA_BUCKET_READS_PER_24H_FOREGROUND,
            QUOTA_BUCKET_READS_PER_15M_BACKGROUND,
            QUOTA_BUCKET_READS_PER_24H_BACKGROUND,
            QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND,
            QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND,
            QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND,
            QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND,
            QUOTA_BUCKET_DATA_PUSH_LIMIT_PER_APP_15M,
            QUOTA_BUCKET_DATA_PUSH_LIMIT_ACROSS_APPS_15M,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Type {}
    }

    public static final class QuotaCategory {
        public static final int QUOTA_CATEGORY_UNDEFINED = 0;
        public static final int QUOTA_CATEGORY_UNMETERED = 1;
        public static final int QUOTA_CATEGORY_READ = 2;
        public static final int QUOTA_CATEGORY_WRITE = 3;

        private QuotaCategory() {}

        /** @hide */
        @IntDef({
            QUOTA_CATEGORY_UNDEFINED,
            QUOTA_CATEGORY_UNMETERED,
            QUOTA_CATEGORY_READ,
            QUOTA_CATEGORY_WRITE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Type {}
    }
}
