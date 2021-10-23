/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.accessibility.IAccessibilityEmbeddedConnection;

import java.util.Objects;

/**
 * Utility class for adding a View hierarchy to a {@link SurfaceControl}. The View hierarchy
 * will render in to a root SurfaceControl, and receive input based on the SurfaceControl's
 * placement on-screen. The primary usage of this class is to embed a View hierarchy from
 * one process in to another. After the SurfaceControlViewHost has been set up in the embedded
 * content provider, we can send the {@link SurfaceControlViewHost.SurfacePackage}
 * to the host process. The host process can then attach the hierarchy to a SurfaceView within
 * its own by calling
 * {@link SurfaceView#setChildSurfacePackage}.
 */
public class SurfaceControlViewHost {
    private final ViewRootImpl mViewRoot;
    private WindowlessWindowManager mWm;

    private SurfaceControl mSurfaceControl;
    private IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;

    /**
     * Package encapsulating a Surface hierarchy which contains interactive view
     * elements. It's expected to get this object from
     * {@link SurfaceControlViewHost#getSurfacePackage} afterwards it can be embedded within
     * a SurfaceView by calling {@link SurfaceView#setChildSurfacePackage}.
     *
     * Note that each {@link SurfacePackage} must be released by calling
     * {@link SurfacePackage#release}. However, if you use the recommended flow,
     *  the framework will automatically handle the lifetime for you.
     *
     * 1. When sending the package to the remote process, return it from an AIDL method
     * or manually use FLAG_WRITE_RETURN_VALUE in writeToParcel. This will automatically
     * release the package in the local process.
     * 2. In the remote process, consume the package using SurfaceView. This way the
     * SurfaceView will take over the lifetime and call {@link SurfacePackage#release}
     * for the user.
     *
     * One final note: The {@link SurfacePackage} lifetime is totally de-coupled
     * from the lifetime of the underlying {@link SurfaceControlViewHost}. Regardless
     * of the lifetime of the package the user should still call
     * {@link SurfaceControlViewHost#release} when finished.
     */
    public static final class SurfacePackage implements Parcelable {
        private SurfaceControl mSurfaceControl;
        private final IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;
        private final IBinder mInputToken;

        SurfacePackage(SurfaceControl sc, IAccessibilityEmbeddedConnection connection,
                       IBinder inputToken) {
            mSurfaceControl = sc;
            mAccessibilityEmbeddedConnection = connection;
            mInputToken = inputToken;
        }

        /**
         * Constructs a copy of {@code SurfacePackage} with an independent lifetime.
         *
         * The caller can use this to create an independent copy in situations where ownership of
         * the {@code SurfacePackage} would be transferred elsewhere, such as attaching to a
         * {@code SurfaceView}, returning as {@code Binder} result value, etc. The caller is
         * responsible for releasing this copy when its done.
         *
         * @param other {@code SurfacePackage} to create a copy of.
         */
        public SurfacePackage(@NonNull SurfacePackage other) {
            SurfaceControl otherSurfaceControl = other.mSurfaceControl;
            if (otherSurfaceControl != null && otherSurfaceControl.isValid()) {
                mSurfaceControl = new SurfaceControl();
                mSurfaceControl.copyFrom(otherSurfaceControl, "SurfacePackage");
            }
            mAccessibilityEmbeddedConnection = other.mAccessibilityEmbeddedConnection;
            mInputToken = other.mInputToken;
        }

        private SurfacePackage(Parcel in) {
            mSurfaceControl = new SurfaceControl();
            mSurfaceControl.readFromParcel(in);
            mAccessibilityEmbeddedConnection = IAccessibilityEmbeddedConnection.Stub.asInterface(
                    in.readStrongBinder());
            mInputToken = in.readStrongBinder();
        }

        /**
         * Use {@link SurfaceView#setChildSurfacePackage} or manually fix
         * accessibility (see SurfaceView implementation).
         * @hide
         */
        public @NonNull SurfaceControl getSurfaceControl() {
            return mSurfaceControl;
        }

        /**
         * Gets an accessibility embedded connection interface for this SurfaceControlViewHost.
         *
         * @return {@link IAccessibilityEmbeddedConnection} interface.
         * @hide
         */
        public IAccessibilityEmbeddedConnection getAccessibilityEmbeddedConnection() {
            return mAccessibilityEmbeddedConnection;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            mSurfaceControl.writeToParcel(out, flags);
            out.writeStrongBinder(mAccessibilityEmbeddedConnection.asBinder());
            out.writeStrongBinder(mInputToken);
        }

        /**
         * Release the {@link SurfaceControl} associated with this package.
         * It's not necessary to call this if you pass the package to
         * {@link SurfaceView#setChildSurfacePackage} as {@link SurfaceView} will
         * take ownership in that case.
         */
        public void release() {
            if (mSurfaceControl != null) {
                mSurfaceControl.release();
             }
             mSurfaceControl = null;
        }

        /**
         * Returns an input token used which can be used to request focus on the embedded surface.
         *
         * @hide
         */
        public IBinder getInputToken() {
            return mInputToken;
        }

