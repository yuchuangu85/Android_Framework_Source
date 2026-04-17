/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.service.personalcontext.insight;

import android.annotation.NonNull;
import android.util.Slog;

/**
 * A utility class to traverse a tree of {@link ContextInsight} objects.
 *
 * <p>This class implements a depth-first search (DFS) traversal and uses the "visitor" pattern by
 * applying a stateless {@link InsightVisitor} to each node. It handles the recursion and enforces a
 * maximum depth to prevent stack overflows.
 *
 * @hide
 */
public final class InsightTraverser {
    private static final String TAG = "InsightTraverser";
    private static final int DEFAULT_MAX_DEPTH = 10;

    private InsightTraverser() {}

    /**
     * Traverses the insight tree starting from the root node and applies the visitor to each node.
     *
     * @param root The root {@link ContextInsight} to start traversal from.
     * @param visitor The {@link InsightVisitor} to apply to each visited node.
     */
    public static void traverse(@NonNull ContextInsight root, @NonNull InsightVisitor visitor) {
        traverseInternal(root, visitor, 0, DEFAULT_MAX_DEPTH);
    }

    /**
     * Traverses the insight tree starting from the root node and applies the visitor to each node,
     * with a custom maximum recursion depth.
     *
     * @param root The root {@link ContextInsight} to start traversal from.
     * @param visitor The {@link InsightVisitor} to apply to each visited node.
     * @param maxDepth The maximum recursion depth.
     */
    public static void traverse(
            @NonNull ContextInsight root, @NonNull InsightVisitor visitor, int maxDepth) {
        traverseInternal(root, visitor, 0, maxDepth);
    }

    private static void traverseInternal(
            @NonNull ContextInsight node,
            @NonNull InsightVisitor visitor,
            int currentDepth,
            int maxDepth) {
        if (currentDepth >= maxDepth) {
            Slog.w(
                    TAG,
                    "Max recursion depth reached. Skipping insight: "
                            + node.getClass().getSimpleName()
                            + " with id "
                            + node.getInsightId());
            return;
        }

        node.accept(visitor);

        for (ContextInsight child : node.getChildren()) {
            traverseInternal(child, visitor, currentDepth + 1, maxDepth);
        }
    }
}
