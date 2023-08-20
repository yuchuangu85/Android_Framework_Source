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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import junit.framework.Test;
import junit.framework.TestSuite;

public class LinkedListTest extends JSR166TestCase {
    // android-note: Removed because the CTS runner does a bad job of
    // retrying tests that have suite() declarations.
    //
    // public static void main(String[] args) {
    //     main(suite(), args);
    // }
    // public static Test suite() {
    //     return new TestSuite(LinkedListTest.class);
    // }

    /**
     * Returns a new queue of given size containing consecutive
     * Integers 0 ... n.
     */
    private LinkedList<Integer> populatedQueue(int n) {
        LinkedList<Integer> q = new LinkedList<Integer>();
        assertTrue(q.isEmpty());
        for (int i = 0; i < n; ++i)
            assertTrue(q.offer(Integer.valueOf(i)));
        assertFalse(q.isEmpty());
        assertEquals(n, q.size());
        return q;
    }

    /**
     * new queue is empty
     */
    public void testConstructor1() {
        assertEquals(0, new LinkedList().size());
    }

    /**
     * Initializing from null Collection throws NPE
     */
    public void testConstructor3() {
        try {
            new LinkedList((Collection)null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Queue contains all elements of collection used to initialize
     */
    public void testConstructor6() {
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE; ++i)
            ints[i] = i;
        LinkedList q = new LinkedList(Arrays.asList(ints));
        for (int i = 0; i < SIZE; ++i)
            assertEquals(ints[i], q.poll());
    }

    /**
     * isEmpty is true before add, false after
     */
    public void testEmpty() {
        LinkedList q = new LinkedList();
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
        LinkedList q = populatedQueue(SIZE);
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
     * offer(null) succeeds
     */
    public void testOfferNull() {
        LinkedList q = new LinkedList();
        q.offer(null);
        assertNull(q.get(0));
        assertTrue(q.contains(null));
    }

    /**
     * Offer succeeds
     */
    public void testOffer() {
        LinkedList q = new LinkedList();
        assertTrue(q.offer(Integer.valueOf(0)));
        assertTrue(q.offer(Integer.valueOf(1)));
    }

    /**
     * add succeeds
     */
    public void testAdd() {
        LinkedList q = new LinkedList();
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.size());
            assertTrue(q.add(Integer.valueOf(i)));
        }
    }

    /**
     * addAll(null) throws NPE
     */
    public void testAddAll1() {
        LinkedList q = new LinkedList();
        try {
            q.addAll(null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Queue contains all elements, in traversal order, of successful addAll
     */
    public void testAddAll5() {
        Integer[] empty = new Integer[0];
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE; ++i)
            ints[i] = i;
        LinkedList q = new LinkedList();
        assertFalse(q.addAll(Arrays.asList(empty)));
        assertTrue(q.addAll(Arrays.asList(ints)));
        for (int i = 0; i < SIZE; ++i)
            assertEquals(ints[i], q.poll());
    }

    /**
     * addAll with too large an index throws IOOBE
     */
    public void testAddAll2_IndexOutOfBoundsException() {
        LinkedList l = new LinkedList();
        l.add(new Object());
        LinkedList m = new LinkedList();
        m.add(new Object());
        try {
            l.addAll(4,m);
            shouldThrow();
        } catch (IndexOutOfBoundsException success) {}
    }

    /**
     * addAll with negative index throws IOOBE
     */
    public void testAddAll4_BadIndex() {
        LinkedList l = new LinkedList();
        l.add(new Object());
        LinkedList m = new LinkedList();
        m.add(new Object());
        try {
            l.addAll(-1,m);
            shouldThrow();
        } catch (IndexOutOfBoundsException success) {}
    }

    /**
     * poll succeeds unless empty
     */
    public void testPoll() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.poll());
        }
        assertNull(q.poll());
    }

    /**
     * peek returns next element, or null if empty
     */
    public void testPeek() {
        LinkedList q = populatedQueue(SIZE);
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
        LinkedList q = populatedQueue(SIZE);
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
        LinkedList q = populatedQueue(SIZE);
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
        LinkedList q = populatedQueue(SIZE);
        for (int i = 1; i < SIZE; i += 2) {
            assertTrue(q.contains(i));
            assertTrue(q.remove((Integer)i));
            assertFalse(q.contains(i));
            assertTrue(q.contains(i - 1));
        }
        for (int i = 0; i < SIZE; i += 2) {
            assertTrue(q.contains(i));
            assertTrue(q.remove((Integer)i));
            assertFalse(q.contains(i));
            assertFalse(q.remove((Integer)(i + 1)));
            assertFalse(q.contains(i + 1));
        }
        assertTrue(q.isEmpty());
    }

