package org.pgledger.database.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Runs callables at the same time on platform threads (a Spring {@code SimpleAsyncTaskExecutor}: one new thread per
 * task) and collects their results. Platform threads, because a blocked JDBC call pins a virtual thread's carrier and
 * a test JVM with one processor would then never let the lock holder finish.
 */
public final class Parallel {

    private Parallel() {
    }

    /** Runs all tasks concurrently; a task that fails makes the call fail with its cause. */
    public static <T> List<T> all(List<Callable<T>> tasks) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("parallel-");
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (ExecutionException e) {
            throw new IllegalStateException("a parallel task failed", e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException("parallel run did not finish", e);
        }
    }

    /** Starts {@code task} on its own thread and returns the future. */
    public static <T> Future<T> start(Callable<T> task) {
        return new SimpleAsyncTaskExecutor("parallel-").submit(task);
    }
}
