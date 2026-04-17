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
package libcore.util;

/**
 * Reflection wrapper around android.util.StatsEvent.
 * @hide
 */
public class ReflexiveStatsEvent {

    private final Object statsEvent;

    private ReflexiveStatsEvent(Object statsEvent) {
        this.statsEvent = statsEvent;
    }

    public Object getStatsEvent() {
        return statsEvent;
    }

    public static ReflexiveStatsEvent.Builder newBuilder() {
        return new ReflexiveStatsEvent.Builder();
    }

    public static final class Builder {
        private static final OptionalMethod newBuilder;
        private static final Class<?> c_statsEvent;
        private static final Class<?> c_statsEvent_Builder;
        private static final OptionalMethod setAtomId;
        private static final OptionalMethod writeBoolean;
        private static final OptionalMethod writeInt;
        private static final OptionalMethod build;
        private static final OptionalMethod usePooledBuffer;
        private static final OptionalMethod writeString;

        static {
            c_statsEvent = initStatsEventClass();
            newBuilder = new OptionalMethod(c_statsEvent, "newBuilder");
            c_statsEvent_Builder = initStatsEventBuilderClass();
            setAtomId = new OptionalMethod(c_statsEvent_Builder, "setAtomId", int.class);
            writeBoolean = new OptionalMethod(c_statsEvent_Builder, "writeBoolean", boolean.class);
            writeInt = new OptionalMethod(c_statsEvent_Builder, "writeInt", int.class);
            build = new OptionalMethod(c_statsEvent_Builder, "build");
            usePooledBuffer = new OptionalMethod(c_statsEvent_Builder, "usePooledBuffer");
            writeString = new OptionalMethod(c_statsEvent_Builder, "writeString", String.class);
        }

        private static Class<?> initStatsEventClass() {
            try {
                return Class.forName("android.util.StatsEvent");
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }

        private static Class<?> initStatsEventBuilderClass() {
            try {
                return Class.forName("android.util.StatsEvent$Builder");
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }

        private final Object builder;

        private Builder() {
            this.builder = newBuilder.invokeStatic();
        }

        public Builder setAtomId(final int atomId) {
            setAtomId.invoke(this.builder, atomId);
            return this;
        }

        public Builder writeBoolean(final boolean value) {
            writeBoolean.invoke(this.builder, value);
            return this;
        }

        public Builder writeInt(final int value) {
            writeInt.invoke(this.builder, value);
            return this;
        }

        public Builder writeString(final String value) {
            writeString.invoke(this.builder, value);
            return this;
        }

        public Builder usePooledBuffer() {
            usePooledBuffer.invoke(this.builder);
            return this;
        }

        public ReflexiveStatsEvent build() {
            Object statsEvent = build.invoke(this.builder);
            return new ReflexiveStatsEvent(statsEvent);
        }
    }
}
