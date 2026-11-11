package org.pgledger.application.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs callables at the same time on platform threads (Spring task executors) and collects their outcomes.
 * Platform threads, because a blocked JDBC call pins a virtual thread's carrier and a test JVM with one processor
 * would then never let the lock holder finish.
 */
public final class Parallel {

    /** What one task ended with: its value or the exception it threw. */
    public record Outcome<T>(T value, Throwable failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    private Parallel() {
    }

    /** Runs all tasks concurrently (all start together) and returns the outcome of each, in task order. */
    public static <T> List<Outcome<T>> all(List<Callable<T>> tasks) {
        return all(tasks, tasks.size());
    }

    /** Same with at most {@code threads} tasks running at a time. */
    public static <T> List<Outcome<T>> all(List<Callable<T>> tasks, int threads) {
        CountDownLatch start = new CountDownLatch(1);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setThreadNamePrefix("parallel-");
        executor.initialize();
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Exception | AssertionError e) {
                        return new Outcome<>(null, e);
                    }
                }));
            }
            start.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                results.add(future.get(180, TimeUnit.SECONDS));
            }
            return results;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("parallel run did not finish", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parallel run interrupted", e);
        } finally {
            executor.shutdown();
        }
    }

    /** Starts the task on its own thread and returns the future. */
    public static <T> Future<T> start(Callable<T> task) {
        return new SimpleAsyncTaskExecutor("parallel-").submit(task);
    }
}
