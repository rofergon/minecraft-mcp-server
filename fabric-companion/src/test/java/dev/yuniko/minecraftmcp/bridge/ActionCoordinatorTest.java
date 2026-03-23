package dev.yuniko.minecraftmcp.bridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActionCoordinatorTest {
    @Test
    void readOnlyRunsOffTheCallerThread() throws Exception {
        ExecutorService readExecutor = Executors.newFixedThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "read-test-thread");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService exclusiveExecutor = Executors.newSingleThreadExecutor();

        try {
            ActionCoordinator coordinator = new ActionCoordinator(readExecutor, exclusiveExecutor);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> executionThread = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            String callerThread = Thread.currentThread().getName();

            coordinator.dispatch(
                ActionCoordinator.Mode.READ_ONLY,
                () -> {
                    executionThread.set(Thread.currentThread().getName());
                    completed.countDown();
                },
                error -> {
                    failure.set(error);
                    completed.countDown();
                }
            );

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(failure.get() == null);
            assertNotEquals(callerThread, executionThread.get());
        } finally {
            readExecutor.shutdownNow();
            exclusiveExecutor.shutdownNow();
        }
    }

    @Test
    void exclusiveTasksStaySerialized() throws Exception {
        ExecutorService readExecutor = Executors.newFixedThreadPool(1);
        ExecutorService exclusiveExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "exclusive-test-thread");
            thread.setDaemon(true);
            return thread;
        });

        try {
            ActionCoordinator coordinator = new ActionCoordinator(readExecutor, exclusiveExecutor);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean overlap = new AtomicBoolean(false);
            AtomicBoolean firstRunning = new AtomicBoolean(false);
            AtomicReference<Exception> failure = new AtomicReference<>();

            coordinator.dispatch(
                ActionCoordinator.Mode.EXCLUSIVE,
                () -> {
                    firstRunning.set(true);
                    firstStarted.countDown();
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release first task");
                    }
                    firstRunning.set(false);
                },
                failure::set
            );

            coordinator.dispatch(
                ActionCoordinator.Mode.EXCLUSIVE,
                () -> {
                    if (firstRunning.get()) {
                        overlap.set(true);
                    }
                    secondStarted.countDown();
                },
                failure::set
            );

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(150L);
            assertFalse(overlap.get());
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();

            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertTrue(failure.get() == null);
            assertFalse(overlap.get());
        } finally {
            readExecutor.shutdownNow();
            exclusiveExecutor.shutdownNow();
        }
    }
}
