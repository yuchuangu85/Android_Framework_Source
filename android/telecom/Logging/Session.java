/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telecom.Logging;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telecom.Log;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores information about a thread's point of entry into that should persist until that thread
 * exits.
 * @hide
 */
public class Session {

    public static final String LOG_TAG = "Session";

    public static final String START_SESSION = "START_SESSION";
    public static final String START_EXTERNAL_SESSION = "START_EXTERNAL_SESSION";
    public static final String CREATE_SUBSESSION = "CREATE_SUBSESSION";
    public static final String CONTINUE_SUBSESSION = "CONTINUE_SUBSESSION";
    public static final String END_SUBSESSION = "END_SUBSESSION";
    public static final String END_SESSION = "END_SESSION";

    public static final String SUBSESSION_SEPARATION_CHAR = "->";
    public static final String SESSION_SEPARATION_CHAR_CHILD = "_";
    public static final String EXTERNAL_INDICATOR = "E-";
    public static final String TRUNCATE_STRING = "...";

    // Prevent infinite recursion by setting a reasonable limit.
    private static final int SESSION_RECURSION_LIMIT = 25;

    /**
     * Initial value of mExecutionEndTimeMs and the final value of {@link #getLocalExecutionTime()}
     * if the Session is canceled.
     */
    public static final long UNDEFINED = -1;

    public static class Info implements Parcelable {
        public final String sessionId;
        public final String methodPath;
        public final String ownerInfo;

        private Info(String id, String path, String owner) {
            sessionId = id;
            methodPath = path;
            ownerInfo = owner;
        }

        public static Info getInfo (Session s) {
            // Create Info based on the truncated method path if the session is external, so we do
            // not get multiple stacking external sessions (unless we have DEBUG level logging or
            // lower).
            return new Info(s.getFullSessionId(), s.getFullMethodPath(
                    !Log.DEBUG && s.isSessionExternal()), s.getOwnerInfo());
        }

        public static Info getExternalInfo(Session s, @Nullable String ownerInfo) {
            // When creating session information for an existing session, the caller may pass in a
            // context to be passed along to the recipient of the external session info.
            // So, for example, if telecom has an active session with owner 'cad', and Telecom is
            // calling into Telephony and providing external session info, it would pass in 'cast'
            // as the owner info.  This would result in Telephony seeing owner info 'cad/cast',
            // which would make it very clear in the Telephony logs the chain of package calls which
            // ultimately resulted in the logs.
            String newInfo = ownerInfo != null && s.getOwnerInfo() != null
                    // If we've got both, concatenate them.
                    ? s.getOwnerInfo() + "/" + ownerInfo
                    // Otherwise use whichever is present.
                    : ownerInfo != null ? ownerInfo : s.getOwnerInfo();

            // Create Info based on the truncated method path if the session is external, so we do
            // not get multiple stacking external sessions (unless we have DEBUG level logging or
            // lower).
            return new Info(s.getFullSessionId(), s.getFullMethodPath(
                    !Log.DEBUG && s.isSessionExternal()), newInfo);
        }

        /** Responsible for creating Info objects for deserialized Parcels. */
        public static final @android.annotation.NonNull Parcelable.Creator<Info> CREATOR =
                new Parcelable.Creator<Info> () {
                    @Override
                    public Info createFromParcel(Parcel source) {
                        String id = source.readString();
                        String methodName = source.readString();
                        String ownerInfo = source.readString();
                        return new Info(id, methodName, ownerInfo);
                    }

                    @Override
                    public Info[] newArray(int size) {
                        return new Info[size];
                    }
                };

        /** {@inheritDoc} */
        @Override
        public int describeContents() {
            return 0;
        }

        /** Writes Info object into a Parcel. */
        @Override
        public void writeToParcel(Parcel destination, int flags) {
            destination.writeString(sessionId);
            destination.writeString(methodPath);
            destination.writeString(ownerInfo);
        }
    }

