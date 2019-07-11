/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.harmony.tests.java.lang;

import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;

public class ClassLoaderTest extends TestCase {

    /** A resource known to be present in the boot classpath. */
    private static final String BOOT_RESOURCE_NAME = "java/util/logging/logging.properties";

    /** A resource known to be present in the classpath associated with the test class. */
    private static final String TEST_RESOURCE_NAME = ClassTest.RESOURCE_ABS_NAME;

    private ClassLoader testClassLoader;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        testClassLoader = getClass().getClassLoader();
    }

    /**
     * java.lang.ClassLoader#getSystemClassLoader()
     */
    public void test_getSystemClassLoader() {
        // Test for method java.lang.ClassLoader
        // java.lang.ClassLoader.getSystemClassLoader()
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        assertNotNull(cl);

        // The SystemClassLoader's parent should be the Boot classloader, which is used to load
        // the various libcore classes.
        assertNotNull(cl.getParent());
        Class<?> libcoreClass = Integer.class;
        assertSame(cl.getParent(), libcoreClass.getClassLoader());

        // It is difficult to test further because the CTS tests run as an instrumented TestCase.
        // Android apps do not have a system classpath, and rely on an application classloader to
        // load app classes and resources, not the System ClassLoader. The System ClassLoader is not
        // usually the parent of the application class loader.
    }

    /**
     * java.lang.ClassLoader#getSystemResource(java.lang.String)
     */
    public void test_getSystemResourceLjava_lang_String() {
        // Test for method java.net.URL
        // java.lang.ClassLoader.getSystemResource(java.lang.String)

        // It is difficult to test this because the CTS tests run as an instrumented TestCase.
        // Android apps do not have a system classpath, and rely on an application classloader to
        // load app classes and resources, not the System ClassLoader.
    }

    /**
     * java.lang.ClassLoader#getResource(java.lang.String)
     */
    public void test_testClassLoader_getResourceLjava_lang_String() {
        // Test for method java.net.URL
        // java.lang.ClassLoader.getResource(java.lang.String)

        // Test basic class loader behavior for the ClassLoader that was used to load the test
        // class while being deliberately vague about which classloader it actually is.

        ClassLoader parentClassLoader = testClassLoader.getParent();
        assertNull(parentClassLoader.getResource(TEST_RESOURCE_NAME));
        assertGetResourceIsValid(parentClassLoader, BOOT_RESOURCE_NAME);

        assertGetResourceIsValid(testClassLoader, TEST_RESOURCE_NAME);
        assertGetResourceIsValid(testClassLoader, BOOT_RESOURCE_NAME);
    }

    /**
     * java.lang.ClassLoader#getResourceAsStream(java.lang.String)
     */
    public void test_testClassLoader_getResourceAsStreamLjava_lang_String() throws Exception {
        // Test for method java.io.InputStream
        // java.lang.ClassLoader.getResourceAsStream(java.lang.String)

        // Test basic class loader behavior for the ClassLoader that was used to load the test
        // class while being deliberately vague about which classloader it actually is.

        ClassLoader parentClassLoader = testClassLoader.getParent();
        assertGetResourceAsStreamNotNull(parentClassLoader, BOOT_RESOURCE_NAME);
        assertNull(parentClassLoader.getResourceAsStream(TEST_RESOURCE_NAME));

        assertGetResourceAsStreamNotNull(testClassLoader, BOOT_RESOURCE_NAME);
        assertGetResourceAsStreamNotNull(testClassLoader, TEST_RESOURCE_NAME);
    }

    public void test_testClassLoader_loadClass() throws Exception {
        // Test basic class loader behavior for the ClassLoader that was used to load the test
        // class while being deliberately vague about which classloader it actually is.
        String integerClassName = Integer.class.getName();
        String testClassName = ClassLoaderTest.class.getName();

        ClassLoader parentClassLoader = testClassLoader.getParent();
        assertSame(Integer.class, parentClassLoader.loadClass(integerClassName));
        try {
            parentClassLoader.loadClass(testClassName);
            fail();
        } catch (ClassNotFoundException expected) {
        }

        assertSame(Integer.class, testClassLoader.loadClass(integerClassName));
        assertSame(this.getClass(), testClassLoader.loadClass(testClassName));
    }

    //Regression Test for JIRA-2047
    public void test_testClassLoader_getResourceAsStream_withSharpChar() throws Exception {
        assertGetResourceAsStreamNotNull(testClassLoader, ClassTest.SHARP_RESOURCE_ABS_NAME);
    }

    private static void assertGetResourceAsStreamNotNull(ClassLoader classLoader,
            String resourceName) throws IOException {
        InputStream is = null;
        try {
            is = classLoader.getResourceAsStream(resourceName);
            assertNotNull(is);
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private static void assertGetResourceIsValid(ClassLoader classLoader, String resourceName) {
        java.net.URL u = classLoader.getResource(resourceName);
        assertNotNull(u);
        InputStream is = null;
        try {
            is = u.openStream();
            assertNotNull(is);
            is.close();
        } catch (IOException e) {
            fail("IOException getting stream for resource : " + e.getMessage());
        }
    }
}