package io.jfrom.sample.workload;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * A hot compute loop across several threads, driving {@code jdk.CPULoad} and
 * JIT compilation ({@code jdk.Compilation}).
 */
@Component
public class CpuWorkload {

    static final int MIN_THREADS = 1;
    static final int MAX_THREADS = 8;
    static final long MAX_DURATION_MS = 10_000;
    private static final int INNER_ITERATIONS = 5000;

    // Volatile sink keeps the computed result "alive" so the JIT cannot elide the loop.
    private volatile double sink;

    public CpuResult run(int threads, long durationMs) {
        int clampedThreads = clamp(threads, MIN_THREADS, MAX_THREADS);
        long clampedDuration = clamp(durationMs, 0, MAX_DURATION_MS);
        long deadline = System.nanoTime() + clampedDuration * 1_000_000L;
        LongAdder iterations = new LongAdder();

        ExecutorService pool = Executors.newFixedThreadPool(clampedThreads);
        try {
            for (int i = 0; i < clampedThreads; i++) {
                pool.submit(() -> hotLoop(deadline, iterations));
            }
            pool.shutdown();
            pool.awaitTermination(clampedDuration + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return new CpuResult(clampedThreads, clampedDuration, iterations.sum());
    }

    private void hotLoop(long deadline, LongAdder iterations) {
        double acc = 0;
        do {
            for (int j = 1; j < INNER_ITERATIONS; j++) {
                acc += Math.sqrt(j) * Math.sin(j);
            }
            iterations.increment();
        } while (System.nanoTime() < deadline);
        sink = acc;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    public record CpuResult(int threads, long durationMs, long iterations) {
    }
}
