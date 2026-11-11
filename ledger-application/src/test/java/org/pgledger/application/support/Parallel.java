package org.pgledger.application.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs callables at the same time on real threads and collects their outcomes. */
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
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
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
        }
    }

    /** Starts the task on its own thread and returns the future. */
    public static <T> Future<T> start(Callable<T> task) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(task);
        executor.shutdown();
        return future;
    }
}
