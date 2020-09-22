/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.WorkSource;
import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for general helper methods for WorkSource operations.
 */
public class WorkSourceUtil {

    /**
     * Retrieve an array of uids & tags within the worksource.
     */
    public static Pair<int[], String[]> getUidsAndTagsForWs(WorkSource ws) {
        List<Integer> uids = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        for (int i = 0; i < ws.size(); i++) {
            uids.add(ws.getUid(i));
            tags.add(ws.getPackageName(i));
        }

        final List<WorkSource.WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkSource.WorkChain workChain = workChains.get(i);
                uids.add(workChain.getAttributionUid());
                tags.add(workChain.getAttributionTag());
            }
        }
        return Pair.create(
                uids.stream().mapToInt(Integer::intValue).toArray(),
                tags.toArray(new String[0]));
    }


}
