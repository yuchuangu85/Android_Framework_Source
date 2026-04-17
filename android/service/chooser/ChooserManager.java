/*
 * Copyright 2025 The Android Open Source Project
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

package android.service.chooser;

import static com.android.window.flags.Flags.touchPassThroughOptIn;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the creation, tracking, and retrieval of chooser sessions.
 *
 *<p>An Interactive Chooser Session allows apps to invoke the system Chooser without entirely
 * covering app UI. Users may interact with both the app and Chooser, while bidirectional
 * communication between the two ensures a consistent state.</p>
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * ChooserManager chooserManager = context.getSystemService(ChooserManager.class);
 * if (chooserManager == null) {
 *     // handle the case when the interactive chooser session functionality is not supported.
 * }
 *
 * // Construct the sharing intent
 * Intent targetIntent = new Intent(Intent.ACTION_SEND);
 * targetIntent.setType("text/plain");
 * targetIntent.putExtra(Intent.EXTRA_TEXT, "This is a message that will be shared.");
 *
 * Intent chooserIntent = Intent.createChooser(targetIntent, null);
 *
 * // Start a new chooser session
 * ChooserSession session = chooserManager.startSession(context, chooserIntent);
 * ChooserSessionToken token = session.getToken();
 * // Optionally, store the token int an activity saved state to re-associate with the session later
 *
 * // Later, to retrieve a session using a token:
 * ChooserSessionToken retrievedToken = ... // obtain the stored token
 * ChooserSession existingSession = chooserManager.getSession(retrievedToken);
 * if (existingSession != null) {
 * // Interact with the existing session
 * }
 * }</pre>
 *
 * @see ChooserSession
 * @see ChooserSessionToken
 */
@FlaggedApi(Flags.FLAG_INTERACTIVE_CHOOSER)
public class ChooserManager {
    private static final String TAG = "ChooserManager";

    /**
     * @hide
     */
    public ChooserManager() {}

    private final Map<ChooserSessionToken, ChooserSession> mSessions =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * Starts a new interactive Chooser session. The method is idempotent and will start Chooser
     * only once.
     * @param chooserIntent an {@link Intent#ACTION_CHOOSER} intent that will be used as a base
     * for the new Chooser session.
     * <p>An interactive Chooser session also supports the following chooser parameters:
     * <ul>
     * <li>{@link Intent#EXTRA_ALTERNATE_INTENTS}</li>
     * <li>{@link Intent#EXTRA_INITIAL_INTENTS}</li>
     * <li>{@link Intent#EXTRA_EXCLUDE_COMPONENTS}</li>
     * <li>{@link Intent#EXTRA_REPLACEMENT_EXTRAS}</li>
     * <li>{@link Intent#EXTRA_CHOOSER_TARGETS}</li>
     * <li>{@link Intent#EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER}</li>
     * <li>{@link Intent#EXTRA_CHOOSER_RESULT_INTENT_SENDER}</li>
     * <li>{@link Intent#EXTRA_CHOSEN_COMPONENT_INTENT_SENDER}</li>
     * </ul>
     * </p>
     * <p>See also {@link Intent#createChooser(Intent, CharSequence) }.</p>
     */
    @NonNull
    public ChooserSession startSession(@NonNull Context context, @NonNull Intent chooserIntent) {
        Objects.requireNonNull(context, "context should not be null");
        Objects.requireNonNull(chooserIntent, "chooserIntent should not be null");
        if (!Intent.ACTION_CHOOSER.equals(chooserIntent.getAction())) {
            throw new IllegalArgumentException("A chooser intent is expected");
        }
        chooserIntent = new Intent(chooserIntent);
        // FLAG_ACTIVITY_NEW_DOCUMENT can be overridden by the documentLaunchMode in the manifest
        // so it is ignored below.
        chooserIntent.removeFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME
                | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Bundle binderExtras = new Bundle();
        ChooserSession chooserSession = new ChooserSession();
        binderExtras.putBinder(ChooserSession.EXTRA_CHOOSER_SESSION, chooserSession.getBinder());
        chooserIntent.putExtras(binderExtras);
        ActivityOptions options = ActivityOptions.makeBasic();
        if (touchPassThroughOptIn()) {
            options.setAllowPassThroughOnTouchOutside(true);
        }
        context.startActivity(chooserIntent, options.toBundle());
        // TODO: should we listen for session closures and remove them from the collection?
        mSessions.put(chooserSession.getToken(), chooserSession);
        return chooserSession;
    }

    /**
     * Returns a {@link ChooserSession} associated with this token or {@code null} if there is no
     * active session.
     * @param token {@link ChooserSessionToken}.
     * @see ChooserSession#getToken()
     */
    @Nullable
    public ChooserSession getSession(@NonNull ChooserSessionToken token) {
        Objects.requireNonNull(token, "Token should not be null");
        ChooserSession session = mSessions.get(token);
        if (session != null && session.getState() == ChooserSession.STATE_CLOSED) {
            mSessions.remove(token);
            return null;
        }
        return session;
    }
}
