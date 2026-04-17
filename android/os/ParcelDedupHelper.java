/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.Nullable;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A helper that deduplicates specific object types written to a Parcel to reduce kernel overhead
 * and payload size.
 *
 * <p>Use the {@link Builder} to configure which types (Binders, String8, String16) should be
 * deduplicated.
 *
 * <p>Note: A new instance of {@link ParcelDedupHelper} must be used for each Parcel.
 *
 * @hide
 */
public final class ParcelDedupHelper extends Parcel.ReadWriteHelper {

    @Nullable private final DedupCache<IBinder> mBinderCache;
    @Nullable private final DedupCache<String> mString8Cache;
    @Nullable private final DedupCache<String> mString16Cache;

    private ParcelDedupHelper(boolean dedupBinders, boolean dedupString8, boolean dedupString16) {
        mBinderCache =
                dedupBinders
                        ? new DedupCache<>(super::writeStrongBinder, super::readStrongBinder)
                        : null;
        mString8Cache =
                dedupString8 ? new DedupCache<>(super::writeString8, super::readString8) : null;
        mString16Cache =
                dedupString16 ? new DedupCache<>(super::writeString16, super::readString16) : null;
    }

    @Override
    public void writeStrongBinder(Parcel p, IBinder val) {
        if (mBinderCache != null) {
            mBinderCache.write(p, val);
        } else {
            super.writeStrongBinder(p, val);
        }
    }

    @Override
    public IBinder readStrongBinder(Parcel p) {
        if (mBinderCache != null) {
            return mBinderCache.read(p);
        } else {
            return super.readStrongBinder(p);
        }
    }

    @Override
    public void writeString8(Parcel p, String val) {
        if (mString8Cache != null) {
            mString8Cache.write(p, val);
        } else {
            super.writeString8(p, val);
        }
    }

    @Override
    public String readString8(Parcel p) {
        if (mString8Cache != null) {
            return mString8Cache.read(p);
        } else {
            return super.readString8(p);
        }
    }

    @Override
    public void writeString16(Parcel p, String val) {
        if (mString16Cache != null) {
            mString16Cache.write(p, val);
        } else {
            super.writeString16(p, val);
        }
    }

    @Override
    public String readString16(Parcel p) {
        if (mString16Cache != null) {
            return mString16Cache.read(p);
        } else {
            return super.readString16(p);
        }
    }

    /** A cache for deduplicating a specific type. */
    private static final class DedupCache<T> {
        private final BiConsumer<Parcel, T> mWriter;
        private final Function<Parcel, T> mReader;

        private final ArrayMap<T, Integer> mWritten = new ArrayMap<>();
        private final ArrayList<T> mRead = new ArrayList<>();

        private static final int NEW_ENTRY_INDEX = -1;

        /**
         * Creates a new {@link DedupCache}.
         *
         * @param writer the function to write the value to the parcel
         * @param reader the function to read the value from the parcel
         */
        DedupCache(BiConsumer<Parcel, T> writer, Function<Parcel, T> reader) {
            mWriter = writer;
            mReader = reader;
        }

        void write(Parcel p, T val) {
            Integer cachedIndex = mWritten.get(val);
            if (cachedIndex != null) {
                p.writeInt(cachedIndex);
            } else {
                p.writeInt(NEW_ENTRY_INDEX);
                mWritten.put(val, mWritten.size());
                mWriter.accept(p, val);
            }
        }

        T read(Parcel p) {
            int index = p.readInt();
            if (index == NEW_ENTRY_INDEX) {
                T val = mReader.apply(p);
                mRead.add(val);
                return val;
            } else {
                if (index < 0 || index >= mRead.size()) {
                    throw new IllegalStateException("Corrupt Parcel: Bad dedup index " + index);
                }
                return mRead.get(index);
            }
        }
    }

    /** A builder for {@link ParcelDedupHelper}. All options default to false. */
    public static final class Builder {
        private boolean mDedupBinders = false;
        private boolean mDedupString8 = false;
        private boolean mDedupString16 = false;

        public Builder dedupBinders(boolean enabled) {
            mDedupBinders = enabled;
            return this;
        }

        public Builder dedupString8(boolean enabled) {
            mDedupString8 = enabled;
            return this;
        }

        public Builder dedupString16(boolean enabled) {
            mDedupString16 = enabled;
            return this;
        }

        public ParcelDedupHelper build() {
            return new ParcelDedupHelper(mDedupBinders, mDedupString8, mDedupString16);
        }
    }
}
