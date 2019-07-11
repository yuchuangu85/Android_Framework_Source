/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright (C) 2008 The Android Open Source Project
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

package java.lang.reflect;

import com.android.dex.Dex;

/**
 * @hide
 */
public final class ArtField {

    private Class<?> declaringClass;
    /** Field access flags (modifiers) */
    private int accessFlags;
    /** Index into DexFile's field ids */
    private int fieldDexIndex;
    /** Offset of field in object or class */
    private int offset;

    /**
     * Only created by art directly.
     */
    private ArtField() {}

    public int getAccessFlags() {
        return accessFlags;
    }

    int getDexFieldIndex() {
        return fieldDexIndex;
    }

    int getOffset() {
        return offset;
    }

    public String getName() {
        if (fieldDexIndex == -1) {
            // Proxy classes have 1 synthesized static field with no valid dex index
            if (!declaringClass.isProxy()) {
                throw new AssertionError();
            }
            return "throws";
        }
        Dex dex = declaringClass.getDex();
        int nameIndex = dex.nameIndexFromFieldIndex(fieldDexIndex);
        return declaringClass.getDexCacheString(dex, nameIndex);
    }

    Class<?> getDeclaringClass() {
        return declaringClass;
    }

    Class<?> getType() {
        if (fieldDexIndex == -1) {
            // The type of the synthesized field in a Proxy class is Class[][]
            if (!declaringClass.isProxy()) {
                throw new AssertionError();
            }
            return Class[][].class;
        }
        Dex dex = declaringClass.getDex();
        int typeIndex = dex.typeIndexFromFieldIndex(fieldDexIndex);
        return declaringClass.getDexCacheType(dex, typeIndex);
    }
}
