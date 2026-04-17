/*
 * Copyright (C) 2025 The Android Open Source Project
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

import libcore.util.Objects;

/**
 * Corresponds to C's {@code struct Dl_info}.
 */
public final class StructDlInfo {
    /** Pathname of shared object that contains address */
    public final String dli_fname;
    /** Base address at which shared object is loaded */
    public final long dli_fbase;
    /** Name of symbol whose definition overlaps addr */
    public final String dli_sname;
    /** Exact address of symbol named in dli_sname */
    public final long dli_saddr;

    public StructDlInfo(String dli_fname, long dli_fbase, String dli_sname, long dli_saddr) {
        this.dli_fname = dli_fname;
        this.dli_fbase = dli_fbase;
        this.dli_sname = dli_sname;
        this.dli_saddr = dli_saddr;
    }

    @Override
    public String toString() {
        return Objects.toString(this);
    }
}
