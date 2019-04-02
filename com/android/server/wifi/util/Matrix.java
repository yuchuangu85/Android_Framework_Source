/*
 * Copyright (C) 2017 The Android Open Source Project
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

/**
 * Utility for doing basic matix calculations
 */
public class Matrix {
    public final int n;
    public final int m;
    public final double[] mem;

    /**
     * Creates a new matrix, initialized to zeros
     *
     * @param rows - number of rows (n)
     * @param cols - number of columns (m)
     */
    public Matrix(int rows, int cols) {
        n = rows;
        m = cols;
        mem = new double[rows * cols];
    }

    /**
     * Creates a new matrix using the provided array of values
     * <p>
     * Values are in row-major order.
     *
     * @param stride is the number of columns.
     * @param values is the array of values.
     * @throws IllegalArgumentException if length of values array not a multiple of stride
     */
    public Matrix(int stride, double[] values) {
        n = (values.length + stride - 1) / stride;
        m = stride;
        mem = values;
        if (mem.length != n * m) throw new IllegalArgumentException();
    }

    /**
     * Creates a new matrix duplicating the given one
     *
     * @param that is the source Matrix.
     */
    public Matrix(Matrix that) {
        n = that.n;
        m = that.m;
        mem = new double[that.mem.length];
        for (int i = 0; i < mem.length; i++) {
            mem[i] = that.mem[i];
        }
    }

    /**
     * Gets the matrix coefficient from row i, column j
     *
     * @param i row number
     * @param j column number
     * @return Coefficient at i,j
     * @throws IndexOutOfBoundsException if an index is out of bounds
     */
    public double get(int i, int j) {
        if (!(0 <= i && i < n && 0 <= j && j < m)) throw new IndexOutOfBoundsException();
        return mem[i * m + j];
    }

    /**
     * Store a matrix coefficient in row i, column j
     *
     * @param i row number
     * @param j column number
     * @param v Coefficient to store at i,j
     * @throws IndexOutOfBoundsException if an index is out of bounds
     */
    public void put(int i, int j, double v) {
        if (!(0 <= i && i < n && 0 <= j && j < m)) throw new IndexOutOfBoundsException();
        mem[i * m + j] = v;
    }

    /**
     * Forms the sum of two matrices, this and that
     *
     * @param that is the other matrix
     * @return newly allocated matrix representing the sum of this and that
     * @throws IllegalArgumentException if shapes differ
     */
    public Matrix plus(Matrix that) {
        return plus(that, new Matrix(n, m));

    }

