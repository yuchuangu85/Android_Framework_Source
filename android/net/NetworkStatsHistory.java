/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStatsHistory.DataStreamUtils.readFullLongArray;
import static android.net.NetworkStatsHistory.DataStreamUtils.readVarLongArray;
import static android.net.NetworkStatsHistory.DataStreamUtils.writeVarLongArray;
import static android.net.NetworkStatsHistory.Entry.UNKNOWN;
import static android.net.NetworkStatsHistory.ParcelUtils.readLongArray;
import static android.net.NetworkStatsHistory.ParcelUtils.writeLongArray;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.internal.net.NetworkUtilsInternal.multiplySafeByRational;
import static com.android.internal.util.ArrayUtils.total;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.NetworkStatsHistoryBucketProto;
import android.service.NetworkStatsHistoryProto;
import android.util.MathUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;

import libcore.util.EmptyArray;

import java.io.CharArrayWriter;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.Arrays;
import java.util.Random;

/**
 * Collection of historical network statistics, recorded into equally-sized
 * "buckets" in time. Internally it stores data in {@code long} series for more
 * efficient persistence.
 * <p>
 * Each bucket is defined by a {@link #bucketStart} timestamp, and lasts for
 * {@link #bucketDuration}. Internally assumes that {@link #bucketStart} is
 * sorted at all times.
 *
 * @hide
 */
public class NetworkStatsHistory implements Parcelable {
    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADD_PACKETS = 2;
    private static final int VERSION_ADD_ACTIVE = 3;

    public static final int FIELD_ACTIVE_TIME = 0x01;
    public static final int FIELD_RX_BYTES = 0x02;
    public static final int FIELD_RX_PACKETS = 0x04;
    public static final int FIELD_TX_BYTES = 0x08;
    public static final int FIELD_TX_PACKETS = 0x10;
    public static final int FIELD_OPERATIONS = 0x20;

    public static final int FIELD_ALL = 0xFFFFFFFF;

    private long bucketDuration;
    private int bucketCount;
    private long[] bucketStart;
    private long[] activeTime;
    private long[] rxBytes;
    private long[] rxPackets;
    private long[] txBytes;
    private long[] txPackets;
    private long[] operations;
    private long totalBytes;

    public static class Entry {
        public static final long UNKNOWN = -1;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long bucketDuration;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long bucketStart;
        public long activeTime;
        @UnsupportedAppUsage
        public long rxBytes;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long rxPackets;
        @UnsupportedAppUsage
        public long txBytes;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long txPackets;
        public long operations;
    }

    @UnsupportedAppUsage
    public NetworkStatsHistory(long bucketDuration) {
        this(bucketDuration, 10, FIELD_ALL);
    }

    public NetworkStatsHistory(long bucketDuration, int initialSize) {
        this(bucketDuration, initialSize, FIELD_ALL);
    }

    public NetworkStatsHistory(long bucketDuration, int initialSize, int fields) {
        this.bucketDuration = bucketDuration;
        bucketStart = new long[initialSize];
        if ((fields & FIELD_ACTIVE_TIME) != 0) activeTime = new long[initialSize];
        if ((fields & FIELD_RX_BYTES) != 0) rxBytes = new long[initialSize];
        if ((fields & FIELD_RX_PACKETS) != 0) rxPackets = new long[initialSize];
        if ((fields & FIELD_TX_BYTES) != 0) txBytes = new long[initialSize];
        if ((fields & FIELD_TX_PACKETS) != 0) txPackets = new long[initialSize];
        if ((fields & FIELD_OPERATIONS) != 0) operations = new long[initialSize];
        bucketCount = 0;
        totalBytes = 0;
    }

