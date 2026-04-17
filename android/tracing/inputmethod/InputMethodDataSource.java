/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.tracing.inputmethod;

import android.annotation.NonNull;
import android.internal.perfetto.protos.DataSourceConfigOuterClass;
import android.internal.perfetto.protos.InputmethodConfig.InputMethodConfig;
import android.tracing.perfetto.CreateTlsStateArgs;
import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceInstance;
import android.util.Log;
import android.util.proto.ProtoInputStream;

import java.io.IOException;

/**
 * @hide
 */
public final class InputMethodDataSource
        extends DataSource<InputMethodDataSource.Instance, InputMethodDataSource.TlsState, Void> {

    public static class Config {
        /** Enable tracing for Input Method clients. */
        public final boolean mIsClientEnabled;
        /** Enable tracing for Input Method Service. */
        public final boolean mIsServiceEnabled;
        /** Enable tracing for Input Method Manager Service. */
        public final boolean mIsManagerServiceEnabled;

        private Config(boolean isClientEnabled, boolean isServiceEnabled,
                boolean isManagerServiceEnabled) {
            mIsClientEnabled = isClientEnabled;
            mIsServiceEnabled = isServiceEnabled;
            mIsManagerServiceEnabled = isManagerServiceEnabled;
        }
    }

    public class Instance extends DataSourceInstance {
        @NonNull
        public final Config mConfig;

        public Instance(@NonNull DataSource dataSource, int instanceIndex,
                @NonNull Config config) {
            super(dataSource, instanceIndex);
            mConfig = config;
        }
    }

    public static class TlsState {
        @NonNull
        public final Config mConfig;

        private TlsState(@NonNull Config config) {
            mConfig = config;
        }
    }

    public static final String DATA_SOURCE_NAME = "android.inputmethod";
    private static final String TAG = "InputMethodDataSource";

    public InputMethodDataSource(@NonNull Runnable onStart, @NonNull Runnable onStop) {
        super(DATA_SOURCE_NAME);

        this.registerOnStartCallback((idx) -> {
            Log.i(TAG, "Starting IME tracing");
            onStart.run();
        });

        this.registerOnStopCallback((idx) -> {
            Log.i(TAG, "Stopping IME tracing");
            onStop.run();
        });
    }

    @Override
    @NonNull
    public Instance createInstance(@NonNull ProtoInputStream configStream, int instanceIndex) {
        if (!android.tracing.Flags.perfettoImeFineGrainedConfig()) {
            final Config config = new Config(true /* isClientEnabled */,
                    true /* isServiceEnabled */, true /* isManagerServiceEnabled */);
            return new Instance(this, instanceIndex, config);
        }

        final Config config = parseDataSourceConfig(configStream);
        return new Instance(this, instanceIndex, config);
    }

    @Override
    @NonNull
    public TlsState createTlsState(
            @NonNull CreateTlsStateArgs<Instance> args) {
        try (Instance dsInstance = args.getDataSourceInstanceLocked()) {
            if (dsInstance == null) {
                // Datasource instance has been removed
                return new TlsState(new Config(false /* isClientEnabled */,
                        false /* isServiceEnabled */, false /* isManagerServiceEnabled */));
            }
            return new TlsState(dsInstance.mConfig);
        }
    }

    @NonNull
    private Config parseDataSourceConfig(@NonNull ProtoInputStream stream) {
        try {
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (stream.getFieldNumber()
                        != (int) DataSourceConfigOuterClass.DataSourceConfig.INPUTMETHOD_CONFIG) {
                    continue;
                }
                return parseInputMethodConfig(stream);
            }
            Log.w(TAG, "Received start request without config parameters. Will use defaults.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse DataSourceConfig", e);
        }
        // Default to "all enabled" if no config is provided
        return new Config(true, true, true);
    }

    @NonNull
    private Config parseInputMethodConfig(@NonNull ProtoInputStream stream) {
        boolean isClientEnabled = false;
        boolean isServiceEnabled = false;
        boolean isManagerServiceEnabled = false;

        try {
            final long token =
                    stream.start(DataSourceConfigOuterClass.DataSourceConfig.INPUTMETHOD_CONFIG);
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (stream.getFieldNumber()) {
                    case (int) InputMethodConfig.CLIENT:
                        isClientEnabled = stream.readBoolean(InputMethodConfig.CLIENT);
                        break;
                    case (int) InputMethodConfig.SERVICE:
                        isServiceEnabled = stream.readBoolean(InputMethodConfig.SERVICE);
                        break;
                    case (int) InputMethodConfig.MANAGER_SERVICE:
                        isManagerServiceEnabled =
                                stream.readBoolean(InputMethodConfig.MANAGER_SERVICE);
                        break;
                    default:
                        Log.w(TAG, "Unrecognized InputMethodConfig field number: "
                                + stream.getFieldNumber());
                }
            }
            stream.end(token);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse InputMethodConfig", e);
        }
        return new Config(isClientEnabled, isServiceEnabled, isManagerServiceEnabled);
    }
}
