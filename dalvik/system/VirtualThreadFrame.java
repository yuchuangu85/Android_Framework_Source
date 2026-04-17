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
package dalvik.system;

/**
 * @hide
 */
public final class VirtualThreadFrame {

    /** art::ShadowFrame */
    public byte[] frame;
    /**
     * Objects referenced in the frame. Stored separately from the frame in order to be traced
     * by the GC properly.
     */
    public Object[] refs;

    /** The declaring class of the invoked method.  */
    public Class<?> declaringClass;

    // private because an instance is created by ART.
    private VirtualThreadFrame() {}
}
