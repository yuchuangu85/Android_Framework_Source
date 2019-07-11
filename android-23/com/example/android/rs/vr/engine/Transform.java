/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.example.android.rs.vr.engine;

import android.util.Log;

import java.text.DecimalFormat;

/**
 * code to manage transformations between world screen and volume space.
 */
public class Transform {
    public final static char WORLD_SPACE = 0;
    public final static char SCREEN_SPACE = 1;
    public final static char VOLUME_SPACE = 2;
    private static final String LOGTAG = "Transform";

    Matrix[][] mAllMat = new Matrix[3][3];
    public ViewMatrix mViewMatrix = new ViewMatrix();
    float[] mVoxelDim = new float[3];

    public void clone(Transform src) {
        System.arraycopy(src.mVoxelDim, 0, mVoxelDim, 0, mVoxelDim.length);
        mViewMatrix.clone(src.mViewMatrix);
        updateAllMatrix();
    }

    public void updateAllMatrix() {
        mViewMatrix.calcMatrix();
        Matrix m = new Matrix();
        m.setToUnit();
        mAllMat[0][0] = m;
        mAllMat[1][1] = m;
        mAllMat[2][2] = m;
        mAllMat[SCREEN_SPACE][WORLD_SPACE] = new Matrix(mViewMatrix);
        mAllMat[WORLD_SPACE][SCREEN_SPACE] = mViewMatrix.invers();
        m = new Matrix();
        m.setToUnit();
        if (mVoxelDim[0] > 0) {
            int min = 0;

            m.m[0] = 1 / mVoxelDim[0];
            m.m[5] = 1 / mVoxelDim[1];
            m.m[10] = 1 / mVoxelDim[2];
        }
        mAllMat[WORLD_SPACE][VOLUME_SPACE] = m;
        mAllMat[VOLUME_SPACE][WORLD_SPACE] = m.invers();
        mAllMat[SCREEN_SPACE][VOLUME_SPACE] = m = m.premult(mViewMatrix);
        mAllMat[VOLUME_SPACE][SCREEN_SPACE] = m.invers();

    }

    public void setVoxelDim(float[] volDim) {
        mVoxelDim[0] = volDim[0];
        mVoxelDim[1] = volDim[1];
        mVoxelDim[2] = volDim[2];
    }

    public Matrix getMatrix(char from, char to) {
        return mAllMat[from][to];
    }

    public void setScreenDim(int x, int y) {
        mViewMatrix.setScreenDim(x, y);
        updateAllMatrix();
    }

    public double[] getLookPoint() {
        return mViewMatrix.getLookPoint();
    }

    public void setLookPoint(double[] mLookPoint) {
        mViewMatrix.setLookPoint(mLookPoint);
        updateAllMatrix();
    }

    public double[] getEyePoint() {
        return mViewMatrix.getEyePoint();
    }

    public void setEyePoint(double[] mEyePoint) {
        mViewMatrix.setEyePoint(mEyePoint);
        updateAllMatrix();
    }

    public double[] getUpVector() {
        return mViewMatrix.getUpVector();
    }

    public void setUpVector(double[] mUpVector) {
        mViewMatrix.setUpVector(mUpVector);
        updateAllMatrix();
    }

    public double getScreenWidth() {
        return mViewMatrix.getScreenWidth();
    }

    public void setScreenWidth(double screenWidth) {
        mViewMatrix.setScreenWidth(screenWidth);
        updateAllMatrix();
    }

    public void lookAt(TriData tri, int w, int h) {
        mViewMatrix.lookAt(tri, mVoxelDim, w, h);
        updateAllMatrix();
    }

    public void look(char dir, TriData tri, int w, int h) {
        mViewMatrix.look(dir, tri, mVoxelDim, w, h);
        updateAllMatrix();
    }

    public void trackBallUp(float x, float y) {
        mViewMatrix.trackBallUP(x, y);
        updateAllMatrix();
    }

    public void trackBallDown(float x, float y) {
        mViewMatrix.trackBallDown(x, y);
    }

    public void trackBallMove(float x, float y) {
        mViewMatrix.trackBallMove(x, y);
        updateAllMatrix();
    }

    static DecimalFormat df = new DecimalFormat("      ##0.000");

    private static String trim(double d) {
        String s = df.format(d);
        return s.substring(s.length() - 6);
    }

    public static void print(float[] d) {
        String s = "";
        for (int i = 0; i < d.length; i++) {
            s += (((i == 0) ? "[ " : " , ") + trim(d[i]));
        }
        Log.v(LOGTAG, s + "]");
    }

    public static void main(String[] args) {
        int[] voldim = {50, 50, 100};
        double[] mEyePoint = {voldim[0] / 2., -voldim[1] / 2., voldim[2] / 2.};
        double[] mLookPoint = {voldim[0] / 2., voldim[1] / 2., voldim[2] / 2.};
        double[] mUpVector = {0., 0., 1.};

        Transform t = new Transform();
        t.mVoxelDim[0] = 1;
        t.setEyePoint(mEyePoint);
        t.setLookPoint(mLookPoint);
        t.setUpVector(mUpVector);
        t.setScreenDim(256, 256);
        t.setScreenWidth(128);
        t.updateAllMatrix();

        Matrix m = t.getMatrix(SCREEN_SPACE, VOLUME_SPACE);
        float[] orig = {.5f, .5f, 0};
        float[] ret = new float[3];

        m.mult3(orig, ret);
        print(ret);
        float[] look = {0, 0, 1};
        m.mult3v(look, ret);
        print(ret);
        float[] up = {1, 0, 0};
        m.mult3v(up, ret);
        print(ret);
        float[] right = {0, 1, 0};
        m.mult3v(right, ret);
        print(ret);

    }

    public void print() {
        Log.v(LOGTAG, "==== =========== VIEW ========== ======");

        mViewMatrix.print();
        Log.v(LOGTAG, "==== SCREEN_SPACE to WORLD_SPACE ======");
        mAllMat[SCREEN_SPACE][WORLD_SPACE].print();
        Log.v(LOGTAG, "==== SCREEN_SPACE to VOLUME_SPACE ======");
        mAllMat[SCREEN_SPACE][VOLUME_SPACE].print();
        Log.v(LOGTAG, "==== WORLD_SPACE to VOLUME_SPACE ======");
        mAllMat[WORLD_SPACE][VOLUME_SPACE].print();
        Log.v(LOGTAG, "==== WORLD_SPACE to SCREEN_SPACE ======");
        mAllMat[WORLD_SPACE][SCREEN_SPACE].print();
        Log.v(LOGTAG, "==== VOLUME_SPACE to SCREEN_SPACE ======");
        mAllMat[VOLUME_SPACE][SCREEN_SPACE].print();
        Log.v(LOGTAG, "==== VOLUME_SPACE to WORLD_SPACE ======");
        mAllMat[VOLUME_SPACE][WORLD_SPACE].print();
        Log.v(LOGTAG, "=======================================");
    }
}