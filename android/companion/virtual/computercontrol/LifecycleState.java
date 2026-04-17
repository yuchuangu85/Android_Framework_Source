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

package android.companion.virtual.computercontrol;

import android.annotation.Nullable;

import java.util.Objects;

/**
 * State definitions for {@link LifecycleStateTracker}.
 * @hide
 */
public sealed interface LifecycleState {

    /** The active state, where the agent can see and interact with the session contents. */
    final class Active implements LifecycleState {
        private Active() {
        }

        @Override
        public String toString() {
            return "Active";
        }
    }

    /** Singleton object for the Active state. */
    LifecycleState ACTIVE = new Active();

    /** The blocked state, where the agent cannot see nor interact with the session contents. */
    final class Blocked implements LifecycleState {
        public final @ComputerControlSession.SessionBlockReason int reason;
        @Nullable
        public final String blockingPackage;

        public Blocked(@ComputerControlSession.SessionBlockReason int reason,
                @Nullable String blockingPackage) {
            this.reason = reason;
            this.blockingPackage = blockingPackage;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Blocked blocked)) return false;
            return reason == blocked.reason && Objects.equals(blockingPackage,
                    blocked.blockingPackage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reason, blockingPackage);
        }

        @Override
        public String toString() {
            return "Blocked(reason=" + reason + ", blockingPackage=" + blockingPackage + ")";
        }
    }

    /** State indicating the session has been closed. */
    final class Closed implements LifecycleState {
        public final @ComputerControlSession.SessionCloseReason int reason;

        public Closed(@ComputerControlSession.SessionCloseReason int reason) {
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Closed closed)) return false;
            return reason == closed.reason;
        }

        @Override
        public int hashCode() {
            return reason;
        }

        @Override
        public String toString() {
            return "Closed(reason=" + reason + ")";
        }
    }
}
