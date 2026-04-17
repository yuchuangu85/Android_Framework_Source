/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player.accessibility.platform;

import android.annotation.NonNull;
import android.view.View;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.RemoteContextActions;
import com.android.internal.widget.remotecompose.player.accessibility.CoreDocumentAccessibility;
import com.android.internal.widget.remotecompose.player.accessibility.RemoteComposeAccessibilityRegistrar;

/**
 * Trivial wrapper for calling setAccessibilityDelegate on a View. This exists primarily because the
 * RemoteDocumentPlayer is either running in the platform on a known API version, or outside in
 * which case it must use the Androidx ViewCompat class.
 */
public class PlatformRemoteComposeAccessibilityRegistrar
        implements RemoteComposeAccessibilityRegistrar {

    /**
     * return helper
     *
     * @param player
     * @param coreDocument
     * @return
     */
    public PlatformRemoteComposeTouchHelper forRemoteComposePlayer(
            View player, @NonNull CoreDocument coreDocument) {
        return new PlatformRemoteComposeTouchHelper(
                player,
                new CoreDocumentAccessibility(coreDocument, ((RemoteContextActions) player)),
                new AndroidPlatformSemanticNodeApplier(player));
    }

    /**
     * set delegate
     *
     * @param remoteComposePlayer The View representing the remote compose player.
     * @param document The CoreDocument containing the accessibility information for the UI
     *     elements.
     */
    public void setAccessibilityDelegate(View remoteComposePlayer, CoreDocument document) {
        remoteComposePlayer.setAccessibilityDelegate(
                forRemoteComposePlayer(remoteComposePlayer, document));
    }

    /**
     * clear delegate
     *
     * @param remoteComposePlayer The View representing the remote compose player.
     */
    public void clearAccessibilityDelegate(View remoteComposePlayer) {
        remoteComposePlayer.setAccessibilityDelegate(null);
    }
}
