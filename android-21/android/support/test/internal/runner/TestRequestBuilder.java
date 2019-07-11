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
package android.support.test.internal.runner;

import android.app.Instrumentation;
import android.os.Bundle;
import android.support.test.filters.RequiresDevice;
import android.support.test.filters.SdkSuppress;
import android.support.test.internal.runner.ClassPathScanner.ChainedClassNameFilter;
import android.support.test.internal.runner.ClassPathScanner.ExcludePackageNameFilter;
import android.support.test.internal.runner.ClassPathScanner.ExternalClassNameFilter;
import android.support.test.internal.runner.ClassPathScanner.InclusivePackageNameFilter;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import org.junit.runner.Computer;
import org.junit.runner.Description;
import org.junit.runner.Request;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a {@link Request} from test classes in given apk paths, filtered on provided set of
 * restrictions.
 */
public class TestRequestBuilder {

    private static final String LOG_TAG = "TestRequestBuilder";

    public static final String LARGE_SIZE = "large";
    public static final String MEDIUM_SIZE = "medium";
    public static final String SMALL_SIZE = "small";

    static final String EMULATOR_HARDWARE = "goldfish";

    private String[] mApkPaths;
    private TestLoader mTestLoader;
    private ClassAndMethodFilter mClassMethodFilter = new ClassAndMethodFilter();
    private Filter mFilter = new AnnotationExclusionFilter(Suppress.class)
            .intersect(new SdkSuppressFilter())
            .intersect(new RequiresDeviceFilter())
            .intersect(mClassMethodFilter);
    private boolean mSkipExecution = false;
    private String mTestPackageName = null;
    private final DeviceBuild mDeviceBuild;

    /**
     * Accessor interface for retrieving device build properties.
     * <p/>
     * Used so unit tests can mock calls
     */
    static interface DeviceBuild {
        /**
         * Returns the SDK API level for current device.
         */
        int getSdkVersionInt();

        /**
         * Returns the hardware type of the current device.
         */
        String getHardware();
    }

    private static class DeviceBuildImpl implements DeviceBuild {
        @Override
        public int getSdkVersionInt() {
            return android.os.Build.VERSION.SDK_INT;
        }

        @Override
        public String getHardware() {
            return android.os.Build.HARDWARE;
        }
    }

    /**
     * Helper parent class for {@link Filter} that allows suites to run if any child matches.
     */
    private abstract static class ParentFilter extends Filter {
        /**
         * {@inheritDoc}
         */
        @Override
        public boolean shouldRun(Description description) {
            if (description.isTest()) {
                return evaluateTest(description);
            }
            // this is a suite, explicitly check if any children should run
            for (Description each : description.getChildren()) {
                if (shouldRun(each)) {
                    return true;
                }
            }
            // no children to run, filter this out
            return false;
        }

        /**
         * Determine if given test description matches filter.
         *
         * @param description the {@link Description} describing the test
         * @return <code>true</code> if matched
         */
        protected abstract boolean evaluateTest(Description description);
    }

    /**
     * Filter that only runs tests whose method or class has been annotated with given filter.
     */
    private static class AnnotationInclusionFilter extends ParentFilter {

        private final Class<? extends Annotation> mAnnotationClass;

        AnnotationInclusionFilter(Class<? extends Annotation> annotation) {
            mAnnotationClass = annotation;
        }

        /**
         * Determine if given test description matches filter.
         *
         * @param description the {@link Description} describing the test
         * @return <code>true</code> if matched
         */
        @Override
        protected boolean evaluateTest(Description description) {
            return description.getAnnotation(mAnnotationClass) != null ||
                    description.getTestClass().isAnnotationPresent(mAnnotationClass);
        }

        protected Class<? extends Annotation> getAnnotationClass() {
            return mAnnotationClass;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String describe() {
            return String.format("annotation %s", mAnnotationClass.getName());
        }
    }

    /**
     * A filter for test sizes.
     * <p/>
     * Will match if test method has given size annotation, or class does, but only if method does
     * not have any other size annotations. ie method size annotation overrides class size
     * annotation.
     */
    private static class SizeFilter extends AnnotationInclusionFilter {
        @SuppressWarnings("unchecked")
        private static final Set<Class<?>> ALL_SIZES = Collections.unmodifiableSet(new
                HashSet<Class<?>>(Arrays.asList(SmallTest.class, MediumTest.class,
                        LargeTest.class)));