        public static final @NonNull Creator<SurfacePackage> CREATOR
             = new Creator<SurfacePackage>() {
                     public SurfacePackage createFromParcel(Parcel in) {
                         return new SurfacePackage(in);
                     }
                     public SurfacePackage[] newArray(int size) {
                         return new SurfacePackage[size];
                     }
             };
    }

    /** @hide */
    public SurfaceControlViewHost(@NonNull Context c, @NonNull Display d,
            @NonNull WindowlessWindowManager wwm) {
        this(c, d, wwm, false /* useSfChoreographer */);
    }

    /** @hide */
    public SurfaceControlViewHost(@NonNull Context c, @NonNull Display d,
            @NonNull WindowlessWindowManager wwm, boolean useSfChoreographer) {
        mWm = wwm;
        mViewRoot = new ViewRootImpl(c, d, mWm, useSfChoreographer);
        mAccessibilityEmbeddedConnection = mViewRoot.getAccessibilityEmbeddedConnection();
    }

    /**
     * Construct a new SurfaceControlViewHost. The root Surface will be
     * allocated internally and is accessible via getSurfacePackage().
     *
     * The {@param hostToken} parameter, primarily used for ANR reporting,
     * must be obtained from whomever will be hosting the embedded hierarchy.
     * It's accessible from {@link SurfaceView#getHostToken}.
     *
     * @param context The Context object for your activity or application.
     * @param display The Display the hierarchy will be placed on.
     * @param hostToken The host token, as discussed above.
     */
    public SurfaceControlViewHost(@NonNull Context context, @NonNull Display display,
            @Nullable IBinder hostToken) {
        mSurfaceControl = new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("SurfaceControlViewHost")
                .setCallsite("SurfaceControlViewHost")
                .build();
        mWm = new WindowlessWindowManager(context.getResources().getConfiguration(),
                mSurfaceControl, hostToken);
        mViewRoot = new ViewRootImpl(context, display, mWm);
        mAccessibilityEmbeddedConnection = mViewRoot.getAccessibilityEmbeddedConnection();
    }

    /**
     * @hide
     */
    @Override
    protected void finalize() throws Throwable {
        // We aren't on the UI thread here so we need to pass false to
        // doDie
        mViewRoot.die(false /* immediate */);
    }


    /**
     * Return a SurfacePackage for the root SurfaceControl of the embedded hierarchy.
     * Rather than be directly reparented using {@link SurfaceControl.Transaction} this
     * SurfacePackage should be passed to {@link SurfaceView#setChildSurfacePackage}
     * which will not only reparent the Surface, but ensure the accessibility hierarchies
     * are linked.
     */
    public @Nullable SurfacePackage getSurfacePackage() {
        if (mSurfaceControl != null && mAccessibilityEmbeddedConnection != null) {
            return new SurfacePackage(mSurfaceControl, mAccessibilityEmbeddedConnection,
                    mViewRoot.getInputToken());
        } else {
            return null;
        }
    }

    /**
     * Set the root view of the SurfaceControlViewHost. This view will render in to
     * the SurfaceControl, and receive input based on the SurfaceControls positioning on
     * screen. It will be laid as if it were in a window of the passed in width and height.
     *
     * @param view The View to add
     * @param width The width to layout the View within, in pixels.
     * @param height The height to layout the View within, in pixels.
     */
    public void setView(@NonNull View view, int width, int height) {
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        setView(view, lp);
    }

    /**
     * @hide
     */
    @TestApi
    public void setView(@NonNull View view, @NonNull WindowManager.LayoutParams attrs) {
        Objects.requireNonNull(view);
        attrs.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        view.setLayoutParams(attrs);
        mViewRoot.setView(view, attrs, null);
    }

    /**
     * @return The view passed to setView, or null if none has been passed.
     */
    public @Nullable View getView() {
        return mViewRoot.getView();
    }

    /**
     * @return the ViewRootImpl wrapped by this host.
     * @hide
     */
    public IWindow getWindowToken() {
        return mViewRoot.mWindow;
    }

    /**
     * @return the WindowlessWindowManager instance that this host is attached to.
     * @hide
     */
    public @NonNull WindowlessWindowManager getWindowlessWM() {
        return mWm;
    }

    /**
     * @hide
     */
    @TestApi
    public void relayout(WindowManager.LayoutParams attrs) {
        mViewRoot.setLayoutParams(attrs, false);
        mViewRoot.setReportNextDraw();
        mWm.setCompletionCallback(mViewRoot.mWindow.asBinder(), (SurfaceControl.Transaction t) -> {
            t.apply();
        });
    }

    /**
     * Modify the size of the root view.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     */
    public void relayout(int width, int height) {
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        relayout(lp);
    }

    /**
     * Trigger the tear down of the embedded view hierarchy and release the SurfaceControl.
     * This will result in onDispatchedFromWindow being dispatched to the embedded view hierarchy
     * and render the object unusable.
     */
    public void release() {
        // ViewRoot will release mSurfaceControl for us.
        mViewRoot.die(true /* immediate */);
    }
}
