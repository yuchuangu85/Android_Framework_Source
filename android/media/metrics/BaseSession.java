/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.metrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;

import com.android.internal.util.AnnotationValidations;

import java.util.Objects;

/**
 * Base media metrics session, used to track metrics throughout the life of a media session.
 */
abstract class BaseSession implements AutoCloseable {
    protected final @NonNull String mId;
    protected final @NonNull MediaMetricsManager mManager;
    private final @NonNull LogSessionId mLogSessionId;

    /** @hide */
    BaseSession(@NonNull String id, @NonNull MediaMetricsManager manager) {
        mId = id;
        mManager = manager;
        AnnotationValidations.validate(NonNull.class, null, mId);
        AnnotationValidations.validate(NonNull.class, null, mManager);
        mLogSessionId = new LogSessionId(mId);
    }

    /**
     * Reports metrics via bundle.
     *
     * The provided bundle must contain a key/integer pair for the statsd atom id, specified by
     * {@link BundleSession#KEY_STATSD_ATOM}. Other keys and their types are defined on a
     * per-atom basis.
     */
    public void reportBundleMetrics(@NonNull PersistableBundle metrics) {
        mManager.reportBundleMetrics(mId, metrics);
    }

    public @NonNull LogSessionId getSessionId() {
        return mLogSessionId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseSession that = (BaseSession) o;
        return Objects.equals(mId, that.mId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId);
    }

    @Override
    public void close() {
        mManager.releaseSessionId(mLogSessionId.getStringId());
    }
}
