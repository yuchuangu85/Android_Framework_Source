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

package android.test;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;

import junit.framework.TestCase;

/**
 * @deprecated Stub only
 */
@SuppressWarnings({ "unchecked", "deprecation", "all" })
@Deprecated
public class AndroidTestCase extends TestCase {

    /**
     * Stub only
     */
    @UnsupportedAppUsage
    public void setTestContext(Context context) {
        throw new RuntimeException("Stub!");
    }

    /**
     * Stub only
     */
    @UnsupportedAppUsage
    public Context getTestContext() {
        throw new RuntimeException("Stub!");
    }
}
