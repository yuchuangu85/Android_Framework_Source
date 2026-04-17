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
 * A wrapper around StatsLog for Libcore.
 *
 * @hide
 */
public final class LibcoreStatsLog {
    // The atom ID comes from frameworks/proto_logging/stats/atoms.proto
    public static final int RUNTIME_UNSAFE_DCL_REPORTED = 1179;

    public static void writeRuntimeUnsafeDclReported(
            int callingUid, String className, String fileName) {
        ReflexiveStatsLog.write(ReflexiveStatsEvent.newBuilder()
                .setAtomId(RUNTIME_UNSAFE_DCL_REPORTED)
                .writeInt(callingUid)
                .writeString(className)
                .writeString(fileName)
                .usePooledBuffer()
                .build());
    }
}
