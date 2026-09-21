package io.jfr2grafana.sample.workload;

import io.jfr2grafana.sample.config.LeakProperties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Slow retained growth into a long-lived collection, so old-gen occupancy
 * creeps up across GCs. Bounded by {@link LeakProperties#getMaxEntries()} so a
 * long-running demo cannot OOM the container, and resettable via {@link #reset()}.
 */
@Component
public class LeakWorkload {

    private final LeakProperties properties;
    private final List<byte[]> retained = new CopyOnWriteArrayList<>();

    public LeakWorkload(LeakProperties properties) {
        this.properties = properties;
    }

    /** Adds up to {@code amount} chunks, silently stopping once the cap is reached. */
    public LeakResult grow(int amount) {
        int clampedAmount = Math.max(0, amount);
        int added = 0;
        for (int i = 0; i < clampedAmount; i++) {
            if (retained.size() >= properties.getMaxEntries()) {
                break;
            }
            retained.add(new byte[properties.getChunkSizeBytes()]);
            added++;
        }
        return new LeakResult(retained.size(), properties.getMaxEntries(), added);
    }

    /** Drops every retained reference so the collection can be reclaimed by the next GC. */
    public void reset() {
        retained.clear();
    }

    public int size() {
        return retained.size();
    }

    public int maxEntries() {
        return properties.getMaxEntries();
    }

    public record LeakResult(int currentSize, int maxEntries, int added) {
    }
}
