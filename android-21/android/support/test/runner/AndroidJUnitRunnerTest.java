/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.support.test.runner;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.test.internal.runner.TestRequestBuilder;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;


import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Unit tests for {@link AndroidJUnitRunner}.
 */
public class AndroidJUnitRunnerTest {
    public static final int SLEEP_TIME = 300;

    private final Thread mInstantiationThread = Thread.currentThread();

    private AndroidJUnitRunner mAndroidJUnitRunner;
    private PrintStream mStubStream;
    @Mock
    private TestRequestBuilder mMockBuilder;
    @Mock
    private Context mMockContext;

    @Before
    public void setUp() throws Exception {
        mAndroidJUnitRunner = new AndroidJUnitRunner() {

            @Override
            TestRequestBuilder createTestRequestBuilder(PrintStream writer,
                    String... packageCodePaths) {
                return mMockBuilder;
            }

            @Override
            public Context getContext() {
                return mMockContext;
            }
        };
        mAndroidJUnitRunner.setArguments(new Bundle());
        mStubStream = new PrintStream(new ByteArrayOutputStream());
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Test {@link AndroidJUnitRunner#buildRequest(Bundle, PrintStream)} when
     * a single class name is provided.
     */
    @Test
    public void testBuildRequest_singleClass() {
        Bundle b = new Bundle();
        b.putString(AndroidJUnitRunner.ARGUMENT_TEST_CLASS, "ClassName");
        mAndroidJUnitRunner.buildRequest(b, mStubStream);
        Mockito.verify(mMockBuilder).addTestClass("ClassName");
    }

    /**
     * Test {@link AndroidJUnitRunner#buildRequest(Bundle, PrintStream)} when
     * multiple class names are provided.
     */
    @Test
    public void testBuildRequest_multiClass() {
        Bundle b = new Bundle();
        b.putString(AndroidJUnitRunner.ARGUMENT_TEST_CLASS, "ClassName1,ClassName2");
        mAndroidJUnitRunner.buildRequest(b, mStubStream);
        Mockito.verify(mMockBuilder).addTestClass("ClassName1");
        Mockito.verify(mMockBuilder).addTestClass("ClassName2");
    }

    /**
     * Test {@link AndroidJUnitRunner#buildRequest(Bundle, PrintStream)} when
     * class name and method name is provided.
     */
    @Test
    public void testBuildRequest_method() {
        Bundle b = new Bundle();
        b.putString(AndroidJUnitRunner.ARGUMENT_TEST_CLASS, "ClassName1#method");
        mAndroidJUnitRunner.buildRequest(b, mStubStream);
        Mockito.verify(mMockBuilder).addTestMethod("ClassName1", "method");
    }

    /**
     * Test {@link AndroidJUnitRunner#buildRequest(Bundle, PrintStream)} when
     * class name and method name is provided along with an additional class name.
     */
    @Test
    public void testBuildRequest_classAndMethodCombo() {
        Bundle b = new Bundle();
        b.putString(AndroidJUnitRunner.ARGUMENT_TEST_CLASS, "ClassName1#method,ClassName2");
        mAndroidJUnitRunner.buildRequest(b, mStubStream);
        Mockito.verify(mMockBuilder).addTestMethod("ClassName1", "method");
        Mockito.verify(mMockBuilder).addTestClass("ClassName2");
    }

    /**
     * Temp file used for testing
     */
    @Rule
    public TemporaryFolder mTmpFolder = new TemporaryFolder();

    /**
     * Test {@link AndroidJUnitRunner#buildRequest(Bundle, PrintStream)} when
     * multiple class and method names are provided within a test file
     */
    @Test
    public void testBuildRequest_testFile() throws IOException {
        final File file = mTmpFolder.newFile("myTestFile.txt");
        BufferedWriter out = new BufferedWriter(new FileWriter(file));
        out.write("ClassName3\n");
        out.write("ClassName4#method2\n");
        out.close();

        Bundle b = new Bundle();
        b.putString(AndroidJUnitRunner.ARGUMENT_TEST_FILE, file.getPath());
        b.putString(AndroidJUnitRunner.ARGUMENT_TEST_CLASS, "ClassName1#method1,ClassName2");
        mAndroidJUnitRunner.buildRequest(b, mStubStream);
        Mockito.verify(mMockBuilder).addTestMethod("ClassName1", "method1");
        Mockito.verify(mMockBuilder).addTestClass("ClassName2");
        Mockito.verify(mMockBuilder).addTestClass("ClassName3");
        Mockito.verify(mMockBuilder).addTestMethod("ClassName4", "method2");
    }

    /**
     * Ensures that the main looper is not blocked and can process
     * messages during test execution.
     */
    @Test
    public void testMainLooperIsAlive() throws InterruptedException {
        final boolean[] called = new boolean[1];
        Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                called[0] = true;
            }
        };
        handler.sendEmptyMessage(0);
        Thread.sleep(SLEEP_TIME);
        Assert.assertTrue(called[0]);
    }

    /**
     * Ensures that the thread the test runs on has not been
     * prepared as a looper.  It doesn't make sense for it
     * to be a looper because it will be blocked for the entire
     * duration of test execution.  Tests should instead post
     * messages to the main looper or a new handler thread
     * of their own as appropriate while running.
     */
    @Test
    public void testTestThreadIsNotALooper() {
        Assert.assertNull(Looper.myLooper());
    }

    /**
     * Ensures that tests run on the same thread they were
     * instantiated on.  This is needed to ensure that
     * objects created by the test don't accidentally bind
     * to thread-local state belonging to other threads.
     * In particular, this ensures that the test cannot
     * create Handlers that are bound to the wrong thread.
     */
    @Test
    public void testTestRunsOnSameThreadAsInstantiation() {
        Assert.assertEquals(Thread.currentThread(), mInstantiationThread);
    }
}
