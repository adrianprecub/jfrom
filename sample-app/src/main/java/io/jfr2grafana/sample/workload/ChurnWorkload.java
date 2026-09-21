package io.jfr2grafana.sample.workload;

import org.springframework.stereotype.Component;

/**
 * Allocation storm: allocate and immediately discard, driving young-gen GC
 * and allocation rate ({@code jdk.ObjectAllocationSample}, {@code jdk.GCHeapSummary}).
 */
@Component
public class ChurnWorkload {

    /** Hard cap so a misbehaving caller cannot turn this into an indefinite hang. */
    static final long MAX_DURATION_MS = 10_000;
    static final int MIN_INTENSITY = 1;
    static final int MAX_INTENSITY = 10;
    private static final int BASE_CHUNK_BYTES = 64 * 1024; // 64 KiB per intensity step

    // Volatile sink defeats dead-code elimination of the "discarded" allocation.
    private volatile byte[] sink;

    public ChurnResult run(int intensity, long durationMs) {
        int clampedIntensity = clamp(intensity, MIN_INTENSITY, MAX_INTENSITY);
        long clampedDuration = clamp(durationMs, 0, MAX_DURATION_MS);
        int chunkBytes = clampedIntensity * BASE_CHUNK_BYTES;

        long deadline = System.nanoTime() + clampedDuration * 1_000_000L;
        long allocations = 0;
        long bytes = 0;
        do {
            byte[] garbage = new byte[chunkBytes];
            garbage[0] = 1; // touch it so it isn't optimized away
            sink = garbage;
            allocations++;
            bytes += chunkBytes;
        } while (System.nanoTime() < deadline);

        return new ChurnResult(clampedIntensity, clampedDuration, allocations, bytes);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    public record ChurnResult(int intensity, long durationMs, long allocations, long bytesAllocated) {
    }
}
