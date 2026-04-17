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

import static com.android.media.editing.flags.Flags.FLAG_ADD_MEDIA_METRICS_EDITING;
import static android.media.metrics.Flags.FLAG_ENABLE_EXTENDED_BUNDLE_METRICS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.MediaMetrics;
import android.os.PersistableBundle;

import com.android.internal.util.AnnotationValidations;

import java.util.Objects;

/**
 * Represents a session of media editing, for example, transcoding between formats, transmuxing or
 * applying trimming or audio/video effects to a stream.
 */
public final class EditingSession extends BaseSession {

    /** @hide */
    public EditingSession(@NonNull String id, @NonNull MediaMetricsManager manager) {
        super(id, manager);
    }

    /** Reports that an editing operation ended. */
    @FlaggedApi(FLAG_ADD_MEDIA_METRICS_EDITING)
    public void reportEditingEndedEvent(@NonNull EditingEndedEvent editingEndedEvent) {
        mManager.reportEditingEndedEvent(mId, editingEndedEvent);
    }

    @FlaggedApi(FLAG_ENABLE_EXTENDED_BUNDLE_METRICS)
    @Override
    public void reportBundleMetrics(@NonNull PersistableBundle metrics) {
        super.reportBundleMetrics(metrics);
    }
}
