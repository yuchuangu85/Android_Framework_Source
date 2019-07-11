/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 * Other contributors include Andrew Wright, Jeffrey Hayes,
 * Pat Fisher, Mike Judd.
 */

package jsr166;

import junit.framework.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArraySet;

public class CopyOnWriteArraySetTest extends JSR166TestCase {

    static CopyOnWriteArraySet<Integer> populatedSet(int n) {
        CopyOnWriteArraySet<Integer> a = new CopyOnWriteArraySet<Integer>();
        assertTrue(a.isEmpty());
        for (int i = 0; i < n; i++)
            a.add(i);
        assertFalse(a.isEmpty());
        assertEquals(n, a.size());
        return a;
    }

    static CopyOnWriteArraySet populatedSet(Integer[] elements) {
        CopyOnWriteArraySet<Integer> a = new CopyOnWriteArraySet<Integer>();
        assertTrue(a.isEmpty());
        for (int i = 0; i < elements.length; i++)
            a.add(elements[i]);
        assertFalse(a.isEmpty());
        assertEquals(elements.length, a.size());
        return a;
    }

    /**
     * Default-constructed set is empty
     */
    public void testConstructor() {
        CopyOnWriteArraySet a = new CopyOnWriteArraySet();
        assertTrue(a.isEmpty());
    }

    /**
     * Collection-constructed set holds all of its elements
     */
    public void testConstructor3() {
        Integer[] ints = new Integer[SIZE];
        for (int i = 0; i < SIZE-1; ++i)
            ints[i] = new Integer(i);
        CopyOnWriteArraySet a = new CopyOnWriteArraySet(Arrays.asList(ints));
        for (int i = 0; i < SIZE; ++i)
            assertTrue(a.contains(ints[i]));
    }

    /**
     * addAll adds each element from the given collection
     */
    public void testAddAll() {
        CopyOnWriteArraySet full = populatedSet(3);
        Vector v = new Vector();
        v.add(three);
        v.add(four);
        v.add(five);
        full.addAll(v);
        assertEquals(6, full.size());
    }

    /**
     * addAll adds each element from the given collection that did not
     * already exist in the set
     */
    public void testAddAll2() {
        CopyOnWriteArraySet full = populatedSet(3);
        Vector v = new Vector();
        v.add(three);
        v.add(four);
        v.add(one); // will not add this element
        full.addAll(v);
        assertEquals(5, full.size());
    }

    /**
     * add will not add the element if it already exists in the set
     */
    public void testAdd2() {
        CopyOnWriteArraySet full = populatedSet(3);
        full.add(one);
        assertEquals(3, full.size());
    }

    /**
     * add adds the element when it does not exist in the set
     */
    public void testAdd3() {
        CopyOnWriteArraySet full = populatedSet(3);
        full.add(three);
        assertTrue(full.contains(three));
    }

    /**
     * clear removes all elements from the set
     */
    public void testClear() {
        CopyOnWriteArraySet full = populatedSet(3);
        full.clear();
        assertEquals(0, full.size());
    }

    /**
     * contains returns true for added elements
     */
    public void testContains() {
        CopyOnWriteArraySet full = populatedSet(3);
        assertTrue(full.contains(one));
        assertFalse(full.contains(five));
    }