    /**
     * contains(x) reports true when elements added but not yet removed
     */
    public void testContains() {
        LinkedList q = populatedQueue(SIZE);
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
        LinkedList q = populatedQueue(SIZE);
        q.clear();
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
        assertTrue(q.add(Integer.valueOf(1)));
        assertFalse(q.isEmpty());
        q.clear();
        assertTrue(q.isEmpty());
    }

    /**
     * containsAll(c) is true when c contains a subset of elements
     */
    public void testContainsAll() {
        LinkedList q = populatedQueue(SIZE);
        LinkedList p = new LinkedList();
        for (int i = 0; i < SIZE; ++i) {
            assertTrue(q.containsAll(p));
            assertFalse(p.containsAll(q));
            assertTrue(p.add(Integer.valueOf(i)));
        }
        assertTrue(p.containsAll(q));
    }

    /**
     * retainAll(c) retains only those elements of c and reports true if changed
     */
    public void testRetainAll() {
        LinkedList q = populatedQueue(SIZE);
        LinkedList p = populatedQueue(SIZE);
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
            LinkedList q = populatedQueue(SIZE);
            LinkedList p = populatedQueue(i);
            assertTrue(q.removeAll(p));
            assertEquals(SIZE - i, q.size());
            for (int j = 0; j < i; ++j) {
                Integer x = (Integer)(p.remove());
                assertFalse(q.contains(x));
            }
        }
    }

    /**
     * toArray contains all elements in FIFO order
     */
    public void testToArray() {
        LinkedList q = populatedQueue(SIZE);
        Object[] o = q.toArray();
        for (int i = 0; i < o.length; i++)
            assertSame(o[i], q.poll());
    }

    /**
     * toArray(a) contains all elements in FIFO order
     */
    public void testToArray2() {
        LinkedList<Integer> q = populatedQueue(SIZE);
        Integer[] ints = new Integer[SIZE];
        Integer[] array = q.toArray(ints);
        assertSame(ints, array);
        for (int i = 0; i < ints.length; i++)
            assertSame(ints[i], q.poll());
    }

    /**
     * toArray(null) throws NullPointerException
     */
    public void testToArray_NullArg() {
        LinkedList l = new LinkedList();
        l.add(new Object());
        try {
            l.toArray((Object[]) null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * toArray(incompatible array type) throws ArrayStoreException
     */
    public void testToArray1_BadArg() {
        LinkedList l = new LinkedList();
        l.add(Integer.valueOf(5));
        try {
            l.toArray(new String[10]);
            shouldThrow();
        } catch (ArrayStoreException success) {}
    }

    /**
     * iterator iterates through all elements
     */
    public void testIterator() {
        LinkedList q = populatedQueue(SIZE);
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
        assertIteratorExhausted(new LinkedList().iterator());
    }

    /**
     * iterator ordering is FIFO
     */
    public void testIteratorOrdering() {
        final LinkedList q = new LinkedList();
        q.add(Integer.valueOf(1));
        q.add(Integer.valueOf(2));
        q.add(Integer.valueOf(3));
        int k = 0;
        for (Iterator it = q.iterator(); it.hasNext();) {
            assertEquals(++k, it.next());
        }

        assertEquals(3, k);
    }

    /**
     * iterator.remove removes current element
     */
    public void testIteratorRemove() {
        final LinkedList q = new LinkedList();
        q.add(Integer.valueOf(1));
        q.add(Integer.valueOf(2));
        q.add(Integer.valueOf(3));
        Iterator it = q.iterator();
        assertEquals(1, it.next());
        it.remove();
        it = q.iterator();
        assertEquals(2, it.next());
        assertEquals(3, it.next());
        assertFalse(it.hasNext());
    }

    /**
     * Descending iterator iterates through all elements
     */
    public void testDescendingIterator() {
        LinkedList q = populatedQueue(SIZE);
        int i = 0;
        Iterator it = q.descendingIterator();
        while (it.hasNext()) {
            assertTrue(q.contains(it.next()));
            ++i;
        }
        assertEquals(i, SIZE);
        assertFalse(it.hasNext());
        try {
            it.next();
            shouldThrow();
        } catch (NoSuchElementException success) {}
    }

    /**
     * Descending iterator ordering is reverse FIFO
     */
    public void testDescendingIteratorOrdering() {
        final LinkedList q = new LinkedList();
        q.add(Integer.valueOf(3));
        q.add(Integer.valueOf(2));
        q.add(Integer.valueOf(1));
        int k = 0;
        for (Iterator it = q.descendingIterator(); it.hasNext();) {
            assertEquals(++k, it.next());
        }

        assertEquals(3, k);
    }

    /**
     * descendingIterator.remove removes current element
     */
    public void testDescendingIteratorRemove() {
        final LinkedList q = new LinkedList();
        q.add(three);
        q.add(two);
        q.add(one);
        Iterator it = q.descendingIterator();
        it.next();
        it.remove();
        it = q.descendingIterator();
        assertSame(it.next(), two);
        assertSame(it.next(), three);
        assertFalse(it.hasNext());
    }

    /**
     * toString contains toStrings of elements
     */
    public void testToString() {
        LinkedList q = populatedQueue(SIZE);
        String s = q.toString();
        for (int i = 0; i < SIZE; ++i) {
            assertTrue(s.contains(String.valueOf(i)));
        }
    }

    /**
     * peek returns element inserted with addFirst
     */
    public void testAddFirst() {
        LinkedList q = populatedQueue(3);
        q.addFirst(four);
        assertSame(four, q.peek());
    }

    /**
     * peekFirst returns element inserted with push
     */
    public void testPush() {
        LinkedList q = populatedQueue(3);
        q.push(four);
        assertSame(four, q.peekFirst());
    }

    /**
     * pop removes next element, or throws NSEE if empty
     */
    public void testPop() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.pop());
        }
        try {
            q.pop();
            shouldThrow();
        } catch (NoSuchElementException success) {}
    }

    /**
     * OfferFirst succeeds
     */
    public void testOfferFirst() {
        LinkedList q = new LinkedList();
        assertTrue(q.offerFirst(Integer.valueOf(0)));
        assertTrue(q.offerFirst(Integer.valueOf(1)));
    }

    /**
     * OfferLast succeeds
     */
    public void testOfferLast() {
        LinkedList q = new LinkedList();
        assertTrue(q.offerLast(Integer.valueOf(0)));
        assertTrue(q.offerLast(Integer.valueOf(1)));
    }

    /**
     * pollLast succeeds unless empty
     */
    public void testPollLast() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = SIZE - 1; i >= 0; --i) {
            assertEquals(i, q.pollLast());
        }
        assertNull(q.pollLast());
    }

    /**
     * peekFirst returns next element, or null if empty
     */
    public void testPeekFirst() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.peekFirst());
            assertEquals(i, q.pollFirst());
            assertTrue(q.peekFirst() == null ||
                       !q.peekFirst().equals(i));
        }
        assertNull(q.peekFirst());
    }

    /**
     * peekLast returns next element, or null if empty
     */
    public void testPeekLast() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = SIZE - 1; i >= 0; --i) {
            assertEquals(i, q.peekLast());
            assertEquals(i, q.pollLast());
            assertTrue(q.peekLast() == null ||
                       !q.peekLast().equals(i));
        }
        assertNull(q.peekLast());
    }

    public void testFirstElement() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = 0; i < SIZE; ++i) {
            assertEquals(i, q.getFirst());
            assertEquals(i, q.pollFirst());
        }
        try {
            q.getFirst();
            shouldThrow();
        } catch (NoSuchElementException success) {}
    }

    /**
     * getLast returns next element, or throws NSEE if empty
     */
    public void testLastElement() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = SIZE - 1; i >= 0; --i) {
            assertEquals(i, q.getLast());
            assertEquals(i, q.pollLast());
        }
        try {
            q.getLast();
            shouldThrow();
        } catch (NoSuchElementException success) {}
        assertNull(q.peekLast());
    }

    /**
     * removeFirstOccurrence(x) removes x and returns true if present
     */
    public void testRemoveFirstOccurrence() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = 1; i < SIZE; i += 2) {
            assertTrue(q.removeFirstOccurrence(Integer.valueOf(i)));
        }
        for (int i = 0; i < SIZE; i += 2) {
            assertTrue(q.removeFirstOccurrence(Integer.valueOf(i)));
            assertFalse(q.removeFirstOccurrence(Integer.valueOf(i + 1)));
        }
        assertTrue(q.isEmpty());
    }

    /**
     * removeLastOccurrence(x) removes x and returns true if present
     */
    public void testRemoveLastOccurrence() {
        LinkedList q = populatedQueue(SIZE);
        for (int i = 1; i < SIZE; i += 2) {
            assertTrue(q.removeLastOccurrence(Integer.valueOf(i)));
        }
        for (int i = 0; i < SIZE; i += 2) {
            assertTrue(q.removeLastOccurrence(Integer.valueOf(i)));
            assertFalse(q.removeLastOccurrence(Integer.valueOf(i + 1)));
        }
        assertTrue(q.isEmpty());
    }

}
