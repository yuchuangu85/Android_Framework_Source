/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import java.io.IOException;
import java.util.Objects;

/**
 * System private API for passing profiler settings.
 *
 * @hide
 */
public class ProfilerInfo implements Parcelable {
    // Regular profiling which provides different modes of profiling at some performance cost.
    public static final int PROFILE_TYPE_REGULAR = 0;

    // Low overhead profiling that captures a simple sliding window of past events.
    public static final int PROFILE_TYPE_LOW_OVERHEAD = 1;

    // Version of the profiler output
    public static final int OUTPUT_VERSION_DEFAULT = 1;
    // Default for flags if they aren't specified. We just set it to 0 and fallback to defaults used
    // by ART.
    public static final int DEFAULT_FLAGS = 0;
    // CLOCK_TYPE_DEFAULT chooses the default used by ART. ART uses CLOCK_TYPE_DUAL by default (see
    // kDefaultTraceClockSource in art/runtime/runtime_globals.h).
    public static final int CLOCK_TYPE_DEFAULT = 0x000;
    // The values of these constants are chosen such that they correspond to the flags passed to
    // VMDebug.startMethodTracing to choose the corresponding clock type (see
    // core/java/android/app/ActivityThread.java).
    // The flag values are defined in ART (see TraceFlag in art/runtime/trace.h).
    public static final int CLOCK_TYPE_WALL = 0x010;
    public static final int CLOCK_TYPE_THREAD_CPU = 0x100;
    public static final int CLOCK_TYPE_DUAL = 0x110;
    public static final int CLOCK_TYPE_MASK = ~(0x110);
    // The second and third bits of the flags field specify the trace format version. This should
    // match with kTraceFormatVersionShift defined in art/runtime/trace.h.
    public static final int OUTPUT_VERSION_MASK = ~(0b0110);
    public static final int TRACE_FORMAT_VERSION_SHIFT = 1;

    private static final String TAG = "ProfilerInfo";

    /* Name of profile output file. */
    public final String profileFile;

    /* File descriptor for profile output file, can be null. */
    public ParcelFileDescriptor profileFd;

    /* Indicates sample profiling when nonzero, interval in microseconds. */
    public final int samplingInterval;

    /* Automatically stop the profiler when the app goes idle. */
    public final boolean autoStopProfiler;

    /*
     * Indicates whether to stream the profiling info to the out file continuously.
     */
    public final boolean streamingOutput;

    /**
     * Denotes an agent (and its parameters) to attach for profiling.
     */
    public final String agent;

    /**
     * Whether the {@link agent} should be attached early (before bind-application) or during
     * bind-application. Agents attached prior to binding cannot be loaded from the app's APK
     * directly and must be given as an absolute path (or available in the default LD_LIBRARY_PATH).
     * Agents attached during bind-application will miss early setup (e.g., resource initialization
     * and classloader generation), but are searched in the app's library search path.
     */
    public final boolean attachAgentDuringBind;

    /**
     * Flags to pass to the profiler. They determine various options including:
     *   clock type for timestamps - this can be wallclock, thread cpu or both
     *   version of the generated trace file
     *   tracing type - low-overhead but imprecise tracing / precise tracing
     */
    public final int profilerFlags;

    /**
     * Indicates if we should trace long running methods for lowoverhead tracing
     */
    public final boolean profileLongRunningMethods;

    /**
     * The duration in microseconds for which the lowoverhed tracing has to be run
     */
    public final long durationMicros;

    public ProfilerInfo(String filename, ParcelFileDescriptor fd, int interval, boolean autoStop,
            boolean streaming, String agent, boolean attachAgentDuringBind, int profilerFlags,
            boolean profileLongRunningMethods, long durationMicros) {
        profileFile = filename;
        profileFd = fd;
        samplingInterval = interval;
        autoStopProfiler = autoStop;
        streamingOutput = streaming;
        this.agent = agent;
        this.attachAgentDuringBind = attachAgentDuringBind;
        this.profilerFlags = profilerFlags;
        this.profileLongRunningMethods = profileLongRunningMethods;
        this.durationMicros = durationMicros;
    }

    public ProfilerInfo(ProfilerInfo in) {
        profileFile = in.profileFile;
        profileFd = in.profileFd;
        samplingInterval = in.samplingInterval;
        autoStopProfiler = in.autoStopProfiler;
        streamingOutput = in.streamingOutput;
        agent = in.agent;
        attachAgentDuringBind = in.attachAgentDuringBind;
        profilerFlags = in.profilerFlags;
        profileLongRunningMethods = in.profileLongRunningMethods;
        durationMicros = in.durationMicros;
    }

    /**
     * Get the value for the clock type corresponding to the option string passed to the activity
     * manager. am profile start / am start-activity start-profiler commands accept clock-type
     * option to choose the source of timestamps when profiling. This function maps the option
     * string to the value of flags that is used when calling VMDebug.startMethodTracing
     */
    public static int getClockTypeFromString(String type) {
        if ("thread-cpu".equals(type)) {
            return CLOCK_TYPE_THREAD_CPU;
        } else if ("wall".equals(type)) {
            return CLOCK_TYPE_WALL;
        } else if ("dual".equals(type)) {
            return CLOCK_TYPE_DUAL;
        } else {
            return CLOCK_TYPE_DEFAULT;
        }
    }