    public NetworkStatsHistory(NetworkStatsHistory existing, long bucketDuration) {
        this(bucketDuration, existing.estimateResizeBuckets(bucketDuration));
        recordEntireHistory(existing);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public NetworkStatsHistory(Parcel in) {
        bucketDuration = in.readLong();
        bucketStart = readLongArray(in);
        activeTime = readLongArray(in);
        rxBytes = readLongArray(in);
        rxPackets = readLongArray(in);
        txBytes = readLongArray(in);
        txPackets = readLongArray(in);
        operations = readLongArray(in);
        bucketCount = bucketStart.length;
        totalBytes = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(bucketDuration);
        writeLongArray(out, bucketStart, bucketCount);
        writeLongArray(out, activeTime, bucketCount);
        writeLongArray(out, rxBytes, bucketCount);
        writeLongArray(out, rxPackets, bucketCount);
        writeLongArray(out, txBytes, bucketCount);
        writeLongArray(out, txPackets, bucketCount);
        writeLongArray(out, operations, bucketCount);
        out.writeLong(totalBytes);
    }

    public NetworkStatsHistory(DataInput in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_INIT: {
                bucketDuration = in.readLong();
                bucketStart = readFullLongArray(in);
                rxBytes = readFullLongArray(in);
                rxPackets = new long[bucketStart.length];
                txBytes = readFullLongArray(in);
                txPackets = new long[bucketStart.length];
                operations = new long[bucketStart.length];
                bucketCount = bucketStart.length;
                totalBytes = total(rxBytes) + total(txBytes);
                break;
            }
            case VERSION_ADD_PACKETS:
            case VERSION_ADD_ACTIVE: {
                bucketDuration = in.readLong();
                bucketStart = readVarLongArray(in);
                activeTime = (version >= VERSION_ADD_ACTIVE) ? readVarLongArray(in)
                        : new long[bucketStart.length];
                rxBytes = readVarLongArray(in);
                rxPackets = readVarLongArray(in);
                txBytes = readVarLongArray(in);
                txPackets = readVarLongArray(in);
                operations = readVarLongArray(in);
                bucketCount = bucketStart.length;
                totalBytes = total(rxBytes) + total(txBytes);
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }

        if (bucketStart.length != bucketCount || rxBytes.length != bucketCount
                || rxPackets.length != bucketCount || txBytes.length != bucketCount
                || txPackets.length != bucketCount || operations.length != bucketCount) {
            throw new ProtocolException("Mismatched history lengths");
        }
    }

    public void writeToStream(DataOutput out) throws IOException {
        out.writeInt(VERSION_ADD_ACTIVE);
        out.writeLong(bucketDuration);
        writeVarLongArray(out, bucketStart, bucketCount);
        writeVarLongArray(out, activeTime, bucketCount);
        writeVarLongArray(out, rxBytes, bucketCount);
        writeVarLongArray(out, rxPackets, bucketCount);
        writeVarLongArray(out, txBytes, bucketCount);
        writeVarLongArray(out, txPackets, bucketCount);
        writeVarLongArray(out, operations, bucketCount);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int size() {
        return bucketCount;
    }

    public long getBucketDuration() {
        return bucketDuration;
    }

    @UnsupportedAppUsage
    public long getStart() {
        if (bucketCount > 0) {
            return bucketStart[0];
        } else {
            return Long.MAX_VALUE;
        }
    }

    @UnsupportedAppUsage
    public long getEnd() {
        if (bucketCount > 0) {
            return bucketStart[bucketCount - 1] + bucketDuration;
        } else {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Return total bytes represented by this history.
     */
    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * Return index of bucket that contains or is immediately before the
     * requested time.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getIndexBefore(long time) {
        int index = Arrays.binarySearch(bucketStart, 0, bucketCount, time);
        if (index < 0) {
            index = (~index) - 1;
        } else {
            index -= 1;
        }
        return MathUtils.constrain(index, 0, bucketCount - 1);
    }

    /**
     * Return index of bucket that contains or is immediately after the
     * requested time.
     */
    public int getIndexAfter(long time) {
        int index = Arrays.binarySearch(bucketStart, 0, bucketCount, time);
        if (index < 0) {
            index = ~index;
        } else {
            index += 1;
        }
        return MathUtils.constrain(index, 0, bucketCount - 1);
    }

    /**
     * Return specific stats entry.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Entry getValues(int i, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.bucketStart = bucketStart[i];
        entry.bucketDuration = bucketDuration;
        entry.activeTime = getLong(activeTime, i, UNKNOWN);
        entry.rxBytes = getLong(rxBytes, i, UNKNOWN);
        entry.rxPackets = getLong(rxPackets, i, UNKNOWN);
        entry.txBytes = getLong(txBytes, i, UNKNOWN);
        entry.txPackets = getLong(txPackets, i, UNKNOWN);
        entry.operations = getLong(operations, i, UNKNOWN);
        return entry;
    }

    public void setValues(int i, Entry entry) {
        // Unwind old values
        if (rxBytes != null) totalBytes -= rxBytes[i];
        if (txBytes != null) totalBytes -= txBytes[i];

        bucketStart[i] = entry.bucketStart;
        setLong(activeTime, i, entry.activeTime);
        setLong(rxBytes, i, entry.rxBytes);
        setLong(rxPackets, i, entry.rxPackets);
        setLong(txBytes, i, entry.txBytes);
        setLong(txPackets, i, entry.txPackets);
        setLong(operations, i, entry.operations);

        // Apply new values
        if (rxBytes != null) totalBytes += rxBytes[i];
        if (txBytes != null) totalBytes += txBytes[i];
    }

    /**
     * Record that data traffic occurred in the given time range. Will
     * distribute across internal buckets, creating new buckets as needed.
     */
    @Deprecated
    public void recordData(long start, long end, long rxBytes, long txBytes) {
        recordData(start, end, new NetworkStats.Entry(
                IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, rxBytes, 0L, txBytes, 0L, 0L));
    }

    /**
     * Record that data traffic occurred in the given time range. Will
     * distribute across internal buckets, creating new buckets as needed.
     */
    public void recordData(long start, long end, NetworkStats.Entry entry) {
        long rxBytes = entry.rxBytes;
        long rxPackets = entry.rxPackets;
        long txBytes = entry.txBytes;
        long txPackets = entry.txPackets;
        long operations = entry.operations;

        if (entry.isNegative()) {
            throw new IllegalArgumentException("tried recording negative data");
        }
        if (entry.isEmpty()) {
            return;
        }

        // create any buckets needed by this range
        ensureBuckets(start, end);

        // distribute data usage into buckets
        long duration = end - start;
        final int startIndex = getIndexAfter(end);
        for (int i = startIndex; i >= 0; i--) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // bucket is older than record; we're finished
            if (curEnd < start) break;
            // bucket is newer than record; keep looking
            if (curStart > end) continue;

            final long overlap = Math.min(curEnd, end) - Math.max(curStart, start);
            if (overlap <= 0) continue;

            // integer math each time is faster than floating point
            final long fracRxBytes = multiplySafeByRational(rxBytes, overlap, duration);
            final long fracRxPackets = multiplySafeByRational(rxPackets, overlap, duration);
            final long fracTxBytes = multiplySafeByRational(txBytes, overlap, duration);
            final long fracTxPackets = multiplySafeByRational(txPackets, overlap, duration);
            final long fracOperations = multiplySafeByRational(operations, overlap, duration);


            addLong(activeTime, i, overlap);
            addLong(this.rxBytes, i, fracRxBytes); rxBytes -= fracRxBytes;
            addLong(this.rxPackets, i, fracRxPackets); rxPackets -= fracRxPackets;
            addLong(this.txBytes, i, fracTxBytes); txBytes -= fracTxBytes;
            addLong(this.txPackets, i, fracTxPackets); txPackets -= fracTxPackets;
            addLong(this.operations, i, fracOperations); operations -= fracOperations;

            duration -= overlap;
        }

        totalBytes += entry.rxBytes + entry.txBytes;
    }

    /**
     * Record an entire {@link NetworkStatsHistory} into this history. Usually
     * for combining together stats for external reporting.
     */
    @UnsupportedAppUsage
    public void recordEntireHistory(NetworkStatsHistory input) {
        recordHistory(input, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Record given {@link NetworkStatsHistory} into this history, copying only
     * buckets that atomically occur in the inclusive time range. Doesn't
     * interpolate across partial buckets.
     */
    public void recordHistory(NetworkStatsHistory input, long start, long end) {
        final NetworkStats.Entry entry = new NetworkStats.Entry(
                IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0L);
        for (int i = 0; i < input.bucketCount; i++) {
            final long bucketStart = input.bucketStart[i];
            final long bucketEnd = bucketStart + input.bucketDuration;

            // skip when bucket is outside requested range
            if (bucketStart < start || bucketEnd > end) continue;

            entry.rxBytes = getLong(input.rxBytes, i, 0L);
            entry.rxPackets = getLong(input.rxPackets, i, 0L);
            entry.txBytes = getLong(input.txBytes, i, 0L);
            entry.txPackets = getLong(input.txPackets, i, 0L);
            entry.operations = getLong(input.operations, i, 0L);

            recordData(bucketStart, bucketEnd, entry);
        }
    }

    /**
     * Ensure that buckets exist for given time range, creating as needed.
     */
    private void ensureBuckets(long start, long end) {
        // normalize incoming range to bucket boundaries
        start -= start % bucketDuration;
        end += (bucketDuration - (end % bucketDuration)) % bucketDuration;

        for (long now = start; now < end; now += bucketDuration) {
            // try finding existing bucket
            final int index = Arrays.binarySearch(bucketStart, 0, bucketCount, now);
            if (index < 0) {
                // bucket missing, create and insert
                insertBucket(~index, now);
            }
        }
    }

    /**
     * Insert new bucket at requested index and starting time.
     */
    private void insertBucket(int index, long start) {
        // create more buckets when needed
        if (bucketCount >= bucketStart.length) {
            final int newLength = Math.max(bucketStart.length, 10) * 3 / 2;
            bucketStart = Arrays.copyOf(bucketStart, newLength);
            if (activeTime != null) activeTime = Arrays.copyOf(activeTime, newLength);
            if (rxBytes != null) rxBytes = Arrays.copyOf(rxBytes, newLength);
            if (rxPackets != null) rxPackets = Arrays.copyOf(rxPackets, newLength);
            if (txBytes != null) txBytes = Arrays.copyOf(txBytes, newLength);
            if (txPackets != null) txPackets = Arrays.copyOf(txPackets, newLength);
            if (operations != null) operations = Arrays.copyOf(operations, newLength);
        }

        // create gap when inserting bucket in middle
        if (index < bucketCount) {
            final int dstPos = index + 1;
            final int length = bucketCount - index;

            System.arraycopy(bucketStart, index, bucketStart, dstPos, length);
            if (activeTime != null) System.arraycopy(activeTime, index, activeTime, dstPos, length);
            if (rxBytes != null) System.arraycopy(rxBytes, index, rxBytes, dstPos, length);
            if (rxPackets != null) System.arraycopy(rxPackets, index, rxPackets, dstPos, length);
            if (txBytes != null) System.arraycopy(txBytes, index, txBytes, dstPos, length);
            if (txPackets != null) System.arraycopy(txPackets, index, txPackets, dstPos, length);
            if (operations != null) System.arraycopy(operations, index, operations, dstPos, length);
        }

        bucketStart[index] = start;
        setLong(activeTime, index, 0L);
        setLong(rxBytes, index, 0L);
        setLong(rxPackets, index, 0L);
        setLong(txBytes, index, 0L);
        setLong(txPackets, index, 0L);
        setLong(operations, index, 0L);
        bucketCount++;
    }

    /**
     * Clear all data stored in this object.
     */
    public void clear() {
        bucketStart = EmptyArray.LONG;
        if (activeTime != null) activeTime = EmptyArray.LONG;
        if (rxBytes != null) rxBytes = EmptyArray.LONG;
        if (rxPackets != null) rxPackets = EmptyArray.LONG;
        if (txBytes != null) txBytes = EmptyArray.LONG;
        if (txPackets != null) txPackets = EmptyArray.LONG;
        if (operations != null) operations = EmptyArray.LONG;
        bucketCount = 0;
        totalBytes = 0;
    }

    /**
     * Remove buckets older than requested cutoff.
     */
    @Deprecated
    public void removeBucketsBefore(long cutoff) {
        int i;
        for (i = 0; i < bucketCount; i++) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // cutoff happens before or during this bucket; everything before
            // this bucket should be removed.
            if (curEnd > cutoff) break;
        }

        if (i > 0) {
            final int length = bucketStart.length;
            bucketStart = Arrays.copyOfRange(bucketStart, i, length);
            if (activeTime != null) activeTime = Arrays.copyOfRange(activeTime, i, length);
            if (rxBytes != null) rxBytes = Arrays.copyOfRange(rxBytes, i, length);
            if (rxPackets != null) rxPackets = Arrays.copyOfRange(rxPackets, i, length);
            if (txBytes != null) txBytes = Arrays.copyOfRange(txBytes, i, length);
            if (txPackets != null) txPackets = Arrays.copyOfRange(txPackets, i, length);
            if (operations != null) operations = Arrays.copyOfRange(operations, i, length);
            bucketCount -= i;

            // TODO: subtract removed values from totalBytes
        }
    }

    /**
     * Return interpolated data usage across the requested range. Interpolates
     * across buckets, so values may be rounded slightly.
     */
    @UnsupportedAppUsage
    public Entry getValues(long start, long end, Entry recycle) {
        return getValues(start, end, Long.MAX_VALUE, recycle);
    }

    /**
     * Return interpolated data usage across the requested range. Interpolates
     * across buckets, so values may be rounded slightly.
     */
    @UnsupportedAppUsage
    public Entry getValues(long start, long end, long now, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.bucketDuration = end - start;
        entry.bucketStart = start;
        entry.activeTime = activeTime != null ? 0 : UNKNOWN;
        entry.rxBytes = rxBytes != null ? 0 : UNKNOWN;
        entry.rxPackets = rxPackets != null ? 0 : UNKNOWN;
        entry.txBytes = txBytes != null ? 0 : UNKNOWN;
        entry.txPackets = txPackets != null ? 0 : UNKNOWN;
        entry.operations = operations != null ? 0 : UNKNOWN;

        final int startIndex = getIndexAfter(end);
        for (int i = startIndex; i >= 0; i--) {
            final long curStart = bucketStart[i];
            long curEnd = curStart + bucketDuration;

            // bucket is older than request; we're finished
            if (curEnd <= start) break;
            // bucket is newer than request; keep looking
            if (curStart >= end) continue;

            // the active bucket is shorter then a normal completed bucket
            if (curEnd > now) curEnd = now;
            // usually this is simply bucketDuration
            final long bucketSpan = curEnd - curStart;
            // prevent division by zero
            if (bucketSpan <= 0) continue;

            final long overlapEnd = curEnd < end ? curEnd : end;
            final long overlapStart = curStart > start ? curStart : start;
            final long overlap = overlapEnd - overlapStart;
            if (overlap <= 0) continue;

            // integer math each time is faster than floating point
            if (activeTime != null) {
                entry.activeTime += multiplySafeByRational(activeTime[i], overlap, bucketSpan);
            }
            if (rxBytes != null) {
                entry.rxBytes += multiplySafeByRational(rxBytes[i], overlap, bucketSpan);
            }
            if (rxPackets != null) {
                entry.rxPackets += multiplySafeByRational(rxPackets[i], overlap, bucketSpan);
            }
            if (txBytes != null) {
                entry.txBytes += multiplySafeByRational(txBytes[i], overlap, bucketSpan);
            }
            if (txPackets != null) {
                entry.txPackets += multiplySafeByRational(txPackets[i], overlap, bucketSpan);
            }
            if (operations != null) {
                entry.operations += multiplySafeByRational(operations[i], overlap, bucketSpan);
            }
        }
        return entry;
    }

    /**
     * @deprecated only for temporary testing
     */
    @Deprecated
    public void generateRandom(long start, long end, long bytes) {
        final Random r = new Random();

        final float fractionRx = r.nextFloat();
        final long rxBytes = (long) (bytes * fractionRx);
        final long txBytes = (long) (bytes * (1 - fractionRx));

        final long rxPackets = rxBytes / 1024;
        final long txPackets = txBytes / 1024;
        final long operations = rxBytes / 2048;

        generateRandom(start, end, rxBytes, rxPackets, txBytes, txPackets, operations, r);
    }

    /**
     * @deprecated only for temporary testing
     */
    @Deprecated
    public void generateRandom(long start, long end, long rxBytes, long rxPackets, long txBytes,
            long txPackets, long operations, Random r) {
        ensureBuckets(start, end);

        final NetworkStats.Entry entry = new NetworkStats.Entry(
                IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0L);
        while (rxBytes > 1024 || rxPackets > 128 || txBytes > 1024 || txPackets > 128
                || operations > 32) {
            final long curStart = randomLong(r, start, end);
            final long curEnd = curStart + randomLong(r, 0, (end - curStart) / 2);

            entry.rxBytes = randomLong(r, 0, rxBytes);
            entry.rxPackets = randomLong(r, 0, rxPackets);
            entry.txBytes = randomLong(r, 0, txBytes);
            entry.txPackets = randomLong(r, 0, txPackets);
            entry.operations = randomLong(r, 0, operations);

            rxBytes -= entry.rxBytes;
            rxPackets -= entry.rxPackets;
            txBytes -= entry.txBytes;
            txPackets -= entry.txPackets;
            operations -= entry.operations;

            recordData(curStart, curEnd, entry);
        }
    }

    public static long randomLong(Random r, long start, long end) {
        return (long) (start + (r.nextFloat() * (end - start)));
    }

    /**
     * Quickly determine if this history intersects with given window.
     */
    public boolean intersects(long start, long end) {
        final long dataStart = getStart();
        final long dataEnd = getEnd();
        if (start >= dataStart && start <= dataEnd) return true;
        if (end >= dataStart && end <= dataEnd) return true;
        if (dataStart >= start && dataStart <= end) return true;
        if (dataEnd >= start && dataEnd <= end) return true;
        return false;
    }

    public void dump(IndentingPrintWriter pw, boolean fullHistory) {
        pw.print("NetworkStatsHistory: bucketDuration=");
        pw.println(bucketDuration / SECOND_IN_MILLIS);
        pw.increaseIndent();

        final int start = fullHistory ? 0 : Math.max(0, bucketCount - 32);
        if (start > 0) {
            pw.print("(omitting "); pw.print(start); pw.println(" buckets)");
        }

        for (int i = start; i < bucketCount; i++) {
            pw.print("st="); pw.print(bucketStart[i] / SECOND_IN_MILLIS);
            if (rxBytes != null) { pw.print(" rb="); pw.print(rxBytes[i]); }
            if (rxPackets != null) { pw.print(" rp="); pw.print(rxPackets[i]); }
            if (txBytes != null) { pw.print(" tb="); pw.print(txBytes[i]); }
            if (txPackets != null) { pw.print(" tp="); pw.print(txPackets[i]); }
            if (operations != null) { pw.print(" op="); pw.print(operations[i]); }
            pw.println();
        }

        pw.decreaseIndent();
    }

    public void dumpCheckin(PrintWriter pw) {
        pw.print("d,");
        pw.print(bucketDuration / SECOND_IN_MILLIS);
        pw.println();

        for (int i = 0; i < bucketCount; i++) {
            pw.print("b,");
            pw.print(bucketStart[i] / SECOND_IN_MILLIS); pw.print(',');
            if (rxBytes != null) { pw.print(rxBytes[i]); } else { pw.print("*"); } pw.print(',');
            if (rxPackets != null) { pw.print(rxPackets[i]); } else { pw.print("*"); } pw.print(',');
            if (txBytes != null) { pw.print(txBytes[i]); } else { pw.print("*"); } pw.print(',');
            if (txPackets != null) { pw.print(txPackets[i]); } else { pw.print("*"); } pw.print(',');
            if (operations != null) { pw.print(operations[i]); } else { pw.print("*"); }
            pw.println();
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long tag) {
        final long start = proto.start(tag);

        proto.write(NetworkStatsHistoryProto.BUCKET_DURATION_MS, bucketDuration);

        for (int i = 0; i < bucketCount; i++) {
            final long startBucket = proto.start(NetworkStatsHistoryProto.BUCKETS);

            proto.write(NetworkStatsHistoryBucketProto.BUCKET_START_MS, bucketStart[i]);
            dumpDebug(proto, NetworkStatsHistoryBucketProto.RX_BYTES, rxBytes, i);
            dumpDebug(proto, NetworkStatsHistoryBucketProto.RX_PACKETS, rxPackets, i);
            dumpDebug(proto, NetworkStatsHistoryBucketProto.TX_BYTES, txBytes, i);
            dumpDebug(proto, NetworkStatsHistoryBucketProto.TX_PACKETS, txPackets, i);
            dumpDebug(proto, NetworkStatsHistoryBucketProto.OPERATIONS, operations, i);

            proto.end(startBucket);
        }

        proto.end(start);
    }

    private static void dumpDebug(ProtoOutputStream proto, long tag, long[] array, int index) {
        if (array != null) {
            proto.write(tag, array[index]);
        }
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "  "), false);
        return writer.toString();
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<NetworkStatsHistory> CREATOR = new Creator<NetworkStatsHistory>() {
        @Override
        public NetworkStatsHistory createFromParcel(Parcel in) {
            return new NetworkStatsHistory(in);
        }

        @Override
        public NetworkStatsHistory[] newArray(int size) {
            return new NetworkStatsHistory[size];
        }
    };

    private static long getLong(long[] array, int i, long value) {
        return array != null ? array[i] : value;
    }

    private static void setLong(long[] array, int i, long value) {
        if (array != null) array[i] = value;
    }

    private static void addLong(long[] array, int i, long value) {
        if (array != null) array[i] += value;
    }

    public int estimateResizeBuckets(long newBucketDuration) {
        return (int) (size() * getBucketDuration() / newBucketDuration);
    }

    /**
     * Utility methods for interacting with {@link DataInputStream} and
     * {@link DataOutputStream}, mostly dealing with writing partial arrays.
     */
    public static class DataStreamUtils {
        @Deprecated
        public static long[] readFullLongArray(DataInput in) throws IOException {
            final int size = in.readInt();
            if (size < 0) throw new ProtocolException("negative array size");
            final long[] values = new long[size];
            for (int i = 0; i < values.length; i++) {
                values[i] = in.readLong();
            }
            return values;
        }

        /**
         * Read variable-length {@link Long} using protobuf-style approach.
         */
        public static long readVarLong(DataInput in) throws IOException {
            int shift = 0;
            long result = 0;
            while (shift < 64) {
                byte b = in.readByte();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0)
                    return result;
                shift += 7;
            }
            throw new ProtocolException("malformed long");
        }

        /**
         * Write variable-length {@link Long} using protobuf-style approach.
         */
        public static void writeVarLong(DataOutput out, long value) throws IOException {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    out.writeByte((int) value);
                    return;
                } else {
                    out.writeByte(((int) value & 0x7F) | 0x80);
                    value >>>= 7;
                }
            }
        }

        public static long[] readVarLongArray(DataInput in) throws IOException {
            final int size = in.readInt();
            if (size == -1) return null;
            if (size < 0) throw new ProtocolException("negative array size");
            final long[] values = new long[size];
            for (int i = 0; i < values.length; i++) {
                values[i] = readVarLong(in);
            }
            return values;
        }

        public static void writeVarLongArray(DataOutput out, long[] values, int size)
                throws IOException {
            if (values == null) {
                out.writeInt(-1);
                return;
            }
            if (size > values.length) {
                throw new IllegalArgumentException("size larger than length");
            }
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                writeVarLong(out, values[i]);
            }
        }
    }

    /**
     * Utility methods for interacting with {@link Parcel} structures, mostly
     * dealing with writing partial arrays.
     */
    public static class ParcelUtils {
        public static long[] readLongArray(Parcel in) {
            final int size = in.readInt();
            if (size == -1) return null;
            final long[] values = new long[size];
            for (int i = 0; i < values.length; i++) {
                values[i] = in.readLong();
            }
            return values;
        }

        public static void writeLongArray(Parcel out, long[] values, int size) {
            if (values == null) {
                out.writeInt(-1);
                return;
            }
            if (size > values.length) {
                throw new IllegalArgumentException("size larger than length");
            }
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeLong(values[i]);
            }
        }
    }

}
