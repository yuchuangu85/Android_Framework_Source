/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 * Other contributors include Andrew Wright, Jeffrey Hayes,
 * Pat Fisher, Mike Judd.
 */

package jsr166;

import junit.framework.*;
import java.util.*;
import java.util.concurrent.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import java.util.concurrent.atomic.AtomicInteger;

public class ScheduledExecutorTest extends JSR166TestCase {

    /**
     * execute successfully executes a runnable
     */
    public void testExecute() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
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
     * delayed schedule of callable successfully executes after delay
     */
    public void testSchedule1() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final long startTime = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Callable task = new CheckedCallable<Boolean>() {
                public Boolean realCall() {
                    done.countDown();
                    assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
                    return Boolean.TRUE;
                }};
            Future f = p.schedule(task, timeoutMillis(), MILLISECONDS);
            assertSame(Boolean.TRUE, f.get());
            assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
            assertTrue(done.await(0L, MILLISECONDS));
        } finally {
            joinPool(p);
        }
    }

    /**
     * delayed schedule of runnable successfully executes after delay
     */
    public void testSchedule3() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final long startTime = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Runnable task = new CheckedRunnable() {
                public void realRun() {
                    done.countDown();
                    assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
                }};
            Future f = p.schedule(task, timeoutMillis(), MILLISECONDS);
            await(done);
            assertNull(f.get(LONG_DELAY_MS, MILLISECONDS));
            assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
        } finally {
            joinPool(p);
        }
    }

    /**
     * scheduleAtFixedRate executes runnable after given initial delay
     */
    public void testSchedule4() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final long startTime = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Runnable task = new CheckedRunnable() {
                public void realRun() {
                    done.countDown();
                    assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
                }};
            ScheduledFuture f =
                p.scheduleAtFixedRate(task, timeoutMillis(),
                                      LONG_DELAY_MS, MILLISECONDS);
            await(done);
            assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
            f.cancel(true);
        } finally {
            joinPool(p);
        }
    }

    /**
     * scheduleWithFixedDelay executes runnable after given initial delay
     */
    public void testSchedule5() throws Exception {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final long startTime = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            Runnable task = new CheckedRunnable() {
                public void realRun() {
                    done.countDown();
                    assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
                }};
            ScheduledFuture f =
                p.scheduleWithFixedDelay(task, timeoutMillis(),
                                         LONG_DELAY_MS, MILLISECONDS);
            await(done);
            assertTrue(millisElapsedSince(startTime) >= timeoutMillis());
            f.cancel(true);
        } finally {
            joinPool(p);
        }
    }

    static class RunnableCounter implements Runnable {
        AtomicInteger count = new AtomicInteger(0);
        public void run() { count.getAndIncrement(); }
    }

    /**
     * scheduleAtFixedRate executes series of tasks at given rate
     */
    public void testFixedRateSequence() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        RunnableCounter counter = new RunnableCounter();
        ScheduledFuture h =
            p.scheduleAtFixedRate(counter, 0, 1, MILLISECONDS);
        delay(SMALL_DELAY_MS);
        h.cancel(true);
        int c = counter.count.get();
        // By time scaling conventions, we must have at least
        // an execution per SHORT delay, but no more than one SHORT more
        assertTrue(c >= SMALL_DELAY_MS / SHORT_DELAY_MS);
        assertTrue(c <= SMALL_DELAY_MS + SHORT_DELAY_MS);
        joinPool(p);
    }

    /**
     * scheduleWithFixedDelay executes series of tasks with given period
     */
    public void testFixedDelaySequence() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        RunnableCounter counter = new RunnableCounter();
        ScheduledFuture h =
            p.scheduleWithFixedDelay(counter, 0, 1, MILLISECONDS);
        delay(SMALL_DELAY_MS);
        h.cancel(true);
        int c = counter.count.get();
        assertTrue(c >= SMALL_DELAY_MS / SHORT_DELAY_MS);
        assertTrue(c <= SMALL_DELAY_MS + SHORT_DELAY_MS);
        joinPool(p);
    }

    /**
     * execute(null) throws NPE
     */
    public void testExecuteNull() throws InterruptedException {
        ScheduledThreadPoolExecutor se = null;
        try {
            se = new ScheduledThreadPoolExecutor(1);
            se.execute(null);
            shouldThrow();
        } catch (NullPointerException success) {}

        joinPool(se);
    }

    /**
     * schedule(null) throws NPE
     */
    public void testScheduleNull() throws InterruptedException {
        ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1);
        try {
            TrackedCallable callable = null;
            Future f = se.schedule(callable, SHORT_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (NullPointerException success) {}
        joinPool(se);
    }

    /**
     * execute throws RejectedExecutionException if shutdown
     */
    public void testSchedule1_RejectedExecutionException() throws InterruptedException {
        ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1);
        try {
            se.shutdown();
            se.schedule(new NoOpRunnable(),
                        MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (RejectedExecutionException success) {
        } catch (SecurityException ok) {
        }

        joinPool(se);
    }

    /**
     * schedule throws RejectedExecutionException if shutdown
     */
    public void testSchedule2_RejectedExecutionException() throws InterruptedException {
        ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1);
        try {
            se.shutdown();
            se.schedule(new NoOpCallable(),
                        MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (RejectedExecutionException success) {
        } catch (SecurityException ok) {
        }
        joinPool(se);
    }

    /**
     * schedule callable throws RejectedExecutionException if shutdown
     */
    public void testSchedule3_RejectedExecutionException() throws InterruptedException {
        ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1);
        try {
            se.shutdown();
            se.schedule(new NoOpCallable(),
                        MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (RejectedExecutionException success) {
        } catch (SecurityException ok) {
        }
        joinPool(se);
    }

    /**
     * scheduleAtFixedRate throws RejectedExecutionException if shutdown
     */
    public void testScheduleAtFixedRate1_RejectedExecutionException() throws InterruptedException {
        ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1);
        try {
            se.shutdown();
            se.scheduleAtFixedRate(new NoOpRunnable(),
                                   MEDIUM_DELAY_MS, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (RejectedExecutionException success) {
        } catch (SecurityException ok) {
        }
        joinPool(se);
    }

    /**
     * scheduleWithFixedDelay throws RejectedExecutionException if shutdown
     */
    public void testScheduleWithFixedDelay1_RejectedExecutionException() throws InterruptedException {
        ScheduledThreadPoolExecutor se = new ScheduledThreadPoolExecutor(1);
        try {
            se.shutdown();
            se.scheduleWithFixedDelay(new NoOpRunnable(),
                                      MEDIUM_DELAY_MS, MEDIUM_DELAY_MS, MILLISECONDS);
            shouldThrow();
        } catch (RejectedExecutionException success) {
        } catch (SecurityException ok) {
        }
        joinPool(se);
    }

    /**
     * getActiveCount increases but doesn't overestimate, when a
     * thread becomes active
     */
    public void testGetActiveCount() throws InterruptedException {
        final ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(2);
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
     * getCompletedTaskCount increases, but doesn't overestimate,
     * when tasks complete
     */
    public void testGetCompletedTaskCount() throws InterruptedException {
        final ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(2);
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
    public void testGetCorePoolSize() throws InterruptedException {
        ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        assertEquals(1, p.getCorePoolSize());
        joinPool(p);
    }

    /**
     * getLargestPoolSize increases, but doesn't overestimate, when
     * multiple threads active
     */
    public void testGetLargestPoolSize() throws InterruptedException {
        final int THREADS = 3;
        final ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(THREADS);
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
     * getPoolSize increases, but doesn't overestimate, when threads
     * become active
     */
    public void testGetPoolSize() throws InterruptedException {
        final ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
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
     * getTaskCount increases, but doesn't overestimate, when tasks
     * submitted
     */
    public void testGetTaskCount() throws InterruptedException {
        final ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final int TASKS = 5;
        try {
            assertEquals(0, p.getTaskCount());
            for (int i = 0; i < TASKS; i++)
                p.execute(new CheckedRunnable() {
                    public void realRun() throws InterruptedException {
                        threadStarted.countDown();
                        done.await();
                    }});
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            assertEquals(TASKS, p.getTaskCount());
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * getThreadFactory returns factory in constructor if not set
     */
    public void testGetThreadFactory() throws InterruptedException {
        ThreadFactory tf = new SimpleThreadFactory();
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1, tf);
        assertSame(tf, p.getThreadFactory());
        joinPool(p);
    }

    /**
     * setThreadFactory sets the thread factory returned by getThreadFactory
     */
    public void testSetThreadFactory() throws InterruptedException {
        ThreadFactory tf = new SimpleThreadFactory();
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        p.setThreadFactory(tf);
        assertSame(tf, p.getThreadFactory());
        joinPool(p);
    }

    /**
     * setThreadFactory(null) throws NPE
     */
    public void testSetThreadFactoryNull() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        try {
            p.setThreadFactory(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(p);
        }
    }

    /**
     * isShutdown is false before shutdown, true after
     */
    public void testIsShutdown() {

        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        try {
            assertFalse(p.isShutdown());
        }
        finally {
            try { p.shutdown(); } catch (SecurityException ok) { return; }
        }
        assertTrue(p.isShutdown());
    }

    /**
     * isTerminated is false before termination, true after
     */
    public void testIsTerminated() throws InterruptedException {
        final ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
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
        final ThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
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
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            ScheduledFuture[] tasks = new ScheduledFuture[5];
            for (int i = 0; i < tasks.length; i++) {
                Runnable r = new CheckedRunnable() {
                    public void realRun() throws InterruptedException {
                        threadStarted.countDown();
                        done.await();
                    }};
                tasks[i] = p.schedule(r, 1, MILLISECONDS);
            }
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            BlockingQueue<Runnable> q = p.getQueue();
            assertTrue(q.contains(tasks[tasks.length - 1]));
            assertFalse(q.contains(tasks[0]));
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * remove(task) removes queued task, and fails to remove active task
     */
    public void testRemove() throws InterruptedException {
        final ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture[] tasks = new ScheduledFuture[5];
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            for (int i = 0; i < tasks.length; i++) {
                Runnable r = new CheckedRunnable() {
                    public void realRun() throws InterruptedException {
                        threadStarted.countDown();
                        done.await();
                    }};
                tasks[i] = p.schedule(r, 1, MILLISECONDS);
            }
            assertTrue(threadStarted.await(SMALL_DELAY_MS, MILLISECONDS));
            BlockingQueue<Runnable> q = p.getQueue();
            assertFalse(p.remove((Runnable)tasks[0]));
            assertTrue(q.contains((Runnable)tasks[4]));
            assertTrue(q.contains((Runnable)tasks[3]));
            assertTrue(p.remove((Runnable)tasks[4]));
            assertFalse(p.remove((Runnable)tasks[4]));
            assertFalse(q.contains((Runnable)tasks[4]));
            assertTrue(q.contains((Runnable)tasks[3]));
            assertTrue(p.remove((Runnable)tasks[3]));
            assertFalse(q.contains((Runnable)tasks[3]));
        } finally {
            done.countDown();
            joinPool(p);
        }
    }

    /**
     * purge eventually removes cancelled tasks from the queue
     */
    public void testPurge() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        ScheduledFuture[] tasks = new ScheduledFuture[5];
        for (int i = 0; i < tasks.length; i++)
            tasks[i] = p.schedule(new SmallPossiblyInterruptedRunnable(),
                                  LONG_DELAY_MS, MILLISECONDS);
        try {
            int max = tasks.length;
            if (tasks[4].cancel(true)) --max;
            if (tasks[3].cancel(true)) --max;
            // There must eventually be an interference-free point at
            // which purge will not fail. (At worst, when queue is empty.)
            long startTime = System.nanoTime();
            do {
                p.purge();
                long count = p.getTaskCount();
                if (count == max)
                    return;
            } while (millisElapsedSince(startTime) < MEDIUM_DELAY_MS);
            fail("Purge failed to remove cancelled tasks");
        } finally {
            for (ScheduledFuture task : tasks)
                task.cancel(true);
            joinPool(p);
        }
    }

    /**
     * shutdownNow returns a list containing tasks that were not run
     */
    public void testShutdownNow() {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        for (int i = 0; i < 5; i++)
            p.schedule(new SmallPossiblyInterruptedRunnable(),
                       LONG_DELAY_MS, MILLISECONDS);
        try {
            List<Runnable> l = p.shutdownNow();
            assertTrue(p.isShutdown());
            assertEquals(5, l.size());
        } catch (SecurityException ok) {
            // Allowed in case test doesn't have privs
        } finally {
            joinPool(p);
        }
    }

    /**
     * In default setting, shutdown cancels periodic but not delayed
     * tasks at shutdown
     */
    public void testShutdown1() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        assertTrue(p.getExecuteExistingDelayedTasksAfterShutdownPolicy());
        assertFalse(p.getContinueExistingPeriodicTasksAfterShutdownPolicy());

        ScheduledFuture[] tasks = new ScheduledFuture[5];
        for (int i = 0; i < tasks.length; i++)
            tasks[i] = p.schedule(new NoOpRunnable(),
                                  SHORT_DELAY_MS, MILLISECONDS);
        try { p.shutdown(); } catch (SecurityException ok) { return; }
        BlockingQueue<Runnable> q = p.getQueue();
        for (ScheduledFuture task : tasks) {
            assertFalse(task.isDone());
            assertFalse(task.isCancelled());
            assertTrue(q.contains(task));
        }
        assertTrue(p.isShutdown());
        assertTrue(p.awaitTermination(SMALL_DELAY_MS, MILLISECONDS));
        assertTrue(p.isTerminated());
        for (ScheduledFuture task : tasks) {
            assertTrue(task.isDone());
            assertFalse(task.isCancelled());
        }
    }

    /**
     * If setExecuteExistingDelayedTasksAfterShutdownPolicy is false,
     * delayed tasks are cancelled at shutdown
     */
    public void testShutdown2() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        p.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        assertFalse(p.getExecuteExistingDelayedTasksAfterShutdownPolicy());
        assertFalse(p.getContinueExistingPeriodicTasksAfterShutdownPolicy());
        ScheduledFuture[] tasks = new ScheduledFuture[5];
        for (int i = 0; i < tasks.length; i++)
            tasks[i] = p.schedule(new NoOpRunnable(),
                                  SHORT_DELAY_MS, MILLISECONDS);
        BlockingQueue q = p.getQueue();
        assertEquals(tasks.length, q.size());
        try { p.shutdown(); } catch (SecurityException ok) { return; }
        assertTrue(p.isShutdown());
        assertTrue(q.isEmpty());
        assertTrue(p.awaitTermination(SMALL_DELAY_MS, MILLISECONDS));
        assertTrue(p.isTerminated());
        for (ScheduledFuture task : tasks) {
            assertTrue(task.isDone());
            assertTrue(task.isCancelled());
        }
    }

    /**
     * If setContinueExistingPeriodicTasksAfterShutdownPolicy is set false,
     * periodic tasks are cancelled at shutdown
     */
    public void testShutdown3() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        assertTrue(p.getExecuteExistingDelayedTasksAfterShutdownPolicy());
        assertFalse(p.getContinueExistingPeriodicTasksAfterShutdownPolicy());
        p.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        assertTrue(p.getExecuteExistingDelayedTasksAfterShutdownPolicy());
        assertFalse(p.getContinueExistingPeriodicTasksAfterShutdownPolicy());
        long initialDelay = LONG_DELAY_MS;
        ScheduledFuture task =
            p.scheduleAtFixedRate(new NoOpRunnable(), initialDelay,
                                  5, MILLISECONDS);
        try { p.shutdown(); } catch (SecurityException ok) { return; }
        assertTrue(p.isShutdown());
        assertTrue(p.getQueue().isEmpty());
        assertTrue(task.isDone());
        assertTrue(task.isCancelled());
        joinPool(p);
    }

    /**
     * if setContinueExistingPeriodicTasksAfterShutdownPolicy is true,
     * periodic tasks are not cancelled at shutdown
     */
    public void testShutdown4() throws InterruptedException {
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(1);
        final CountDownLatch counter = new CountDownLatch(2);
        try {
            p.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
            assertTrue(p.getExecuteExistingDelayedTasksAfterShutdownPolicy());
            assertTrue(p.getContinueExistingPeriodicTasksAfterShutdownPolicy());
            final Runnable r = new CheckedRunnable() {
                public void realRun() {
                    counter.countDown();
                }};
            ScheduledFuture task =
                p.scheduleAtFixedRate(r, 1, 1, MILLISECONDS);
            assertFalse(task.isDone());
            assertFalse(task.isCancelled());
            try { p.shutdown(); } catch (SecurityException ok) { return; }
            assertFalse(task.isCancelled());
            assertFalse(p.isTerminated());
            assertTrue(p.isShutdown());
            assertTrue(counter.await(SMALL_DELAY_MS, MILLISECONDS));
            assertFalse(task.isCancelled());
            assertTrue(task.cancel(false));
            assertTrue(task.isDone());
            assertTrue(task.isCancelled());
            assertTrue(p.awaitTermination(SMALL_DELAY_MS, MILLISECONDS));
            assertTrue(p.isTerminated());
        }
        finally {
            joinPool(p);
        }
    }

    /**
     * completed submit of callable returns result
     */
    public void testSubmitCallable() throws Exception {
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
    public void testInvokeAll2() throws Exception {
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
     * get of invokeAll(c) throws exception on failed task
     */
    public void testInvokeAll4() throws Exception {
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
        List<Callable<String>> l = new ArrayList<Callable<String>>();
        l.add(new NPETask());
        List<Future<String>> futures = e.invokeAll(l);
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
     * invokeAll(c) returns results of all completed tasks
     */
    public void testInvokeAll5() throws Exception {
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
    public void testTimedInvokeAll2() throws Exception {
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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
        ExecutorService e = new ScheduledThreadPoolExecutor(2);
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

}