    private final String mSessionId;
    private volatile String mShortMethodName;
    private long mExecutionStartTimeMs;
    private long mExecutionEndTimeMs = UNDEFINED;
    private volatile Session mParentSession;
    private final ArrayList<Session> mChildSessions = new ArrayList<>(5);
    private boolean mIsCompleted = false;
    private final boolean mIsExternal;
    private final AtomicInteger mChildCounter = new AtomicInteger(0);
    // True if this is a subsession that has been started from the same thread as the parent
    // session. This can happen if Log.startSession(...) is called multiple times on the same
    // thread in the case of one Telecom entry point method calling another entry point method.
    // In this case, we can just make this subsession "invisible," but still keep track of it so
    // that the Log.endSession() calls match up.
    private final boolean mIsStartedFromActiveSession;
    // Optionally provided info about the method/class/component that started the session in order
    // to make Logging easier. This info will be provided in parentheses along with the session.
    private final String mOwnerInfo;
    // Cache Full Method path so that recursive population of the full method path only needs to
    // be calculated once.
    private volatile String mFullMethodPathCache;

    public Session(String sessionId, String shortMethodName, long startTimeMs,
            boolean isStartedFromActiveSession, boolean isExternal, String ownerInfo) {
        mSessionId = (sessionId != null) ? sessionId : "???";
        setShortMethodName(shortMethodName);
        mExecutionStartTimeMs = startTimeMs;
        mParentSession = null;
        mIsStartedFromActiveSession = isStartedFromActiveSession;
        mIsExternal = isExternal;
        mOwnerInfo = ownerInfo;
    }

    public String getShortMethodName() {
        return mShortMethodName;
    }

    public void setShortMethodName(String shortMethodName) {
        if (shortMethodName == null) {
            shortMethodName = "";
        }
        mShortMethodName = shortMethodName;
    }

    public boolean isExternal() {
        return mIsExternal;
    }

    public void setParentSession(Session parentSession) {
        mParentSession = parentSession;
    }

    public void addChild(Session childSession) {
        if (childSession == null) return;
        synchronized (mChildSessions) {
            mChildSessions.add(childSession);
        }
    }

    public void removeChild(Session child) {
        if (child == null) return;
        synchronized (mChildSessions) {
            mChildSessions.remove(child);
        }
    }

    public long getExecutionStartTimeMilliseconds() {
        return mExecutionStartTimeMs;
    }

    public void setExecutionStartTimeMs(long startTimeMs) {
        mExecutionStartTimeMs = startTimeMs;
    }

    public Session getParentSession() {
        return mParentSession;
    }

    public ArrayList<Session> getChildSessions() {
        synchronized (mChildSessions) {
            return new ArrayList<>(mChildSessions);
        }
    }

    public boolean isSessionCompleted() {
        return mIsCompleted;
    }

    public boolean isStartedFromActiveSession() {
        return mIsStartedFromActiveSession;
    }

    public Info getInfo() {
        return Info.getInfo(this);
    }

    public Info getExternalInfo(@Nullable String ownerInfo) {
        return Info.getExternalInfo(this, ownerInfo);
    }

    public String getOwnerInfo() {
        return mOwnerInfo;
    }

    @VisibleForTesting
    public String getSessionId() {
        return mSessionId;
    }

    // Mark this session complete. This will be deleted by Log when all subsessions are complete
    // as well.
    public void markSessionCompleted(long executionEndTimeMs) {
        mExecutionEndTimeMs = executionEndTimeMs;
        mIsCompleted = true;
    }

    public long getLocalExecutionTime() {
        if (mExecutionEndTimeMs == UNDEFINED) {
            return UNDEFINED;
        }
        return mExecutionEndTimeMs - mExecutionStartTimeMs;
    }

    public String getNextChildId() {
        return String.valueOf(mChildCounter.getAndIncrement());
    }

