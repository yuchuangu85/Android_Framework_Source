/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app.admin.metadata;

import android.annotation.NonNull;
import android.annotation.TestApi;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract class to identify a resolution mechanism that is used to resolve the enforced policy
 * when being set by multiple admins.
 *
 * @hide
 */
public abstract class ResolutionMechanismMetadata<T> {

    public static final class MostRestrictive<T> extends ResolutionMechanismMetadata<T> {
        private final List<T> mMostToLeastRestrictive;

        public MostRestrictive(@NonNull List<T> mostToLeastRestrictive) {
            mMostToLeastRestrictive = new ArrayList<>(mostToLeastRestrictive);
        }

        public MostRestrictive() {
            mMostToLeastRestrictive = new ArrayList<>();
        }

        @NonNull
        public List<T> getMostToLeastRestrictiveValues() {
            return mMostToLeastRestrictive;
        }

        @Override
        public String toString() {
            return "MostRestrictive { mMostToLeastRestrictive= " + mMostToLeastRestrictive + " }";
        }
    }
}