    /**
     * Get the flags that need to be passed to VMDebug.startMethodTracing to specify the desired
     * output format.
     */
    public static int getFlagsForOutputVersion(int version) {
        // Only two version 1 and version 2 are supported. Just use the default if we see an unknown
        // version.
        if (version != 1 && version != 2) {
            version = OUTPUT_VERSION_DEFAULT;
        }

        // The encoded version in the flags starts from 0, where as the version that we read from
        // user starts from 1. So, subtract one before encoding it in the flags.
        return (version - 1) << TRACE_FORMAT_VERSION_SHIFT;
    }

    /**
     * Update the flags with the specified output version and the clock type. Flags include clock
     * type and output version but we allow users to specify them in text format. If user specifies
     * flags along with --clock-type / --profiler-output-version we give priority to the one
     * specified by --clock-type / --profiler-output-version.
     */
    public static int updateFlags(int clockType, int outputVersion, int flags) {
        // If clock type was specified explicitly using --clock-type use the clock type instead of
        // the one specified in flags.
        if (clockType != 0) {
            // Reset the bits specifying the flag and use the one specified by --clock-type.
            flags = flags & CLOCK_TYPE_MASK;
            flags = flags | clockType;
        }

        // If output version was specified using --profiler-output-version use that version instead
        // of the one specified in flags.
        if (outputVersion != 0) {
            // Reset the bits specifying the flag and use the one specified by
            // --profiler-output-version.
            flags = flags & OUTPUT_VERSION_MASK;
            flags = flags | getFlagsForOutputVersion(outputVersion);
        }

        return flags;
    }

    /**
     * Return a new ProfilerInfo instance, with fields populated from this object,
     * and {@link agent} and {@link attachAgentDuringBind} as given.
     */
    public ProfilerInfo setAgent(String agent, boolean attachAgentDuringBind) {
        return new ProfilerInfo(this.profileFile, this.profileFd, this.samplingInterval,
                this.autoStopProfiler, this.streamingOutput, agent, attachAgentDuringBind,
                this.profilerFlags, this.profileLongRunningMethods, this.durationMicros);
    }

    /**
     * Close profileFd, if it is open. The field will be null after a call to this function.
     */
    public void closeFd() {
        if (profileFd != null) {
            try {
                profileFd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failure closing profile fd", e);
            }
            profileFd = null;
        }
    }

    @Override
    public int describeContents() {
        if (profileFd != null) {
            return profileFd.describeContents();
        } else {
            return 0;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(profileFile);
        if (profileFd != null) {
            out.writeInt(1);
            profileFd.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeInt(samplingInterval);
        out.writeInt(autoStopProfiler ? 1 : 0);
        out.writeInt(streamingOutput ? 1 : 0);
        out.writeString(agent);
        out.writeBoolean(attachAgentDuringBind);
        out.writeInt(this.profilerFlags);
        out.writeBoolean(profileLongRunningMethods);
        out.writeLong(durationMicros);
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ProfilerInfoProto.PROFILE_FILE, profileFile);
        if (profileFd != null) {
            proto.write(ProfilerInfoProto.PROFILE_FD, profileFd.getFd());
        }
        proto.write(ProfilerInfoProto.SAMPLING_INTERVAL, samplingInterval);
        proto.write(ProfilerInfoProto.AUTO_STOP_PROFILER, autoStopProfiler);
        proto.write(ProfilerInfoProto.STREAMING_OUTPUT, streamingOutput);
        proto.write(ProfilerInfoProto.AGENT, agent);
        proto.write(ProfilerInfoProto.PROFILER_FLAGS, profilerFlags);
        proto.write(ProfilerInfoProto.PROFILE_LONG_RUNNING_METHODS, profileLongRunningMethods);
        proto.write(ProfilerInfoProto.DURATION_MICROS, durationMicros);
        proto.end(token);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ProfilerInfo> CREATOR =
            new Parcelable.Creator<ProfilerInfo>() {
                @Override
                public ProfilerInfo createFromParcel(Parcel in) {
                    return new ProfilerInfo(in);
                }

                @Override
                public ProfilerInfo[] newArray(int size) {
                    return new ProfilerInfo[size];
                }
            };

    private ProfilerInfo(Parcel in) {
        profileFile = in.readString();
        profileFd = in.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(in) : null;
        samplingInterval = in.readInt();
        autoStopProfiler = in.readInt() != 0;
        streamingOutput = in.readInt() != 0;
        agent = in.readString();
        attachAgentDuringBind = in.readBoolean();
        profilerFlags = in.readInt();
        profileLongRunningMethods = in.readBoolean();
        durationMicros = in.readLong();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ProfilerInfo other = (ProfilerInfo) o;
        // TODO: Also check #profileFd for equality.
        return Objects.equals(profileFile, other.profileFile)
                && autoStopProfiler == other.autoStopProfiler
                && samplingInterval == other.samplingInterval
                && streamingOutput == other.streamingOutput && Objects.equals(agent, other.agent)
                && profilerFlags == other.profilerFlags
                && profileLongRunningMethods == other.profileLongRunningMethods
                && durationMicros == other.durationMicros;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(profileFile);
        result = 31 * result + samplingInterval;
        result = 31 * result + (autoStopProfiler ? 1 : 0);
        result = 31 * result + (streamingOutput ? 1 : 0);
        result = 31 * result + Objects.hashCode(agent);
        result = 31 * result + profilerFlags;
        result = 31 * result + (profileLongRunningMethods ? 1 : 0);
        result = 31 * result + Long.hashCode(durationMicros);
        return result;
    }
}
