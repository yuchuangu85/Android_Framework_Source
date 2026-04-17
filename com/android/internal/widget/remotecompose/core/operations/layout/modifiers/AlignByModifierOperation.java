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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;
import com.android.internal.widget.remotecompose.core.serialize.SerializeTags;

import java.util.List;

/** Represents an align by modifier. */
public class AlignByModifierOperation extends DecoratorModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_ALIGN_BY;
    public static final String CLASS_NAME = "AlignByModifierOperation";

    private @Nullable Component mParent;

    private float mLine = RemoteContext.FIRST_BASELINE;
    private int mFlags = 0;

    public void setParent(@NonNull Component component) {
        mParent = component;
    }

    public AlignByModifierOperation(float line, int flags) {
        mLine = line;
        mFlags = flags;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mLine, mFlags);
    }

    /**
     * Serialize the string
     *
     * @param indent     padding to display
     * @param serializer append the string
     */
    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, "");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(@NonNull PaintContext context) {
    }

    @Override
    public String toString() {
        return "AlignByModifierOperation()";
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * Write the operation to the buffer
     *
     * @param buffer a WireBuffer
     */
    public static void apply(@NonNull WireBuffer buffer, float line, int flags) {
        buffer.start(OP_CODE);
        buffer.writeFloat(line);
        buffer.writeInt(flags);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer     the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float line = buffer.readFloat();
        int flags = buffer.readInt();
        operations.add(new AlignByModifierOperation(line, flags));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .addedVersion(7)
                .experimental(true)
                .description("Align a component based on a specific baseline or anchor")
                .field(DocumentedOperation.FLOAT, "line",
                        "The ID of the float variable or baseline ID to align by")
                .field(DocumentedOperation.INT, "flags", "Alignment flags");
    }

    @Override
    public void layout(
            @NonNull RemoteContext context,
            @NonNull Component component,
            float width,
            float height) {
    }

    @Override
    public void serialize(@NonNull MapSerializer serializer) {
        String value = "" + mLine;
        if (Float.isNaN(mLine)) {
            int id = Utils.idFromNan(mLine);
            switch (id) {
                case RemoteContext.ID_FIRST_BASELINE:
                    value = "FirstBaseline";
                    break;
                case RemoteContext.ID_LAST_BASELINE:
                    value = "LastBaseline";
                    break;
                default:
                    value = "Unknown";
            }
        }
        serializer
                .addTags(SerializeTags.MODIFIER)
                .addType("AlignByModifierOperation")
                .add("line", value);
    }

    /**
     * Returns the offset
     */
    public float getValue(@NonNull PaintContext context) {
        if (mParent == null) {
            return 0f;
        }
        return mParent.getAlignValue(context, mLine);
    }
}