    /**
     * Forms the sum of two matrices, this and that
     *
     * @param that   is the other matrix
     * @param result is space to hold the result
     * @return result, filled with the matrix sum
     * @throws IllegalArgumentException if shapes differ
     */
    public Matrix plus(Matrix that, Matrix result) {
        if (!(this.n == that.n && this.m == that.m && this.n == result.n && this.m == result.m)) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < mem.length; i++) {
            result.mem[i] = this.mem[i] + that.mem[i];
        }
        return result;
    }

    /**
     * Forms the difference of two matrices, this and that
     *
     * @param that is the other matrix
     * @return newly allocated matrix representing the difference of this and that
     * @throws IllegalArgumentException if shapes differ
     */
    public Matrix minus(Matrix that) {
        return minus(that, new Matrix(n, m));
    }

    /**
     * Forms the difference of two matrices, this and that
     *
     * @param that   is the other matrix
     * @param result is space to hold the result
     * @return result, filled with the matrix difference
     * @throws IllegalArgumentException if shapes differ
     */
    public Matrix minus(Matrix that, Matrix result) {
        if (!(this.n == that.n && this.m == that.m && this.n == result.n && this.m == result.m)) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < mem.length; i++) {
            result.mem[i] = this.mem[i] - that.mem[i];
        }
        return result;
    }

    /**
     * Forms the matrix product of two matrices, this and that
     *
     * @param that is the other matrix
     * @return newly allocated matrix representing the matrix product of this and that
     * @throws IllegalArgumentException if shapes are not conformant
     */
    public Matrix dot(Matrix that) {
        return dot(that, new Matrix(this.n, that.m));
    }

    /**
     * Forms the matrix product of two matrices, this and that
     * <p>
     * Caller supplies an object to contain the result, as well as scratch space
     *
     * @param that   is the other matrix
     * @param result is space to hold the result
     * @return result, filled with the matrix product
     * @throws IllegalArgumentException if shapes are not conformant
     */
    public Matrix dot(Matrix that, Matrix result) {
        if (!(this.n == result.n && this.m == that.n && that.m == result.m)) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < that.m; j++) {
                double s = 0.0;
                for (int k = 0; k < m; k++) {
                    s += this.get(i, k) * that.get(k, j);
                }
                result.put(i, j, s);
            }
        }
        return result;
    }

    /**
     * Forms the matrix transpose
     *
     * @return newly allocated transpose matrix
     */
    public Matrix transpose() {
        return transpose(new Matrix(m, n));
    }

    /**
     * Forms the matrix transpose
     * <p>
     * Caller supplies an object to contain the result
     *
     * @param result is space to hold the result
     * @return result, filled with the matrix transpose
     * @throws IllegalArgumentException if result shape is wrong
     */
    public Matrix transpose(Matrix result) {
        if (!(this.n == result.m && this.m == result.n)) throw new IllegalArgumentException();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                result.put(j, i, get(i, j));
            }
        }
        return result;
    }

    /**
     * Forms the inverse of a square matrix
     *
     * @return newly allocated matrix representing the matrix inverse
     * @throws ArithmeticException if the matrix is not invertible
     */
    public Matrix inverse() {
        return inverse(new Matrix(n, m), new Matrix(n, 2 * m));
    }

    /**
     * Forms the inverse of a square matrix
     *
     * @param result  is space to hold the result
     * @param scratch is workspace of dimension n by 2*n
     * @return result, filled with the matrix inverse
     * @throws ArithmeticException if the matrix is not invertible
     * @throws IllegalArgumentException if shape of scratch or result is wrong
     */
    public Matrix inverse(Matrix result, Matrix scratch) {
        if (!(n == m && n == result.n && m == result.m && n == scratch.n && 2 * m == scratch.m)) {
            throw new IllegalArgumentException();
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                scratch.put(i, j, get(i, j));
                scratch.put(i, m + j, i == j ? 1.0 : 0.0);
            }
        }

        for (int i = 0; i < n; i++) {
            int ibest = i;
            double vbest = Math.abs(scratch.get(ibest, ibest));
            for (int ii = i + 1; ii < n; ii++) {
                double v = Math.abs(scratch.get(ii, i));
                if (v > vbest) {
                    ibest = ii;
                    vbest = v;
                }
            }
            if (ibest != i) {
                for (int j = 0; j < scratch.m; j++) {
                    double t = scratch.get(i, j);
                    scratch.put(i, j, scratch.get(ibest, j));
                    scratch.put(ibest, j, t);
                }
            }
            double d = scratch.get(i, i);
            if (d == 0.0) throw new ArithmeticException("Singular matrix");
            for (int j = 0; j < scratch.m; j++) {
                scratch.put(i, j, scratch.get(i, j) / d);
            }
            for (int ii = i + 1; ii < n; ii++) {
                d = scratch.get(ii, i);
                for (int j = 0; j < scratch.m; j++) {
                    scratch.put(ii, j, scratch.get(ii, j) - d * scratch.get(i, j));
                }
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            for (int ii = 0; ii < i; ii++) {
                double d = scratch.get(ii, i);
                for (int j = 0; j < scratch.m; j++) {
                    scratch.put(ii, j, scratch.get(ii, j) - d * scratch.get(i, j));
                }
            }
        }
        for (int i = 0; i < result.n; i++) {
            for (int j = 0; j < result.m; j++) {
                result.put(i, j, scratch.get(i, m + j));
            }
        }
        return result;
    }

    /**
     * Tests for equality
     */
    @Override
    public boolean equals(Object that) {
        if (this == that) return true;
        if (!(that instanceof Matrix)) return false;
        Matrix other = (Matrix) that;
        if (n != other.n) return false;
        if (m != other.m) return false;
        for (int i = 0; i < mem.length; i++) {
            if (mem[i] != other.mem[i]) return false;
        }
        return true;
    }

    /**
     * Calculates a hash code
     */
    @Override
    public int hashCode() {
        int h = n * 101 + m;
        for (int i = 0; i < mem.length; i++) {
            h = h * 37 + Double.hashCode(mem[i]);
        }
        return h;
    }

    /**
     * Makes a string representation
     *
     * @return string like "[a, b; c, d]"
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(n * m * 8);
        sb.append("[");
        for (int i = 0; i < mem.length; i++) {
            if (i > 0) sb.append(i % m == 0 ? "; " : ", ");
            sb.append(mem[i]);
        }
        sb.append("]");
        return sb.toString();
    }

}
