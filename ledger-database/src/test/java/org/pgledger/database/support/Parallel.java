package org.pgledger.database.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs callables at the same time on virtual threads and collects their results. */
public final class Parallel {

    private Parallel() {
    }

    /** Runs all tasks concurrently; a task that fails makes the call fail with its cause. */
    public static <T> List<T> all(List<Callable<T>> tasks) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(task);
        executor.shutdown();
        return future;
    }
}
