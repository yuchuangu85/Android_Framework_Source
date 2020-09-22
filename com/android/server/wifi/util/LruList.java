/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Utility class for keeping a reletively small List of values, sorted by most recently added
 * first.
 * @param <E>
 */
public class LruList<E> {
    private int mSize;
    private LinkedList<E> mLinkedList;

    /**
     * Creates a new LruList capped by maxSize.
     * @param maxSize max allowed size of the LruList
     */
    public LruList(int maxSize) {
        mSize = maxSize;
        mLinkedList = new LinkedList<E>();
    }

    /**
     * Add an entry. If the entry already exists then it will be moved to the front. Otherwise,
     * a new entry will be added.
     * If this operation makes the LruList exceed the max allowed size, then the least recently
     * added entry will be removed.
     * @param entry
     */
    public void add(@NonNull E entry) {
        if (entry == null) {
            return;
        }
        int index = mLinkedList.indexOf(entry);
        if (index >= 0) {
            mLinkedList.remove(index);
        }
        mLinkedList.addFirst(entry);
        while (mLinkedList.size() > mSize) {
            mLinkedList.removeLast();
        }
    }

    /**
     * Remove an entry from list.
     */
    public void remove(@NonNull E entry) {
        if (entry == null) {
            return;
        }
        int index = mLinkedList.indexOf(entry);
        if (index < 0) {
            return;
        }
        mLinkedList.remove(index);
    }

    /**
     * Returns the list of entries sorted by most recently added entries first.
     * @return
     */
    public @NonNull List<E> getEntries() {
        return new ArrayList<E>(mLinkedList);
    }

    /**
     * Gets the number of entries in this LruList.
     */
    public int size() {
        return mLinkedList.size();
    }

    /**
     * Get the index in the list of the input entry.
     * If not in the list will return -1.
     * If in the list, smaller index is more recently added.
     */
    public int indexOf(E entry) {
        return mLinkedList.indexOf(entry);
    }
}
