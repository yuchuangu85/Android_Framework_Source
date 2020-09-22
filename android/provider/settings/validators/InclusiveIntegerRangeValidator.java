/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider.settings.validators;

import android.annotation.Nullable;

/**
 * Validate an integer value lies within a given (boundary inclusive) range.
 *
 * @hide
 */
final class InclusiveIntegerRangeValidator implements Validator {
    private final int mMin;
    private final int mMax;

    InclusiveIntegerRangeValidator(int min, int max) {
        mMin = min;
        mMax = max;
    }

    @Override
    public boolean validate(@Nullable String value) {
        try {
            final int intValue = Integer.parseInt(value);
            return intValue >= mMin && intValue <= mMax;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
