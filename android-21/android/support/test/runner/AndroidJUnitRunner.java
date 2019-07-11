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

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Looper;
import android.support.test.internal.runner.TestRequest;
import android.support.test.internal.runner.TestRequestBuilder;
import android.support.test.internal.runner.listener.CoverageListener;
import android.support.test.internal.runner.listener.DelayInjector;
import android.support.test.internal.runner.listener.InstrumentationResultPrinter;
import android.support.test.internal.runner.listener.InstrumentationRunListener;
import android.support.test.internal.runner.listener.LogRunListener;
import android.support.test.internal.runner.listener.SuiteAssignmentPrinter;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link Instrumentation} that runs JUnit3 and JUnit4 tests against
 * an Android package (application).
 * <p/>
 * Currently experimental. Based on {@link android.test.InstrumentationTestRunner}.
 * <p/>
 * Will eventually support a superset of {@link android.test.InstrumentationTestRunner} features,
 * while maintaining command/output format compatibility with that class.
 *
 * <h3>Typical Usage</h3>
 * <p/>
 * Write JUnit3 style {@link junit.framework.TestCase}s and/or JUnit4 style
 * {@link org.junit.Test}s that perform tests against the classes in your package.
 * Make use of the {@link android.support.test.InjectContext} and
 * {@link android.support.test.InjectInstrumentation} annotations if needed.
 * <p/>
 * In an appropriate AndroidManifest.xml, define an instrumentation with android:name set to
 * {@link android.support.test.runner.AndroidJUnitRunner} and the appropriate android:targetPackage
 * set.
 * <p/>
 * Execution options:
 * <p/>
 * <b>Running all tests:</b> adb shell am instrument -w
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>Running all tests in a class:</b> adb shell am instrument -w
 * -e class com.android.foo.FooTest
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>Running a single test:</b> adb shell am instrument -w
 * -e class com.android.foo.FooTest#testFoo
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>Running all tests in multiple classes:</b> adb shell am instrument -w
 * -e class com.android.foo.FooTest,com.android.foo.TooTest
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>Running all tests listed in a file:</b> adb shell am instrument -w
 * -e testFile /sdcard/tmp/testFile.txt com.android.foo/com.android.test.runner.AndroidJUnitRunner
 * The file should contain a list of line separated test classes and optionally methods (expected
 * format: com.android.foo.FooClassName#testMethodName).
 * <p/>
 * <b>Running all tests in a java package:</b> adb shell am instrument -w
 * -e package com.android.foo.bar
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <b>To debug your tests, set a break point in your code and pass:</b>
 * -e debug true
 * <p/>
 * <b>Running a specific test size i.e. annotated with
 * {@link android.test.suitebuilder.annotation.SmallTest} or
 * {@link android.test.suitebuilder.annotation.MediumTest} or
 * {@link android.test.suitebuilder.annotation.LargeTest}:</b>
 * adb shell am instrument -w -e size [small|medium|large]
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>Filter test run to tests with given annotation:</b> adb shell am instrument -w
 * -e annotation com.android.foo.MyAnnotation
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * If used with other options, the resulting test run will contain the intersection of the two
 * options.
 * e.g. "-e size large -e annotation com.android.foo.MyAnnotation" will run only tests with both
 * the {@link LargeTest} and "com.android.foo.MyAnnotation" annotations.
 * <p/>
 * <b>Filter test run to tests <i>without</i> given annotation:</b> adb shell am instrument -w
 * -e notAnnotation com.android.foo.MyAnnotation
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * As above, if used with other options, the resulting test run will contain the intersection of
 * the two options.
 * e.g. "-e size large -e notAnnotation com.android.foo.MyAnnotation" will run tests with
 * the {@link LargeTest} annotation that do NOT have the "com.android.foo.MyAnnotation" annotations.
 * <p/>
 * <b>Filter test run to tests <i>without any</i> of a list of annotations:</b> adb shell am
 * instrument -w -e notAnnotation com.android.foo.MyAnnotation,com.android.foo.AnotherAnnotation
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>Filter test run to a shard of all tests, where numShards is an integer greater than 0 and
 * shardIndex is an integer between 0 (inclusive) and numShards (exclusive):</b> adb shell am
 * instrument -w -e numShards 4 -e shardIndex 1
 * com.android.foo/android.support.test.runner.AndroidJUnitRunner
 * <p/>
 * <b>To run in 'log only' mode</b>
 * -e log true
 * This option will load and iterate through all test classes and methods, but will bypass actual
 * test execution. Useful for quickly obtaining info on the tests to be executed by an
 * instrumentation command.
 * <p/>
 * <b>To generate EMMA code coverage:</b>
 * -e coverage true
 * Note: this requires an emma instrumented build. By default, the code coverage results file
 * will be saved in a /data/<app>/coverage.ec file, unless overridden by coverageFile flag (see
 * below)
 * <p/>
 * <b> To specify EMMA code coverage results file path:</b>
 * -e coverageFile /sdcard/myFile.ec
 * <p/>
 * <b> To specify one or more {@link RunListener}s to observe the test run:</b>
 * -e listener com.foo.Listener,com.foo.Listener2
 * <p/>
 * <b/>OR, specify the multiple listeners in the AndroidManifest via a meta-data tag:</b>
 * instrumentation android:name="android.support.test.runner.AndroidJUnitRunner" ...
 *    meta-data android:name="listener"
 *              android:value="com.foo.Listener,com.foo.Listener2"
 */
public class AndroidJUnitRunner extends MonitoringInstrumentation {

    // constants for supported instrumentation arguments
    public static final String ARGUMENT_TEST_CLASS = "class";
    private static final String ARGUMENT_TEST_SIZE = "size";
    private static final String ARGUMENT_LOG_ONLY = "log";
    private static final String ARGUMENT_ANNOTATION = "annotation";
    private static final String ARGUMENT_NOT_ANNOTATION = "notAnnotation";
    private static final String ARGUMENT_NUM_SHARDS = "numShards";
    private static final String ARGUMENT_SHARD_INDEX = "shardIndex";
    private static final String ARGUMENT_DELAY_MSEC = "delay_msec";
    private static final String ARGUMENT_COVERAGE = "coverage";
    private static final String ARGUMENT_COVERAGE_PATH = "coverageFile";
    private static final String ARGUMENT_SUITE_ASSIGNMENT = "suiteAssignment";
    private static final String ARGUMENT_DEBUG = "debug";
    private static final String ARGUMENT_LISTENER = "listener";
    private static final String ARGUMENT_TEST_PACKAGE = "package";
    static final String ARGUMENT_TEST_FILE = "testFile";
    // TODO: consider supporting 'count' from InstrumentationTestRunner

    private static final String LOG_TAG = "AndroidJUnitRunner";

    // used to separate multiple fully-qualified test case class names
    private static final char CLASS_SEPARATOR = ',';
    // used to separate fully-qualified test case class name, and one of its methods
    private static final char METHOD_SEPARATOR = '#';

    private Bundle mArguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        setArguments(arguments);
        specifyDexMakerCacheProperty();

        start();
    }

    /**
     * Get the Bundle object that contains the arguments passed to the instrumentation
     *
     * @return the Bundle object
     * @hide
     */
    public Bundle getArguments(){
        return mArguments;
    }

    /**
     * Set the arguments.
     *
     * @VisibleForTesting
     */
    void setArguments(Bundle args) {
        mArguments = args;
    }

    private boolean getBooleanArgument(String tag) {
        String tagString = getArguments().getString(tag);
        return tagString != null && Boolean.parseBoolean(tagString);
    }

    @Override
    public void onStart() {
        super.onStart();

        if (getBooleanArgument(ARGUMENT_DEBUG)) {
            Debug.waitForDebugger();
        }

        setupDexmakerClassloader();

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream writer = new PrintStream(byteArrayOutputStream);
        List<RunListener> listeners = new ArrayList<RunListener>();

        try {
            JUnitCore testRunner = new JUnitCore();
            addListeners(listeners, testRunner, writer);

            TestRequest testRequest = buildRequest(getArguments(), writer);
            Result result = testRunner.run(testRequest.getRequest());
            result.getFailures().addAll(testRequest.getFailures());
        } catch (Throwable t) {
            // catch all exceptions so a more verbose error message can be displayed
            writer.println(String.format(
                    "Test run aborted due to unexpected exception: %s",
                    t.getMessage()));
            t.printStackTrace(writer);

        } finally {
            Bundle results = new Bundle();
            reportRunEnded(listeners, writer, results);
            writer.close();
            results.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                    String.format("\n%s",
                            byteArrayOutputStream.toString()));
            finish(Activity.RESULT_OK, results);
        }

    }

    private void addListeners(List<RunListener> listeners, JUnitCore testRunner,
            PrintStream writer) {
        if (getBooleanArgument(ARGUMENT_SUITE_ASSIGNMENT)) {
            listeners.add(new SuiteAssignmentPrinter());
        } else {
            listeners.add(new TextListener(writer));
            listeners.add(new LogRunListener());
            listeners.add(new InstrumentationResultPrinter());
            addDelayListener(listeners);
            addCoverageListener(listeners);
        }

        addListenersFromArg(listeners, writer);
        addListenersFromManifest(listeners, writer);

        for (RunListener listener : listeners) {
            testRunner.addListener(listener);
            if (listener instanceof InstrumentationRunListener) {
                ((InstrumentationRunListener)listener).setInstrumentation(this);
            }
        }
    }

    private void addCoverageListener(List<RunListener> list) {
        if (getBooleanArgument(ARGUMENT_COVERAGE)) {
            String coverageFilePath = getArguments().getString(ARGUMENT_COVERAGE_PATH);
            list.add(new CoverageListener(coverageFilePath));
        }
    }

    /**
     * Sets up listener to inject {@link #ARGUMENT_DELAY_MSEC}, if specified.
     */
    private void addDelayListener(List<RunListener> list) {
        try {
            Object delay = getArguments().get(ARGUMENT_DELAY_MSEC);  // Accept either string or int
            if (delay != null) {
                int delayMsec = Integer.parseInt(delay.toString());
                list.add(new DelayInjector(delayMsec));
            }
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, "Invalid delay_msec parameter", e);
        }
    }

    /**
     * Add extra {@link RunListener}s specified via command line
     */
    private void addListenersFromArg(List<RunListener> listeners,
            PrintStream writer) {
        addListenersFromClassString(getArguments().getString(ARGUMENT_LISTENER),
                listeners, writer);
    }

    /**
     * Load the listeners specified via meta-data name="listener" in the AndroidManifest.
     */
    private void addListenersFromManifest(List<RunListener> listeners,
            PrintStream writer) {
        PackageManager pm = getContext().getPackageManager();
        try {
            InstrumentationInfo instrInfo = pm.getInstrumentationInfo(getComponentName(),
                    PackageManager.GET_META_DATA);
            Bundle b = instrInfo.metaData;
            if (b == null) {
                return;
            }
            String extraListenerList = b.getString(ARGUMENT_LISTENER);
            addListenersFromClassString(extraListenerList, listeners, writer);
        } catch (NameNotFoundException e) {
            // should never happen
            Log.wtf(LOG_TAG, String.format("Could not find component %s", getComponentName()));
        }
    }

    /**
     * Add extra {@link RunListener}s to the testRunner as given in the csv class name list
     *
     * @param extraListenerList the CSV class name of {@link RunListener}s to add
     * @param writer the {@link PrintStream} to dump errors to
     * @param listeners the {@link List} to add listeners to
     */
    private void addListenersFromClassString(String extraListenerList,
            List<RunListener> listeners, PrintStream writer) {
        if (extraListenerList == null) {
            return;
        }

        for (String listenerName : extraListenerList.split(",")) {
            addListenerByClassName(listeners, writer, listenerName);
        }
    }

    private void addListenerByClassName(List<RunListener> listeners,
            PrintStream writer, String extraListener) {
        if (extraListener == null || extraListener.length() == 0) {
            return;
        }

        final Class<?> klass;
        try {
            klass = Class.forName(extraListener);
        } catch (ClassNotFoundException e) {
            writer.println("Could not find extra RunListener class " + extraListener);
            return;
        }

        if (!RunListener.class.isAssignableFrom(klass)) {
            writer.println("Extra listeners must extend RunListener class " + extraListener);
            return;
        }

        try {
            klass.getConstructor().setAccessible(true);
        } catch (NoSuchMethodException e) {
            writer.println("Must have no argument constructor for class " + extraListener);
            return;
        }

        final RunListener l;
        try {
            l = (RunListener) klass.newInstance();
        } catch (Throwable t) {
            writer.println("Could not instantiate extra RunListener class " + extraListener);
            t.printStackTrace(writer);
            return;
        }

        listeners.add(l);
    }

    private void reportRunEnded(List<RunListener> listeners, PrintStream writer, Bundle results) {
        for (RunListener listener : listeners) {
            if (listener instanceof InstrumentationRunListener) {
                ((InstrumentationRunListener)listener).instrumentationRunFinished(writer, results);
            }
        }
    }

    /**
     * Builds a {@link TestRequest} based on given input arguments.
     * <p/>
     * Exposed for unit testing.
     */
    TestRequest buildRequest(Bundle arguments, PrintStream writer) {
        // only load tests for current aka testContext
        // Note that this represents a change from InstrumentationTestRunner where
        // getTargetContext().getPackageCodePath() was also scanned
        TestRequestBuilder builder = createTestRequestBuilder(writer,
                getContext().getPackageCodePath());

        String testClassName = arguments.getString(ARGUMENT_TEST_CLASS);
        if (testClassName != null) {
            for (String className : testClassName.split(String.valueOf(CLASS_SEPARATOR))) {
                parseTestClass(className, builder);
            }
        }

        String testFilePath = arguments.getString(ARGUMENT_TEST_FILE);
        if (testFilePath != null) {
            parseTestClassesFromFile(testFilePath, builder);
        }

        String testPackage = arguments.getString(ARGUMENT_TEST_PACKAGE);
        if (testPackage != null) {
            builder.addTestPackageFilter(testPackage);
        }

        String testSize = arguments.getString(ARGUMENT_TEST_SIZE);
        if (testSize != null) {
            builder.addTestSizeFilter(testSize);
        }

        String annotation = arguments.getString(ARGUMENT_ANNOTATION);
        if (annotation != null) {
            builder.addAnnotationInclusionFilter(annotation);
        }

        String notAnnotations = arguments.getString(ARGUMENT_NOT_ANNOTATION);
        if (notAnnotations != null) {
            for (String notAnnotation : notAnnotations.split(",")) {
                builder.addAnnotationExclusionFilter(notAnnotation);
            }
        }

        // Accept either string or int.
        Object numShardsObj = arguments.get(ARGUMENT_NUM_SHARDS);
        Object shardIndexObj = arguments.get(ARGUMENT_SHARD_INDEX);
        if (numShardsObj != null && shardIndexObj != null) {
            int numShards = -1;
            int shardIndex = -1;
            try {
                numShards = Integer.parseInt(numShardsObj.toString());
                shardIndex = Integer.parseInt(shardIndexObj.toString());
            } catch(NumberFormatException e) {
                Log.e(LOG_TAG, "Invalid sharding parameter", e);
            }
            if (numShards > 0 && shardIndex >= 0 && shardIndex < numShards) {
                builder.addShardingFilter(numShards, shardIndex);
            }
        }

        if (getBooleanArgument(ARGUMENT_LOG_ONLY)) {
            builder.setSkipExecution(true);
        }
        return builder.build(this, arguments);
    }

    /**
     * Factory method for {@link TestRequestBuilder}.
     * <p/>
     * Exposed for unit testing.
     */
    TestRequestBuilder createTestRequestBuilder(PrintStream writer, String... packageCodePaths) {
        return new TestRequestBuilder(writer, packageCodePaths);
    }

    /**
     * Parse and load the given test class and, optionally, method
     *
     * @param testClassName - full package name of test class and optionally method to add.
     *        Expected format: com.android.TestClass#testMethod
     * @param testRequestBuilder - builder to add tests to
     */
    private void parseTestClass(String testClassName, TestRequestBuilder testRequestBuilder) {
        int methodSeparatorIndex = testClassName.indexOf(METHOD_SEPARATOR);

        if (methodSeparatorIndex > 0) {
            String testMethodName = testClassName.substring(methodSeparatorIndex + 1);
            testClassName = testClassName.substring(0, methodSeparatorIndex);
            testRequestBuilder.addTestMethod(testClassName, testMethodName);
        } else {
            testRequestBuilder.addTestClass(testClassName);
        }
    }

    /**
     * Parse and load the content of a test file
     *
     * @param filePath  path to test file contaitnig full package names of test classes and
     *                  optionally methods to add.
     * @param testRequestBuilder - builder to add tests to
     */
    private void parseTestClassesFromFile(String filePath, TestRequestBuilder testRequestBuilder) {
        List<String> classes = new ArrayList<String>();
        BufferedReader br = null;
        String line;
        try {
            br = new BufferedReader(new FileReader(new File(filePath)));
            while ((line = br.readLine()) != null) {
                classes.add(line);
            }
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, String.format("File not found: %s", filePath), e);
        } catch (IOException e) {
            Log.e(LOG_TAG,
                    String.format("Something went wrong reading %s, ignoring file", filePath), e);
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException e) { /* ignore */ }
            }
        }

        for (String className : classes) {
            parseTestClass(className, testRequestBuilder);
        }
    }

    private void setupDexmakerClassloader() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        // must set the context classloader for apps that use a shared uid, see
        // frameworks/base/core/java/android/app/LoadedApk.java
        ClassLoader newClassLoader = this.getClass().getClassLoader();
        Log.i(LOG_TAG, String.format("Setting context classloader to '%s', Original: '%s'",
                newClassLoader.toString(), originalClassLoader.toString()));
        Thread.currentThread().setContextClassLoader(newClassLoader);
    }

    // ActivityUnitTestCase defaults to building the ComponentName via
    // Activity.getClass().getPackage().getName(). This will cause a problem if the Java Package of
    // the Activity is not the Android Package of the application, specifically
    // Activity.getPackageName() will return an incorrect value.
    // @see b/14561718
    @Override
    public Activity newActivity(Class<?> clazz,
            Context context,
            IBinder token,
            Application application,
            Intent intent,
            ActivityInfo info,
            CharSequence title,
            Activity parent,
            String id,
            Object lastNonConfigurationInstance) throws InstantiationException, IllegalAccessException {
        String activityClassPackageName = clazz.getPackage().getName();
        String contextPackageName = context.getPackageName();
        ComponentName intentComponentName = intent.getComponent();
        if (!contextPackageName.equals(intentComponentName.getPackageName())) {
            if (activityClassPackageName.equals(intentComponentName.getPackageName())) {
                intent.setComponent(
                        new ComponentName(contextPackageName, intentComponentName.getClassName()));
            }
        }
        return super.newActivity(clazz,
                context,
                token,
                application,
                intent,
                info,
                title,
                parent,
                id,
                lastNonConfigurationInstance);
    }
}
