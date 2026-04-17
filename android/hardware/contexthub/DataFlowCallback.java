/*
 * Copyright 2025 The Android Open Source Project
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
package android.hardware.contexthub;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Interface for handling all events relating to data flows. This includes receiving {@link
 * DataFlowSink}s from offload endpoint sources, as well as handling for all events relating to
 * active {@link DataFlowSource} and {@link DataFlowSink} instances owned by this endpoint.
 *
 * <p>See {@link HubEndpoint#createDataFlowSource(Set, DataFlowDataConfig, int, int)} for an
 * overview of data flows.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_FMCQ_API)
public interface DataFlowCallback {
    /**
     * Event indicating that the user may read data from a sink. This event is triggered by the
     * source based on the {@link DataFlowNewDataAlertPolicy} it has set for this sink.
     */
    public static final int SINK_EVENT_READABLE = 0;

    /**
     * Event indicating that the source of a data flow the user is reading from has stopped. All
     * {@link DataFlowSink} APIs which can throw an {@link IllegalStateException} due to the data
     * flow being stopped will throw that exception. The user can still use the {@link DataFlowSink}
     * instance to look up local state associated with the sink.
     */
    public static final int SINK_EVENT_STOPPED = 1;

    /**
     * Event indicating that the user may write data to a data flow. This is generally triggered if
     * the user was previously blocked from pushing data due to a slow sink. Either that sink has
     * read data, opening up space in the data flow, or that sink has crashed, disconnected from the
     * messaging network, or stopped reading from the data flow.
     */
    public static final int SOURCE_EVENT_WRITABLE = 0;

    /**
     * Event indicating that a sink has stopped reading from the data flow. The sink's {@link
     * HubEndpointInfo} is provided in the {@link SourceEventData}.
     */
    public static final int SOURCE_EVENT_SINK_GONE = 1;

    /**
     * Events that can be triggered on a sink.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "SINK_EVENT_",
            value = {SINK_EVENT_READABLE, SINK_EVENT_STOPPED})
    /* package */ @interface SinkEvent {}

    /**
     * Events that can be triggered on a source.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "SOURCE_EVENT_",
            value = {SOURCE_EVENT_WRITABLE, SOURCE_EVENT_SINK_GONE})
    /* package */ @interface SourceEvent {}

    /** Event data for a {@link SourceEvent}. */
    @FlaggedApi(Flags.FLAG_FMCQ_API)
    class SourceEventData {
        @Nullable private final HubEndpointInfo mSink;

        /**
         * Creates a new {@link SourceEventData} instance for an event associated with a sink.
         *
         * @param sink The sink endpoint that triggered this event
         */
        public SourceEventData(@NonNull HubEndpointInfo sink) {
            Objects.requireNonNull(sink);
            mSink = sink;
        }

        /**
         * Returns information about the sink endpoint associated with this event, if any.
         *
         * @return The endpoint's {@link HubEndpointInfo} or {@code null} if this event is not
         *     associated with a sink endpoint
         */
        @Nullable
        public HubEndpointInfo getSink() {
            return mSink;
        }
    }

    /**
     * Called when this endpoint is added as a sink for a data flow whose source is an offload
     * endpoint.
     *
     * <p>The new {@link DataFlowSink} instance is synced to the source's position at the time of
     * sharing the flow with this endpoint. Any data pushed to the flow after this point will be
     * available for this sink to read, unless the source subsequently overwrites the sink's read
     * position. Once this method is called, the user will start receiving new data alerts per the
     * {@link DataFlowNewDataAlertPolicy} set by the source.
     *
     * <p>The source may have shared an optional message along with this registration event,
     * provided to the user as {@code msg}. If the source endpoint also associated the registration
     * with a valid open {@code sessionId}, it may have been notified that this registration event
     * occurred.
     *
     * <p>The framework ensures that this endpoint has the necessary permissions to be able to
     * access the data flow.
     *
     * @param sink The new {@link DataFlowSink} instance giving this endpoint access to the data
     *     flow
     * @param source The source endpoint of the data flow
     * @param session If not {@code null}, the source shared the data flow with this endpoint over
     *     the provided open session
     * @param msg If {@code session} is not {@code null}, a message that the source shared with this
     *     endpoint as part of the registration
     */
    public void onReceivedDataFlowSink(
            @NonNull DataFlowSink sink,
            @NonNull HubEndpointInfo source,
            @Nullable HubEndpointSession session,
            @Nullable HubMessage msg);

    /**
     * Called when an event occurs on a data flow whose source is this endpoint.
     *
     * @param source The associated {@link DataFlowSource} instance which was initially returned by
     *     {@link HubEndpoint#createDataFlowSource(Set, DataFlowDataConfig, int, int)}
     * @param event The source event that occurred, either {@link #SOURCE_EVENT_WRITABLE} or {@link
     *     #SOURCE_EVENT_SINK_GONE}
     * @param data An optional payload associated with the event
     */
    public void onDataFlowSourceEvent(
            @NonNull DataFlowSource source, @SourceEvent int event, @Nullable SourceEventData data);

    /**
     * Called when an event occurs on a data flow whose source is an offload endpoint and for which
     * this endpoint is a sink.
     *
     * @param sink The associated {@link DataFlowSink} instance which was initially provided to
     *     {@link #onDataFlowHostSinkRegistered(DataFlowSink, HubEndpointInfo, HubMessage, int)}
     * @param event The sink event that occurred, either {@link #SINK_EVENT_READABLE} or {@link
     *     #SINK_EVENT_STOPPED}
     */
    public void onDataFlowSinkEvent(@NonNull DataFlowSink sink, @SinkEvent int event);
}
