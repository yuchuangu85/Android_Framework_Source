/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.webview.chromium;

import android.view.View;
import android.graphics.Canvas;
import android.util.Log;

import com.android.webview.chromium.WebViewDelegateFactory.WebViewDelegate;

import org.chromium.content.common.CleanupReference;

// Simple Java abstraction and wrapper for the native DrawGLFunctor flow.
// An instance of this class can be constructed, bound to a single view context (i.e. AwContennts)
// and then drawn and detached from the view tree any number of times (using requestDrawGL and
// detach respectively). Then when finished with, it can be explicitly released by calling
// destroy() or will clean itself up as required via finalizer / CleanupReference.
class DrawGLFunctor {

    private static final String TAG = DrawGLFunctor.class.getSimpleName();

    // Pointer to native side instance
    private CleanupReference mCleanupReference;
    private DestroyRunnable mDestroyRunnable;
    private WebViewDelegate mWebViewDelegate;

    public DrawGLFunctor(long viewContext, WebViewDelegate webViewDelegate) {
        mDestroyRunnable = new DestroyRunnable(nativeCreateGLFunctor(viewContext), webViewDelegate);
        mCleanupReference = new CleanupReference(this, mDestroyRunnable);
        mWebViewDelegate = webViewDelegate;
    }

    public void destroy() {
        detach();
        if (mCleanupReference != null) {
            mCleanupReference.cleanupNow();
            mCleanupReference = null;
            mDestroyRunnable = null;
            mWebViewDelegate = null;
        }
    }

    public void detach() {
        mDestroyRunnable.detachNativeFunctor();
    }

    public boolean requestDrawGL(Canvas canvas, View containerView,
            boolean waitForCompletion) {
        if (mDestroyRunnable.mNativeDrawGLFunctor == 0) {
            throw new RuntimeException("requested DrawGL on already destroyed DrawGLFunctor");
        }

        if (!mWebViewDelegate.canInvokeDrawGlFunctor(containerView)) {
            return false;
        }

        mDestroyRunnable.mContainerView = containerView;

        if (canvas == null) {
            mWebViewDelegate.invokeDrawGlFunctor(containerView,
                    mDestroyRunnable.mNativeDrawGLFunctor, waitForCompletion);
            return true;
        }

        mWebViewDelegate.callDrawGlFunction(canvas, mDestroyRunnable.mNativeDrawGLFunctor);
        if (waitForCompletion) {
            mWebViewDelegate.invokeDrawGlFunctor(containerView,
                    mDestroyRunnable.mNativeDrawGLFunctor, waitForCompletion);
        }
        return true;
    }

    public static void setChromiumAwDrawGLFunction(long functionPointer) {
        nativeSetChromiumAwDrawGLFunction(functionPointer);
    }

    // Holds the core resources of the class, everything required to correctly cleanup.
    // IMPORTANT: this class must not hold any reference back to the outer DrawGLFunctor
    // instance, as that will defeat GC of that object.
    private static final class DestroyRunnable implements Runnable {
        private WebViewDelegate mWebViewDelegate;
        View mContainerView;
        long mNativeDrawGLFunctor;
        DestroyRunnable(long nativeDrawGLFunctor, WebViewDelegate webViewDelegate) {
            mNativeDrawGLFunctor = nativeDrawGLFunctor;
            mWebViewDelegate = webViewDelegate;
        }

        // Called when the outer DrawGLFunctor instance has been GC'ed, i.e this is its finalizer.
        @Override
        public void run() {
            detachNativeFunctor();
            nativeDestroyGLFunctor(mNativeDrawGLFunctor);
            mNativeDrawGLFunctor = 0;
        }

        void detachNativeFunctor() {
            if (mNativeDrawGLFunctor != 0 && mContainerView != null
                    && mWebViewDelegate != null) {
                mWebViewDelegate.detachDrawGlFunctor(mContainerView, mNativeDrawGLFunctor);
            }
            mContainerView = null;
            mWebViewDelegate = null;
        }
    }

    private static native long nativeCreateGLFunctor(long viewContext);
    private static native void nativeDestroyGLFunctor(long functor);
    private static native void nativeSetChromiumAwDrawGLFunction(long functionPointer);
}
