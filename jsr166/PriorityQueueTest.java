/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 * Other contributors include Andrew Wright, Jeffrey Hayes,
 * Pat Fisher, Mike Judd.
 */

package jsr166;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Queue;

import junit.framework.Test;
import junit.framework.TestSuite;

public class PriorityQueueTest extends JSR166TestCase {
    // android-note: Removed because the CTS runner does a bad job of
    // retrying tests that have suite() declarations.
    //
    // public static void main(String[] args) {
    //     main(suite(), args);
    // }
    // public static Test suite() {
    //     return new TestSuite(PriorityQueueTest.class);
    // }

    static class MyReverseComparator implements Comparator {
        public int compare(Object x, Object y) {
            return ((Comparable)y).compareTo(x);
        }
    }

    /**
     * Returns a new queue of given size containing consecutive
     * Integers 0 ... n.
     */
    private PriorityQueue<Integer> populatedQueue(int n) {
        PriorityQueue<Integer> q = new PriorityQueue<Integer>(n);
        assertTrue(q.isEmpty());
        for (int i = n - 1; i >= 0; i -= 2)
            assertTrue(q.offer(Integer.valueOf(i)));
        for (int i = (n & 1); i < n; i += 2)
            assertTrue(q.offer(Integer.valueOf(i)));
        assertFalse(q.isEmpty());
        assertEquals(n, q.size());
        return q;
    }

    /**
     * A new queue has unbounded capacity
     */
    public void testConstructor1() {
        assertEquals(0, new PriorityQueue(SIZE).size());
    }

