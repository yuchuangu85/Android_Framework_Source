/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.inputmethodservice;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.proto.ProtoOutputStream;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSession;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * AbstractInputMethodService provides a abstract base class for input methods.
 * Normal input method implementations will not derive from this directly,
 * instead building on top of {@link InputMethodService} or another more
 * complete base class.  Be sure to read {@link InputMethod} for more
 * information on the basics of writing input methods.
 * 
 * <p>This class combines a Service (representing the input method component
 * to the system with the InputMethod interface that input methods must
 * implement.  This base class takes care of reporting your InputMethod from
 * the service when clients bind to it, but provides no standard implementation
 * of the InputMethod interface itself.  Derived classes must implement that
 * interface.
 */
public abstract class AbstractInputMethodService extends Service
        implements KeyEvent.Callback {
    private InputMethod mInputMethod;
    
    final KeyEvent.DispatcherState mDispatcherState
            = new KeyEvent.DispatcherState();

    /**
     * Base class for derived classes to implement their {@link InputMethod}
     * interface.  This takes care of basic maintenance of the input method,
     * but most behavior must be implemented in a derived class.
     */
    public abstract class AbstractInputMethodImpl implements InputMethod {
        /**
         * Instantiate a new client session for the input method, by calling
         * back to {@link AbstractInputMethodService#onCreateInputMethodSessionInterface()
         * AbstractInputMethodService.onCreateInputMethodSessionInterface()}.
         */
        @MainThread
        public void createSession(SessionCallback callback) {
            callback.sessionCreated(onCreateInputMethodSessionInterface());
        }
        
        /**
         * Take care of enabling or disabling an existing session by calling its
         * {@link AbstractInputMethodSessionImpl#revokeSelf()
         * AbstractInputMethodSessionImpl.setEnabled()} method.
         */
        @MainThread
        public void setSessionEnabled(InputMethodSession session, boolean enabled) {
            ((AbstractInputMethodSessionImpl)session).setEnabled(enabled);
        }
        
        /**
         * Take care of killing an existing session by calling its
         * {@link AbstractInputMethodSessionImpl#revokeSelf()
         * AbstractInputMethodSessionImpl.revokeSelf()} method.
         */
        @MainThread
        public void revokeSession(InputMethodSession session) {
            ((AbstractInputMethodSessionImpl)session).revokeSelf();
        }
    }
    
    /**
     * Base class for derived classes to implement their {@link InputMethodSession}
     * interface.  This takes care of basic maintenance of the session,
     * but most behavior must be implemented in a derived class.
     */
    public abstract class AbstractInputMethodSessionImpl implements InputMethodSession {
        boolean mEnabled = true;
        boolean mRevoked;
        
        /**
         * Check whether this session has been enabled by the system.  If not
         * enabled, you should not execute any calls on to it.
         */
        public boolean isEnabled() {
            return mEnabled;
        }
        
        /**
         * Check whether this session has been revoked by the system.  Revoked
         * session is also always disabled, so there is generally no need to
         * explicitly check for this.
         */
        public boolean isRevoked() {
            return mRevoked;
        }
        
        /**
         * Change the enabled state of the session.  This only works if the
         * session has not been revoked.
         */
        public void setEnabled(boolean enabled) {
            if (!mRevoked) {
                mEnabled = enabled;
            }
        }
        
        /**
         * Revoke the session from the client.  This disabled the session, and
         * prevents it from ever being enabled again.
         */
        public void revokeSelf() {
            mRevoked = true;
            mEnabled = false;
        }

        /**
         * Take care of dispatching incoming key events to the appropriate
         * callbacks on the service, and tell the client when this is done.
         */
        @Override
        public void dispatchKeyEvent(int seq, KeyEvent event, EventCallback callback) {
            boolean handled = event.dispatch(AbstractInputMethodService.this,
                    mDispatcherState, this);
            if (callback != null) {
                callback.finishedEvent(seq, handled);
            }
        }

        /**
         * Take care of dispatching incoming trackball events to the appropriate
         * callbacks on the service, and tell the client when this is done.
         */
        @Override
        public void dispatchTrackballEvent(int seq, MotionEvent event, EventCallback callback) {
            boolean handled = onTrackballEvent(event);
            if (callback != null) {
                callback.finishedEvent(seq, handled);
            }
        }

        /**
         * Take care of dispatching incoming generic motion events to the appropriate
         * callbacks on the service, and tell the client when this is done.
         */
        @Override
        public void dispatchGenericMotionEvent(int seq, MotionEvent event, EventCallback callback) {
            boolean handled = onGenericMotionEvent(event);
            if (callback != null) {
                callback.finishedEvent(seq, handled);
            }
        }
    }
    
    /**
     * Return the global {@link KeyEvent.DispatcherState KeyEvent.DispatcherState}
     * for used for processing events from the target application.
     * Normally you will not need to use this directly, but
     * just use the standard high-level event callbacks like {@link #onKeyDown}.
     */
    public KeyEvent.DispatcherState getKeyDispatcherState() {
        return mDispatcherState;
    }
    
    /**
     * Called by the framework during initialization, when the InputMethod
     * interface for this service needs to be created.
     */
    public abstract AbstractInputMethodImpl onCreateInputMethodInterface();
    
    /**
     * Called by the framework when a new InputMethodSession interface is
     * needed for a new client of the input method.
     */
    public abstract AbstractInputMethodSessionImpl onCreateInputMethodSessionInterface();

    /**
     * Dumps the internal state of IME to a protocol buffer output stream.
     *
     * @param proto ProtoOutputStream to dump data to.
     * @param icProto {@link InputConnection} call data in proto format.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void dumpProtoInternal(ProtoOutputStream proto, ProtoOutputStream icProto);

    /**
     * Implement this to handle {@link android.os.Binder#dump Binder.dump()}
     * calls on your input method.
     */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
    }

    @Override
    final public IBinder onBind(Intent intent) {
        if (mInputMethod == null) {
            mInputMethod = onCreateInputMethodInterface();
        }
        return new IInputMethodWrapper(this, mInputMethod);
    }
    
    /**
     * Implement this to handle trackball events on your input method.
     *
     * @param event The motion event being received.
     * @return True if the event was handled in this function, false otherwise.
     * @see android.view.View#onTrackballEvent(MotionEvent)
     */
    public boolean onTrackballEvent(MotionEvent event) {
        return false;
    }

    /**
     * Implement this to handle generic motion events on your input method.
     *
     * @param event The motion event being received.
     * @return True if the event was handled in this function, false otherwise.
     * @see android.view.View#onGenericMotionEvent(MotionEvent)
     */
    public boolean onGenericMotionEvent(MotionEvent event) {
        return false;
    }

    /**
     * Allow the receiver of {@link InputContentInfo} to obtain a temporary read-only access
     * permission to the content.
     *
     * <p>Default implementation does nothing.</p>
     *
     * @param inputContentInfo Content to be temporarily exposed from the input method to the
     * application.
     * This cannot be {@code null}.
     * @param inputConnection {@link InputConnection} with which
     * {@link InputConnection#commitContent(InputContentInfo, int, android.os.Bundle)} will be
     * called.
     * @return {@code false} if we cannot allow a temporary access permission.
     * @hide
     */
    public void exposeContent(@NonNull InputContentInfo inputContentInfo,
            @NonNull InputConnection inputConnection) {
        return;
    }

    /**
     * Called when the user took some actions that should be taken into consideration to update the
     * MRU list for input method rotation.
     *
     * @hide
     */
    public void notifyUserActionIfNecessary() {
    }

    /** @hide */
    @Override
    public final boolean isUiContext() {
        return true;
    }
}
