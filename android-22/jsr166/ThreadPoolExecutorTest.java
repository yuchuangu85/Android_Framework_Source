/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 * Other contributors include Andrew Wright, Jeffrey Hayes,
 * Pat Fisher, Mike Judd.
 */

package jsr166;

import junit.framework.*;
import java.util.concurrent.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import java.util.*;

public class ThreadPoolExecutorTest extends JSR166TestCase {

    static class ExtendedTPE extends ThreadPoolExecutor {
        final CountDownLatch beforeCalled = new CountDownLatch(1);
        final CountDownLatch afterCalled = new CountDownLatch(1);
        final CountDownLatch terminatedCalled = new CountDownLatch(1);

        public ExtendedTPE() {
            super(1, 1, LONG_DELAY_MS, MILLISECONDS, new SynchronousQueue<Runnable>());
        }
        protected void beforeExecute(Thread t, Runnable r) {
            beforeCalled.countDown();
        }
        protected void afterExecute(Runnable r, Throwable t) {
            afterCalled.countDown();
        }
        protected void terminated() {
            terminatedCalled.countDown();
        }

        public boolean beforeCalled() {
            return beforeCalled.getCount() == 0;
        }
        public boolean afterCalled() {
            return afterCalled.getCount() == 0;
        }
        public boolean terminatedCalled() {
            return terminatedCalled.getCount() == 0;
        }
    }

    static class FailingThreadFactory implements ThreadFactory {
        int calls = 0;
        public Thread newThread(Runnable r) {
            if (++calls > 1) return null;
            return new Thread(r);
        }
    }