        SizeFilter(Class<? extends Annotation> annotation) {
            super(annotation);
        }

        @Override
        protected boolean evaluateTest(Description description) {
            final Class<?> testClass = description.getTestClass();
            if (description.getAnnotation(getAnnotationClass()) != null) {
                return true;
            } else if (testClass != null && testClass.isAnnotationPresent(getAnnotationClass())) {
                // size annotation matched at class level. Make sure method doesn't have any other
                // size annotations
                for (Annotation a : description.getAnnotations()) {
                    if (ALL_SIZES.contains(a.annotationType())) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Filter out tests whose method or class has been annotated with given filter.
     */
    private static class AnnotationExclusionFilter extends ParentFilter {

        private final Class<? extends Annotation> mAnnotationClass;

        AnnotationExclusionFilter(Class<? extends Annotation> annotation) {
            mAnnotationClass = annotation;
        }

        @Override
        protected boolean evaluateTest(Description description) {
            final Class<?> testClass = description.getTestClass();
            if ((testClass != null && testClass.isAnnotationPresent(mAnnotationClass))
                    || (description.getAnnotation(mAnnotationClass) != null)) {
                return false;
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String describe() {
            return String.format("not annotation %s", mAnnotationClass.getName());
        }
    }

    private class SdkSuppressFilter extends ParentFilter {

        @Override
        protected boolean evaluateTest(Description description) {
            final SdkSuppress s = getAnnotationForTest(description);
            if (s != null && getDeviceSdkInt() < s.minSdkVersion()) {
                return false;
            }
            return true;
        }

        private SdkSuppress getAnnotationForTest(Description description) {
            final SdkSuppress s = description.getAnnotation(SdkSuppress.class);
            if (s != null) {
                return s;
            }
            final Class<?> testClass = description.getTestClass();
            if (testClass != null) {
                return testClass.getAnnotation(SdkSuppress.class);
            }
            return null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String describe() {
            return String.format("skip tests annotated with SdkSuppress if necessary");
        }
    }

    /**
     * Class that filters out tests annotated with {@link RequiresDevice} when running on emulator
     */
    private class RequiresDeviceFilter extends AnnotationExclusionFilter {

        RequiresDeviceFilter() {
            super(RequiresDevice.class);
        }

        @Override
        protected boolean evaluateTest(Description description) {
            if (!super.evaluateTest(description)) {
                // annotation is present - check if device is an emulator
                return !EMULATOR_HARDWARE.equals(getDeviceHardware());
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String describe() {
            return String.format("skip tests annotated with RequiresDevice if necessary");
        }
    }

    private static class ShardingFilter extends Filter {
        private final int mNumShards;
        private final int mShardIndex;

        ShardingFilter(int numShards, int shardIndex) {
            mNumShards = numShards;
            mShardIndex = shardIndex;
        }

        @Override
        public boolean shouldRun(Description description) {
            if (description.isTest()) {
                return (Math.abs(description.hashCode()) % mNumShards) == mShardIndex;
            }
            // this is a suite, explicitly check if any children should run
            for (Description each : description.getChildren()) {
                if (shouldRun(each)) {
                    return true;
                }
            }
            // no children to run, filter this out
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String describe() {
            return String.format("Shard %s of %s shards", mShardIndex, mNumShards);
        }
    }

    /**
     * A {@link Request} that doesn't report an error if all tests are filtered out. Done for
     * consistency with InstrumentationTestRunner.
     */
    private static class LenientFilterRequest extends Request {
        private final Request mRequest;
        private final Filter mFilter;

        public LenientFilterRequest(Request classRequest, Filter filter) {
            mRequest = classRequest;
            mFilter = filter;
        }

        @Override
        public Runner getRunner() {
            try {
                Runner runner = mRequest.getRunner();
                mFilter.apply(runner);
                return runner;
            } catch (NoTestsRemainException e) {
                // don't treat filtering out all tests as an error
                return new BlankRunner();
            }
        }
    }

    /**
     * A {@link Runner} that doesn't do anything
     */
    private static class BlankRunner extends Runner {
        @Override
        public Description getDescription() {
            return Description.createSuiteDescription("no tests found");
        }

        @Override
        public void run(RunNotifier notifier) {
            // do nothing
        }
    }

    public TestRequestBuilder(PrintStream writer, String... apkPaths) {
        this(new DeviceBuildImpl(), writer, apkPaths);
    }

    TestRequestBuilder(DeviceBuild deviceBuildAccessor, PrintStream writer, String... apkPaths) {
        mDeviceBuild = deviceBuildAccessor;
        mApkPaths = apkPaths;
        mTestLoader = new TestLoader(writer);
    }

    /**
     * Add a test class to be executed. All test methods in this class will be executed.
     *
     * @param className
     */
    public void addTestClass(String className) {
        mTestLoader.loadClass(className);
    }

    /**
     * Adds a test method to run.
     * <p/>
     * Currently only supports one test method to be run.
     */
    public void addTestMethod(String testClassName, String testMethodName) {
        Class<?> clazz = mTestLoader.loadClass(testClassName);
        if (clazz != null) {
            mClassMethodFilter.add(testClassName, testMethodName);
        }
    }

    /**
     * A {@link Filter} to support the ability to filter out multiple classes#methodes combinations.
     */
    private static class ClassAndMethodFilter extends Filter {

        private Map<String, MethodFilter> mClassMethodFilterMap
                = new HashMap<String, MethodFilter>();

        @Override
        public boolean shouldRun(Description description) {
            if (mClassMethodFilterMap.isEmpty()) {
                return true;
            }
            if (description.isTest()) {
                MethodFilter mf = mClassMethodFilterMap.get(description.getClassName());
                if (mf != null) {
                    return mf.shouldRun(description);
                }
            } else {
                // Check all children, if any
                for (Description child : description.getChildren()) {
                    if (shouldRun(child)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return "Class and method filter";
        }

        public void add(String className, String methodName) {
            MethodFilter mf = mClassMethodFilterMap.get(className);
            if (mf == null) {
                mf = new MethodFilter(className);
                mClassMethodFilterMap.put(className, mf);
            }
            mf.add(methodName);
        }
    }

    /**
     * A {@link Filter} used to filter out desired test methods from a given class
     */
    private static class MethodFilter extends Filter {

        private final String mClassName;
        private Set<String> mMethodNames = new HashSet<String>();

        /**
         * Constructs a method filter for a given class
         * @param className  name of the class the method belongs to
         */
        public MethodFilter(String className) {
            mClassName = className;
        }

        @Override
        public String describe() {
            return "Method filter for " + mClassName + " class";
        }

        @Override
        public boolean shouldRun(Description description) {
            if (description.isTest()) {
                String methodName = description.getMethodName();
                // Parameterized tests append "[#]" at the end of the method names.
                // For instance, "getFoo" would become "getFoo[0]".
                methodName = stripParameterizedSuffix(methodName);
                return mMethodNames.contains(methodName);
            }
            // At this point, this could only be a description of this filter
            return true;

        }

        // Strips out the parameterized suffix if it exists
        private String stripParameterizedSuffix(String name) {
            Pattern suffixPattern = Pattern.compile(".+(\\[[0-9]+\\])$");
            if (suffixPattern.matcher(name).matches()) {
                name = name.substring(0, name.lastIndexOf('['));
            }
            return name;
        }

        public void add(String methodName) {
            mMethodNames.add(methodName);
        }
    }

    /**
     * Run only tests within given java package
     * @param testPackage
     */
    public void addTestPackageFilter(String testPackage) {
        mTestPackageName = testPackage;
    }

    /**
     * Run only tests with given size
     * @param testSize
     */
    public void addTestSizeFilter(String testSize) {
        if (SMALL_SIZE.equals(testSize)) {
            mFilter = mFilter.intersect(new SizeFilter(SmallTest.class));
        } else if (MEDIUM_SIZE.equals(testSize)) {
            mFilter = mFilter.intersect(new SizeFilter(MediumTest.class));
        } else if (LARGE_SIZE.equals(testSize)) {
            mFilter = mFilter.intersect(new SizeFilter(LargeTest.class));
        } else {
            Log.e(LOG_TAG, String.format("Unrecognized test size '%s'", testSize));
        }
    }

    /**
     * Only run tests annotated with given annotation class.
     *
     * @param annotation the full class name of annotation
     */
    public void addAnnotationInclusionFilter(String annotation) {
        Class<? extends Annotation> annotationClass = loadAnnotationClass(annotation);
        if (annotationClass != null) {
            mFilter = mFilter.intersect(new AnnotationInclusionFilter(annotationClass));
        }
    }

    /**
     * Skip tests annotated with given annotation class.
     *
     * @param notAnnotation the full class name of annotation
     */
    public void addAnnotationExclusionFilter(String notAnnotation) {
        Class<? extends Annotation> annotationClass = loadAnnotationClass(notAnnotation);
        if (annotationClass != null) {
            mFilter = mFilter.intersect(new AnnotationExclusionFilter(annotationClass));
        }
    }

    public void addShardingFilter(int numShards, int shardIndex) {
        mFilter = mFilter.intersect(new ShardingFilter(numShards, shardIndex));
    }

    /**
     * Build a request that will generate test started and test ended events, but will skip actual
     * test execution.
     */
    public void setSkipExecution(boolean b) {
        mSkipExecution = b;
    }

    /**
     * Builds the {@link TestRequest} based on current contents of added classes and methods.
     * <p/>
     * If no classes have been explicitly added, will scan the classpath for all tests.
     *
     */
    public TestRequest build(Instrumentation instr, Bundle bundle) {
        if (mTestLoader.isEmpty()) {
            // no class restrictions have been specified. Load all classes
            loadClassesFromClassPath();
        }

        Request request = classes(instr, bundle, mSkipExecution, new Computer(),
                mTestLoader.getLoadedClasses().toArray(new Class[0]));
        return new TestRequest(mTestLoader.getLoadFailures(), new LenientFilterRequest(request, mFilter));
    }

    /**
     * Create a <code>Request</code> that, when processed, will run all the tests
     * in a set of classes.
     *
     * @param instr the {@link Instrumentation} to inject into any tests that require it
     * @param bundle the {@link Bundle} of command line args to inject into any tests that require
     *         it
     * @param skipExecution whether or not to skip actual test execution
     * @param computer Helps construct Runners from classes
     * @param classes the classes containing the tests
     * @return a <code>Request</code> that will cause all tests in the classes to be run
     */
    private static Request classes(Instrumentation instr, Bundle bundle, boolean skipExecution,
            Computer computer, Class<?>... classes) {
        try {
            AndroidRunnerBuilder builder = new AndroidRunnerBuilder(instr, bundle, skipExecution);
            Runner suite = computer.getSuite(builder, classes);
            return Request.runner(suite);
        } catch (InitializationError e) {
            throw new RuntimeException(
                    "Suite constructor, called as above, should always complete");
        }
    }

    private void loadClassesFromClassPath() {
        Collection<String> classNames = getClassNamesFromClassPath();
        for (String className : classNames) {
            mTestLoader.loadIfTest(className);
        }
    }

    private Collection<String> getClassNamesFromClassPath() {
        Log.i(LOG_TAG, String.format("Scanning classpath to find tests in apks %s",
                Arrays.toString(mApkPaths)));
        ClassPathScanner scanner = new ClassPathScanner(mApkPaths);

        ChainedClassNameFilter filter =   new ChainedClassNameFilter();
        // exclude inner classes
        filter.add(new ExternalClassNameFilter());
        if (mTestPackageName != null) {
            // request to run only a specific java package, honor that
            filter.add(new InclusivePackageNameFilter(mTestPackageName));
        } else {
            // scan all packages, but exclude junit packages
            filter.addAll(new ExcludePackageNameFilter("junit"),
                    new ExcludePackageNameFilter("org.junit"),
                    new ExcludePackageNameFilter("org.hamcrest"),
                    // always skip AndroidTestSuite
                    new ExcludePackageNameFilter("android.support.test.internal.runner.junit3"));
        }

        try {
            return scanner.getClassPathEntries(filter);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to scan classes", e);
        }
        return Collections.emptyList();
    }

    /**
     * Factory method for {@link ClassPathScanner}.
     * <p/>
     * Exposed so unit tests can mock.
     */
    ClassPathScanner createClassPathScanner(String... apkPaths) {
        return new ClassPathScanner(apkPaths);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Annotation> loadAnnotationClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (Class<? extends Annotation>)clazz;
        } catch (ClassNotFoundException e) {
            Log.e(LOG_TAG, String.format("Could not find annotation class: %s", className));
        } catch (ClassCastException e) {
            Log.e(LOG_TAG, String.format("Class %s is not an annotation", className));
        }
        return null;
    }

    private int getDeviceSdkInt() {
        return mDeviceBuild.getSdkVersionInt();
    }

    private String getDeviceHardware() {
        return mDeviceBuild.getHardware();
    }
}