    /**
     * Sets with equal elements are equal
     */
    public void testEquals() {
        CopyOnWriteArraySet a = populatedSet(3);
        CopyOnWriteArraySet b = populatedSet(3);
        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
        assertEquals(a.hashCode(), b.hashCode());
        a.add(m1);
        assertFalse(a.equals(b));
        assertFalse(b.equals(a));
        b.add(m1);
        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
        assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * containsAll returns true for collections with subset of elements
     */
    public void testContainsAll() {
        CopyOnWriteArraySet full = populatedSet(3);
        Vector v = new Vector();
        v.add(one);
        v.add(two);
        assertTrue(full.containsAll(v));
        v.add(six);
        assertFalse(full.containsAll(v));
    }

    /**
     * isEmpty is true when empty, else false
     */
    public void testIsEmpty() {
        CopyOnWriteArraySet empty = new CopyOnWriteArraySet();
        CopyOnWriteArraySet full = populatedSet(3);
        assertTrue(empty.isEmpty());
        assertFalse(full.isEmpty());
    }

    /**
     * iterator() returns an iterator containing the elements of the
     * set in insertion order
     */
    public void testIterator() {
        Collection empty = new CopyOnWriteArraySet();
        assertFalse(empty.iterator().hasNext());
        try {
            empty.iterator().next();
            shouldThrow();
        } catch (NoSuchElementException success) {}

        Integer[] elements = new Integer[SIZE];
        for (int i = 0; i < SIZE; i++)
            elements[i] = i;
        Collections.shuffle(Arrays.asList(elements));
        Collection<Integer> full = populatedSet(elements);

        Iterator it = full.iterator();
        for (int j = 0; j < SIZE; j++) {
            assertTrue(it.hasNext());
            assertEquals(elements[j], it.next());
        }
        assertFalse(it.hasNext());
        try {
            it.next();
            shouldThrow();
        } catch (NoSuchElementException success) {}
    }

    /**
     * iterator remove is unsupported
     */
    public void testIteratorRemove() {
        CopyOnWriteArraySet full = populatedSet(3);
        Iterator it = full.iterator();
        it.next();
        try {
            it.remove();
            shouldThrow();
        } catch (UnsupportedOperationException success) {}
    }

    /**
     * toString holds toString of elements
     */
    public void testToString() {
        assertEquals("[]", new CopyOnWriteArraySet().toString());
        CopyOnWriteArraySet full = populatedSet(3);
        String s = full.toString();
        for (int i = 0; i < 3; ++i)
            assertTrue(s.contains(String.valueOf(i)));
        assertEquals(new ArrayList(full).toString(),
                     full.toString());
    }

    /**
     * removeAll removes all elements from the given collection
     */
    public void testRemoveAll() {
        CopyOnWriteArraySet full = populatedSet(3);
        Vector v = new Vector();
        v.add(one);
        v.add(two);
        full.removeAll(v);
        assertEquals(1, full.size());
    }

    /**
     * remove removes an element
     */
    public void testRemove() {
        CopyOnWriteArraySet full = populatedSet(3);
        full.remove(one);
        assertFalse(full.contains(one));
        assertEquals(2, full.size());
    }

    /**
     * size returns the number of elements
     */
    public void testSize() {
        CopyOnWriteArraySet empty = new CopyOnWriteArraySet();
        CopyOnWriteArraySet full = populatedSet(3);
        assertEquals(3, full.size());
        assertEquals(0, empty.size());
    }

    /**
     * toArray() returns an Object array containing all elements from
     * the set in insertion order
     */
    public void testToArray() {
        Object[] a = new CopyOnWriteArraySet().toArray();
        assertTrue(Arrays.equals(new Object[0], a));
        assertSame(Object[].class, a.getClass());

        Integer[] elements = new Integer[SIZE];
        for (int i = 0; i < SIZE; i++)
            elements[i] = i;
        Collections.shuffle(Arrays.asList(elements));
        Collection<Integer> full = populatedSet(elements);

        assertTrue(Arrays.equals(elements, full.toArray()));
        assertSame(Object[].class, full.toArray().getClass());
    }

    /**
     * toArray(Integer array) returns an Integer array containing all
     * elements from the set in insertion order
     */
    public void testToArray2() {
        Collection empty = new CopyOnWriteArraySet();
        Integer[] a;

        a = new Integer[0];
        assertSame(a, empty.toArray(a));

        a = new Integer[SIZE/2];
        Arrays.fill(a, 42);
        assertSame(a, empty.toArray(a));
        assertNull(a[0]);
        for (int i = 1; i < a.length; i++)
            assertEquals(42, (int) a[i]);

        Integer[] elements = new Integer[SIZE];
        for (int i = 0; i < SIZE; i++)
            elements[i] = i;
        Collections.shuffle(Arrays.asList(elements));
        Collection<Integer> full = populatedSet(elements);

        Arrays.fill(a, 42);
        assertTrue(Arrays.equals(elements, full.toArray(a)));
        for (int i = 0; i < a.length; i++)
            assertEquals(42, (int) a[i]);
        assertSame(Integer[].class, full.toArray(a).getClass());

        a = new Integer[SIZE];
        Arrays.fill(a, 42);
        assertSame(a, full.toArray(a));
        assertTrue(Arrays.equals(elements, a));

        a = new Integer[2*SIZE];
        Arrays.fill(a, 42);
        assertSame(a, full.toArray(a));
        assertTrue(Arrays.equals(elements, Arrays.copyOf(a, SIZE)));
        assertNull(a[SIZE]);
        for (int i = SIZE + 1; i < a.length; i++)
            assertEquals(42, (int) a[i]);
    }

    /**
     * toArray throws an ArrayStoreException when the given array can
     * not store the objects inside the set
     */
    public void testToArray_ArrayStoreException() {
        try {
            CopyOnWriteArraySet c = new CopyOnWriteArraySet();
            c.add("zfasdfsdf");
            c.add("asdadasd");
            c.toArray(new Long[5]);
            shouldThrow();
        } catch (ArrayStoreException success) {}
    }

    /**
     * A deserialized serialized set is equal
     */
    public void testSerialization() throws Exception {
        Set x = populatedSet(SIZE);
        Set y = serialClone(x);

        assertNotSame(y, x);
        assertEquals(x.size(), y.size());
        assertEquals(x.toString(), y.toString());
        assertTrue(Arrays.equals(x.toArray(), y.toArray()));
        assertEquals(x, y);
        assertEquals(y, x);
    }

    /**
     * addAll is idempotent
     */
    public void testAddAll_idempotent() throws Exception {
        Set x = populatedSet(SIZE);
        Set y = new CopyOnWriteArraySet(x);
        y.addAll(x);
        assertEquals(x, y);
        assertEquals(y, x);
    }

}
