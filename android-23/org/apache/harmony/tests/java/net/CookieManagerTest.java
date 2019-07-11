/* Licensed to the Apache Software Foundation (ASF) under one or more
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
package org.apache.harmony.tests.java.net;

import junit.framework.TestCase;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CookieManagerTest extends TestCase {

    private static void checkValidParams4Get(URI uri,
            Map<String, List<String>> map) throws IOException {
        CookieManager manager = new CookieManager();
        try {
            manager.get(uri, map);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }

    }

    private static void checkValidParams4Put(URI uri,
            Map<String, List<String>> map) throws IOException {
        CookieManager manager = new CookieManager();
        try {
            manager.put(uri, map);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }

    }

    /**
     * {@link java.net.CookieManager#get(java.net.URI, java.util.Map)} &
     * {@link java.net.CookieManager#put(java.net.URI, java.util.Map)}
     * IllegalArgumentException
     * @since 1.6
     */
    public void test_Put_Get_LURI_LMap_exception() throws IOException,
            URISyntaxException {
        // get
        checkValidParams4Get(new URI(""), null);
        checkValidParams4Get(new URI("http://www.test.com"), null);
        checkValidParams4Get(null, null);
        checkValidParams4Get(null, new HashMap<String, List<String>>());

        // put
        checkValidParams4Put(new URI(""), null);
        checkValidParams4Put(new URI("http://www.test.com"), null);
        checkValidParams4Put(null, null);
        checkValidParams4Put(null, new HashMap<String, List<String>>());
    }

    private static Map<String, List<String>> addCookie(String[][] cookies) {
        Map<String, List<String>> responseHeaders = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < cookies.length; i++) {
            List<String> fields = new ArrayList<String>();
            for (int j = 1; j < cookies[i].length; j += 2) {
                fields.add(cookies[i][j]);
            }
            responseHeaders.put(cookies[i][0], fields);
        }
        return responseHeaders;
    }

    private static CookieManager store(String[][] cookies,
            Map<String, List<String>> responseHeaders, CookiePolicy policy)
            throws IOException, URISyntaxException {
        CookieManager manager = new CookieManager(null, policy);
        // Put all cookies into manager
        for (int i = 0; i < cookies.length; i++) {
            for (int j = 2; j < cookies[i].length; j += 2) {
                URI uri = new URI(cookies[i][j]);
                manager.put(uri, responseHeaders);
            }
        }
        return manager;
    }

    /**
     * Unlike the RI, we flatten all matching cookies into a single Cookie header
     * instead of sending down multiple cookie headers. Also, when no cookies match
     * a given URI, we leave the requestHeaders unmodified.
     *
     * @since 1.6
     */
    public void test_Put_Get_LURI_LMap() throws IOException, URISyntaxException {
        // cookie-key | (content, URI)...
        String[][] cookies = {
                { "Set-cookie",
                        "Set-cookie:PREF=test;path=/;domain=.b.c;", "http://a.b.c/",
                        "Set-cookie:PREF1=test2;path=/;domain=.beg.com;", "http://a.b.c/" },

                { "Set-cookie2",
                        "Set-cookie2:NAME1=VALUE1;path=/te;domain=.b.c;", "http://a.b.c/test" },

                { "Set-cookie",
                        "Set-cookie2:NAME=VALUE;path=/;domain=.beg.com;", "http://a.beg.com/test",
                        "Set-cookie2:NAME1=VALUE1;path=/;domain=.beg.com;", "http://a.beg.com/test" },

                { "Set-cookie2",
                        "Set-cookie3:NAME=VALUE;path=/;domain=.test.org;", "http://a.test.org/test" },

                { null,
                        "Set-cookie3:NAME=VALUE;path=/te;domain=.test.org;", "http://a.test.org/test" },

                { "Set-cookie2",
                        "lala", "http://a.test.org/test" }

        };

        // requires path of cookie is the prefix of uri
        // domain of cookie must match that of uri
        Map<String, List<String>> responseHeaders = addCookie(new String[][] {
                cookies[0], cookies[1] });
        CookieManager manager = store(
                new String[][] { cookies[0], cookies[1] }, responseHeaders,
                null);

        HashMap<String, List<String>> dummyMap = new HashMap<String, List<String>>();
        Map<String, List<String>> map = manager.get(new URI("http://a.b.c/"),
                dummyMap);

        assertEquals(1, map.size());
        List<String> list = map.get("Cookie");
        assertEquals(1, list.size());

        // requires path of cookie is the prefix of uri
        map = manager.get(new URI("http://a.b.c/te"), dummyMap);
        list = map.get("Cookie");
        assertEquals(1, list.size());
        assertTrue(list.get(0).contains("PREF=test"));
        // Cookies from "/test" should *not* match the URI "/te".
        assertFalse(list.get(0).contains("NAME=VALUE"));

        // If all cookies are of version 1, then $version=1 will be added
        // ,no matter the value cookie-key
        responseHeaders = addCookie(new String[][] { cookies[2] });
        manager = store(new String[][] { cookies[2] }, responseHeaders, null);
        map = manager.get(new URI("http://a.beg.com/test"), dummyMap);
        list = map.get("Cookie");
        assertEquals(1, list.size());
        assertTrue(list.get(0).startsWith("$Version=\"1\""));

        // cookie-key will not have effect on determining cookie version
        responseHeaders = addCookie(new String[][] { cookies[3] });
        manager = store(new String[][] { cookies[3] }, responseHeaders, null);
        map = manager.get(new URI("http://a.test.org/"), responseHeaders);
        list = map.get("Cookie");
        assertEquals(1, list.size());
        assertEquals("Set-cookie3:NAME=VALUE", list.get(0));

        // When key is null, no cookie can be stored/retrieved, even if policy =
        // ACCEPT_ALL
        responseHeaders = addCookie(new String[][] { cookies[4] });
        manager = store(new String[][] { cookies[4] }, responseHeaders,
                CookiePolicy.ACCEPT_ALL);
        map = manager.get(new URI("http://a.test.org/"), responseHeaders);
        list = map.get("Cookie");
        assertNull(list);

        // All cookies will be rejected if policy == ACCEPT_NONE
        responseHeaders = addCookie(new String[][] { cookies[3] });
        manager = store(new String[][] { cookies[3] }, responseHeaders,
                CookiePolicy.ACCEPT_NONE);
        map = manager.get(new URI("http://a.test.org/"), responseHeaders);
        list = map.get("Cookie");
        assertNull(list);

        responseHeaders = addCookie(new String[][] { cookies[5] });
        manager = store(new String[][] { cookies[5] }, responseHeaders,
                CookiePolicy.ACCEPT_ALL);
        list = map.get("Cookie");
        assertNull(list);

        try {
            map.put(null, null);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }

    }

    /**
     * {@link java.net.CookieManager#CookieManager()}
     * @since 1.6
     */
    public void test_CookieManager() {
        CookieManager cookieManager = new CookieManager();
        assertNotNull(cookieManager);
        assertNotNull(cookieManager.getCookieStore());
    }

    /**
     * {@link java.net.CookieManager#CookieManager(java.net.CookieStore, java.net.CookiePolicy)}
     * @since 1.6
     */
    public void testCookieManager_LCookieStore_LCookiePolicy() {
        class DummyStore implements CookieStore {
            public String getName() {
                return "A dummy store";
            }

            public void add(URI uri, HttpCookie cookie) {
                // expected
            }

            public List<HttpCookie> get(URI uri) {
                return null;
            }

            public List<HttpCookie> getCookies() {
                return null;
            }

            public List<URI> getURIs() {
                return null;
            }

            public boolean remove(URI uri, HttpCookie cookie) {
                return false;
            }

            public boolean removeAll() {
                return false;
            }
        }
        CookieStore store = new DummyStore();
        CookieManager cookieManager = new CookieManager(store,
                CookiePolicy.ACCEPT_ALL);
        assertEquals("A dummy store", ((DummyStore) cookieManager
                .getCookieStore()).getName());
        assertSame(store, cookieManager.getCookieStore());
    }

    /**
     * {@link java.net.CookieManager#setCookiePolicy(java.net.CookiePolicy)}
     * @since 1.6
     */
    public void test_SetCookiePolicy_LCookiePolicy() throws URISyntaxException,
            IOException {

        // Policy = ACCEPT_NONE
        CookieManager manager = new CookieManager();
        manager.setCookiePolicy(CookiePolicy.ACCEPT_NONE);
        Map<String, List<String>> responseHeaders = new TreeMap<String, List<String>>();
        URI uri = new URI("http://a.b.c");
        manager.put(uri, responseHeaders);
        Map<String, List<String>> map = manager.get(uri,
                new HashMap<String, List<String>>());

        assertEquals(0, map.size());

        // Policy = ACCEPT_ALL
        manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        responseHeaders = new TreeMap<String, List<String>>();
        ArrayList<String> list = new ArrayList<String>();
        list.add("test=null");
        responseHeaders.put("Set-cookie", list);
        uri = new URI("http://b.c.d");
        manager.put(uri, responseHeaders);
        map = manager.get(uri, new HashMap<String, List<String>>());
        assertEquals(1, map.size());
    }

    /**
     * {@link java.net.CookieManager#getCookieStore()}
     * @since 1.6
     */
    public void test_GetCookieStore() {
        CookieManager cookieManager = new CookieManager();
        CookieStore store = cookieManager.getCookieStore();
        assertNotNull(store);
    }

}