    // Builds full session ID, which incliudes the optional external indicators (E),
    // base session ID, and the optional sub-session IDs (_X): @[E-]...[ID][_X][_Y]...
    private String getFullSessionId() {
        int currParentCount = 0;
        StringBuilder id = new StringBuilder();
        Session currSession = this;
        while (currSession != null) {
            Session parentSession = currSession.getParentSession();
            if (parentSession != null) {
                if (currParentCount >= SESSION_RECURSION_LIMIT) {
                    id.insert(0, getSessionId());
                    id.insert(0, TRUNCATE_STRING);
                    android.util.Slog.w(LOG_TAG, "getFullSessionId: Hit iteration limit!");
                    return id.toString();
                }
                if (Log.VERBOSE) {
                    id.insert(0, currSession.getSessionId());
                    id.insert(0, SESSION_SEPARATION_CHAR_CHILD);
                }
            } else {
                id.insert(0, currSession.getSessionId());
            }
            currSession = parentSession;
            currParentCount++;
        }
        return id.toString();
    }
    private Session getRootSession(String callingMethod) {
        int currParentCount = 0;
        Session topNode = this;
        Session parentNode = topNode.getParentSession();
        while (parentNode != null) {
            if (currParentCount >= SESSION_RECURSION_LIMIT) {
                // Don't use Telecom's Log.w here or it will cause infinite recursion because it
                // will try to add session information to this logging statement, which will cause
                // it to hit this condition again and so on...
                android.util.Slog.w(LOG_TAG, "getRootSession: Hit iteration limit from "
                        + callingMethod);
                break;
            }
            topNode = parentNode;
            parentNode = topNode.getParentSession();
            currParentCount++;
        }
        return topNode;
    }

    // Print out the full Session tree from any subsession node
    public String printFullSessionTree() {
        return getRootSession("printFullSessionTree").printSessionTree();
    }

    private String printSessionTree() {
        final StringBuilder sb = new StringBuilder();
        // Use an ArrayDeque as a stack for nodes to visit.
        final ArrayDeque<Session> sessionStack = new ArrayDeque<>();
        // Use a parallel stack to track the depth of each node.
        final ArrayDeque<Integer> depthStack = new ArrayDeque<>();

        // Start with the current session at depth 0.
        sessionStack.push(this);
        depthStack.push(0);

        while (!sessionStack.isEmpty()) {
            // Pop the next session and its corresponding depth.
            final Session session = sessionStack.pop();
            final int depth = depthStack.pop();

            // Append indented session information.
            sb.append("\t".repeat(depth));
            sb.append(session.toString());
            sb.append("\n");

            // If the depth limit is reached, print a truncation marker and stop descending.
            if (depth >= SESSION_RECURSION_LIMIT) {
                sb.append("\t".repeat(depth + 1));
                sb.append(TRUNCATE_STRING);
                sb.append("\n");
                continue;
            }

            final List<Session> childSessions = session.getChildSessions();
            // Push children onto the stack in reverse order. This ensures they are
            // processed in their natural (first-to-last) order due to the LIFO
            // nature of the stack.
            for (int i = childSessions.size() - 1; i >= 0; i--) {
                sessionStack.push(childSessions.get(i));
                depthStack.push(depth + 1);
            }
        }

        return sb.toString();
    }

