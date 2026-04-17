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
package com.android.internal.widget.remotecompose.core.operations.matrix;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.MatrixAccess;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.utilities.Matrix;
import com.android.internal.widget.remotecompose.core.operations.utilities.MatrixOperations;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;
import com.android.internal.widget.remotecompose.core.serialize.Serializable;

import java.util.Arrays;
import java.util.List;

/** This is a matrix that is formed by an expression */
public class MatrixExpression extends Operation
        implements VariableSupport, MatrixAccess, Serializable {
    private static final int OP_CODE = Operations.MATRIX_EXPRESSION;
    private static final String CLASS_NAME = "MatrixExpression";
    private int mMatrixId;
    private int mType;
    private @NonNull float [] mValues = new float[16];
    private final @NonNull float [] mExpression;
    private @Nullable float [] mOutExpression;
    MatrixOperations mMatrixOperations = new MatrixOperations();

    public MatrixExpression(int matrixId, int type, @NonNull float [] expression) {
        this.mMatrixId = matrixId;
        this.mType = type;
        this.mExpression = expression;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mOutExpression == null || mOutExpression.length != mExpression.length) {
            mOutExpression = new float[mExpression.length];
        }
        for (int i = 0; i < mExpression.length; i++) {
            float v = mExpression[i];
            if (Float.isNaN(v) && !MatrixOperations.isOperator(v)) {

                float newValue = context.getFloat(Utils.idFromNan(v));
                mOutExpression[i] = newValue;
            } else {
                mOutExpression[i] = mExpression[i];
            }
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        for (int i = 0; i < mExpression.length; i++) {
            float v = mExpression[i];
            if (Float.isNaN(v) && !MatrixOperations.isOperator(v)) {
                context.listensTo(Utils.idFromNan(v), this);
            }
        }
    }

    /**
     * Copy the value from another operation
     *
     * @param from value to copy from
     */
    public void update(@NonNull MatrixExpression from) {
        mValues = from.mValues;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mMatrixId, mType, mValues);
    }

    @NonNull
    @Override
    public String toString() {
        return "MatrixExpression[" + mMatrixId + "] = "
                + MatrixOperations.toString(mExpression, null) + "-> "
                + Arrays.toString(mValues);
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
     * Writes out the operation to the buffer
     *
     * @param buffer   write command to this buffer
     * @param matrixId the id
     * @param type     the type of matrix it is
     * @param values   the value of the float
     */
    public static void apply(
            @NonNull WireBuffer buffer, int matrixId, int type, @NonNull float [] values) {
        buffer.start(OP_CODE);
        buffer.writeInt(matrixId);
        buffer.writeInt(type);
        buffer.writeInt(values.length);
        for (int i = 0; i < values.length; i++) {
            buffer.writeFloat(values[i]);
        }
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer     the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int type = buffer.readInt();
        int len = buffer.readInt();
        if (len > 32 || len < 0) {
            throw new IllegalArgumentException("Invalid Length " + len + " corrupt buffer");
        }
        float[] exp = new float[len];
        for (int i = 0; i < exp.length; i++) {
            exp[i] = buffer.readFloat();
        }

        operations.add(new MatrixExpression(id, type, exp));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Matrix Operations", OP_CODE, CLASS_NAME)
                .addedVersion(7)
                .description("A matrix defined by an expression")
                .field(DocumentedOperation.INT, "matrixId", "The ID of the matrix")
                .field(DocumentedOperation.INT, "type", "The type of matrix")
                .field(DocumentedOperation.FLOAT_ARRAY, "expression", "The matrix expression");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        Matrix m = mMatrixOperations.eval(mOutExpression);
        m.putValues(mValues);
        context.putObject(mMatrixId, this);
        context.loadFloat(mMatrixId, System.nanoTime() * 1E9f);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void serialize(@NonNull MapSerializer serializer) {
        serializer
                .addType(CLASS_NAME)
                .add("matrix", mMatrixId)
                .add("type", mType)
                .addFloatExpressionSrc("value", mValues);
    }

    @Override
    public @NonNull float [] get() {
        return mValues;
    }
}
