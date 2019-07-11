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

package android.system;

import junit.framework.TestCase;

public class OsConstantsTest extends TestCase {

    // http://b/15602893
    public void testBug15602893() {
        assertTrue(OsConstants.RT_SCOPE_HOST > 0);
        assertTrue(OsConstants.RT_SCOPE_LINK > 0);
        assertTrue(OsConstants.RT_SCOPE_SITE > 0);

        assertTrue(OsConstants.IFA_F_TENTATIVE > 0);
    }
}