    /**
     * Concatenate the short method name with the parent Sessions to create full method path.
     * @param truncatePath if truncatePath is set to true, all other external sessions (except for
     *                     the most recent) will be truncated to "..."
     * @return The full method path associated with this Session.
     */
    @VisibleForTesting
    public String getFullMethodPath(boolean truncatePath) {
        StringBuilder sb = new StringBuilder();
        // Check to see if the session has been renamed yet. If it has not, then the session
        // has not been continued.
        Session parentSession = getParentSession();
        boolean isSessionStarted = parentSession == null
                || !getShortMethodName().equals(parentSession.getShortMethodName());
        int depth = 0;
        Session currSession = this;
        while (currSession != null) {
            String cache = currSession.mFullMethodPathCache;
            // Return cached value for method path. When returning the truncated path, recalculate
            // the full path without using the cached value.
            if (!TextUtils.isEmpty(cache) && !truncatePath) {
                sb.insert(0, cache);
                return sb.toString();
            }

            parentSession = currSession.getParentSession();
            // Encapsulate the external session's method name so it is obvious what part of the
            // session is external or truncate it if we do not want the entire history.
            if (currSession.isExternal()) {
                if (truncatePath) {
                    sb.insert(0, TRUNCATE_STRING);
                } else {
                    sb.insert(0, ")");
                    sb.insert(0, currSession.getShortMethodName());
                    sb.insert(0, "(");
                }
            } else {
                sb.insert(0, currSession.getShortMethodName());
            }
            if (parentSession != null) {
                sb.insert(0, SUBSESSION_SEPARATION_CHAR);
            }

            if (depth >= SESSION_RECURSION_LIMIT) {
                // Don't use Telecom's Log.w here or it will cause infinite recursion because it
                // will try to add session information to this logging statement, which will cause
                // it to hit this condition again and so on...
                android.util.Slog.w(LOG_TAG, "getFullMethodPath: Hit iteration limit!");
                sb.insert(0, TRUNCATE_STRING);
                return sb.toString();
            }
            currSession = parentSession;
            depth++;
        }
        if (isSessionStarted && !truncatePath) {
            // Cache the full method path for this node so that we do not need to calculate it
            // again in the future.
            mFullMethodPathCache = sb.toString();
        }
        return sb.toString();
    }

    // Recursively move to the top of the tree to see if the parent session is external.
    private boolean isSessionExternal() {
        return getRootSession("isSessionExternal").isExternal();
    }

    @Override
    public int hashCode() {
        int result = mSessionId.hashCode();
        result = 31 * result + mShortMethodName.hashCode();
        result = 31 * result + Long.hashCode(mExecutionStartTimeMs);
        result = 31 * result + Long.hashCode(mExecutionEndTimeMs);
        result = 31 * result + (mParentSession != null ? mParentSession.hashCode() : 0);
        result = 31 * result + mChildSessions.hashCode();
        result = 31 * result + (mIsCompleted ? 1 : 0);
        result = 31 * result + mChildCounter.hashCode();
        result = 31 * result + (mIsStartedFromActiveSession ? 1 : 0);
        result = 31 * result + (mOwnerInfo != null ? mOwnerInfo.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Session session = (Session) o;

        if (mExecutionStartTimeMs != session.mExecutionStartTimeMs) return false;
        if (mExecutionEndTimeMs != session.mExecutionEndTimeMs) return false;
        if (mIsCompleted != session.mIsCompleted) return false;
        if (!(mChildCounter.get() == session.mChildCounter.get())) return false;
        if (mIsStartedFromActiveSession != session.mIsStartedFromActiveSession) return false;
        if (!Objects.equals(mSessionId, session.mSessionId)) return false;
        if (!Objects.equals(mShortMethodName, session.mShortMethodName)) return false;
        if (!Objects.equals(mParentSession, session.mParentSession)) return false;
        if (!Objects.equals(mChildSessions, session.mChildSessions)) return false;
        return Objects.equals(mOwnerInfo, session.mOwnerInfo);
    }

    @Override
    public String toString() {
        Session sessionToPrint = this;
        if (getParentSession() != null && isStartedFromActiveSession()) {
            // Log.startSession was called from within another active session. Use the parent's
            // Id instead of the child to reduce confusion.
            sessionToPrint = getRootSession("toString");
        }
        StringBuilder methodName = new StringBuilder();
        methodName.append(sessionToPrint.getFullMethodPath(false /*truncatePath*/));
        if (sessionToPrint.getOwnerInfo() != null && !sessionToPrint.getOwnerInfo().isEmpty()) {
            methodName.append("(");
            methodName.append(sessionToPrint.getOwnerInfo());
            methodName.append(")");
        }
        return methodName.toString() + "@" + sessionToPrint.getFullSessionId();
    }
}
