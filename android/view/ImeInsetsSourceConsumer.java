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
 * limitations under the License
 */

package android.view;

import static android.os.Trace.TRACE_TAG_VIEW;
import static android.view.ImeInsetsSourceConsumerProto.INSETS_SOURCE_CONSUMER;
import static android.view.ImeInsetsSourceConsumerProto.IS_REQUESTED_VISIBLE_AWAITING_CONTROL;
import static android.view.InsetsController.AnimationType;
import static android.view.InsetsState.ITYPE_IME;

import android.annotation.Nullable;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.Trace;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl.Transaction;
import android.view.inputmethod.InputMethodManager;

import java.util.function.Supplier;

/**
 * Controls the visibility and animations of IME window insets source.
 * @hide
 */
public final class ImeInsetsSourceConsumer extends InsetsSourceConsumer {

    /**
     * Tracks whether we have an outstanding request from the IME to show, but weren't able to
     * execute it because we didn't have control yet.
     */
    private boolean mIsRequestedVisibleAwaitingControl;

    public ImeInsetsSourceConsumer(
            InsetsState state, Supplier<Transaction> transactionSupplier,
            InsetsController controller) {
        super(ITYPE_IME, state, transactionSupplier, controller);
    }

    @Override
    public void onWindowFocusGained(boolean hasViewFocus) {
        super.onWindowFocusGained(hasViewFocus);
        getImm().registerImeConsumer(this);
    }

    @Override
    public void onWindowFocusLost() {
        super.onWindowFocusLost();
        getImm().unregisterImeConsumer(this);
        mIsRequestedVisibleAwaitingControl = false;
    }

    @Override
    public void hide() {
        super.hide();
        mIsRequestedVisibleAwaitingControl = false;
    }

    @Override
    void hide(boolean animationFinished, @AnimationType int animationType) {
        hide();

        if (animationFinished) {
            // remove IME surface as IME has finished hide animation.
            notifyHidden();
            removeSurface();
        }
    }

    /**
     * Request {@link InputMethodManager} to show the IME.
     * @return @see {@link android.view.InsetsSourceConsumer.ShowResult}.
     */
    @Override
    public @ShowResult int requestShow(boolean fromIme) {
        // TODO: ResultReceiver for IME.
        // TODO: Set mShowOnNextImeRender to automatically show IME and guard it with a flag.
        if (getControl() == null) {
            // If control is null, schedule to show IME when control is available.
            mIsRequestedVisibleAwaitingControl = true;
        }
        // If we had a request before to show from IME (tracked with mImeRequestedShow), reaching
        // this code here means that we now got control, so we can start the animation immediately.
        // If client window is trying to control IME and IME is already visible, it is immediate.
        if (fromIme || mState.getSource(getType()).isVisible() && getControl() != null) {
            return ShowResult.SHOW_IMMEDIATELY;
        }

        return getImm().requestImeShow(mController.getHost().getWindowToken())
                ? ShowResult.IME_SHOW_DELAYED : ShowResult.IME_SHOW_FAILED;
    }

    /**
     * Notify {@link InputMethodService} that IME window is hidden.
     */
    @Override
    void notifyHidden() {
        getImm().notifyImeHidden(mController.getHost().getWindowToken());
        Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.hideRequestFromApi", 0);
    }

    @Override
    public void removeSurface() {
        final IBinder window = mController.getHost().getWindowToken();
        if (window != null) {
            getImm().removeImeSurface(window);
        }
    }

    @Override
    public void setControl(@Nullable InsetsSourceControl control, int[] showTypes,
            int[] hideTypes) {
        super.setControl(control, showTypes, hideTypes);
        if (control == null && !mIsRequestedVisibleAwaitingControl) {
            hide();
            removeSurface();
        }
        if (control != null) {
            mIsRequestedVisibleAwaitingControl = false;
        }
    }

    @Override
    protected boolean isRequestedVisibleAwaitingControl() {
        return mIsRequestedVisibleAwaitingControl || isRequestedVisible();
    }

    @Override
    public void onPerceptible(boolean perceptible) {
        super.onPerceptible(perceptible);
        final IBinder window = mController.getHost().getWindowToken();
        if (window != null) {
            getImm().reportPerceptible(window, perceptible);
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        super.dumpDebug(proto, INSETS_SOURCE_CONSUMER);
        proto.write(IS_REQUESTED_VISIBLE_AWAITING_CONTROL, mIsRequestedVisibleAwaitingControl);
        proto.end(token);
    }

    private InputMethodManager getImm() {
        return mController.getHost().getInputMethodManager();
    }
}
