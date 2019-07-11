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
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.RandomAccess;

public class CookieStoreTest extends TestCase {

    private CookieManager cookieManager;

    private CookieStore cookieStore;

    /**
     * java.net.CookieStore#add(URI, HttpCookie)
     * @since 1.6
     */
    public void test_add_LURI_LHttpCookie() throws URISyntaxException {
        URI uri = new URI("http://harmony.test.unit.org");
        HttpCookie cookie = new HttpCookie("name1", "value1");
        cookie.setDiscard(true);

        // This needn't throw. We should use the cookie's domain, if set.
        // If no domain is set, this cookie will languish in the store until
        // it expires.
        cookieStore.add(null, cookie);

        try {
            cookieStore.add(uri, null);
            fail("should throw NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }

        try {
            cookieStore.add(null, null);
            fail("should throw NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }

        cookieStore.add(uri, cookie);
        List<HttpCookie> list = cookieStore.get(uri);
        assertEquals(1, list.size());
        assertTrue(list.contains(cookie));

        HttpCookie cookie2 = new HttpCookie("  NaME1   ", "  TESTVALUE1   ");
        cookieStore.add(uri, cookie2);
        list = cookieStore.get(uri);
        assertEquals(1, list.size());
        assertEquals("  TESTVALUE1   ", list.get(0).getValue());
        assertTrue(list.contains(cookie2));

        // domain and path attributes works
        HttpCookie anotherCookie = new HttpCookie("name1", "value1");
        anotherCookie.setDomain("domain");
        anotherCookie.setPath("Path");
        cookieStore.add(uri, anotherCookie);
        list = cookieStore.get(uri);
        assertEquals(2, list.size());
        assertNull(list.get(0).getDomain());
        assertEquals("domain", list.get(1).getDomain());
        assertEquals("Path", list.get(1).getPath());

        URI uri2 = new URI("http://.test.unit.org");
        HttpCookie cookie3 = new HttpCookie("NaME2", "VALUE2");
        cookieStore.add(uri2, cookie3);
        list = cookieStore.get(uri2);
        assertEquals(1, list.size());
        assertEquals("VALUE2", list.get(0).getValue());
        list = cookieStore.getCookies();
        assertEquals(3, list.size());

        // expired cookie won't be selected.
        HttpCookie cookie4 = new HttpCookie("cookie4", "value4");
        cookie4.setMaxAge(-2);
        assertTrue(cookie4.hasExpired());
        cookieStore.add(uri2, cookie4);
        list = cookieStore.getCookies();
        assertEquals(3, list.size());
        assertFalse(cookieStore.remove(uri2, cookie4));

        cookie4.setMaxAge(3000);
        cookie4.setDomain("domain");
        cookie4.setPath("path");
        cookieStore.add(uri2, cookie4);
        list = cookieStore.get(uri2);
        assertEquals(2, list.size());

        cookieStore.add(uri, cookie4);
        list = cookieStore.get(uri);
        assertEquals(3, list.size());
        list = cookieStore.get(uri2);
        assertEquals(2, list.size());
        list = cookieStore.getCookies();
        assertEquals(4, list.size());

        URI baduri = new URI("bad_url");
        HttpCookie cookie6 = new HttpCookie("cookie5", "value5");
        cookieStore.add(baduri, cookie6);
        list = cookieStore.get(baduri);
        assertTrue(list.contains(cookie6));
    }

    /**
     * java.net.CookieStore#get(URI)
     * @since 1.6
     */
    public void test_get_LURI() throws URISyntaxException {
        try {
            cookieStore.get(null);
            fail("should throw NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }

        URI uri1 = new URI("http://get.uri1.test.org");
        List<HttpCookie> list = cookieStore.get(uri1);
        assertTrue(list.isEmpty());

        HttpCookie cookie1 = new HttpCookie("cookie_name1", "cookie_value1");
        HttpCookie cookie2 = new HttpCookie("cookie_name2", "cookie_value2");
        cookieStore.add(uri1, cookie1);
        cookieStore.add(uri1, cookie2);
        URI uri2 = new URI("http://get.uri2.test.org");
        HttpCookie cookie3 = new HttpCookie("cookie_name3", "cookie_value3");
        cookieStore.add(uri2, cookie3);
        list = cookieStore.get(uri1);
        assertEquals(2, list.size());
        list = cookieStore.get(uri2);
        assertEquals(1, list.size());

        // domain-match cookies also be selected.
        HttpCookie cookie4 = new HttpCookie("cookie_name4", "cookie_value4");
        cookie4.setDomain(".uri1.test.org");
        cookieStore.add(uri2, cookie4);
        list = cookieStore.get(uri1);
        assertEquals(3, list.size());

        cookieStore.add(uri1, cookie4);
        list = cookieStore.get(uri1);
        assertEquals(3, list.size());
        list = cookieStore.get(uri2);
        assertEquals(2, list.size());

        // expired cookie won't be selected.
        HttpCookie cookie5 = new HttpCookie("cookie_name5", "cookie_value5");
        cookie5.setMaxAge(-333);
        assertTrue(cookie5.hasExpired());
        cookieStore.add(uri1, cookie5);
        list = cookieStore.get(uri1);
        assertEquals(3, list.size());
        assertFalse(cookieStore.remove(uri1, cookie5));
        list = cookieStore.getCookies();
        assertEquals(4, list.size());

        cookie4.setMaxAge(-123);
        list = cookieStore.get(uri1);
        assertEquals(2, list.size());
        list = cookieStore.getCookies();
        assertEquals(3, list.size());
        // expired cookies are also deleted even if it domain-matches the URI
        HttpCookie cookie6 = new HttpCookie("cookie_name6", "cookie_value6");
        cookie6.setMaxAge(-2);
        cookie6.setDomain(".uri1.test.org");
        cookieStore.add(uri2, cookie6);
        list = cookieStore.get(uri1);
        assertEquals(2, list.size());
        assertFalse(cookieStore.remove(null, cookie6));

        URI uri3 = new URI("http://get.uri3.test.org");
        assertTrue(cookieStore.get(uri3).isEmpty());
        URI baduri = new URI("invalid_uri");
        assertTrue(cookieStore.get(baduri).isEmpty());
    }

    /**
     * java.net.CookieStore#getCookies()
     * @since 1.6
     */
    public void test_getCookies() throws URISyntaxException {
        List<HttpCookie> list = cookieStore.getCookies();
        assertTrue(list.isEmpty());
        assertTrue(list instanceof RandomAccess);

        HttpCookie cookie1 = new HttpCookie("cookie_name", "cookie_value");
        URI uri1 = new URI("http://getcookies1.test.org");
        cookieStore.add(uri1, cookie1);
        list = cookieStore.getCookies();
        assertTrue(list.contains(cookie1));

        HttpCookie cookie2 = new HttpCookie("cookie_name2", "cookie_value2");
        URI uri2 = new URI("http://getcookies2.test.org");
        cookieStore.add(uri2, cookie2);
        list = cookieStore.getCookies();
        assertEquals(2, list.size());
        assertTrue(list.contains(cookie1));
        assertTrue(list.contains(cookie2));

        // duplicated cookie won't be selected.
        cookieStore.add(uri2, cookie1);
        list = cookieStore.getCookies();
        assertEquals(2, list.size());
        // expired cookie won't be selected.
        HttpCookie cookie3 = new HttpCookie("cookie_name3", "cookie_value3");
        cookie3.setMaxAge(-1357);
        cookieStore.add(uri1, cookie3);
        list = cookieStore.getCookies();
        assertEquals(2, list.size());

        try {
            list.add(new HttpCookie("readOnlyName", "readOnlyValue"));
            fail("should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }

        try {
            list.remove(new HttpCookie("readOnlyName", "readOnlyValue"));
            fail("should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    /**
     * java.net.CookieStore#getURIs()
     * @since 1.6
     */
    public void test_getURIs() throws URISyntaxException {
        List<URI> list = cookieStore.getURIs();
        assertTrue(list.isEmpty());

        URI uri1 = new URI("http://geturis1.test.com");
        HttpCookie cookie1 = new HttpCookie("cookie_name1", "cookie_value1");
        cookieStore.add(uri1, cookie1);
        list = cookieStore.getURIs();
        assertEquals("geturis1.test.com", list.get(0).getHost());

        HttpCookie cookie2 = new HttpCookie("cookie_name2", "cookie_value2");
        cookieStore.add(uri1, cookie2);
        list = cookieStore.getURIs();
        assertEquals(1, list.size());

        URI uri2 = new URI("http://geturis2.test.com");
        cookieStore.add(uri2, cookie2);
        list = cookieStore.getURIs();
        assertEquals(2, list.size());
        assertTrue(list.contains(uri1));
        assertTrue(list.contains(uri2));
    }

    /**
     * java.net.CookieStore#remove(URI, HttpCookie)
     * @since 1.6
     */
    public void test_remove_LURI_LHttpCookie() throws URISyntaxException {
        URI uri1 = new URI("http://remove1.test.com");
        HttpCookie cookie1 = new HttpCookie("cookie_name1", "cookie_value1");
        try {
            cookieStore.remove(uri1, null);
            fail("should throw NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }
        assertFalse(cookieStore.remove(uri1, cookie1));
        assertFalse(cookieStore.remove(null, cookie1));

        cookieStore.add(uri1, cookie1);
        URI uri2 = new URI("http://remove2.test.com");
        HttpCookie cookie2 = new HttpCookie("cookie_name2", "cookie_value2");
        cookieStore.add(uri2, cookie2);
        assertTrue(cookieStore.remove(uri1, cookie1));
        assertFalse(cookieStore.remove(uri1, cookie1));
        assertEquals(2, cookieStore.getURIs().size());
        assertEquals(1, cookieStore.getCookies().size());
        assertTrue(cookieStore.remove(uri2, cookie2));
        assertFalse(cookieStore.remove(uri2, cookie2));
        assertEquals(2, cookieStore.getURIs().size());
        assertEquals(0, cookieStore.getCookies().size());

        assertTrue(cookieStore.removeAll());
        cookieStore.add(uri1, cookie1);
        cookieStore.add(uri2, cookie2);
        HttpCookie cookie3 = new HttpCookie("cookie_name3", "cookie_value3");
        assertFalse(cookieStore.remove(null, cookie3));
        // No guarantees on behavior if we call remove with a different
        // uri from the one originally associated with the cookie.
        assertFalse(cookieStore.remove(null, cookie1));
        assertTrue(cookieStore.remove(uri1, cookie1));
        assertFalse(cookieStore.remove(uri1, cookie1));

        assertEquals(2, cookieStore.getURIs().size());
        assertEquals(1, cookieStore.getCookies().size());
        assertTrue(cookieStore.remove(uri2, cookie2));
        assertFalse(cookieStore.remove(uri2, cookie2));
        assertEquals(2, cookieStore.getURIs().size());
        assertEquals(0, cookieStore.getCookies().size());

        cookieStore.removeAll();
        // expired cookies can also be deleted.
        cookie2.setMaxAge(-34857);
        cookieStore.add(uri2, cookie2);
        assertTrue(cookieStore.remove(uri2, cookie2));
        assertFalse(cookieStore.remove(uri2, cookie2));
        assertEquals(0, cookieStore.getCookies().size());

        cookie2.setMaxAge(34857);
        cookieStore.add(uri1, cookie1);
        cookieStore.add(uri2, cookie1);
        cookieStore.add(uri2, cookie2);
        assertTrue(cookieStore.remove(uri1, cookie1));
        assertFalse(cookieStore.remove(uri1, cookie1));
        assertTrue(cookieStore.get(uri2).contains(cookie1));
        assertTrue(cookieStore.get(uri2).contains(cookie2));
        assertEquals(0, cookieStore.get(uri1).size());
        cookieStore.remove(uri2, cookie2);

        cookieStore.removeAll();
        cookieStore.add(uri2, cookie2);
        cookieStore.add(uri1, cookie1);
        assertEquals(2, cookieStore.getCookies().size());
        assertFalse(cookieStore.remove(uri2, cookie1));
        assertTrue(cookieStore.remove(uri1, cookie1));
        assertEquals(2, cookieStore.getURIs().size());
        assertEquals(1, cookieStore.getCookies().size());
        assertTrue(cookieStore.getCookies().contains(cookie2));

        cookieStore.removeAll();
        URI uri3 = new URI("http://remove3.test.com");
        URI uri4 = new URI("http://test.com");
        HttpCookie cookie4 = new HttpCookie("cookie_name4", "cookie_value4");
        cookie4.setDomain(".test.com");
        cookie2.setMaxAge(-34857);
        cookie3.setMaxAge(-22);
        cookie4.setMaxAge(-45);
        cookieStore.add(uri1, cookie1);
        cookieStore.add(uri2, cookie2);
        cookieStore.add(uri3, cookie3);
        cookieStore.add(uri4, cookie4);
        assertEquals(0, cookieStore.get(uri2).size());
        assertFalse(cookieStore.remove(uri2, cookie2));
        assertTrue(cookieStore.remove(uri3, cookie3));
        assertFalse(cookieStore.remove(uri4, cookie4));
    }

    /**
     * java.net.CookieStore#test_removeAll()
     * @since 1.6
     */
    public void test_removeAll() throws URISyntaxException {
        assertFalse(cookieStore.removeAll());

        URI uri1 = new URI("http://removeAll1.test.com");
        HttpCookie cookie1 = new HttpCookie("cookie_name1", "cookie_value1");
        cookieStore.add(uri1, cookie1);
        URI uri2 = new URI("http://removeAll2.test.com");
        HttpCookie cookie2 = new HttpCookie("cookie_name2", "cookie_value2");
        cookieStore.add(uri2, cookie2);

        assertTrue(cookieStore.removeAll());
        assertTrue(cookieStore.getURIs().isEmpty());
        assertTrue(cookieStore.getCookies().isEmpty());

        assertFalse(cookieStore.removeAll());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        cookieManager = new CookieManager();
        cookieStore = cookieManager.getCookieStore();
    }

    @Override
    protected void tearDown() throws Exception {
        cookieManager = null;
        cookieStore = null;
        super.tearDown();
    }

}
