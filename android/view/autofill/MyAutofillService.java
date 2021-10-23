/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.autofill;

import android.app.assist.AssistStructure.ViewNode;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Log;
import android.util.Pair;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.android.perftests.autofill.R;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An {@link AutofillService} implementation whose replies can be programmed by the test case.
 */
public class MyAutofillService extends AutofillService {

    private static final String TAG = "MyAutofillService";
    private static final int TIMEOUT_MS = 5_000;

    private static final String PACKAGE_NAME = "com.android.perftests.autofill";
    static final String COMPONENT_NAME = PACKAGE_NAME + "/android.view.autofill.MyAutofillService";

    private static final BlockingQueue<FillRequest> sFillRequests = new LinkedBlockingQueue<>();
    private static final BlockingQueue<CannedResponse> sCannedResponses =
            new LinkedBlockingQueue<>();

    private static boolean sEnabled;

    /**
     * Returns the TestWatcher that was used for the testing.
     */
    @NonNull
    public static AutofillTestWatcher getTestWatcher() {
        return new AutofillTestWatcher();
    }

    /**
     * Resets the static state associated with the service.
     */
    static void resetStaticState() {
        sFillRequests.clear();
        sCannedResponses.clear();
        sEnabled = false;
    }

    /**
     * Sets whether the service is enabled or not - when disabled, calls to
     * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} will be ignored.
     */
    static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * Gets the the last {@link FillRequest} passed to
     * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} or throws an
     * exception if that method was not called.
     */
    @NonNull
    static FillRequest getLastFillRequest() {
        FillRequest request = null;
        try {
            request = sFillRequests.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("onFillRequest() interrupted");
        }
        if (request == null) {
            throw new IllegalStateException("onFillRequest() not called in " + TIMEOUT_MS + "ms");
        }
        return request;
    }

    @Override
    public void onConnected() {
        AutofillTestWatcher.ServiceWatcher.onConnected();
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal,
            FillCallback callback) {
        try {
            handleRequest(request, callback);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onError("onFillRequest() interrupted", e, callback);
        } catch (Exception e) {
            onError("exception on onFillRequest()", e, callback);
        }
    }


    private void handleRequest(FillRequest request, FillCallback callback) throws Exception {
        if (!sEnabled) {
            onError("ignoring onFillRequest(): service is disabled", callback);
            return;
        }
        CannedResponse response = sCannedResponses.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (response == null) {
            onError("ignoring onFillRequest(): response not set", callback);
            return;
        }
        Dataset.Builder dataset = new Dataset.Builder(newDatasetPresentation("dataset"));
        boolean hasData = false;
        if (response.mUsername != null) {
            hasData = true;
            AutofillId autofillId = getAutofillIdByResourceId(request, response.mUsername.first);
            AutofillValue value = AutofillValue.forText(response.mUsername.second);
            dataset.setValue(autofillId, value, newDatasetPresentation("dataset"));
        }
        if (response.mPassword != null) {
            hasData = true;
            AutofillId autofillId = getAutofillIdByResourceId(request, response.mPassword.first);
            AutofillValue value = AutofillValue.forText(response.mPassword.second);
            dataset.setValue(autofillId, value, newDatasetPresentation("dataset"));
        }
        if (hasData) {
            FillResponse.Builder fillResponse = new FillResponse.Builder();
            if (response.mIgnoredIds != null) {
                int length = response.mIgnoredIds.length;
                AutofillId[] requiredIds = new AutofillId[length];
                for (int i = 0; i < length; i++) {
                    String resourceId = response.mIgnoredIds[i];
                    requiredIds[i] = getAutofillIdByResourceId(request, resourceId);
                }
                fillResponse.setIgnoredIds(requiredIds);
            }
            callback.onSuccess(fillResponse.addDataset(dataset.build()).build());
        } else {
            callback.onSuccess(null);
        }
        if (!sFillRequests.offer(request, TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "could not offer request in " + TIMEOUT_MS + "ms");
        }
    }

    private AutofillId getAutofillIdByResourceId(FillRequest request, String resourceId)
            throws Exception {
        ViewNode node = AutofillTestHelper.findNodeByResourceId(request.getFillContexts(),
                resourceId);
        if (node == null) {
            throw new AssertionError("No node with resource id " + resourceId);
        }
        return node.getAutofillId();
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        // No current test should have triggered it...
        Log.e(TAG, "onSaveRequest() should not have been called");
        callback.onFailure("onSaveRequest() should not have been called");
    }

    static final class CannedResponse {
        private final Pair<String, String> mUsername;
        private final Pair<String, String> mPassword;
        private final String[] mIgnoredIds;

        private CannedResponse(@NonNull Builder builder) {
            mUsername = builder.mUsername;
            mPassword = builder.mPassword;
            mIgnoredIds = builder.mIgnoredIds;
        }

        static class Builder {
            private Pair<String, String> mUsername;
            private Pair<String, String> mPassword;
            private String[] mIgnoredIds;

            @NonNull
            Builder setUsername(@NonNull String id, @NonNull String value) {
                mUsername = new Pair<>(id, value);
                return this;
            }

            @NonNull
            Builder setPassword(@NonNull String id, @NonNull String value) {
                mPassword = new Pair<>(id, value);
                return this;
            }

            @NonNull
            Builder setIgnored(String... ids) {
                mIgnoredIds = ids;
                return this;
            }

            void reply() {
                sCannedResponses.add(new CannedResponse(this));
            }
        }
    }

    /**
     * Sets the expected canned {@link FillResponse} for the next
     * {@link AutofillService#onFillRequest(FillRequest, CancellationSignal, FillCallback)}.
     */
    static CannedResponse.Builder newCannedResponse() {
        return new CannedResponse.Builder();
    }

    private void onError(@NonNull String msg, @NonNull FillCallback callback) {
        Log.e(TAG, msg);
        callback.onFailure(msg);
    }

    private void onError(@NonNull String msg, @NonNull Exception e,
            @NonNull FillCallback callback) {
        Log.e(TAG, msg, e);
        callback.onFailure(msg);
    }

    @NonNull
    private static RemoteViews newDatasetPresentation(@NonNull CharSequence text) {
        RemoteViews presentation =
                new RemoteViews(PACKAGE_NAME, R.layout.autofill_dataset_picker_text_only);
        presentation.setTextViewText(R.id.text, text);
        return presentation;
    }
}