    /**
     * Constructor throws IAE if capacity argument nonpositive
     */
    public void testConstructor2() {
        try {
            new PriorityQueue(0);
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Initializing from null Collection throws NPE
     */
    public void testConstructor3() {
        try {
            new PriorityQueue((Collection)null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Initializing from Collection of null elements throws NPE
     */
    public void testConstructor4() {
        try {
            new PriorityQueue(Arrays.asList(new Integer[SIZE]));
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Initializing from Collection with some null elements throws NPE
     */
    public void testConstructor5() {
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE - 1; ++i)
            ints[i] = Integer.valueOf(i);
        try {
            new PriorityQueue(Arrays.asList(ints));
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Queue contains all elements of collection used to initialize
     */
    public void testConstructor6() {
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE; ++i)
            ints[i] = Integer.valueOf(i);
        PriorityQueue q = new PriorityQueue(Arrays.asList(ints));
        for (int i = 0; i < SIZE; ++i)
            assertEquals(ints[i], q.poll());
    }

    /**
     * The comparator used in constructor is used
     */
    public void testConstructor7() {
        MyReverseComparator cmp = new MyReverseComparator();
        PriorityQueue q = new PriorityQueue(SIZE, cmp);
        assertEquals(cmp, q.comparator());
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE; ++i)
            ints[i] = Integer.valueOf(i);
        q.addAll(Arrays.asList(ints));
        for (int i = SIZE - 1; i >= 0; --i)
            assertEquals(ints[i], q.poll());
    }

    /**
     * isEmpty is true before add, false after
     */
    public void testEmpty() {
        PriorityQueue q = new PriorityQueue(2);
        assertTrue(q.isEmpty());
        q.add(Integer.valueOf(1));
        assertFalse(q.isEmpty());
        q.add(Integer.valueOf(2));
        q.remove();
        q.remove();
        assertTrue(q.isEmpty());
    }

    /**
     * size changes when elements added and removed
     */
    public void testSize() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(SIZE - i, q.size());
            q.remove();
        }
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.size());
            q.add(Integer.valueOf(i));
        }
    }

    /**
     * offer(null) throws NPE
     */
    public void testOfferNull() {
        PriorityQueue q = new PriorityQueue(1);
        try {
            q.offer(null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * add(null) throws NPE
     */
    public void testAddNull() {
        PriorityQueue q = new PriorityQueue(1);
        try {
            q.add(null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Offer of comparable element succeeds
     */
    public void testOffer() {
        PriorityQueue q = new PriorityQueue(1);
        assertTrue(q.offer(zero));
        assertTrue(q.offer(one));
    }

    /**
     * Offer of non-Comparable throws CCE
     */
    public void testOfferNonComparable() {
        PriorityQueue q = new PriorityQueue(1);
        try {
            q.offer(new Object());
            q.offer(new Object());
            shouldThrow();
        } catch (ClassCastException success) {}
    }

    /**
     * add of comparable succeeds
     */
    public void testAdd() {
        PriorityQueue q = new PriorityQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.size());
            assertTrue(q.add(Integer.valueOf(i)));
        }
    }

    /**
     * addAll(null) throws NPE
     */
    public void testAddAll1() {
        PriorityQueue q = new PriorityQueue(1);
        try {
            q.addAll(null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * addAll of a collection with null elements throws NPE
     */
    public void testAddAll2() {
        PriorityQueue q = new PriorityQueue(SIZE);
        try {
            q.addAll(Arrays.asList(new Integer[SIZE]));
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * addAll of a collection with any null elements throws NPE after
     * possibly adding some elements
     */
    public void testAddAll3() {
        PriorityQueue q = new PriorityQueue(SIZE);
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE - 1; ++i)
            ints[i] = Integer.valueOf(i);
        try {
            q.addAll(Arrays.asList(ints));
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Queue contains all elements of successful addAll
     */
    public void testAddAll5() {
        Integer[] empty = new Integer[0];
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE; ++i)
            ints[i] = Integer.valueOf(SIZE - 1 - i);
        PriorityQueue q = new PriorityQueue(SIZE);
        assertFalse(q.addAll(Arrays.asList(empty)));
        assertTrue(q.addAll(Arrays.asList(ints)));
        for (int i = 0; i < SIZE; ++i)
            assertEquals(Integer.valueOf(i), q.poll());
    }

    /**
     * poll succeeds unless empty
     */
    public void testPoll() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.poll());
        }
        assertNull(q.poll());
    }

    /**
     * peek returns next element, or null if empty
     */
    public void testPeek() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.peek());
            assertEquals(i, q.poll());
            assertTrue(q.peek() == null ||
                       !q.peek().equals(i));
        }
        assertNull(q.peek());
    }

    /**
     * element returns next element, or throws NSEE if empty
     */
    public void testElement() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.element());
            assertEquals(i, q.poll());
        }
        try {
            q.element();
            shouldThrow();
        } catch (NoSuchElementException success) {}
    }

    /**
     * remove removes next element, or throws NSEE if empty
     */
    public void testRemove() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.remove());
        }
        try {
            q.remove();
            shouldThrow();
        } catch (NoSuchElementException success) {}
    }

    /**
     * remove(x) removes x and returns true if present
     */
    public void testRemoveElement() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 1; i < SIZE; i += 2) {
            assertTrue(q.contains(i));
            assertTrue(q.remove(i));
            assertFalse(q.contains(i));
            assertTrue(q.contains(i - 1));
        }
        for (int i = 0; i < SIZE; i += 2) {
            assertTrue(q.contains(i));
            assertTrue(q.remove(i));
            assertFalse(q.contains(i));
            assertFalse(q.remove(i + 1));
            assertFalse(q.contains(i + 1));
        }
        assertTrue(q.isEmpty());
    }

    /**
     * contains(x) reports true when elements added but not yet removed
     */
    public void testContains() {
        PriorityQueue q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertTrue(q.contains(Integer.valueOf(i)));
            q.poll();
            assertFalse(q.contains(Integer.valueOf(i)));
        }
    }

    /**
     * clear removes all elements
     */
    public void testClear() {
        PriorityQueue q = populatedQueue(SIZE);
        q.clear();
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
        q.add(Integer.valueOf(1));
        assertFalse(q.isEmpty());
        q.clear();
        assertTrue(q.isEmpty());
    }

    /**
     * containsAll(c) is true when c contains a subset of elements
     */
    public void testContainsAll() {
        PriorityQueue q = populatedQueue(SIZE);
        PriorityQueue p = new PriorityQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertTrue(q.containsAll(p));
            assertFalse(p.containsAll(q));
            p.add(Integer.valueOf(i));
        }
        assertTrue(p.containsAll(q));
    }

    /**
     * retainAll(c) retains only those elements of c and reports true if changed
     */
    public void testRetainAll() {
        PriorityQueue q = populatedQueue(SIZE);
        PriorityQueue p = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            boolean changed = q.retainAll(p);
            if (i == 0)
                assertFalse(changed);
            else
                assertTrue(changed);

            assertTrue(q.containsAll(p));
            assertEquals(SIZE - i, q.size());
            p.remove();
        }
    }

    /**
     * removeAll(c) removes only those elements of c and reports true if changed
     */
    public void testRemoveAll() {
        for (int i = 1; i < SIZE; ++i) {
            PriorityQueue q = populatedQueue(SIZE);
            PriorityQueue p = populatedQueue(i);
            assertTrue(q.removeAll(p));
            assertEquals(SIZE - i, q.size());
            for (int j = 0; j < i; ++j) {
                Integer x = (Integer)(p.remove());
                assertFalse(q.contains(x));
            }
        }
    }

    /**
     * toArray contains all elements
     */
    public void testToArray() {
        PriorityQueue q = populatedQueue(SIZE);
        Object[] o = q.toArray();
        Arrays.sort(o);
        for (int i = 0; i < o.length; i++)
            assertSame(o[i], q.poll());
    }

    /**
     * toArray(a) contains all elements
     */
    public void testToArray2() {
        PriorityQueue<Integer> q = populatedQueue(SIZE);
        Integer[] ints = new Integer[SIZE];
        Integer[] array = q.toArray(ints);
        assertSame(ints, array);
        Arrays.sort(ints);
        for (int i = 0; i < ints.length; i++)
            assertSame(ints[i], q.poll());
    }

    /**
     * iterator iterates through all elements
     */
    public void testIterator() {
        PriorityQueue q = populatedQueue(SIZE);
        Iterator it = q.iterator();
        int i;
        for (i = 0; it.hasNext(); i++)
            assertTrue(q.contains(it.next()));
        assertEquals(i, SIZE);
        assertIteratorExhausted(it);
    }

    /**
     * iterator of empty collection has no elements
     */
    public void testEmptyIterator() {
        assertIteratorExhausted(new PriorityQueue().iterator());
    }

    /**
     * iterator.remove removes current element
     */
    public void testIteratorRemove() {
        final PriorityQueue q = new PriorityQueue(3);
        q.add(Integer.valueOf(2));
        q.add(Integer.valueOf(1));
        q.add(Integer.valueOf(3));

        Iterator it = q.iterator();
        it.next();
        it.remove();

        it = q.iterator();
        assertEquals(it.next(), Integer.valueOf(2));
        assertEquals(it.next(), Integer.valueOf(3));
        assertFalse(it.hasNext());
    }

    /**
     * toString contains toStrings of elements
     */
    public void testToString() {
        PriorityQueue q = populatedQueue(SIZE);
        String s = q.toString();
        for (int i = 0; i < SIZE; ++i) {
            assertTrue(s.contains(String.valueOf(i)));
        }
    }

    /**
     * A deserialized serialized queue has same elements
     */
    public void testSerialization() throws Exception {
        Queue x = populatedQueue(SIZE);
        Queue y = serialClone(x);

        assertNotSame(x, y);
        assertEquals(x.size(), y.size());
        while (!x.isEmpty()) {
            assertFalse(y.isEmpty());
            assertEquals(x.remove(), y.remove());
        }
        assertTrue(y.isEmpty());
    }
}
