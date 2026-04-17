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

package com.android.internal.protolog;

import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceInstance;
import android.tracing.perfetto.StartCallbackArguments;
import android.util.proto.ProtoInputStream;

import androidx.annotation.NonNull;

/**
 * A Perfetto {@link DataSource} for providing the ProtoLog viewer configuration.
 * <p>
 * This separate data source is used to dump the viewer configuration, extracted ProtoLog message
 * to id mappings, into a Perfetto trace.
 */
public class ProtoLogViewerConfigDataSource extends DataSource<DataSourceInstance, Void, Void> {
    private static final String DATASOURCE_NAME = "android.protolog.viewer";
    private byte[] mViewerConfig;

    public ProtoLogViewerConfigDataSource() {
        super(DATASOURCE_NAME);
    }

    @Override
    @NonNull
    public DataSourceInstance createInstance(@NonNull ProtoInputStream configStream,
            int instanceIndex) {
        return new DataSourceInstance(this, instanceIndex) {
            @Override
            public void onStart(StartCallbackArguments args) {
                if (mViewerConfig != null) {
                    Utils.dumpViewerConfig(getDataSource(),
                            () -> new AutoClosableProtoInputStream(mViewerConfig));
                }
            }
        };
    }

    public void setViewerConfig(@NonNull byte[] config) {
        this.mViewerConfig = config;
    }
}
