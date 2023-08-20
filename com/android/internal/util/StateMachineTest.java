/**
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.util;

import android.os.Debug;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.TestLooperManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.StateMachine.LogRec;

import junit.framework.TestCase;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Iterator;

/**
 * Test for StateMachine.
 */
public class StateMachineTest extends TestCase {
    private static final String ENTER = "enter";
    private static final String EXIT = "exit";
    private static final String ON_QUITTING = "ON_QUITTING";

    private static final int TEST_CMD_1 = 1;
    private static final int TEST_CMD_2 = 2;
    private static final int TEST_CMD_3 = 3;
    private static final int TEST_CMD_4 = 4;
    private static final int TEST_CMD_5 = 5;
    private static final int TEST_CMD_6 = 6;

    private static final boolean DBG = true;
    private static final boolean WAIT_FOR_DEBUGGER = false;
    private static final String TAG = "StateMachineTest";

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch(InterruptedException e) {
        }
    }

    private void dumpLogRecs(StateMachine sm) {
        int size = sm.getLogRecSize();
        tlog("size=" + size + " count=" + sm.getLogRecCount());
        for (int i = 0; i < size; i++) {
            LogRec lr = sm.getLogRec(i);
            tlog(lr.toString());
        }
    }

    private void dumpLogRecs(Collection<LogRec> clr) {
        int size = clr.size();
        tlog("size=" + size);
        for (LogRec lr : clr) {
            tlog(lr.toString());
        }
    }

    /**
     * Tests {@link StateMachine#toString()}.
     */
    class StateMachineToStringTest extends StateMachine {
        StateMachineToStringTest(String name) {
            super(name);
        }
    }

    class ExampleState extends State {
        String mName;

        ExampleState(String name) {
            mName = name;
        }

        @Override
        public String getName() {
            return mName;
        }
    }

    @SmallTest
    public void testToStringSucceedsEvenIfMachineHasNoStates() throws Exception {
        StateMachine stateMachine = new StateMachineToStringTest("TestStateMachine");
        assertTrue(stateMachine.toString().contains("TestStateMachine"));
    }

    @SmallTest
    public void testToStringSucceedsEvenIfStateHasNoName() throws Exception {
        StateMachine stateMachine = new StateMachineToStringTest("TestStateMachine");
        State exampleState = new ExampleState(null);
        stateMachine.addState(exampleState);
        stateMachine.setInitialState(exampleState);
        stateMachine.start();
        assertTrue(stateMachine.toString().contains("TestStateMachine"));
        assertTrue(stateMachine.toString().contains("(null)"));
    }

    @SmallTest
    public void testToStringIncludesMachineAndStateNames() throws Exception {
        StateMachine stateMachine = new StateMachineToStringTest("TestStateMachine");
        State exampleState = new ExampleState("exampleState");
        stateMachine.addState(exampleState);
        stateMachine.setInitialState(exampleState);
        stateMachine.start();
        assertTrue(stateMachine.toString().contains("TestStateMachine"));
        assertTrue(stateMachine.toString().contains("exampleState"));
    }

    @SmallTest
    public void testToStringDoesNotContainMultipleLines() throws Exception {
        StateMachine stateMachine = new StateMachineToStringTest("TestStateMachine");
        State exampleState = new ExampleState("exampleState");
        stateMachine.addState(exampleState);
        stateMachine.setInitialState(exampleState);
        stateMachine.start();
        assertFalse(stateMachine.toString().contains("\n"));
    }

    /**
     * Tests {@link StateMachine#quit()}.
     */
    class StateMachineQuitTest extends StateMachine {
        Collection<LogRec> mLogRecs;

        StateMachineQuitTest(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        @Override
        public void onQuitting() {
            tlog("onQuitting");
            addLogRec(ON_QUITTING);
            mLogRecs = mThisSm.copyLogRecs();
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        class S1 extends State {
            @Override
            public void exit() {
                tlog("S1.exit");
                addLogRec(EXIT);
            }
            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    // Sleep and assume the other messages will be queued up.
                    case TEST_CMD_1: {
                        tlog("TEST_CMD_1");
                        sleep(500);
                        quit();
                        break;
                    }
                    default: {
                        tlog("default what=" + message.what);
                        break;
                    }
                }
                return HANDLED;
            }
        }

        private StateMachineQuitTest mThisSm;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachineQuit() throws Exception {
        if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachineQuitTest smQuitTest = new StateMachineQuitTest("smQuitTest");
        smQuitTest.start();
        if (smQuitTest.isDbg()) tlog("testStateMachineQuit E");

        synchronized (smQuitTest) {

            // Send 6 message we'll quit on the first but all 6 should be processed before quitting.
            for (int i = 1; i <= 6; i++) {
                smQuitTest.sendMessage(smQuitTest.obtainMessage(i));
            }

            try {
                // wait for the messages to be handled
                smQuitTest.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachineQuit: exception while waiting " + e.getMessage());
            }
        }

        dumpLogRecs(smQuitTest.mLogRecs);
        assertEquals(8, smQuitTest.mLogRecs.size());

        LogRec lr;
        Iterator<LogRec> itr = smQuitTest.mLogRecs.iterator();
        for (int i = 1; i <= 6; i++) {
            lr = itr.next();
            assertEquals(i, lr.getWhat());
            assertEquals(smQuitTest.mS1, lr.getState());
            assertEquals(smQuitTest.mS1, lr.getOriginalState());
        }
        lr = itr.next();
        assertEquals(EXIT, lr.getInfo());
        assertEquals(smQuitTest.mS1, lr.getState());

        lr = itr.next();
        assertEquals(ON_QUITTING, lr.getInfo());

        if (smQuitTest.isDbg()) tlog("testStateMachineQuit X");
    }

    /**
     * Tests {@link StateMachine#quitNow()}
     */
    class StateMachineQuitNowTest extends StateMachine {
        public Collection<LogRec> mLogRecs = null;

        StateMachineQuitNowTest(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        @Override
        public void onQuitting() {
            tlog("onQuitting");
            addLogRec(ON_QUITTING);
            // Get a copy of the log records since we're quitting and they will disappear
            mLogRecs = mThisSm.copyLogRecs();

            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        class S1 extends State {
            @Override
            public void exit() {
                tlog("S1.exit");
                addLogRec(EXIT);
            }
            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    // Sleep and assume the other messages will be queued up.
                    case TEST_CMD_1: {
                        tlog("TEST_CMD_1");
                        sleep(500);
                        quitNow();
                        break;
                    }
                    default: {
                        tlog("default what=" + message.what);
                        break;
                    }
                }
                return HANDLED;
            }
        }

        private StateMachineQuitNowTest mThisSm;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachineQuitNow() throws Exception {
        if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachineQuitNowTest smQuitNowTest = new StateMachineQuitNowTest("smQuitNowTest");
        smQuitNowTest.start();
        if (smQuitNowTest.isDbg()) tlog("testStateMachineQuitNow E");

        synchronized (smQuitNowTest) {

            // Send 6 message we'll QuitNow on the first even though
            // we send 6 only one will be processed.
            for (int i = 1; i <= 6; i++) {
                smQuitNowTest.sendMessage(smQuitNowTest.obtainMessage(i));
            }

            try {
                // wait for the messages to be handled
                smQuitNowTest.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachineQuitNow: exception while waiting " + e.getMessage());
            }
        }

        tlog("testStateMachineQuiteNow: logRecs=" + smQuitNowTest.mLogRecs);
        assertEquals(3, smQuitNowTest.mLogRecs.size());

        Iterator<LogRec> itr = smQuitNowTest.mLogRecs.iterator();
        LogRec lr = itr.next();
        assertEquals(1, lr.getWhat());
        assertEquals(smQuitNowTest.mS1, lr.getState());
        assertEquals(smQuitNowTest.mS1, lr.getOriginalState());

        lr = itr.next();
        assertEquals(EXIT, lr.getInfo());
        assertEquals(smQuitNowTest.mS1, lr.getState());

        lr = itr.next();
        assertEquals(ON_QUITTING, lr.getInfo());

        if (smQuitNowTest.isDbg()) tlog("testStateMachineQuitNow X");
    }

    /**
     * Tests {@link StateMachine#quitNow()} immediately after {@link StateMachine#start()}.
     */
    class StateMachineQuitNowAfterStartTest extends StateMachine {
        Collection<LogRec> mLogRecs;

        StateMachineQuitNowAfterStartTest(String name, Looper looper) {
            super(name, looper);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        @Override
        public void onQuitting() {
            tlog("onQuitting");
            addLogRec(ON_QUITTING);
            mLogRecs = mThisSm.copyLogRecs();
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        class S1 extends State {
            @Override
            public void enter() {
                tlog("S1.enter");
                addLogRec(ENTER);
            }
            @Override
            public void exit() {
                tlog("S1.exit");
                addLogRec(EXIT);
            }
            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    // Sleep and assume the other messages will be queued up.
                    case TEST_CMD_1: {
                        tlog("TEST_CMD_1");
                        sleep(500);
                        break;
                    }
                    default: {
                        tlog("default what=" + message.what);
                        break;
                    }
                }
                return HANDLED;
            }
        }

        private StateMachineQuitNowAfterStartTest mThisSm;
        private S1 mS1 = new S1();
    }

    /**
     * When quitNow() is called immediately after start(), the QUIT_CMD gets processed
     * before the INIT_CMD. This test ensures that the StateMachine can gracefully handle
     * this sequencing of messages (QUIT before INIT).
     */
    @SmallTest
    public void testStateMachineQuitNowAfterStart() throws Exception {
        if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        HandlerThread mThread = new HandlerThread("testStateMachineQuitNowAfterStart");
        mThread.start();

        Looper mLooper = mThread.getLooper();
        TestLooperManager looperManager = InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mLooper);
        StateMachineQuitNowAfterStartTest smQuitNowAfterStartTest =
                new StateMachineQuitNowAfterStartTest("smQuitNowAfterStartTest", mLooper);
        synchronized(smQuitNowAfterStartTest) {
            smQuitNowAfterStartTest.start();
            smQuitNowAfterStartTest.quitNow();
            if (smQuitNowAfterStartTest.isDbg()) tlog("testStateMachineQuitNowAfterStart E");

            looperManager.release();
            smQuitNowAfterStartTest.wait();
        }
        dumpLogRecs(smQuitNowAfterStartTest.mLogRecs);
        assertEquals(2, smQuitNowAfterStartTest.mLogRecs.size());

        LogRec lr;
        Iterator<LogRec> itr = smQuitNowAfterStartTest.mLogRecs.iterator();
        lr = itr.next();
        assertEquals(EXIT, lr.getInfo());
        assertEquals(smQuitNowAfterStartTest.mS1, lr.getState());

        lr = itr.next();
        assertEquals(ON_QUITTING, lr.getInfo());

        if (smQuitNowAfterStartTest.isDbg()) tlog("testStateMachineQuitNowAfterStart X");
    }

    /**
     * Test enter/exit can use transitionTo
     */
    class StateMachineEnterExitTransitionToTest extends StateMachine {

        StateMachineEnterExitTransitionToTest(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);
            addState(mS2);
            addState(mS3);
            addState(mS4);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public void enter() {
                // Test transitions in enter on the initial state work
                addLogRec(ENTER);
                transitionTo(mS2);
                tlog("S1.enter");
            }
            @Override
            public void exit() {
                addLogRec(EXIT);
                tlog("S1.exit");
            }
        }

        class S2 extends State {
            @Override
            public void enter() {
                addLogRec(ENTER);
                tlog("S2.enter");
            }
            @Override
            public void exit() {
                // Test transition in exit work
                transitionTo(mS4);

                assertEquals(TEST_CMD_1, getCurrentMessage().what);
                addLogRec(EXIT);

                tlog("S2.exit");
            }
            @Override
            public boolean processMessage(Message message) {
                // Start a transition to S3 but it will be
                // changed to a transition to S4 in exit
                transitionTo(mS3);
                tlog("S2.processMessage");
                return HANDLED;
            }
        }

        class S3 extends State {
            @Override
            public void enter() {
                addLogRec(ENTER);
                tlog("S3.enter");
            }
            @Override
            public void exit() {
                addLogRec(EXIT);
                tlog("S3.exit");
            }
        }

        class S4 extends State {
            @Override
            public void enter() {
                addLogRec(ENTER);
                // Test that we can do halting in an enter/exit
                transitionToHaltingState();
                tlog("S4.enter");
            }
            @Override
            public void exit() {
                addLogRec(EXIT);
                tlog("S4.exit");
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachineEnterExitTransitionToTest mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();
        private S3 mS3 = new S3();
        private S4 mS4 = new S4();
    }

    @SmallTest
    public void testStateMachineEnterExitTransitionToTest() throws Exception {
        //if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachineEnterExitTransitionToTest smEnterExitTransitionToTest =
                new StateMachineEnterExitTransitionToTest("smEnterExitTransitionToTest");
        smEnterExitTransitionToTest.start();
        if (smEnterExitTransitionToTest.isDbg()) {
            tlog("testStateMachineEnterExitTransitionToTest E");
        }

        synchronized (smEnterExitTransitionToTest) {
            smEnterExitTransitionToTest.sendMessage(TEST_CMD_1);

            try {
                // wait for the messages to be handled
                smEnterExitTransitionToTest.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachineEnterExitTransitionToTest: exception while waiting "
                    + e.getMessage());
            }
        }

        dumpLogRecs(smEnterExitTransitionToTest);

        assertEquals(9, smEnterExitTransitionToTest.getLogRecCount());
        LogRec lr;

        lr = smEnterExitTransitionToTest.getLogRec(0);
        assertEquals(ENTER, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS1, lr.getState());

        lr = smEnterExitTransitionToTest.getLogRec(1);
        assertEquals(EXIT, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS1, lr.getState());

        lr = smEnterExitTransitionToTest.getLogRec(2);
        assertEquals(ENTER, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS2, lr.getState());

        lr = smEnterExitTransitionToTest.getLogRec(3);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(smEnterExitTransitionToTest.mS2, lr.getState());
        assertEquals(smEnterExitTransitionToTest.mS2, lr.getOriginalState());
        assertEquals(smEnterExitTransitionToTest.mS3, lr.getDestState());

        lr = smEnterExitTransitionToTest.getLogRec(4);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(smEnterExitTransitionToTest.mS2, lr.getState());
        assertEquals(smEnterExitTransitionToTest.mS2, lr.getOriginalState());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getDestState());
        assertEquals(EXIT, lr.getInfo());

        lr = smEnterExitTransitionToTest.getLogRec(5);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(ENTER, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS3, lr.getState());
        assertEquals(smEnterExitTransitionToTest.mS3, lr.getOriginalState());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getDestState());

        lr = smEnterExitTransitionToTest.getLogRec(6);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(EXIT, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS3, lr.getState());
        assertEquals(smEnterExitTransitionToTest.mS3, lr.getOriginalState());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getDestState());

        lr = smEnterExitTransitionToTest.getLogRec(7);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(ENTER, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getState());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getOriginalState());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getDestState());

        lr = smEnterExitTransitionToTest.getLogRec(8);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(EXIT, lr.getInfo());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getState());
        assertEquals(smEnterExitTransitionToTest.mS4, lr.getOriginalState());

        if (smEnterExitTransitionToTest.isDbg()) {
            tlog("testStateMachineEnterExitTransitionToTest X");
        }
    }

    /**
     * Tests that ProcessedMessage works as a circular buffer.
     */
    class StateMachine0 extends StateMachine {
        StateMachine0(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);
            setLogRecSize(3);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_6) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine0 mThisSm;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachine0() throws Exception {
        //if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();

        StateMachine0 sm0 = new StateMachine0("sm0");
        sm0.start();
        if (sm0.isDbg()) tlog("testStateMachine0 E");

        synchronized (sm0) {
            // Send 6 messages
            for (int i = 1; i <= 6; i++) {
                sm0.sendMessage(sm0.obtainMessage(i));
            }

            try {
                // wait for the messages to be handled
                sm0.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine0: exception while waiting " + e.getMessage());
            }
        }

        assertEquals(6, sm0.getLogRecCount());
        assertEquals(3, sm0.getLogRecSize());

        dumpLogRecs(sm0);

        LogRec lr;
        lr = sm0.getLogRec(0);
        assertEquals(TEST_CMD_4, lr.getWhat());
        assertEquals(sm0.mS1, lr.getState());
        assertEquals(sm0.mS1, lr.getOriginalState());

        lr = sm0.getLogRec(1);
        assertEquals(TEST_CMD_5, lr.getWhat());
        assertEquals(sm0.mS1, lr.getState());
        assertEquals(sm0.mS1, lr.getOriginalState());

        lr = sm0.getLogRec(2);
        assertEquals(TEST_CMD_6, lr.getWhat());
        assertEquals(sm0.mS1, lr.getState());
        assertEquals(sm0.mS1, lr.getOriginalState());

        if (sm0.isDbg()) tlog("testStateMachine0 X");
    }

    /**
     * This tests enter/exit and transitions to the same state.
     * The state machine has one state, it receives two messages
     * in state mS1. With the first message it transitions to
     * itself which causes it to be exited and reentered.
     */
    class StateMachine1 extends StateMachine {
        StateMachine1(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) tlog("StateMachine1: ctor X");
        }

        class S1 extends State {
            @Override
            public void enter() {
                mEnterCount++;
            }
            @Override
            public void exit() {
                mExitCount++;
            }
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_1) {
                    assertEquals(1, mEnterCount);
                    assertEquals(0, mExitCount);
                    transitionTo(mS1);
                } else if (message.what == TEST_CMD_2) {
                    assertEquals(2, mEnterCount);
                    assertEquals(1, mExitCount);
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine1 mThisSm;
        private S1 mS1 = new S1();

        private int mEnterCount;
        private int mExitCount;
    }

    @MediumTest
    public void testStateMachine1() throws Exception {
        StateMachine1 sm1 = new StateMachine1("sm1");
        sm1.start();
        if (sm1.isDbg()) tlog("testStateMachine1 E");

        synchronized (sm1) {
            // Send two messages
            sm1.sendMessage(TEST_CMD_1);
            sm1.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm1.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine1: exception while waiting " + e.getMessage());
            }
        }

        assertEquals(2, sm1.mEnterCount);
        assertEquals(2, sm1.mExitCount);

        assertEquals(2, sm1.getLogRecSize());

        LogRec lr;
        lr = sm1.getLogRec(0);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(sm1.mS1, lr.getState());
        assertEquals(sm1.mS1, lr.getOriginalState());

        lr = sm1.getLogRec(1);
        assertEquals(TEST_CMD_2, lr.getWhat());
        assertEquals(sm1.mS1, lr.getState());
        assertEquals(sm1.mS1, lr.getOriginalState());

        assertEquals(2, sm1.mEnterCount);
        assertEquals(2, sm1.mExitCount);

        if (sm1.isDbg()) tlog("testStateMachine1 X");
    }

    /**
     * Test deferring messages and states with no parents. The state machine
     * has two states, it receives two messages in state mS1 deferring them
     * until what == TEST_CMD_2 and then transitions to state mS2. State
     * mS2 then receives both of the deferred messages first TEST_CMD_1 and
     * then TEST_CMD_2.
     */
    class StateMachine2 extends StateMachine {
        StateMachine2(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup the hierarchy
            addState(mS1);
            addState(mS2);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) tlog("StateMachine2: ctor X");
        }

        class S1 extends State {
            @Override
            public void enter() {
                mDidEnter = true;
            }
            @Override
            public void exit() {
                mDidExit = true;
            }
            @Override
            public boolean processMessage(Message message) {
                deferMessage(message);
                if (message.what == TEST_CMD_2) {
                    transitionTo(mS2);
                }
                return HANDLED;
            }
        }

        class S2 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine2 mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();

        private boolean mDidEnter = false;
        private boolean mDidExit = false;
    }

    @MediumTest
    public void testStateMachine2() throws Exception {
        StateMachine2 sm2 = new StateMachine2("sm2");
        sm2.start();
        if (sm2.isDbg()) tlog("testStateMachine2 E");

        synchronized (sm2) {
            // Send two messages
            sm2.sendMessage(TEST_CMD_1);
            sm2.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm2.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine2: exception while waiting " + e.getMessage());
            }
        }

        assertEquals(4, sm2.getLogRecSize());

        LogRec lr;
        lr = sm2.getLogRec(0);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(sm2.mS1, lr.getState());

        lr = sm2.getLogRec(1);
        assertEquals(TEST_CMD_2, lr.getWhat());
        assertEquals(sm2.mS1, lr.getState());

        lr = sm2.getLogRec(2);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(sm2.mS2, lr.getState());

        lr = sm2.getLogRec(3);
        assertEquals(TEST_CMD_2, lr.getWhat());
        assertEquals(sm2.mS2, lr.getState());

        assertTrue(sm2.mDidEnter);
        assertTrue(sm2.mDidExit);

        if (sm2.isDbg()) tlog("testStateMachine2 X");
    }

    /**
     * Test that unhandled messages in a child are handled by the parent.
     * When TEST_CMD_2 is received.
     */
    class StateMachine3 extends StateMachine {
        StateMachine3(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup the simplest hierarchy of two states
            // mParentState and mChildState.
            // (Use indentation to help visualize hierarchy)
            addState(mParentState);
                addState(mChildState, mParentState);

            // Set the initial state will be the child
            setInitialState(mChildState);
            if (DBG) tlog("StateMachine3: ctor X");
        }

        class ParentState extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        class ChildState extends State {
            @Override
            public boolean processMessage(Message message) {
                return NOT_HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine3 mThisSm;
        private ParentState mParentState = new ParentState();
        private ChildState mChildState = new ChildState();
    }

    @MediumTest
    public void testStateMachine3() throws Exception {
        StateMachine3 sm3 = new StateMachine3("sm3");
        sm3.start();
        if (sm3.isDbg()) tlog("testStateMachine3 E");

        synchronized (sm3) {
            // Send two messages
            sm3.sendMessage(TEST_CMD_1);
            sm3.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm3.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine3: exception while waiting " + e.getMessage());
            }
        }

        assertEquals(2, sm3.getLogRecSize());

        LogRec lr;
        lr = sm3.getLogRec(0);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(sm3.mParentState, lr.getState());
        assertEquals(sm3.mChildState, lr.getOriginalState());

        lr = sm3.getLogRec(1);
        assertEquals(TEST_CMD_2, lr.getWhat());
        assertEquals(sm3.mParentState, lr.getState());
        assertEquals(sm3.mChildState, lr.getOriginalState());

        if (sm3.isDbg()) tlog("testStateMachine3 X");
    }

    /**
     * Test a hierarchy of 3 states a parent and two children
     * with transition from child 1 to child 2 and child 2
     * lets the parent handle the messages.
     */
    class StateMachine4 extends StateMachine {
        StateMachine4(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup a hierarchy of three states
            // mParentState, mChildState1 & mChildState2
            // (Use indentation to help visualize hierarchy)
            addState(mParentState);
                addState(mChildState1, mParentState);
                addState(mChildState2, mParentState);

            // Set the initial state will be child 1
            setInitialState(mChildState1);
            if (DBG) tlog("StateMachine4: ctor X");
        }

        class ParentState extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        class ChildState1 extends State {
            @Override
            public boolean processMessage(Message message) {
                transitionTo(mChildState2);
                return HANDLED;
            }
        }

        class ChildState2 extends State {
            @Override
            public boolean processMessage(Message message) {
                return NOT_HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine4 mThisSm;
        private ParentState mParentState = new ParentState();
        private ChildState1 mChildState1 = new ChildState1();
        private ChildState2 mChildState2 = new ChildState2();
    }

    @MediumTest
    public void testStateMachine4() throws Exception {
        StateMachine4 sm4 = new StateMachine4("sm4");
        sm4.start();
        if (sm4.isDbg()) tlog("testStateMachine4 E");

        synchronized (sm4) {
            // Send two messages
            sm4.sendMessage(TEST_CMD_1);
            sm4.sendMessage(TEST_CMD_2);

            try {
                // wait for the messages to be handled
                sm4.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine4: exception while waiting " + e.getMessage());
            }
        }


        assertEquals(2, sm4.getLogRecSize());

        LogRec lr;
        lr = sm4.getLogRec(0);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(sm4.mChildState1, lr.getState());
        assertEquals(sm4.mChildState1, lr.getOriginalState());

        lr = sm4.getLogRec(1);
        assertEquals(TEST_CMD_2, lr.getWhat());
        assertEquals(sm4.mParentState, lr.getState());
        assertEquals(sm4.mChildState2, lr.getOriginalState());

        if (sm4.isDbg()) tlog("testStateMachine4 X");
    }

    /**
     * Test transition from one child to another of a "complex"
     * hierarchy with two parents and multiple children.
     */
    class StateMachine5 extends StateMachine {
        StateMachine5(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup a hierarchy with two parents and some children.
            // (Use indentation to help visualize hierarchy)
            addState(mParentState1);
                addState(mChildState1, mParentState1);
                addState(mChildState2, mParentState1);

            addState(mParentState2);
                addState(mChildState3, mParentState2);
                addState(mChildState4, mParentState2);
                    addState(mChildState5, mChildState4);

            // Set the initial state will be the child
            setInitialState(mChildState1);
            if (DBG) tlog("StateMachine5: ctor X");
        }

        class ParentState1 extends State {
            @Override
            public void enter() {
                mParentState1EnterCount += 1;
            }
            @Override
            public void exit() {
                mParentState1ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                return HANDLED;
            }
        }

        class ChildState1 extends State {
            @Override
            public void enter() {
                mChildState1EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState1ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(0, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(0, mChildState1ExitCount);
                assertEquals(0, mChildState2EnterCount);
                assertEquals(0, mChildState2ExitCount);
                assertEquals(0, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(0, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(0, mChildState4EnterCount);
                assertEquals(0, mChildState4ExitCount);
                assertEquals(0, mChildState5EnterCount);
                assertEquals(0, mChildState5ExitCount);

                transitionTo(mChildState2);
                return HANDLED;
            }
        }

        class ChildState2 extends State {
            @Override
            public void enter() {
                mChildState2EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState2ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(0, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(0, mChildState2ExitCount);
                assertEquals(0, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(0, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(0, mChildState4EnterCount);
                assertEquals(0, mChildState4ExitCount);
                assertEquals(0, mChildState5EnterCount);
                assertEquals(0, mChildState5ExitCount);

                transitionTo(mChildState5);
                return HANDLED;
            }
        }

        class ParentState2 extends State {
            @Override
            public void enter() {
                mParentState2EnterCount += 1;
            }
            @Override
            public void exit() {
                mParentState2ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(2, mParentState2EnterCount);
                assertEquals(1, mParentState2ExitCount);
                assertEquals(1, mChildState3EnterCount);
                assertEquals(1, mChildState3ExitCount);
                assertEquals(2, mChildState4EnterCount);
                assertEquals(2, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(1, mChildState5ExitCount);

                transitionToHaltingState();
                return HANDLED;
            }
        }

        class ChildState3 extends State {
            @Override
            public void enter() {
                mChildState3EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState3ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(1, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(1, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(1, mChildState4EnterCount);
                assertEquals(1, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(1, mChildState5ExitCount);

                transitionTo(mChildState4);
                return HANDLED;
            }
        }

        class ChildState4 extends State {
            @Override
            public void enter() {
                mChildState4EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState4ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(1, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(1, mChildState3EnterCount);
                assertEquals(1, mChildState3ExitCount);
                assertEquals(2, mChildState4EnterCount);
                assertEquals(1, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(1, mChildState5ExitCount);

                transitionTo(mParentState2);
                return HANDLED;
            }
        }

        class ChildState5 extends State {
            @Override
            public void enter() {
                mChildState5EnterCount += 1;
            }
            @Override
            public void exit() {
                mChildState5ExitCount += 1;
            }
            @Override
            public boolean processMessage(Message message) {
                assertEquals(1, mParentState1EnterCount);
                assertEquals(1, mParentState1ExitCount);
                assertEquals(1, mChildState1EnterCount);
                assertEquals(1, mChildState1ExitCount);
                assertEquals(1, mChildState2EnterCount);
                assertEquals(1, mChildState2ExitCount);
                assertEquals(1, mParentState2EnterCount);
                assertEquals(0, mParentState2ExitCount);
                assertEquals(0, mChildState3EnterCount);
                assertEquals(0, mChildState3ExitCount);
                assertEquals(1, mChildState4EnterCount);
                assertEquals(0, mChildState4ExitCount);
                assertEquals(1, mChildState5EnterCount);
                assertEquals(0, mChildState5ExitCount);

                transitionTo(mChildState3);
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine5 mThisSm;
        private ParentState1 mParentState1 = new ParentState1();
        private ChildState1 mChildState1 = new ChildState1();
        private ChildState2 mChildState2 = new ChildState2();
        private ParentState2 mParentState2 = new ParentState2();
        private ChildState3 mChildState3 = new ChildState3();
        private ChildState4 mChildState4 = new ChildState4();
        private ChildState5 mChildState5 = new ChildState5();

        private int mParentState1EnterCount = 0;
        private int mParentState1ExitCount = 0;
        private int mChildState1EnterCount = 0;
        private int mChildState1ExitCount = 0;
        private int mChildState2EnterCount = 0;
        private int mChildState2ExitCount = 0;
        private int mParentState2EnterCount = 0;
        private int mParentState2ExitCount = 0;
        private int mChildState3EnterCount = 0;
        private int mChildState3ExitCount = 0;
        private int mChildState4EnterCount = 0;
        private int mChildState4ExitCount = 0;
        private int mChildState5EnterCount = 0;
        private int mChildState5ExitCount = 0;
    }

    @MediumTest
    public void testStateMachine5() throws Exception {
        StateMachine5 sm5 = new StateMachine5("sm5");
        sm5.start();
        if (sm5.isDbg()) tlog("testStateMachine5 E");

        synchronized (sm5) {
            // Send 6 messages
            sm5.sendMessage(TEST_CMD_1);
            sm5.sendMessage(TEST_CMD_2);
            sm5.sendMessage(TEST_CMD_3);
            sm5.sendMessage(TEST_CMD_4);
            sm5.sendMessage(TEST_CMD_5);
            sm5.sendMessage(TEST_CMD_6);

            try {
                // wait for the messages to be handled
                sm5.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine5: exception while waiting " + e.getMessage());
            }
        }


        assertEquals(6, sm5.getLogRecSize());

        assertEquals(1, sm5.mParentState1EnterCount);
        assertEquals(1, sm5.mParentState1ExitCount);
        assertEquals(1, sm5.mChildState1EnterCount);
        assertEquals(1, sm5.mChildState1ExitCount);
        assertEquals(1, sm5.mChildState2EnterCount);
        assertEquals(1, sm5.mChildState2ExitCount);
        assertEquals(2, sm5.mParentState2EnterCount);
        assertEquals(2, sm5.mParentState2ExitCount);
        assertEquals(1, sm5.mChildState3EnterCount);
        assertEquals(1, sm5.mChildState3ExitCount);
        assertEquals(2, sm5.mChildState4EnterCount);
        assertEquals(2, sm5.mChildState4ExitCount);
        assertEquals(1, sm5.mChildState5EnterCount);
        assertEquals(1, sm5.mChildState5ExitCount);

        LogRec lr;
        lr = sm5.getLogRec(0);
        assertEquals(TEST_CMD_1, lr.getWhat());
        assertEquals(sm5.mChildState1, lr.getState());
        assertEquals(sm5.mChildState1, lr.getOriginalState());

        lr = sm5.getLogRec(1);
        assertEquals(TEST_CMD_2, lr.getWhat());
        assertEquals(sm5.mChildState2, lr.getState());
        assertEquals(sm5.mChildState2, lr.getOriginalState());

        lr = sm5.getLogRec(2);
        assertEquals(TEST_CMD_3, lr.getWhat());
        assertEquals(sm5.mChildState5, lr.getState());
        assertEquals(sm5.mChildState5, lr.getOriginalState());

        lr = sm5.getLogRec(3);
        assertEquals(TEST_CMD_4, lr.getWhat());
        assertEquals(sm5.mChildState3, lr.getState());
        assertEquals(sm5.mChildState3, lr.getOriginalState());

        lr = sm5.getLogRec(4);
        assertEquals(TEST_CMD_5, lr.getWhat());
        assertEquals(sm5.mChildState4, lr.getState());
        assertEquals(sm5.mChildState4, lr.getOriginalState());

        lr = sm5.getLogRec(5);
        assertEquals(TEST_CMD_6, lr.getWhat());
        assertEquals(sm5.mParentState2, lr.getState());
        assertEquals(sm5.mParentState2, lr.getOriginalState());

        if (sm5.isDbg()) tlog("testStateMachine5 X");
    }

    /**
     * Test that the initial state enter is invoked immediately
     * after construction and before any other messages arrive and that
     * sendMessageDelayed works.
     */
    class StateMachine6 extends StateMachine {
        StateMachine6(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) tlog("StateMachine6: ctor X");
        }

        class S1 extends State {
            @Override
            public void enter() {
                sendMessage(TEST_CMD_1);
            }
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_1) {
                    mArrivalTimeMsg1 = SystemClock.elapsedRealtime();
                } else if (message.what == TEST_CMD_2) {
                    mArrivalTimeMsg2 = SystemClock.elapsedRealtime();
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine6 mThisSm;
        private S1 mS1 = new S1();

        private long mArrivalTimeMsg1;
        private long mArrivalTimeMsg2;
    }

    @MediumTest
    public void testStateMachine6() throws Exception {
        final int DELAY_TIME = 250;
        final int DELAY_FUDGE = 20;

        StateMachine6 sm6 = new StateMachine6("sm6");
        sm6.start();
        if (sm6.isDbg()) tlog("testStateMachine6 E");

        synchronized (sm6) {
            // Send a message
            sm6.sendMessageDelayed(TEST_CMD_2, DELAY_TIME);

            try {
                // wait for the messages to be handled
                sm6.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine6: exception while waiting " + e.getMessage());
            }
        }

        /**
         * TEST_CMD_1 was sent in enter and must always have been processed
         * immediately after construction and hence the arrival time difference
         * should always >= to the DELAY_TIME
         */
        long arrivalTimeDiff = sm6.mArrivalTimeMsg2 - sm6.mArrivalTimeMsg1;
        long expectedDelay = DELAY_TIME - DELAY_FUDGE;
        if (sm6.isDbg()) tlog("testStateMachine6: expect " + arrivalTimeDiff
                                    + " >= " + expectedDelay);
        assertTrue(arrivalTimeDiff >= expectedDelay);

        if (sm6.isDbg()) tlog("testStateMachine6 X");
    }

    /**
     * Test that enter is invoked immediately after exit. This validates
     * that enter can be used to send a watch dog message for its state.
     */
    class StateMachine7 extends StateMachine {
        private final int SM7_DELAY_TIME = 250;

        StateMachine7(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);
            addState(mS2);

            // Set the initial state
            setInitialState(mS1);
            if (DBG) tlog("StateMachine7: ctor X");
        }

        class S1 extends State {
            @Override
            public void exit() {
                sendMessage(TEST_CMD_2);
            }
            @Override
            public boolean processMessage(Message message) {
                transitionTo(mS2);
                return HANDLED;
            }
        }

        class S2 extends State {
            @Override
            public void enter() {
                // Send a delayed message as a watch dog
                sendMessageDelayed(TEST_CMD_3, SM7_DELAY_TIME);
            }
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    mMsgCount += 1;
                    mArrivalTimeMsg2 = SystemClock.elapsedRealtime();
                } else if (message.what == TEST_CMD_3) {
                    mMsgCount += 1;
                    mArrivalTimeMsg3 = SystemClock.elapsedRealtime();
                }

                if (mMsgCount == 2) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachine7 mThisSm;
        private S1 mS1 = new S1();
        private S2 mS2 = new S2();

        private int mMsgCount = 0;
        private long mArrivalTimeMsg2;
        private long mArrivalTimeMsg3;
    }

    @MediumTest
    public void testStateMachine7() throws Exception {
        final int SM7_DELAY_FUDGE = 20;

        StateMachine7 sm7 = new StateMachine7("sm7");
        sm7.start();
        if (sm7.isDbg()) tlog("testStateMachine7 E");

        synchronized (sm7) {
            // Send a message
            sm7.sendMessage(TEST_CMD_1);

            try {
                // wait for the messages to be handled
                sm7.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachine7: exception while waiting " + e.getMessage());
            }
        }

        /**
         * TEST_CMD_3 was sent in S2.enter with a delay and must always have been
         * processed immediately after S1.exit. Since S1.exit sent TEST_CMD_2
         * without a delay the arrival time difference should always >= to SM7_DELAY_TIME.
         */
        long arrivalTimeDiff = sm7.mArrivalTimeMsg3 - sm7.mArrivalTimeMsg2;
        long expectedDelay = sm7.SM7_DELAY_TIME - SM7_DELAY_FUDGE;
        if (sm7.isDbg()) tlog("testStateMachine7: expect " + arrivalTimeDiff
                                    + " >= " + expectedDelay);
        assertTrue(arrivalTimeDiff >= expectedDelay);

        if (sm7.isDbg()) tlog("testStateMachine7 X");
    }

    /**
     * Test unhandledMessage.
     */
    class StateMachineUnhandledMessage extends StateMachine {
        StateMachineUnhandledMessage(String name) {
            super(name);
            mThisSm = this;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }
        @Override
        public void unhandledMessage(Message message) {
            mUnhandledMessageCount += 1;
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_2) {
                    transitionToHaltingState();
                }
                return NOT_HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            synchronized (mThisSm) {
                mThisSm.notifyAll();
            }
        }

        private StateMachineUnhandledMessage mThisSm;
        private int mUnhandledMessageCount;
        private S1 mS1 = new S1();
    }

    @SmallTest
    public void testStateMachineUnhandledMessage() throws Exception {

        StateMachineUnhandledMessage sm = new StateMachineUnhandledMessage("smUnhandledMessage");
        sm.start();
        if (sm.isDbg()) tlog("testStateMachineUnhandledMessage E");

        synchronized (sm) {
            // Send 2 messages
            for (int i = 1; i <= 2; i++) {
                sm.sendMessage(i);
            }

            try {
                // wait for the messages to be handled
                sm.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachineUnhandledMessage: exception while waiting "
                        + e.getMessage());
            }
        }

        assertEquals(2, sm.getLogRecSize());
        assertEquals(2, sm.mUnhandledMessageCount);

        if (sm.isDbg()) tlog("testStateMachineUnhandledMessage X");
    }

    /**
     * Test state machines sharing the same thread/looper. Multiple instances
     * of the same state machine will be created. They will all share the
     * same thread and thus each can update <code>sharedCounter</code> which
     * will be used to notify testStateMachineSharedThread that the test is
     * complete.
     */
    class StateMachineSharedThread extends StateMachine {
        StateMachineSharedThread(String name, Looper looper, int maxCount) {
            super(name, looper);
            mMaxCount = maxCount;
            setDbg(DBG);

            // Setup state machine with 1 state
            addState(mS1);

            // Set the initial state
            setInitialState(mS1);
        }

        class S1 extends State {
            @Override
            public boolean processMessage(Message message) {
                if (message.what == TEST_CMD_4) {
                    transitionToHaltingState();
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            // Update the shared counter, which is OK since all state
            // machines are using the same thread.
            sharedCounter += 1;
            if (sharedCounter == mMaxCount) {
                synchronized (waitObject) {
                    waitObject.notifyAll();
                }
            }
        }

        private int mMaxCount;
        private S1 mS1 = new S1();
    }
    private static int sharedCounter = 0;
    private static Object waitObject = new Object();

    @MediumTest
    public void testStateMachineSharedThread() throws Exception {
        if (DBG) tlog("testStateMachineSharedThread E");

        // Create and start the handler thread
        HandlerThread smThread = new HandlerThread("testStateMachineSharedThread");
        smThread.start();

        // Create the state machines
        StateMachineSharedThread sms[] = new StateMachineSharedThread[10];
        for (int i = 0; i < sms.length; i++) {
            sms[i] = new StateMachineSharedThread("smSharedThread",
                        smThread.getLooper(), sms.length);
            sms[i].start();
        }

        synchronized (waitObject) {
            // Send messages to each of the state machines
            for (StateMachineSharedThread sm : sms) {
                for (int i = 1; i <= 4; i++) {
                    sm.sendMessage(i);
                }
            }

            // Wait for the last state machine to notify its done
            try {
                waitObject.wait();
            } catch (InterruptedException e) {
                tloge("testStateMachineSharedThread: exception while waiting "
                        + e.getMessage());
            }
        }

        for (StateMachineSharedThread sm : sms) {
            assertEquals(4, sm.getLogRecCount());
            for (int i = 0; i < sm.getLogRecSize(); i++) {
                LogRec lr = sm.getLogRec(i);
                assertEquals(i+1, lr.getWhat());
                assertEquals(sm.mS1, lr.getState());
                assertEquals(sm.mS1, lr.getOriginalState());
            }
        }

        if (DBG) tlog("testStateMachineSharedThread X");
    }

    static class Hsm1 extends StateMachine {
        private static final String HSM1_TAG = "hsm1";

        public static final int CMD_1 = 1;
        public static final int CMD_2 = 2;
        public static final int CMD_3 = 3;
        public static final int CMD_4 = 4;
        public static final int CMD_5 = 5;

        public static Hsm1 makeHsm1() {
            Log.d(HSM1_TAG, "makeHsm1 E");
            Hsm1 sm = new Hsm1(HSM1_TAG);
            sm.start();
            Log.d(HSM1_TAG, "makeHsm1 X");
            return sm;
        }

        Hsm1(String name) {
            super(name);
            tlog("ctor E");

            // Add states, use indentation to show hierarchy
            addState(mP1);
                addState(mS1, mP1);
                addState(mS2, mP1);
            addState(mP2);

            // Set the initial state
            setInitialState(mS1);
            tlog("ctor X");
        }

        class P1 extends State {
            @Override
            public void enter() {
                tlog("P1.enter");
            }
            @Override
            public void exit() {
                tlog("P1.exit");
            }
            @Override
            public boolean processMessage(Message message) {
                boolean retVal;
                tlog("P1.processMessage what=" + message.what);
                switch(message.what) {
                case CMD_2:
                    // CMD_2 will arrive in mS2 before CMD_3
                    sendMessage(CMD_3);
                    deferMessage(message);
                    transitionTo(mS2);
                    retVal = true;
                    break;
                default:
                    // Any message we don't understand in this state invokes unhandledMessage
                    retVal = false;
                    break;
                }
                return retVal;
            }
        }

        class S1 extends State {
            @Override
            public void enter() {
                tlog("S1.enter");
            }
            @Override
            public void exit() {
                tlog("S1.exit");
            }
            @Override
            public boolean processMessage(Message message) {
                tlog("S1.processMessage what=" + message.what);
                if (message.what == CMD_1) {
                    // Transition to ourself to show that enter/exit is called
                    transitionTo(mS1);
                    return HANDLED;
                } else {
                    // Let parent process all other messages
                    return NOT_HANDLED;
                }
            }
        }

        class S2 extends State {
            @Override
            public void enter() {
                tlog("S2.enter");
            }
            @Override
            public void exit() {
                tlog("S2.exit");
            }
            @Override
            public boolean processMessage(Message message) {
                boolean retVal;
                tlog("S2.processMessage what=" + message.what);
                switch(message.what) {
                case(CMD_2):
                    sendMessage(CMD_4);
                    retVal = true;
                    break;
                case(CMD_3):
                    deferMessage(message);
                    transitionTo(mP2);
                    retVal = true;
                    break;
                default:
                    retVal = false;
                    break;
                }
                return retVal;
            }
        }

        class P2 extends State {
            @Override
            public void enter() {
                tlog("P2.enter");
                sendMessage(CMD_5);
            }
            @Override
            public void exit() {
                tlog("P2.exit");
            }
            @Override
            public boolean processMessage(Message message) {
                tlog("P2.processMessage what=" + message.what);
                switch(message.what) {
                case(CMD_3):
                    break;
                case(CMD_4):
                    break;
                case(CMD_5):
                    transitionToHaltingState();
                    break;
                }
                return HANDLED;
            }
        }

        @Override
        protected void onHalting() {
            tlog("halting");
            synchronized (this) {
                this.notifyAll();
            }
        }

        P1 mP1 = new P1();
        S1 mS1 = new S1();
        S2 mS2 = new S2();
        P2 mP2 = new P2();
    }

    @MediumTest
    public void testHsm1() throws Exception {
        if (DBG) tlog("testHsm1 E");

        Hsm1 sm = Hsm1.makeHsm1();

        // Send messages
        sm.sendMessage(Hsm1.CMD_1);
        sm.sendMessage(Hsm1.CMD_2);

        synchronized (sm) {
            // Wait for the last state machine to notify its done
            try {
                sm.wait();
            } catch (InterruptedException e) {
                tloge("testHsm1: exception while waiting " + e.getMessage());
            }
        }

        dumpLogRecs(sm);

        assertEquals(7, sm.getLogRecCount());

        LogRec lr = sm.getLogRec(0);
        assertEquals(Hsm1.CMD_1, lr.getWhat());
        assertEquals(sm.mS1, lr.getState());
        assertEquals(sm.mS1, lr.getOriginalState());

        lr = sm.getLogRec(1);
        assertEquals(Hsm1.CMD_2, lr.getWhat());
        assertEquals(sm.mP1, lr.getState());
        assertEquals(sm.mS1, lr.getOriginalState());

        lr = sm.getLogRec(2);
        assertEquals(Hsm1.CMD_2, lr.getWhat());
        assertEquals(sm.mS2, lr.getState());
        assertEquals(sm.mS2, lr.getOriginalState());

        lr = sm.getLogRec(3);
        assertEquals(Hsm1.CMD_3, lr.getWhat());
        assertEquals(sm.mS2, lr.getState());
        assertEquals(sm.mS2, lr.getOriginalState());

        lr = sm.getLogRec(4);
        assertEquals(Hsm1.CMD_3, lr.getWhat());
        assertEquals(sm.mP2, lr.getState());
        assertEquals(sm.mP2, lr.getOriginalState());

        lr = sm.getLogRec(5);
        assertEquals(Hsm1.CMD_4, lr.getWhat());
        assertEquals(sm.mP2, lr.getState());
        assertEquals(sm.mP2, lr.getOriginalState());

        lr = sm.getLogRec(6);
        assertEquals(Hsm1.CMD_5, lr.getWhat());
        assertEquals(sm.mP2, lr.getState());
        assertEquals(sm.mP2, lr.getOriginalState());

        if (DBG) tlog("testStateMachineSharedThread X");
    }

    private static void tlog(String s) {
        Log.d(TAG, s);
    }

    private static void tloge(String s) {
        Log.e(TAG, s);
    }

    public void testDumpDoesNotThrowNpeAfterQuit() {
        final Hsm1 sm = Hsm1.makeHsm1();
        sm.quitNow();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(stringWriter);
        sm.dump(null, printWriter, new String[0]);
    }
}