    /**
     * execute successfully executes a runnable
     */
    public void testExecute() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch done = new CountDownLatch(1);
        final Runnable task = new CheckedRunnable() {
            public void realRun() {
                done.countDown();
            }};
        try {
            p.execute(task);
            assertTrue(done.await(SMALL_DELAY_MS, MILLISECONDS));
        } finally {
            joinPool(p);
        }
    }

    /**
     * getActiveCount increases but doesn't overestimate, when a
     * thread becomes active
     */
    public void testGetActiveCount() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            assertEquals(0, p.getActiveCount());
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    threadStarted.countDown();
                    assertEquals(1, p.getActiveCount());
                    done.await();
                }});
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertEquals(1, p.getActiveCount());
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * prestartCoreThread starts a thread if under corePoolSize, else doesn't
     */
    public void testPrestartCoreThread() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertEquals(0, p.getPoolSize());
        assertTrue(p.prestartCoreThread());
        assertEquals(1, p.getPoolSize());
        assertTrue(p.prestartCoreThread());
        assertEquals(2, p.getPoolSize());
        assertFalse(p.prestartCoreThread());
        assertEquals(2, p.getPoolSize());
        joinPool(p);
    }

    /**
     * prestartAllCoreThreads starts all corePoolSize threads
     */
    public void testPrestartAllCoreThreads() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertEquals(0, p.getPoolSize());
        p.prestartAllCoreThreads();
        assertEquals(2, p.getPoolSize());
        p.prestartAllCoreThreads();
        assertEquals(2, p.getPoolSize());
        joinPool(p);
    }

    /**
     * getCompletedTaskCount increases, but doesn't overestimate,
     * when tasks complete
     */
    public void testGetCompletedTaskCount() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch threadProceed = new CountDownLatch(1);
        final CountDownLatch threadDone = new CountDownLatch(1);
        try {
            assertEquals(0, p.getCompletedTaskCount());
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    threadStarted.countDown();
                    assertEquals(0, p.getCompletedTaskCount());
                    threadProceed.await();
                    threadDone.countDown();
                }});
            await(threadStarted);
            assertEquals(0, p.getCompletedTaskCount());
            threadProceed.countDown();
            threadDone.await();
            long startTime = System.nanoTime();
            while (p.getCompletedTaskCount() != 1) {
                if (millisElapsedSince(startTime) > LONG_DELAY_MS)
                    fail("timed out");
                Thread.yield();
            }
        } finally {
            joinPool(p);
        }
    }

    /**
     * getCorePoolSize returns size given in constructor if not otherwise set
     */
    public void testGetCorePoolSize() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertEquals(1, p.getCorePoolSize());
        joinPool(p);
    }

    /**
     * getKeepAliveTime returns value given in constructor if not otherwise set
     */
    public void testGetKeepAliveTime() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 2,
                                   1000, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertEquals(1, p.getKeepAliveTime(TimeUnit.SECONDS));
        joinPool(p);
    }

    /**
     * getThreadFactory returns factory in constructor if not set
     */
    public void testGetThreadFactory() {
        ThreadFactory tf = new SimpleThreadFactory();
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   tf,
                                   new NoOpREHandler());
        assertSame(tf, p.getThreadFactory());
        joinPool(p);
    }

    /**
     * setThreadFactory sets the thread factory returned by getThreadFactory
     */
    public void testSetThreadFactory() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        ThreadFactory tf = new SimpleThreadFactory();
        p.setThreadFactory(tf);
        assertSame(tf, p.getThreadFactory());
        joinPool(p);
    }

    /**
     * setThreadFactory(null) throws NPE
     */
    public void testSetThreadFactoryNull() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.setThreadFactory(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(p);
        }
    }

    /**
     * getRejectedExecutionHandler returns handler in constructor if not set
     */
    public void testGetRejectedExecutionHandler() {
        final RejectedExecutionHandler h = new NoOpREHandler();
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   h);
        assertSame(h, p.getRejectedExecutionHandler());
        joinPool(p);
    }

    /**
     * setRejectedExecutionHandler sets the handler returned by
     * getRejectedExecutionHandler
     */
    public void testSetRejectedExecutionHandler() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        RejectedExecutionHandler h = new NoOpREHandler();
        p.setRejectedExecutionHandler(h);
        assertSame(h, p.getRejectedExecutionHandler());
        joinPool(p);
    }

    /**
     * setRejectedExecutionHandler(null) throws NPE
     */
    public void testSetRejectedExecutionHandlerNull() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.setRejectedExecutionHandler(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(p);
        }
    }

    /**
     * getLargestPoolSize increases, but doesn't overestimate, when
     * multiple threads active
     */
    public void testGetLargestPoolSize() throws InterruptedException {
        final int THREADS = 3;
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(THREADS, THREADS,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadsStarted = new CountDownLatch(THREADS);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            assertEquals(0, p.getLargestPoolSize());
            for (int i = 0; i < THREADS; i++)
                p.execute(new CheckedRunnable() {
                    public void realRun() throws InterruptedException {
                        threadsStarted.countDown();
                        done.await();
                        assertEquals(THREADS, p.getLargestPoolSize());
                    }});
            assertTrue(threadsStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertEquals(THREADS, p.getLargestPoolSize());
        } finally {
            done.countDown();
            joinPool(p);
            assertEquals(THREADS, p.getLargestPoolSize());
        }
    }

    /**
     * getMaximumPoolSize returns value given in constructor if not
     * otherwise set
     */
    public void testGetMaximumPoolSize() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 3,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertEquals(3, p.getMaximumPoolSize());
        joinPool(p);
    }

    /**
     * getPoolSize increases, but doesn't overestimate, when threads
     * become active
     */
    public void testGetPoolSize() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            assertEquals(0, p.getPoolSize());
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    threadStarted.countDown();
                    assertEquals(1, p.getPoolSize());
                    done.await();
                }});
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertEquals(1, p.getPoolSize());
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * getTaskCount increases, but doesn't overestimate, when tasks submitted
     */
    public void testGetTaskCount() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            assertEquals(0, p.getTaskCount());
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    threadStarted.countDown();
                    assertEquals(1, p.getTaskCount());
                    done.await();
                }});
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertEquals(1, p.getTaskCount());
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * isShutdown is false before shutdown, true after
     */
    public void testIsShutdown() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertFalse(p.isShutdown());
        try { p.shutdown(); } catch (SecurityException ok) { return; }
        assertTrue(p.isShutdown());
        joinPool(p);
    }

    /**
     * awaitTermination on a non-shutdown pool times out
     */
    public void testAwaitTermination_timesOut() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertFalse(p.isTerminated());
        assertFalse(p.awaitTermination(Long.MIN_VALUE, NANOSECONDS));
        assertFalse(p.awaitTermination(Long.MIN_VALUE, MILLISECONDS));
        assertFalse(p.awaitTermination(-1L, NANOSECONDS));
        assertFalse(p.awaitTermination(-1L, MILLISECONDS));
        assertFalse(p.awaitTermination(0L, NANOSECONDS));
        assertFalse(p.awaitTermination(0L, MILLISECONDS));
        long timeoutNanos = 999999L;
        long startTime = System.nanoTime();
        assertFalse(p.awaitTermination(timeoutNanos, NANOSECONDS));
        assertTrue(System.nanoTime() - startTime >= timeoutNanos);
        assertFalse(p.isTerminated());
        startTime = System.nanoTime();
        long timeoutMillis = timeoutMillis();
        assertFalse(p.awaitTermination(timeoutMillis, MILLISECONDS));
        assertTrue(millisElapsedSince(startTime) >= timeoutMillis);
        assertFalse(p.isTerminated());
        p.shutdown();
        assertTrue(p.awaitTermination(LONG_DELAY_MS, MILLISECONDS));
        assertTrue(p.isTerminated());
    }

    /**
     * isTerminated is false before termination, true after
     */
    public void testIsTerminated() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        assertFalse(p.isTerminated());
        try {
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    assertFalse(p.isTerminated());
                    threadStarted.countDown();
                    done.await();
                }});
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertFalse(p.isTerminating());
            done.countDown();
        } finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        assertTrue(p.awaitTermination(LONG_DELAY_MS, MILLISECONDS));
        assertTrue(p.isTerminated());
    }

    /**
     * isTerminating is not true when running or when terminated
     */
    public void testIsTerminating() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            assertFalse(p.isTerminating());
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    assertFalse(p.isTerminating());
                    threadStarted.countDown();
                    done.await();
                }});
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertFalse(p.isTerminating());
            done.countDown();
        } finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        assertTrue(p.awaitTermination(LONG_DELAY_MS, MILLISECONDS));
        assertTrue(p.isTerminated());
        assertFalse(p.isTerminating());
    }

    /**
     * getQueue returns the work queue, which contains queued tasks
     */
    public void testGetQueue() throws InterruptedException {
        final BlockingQueue<Runnable> q = new ArrayBlockingQueue<Runnable>(10);
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   q);
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            FutureTask[] tasks = new FutureTask[5];
            for (int i = 0; i < tasks.length; i++) {
                Callable task = new CheckedCallable<Boolean>() {
                    public Boolean realCall() throws InterruptedException {
                        threadStarted.countDown();
                        assertSame(q, p.getQueue());
                        done.await();
                        return Boolean.TRUE;
                    }};
                tasks[i] = new FutureTask(task);
                p.execute(tasks[i]);
            }
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertSame(q, p.getQueue());
            assertFalse(q.contains(tasks[0]));
            assertTrue(q.contains(tasks[tasks.length - 1]));
            assertEquals(tasks.length - 1, q.size());
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * remove(task) removes queued task, and fails to remove active task
     */
    public void testRemove() throws InterruptedException {
        BlockingQueue<Runnable> q = new ArrayBlockingQueue<Runnable>(10);
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   q);
        Runnable[] tasks = new Runnable[5];
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = new CheckedRunnable() {
                    public void realRun() throws InterruptedException {
                        threadStarted.countDown();
                        done.await();
                    }};
                p.execute(tasks[i]);
            }
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertFalse(p.remove(tasks[0]));
            assertTrue(q.contains(tasks[4]));
            assertTrue(q.contains(tasks[3]));
            assertTrue(p.remove(tasks[4]));
            assertFalse(p.remove(tasks[4]));
            assertFalse(q.contains(tasks[4]));
            assertTrue(q.contains(tasks[3]));
            assertTrue(p.remove(tasks[3]));
            assertFalse(q.contains(tasks[3]));
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * purge removes cancelled tasks from the queue
     */
    public void testPurge() throws InterruptedException {
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final BlockingQueue<Runnable> q = new ArrayBlockingQueue<Runnable>(10);
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   q);
        FutureTask[] tasks = new FutureTask[5];
        try {
            for (int i = 0; i < tasks.length; i++) {
                Callable task = new CheckedCallable<Boolean>() {
                    public Boolean realCall() throws InterruptedException {
                        threadStarted.countDown();
                        done.await();
                        return Boolean.TRUE;
                    }};
                tasks[i] = new FutureTask(task);
                p.execute(tasks[i]);
            }
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertEquals(tasks.length, p.getTaskCount());
            assertEquals(tasks.length - 1, q.size());
            assertEquals(1L, p.getActiveCount());
            assertEquals(0L, p.getCompletedTaskCount());
            tasks[4].cancel(true);
            tasks[3].cancel(false);
            p.purge();
            assertEquals(tasks.length - 3, q.size());
            assertEquals(tasks.length - 2, p.getTaskCount());
            p.purge();         // Nothing to do
            assertEquals(tasks.length - 3, q.size());
            assertEquals(tasks.length - 2, p.getTaskCount());
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * shutdownNow returns a list containing tasks that were not run
     */
    public void testShutdownNow() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List l;
        try {
            for (int i = 0; i < 5; i++)
                p.execute(new MediumPossiblyInterruptedRunnable());
        }
        finally {
            try {
                l = p.shutdownNow();
            } catch (SecurityException ok) { return; }
        }
        assertTrue(p.isShutdown());
        assertTrue(l.size() <= 4);
    }

    // Exception Tests

    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor1() {
        try {
            new ThreadPoolExecutor(-1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor2() {
        try {
            new ThreadPoolExecutor(1, -1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor3() {
        try {
            new ThreadPoolExecutor(1, 0,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor4() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   -1L, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor5() {
        try {
            new ThreadPoolExecutor(2, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   (BlockingQueue) null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor6() {
        try {
            new ThreadPoolExecutor(-1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor7() {
        try {
            new ThreadPoolExecutor(1, -1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor8() {
        try {
            new ThreadPoolExecutor(1, 0,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor9() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   -1L, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor10() {
        try {
            new ThreadPoolExecutor(2, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException2() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   (BlockingQueue) null,
                                   new SimpleThreadFactory());
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if threadFactory is set to null
     */
    public void testConstructorNullPointerException3() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   (ThreadFactory) null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor11() {
        try {
            new ThreadPoolExecutor(-1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor12() {
        try {
            new ThreadPoolExecutor(1, -1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor13() {
        try {
            new ThreadPoolExecutor(1, 0,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor14() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   -1L, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor15() {
        try {
            new ThreadPoolExecutor(2, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException4() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   (BlockingQueue) null,
                                   new NoOpREHandler());
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if handler is set to null
     */
    public void testConstructorNullPointerException5() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   (RejectedExecutionHandler) null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor16() {
        try {
            new ThreadPoolExecutor(-1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory(),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor17() {
        try {
            new ThreadPoolExecutor(1, -1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory(),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor18() {
        try {
            new ThreadPoolExecutor(1, 0,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory(),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor19() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   -1L, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory(),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor20() {
        try {
            new ThreadPoolExecutor(2, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory(),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (IllegalArgumentException success) {}
    }

    /**
     * Constructor throws if workQueue is null
     */
    public void testConstructorNullPointerException6() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   (BlockingQueue) null,
                                   new SimpleThreadFactory(),
                                   new NoOpREHandler());
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if handler is null
     */
    public void testConstructorNullPointerException7() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   new SimpleThreadFactory(),
                                   (RejectedExecutionHandler) null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * Constructor throws if ThreadFactory is null
     */
    public void testConstructorNullPointerException8() {
        try {
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10),
                                   (ThreadFactory) null,
                                   new NoOpREHandler());
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    /**
     * get of submitted callable throws InterruptedException if interrupted
     */
    public void testInterruptedSubmit() throws InterruptedException {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   60, TimeUnit.SECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));

        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Thread t = newStartedThread(new CheckedInterruptedRunnable() {
                public void realRun() throws Exception {
                    Callable task = new CheckedCallable<Boolean>() {
                        public Boolean realCall() throws InterruptedException {
                            threadStarted.countDown();
                            done.await();
                            return Boolean.TRUE;
                        }};
                    p.submit(task).get();
                }});

            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            t.interrupt();
            awaitTermination(t, MEDIUM_DELAY_MS);
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * execute throws RejectedExecutionException if saturated.
     */
    public void testSaturatedExecute() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1));
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Runnable task = new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    done.await();
                }};
            for (int i = 0; i < 2; ++i)
                p.execute(task);
            for (int i = 0; i < 2; ++i) {
                try {
                    p.execute(task);
                    shouldThrow();
                } catch (RejectedExecutionException success) {}
                assertTrue(p.getTaskCount() <= 2);
            }
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * submit(runnable) throws RejectedExecutionException if saturated.
     */
    public void testSaturatedSubmitRunnable() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1));
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Runnable task = new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    done.await();
                }};
            for (int i = 0; i < 2; ++i)
                p.submit(task);
            for (int i = 0; i < 2; ++i) {
                try {
                    p.execute(task);
                    shouldThrow();
                } catch (RejectedExecutionException success) {}
                assertTrue(p.getTaskCount() <= 2);
            }
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * submit(callable) throws RejectedExecutionException if saturated.
     */
    public void testSaturatedSubmitCallable() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1));
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Runnable task = new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    done.await();
                }};
            for (int i = 0; i < 2; ++i)
                p.submit(Executors.callable(task));
            for (int i = 0; i < 2; ++i) {
                try {
                    p.execute(task);
                    shouldThrow();
                } catch (RejectedExecutionException success) {}
                assertTrue(p.getTaskCount() <= 2);
            }
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * executor using CallerRunsPolicy runs task if saturated.
     */
    public void testSaturatedExecute2() {
        RejectedExecutionHandler h = new ThreadPoolExecutor.CallerRunsPolicy();
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS,
                                   MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1),
                                   h);
        try {
            TrackedNoOpRunnable[] tasks = new TrackedNoOpRunnable[5];
            for (int i = 0; i < tasks.length; ++i)
                tasks[i] = new TrackedNoOpRunnable();
            TrackedLongRunnable mr = new TrackedLongRunnable();
            p.execute(mr);
            for (int i = 0; i < tasks.length; ++i)
                p.execute(tasks[i]);
            for (int i = 1; i < tasks.length; ++i)
                assertTrue(tasks[i].done);
            try { p.shutdownNow(); } catch (SecurityException ok) { return; }
        } finally {
            joinPool(p);
        }
    }

    /**
     * executor using DiscardPolicy drops task if saturated.
     */
    public void testSaturatedExecute3() {
        RejectedExecutionHandler h = new ThreadPoolExecutor.DiscardPolicy();
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1),
                                   h);
        try {
            TrackedNoOpRunnable[] tasks = new TrackedNoOpRunnable[5];
            for (int i = 0; i < tasks.length; ++i)
                tasks[i] = new TrackedNoOpRunnable();
            p.execute(new TrackedLongRunnable());
            for (TrackedNoOpRunnable task : tasks)
                p.execute(task);
            for (TrackedNoOpRunnable task : tasks)
                assertFalse(task.done);
            try { p.shutdownNow(); } catch (SecurityException ok) { return; }
        } finally {
            joinPool(p);
        }
    }

    /**
     * executor using DiscardOldestPolicy drops oldest task if saturated.
     */
    public void testSaturatedExecute4() {
        RejectedExecutionHandler h = new ThreadPoolExecutor.DiscardOldestPolicy();
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1),
                                   h);
        try {
            p.execute(new TrackedLongRunnable());
            TrackedLongRunnable r2 = new TrackedLongRunnable();
            p.execute(r2);
            assertTrue(p.getQueue().contains(r2));
            TrackedNoOpRunnable r3 = new TrackedNoOpRunnable();
            p.execute(r3);
            assertFalse(p.getQueue().contains(r2));
            assertTrue(p.getQueue().contains(r3));
            try { p.shutdownNow(); } catch (SecurityException ok) { return; }
        } finally {
            joinPool(p);
        }
    }

    /**
     * execute throws RejectedExecutionException if shutdown
     */
    public void testRejectedExecutionExceptionOnShutdown() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1));
        try { p.shutdown(); } catch (SecurityException ok) { return; }
        try {
            p.execute(new NoOpRunnable());
            shouldThrow();
        } catch (RejectedExecutionException success) {}

        joinPool(p);
    }

    /**
     * execute using CallerRunsPolicy drops task on shutdown
     */
    public void testCallerRunsOnShutdown() {
        RejectedExecutionHandler h = new ThreadPoolExecutor.CallerRunsPolicy();
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1), h);

        try { p.shutdown(); } catch (SecurityException ok) { return; }
        try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
            p.execute(r);
            assertFalse(r.done);
        } finally {
            joinPool(p);
        }
    }

    /**
     * execute using DiscardPolicy drops task on shutdown
     */
    public void testDiscardOnShutdown() {
        RejectedExecutionHandler h = new ThreadPoolExecutor.DiscardPolicy();
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1),
                                   h);

        try { p.shutdown(); } catch (SecurityException ok) { return; }
        try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
            p.execute(r);
            assertFalse(r.done);
        } finally {
            joinPool(p);
        }
    }

    /**
     * execute using DiscardOldestPolicy drops task on shutdown
     */
    public void testDiscardOldestOnShutdown() {
        RejectedExecutionHandler h = new ThreadPoolExecutor.DiscardOldestPolicy();
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 1,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(1),
                                   h);

        try { p.shutdown(); } catch (SecurityException ok) { return; }
        try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
            p.execute(r);
            assertFalse(r.done);
        } finally {
            joinPool(p);
        }
    }

    /**
     * execute(null) throws NPE
     */
    public void testExecuteNull() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.execute(null);
            shouldThrow();
        } catch (NullPointerException success) {}

        joinPool(p);
    }

    /**
     * setCorePoolSize of negative value throws IllegalArgumentException
     */
    public void testCorePoolSizeIllegalArgumentException() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.setCorePoolSize(-1);
            shouldThrow();
        } catch (IllegalArgumentException success) {
        } finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        joinPool(p);
    }

    /**
     * setMaximumPoolSize(int) throws IllegalArgumentException if
     * given a value less the core pool size
     */
    public void testMaximumPoolSizeIllegalArgumentException() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 3,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.setMaximumPoolSize(1);
            shouldThrow();
        } catch (IllegalArgumentException success) {
        } finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        joinPool(p);
    }

    /**
     * setMaximumPoolSize throws IllegalArgumentException
     * if given a negative value
     */
    public void testMaximumPoolSizeIllegalArgumentException2() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 3,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.setMaximumPoolSize(-1);
            shouldThrow();
        } catch (IllegalArgumentException success) {
        } finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        joinPool(p);
    }

    /**
     * setKeepAliveTime throws IllegalArgumentException
     * when given a negative value
     */
    public void testKeepAliveTimeIllegalArgumentException() {
        ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 3,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            p.setKeepAliveTime(-1,MILLISECONDS);
            shouldThrow();
        } catch (IllegalArgumentException success) {
        } finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        joinPool(p);
    }

    /**
     * terminated() is called on termination
     */
    public void testTerminated() {
        ExtendedTPE p = new ExtendedTPE();
        try { p.shutdown(); } catch (SecurityException ok) { return; }
        assertTrue(p.terminatedCalled());
        joinPool(p);
    }

    /**
     * beforeExecute and afterExecute are called when executing task
     */
    public void testBeforeAfter() throws InterruptedException {
        ExtendedTPE p = new ExtendedTPE();
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final CheckedRunnable task = new CheckedRunnable() {
                public void realRun() {
                    done.countDown();
                }};
            p.execute(task);
            await(p.afterCalled);
            assertEquals(0, done.getCount());
            assertTrue(p.afterCalled());
            assertTrue(p.beforeCalled());
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        } finally {
            joinPool(p);
        }
    }

    /**
     * completed submit of callable returns result
     */
    public void testSubmitCallable() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            Future<String> future = e.submit(new StringTask());
            String result = future.get();
            assertSame(TEST_STRING, result);
        } finally {
            joinPool(e);
        }
    }

    /**
     * completed submit of runnable returns successfully
     */
    public void testSubmitRunnable() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            Future<?> future = e.submit(new NoOpRunnable());
            future.get();
            assertTrue(future.isDone());
        } finally {
            joinPool(e);
        }
    }

    /**
     * completed submit of (runnable, result) returns result
     */
    public void testSubmitRunnable2() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            Future<String> future = e.submit(new NoOpRunnable(), TEST_STRING);
            String result = future.get();
            assertSame(TEST_STRING, result);
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(null) throws NPE
     */
    public void testInvokeAny1() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            e.invokeAny(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(empty collection) throws IAE
     */
    public void testInvokeAny2() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            e.invokeAny(new ArrayList<Callable<String>>());
            shouldThrow();
        } catch (IllegalArgumentException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(c) throws NPE if c has null elements
     */
    public void testInvokeAny3() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(latchAwaitingStringTask(latch));
        l.add(null);
        try {
            e.invokeAny(l);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            latch.countDown();
            joinPool(e);
        }
    }

    /**
     * invokeAny(c) throws ExecutionException if no task completes
     */
    public void testInvokeAny4() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new NPETask());
        try {
            e.invokeAny(l);
            shouldThrow();
        } catch (ExecutionException success) {
            assertTrue(success.getCause() instanceof NullPointerException);
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(c) returns result of some task
     */
    public void testInvokeAny5() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Callable<String>> l = new ArrayList<Callable<String>>();
            l.add(new StringTask());
            l.add(new StringTask());
            String result = e.invokeAny(l);
            assertSame(TEST_STRING, result);
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(null) throws NPE
     */
    public void testInvokeAll1() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            e.invokeAll(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(empty collection) returns empty collection
     */
    public void testInvokeAll2() throws InterruptedException {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Future<String>> r = e.invokeAll(new ArrayList<Callable<String>>());
            assertTrue(r.isEmpty());
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(c) throws NPE if c has null elements
     */
    public void testInvokeAll3() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new StringTask());
        l.add(null);
        try {
            e.invokeAll(l);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * get of element of invokeAll(c) throws exception on failed task
     */
    public void testInvokeAll4() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Callable<String>> l = new ArrayList<Callable<String>>();
            l.add(new NPETask());
            List<Future<String>> futures = e.invokeAll(l);
            assertEquals(1, futures.size());
            try {
                futures.get(0).get();
                shouldThrow();
            } catch (ExecutionException success) {
                assertTrue(success.getCause() instanceof NullPointerException);
            }
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(c) returns results of all completed tasks
     */
    public void testInvokeAll5() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Callable<String>> l = new ArrayList<Callable<String>>();
            l.add(new StringTask());
            l.add(new StringTask());
            List<Future<String>> futures = e.invokeAll(l);
            assertEquals(2, futures.size());
            for (Future<String> future : futures)
                assertSame(TEST_STRING, future.get());
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(null) throws NPE
     */
    public void testTimedInvokeAny1() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            e.invokeAny(null, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(,,null) throws NPE
     */
    public void testTimedInvokeAnyNullTimeUnit() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new StringTask());
        try {
            e.invokeAny(l, MEDIUM_DELAY_MS, null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(empty collection) throws IAE
     */
    public void testTimedInvokeAny2() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            e.invokeAny(new ArrayList<Callable<String>>(), MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (IllegalArgumentException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(c) throws NPE if c has null elements
     */
    public void testTimedInvokeAny3() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(latchAwaitingStringTask(latch));
        l.add(null);
        try {
            e.invokeAny(l, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            latch.countDown();
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(c) throws ExecutionException if no task completes
     */
    public void testTimedInvokeAny4() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new NPETask());
        try {
            e.invokeAny(l, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (ExecutionException success) {
            assertTrue(success.getCause() instanceof NullPointerException);
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(c) returns result of some task
     */
    public void testTimedInvokeAny5() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Callable<String>> l = new ArrayList<Callable<String>>();
            l.add(new StringTask());
            l.add(new StringTask());
            String result = e.invokeAny(l, MEDIUM_DELAY_MS, MILLISECONDS);
            assertSame(TEST_STRING, result);
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(null) throws NPE
     */
    public void testTimedInvokeAll1() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            e.invokeAll(null, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(,,null) throws NPE
     */
    public void testTimedInvokeAllNullTimeUnit() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new StringTask());
        try {
            e.invokeAll(l, MEDIUM_DELAY_MS, null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(empty collection) returns empty collection
     */
    public void testTimedInvokeAll2() throws InterruptedException {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Future<String>> r = e.invokeAll(new ArrayList<Callable<String>>(), MEDIUM_DELAY_MS, MILLISECONDS);
            assertTrue(r.isEmpty());
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(c) throws NPE if c has null elements
     */
    public void testTimedInvokeAll3() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new StringTask());
        l.add(null);
        try {
            e.invokeAll(l, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(e);
        }
    }

    /**
     * get of element of invokeAll(c) throws exception on failed task
     */
    public void testTimedInvokeAll4() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new NPETask());
        List<Future<String>> futures =
            e.invokeAll(l, MEDIUM_DELAY_MS, MILLISECONDS);
        assertEquals(1, futures.size());
        try {
            futures.get(0).get();
            shouldThrow();
        } catch (ExecutionException success) {
            assertTrue(success.getCause() instanceof NullPointerException);
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(c) returns results of all completed tasks
     */
    public void testTimedInvokeAll5() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Callable<String>> l = new ArrayList<Callable<String>>();
            l.add(new StringTask());
            l.add(new StringTask());
            List<Future<String>> futures =
                e.invokeAll(l, MEDIUM_DELAY_MS, MILLISECONDS);
            assertEquals(2, futures.size());
            for (Future<String> future : futures)
                assertSame(TEST_STRING, future.get());
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(c) cancels tasks not completed by timeout
     */
    public void testTimedInvokeAll6() throws Exception {
        ExecutorService e =
            new ThreadPoolExecutor(2, 2,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        try {
            List<Callable<String>> l = new ArrayList<Callable<String>>();
            l.add(new StringTask());
            l.add(Executors.callable(new MediumPossiblyInterruptedRunnable(), TEST_STRING));
            l.add(new StringTask());
            List<Future<String>> futures =
                e.invokeAll(l, SHORT_DELAY_MS, MILLISECONDS);
            assertEquals(l.size(), futures.size());
            for (Future future : futures)
                assertTrue(future.isDone());
            assertFalse(futures.get(0).isCancelled());
            assertTrue(futures.get(1).isCancelled());
        } finally {
            joinPool(e);
        }
    }

    /**
     * Execution continues if there is at least one thread even if
     * thread factory fails to create more
     */
    public void testFailingThreadFactory() throws InterruptedException {
        final ExecutorService e =
            new ThreadPoolExecutor(100, 100,
                                   LONG_DELAY_MS, MILLISECONDS,
                                   new LinkedBlockingQueue<Runnable>(),
                                   new FailingThreadFactory());
        try {
            final int TASKS = 100;
            final CountDownLatch done = new CountDownLatch(TASKS);
            for (int k = 0; k < TASKS; ++k)
                e.execute(new CheckedRunnable() {
                    public void realRun() {
                        done.countDown();
                    }});
            assertTrue(done.await(LONG_DELAY_MS, MILLISECONDS));
        } finally {
            joinPool(e);
        }
    }

    /**
     * allowsCoreThreadTimeOut is by default false.
     */
    public void testAllowsCoreThreadTimeOut() {
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 2,
                                   1000, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        assertFalse(p.allowsCoreThreadTimeOut());
        joinPool(p);
    }

    /**
     * allowCoreThreadTimeOut(true) causes idle threads to time out
     */
    public void testAllowCoreThreadTimeOut_true() throws Exception {
        long coreThreadTimeOut = SHORT_DELAY_MS;
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 10,
                                   coreThreadTimeOut, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        try {
            p.allowCoreThreadTimeOut(true);
            p.execute(new CheckedRunnable() {
                public void realRun() {
                    threadStarted.countDown();
                    assertEquals(1, p.getPoolSize());
                }});
            await(threadStarted);
            delay(coreThreadTimeOut);
            long startTime = System.nanoTime();
            while (p.getPoolSize() > 0
                   && millisElapsedSince(startTime) < LONG_DELAY_MS)
                Thread.yield();
            assertTrue(millisElapsedSince(startTime) < LONG_DELAY_MS);
            assertEquals(0, p.getPoolSize());
        } finally {
            joinPool(p);
        }
    }

    /**
     * allowCoreThreadTimeOut(false) causes idle threads not to time out
     */
    public void testAllowCoreThreadTimeOut_false() throws Exception {
        long coreThreadTimeOut = SHORT_DELAY_MS;
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(2, 10,
                                   coreThreadTimeOut, MILLISECONDS,
                                   new ArrayBlockingQueue<Runnable>(10));
        final CountDownLatch threadStarted = new CountDownLatch(1);
        try {
            p.allowCoreThreadTimeOut(false);
            p.execute(new CheckedRunnable() {
                public void realRun() throws InterruptedException {
                    threadStarted.countDown();
                    assertTrue(p.getPoolSize() >= 1);
                }});
            delay(2 * coreThreadTimeOut);
            assertTrue(p.getPoolSize() >= 1);
        } finally {
            joinPool(p);
        }
    }

    /**
     * execute allows the same task to be submitted multiple times, even
     * if rejected
     */
    public void testRejectedRecycledTask() throws InterruptedException {
        final int nTasks = 1000;
        final CountDownLatch done = new CountDownLatch(nTasks);
        final Runnable recycledTask = new Runnable() {
            public void run() {
                done.countDown();
            }};
        final ThreadPoolExecutor p =
            new ThreadPoolExecutor(1, 30, 60, TimeUnit.SECONDS,
                                   new ArrayBlockingQueue(30));
        try {
            for (int i = 0; i < nTasks; ++i) {
                for (;;) {
                    try {
                        p.execute(recycledTask);
                        break;
                    }
                    catch (RejectedExecutionException ignore) {}
                }
            }
            // enough time to run all tasks
            assertTrue(done.await(nTasks * SHORT_DELAY_MS, MILLISECONDS));
        } finally {
            joinPool(p);
        }
    }

}
