/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.display;

import android.annotation.FlaggedApi;
import android.annotation.TestApi;
import android.graphics.RectF;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.server.display.feature.flags.Flags;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Graph of the displays in {@link android.hardware.display.DisplayTopology} tree.
 *
 * <p>If there is a corner adjacency, the same display will appear twice in the list of adjacent
 * displays with both possible placements.
 *
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_DISPLAY_TOPOLOGY_API)
public class DisplayTopologyGraph {

    private final int mPrimaryDisplayId;
    private final DisplayNode[] mDisplayNodes;

    /** @hide */
    public DisplayTopologyGraph(int primaryDisplayId, DisplayNode[] displayNodes) {
        mPrimaryDisplayId = primaryDisplayId;
        mDisplayNodes = displayNodes;
    }

    /** @hide */
    public int getPrimaryDisplayId() {
        return mPrimaryDisplayId;
    }

    /**
     * Gets list of node representation of all displays available in the {@link DisplayTopology}.
     * The key of the SparseArray is the id of the DisplayNode
     */
    public @NonNull SparseArray<DisplayNode> getDisplayNodes() {
        SparseArray<DisplayNode> displayNodes = new SparseArray<>(mDisplayNodes.length);
        for (DisplayNode displayNode : mDisplayNodes) {
            displayNodes.put(displayNode.mDisplayId, displayNode);
        }
        return displayNodes;
    }

    /**
     * Print the object's state and debug information into the given stream.
     * @hide
     * @param pw The stream to dump information to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("DisplayTopologyGraph{" + "mPrimaryDisplayId=" + mPrimaryDisplayId + '}');
        pw.increaseIndent();
        for (DisplayNode displayNode : mDisplayNodes) {
            displayNode.dump(pw);
        }
        pw.decreaseIndent();
    }

    @Override
    public String toString() {
        StringWriter out = new StringWriter();
        dump(new IndentingPrintWriter(out));
        return out.toString();
    }

    /** Node representation of a display, including its {@link AdjacentEdge} */
    public static class DisplayNode {

        private final int mDisplayId;
        private final int mDensity;
        private final RectF mBoundsInGlobalDp;
        private AdjacentEdge[] mAdjacentEdges;

        /** @hide */
        public DisplayNode(int displayId, int density, @NonNull RectF boundsInGlobalDp) {
            mDisplayId = displayId;
            mDensity = density;
            mBoundsInGlobalDp = boundsInGlobalDp;
        }

        /**
         * Gets the display id of this display node
         */
        public int getDisplayId() {
            return mDisplayId;
        }

        /** @hide */
        public int getDensity() {
            return mDensity;
        }

        /** @hide */
        public @NonNull RectF getBoundsInGlobalDp() {
            return mBoundsInGlobalDp;
        }

        /** @hide */
        void setAdjacentEdges(@NonNull AdjacentEdge[] edges) {
            mAdjacentEdges = edges;
        }

        /**
         * Gets a list of neighboring displays
         */
        public @NonNull List<AdjacentEdge> getAdjacentEdges() {
            return Collections.unmodifiableList(Arrays.asList(mAdjacentEdges));
        }

        /**
         * Print the object's state and debug information into the given stream.
         * @hide
         * @param pw The stream to dump information to.
         */
        public void dump(IndentingPrintWriter pw) {
            pw.println("DisplayNode{" + "displayId=" + mDisplayId + ", density=" + mDensity
                    + ", bounds=" + mBoundsInGlobalDp + '}');
            if (mAdjacentEdges != null) {
                pw.increaseIndent();
                for (AdjacentEdge edge : mAdjacentEdges) {
                    pw.println(edge);
                }
                pw.decreaseIndent();
            }
        }
    }

    /** Edge to adjacent display */
    public static final class AdjacentEdge {

        // The logical Id of this adjacent display
        private final DisplayNode mDisplayNode;

        // Side of the other display which touches this adjacent display.
        @DisplayTopology.Position
        private final int mPosition;

        // The distance from the top edge of the other display to the top edge of this display
        // (in case of POSITION_LEFT or POSITION_RIGHT) or from the left edge of the parent
        // display to the left edge of this display (in case of POSITION_TOP or
        // POSITION_BOTTOM). The unit used is density-independent pixels (dp).
        private final float mOffsetDp;

        /** @hide */
        public AdjacentEdge(DisplayNode displayNode, @DisplayTopology.Position int position,
                float offsetDp) {
            mDisplayNode = displayNode;
            mPosition = position;
            mOffsetDp = offsetDp;
        }

        /**
         * Gets the {@link DisplayNode} of this adjacent display
         */
        public @NonNull DisplayNode getDisplayNode() {
            return mDisplayNode;
        }

        /**
         * Gets the position of this display relative to {@link DisplayNode} it belongs to
         */
        @DisplayTopology.Position
        public int getPosition() {
            return mPosition;
        }

        /** @hide */
        public float getOffsetDp() {
            return mOffsetDp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            AdjacentEdge rhs = (AdjacentEdge) o;
            return this.mDisplayNode.mDisplayId == rhs.mDisplayNode.mDisplayId
                    && this.mPosition == rhs.mPosition && this.mOffsetDp == rhs.mOffsetDp;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDisplayNode.mDisplayId, mPosition, mOffsetDp);
        }

        @Override
        public String toString() {
            return "AdjacentEdge{" + "displayId=" + mDisplayNode.mDisplayId + ", position="
                    + DisplayTopology.TreeNode.positionToString(mPosition) + ", offsetDp="
                    + mOffsetDp + '}';
        }
    }
}
