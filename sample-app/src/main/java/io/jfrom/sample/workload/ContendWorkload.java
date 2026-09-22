package io.jfrom.sample.workload;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Lock contention: a pool of threads contending on one monitor, each holding
 * it a few ms. With enough threads queued behind a holder, individual entries
 * comfortably exceed a 1ms wait, producing {@code jdk.JavaMonitorEnter} events
 * above the agent's threshold.
 */
@Component
public class ContendWorkload {

    static final int MIN_THREADS = 2;
    static final int MAX_THREADS = 16;
    static final int MIN_HOLD_MS = 1;
    static final int MAX_HOLD_MS = 50;
    static final long MAX_DURATION_MS = 10_000;

    private final Object contendedMonitor = new Object();

    public ContendResult run(int threads, int holdMs, long durationMs) {
        int clampedThreads = clamp(threads, MIN_THREADS, MAX_THREADS);
        int clampedHold = clamp(holdMs, MIN_HOLD_MS, MAX_HOLD_MS);
        long clampedDuration = clamp(durationMs, 0, MAX_DURATION_MS);

        long deadline = System.nanoTime() + clampedDuration * 1_000_000L;
        AtomicLong monitorEntries = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(clampedThreads);
        CountDownLatch done = new CountDownLatch(clampedThreads);
        try {
            for (int i = 0; i < clampedThreads; i++) {
                pool.submit(() -> {
                    try {
                        while (System.nanoTime() < deadline) {
                            synchronized (contendedMonitor) {
                                monitorEntries.incrementAndGet();
                                try {
                                    Thread.sleep(clampedHold);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(clampedDuration + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return new ContendResult(clampedThreads, clampedHold, clampedDuration, monitorEntries.get());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    public record ContendResult(int threads, int holdMs, long durationMs, long monitorEntries) {
    }
}
