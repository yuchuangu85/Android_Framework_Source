/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.LocalLog;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.power.ShutdownThread;
import com.google.android.collect.Lists;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.LinkedList;
import java.util.Objects;

/**
 * Generic connector class for interfacing with a native daemon which uses the
 * {@code libsysutils} FrameworkListener protocol.
 */
final class NativeDaemonConnector implements Runnable, Handler.Callback, Watchdog.Monitor {
    private final static boolean VDBG = false;

    private final String TAG;

    private String mSocket;
    private OutputStream mOutputStream;
    private LocalLog mLocalLog;

    private volatile boolean mDebug = false;
    private volatile Object mWarnIfHeld;

    private final ResponseQueue mResponseQueue;

    private final PowerManager.WakeLock mWakeLock;

    private final Looper mLooper;

    private INativeDaemonConnectorCallbacks mCallbacks;
    private Handler mCallbackHandler;

    private AtomicInteger mSequenceNumber;

    private static final long DEFAULT_TIMEOUT = 1 * 60 * 1000; /* 1 minute */
    private static final long WARN_EXECUTE_DELAY_MS = 500; /* .5 sec */

    /** Lock held whenever communicating with native daemon. */
    private final Object mDaemonLock = new Object();

    private final int BUFFER_SIZE = 4096;

    NativeDaemonConnector(INativeDaemonConnectorCallbacks callbacks, String socket,
            int responseQueueSize, String logTag, int maxLogSize, PowerManager.WakeLock wl) {
        this(callbacks, socket, responseQueueSize, logTag, maxLogSize, wl,
                FgThread.get().getLooper());
    }

    NativeDaemonConnector(INativeDaemonConnectorCallbacks callbacks, String socket,
            int responseQueueSize, String logTag, int maxLogSize, PowerManager.WakeLock wl,
            Looper looper) {
        mCallbacks = callbacks;
        mSocket = socket;
        mResponseQueue = new ResponseQueue(responseQueueSize);
        mWakeLock = wl;
        if (mWakeLock != null) {
            mWakeLock.setReferenceCounted(true);
        }
        mLooper = looper;
        mSequenceNumber = new AtomicInteger(0);
        TAG = logTag != null ? logTag : "NativeDaemonConnector";
        mLocalLog = new LocalLog(maxLogSize);
    }

    /**
     * Enable Set debugging mode, which causes messages to also be written to both
     * {@link Slog} in addition to internal log.
     */
    public void setDebug(boolean debug) {
        mDebug = debug;
    }

    /**
     * Like SystemClock.uptimeMillis, except truncated to an int so it will fit in a message arg.
     * Inaccurate across 49.7 days of uptime, but only used for debugging.
     */
    private int uptimeMillisInt() {
        return (int) SystemClock.uptimeMillis() & Integer.MAX_VALUE;
    }

    /**
     * Yell loudly if someone tries making future {@link #execute(Command)}
     * calls while holding a lock on the given object.
     */
    public void setWarnIfHeld(Object warnIfHeld) {
        Preconditions.checkState(mWarnIfHeld == null);
        mWarnIfHeld = Objects.requireNonNull(warnIfHeld);
    }

    @Override
    public void run() {
        mCallbackHandler = new Handler(mLooper, this);

        while (true) {
            if (isShuttingDown()) break;
            try {
                listenToSocket();
            } catch (Exception e) {
                loge("Error in NativeDaemonConnector: " + e);
                if (isShuttingDown()) break;
                SystemClock.sleep(5000);
            }
        }
    }

