/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Shader.TileMode;

import libcore.util.NativeAllocationRegistry_Delegate;

/**
 * Delegate implementing the native methods of android.graphics.Shader
 *
 * Through the layoutlib_create tool, the original native methods of Shader have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Shader class.
 *
 * This also serve as a base class for all Shader delegate classes.
 *
 * @see DelegateManager
 *
 */
public abstract class Shader_Delegate {

    // ---- delegate manager ----
    protected static final DelegateManager<Shader_Delegate> sManager =
            new DelegateManager<Shader_Delegate>(Shader_Delegate.class);
    private static long sFinalizer = -1;

    // ---- delegate helper data ----

    // ---- delegate data ----
    private Matrix_Delegate mLocalMatrix = null;
    private float mAlpha = 1.0f;

    // ---- Public Helper methods ----

    public static Shader_Delegate getDelegate(long nativeShader) {
        return sManager.getDelegate(nativeShader);
    }

    /**
     * Returns the {@link TileMode} matching the given int.
     * @param tileMode the tile mode int value
     * @return the TileMode enum.
     */
    public static TileMode getTileMode(int tileMode) {
        for (TileMode tm : TileMode.values()) {
            if (tm.nativeInt == tileMode) {
                return tm;
            }
        }

        assert false;
        return TileMode.CLAMP;
    }

    public abstract java.awt.Paint getJavaPaint();
    public abstract boolean isSupported();
    public abstract String getSupportMessage();

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nativeGetFinalizer() {
        synchronized (Shader_Delegate.class) {
            if (sFinalizer == -1) {
                sFinalizer = NativeAllocationRegistry_Delegate.createFinalizer(
                        sManager::removeJavaReferenceFor);
            }
        }
        return sFinalizer;
    }

    // ---- Private delegate/helper methods ----

    protected Shader_Delegate(long nativeMatrix) {
        setLocalMatrix(nativeMatrix);
    }

    public void setLocalMatrix(long nativeMatrix) {
        mLocalMatrix = Matrix_Delegate.getDelegate(nativeMatrix);
    }

    protected java.awt.geom.AffineTransform getLocalMatrix() {
        if (mLocalMatrix != null) {
            return mLocalMatrix.getAffineTransform();
        }

        return new java.awt.geom.AffineTransform();
    }

    public void setAlpha(float alpha) {
        mAlpha = alpha;
    }

    public float getAlpha() {
        return mAlpha;
    }
}
