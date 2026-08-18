package org.example.takeout.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发测试的通用执行模板：等待所有线程就绪后统一放行，并负责超时、异常传播和线程池回收。
 */
public final class ConcurrentTestTemplate {

    private ConcurrentTestTemplate() {
    }

    /**
     * 使用两个线程同时执行两个不同任务，并按任务顺序返回结果。
     */
    public static <FirstResult, SecondResult> TwoTaskResult<FirstResult, SecondResult> runTwoTasks(
            Duration timeout,
            ThrowingSupplier<FirstResult> firstTask,
            ThrowingSupplier<SecondResult> secondTask
    ) {
        AtomicReference<FirstResult> firstResult = new AtomicReference<>();
        AtomicReference<SecondResult> secondResult = new AtomicReference<>();

        runConcurrently(
                2,
                timeout,
                workerIndex -> {
                    if (workerIndex == 0) {
                        firstResult.set(firstTask.get());
                    } else {
                        secondResult.set(secondTask.get());
                    }
                }
        );

        return new TwoTaskResult<>(firstResult.get(), secondResult.get());
    }

    /**
     * 创建一个可命名、带超时的并发阶段信号，替代测试中重复的 CountDownLatch 等待代码。
     */
    public static Checkpoint checkpoint(String description, Duration timeout) {
        return new Checkpoint(description, timeout);
    }

    /**
     * 使用指定数量的线程同时执行同一种任务，workerIndex 用于区分每个线程。
     */
    public static void runConcurrently(
            int threadCount,
            Duration timeout,
            IndexedTask task
    ) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be greater than zero");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(threadCount);

        try {
            for (int workerIndex = 0; workerIndex < threadCount; workerIndex++) {
                int currentWorkerIndex = workerIndex;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    task.run(currentWorkerIndex);
                    return null;
                }));
            }

            awaitReady(ready, timeout);
            start.countDown();
            awaitCompletion(futures, timeout);
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private static void awaitReady(CountDownLatch ready, Duration timeout) {
        try {
            if (!ready.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new AssertionError("并发线程未在规定时间内准备就绪");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发线程就绪时被中断", exception);
        }
    }

    private static void awaitCompletion(List<Future<?>> futures, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Throwable> failures = new ArrayList<>();

        for (Future<?> future : futures) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new AssertionError("并发任务未在规定时间内执行完成");
            }

            try {
                future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
            } catch (TimeoutException exception) {
                throw new AssertionError("并发任务未在规定时间内执行完成", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待并发任务完成时被中断", exception);
            }
        }

        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("并发任务执行失败", failures.get(0));
            failures.stream().skip(1).forEach(error::addSuppressed);
            throw error;
        }
    }

    @FunctionalInterface
    public interface IndexedTask {
        void run(int workerIndex) throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public record TwoTaskResult<FirstResult, SecondResult>(
            FirstResult firstResult,
            SecondResult secondResult
    ) {
    }

    public static final class Checkpoint {

        private final String description;
        private final Duration timeout;
        private final CountDownLatch reached = new CountDownLatch(1);

        private Checkpoint(String description, Duration timeout) {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description must not be blank");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.description = description;
            this.timeout = timeout;
        }

        public void signal() {
            reached.countDown();
        }

        public void awaitSignal() {
            try {
                if (!reached.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new AssertionError(description + " 未在规定时间内发生");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待“" + description + "”时被中断", exception);
            }
        }
    }
}