    private static boolean isShuttingDown() {
        String shutdownAct = SystemProperties.get(
            ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
        return shutdownAct != null && shutdownAct.length() > 0;
    }

    @Override
    public boolean handleMessage(Message msg) {
        final String event = (String) msg.obj;
        final int start = uptimeMillisInt();
        final int sent = msg.arg1;
        try {
            if (!mCallbacks.onEvent(msg.what, event, NativeDaemonEvent.unescapeArgs(event))) {
                log(String.format("Unhandled event '%s'", event));
            }
        } catch (Exception e) {
            loge("Error handling '" + event + "': " + e);
        } finally {
            if (mCallbacks.onCheckHoldWakeLock(msg.what) && mWakeLock != null) {
                mWakeLock.release();
            }
            final int end = uptimeMillisInt();
            if (start > sent && start - sent > WARN_EXECUTE_DELAY_MS) {
                loge(String.format("NDC event {%s} processed too late: %dms", event, start - sent));
            }
            if (end > start && end - start > WARN_EXECUTE_DELAY_MS) {
                loge(String.format("NDC event {%s} took too long: %dms", event, end - start));
            }
        }
        return true;
    }

    private LocalSocketAddress determineSocketAddress() {
        // If we're testing, set up a socket in a namespace that's accessible to test code.
        // In order to ensure that unprivileged apps aren't able to impersonate native daemons on
        // production devices, even if said native daemons ill-advisedly pick a socket name that
        // starts with __test__, only allow this on debug builds.
        if (mSocket.startsWith("__test__") && Build.IS_DEBUGGABLE) {
            return new LocalSocketAddress(mSocket);
        } else {
            return new LocalSocketAddress(mSocket, LocalSocketAddress.Namespace.RESERVED);
        }
    }

    private void listenToSocket() throws IOException {
        LocalSocket socket = null;

        try {
            socket = new LocalSocket();
            LocalSocketAddress address = determineSocketAddress();

            socket.connect(address);

            InputStream inputStream = socket.getInputStream();
            synchronized (mDaemonLock) {
                mOutputStream = socket.getOutputStream();
            }

            mCallbacks.onDaemonConnected();

            FileDescriptor[] fdList = null;
            byte[] buffer = new byte[BUFFER_SIZE];
            int start = 0;

            while (true) {
                int count = inputStream.read(buffer, start, BUFFER_SIZE - start);
                if (count < 0) {
                    loge("got " + count + " reading with start = " + start);
                    break;
                }
                fdList = socket.getAncillaryFileDescriptors();

                // Add our starting point to the count and reset the start.
                count += start;
                start = 0;

                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        // Note - do not log this raw message since it may contain
                        // sensitive data
                        final String rawEvent = new String(
                                buffer, start, i - start, StandardCharsets.UTF_8);

                        boolean releaseWl = false;
                        try {
                            final NativeDaemonEvent event =
                                    NativeDaemonEvent.parseRawEvent(rawEvent, fdList);

                            log("RCV <- {" + event + "}");

                            if (event.isClassUnsolicited()) {
                                // TODO: migrate to sending NativeDaemonEvent instances
                                if (mCallbacks.onCheckHoldWakeLock(event.getCode())
                                        && mWakeLock != null) {
                                    mWakeLock.acquire();
                                    releaseWl = true;
                                }
                                Message msg = mCallbackHandler.obtainMessage(
                                        event.getCode(), uptimeMillisInt(), 0, event.getRawEvent());
                                if (mCallbackHandler.sendMessage(msg)) {
                                    releaseWl = false;
                                }
                            } else {
                                mResponseQueue.add(event.getCmdNumber(), event);
                            }
                        } catch (IllegalArgumentException e) {
                            log("Problem parsing message " + e);
                        } finally {
                            if (releaseWl) {
                                mWakeLock.release();
                            }
                        }

                        start = i + 1;
                    }
                }

                if (start == 0) {
                    log("RCV incomplete");
                }

                // We should end at the amount we read. If not, compact then
                // buffer and read again.
                if (start != count) {
                    final int remaining = BUFFER_SIZE - start;
                    System.arraycopy(buffer, start, buffer, 0, remaining);
                    start = remaining;
                } else {
                    start = 0;
                }
            }
        } catch (IOException ex) {
            loge("Communications error: " + ex);
            throw ex;
        } finally {
            synchronized (mDaemonLock) {
                if (mOutputStream != null) {
                    try {
                        loge("closing stream for " + mSocket);
                        mOutputStream.close();
                    } catch (IOException e) {
                        loge("Failed closing output stream: " + e);
                    }
                    mOutputStream = null;
                }
            }

            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ex) {
                loge("Failed closing socket: " + ex);
            }
        }
    }

    /**
     * Wrapper around argument that indicates it's sensitive and shouldn't be
     * logged.
     */
    public static class SensitiveArg {
        private final Object mArg;

        public SensitiveArg(Object arg) {
            mArg = arg;
        }

        @Override
        public String toString() {
            return String.valueOf(mArg);
        }
    }

    /**
     * Make command for daemon, escaping arguments as needed.
     */
    @VisibleForTesting
    static void makeCommand(StringBuilder rawBuilder, StringBuilder logBuilder, int sequenceNumber,
            String cmd, Object... args) {
        if (cmd.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unexpected command: " + cmd);
        }
        if (cmd.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Arguments must be separate from command");
        }

        rawBuilder.append(sequenceNumber).append(' ').append(cmd);
        logBuilder.append(sequenceNumber).append(' ').append(cmd);
        for (Object arg : args) {
            final String argString = String.valueOf(arg);
            if (argString.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }

            rawBuilder.append(' ');
            logBuilder.append(' ');

            appendEscaped(rawBuilder, argString);
            if (arg instanceof SensitiveArg) {
                logBuilder.append("[scrubbed]");
            } else {
                appendEscaped(logBuilder, argString);
            }
        }

        rawBuilder.append('\0');
    }

    /**
     * Method that waits until all asychronous notifications sent by the native daemon have
     * been processed. This method must not be called on the notification thread or an
     * exception will be thrown.
     */
    public void waitForCallbacks() {
        if (Thread.currentThread() == mLooper.getThread()) {
            throw new IllegalStateException("Must not call this method on callback thread");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        mCallbackHandler.post(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Slog.wtf(TAG, "Interrupted while waiting for unsolicited response handling", e);
        }
    }

    /**
     * Issue the given command to the native daemon and return a single expected
     * response.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent execute(Command cmd) throws NativeDaemonConnectorException {
        return execute(cmd.mCmd, cmd.mArguments.toArray());
    }

    /**
     * Issue the given command to the native daemon and return a single expected
     * response. Any arguments must be separated from base command so they can
     * be properly escaped.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent execute(String cmd, Object... args)
            throws NativeDaemonConnectorException {
        return execute(DEFAULT_TIMEOUT, cmd, args);
    }

    public NativeDaemonEvent execute(long timeoutMs, String cmd, Object... args)
            throws NativeDaemonConnectorException {
        final NativeDaemonEvent[] events = executeForList(timeoutMs, cmd, args);
        if (events.length != 1) {
            throw new NativeDaemonConnectorException(
                    "Expected exactly one response, but received " + events.length);
        }
        return events[0];
    }

    /**
     * Issue the given command to the native daemon and return any
     * {@link NativeDaemonEvent#isClassContinue()} responses, including the
     * final terminal response.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent[] executeForList(Command cmd) throws NativeDaemonConnectorException {
        return executeForList(cmd.mCmd, cmd.mArguments.toArray());
    }

    /**
     * Issue the given command to the native daemon and return any
     * {@link NativeDaemonEvent#isClassContinue()} responses, including the
     * final terminal response. Any arguments must be separated from base
     * command so they can be properly escaped.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent[] executeForList(String cmd, Object... args)
            throws NativeDaemonConnectorException {
        return executeForList(DEFAULT_TIMEOUT, cmd, args);
    }

    /**
     * Issue the given command to the native daemon and return any {@linke
     * NativeDaemonEvent@isClassContinue()} responses, including the final
     * terminal response. Note that the timeout does not count time in deep
     * sleep. Any arguments must be separated from base command so they can be
     * properly escaped.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent[] executeForList(long timeoutMs, String cmd, Object... args)
            throws NativeDaemonConnectorException {
        if (mWarnIfHeld != null && Thread.holdsLock(mWarnIfHeld)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding 0x"
                    + Integer.toHexString(System.identityHashCode(mWarnIfHeld)), new Throwable());
        }

        final long startTime = SystemClock.elapsedRealtime();

        final ArrayList<NativeDaemonEvent> events = Lists.newArrayList();

        final StringBuilder rawBuilder = new StringBuilder();
        final StringBuilder logBuilder = new StringBuilder();
        final int sequenceNumber = mSequenceNumber.incrementAndGet();

        makeCommand(rawBuilder, logBuilder, sequenceNumber, cmd, args);

        final String rawCmd = rawBuilder.toString();
        final String logCmd = logBuilder.toString();

        log("SND -> {" + logCmd + "}");

        synchronized (mDaemonLock) {
            if (mOutputStream == null) {
                throw new NativeDaemonConnectorException("missing output stream");
            } else {
                try {
                    mOutputStream.write(rawCmd.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new NativeDaemonConnectorException("problem sending command", e);
                }
            }
        }

        NativeDaemonEvent event = null;
        do {
            event = mResponseQueue.remove(sequenceNumber, timeoutMs, logCmd);
            if (event == null) {
                loge("timed-out waiting for response to " + logCmd);
                throw new NativeDaemonTimeoutException(logCmd, event);
            }
            if (VDBG) log("RMV <- {" + event + "}");
            events.add(event);
        } while (event.isClassContinue());

        final long endTime = SystemClock.elapsedRealtime();
        if (endTime - startTime > WARN_EXECUTE_DELAY_MS) {
            loge("NDC Command {" + logCmd + "} took too long (" + (endTime - startTime) + "ms)");
        }

        if (event.isClassClientError()) {
            throw new NativeDaemonArgumentException(logCmd, event);
        }
        if (event.isClassServerError()) {
            throw new NativeDaemonFailureException(logCmd, event);
        }

        return events.toArray(new NativeDaemonEvent[events.size()]);
    }

    /**
     * Append the given argument to {@link StringBuilder}, escaping as needed,
     * and surrounding with quotes when it contains spaces.
     */
    @VisibleForTesting
    static void appendEscaped(StringBuilder builder, String arg) {
        final boolean hasSpaces = arg.indexOf(' ') >= 0;
        if (hasSpaces) {
            builder.append('"');
        }

        final int length = arg.length();
        for (int i = 0; i < length; i++) {
            final char c = arg.charAt(i);

            if (c == '"') {
                builder.append("\\\"");
            } else if (c == '\\') {
                builder.append("\\\\");
            } else {
                builder.append(c);
            }
        }

        if (hasSpaces) {
            builder.append('"');
        }
    }

    private static class NativeDaemonArgumentException extends NativeDaemonConnectorException {
        public NativeDaemonArgumentException(String command, NativeDaemonEvent event) {
            super(command, event);
        }

        @Override
        public IllegalArgumentException rethrowAsParcelableException() {
            throw new IllegalArgumentException(getMessage(), this);
        }
    }

    private static class NativeDaemonFailureException extends NativeDaemonConnectorException {
        public NativeDaemonFailureException(String command, NativeDaemonEvent event) {
            super(command, event);
        }
    }

    /**
     * Command builder that handles argument list building. Any arguments must
     * be separated from base command so they can be properly escaped.
     */
    public static class Command {
        private String mCmd;
        private ArrayList<Object> mArguments = Lists.newArrayList();

        public Command(String cmd, Object... args) {
            mCmd = cmd;
            for (Object arg : args) {
                appendArg(arg);
            }
        }

        public Command appendArg(Object arg) {
            mArguments.add(arg);
            return this;
        }
    }

    /** {@inheritDoc} */
    public void monitor() {
        synchronized (mDaemonLock) { }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mLocalLog.dump(fd, pw, args);
        pw.println();
        mResponseQueue.dump(fd, pw, args);
    }

    private void log(String logstring) {
        if (mDebug) Slog.d(TAG, logstring);
        mLocalLog.log(logstring);
    }

    private void loge(String logstring) {
        Slog.e(TAG, logstring);
        mLocalLog.log(logstring);
    }

    private static class ResponseQueue {

        private static class PendingCmd {
            public final int cmdNum;
            public final String logCmd;

            public BlockingQueue<NativeDaemonEvent> responses =
                    new ArrayBlockingQueue<NativeDaemonEvent>(10);

            // The availableResponseCount member is used to track when we can remove this
            // instance from the ResponseQueue.
            // This is used under the protection of a sync of the mPendingCmds object.
            // A positive value means we've had more writers retreive this object while
            // a negative value means we've had more readers.  When we've had an equal number
            // (it goes to zero) we can remove this object from the mPendingCmds list.
            // Note that we may have more responses for this command (and more readers
            // coming), but that would result in a new PendingCmd instance being created
            // and added with the same cmdNum.
            // Also note that when this goes to zero it just means a parity of readers and
            // writers have retrieved this object - not that they are done using it.  The
            // responses queue may well have more responses yet to be read or may get more
            // responses added to it.  But all those readers/writers have retreived and
            // hold references to this instance already so it can be removed from
            // mPendingCmds queue.
            public int availableResponseCount;

            public PendingCmd(int cmdNum, String logCmd) {
                this.cmdNum = cmdNum;
                this.logCmd = logCmd;
            }
        }

        private final LinkedList<PendingCmd> mPendingCmds;
        private int mMaxCount;

        ResponseQueue(int maxCount) {
            mPendingCmds = new LinkedList<PendingCmd>();
            mMaxCount = maxCount;
        }

        public void add(int cmdNum, NativeDaemonEvent response) {
            PendingCmd found = null;
            synchronized (mPendingCmds) {
                for (PendingCmd pendingCmd : mPendingCmds) {
                    if (pendingCmd.cmdNum == cmdNum) {
                        found = pendingCmd;
                        break;
                    }
                }
                if (found == null) {
                    // didn't find it - make sure our queue isn't too big before adding
                    while (mPendingCmds.size() >= mMaxCount) {
                        Slog.e("NativeDaemonConnector.ResponseQueue",
                                "more buffered than allowed: " + mPendingCmds.size() +
                                " >= " + mMaxCount);
                        // let any waiter timeout waiting for this
                        PendingCmd pendingCmd = mPendingCmds.remove();
                        Slog.e("NativeDaemonConnector.ResponseQueue",
                                "Removing request: " + pendingCmd.logCmd + " (" +
                                pendingCmd.cmdNum + ")");
                    }
                    found = new PendingCmd(cmdNum, null);
                    mPendingCmds.add(found);
                }
                found.availableResponseCount++;
                // if a matching remove call has already retrieved this we can remove this
                // instance from our list
                if (found.availableResponseCount == 0) mPendingCmds.remove(found);
            }
            try {
                found.responses.put(response);
            } catch (InterruptedException e) { }
        }

        // note that the timeout does not count time in deep sleep.  If you don't want
        // the device to sleep, hold a wakelock
        public NativeDaemonEvent remove(int cmdNum, long timeoutMs, String logCmd) {
            PendingCmd found = null;
            synchronized (mPendingCmds) {
                for (PendingCmd pendingCmd : mPendingCmds) {
                    if (pendingCmd.cmdNum == cmdNum) {
                        found = pendingCmd;
                        break;
                    }
                }
                if (found == null) {
                    found = new PendingCmd(cmdNum, logCmd);
                    mPendingCmds.add(found);
                }
                found.availableResponseCount--;
                // if a matching add call has already retrieved this we can remove this
                // instance from our list
                if (found.availableResponseCount == 0) mPendingCmds.remove(found);
            }
            NativeDaemonEvent result = null;
            try {
                result = found.responses.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
            if (result == null) {
                Slog.e("NativeDaemonConnector.ResponseQueue", "Timeout waiting for response");
            }
            return result;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("Pending requests:");
            synchronized (mPendingCmds) {
                for (PendingCmd pendingCmd : mPendingCmds) {
                    pw.println("  Cmd " + pendingCmd.cmdNum + " - " + pendingCmd.logCmd);
                }
            }
        }
    }
}
