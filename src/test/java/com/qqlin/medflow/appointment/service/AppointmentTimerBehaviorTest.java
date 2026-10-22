package com.qqlin.medflow.appointment.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real JDK executor behavior only. These tests do not prove database transactions,
 * process crash recovery, heap usage, or production throughput. MySQL and child
 * JVM experiments cover the corresponding appointment lifecycle boundaries.
 */
class AppointmentTimerBehaviorTest {

    @Test
    void oneDelayedTaskExecutesOnce() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        try {
            AtomicInteger executions = new AtomicInteger();
            CountDownLatch invoked = new CountDownLatch(1);

            ScheduledFuture<?> task = executor.schedule(() -> {
                executions.incrementAndGet();
                invoked.countDown();
            }, 100, TimeUnit.MILLISECONDS);

            assertTrue(invoked.await(2, TimeUnit.SECONDS), "delayed callback did not run");
            task.get(2, TimeUnit.SECONDS);
            assertEquals(1, executions.get());
            assertTrue(task.isDone());
            assertTrue(executor.getQueue().isEmpty());
        } finally {
            stop(executor);
        }
    }

    @Test
    void oneShotTaskFailureCompletesFutureExceptionallyWithoutAutomaticRetry() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        try {
            AtomicInteger executions = new AtomicInteger();
            ScheduledFuture<?> task = executor.schedule(() -> {
                executions.incrementAndGet();
                throw new IllegalStateException("database temporarily unavailable");
            }, 100, TimeUnit.MILLISECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> task.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("database temporarily unavailable", failure.getCause().getMessage());
            assertTrue(task.isDone());

            // The executor remains usable. A later callback completes, while the
            // failed one-shot future is terminal and has not been re-enqueued.
            executor.schedule(() -> { }, 100, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
            assertEquals(1, executions.get());
            assertTrue(executor.getQueue().isEmpty());
        } finally {
            stop(executor);
        }
    }

    @Test
    void shutdownNowDiscardsPendingTaskAndNewExecutorDoesNotRestoreIt() throws Exception {
        ScheduledThreadPoolExecutor original = new ScheduledThreadPoolExecutor(2);
        ScheduledThreadPoolExecutor replacement = null;
        ScheduledFuture<?> pending = null;
        try {
            AtomicInteger appointmentCallbacks = new AtomicInteger();
            pending = original.schedule(appointmentCallbacks::incrementAndGet,
                    1, TimeUnit.DAYS);
            assertEquals(1, original.getQueue().size());

            List<Runnable> discarded = original.shutdownNow();
            assertEquals(1, discarded.size());
            assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, appointmentCallbacks.get());

            // This is executor destruction/recreation in one JVM. Actual process
            // stop/restart must be tested independently with a child JVM.
            replacement = new ScheduledThreadPoolExecutor(2);
            assertTrue(replacement.getQueue().isEmpty());
            CountDownLatch replacementIsRunning = new CountDownLatch(1);
            replacement.schedule(replacementIsRunning::countDown,
                    100, TimeUnit.MILLISECONDS);
            assertTrue(replacementIsRunning.await(2, TimeUnit.SECONDS));
            assertEquals(0, appointmentCallbacks.get());
        } finally {
            if (pending != null) {
                pending.cancel(false);
            }
            stop(original);
            if (replacement != null) {
                stop(replacement);
            }
        }
    }

    @Test
    void tenThousandFutureTasksShareTwoThreadsRatherThanCreatingOneThreadPerTask() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        try {
            AtomicInteger executions = new AtomicInteger();
            for (int index = 0; index < 10_000; index++) {
                executor.schedule(executions::incrementAndGet, 1, TimeUnit.DAYS);
            }

            assertEquals(10_000, executor.getQueue().size());
            assertTrue(executor.getPoolSize() <= 2, "scheduled tasks must share the thread pool");
            assertEquals(0, executions.get());
            // Queue count and worker count say nothing about bytes per task or QPS.
        } finally {
            stop(executor);
        }
    }

    @Test
    void cancelWithRemoveOnCancelPolicyRemovesFutureTaskFromQueue() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
        try {
            AtomicInteger executions = new AtomicInteger();
            assertFalse(executor.getRemoveOnCancelPolicy(), "JDK default keeps cancelled delayed tasks queued");
            executor.setRemoveOnCancelPolicy(true);
            ScheduledFuture<?> task = executor.schedule(executions::incrementAndGet,
                    1, TimeUnit.DAYS);
            assertEquals(1, executor.getQueue().size());

            assertTrue(task.cancel(false));
            assertTrue(task.isCancelled());
            assertTrue(executor.getQueue().isEmpty());
            assertEquals(0, executions.get());
        } finally {
            stop(executor);
        }
    }

    private static void stop(ScheduledThreadPoolExecutor executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "test executor did not terminate");
    }
}
